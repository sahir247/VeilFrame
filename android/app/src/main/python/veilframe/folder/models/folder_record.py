"""
veilframe.folder.models.folder_record — Folder node metadata and aggregate metrics.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Optional

from veilframe.folder.models.classification import ClassificationResult
from veilframe.folder.models.file_record import format_bytes


@dataclass
class FolderRecord:
    """Folder node metadata with bottom-up rollup counters and classification statistics."""
    id: int = 0
    parent_id: Optional[int] = None
    name: str = ""
    path: str = ""
    relative_path: str = ""
    depth: int = 0
    direct_files: int = 0
    direct_folders: int = 0
    total_files: int = 0
    total_folders: int = 0
    total_size: int = 0
    error: Optional[str] = None

    # Intelligence & Classification rollup
    classification: Optional[ClassificationResult] = None
    category_counts: Dict[str, int] = field(default_factory=dict)
    is_excluded: bool = False

    def format_size(self) -> str:
        return format_bytes(self.total_size)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "parent_id": self.parent_id,
            "name": self.name,
            "path": self.path,
            "relative_path": self.relative_path,
            "depth": self.depth,
            "direct_files": self.direct_files,
            "direct_folders": self.direct_folders,
            "total_files": self.total_files,
            "total_folders": self.total_folders,
            "total_size": self.total_size,
            "total_size_formatted": self.format_size(),
            "error": self.error,
            "classification": self.classification.to_dict() if self.classification else None,
            "category_counts": self.category_counts,
            "is_excluded": self.is_excluded,
        }
