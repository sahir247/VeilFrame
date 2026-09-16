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

            # Hard security exclude
            if f.is_secret or f.effective_category in (FileCategory.SECRET, FileCategory.CREDENTIAL, FileCategory.PRIVATE_KEY):
                excluded.append((f, f"Security exclusion: {f.classification.reason or 'Potentially sensitive secret'}"))
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
            file_tokens = f.token_count or max(1, f.size // 4)

            # Mandatory files: Entry points, manifests, READMEs always fit if under max limit
            is_mandatory = f.is_entry_point or f.effective_category == FileCategory.MANIFEST or f.name.lower().startswith("readme")

            if budget is not None and not is_mandatory:
                if accumulated_tokens + file_tokens > budget:
                    excluded.append((f, f"Excluded: Exceeds target token ceiling ({budget:,} tokens)"))
                    continue

            included.append(f)
            accumulated_tokens += file_tokens

        return included, excluded, accumulated_tokens
