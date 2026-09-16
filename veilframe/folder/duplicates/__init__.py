"""
veilframe.folder.duplicates — Parallel hashing and duplicate detection.
"""

from veilframe.folder.duplicates.duplicate_finder import DuplicateFinder
from veilframe.folder.duplicates.hasher import (
    ParallelHashEngine,
    compute_file_hash,
    compute_quick_fingerprint,
    get_hash_object,
)

__all__ = [
    "compute_file_hash",
    "compute_quick_fingerprint",
    "get_hash_object",
    "ParallelHashEngine",
    "DuplicateFinder",
]
