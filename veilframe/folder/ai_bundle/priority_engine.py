"""
veilframe.folder.ai_bundle.priority_engine — AI priority ranking and sorting utilities.
"""

from __future__ import annotations

from typing import List

from veilframe.folder.models.file_record import FileRecord


def rank_files_by_priority(files: List[FileRecord]) -> List[FileRecord]:
    """
    Sort file records in descending order of AI value:
    1. Highest priority score (100 down to 0)
    2. Entry points first
    3. Smallest byte size on tie-break to maximize token density
    """
    return sorted(
        files,
        key=lambda f: (
            f.priority_score,
            1 if f.is_entry_point else 0,
            -f.size,
        ),
        reverse=True,
    )
