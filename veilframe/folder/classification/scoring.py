"""
veilframe.folder.classification.scoring — File prioritization and relevance scoring algorithm (0 to 100).
"""

from __future__ import annotations

import os
from typing import Any, Optional

from veilframe.folder.models.classification import FileCategory

# Baseline score per category according to the AI priority specification
CATEGORY_BASE_SCORES = {
    FileCategory.MANIFEST: 95,
    FileCategory.SOURCE: 90,
    FileCategory.TEST: 88,
    FileCategory.CI_CD: 85,
    FileCategory.DOCUMENTATION: 82,
    FileCategory.CONFIG: 80,
    FileCategory.SCRIPT: 75,
    FileCategory.IDE_CONFIG: 60,
    FileCategory.VCS_CONFIG: 50,
    FileCategory.DATABASE: 40,
    FileCategory.DATASET: 20,
    FileCategory.MODEL: 15,
    FileCategory.MEDIA: 10,
    FileCategory.BINARY: 10,
    FileCategory.UNKNOWN: 50,
    FileCategory.SECRET: 0,
    FileCategory.CREDENTIAL: 0,
    FileCategory.PRIVATE_KEY: 0,
    FileCategory.DEPENDENCY: 0,
    FileCategory.VENDOR: 0,
    FileCategory.CACHE: 0,
    FileCategory.BUILD_OUTPUT: 0,
    FileCategory.GENERATED: 0,
    FileCategory.COMPILED: 0,
    FileCategory.RUNTIME_DATA: 0,
}


def calculate_file_priority(
    rel_path: str,
    category: FileCategory,
    is_entry_point: bool = False,
    centrality_bonus: int = 0,
    size_bytes: int = 0,
    depth: int = 0,
) -> int:
    """
    Compute a composite priority score (0–100) for a file to determine inclusion order
    when budgeting tokens for AI context windows.
    """
    basename = os.path.basename(rel_path).lower()
    base_stem, _ = os.path.splitext(basename)

    # 1. Entry point check: highest priority (100)
    if is_entry_point:
        return 100

    # 2. README check (98)
    if basename in ("readme.md", "readme.rst", "readme.txt", "readme"):
        return 98

    # 3. Architecture or design document (96)
    if "architecture" in basename or "design" in basename or basename in ("contributing.md", "security.md"):
        return 96

    # 4. Base score from category
    score = CATEGORY_BASE_SCORES.get(category, 50)

    # If it's zero (excluded category like cache or secret), keep at 0
    if score == 0:
        return 0

    # 5. Core source vs supporting source: apply centrality bonus
    score += centrality_bonus

    # 6. Depth penalty (shallow files near root are generally more important)
    if depth > 3:
        score -= min(10, (depth - 3) * 2)

    # 7. Size penalty for oversized files
    if size_bytes > 500_000:  # > 500 KB
        score -= 20
    elif size_bytes > 200_000:  # > 200 KB
        score -= 10
    elif size_bytes > 100_000:  # > 100 KB
        score -= 5

    # Clamp between 1 and 99 (100 reserved for verified entry points)
    return max(1, min(99, score))


def calculate_priority_score(file_rec: Any) -> int:
    """Convenience wrapper accepting a FileRecord or object with equivalent attributes."""
    rel = getattr(file_rec, "relative_path", getattr(file_rec, "name", ""))
    cat = getattr(file_rec, "effective_category", FileCategory.SOURCE)
    is_ep = getattr(file_rec, "is_entry_point", False)
    size = getattr(file_rec, "size", 0)
    return calculate_file_priority(
        rel_path=rel,
        category=cat,
        is_entry_point=is_ep,
        size_bytes=size,
    )
