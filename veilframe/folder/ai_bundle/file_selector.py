"""
veilframe.folder.ai_bundle.file_selector — Priority knapsack selection under token budgets.
"""

from __future__ import annotations

from typing import List, Optional, Set, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.priority_engine import rank_files_by_priority
from veilframe.folder.ai_bundle.token_estimator import estimate_tokens
from veilframe.folder.models.classification import AIAction, FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.security.sensitive_files import is_sensitive_filepath


class FileSelector:
    """Intelligently selects the highest-value files fitting within the token budget."""

    def __init__(self, config: BundleConfig) -> None:
        self.config = config

    def select_files(
        self,
        files: List[FileRecord],
    ) -> Tuple[List[FileRecord], List[Tuple[FileRecord, str]], int]:
        """
        Partition files into included and excluded sets under the token budget.
        
        Returns:
            (included_files, excluded_files_with_reasons, total_tokens_used)
        """
        included: List[FileRecord] = []
        excluded: List[Tuple[FileRecord, str]] = []
        accumulated_tokens = 0
        budget = self.config.target_tokens

        ranked = rank_files_by_priority(files)

        # 1. First Pass: Handle hard exclusions, security flags, and category filters
        eligible: List[FileRecord] = []

        for f in ranked:
            norm_rel = f.relative_path.replace("\\", "/").strip("/")

            # Check manual pinning / exclusions
            if norm_rel in self.config.excluded_files:
                excluded.append((f, "User explicitly excluded"))
                continue

            if norm_rel in self.config.pinned_files:
                eligible.append(f)
                continue

            # Hard security exclude for dedicated sensitive credential files (e.g. .env, private keys, certificates)
            is_sens, sens_reason = is_sensitive_filepath(f.path)
            if (f.is_secret and is_sens) or is_sens or f.effective_category in (FileCategory.SECRET, FileCategory.CREDENTIAL, FileCategory.PRIVATE_KEY):
                sec_reason = sens_reason or (f.classification.reason if f.effective_category == FileCategory.SECRET else "Dedicated credential or secret file")
                excluded.append((f, f"Security exclusion: {sec_reason}"))
                continue

            # Check default action
            if f.effective_action == AIAction.EXCLUDE:
                excluded.append((f, f.classification.reason or "Excluded by ecosystem/rule policy"))
                continue

            # Check user filter toggles
            if not self.config.include_tests and f.effective_category == FileCategory.TEST:
                excluded.append((f, "Test files disabled in bundle settings"))
                continue

            if not self.config.include_documentation and f.effective_category == FileCategory.DOCUMENTATION:
                excluded.append((f, "Documentation files disabled in bundle settings"))
                continue

            if not self.config.include_configs and f.effective_category in (FileCategory.CONFIG, FileCategory.IDE_CONFIG, FileCategory.VCS_CONFIG):
                excluded.append((f, "Configuration files disabled in bundle settings"))
                continue

            if not self.config.include_scripts and f.effective_category == FileCategory.SCRIPT:
                excluded.append((f, "Script files disabled in bundle settings"))
                continue

            eligible.append(f)

        # 2. Second Pass: Greedy selection under token budget
        for f in eligible:
            # Machine-generated lockfiles should not bypass the budget as mandatory manifests
            is_lockfile = (
                f.name.lower().endswith((".lock", "-lock.json", "-lock.yaml"))
                or f.name.lower() in ("cargo.lock", "poetry.lock", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "gemfile.lock")
            )

            file_tokens = f.token_count or max(1, f.size // 4)
            if is_lockfile:
                # Lockfiles are summarized into concise dependency lists (~300-500 tokens)
                file_tokens = min(file_tokens, 500)

            # Mandatory / core files: Entry points, primary root manifests, root README, architecture docs, and build specs
            is_shallow = len(f.relative_path.replace("\\", "/").split("/")) <= 2
            is_root_manifest = (f.effective_category == FileCategory.MANIFEST and not is_lockfile and is_shallow)
            is_root_readme = (f.name.lower().startswith("readme") and is_shallow)

            is_mandatory = (
                f.is_entry_point
                or is_root_manifest
                or is_root_readme
                or f.name.lower().startswith("architecture")
                or f.name.endswith(".spec")
                or f.name in ("build.sh", "Makefile", "Dockerfile", "run.py")
            ) and (f.size < 200_000)

            if budget is not None:
                hard_cap = int(budget * 1.10) if is_mandatory else budget
                if accumulated_tokens + file_tokens > hard_cap:
                    remaining_budget = max(0, budget - accumulated_tokens)
                    excluded.append((
                        f,
                        f"Context budget exhausted (file requires ~{file_tokens:,} tokens, budget remaining: {remaining_budget:,} tokens)"
                    ))
                    continue

            included.append(f)
            accumulated_tokens += file_tokens

        return included, excluded, accumulated_tokens
