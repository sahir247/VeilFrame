"""
veilframe.folder.classification — Classification, categorization, scoring, and precedence pipeline.
"""

from veilframe.folder.classification.categories import (
    ACTION_COLOR_MAP,
    CATEGORY_COLOR_MAP,
    get_action_color,
    get_category_color,
)
from veilframe.folder.classification.classifier import Classifier
from veilframe.folder.classification.scoring import (
    CATEGORY_BASE_SCORES,
    calculate_file_priority,
)

__all__ = [
    "Classifier",
    "calculate_file_priority",
    "CATEGORY_BASE_SCORES",
    "CATEGORY_COLOR_MAP",
    "ACTION_COLOR_MAP",
    "get_category_color",
    "get_action_color",
]
