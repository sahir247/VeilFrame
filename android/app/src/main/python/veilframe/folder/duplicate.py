"""
veilframe.folder.duplicate — Staged multi-tiered duplicate detection engine.

Implements the 3-stage optimization pipeline:
  Stage 1: Size grouping (Filter out all uniquely-sized files — 0 content read).
  Stage 2: Quick fingerprinting (Sample head 8KB + tail 8KB — microsecond I/O).
  Stage 3: Full cryptographic hash on collision candidates only.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Callable, Dict, List, Optional

from veilframe.folder.hasher import (
    ParallelHashEngine,
    compute_quick_fingerprint,
)
from veilframe.folder.models import DuplicateGroup, FileRecord


class DuplicateFinder:
    """Multi-stage duplicate detector designed to minimize disk I/O."""

    def __init__(
        self,
        hash_algorithm: str = "sha256",
        workers: int = 4,
        chunk_size: int = 1024 * 1024,
    ) -> None:
        self.hash_algorithm = hash_algorithm
        self.workers = workers
        self.chunk_size = chunk_size

    def find_duplicates(
        self,
        files: List[FileRecord],
        staged: bool = True,
        progress_callback: Optional[Callable[[str, int, int], None]] = None,
    ) -> List[DuplicateGroup]:
        """
        Execute duplicate detection on a list of FileRecords.
        If staged is True, uses Size -> Fingerprint -> SHA-256.
        If staged is False, hashes all candidate files sharing identical size.
        """
        if not files or len(files) < 2:
            return []

        # ----------------------------------------------------
        # STAGE 1: Group by file size (0 byte files are ignored as duplicates)
        # ----------------------------------------------------
        if progress_callback:
            progress_callback("Stage 1: Grouping by size...", 0, len(files))

        size_groups: Dict[int, List[FileRecord]] = defaultdict(list)
        for f in files:
            if f.size > 0:
                size_groups[f.size].append(f)

        # Retain only sizes with >= 2 files
        candidates_stage1: List[FileRecord] = []
        for sz, flist in size_groups.items():
            if len(flist) >= 2:
                candidates_stage1.extend(flist)

        if not candidates_stage1:
            return []

        # ----------------------------------------------------
        # STAGE 2: Quick Fingerprint (Head + Tail)
        # ----------------------------------------------------
        candidates_to_hash: List[FileRecord] = []

        if staged:
            if progress_callback:
                progress_callback("Stage 2: Quick fingerprinting...", 0, len(candidates_stage1))

            fp_groups: Dict[str, List[FileRecord]] = defaultdict(list)
            for idx, f in enumerate(candidates_stage1):
                if not f.quick_fingerprint:
                    f.quick_fingerprint = compute_quick_fingerprint(f.path, f.size)
                fp_groups[f"{f.size}:{f.quick_fingerprint}"].append(f)
                if progress_callback and idx % 100 == 0:
                    progress_callback("Stage 2: Quick fingerprinting...", idx, len(candidates_stage1))

            for key, flist in fp_groups.items():
                if len(flist) >= 2:
                    candidates_to_hash.extend(flist)
        else:
            candidates_to_hash = candidates_stage1

        if not candidates_to_hash:
            return []

        # ----------------------------------------------------
        # STAGE 3: Full Cryptographic Hash
        # ----------------------------------------------------
        # Only compute hash for records that don't already have one
        needed_hash = [f for f in candidates_to_hash if not f.hash_value]
        if needed_hash:
            if progress_callback:
                progress_callback("Stage 3: Full cryptographic hashing...", 0, len(needed_hash))

            engine = ParallelHashEngine(
                workers=self.workers,
                algorithm=self.hash_algorithm,
                chunk_size=self.chunk_size,
            )

            def _hash_progress(cur: int, tot: int, rec: FileRecord):
                if progress_callback:
                    progress_callback(f"Stage 3: Hashing ({cur}/{tot})...", cur, tot)

            engine.hash_files(needed_hash, progress_callback=_hash_progress)

        # ----------------------------------------------------
        # Exact Duplicate Grouping
        # ----------------------------------------------------
        hash_groups: Dict[Tuple[int, str], List[FileRecord]] = defaultdict(list)
        for f in candidates_to_hash:
            if f.hash_value:
                hash_groups[(f.size, f.hash_value)].append(f)

        duplicate_groups: List[DuplicateGroup] = []
        for (sz, h_val), flist in hash_groups.items():
            if len(flist) >= 2:
                duplicate_groups.append(
                    DuplicateGroup(
                        size=sz,
                        hash_value=h_val,
                        files=flist,
                    )
                )

        # Sort duplicate groups by total wasted space descending
        duplicate_groups.sort(key=lambda g: g.wasted_bytes, reverse=True)
        return duplicate_groups
