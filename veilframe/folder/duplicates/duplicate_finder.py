"""
veilframe.folder.duplicates.duplicate_finder — Staged duplicate file detection pipeline.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Callable, Dict, List, Optional

from veilframe.folder.duplicates.hasher import (
    ParallelHashEngine,
    compute_quick_fingerprint,
)
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import DuplicateGroup


class DuplicateFinder:
    """Detects identical duplicate files using size grouping, quick fingerprinting, and cryptographic hashing."""

    def __init__(
        self,
        algorithm: str = "sha256",
        hash_algorithm: Optional[str] = None,
        workers: int = 4,
        staged: bool = True,
    ) -> None:
        self.algorithm = hash_algorithm or algorithm
        self.workers = workers
        self.staged = staged
        self.hash_engine = ParallelHashEngine(algorithm=self.algorithm, max_workers=workers)

    def cancel(self) -> None:
        self.hash_engine.cancel()

    def find_duplicates(
        self,
        files: List[FileRecord],
        staged: Optional[bool] = None,
        on_progress: Optional[Callable[[int, int, str], None]] = None,
    ) -> List[DuplicateGroup]:
        """Group files by exact content match."""
        use_staged = self.staged if staged is None else staged
        # Stage 1: Group by file size (> 0 bytes)
        size_groups: Dict[int, List[FileRecord]] = defaultdict(list)
        for f in files:
            if f.size > 0 and not f.error:
                size_groups[f.size].append(f)

        candidates: List[FileRecord] = []
        for sz, group in size_groups.items():
            if len(group) > 1:
                candidates.extend(group)

        if not candidates:
            return []

        # Stage 2: Quick Fingerprinting
        if use_staged:
            fp_groups: Dict[str, List[FileRecord]] = defaultdict(list)
            for f in candidates:
                try:
                    fp = compute_quick_fingerprint(f.path, f.size)
                    f.quick_fingerprint = fp
                    fp_groups[fp].append(f)
                except Exception as e:
                    f.error = str(e)

            confirmed_candidates: List[FileRecord] = []
            for fp, group in fp_groups.items():
                if len(group) > 1:
                    confirmed_candidates.extend(group)
            candidates = confirmed_candidates

        if not candidates:
            return []

        # Stage 3: Full Cryptographic Hash on collisions
        unhashed = [f for f in candidates if not f.hash_value]
        if unhashed:
            self.hash_engine.hash_files(unhashed, on_progress=on_progress)

        # Stage 4: Construct Duplicate Groups
        hash_groups: Dict[str, List[FileRecord]] = defaultdict(list)
        for f in candidates:
            if f.hash_value:
                hash_groups[f.hash_value].append(f)

        duplicate_groups: List[DuplicateGroup] = []
        for h, group in hash_groups.items():
            if len(group) > 1:
                duplicate_groups.append(
                    DuplicateGroup(
                        size=group[0].size,
                        hash_value=h,
                        files=group,
                    )
                )

        duplicate_groups.sort(key=lambda d: d.wasted_bytes, reverse=True)
        return duplicate_groups
