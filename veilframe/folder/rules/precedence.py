"""
veilframe.folder.rules.precedence — Strict 9-tier rule precedence resolution engine.
"""

from __future__ import annotations

from typing import Any, List, Optional

from veilframe.folder.models.classification import (
    ClassificationResult,
    FileCategory,
    RulePriority,
)


PRECEDENCE_ORDER = {
    RulePriority.SECURITY: 1,
    RulePriority.USER_OVERRIDE: 2,
    RulePriority.GITIGNORE: 3,
    RulePriority.ECOSYSTEM: 4,
    RulePriority.FRAMEWORK: 5,
    RulePriority.TOOL_IDE: 6,
    RulePriority.GENERATED_DETECTOR: 7,
    RulePriority.FILE_TYPE: 8,
    RulePriority.DEFAULT: 9,
}


def resolve_highest_precedence(candidates: List[Any]) -> Any:
    """
    Given multiple classification candidates for a file or directory, resolve the single
    highest-precedence outcome according to the strict 9-tier hierarchy:
    
    1. SECURITY (100)
    2. USER_OVERRIDE (90)
    3. GITIGNORE (80)
    4. ECOSYSTEM (70)
    5. FRAMEWORK (60)
    6. TOOL_IDE (50)
    7. GENERATED (40)
    8. FILE_TYPE (30)
    9. DEFAULT (10)
    
    Ties within the same precedence tier are broken by highest confidence score.
    """
    if not candidates:
        return ClassificationResult()

    if len(candidates) == 1:
        return candidates[0]

    # Sort descending: (priority_value, confidence)
    sorted_candidates = sorted(
        candidates,
        key=lambda c: (
            getattr(c.priority, "value", 0),
            getattr(c, "confidence", 1.0)
        ),
        reverse=True,
    )
    return sorted_candidates[0]
