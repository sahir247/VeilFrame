"""
veilframe.folder.models — Backward compatibility re-export shim.
"""

from veilframe.folder.models.classification import (
    DEFAULT_CATEGORY_ACTIONS,
    AIAction,
    ClassificationResult,
    FileCategory,
    RulePriority,
    get_default_action,
)
from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.models.folder_record import FolderRecord
from veilframe.folder.models.scan_result import (
    DuplicateGroup,
    ExtensionStat,
    ScanError,
    ScanResult,
    ScanStats,
)

__all__ = [
    "FileCategory",
    "AIAction",
    "RulePriority",
    "DEFAULT_CATEGORY_ACTIONS",
    "get_default_action",
    "ClassificationResult",
    "FileRecord",
    "FolderRecord",
    "ScanError",
    "DuplicateGroup",
    "ExtensionStat",
    "ScanStats",
    "ScanResult",
    "format_bytes",
]
