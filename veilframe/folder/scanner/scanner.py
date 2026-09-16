"""
veilframe.folder.scanner.scanner — Complete directory scanning and intelligence pipeline.
"""

from __future__ import annotations

import os
import time
from collections import defaultdict
from typing import Callable, Dict, List, Optional, Set

from veilframe.folder.classification.classifier import Classifier
from veilframe.folder.classification.scoring import calculate_file_priority
from veilframe.folder.database.folder_db import FolderDatabase
from veilframe.folder.duplicates.duplicate_finder import DuplicateFinder
from veilframe.folder.duplicates.hasher import ParallelHashEngine
from veilframe.folder.models.classification import AIAction, FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.folder_record import FolderRecord
from veilframe.folder.models.scan_result import (
    DuplicateGroup,
    ExtensionStat,
    ScanError,
    ScanResult,
    ScanStats,
)
from veilframe.folder.project_context.ecosystem_detector import (
    detect_ecosystems,
)
from veilframe.folder.project_context.gitignore_parser import GitIgnoreParser
from veilframe.folder.project_context.import_analyzer import extract_imports
from veilframe.folder.project_context.manifest_parser import (
    ParsedManifest,
    parse_manifest,
)
from veilframe.folder.project_context.project_graph import ProjectGraph
from veilframe.folder.rules.registry import RuleRegistry
from veilframe.folder.scanner.cancellation import CancellationToken
from veilframe.folder.scanner.scan_config import ScanConfig, ScanProfile
from veilframe.folder.scanner.walker import DirectoryWalker
from veilframe.folder.security.secret_detector import SecretDetector


class FolderScanner:
    """High-performance directory scanner, classifier, and project context engine."""

    def __init__(self, config: Optional[ScanConfig] = None) -> None:
        self.config = config or ScanConfig()
        self.cancel_token = CancellationToken()
        self._is_running = False

    def cancel(self) -> None:
        """Signal scanner to stop traversal and analysis."""
        self.cancel_token.cancel()

    @property
    def is_cancelled(self) -> bool:
        return self.cancel_token.is_cancelled

    def scan(
        self,
        root_path: str,
        db_path: str = ":memory:",
        on_progress: Optional[Callable[[int, int, str], None]] = None,
        on_file_found: Optional[Callable[[FileRecord], None]] = None,
        on_folder_found: Optional[Callable[[FolderRecord], None]] = None,
        on_error: Optional[Callable[[ScanError], None]] = None,
    ) -> ScanResult:
        """Execute the complete scanning, intelligence, and classification pipeline."""
        self._is_running = True
        start_time = time.time()

        root_abs = os.path.abspath(root_path)
        if not os.path.exists(root_abs):
            raise FileNotFoundError(f"Root path does not exist: {root_abs}")
        if not os.path.isdir(root_abs):
            raise NotADirectoryError(f"Root path is not a directory: {root_abs}")

        # ----------------------------------------------------
        # 1. Project Context Initialization
        # ----------------------------------------------------
        active_ecosystems: Set[str] = set()
        gitignore_parser: Optional[GitIgnoreParser] = None
        manifests: Dict[str, ParsedManifest] = {}
        entry_points: Set[str] = set()
        project_graph = ProjectGraph()

        if self.config.enable_intelligence:
            active_ecosystems = detect_ecosystems(root_abs)
            gitignore_parser = GitIgnoreParser(root_abs)

            # Check root directory for primary package manifests
            for manifest_name in ("package.json", "pyproject.toml", "Cargo.toml", "go.mod", "pom.xml"):
                m_path = os.path.join(root_abs, manifest_name)
                if os.path.exists(m_path):
                    parsed_m = parse_manifest(m_path)
                    if parsed_m:
                        manifests[manifest_name] = parsed_m
                        for ep in parsed_m.entry_points:
                            entry_points.add(ep.replace("\\", "/").strip("/"))

        # ----------------------------------------------------
        # 2. Filesystem Traversal
        # ----------------------------------------------------
        walker = DirectoryWalker(config=self.config, cancel_token=self.cancel_token)
        folders, files, errors = walker.walk(
            root_path=root_abs,
            on_progress=on_progress,
            on_file_found=on_file_found,
            on_folder_found=on_folder_found,
            on_error=on_error,
        )

        if self.cancel_token.is_cancelled:
            return ScanResult(
                config=self.config,
                root_path=root_abs,
                files=files,
                folders=folders,
                errors=errors,
                is_cancelled=True,
            )

        # ----------------------------------------------------
        # 3. Intelligence, Classification, & Graph Construction
        # ----------------------------------------------------
        if self.config.enable_intelligence and files:
            active_ecosystems.update(
                detect_ecosystems(root_abs, file_rel_paths=[f.relative_path for f in files])
            )

        classifier = Classifier(
            registry=RuleRegistry.get_default(),
            gitignore_parser=gitignore_parser,
            project_graph=project_graph,
        )
        secret_detector = SecretDetector() if self.config.scan_secrets else None

        # Step 3a: Register nodes & flag entry points in graph
        for f in files:
            norm_rel = f.relative_path.replace("\\", "/").strip("/")
            is_ep = norm_rel in entry_points or (
                f.name in ("run.py", "main.py", "app.py", "cli.py", "__main__.py", "manage.py", "index.ts", "index.js", "main.go", "main.rs")
                and len(norm_rel.split("/")) <= 2
            )
            f.is_entry_point = is_ep
            project_graph.add_file(norm_rel, is_entry_point=is_ep)

        # Step 3b: Classify files and scan secrets
        total_estimated_tokens = 0
        secret_alerts_count = 0

        for f in files:
            if self.cancel_token.is_cancelled:
                break

            norm_rel = f.relative_path.replace("\\", "/").strip("/")

            # Secret detection
            if secret_detector and not f.is_binary and f.size < 250_000:
                alerts = secret_detector.scan_file(f)
                if alerts:
                    # Sensitive files (e.g. .env, id_rsa) are flagged is_secret = True.
                    # Normal source files retain is_secret = False, with alerts preserved for auditing/redaction.
                    f.secret_alerts = [a.description for a in alerts]
                    secret_alerts_count += len(alerts)

            # Classification
            if self.config.classify_files:
                file_depth = len(norm_rel.split("/")) - 1
                classifier.classify_file(f, active_ecosystems=active_ecosystems, depth=file_depth)

            # Token estimation heuristic (~4 chars/token for code/text)
            if self.config.estimate_tokens and not f.is_binary and f.size > 0:
                f.token_count = max(1, f.size // 4)
                if f.effective_action in (AIAction.INCLUDE, AIAction.SELECTIVE_INCLUDE):
                    total_estimated_tokens += f.token_count

        # Step 3c: Extract imports for text source files into project graph
        if self.config.enable_intelligence and len(files) < 3000:
            for f in files:
                if self.cancel_token.is_cancelled:
                    break
                if f.effective_category == FileCategory.SOURCE and not f.is_binary and 0 < f.size < 150_000:
                    try:
                        with open(f.path, "r", encoding="utf-8", errors="ignore") as src_f:
                            imports = extract_imports(f.path, src_f.read(150_000))
                            for imp in imports:
                                project_graph.add_import(f.relative_path, imp)
                    except OSError:
                        pass

            # Recalculate priority scores with real graph centrality bonus
            for f in files:
                if f.effective_action != AIAction.EXCLUDE:
                    norm_rel = f.relative_path.replace("\\", "/").strip("/")
                    file_depth = len(norm_rel.split("/")) - 1
                    centrality = project_graph.get_centrality_score(norm_rel)
                    f.priority_score = calculate_file_priority(
                        rel_path=norm_rel,
                        category=f.effective_category,
                        is_entry_point=f.is_entry_point,
                        centrality_bonus=centrality,
                        size_bytes=f.size,
                        depth=file_depth,
                    )


        # ----------------------------------------------------
        # 4. Content Hashing & Duplicate Detection
        # ----------------------------------------------------
        if self.config.include_hash and not self.cancel_token.is_cancelled:
            if on_progress:
                on_progress(len(files), len(folders), "Calculating cryptographic hashes...")
            hasher = ParallelHashEngine(
                algorithm=self.config.hash_algorithm,
                max_workers=self.config.effective_hash_workers,
                chunk_size=self.config.chunk_size_bytes,
            )
            hasher.hash_files(files, on_progress=on_progress)

        duplicate_groups: List[DuplicateGroup] = []
        if self.config.detect_duplicates and not self.cancel_token.is_cancelled:
            if on_progress:
                on_progress(len(files), len(folders), "Detecting duplicate files...")
            dupe_finder = DuplicateFinder(
                algorithm=self.config.hash_algorithm,
                workers=self.config.effective_hash_workers,
                staged=self.config.duplicate_staged_hash,
            )
            duplicate_groups = dupe_finder.find_duplicates(files, on_progress=on_progress)

        # ----------------------------------------------------
        # 5. Database Persistence
        # ----------------------------------------------------
        db = FolderDatabase(db_path=db_path)
        db.insert_folders(folders)
        db.insert_files(files)
        db.insert_errors(errors)

        # ----------------------------------------------------
        # 6. Aggregate Summary Metrics
        # ----------------------------------------------------
        duration = time.time() - start_time
        total_size = sum(f.size for f in files)
        speed = len(files) / max(duration, 0.001)
        wasted_bytes = sum(d.wasted_bytes for d in duplicate_groups)

        # Extension stats
        ext_sizes: Dict[str, int] = defaultdict(int)
        ext_counts: Dict[str, int] = defaultdict(int)
        for f in files:
            ext = f.extension or "no_ext"
            ext_sizes[ext] += f.size
            ext_counts[ext] += 1

        ext_distribution: List[ExtensionStat] = []
        for ext, count in sorted(ext_counts.items(), key=lambda x: x[1], reverse=True)[:20]:
            sz = ext_sizes[ext]
            pct_files = (count / max(len(files), 1)) * 100.0
            pct_size = (sz / max(total_size, 1)) * 100.0
            ext_distribution.append(
                ExtensionStat(
                    extension=ext,
                    file_count=count,
                    total_size=sz,
                    percentage_files=pct_files,
                    percentage_size=pct_size,
                )
            )

        cat_dist: Dict[str, int] = defaultdict(int)
        act_dist: Dict[str, int] = defaultdict(int)
        lang_dist: Dict[str, int] = defaultdict(int)
        inc_count = 0
        exc_count = 0

        for f in files:
            cat_dist[f.effective_category.value] += 1
            act_dist[f.effective_action.value] += 1
            if f.language:
                lang_dist[f.language] += 1
            if f.effective_action in (AIAction.INCLUDE, AIAction.SELECTIVE_INCLUDE):
                inc_count += 1
            else:
                exc_count += 1

        sorted_files = sorted(files, key=lambda f: f.size, reverse=True)
        largest = [f for f in sorted_files if f.size > 0][:10]
        smallest = [f for f in sorted_files if f.size > 0][-10:]

        stats = ScanStats(
            root_path=root_abs,
            total_files=len(files),
            total_folders=len(folders),
            total_size_bytes=total_size,
            duration_seconds=duration,
            scan_speed_files_per_sec=speed,
            max_depth=max((f.depth for f in folders), default=0),
            empty_files_count=sum(1 for f in files if f.size == 0),
            empty_folders_count=sum(1 for f in folders if f.total_files == 0),
            largest_files=largest,
            smallest_files=smallest,
            extension_distribution=ext_distribution,
            duplicate_groups=duplicate_groups,
            duplicate_wasted_bytes=wasted_bytes,
            error_count=len(errors),
            category_distribution=dict(cat_dist),
            action_distribution=dict(act_dist),
            detected_ecosystems=active_ecosystems,
            detected_languages=dict(lang_dist),
            estimated_total_tokens=total_estimated_tokens,
            secret_alerts_count=secret_alerts_count,
            included_files_count=inc_count,
            excluded_files_count=exc_count,
        )

        project_context_payload = {
            "root_path": root_abs,
            "ecosystems": sorted(list(active_ecosystems)),
            "manifests": {k: v.to_dict() for k, v in manifests.items()},
            "graph": project_graph.to_dict(),
            "entry_points": sorted(list(entry_points)),
        }

        return ScanResult(
            config=self.config,
            root_path=root_abs,
            files=files,
            folders=folders,
            errors=errors,
            stats=stats,
            db_path=db_path,
            is_cancelled=False,
            project_context=project_context_payload,
        )
