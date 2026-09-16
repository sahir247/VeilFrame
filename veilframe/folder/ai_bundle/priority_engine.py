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
    3. Substantive implementation files before boilerplate (e.g. real module logic before empty __init__.py)
    4. Balanced size weighting on tie breaks (avoid monolith starvation)
    """
    def _rank_key(f: FileRecord):
        is_boilerplate = 1 if (f.name == "__init__.py" and f.size < 300) else 0
        size = f.size
        if size < 100:
            size_score = -500
        elif size <= 25_000:
            size_score = size
        else:
            # Gentle taper for monolithic files on equal priority so modular components aren't starved
            size_score = 25_000 - (size - 25_000) // 2

        return (
            f.priority_score,
            1 if f.is_entry_point else 0,
            -is_boilerplate,
            size_score,
        )

    return sorted(files, key=_rank_key, reverse=True)

