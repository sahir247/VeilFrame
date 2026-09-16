"""
veilframe.folder.models.file_record — File-level metadata, classification, and AI prioritization record.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional

from veilframe.folder.models.classification import (
    AIAction,
    ClassificationResult,
    FileCategory,
)


def format_bytes(byte_count: int) -> str:
    """Format bytes into human-readable representation (B, KB, MB, GB, TB, PB)."""
    if byte_count < 0:
        return "0 B"
    if byte_count < 1024:
        return f"{byte_count} B"
    
    units = ["KB", "MB", "GB", "TB", "PB"]
    val = float(byte_count)
    for unit in units:
        val /= 1024.0
        if val < 1024.0 or unit == units[-1]:
            return f"{val:.2f} {unit}"
    return f"{val:.2f} PB"


@dataclass
class FileRecord:
    """Individual file record capturing filesystem metadata, classification, and AI attributes."""
    id: int = 0
    folder_id: int = 0
    name: str = ""
    path: str = ""
    relative_path: str = ""
    extension: str = ""
    size: int = 0
    created: Optional[float] = None
    modified: Optional[float] = None
    accessed: Optional[float] = None
    permissions: Optional[str] = None
    hash_value: Optional[str] = None
    hash_algorithm: Optional[str] = None
    quick_fingerprint: Optional[str] = None
    is_hidden: bool = False
    is_symlink: bool = False
    error: Optional[str] = None

    # Project Intelligence & AI Bundle metadata
    classification: ClassificationResult = field(default_factory=ClassificationResult)
    language: Optional[str] = None
    is_binary: bool = False
    mime_type: Optional[str] = None
    priority_score: int = 50
    token_count: int = 0
    is_entry_point: bool = False
    is_secret: bool = False
    secret_alerts: List[str] = field(default_factory=list)
    summary: Optional[str] = None
    user_override_action: Optional[AIAction] = None

    @property
    def effective_action(self) -> AIAction:
        """User explicit override takes precedence over rule action."""
        if self.user_override_action is not None:
            return self.user_override_action
        return self.classification.action

    @property
    def effective_category(self) -> FileCategory:
        return self.classification.category

    @property
    def created_datetime(self) -> Optional[datetime]:
        return datetime.fromtimestamp(self.created) if self.created else None

    @property
    def modified_datetime(self) -> Optional[datetime]:
        return datetime.fromtimestamp(self.modified) if self.modified else None

    @property
    def accessed_datetime(self) -> Optional[datetime]:
        return datetime.fromtimestamp(self.accessed) if self.accessed else None

    def format_size(self) -> str:
        return format_bytes(self.size)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "folder_id": self.folder_id,
            "name": self.name,
            "path": self.path,
            "relative_path": self.relative_path,
            "extension": self.extension,
            "size": self.size,
            "size_formatted": self.format_size(),
            "created": self.created_datetime.isoformat() if self.created_datetime else None,
            "modified": self.modified_datetime.isoformat() if self.modified_datetime else None,
            "accessed": self.accessed_datetime.isoformat() if self.accessed_datetime else None,
            "permissions": self.permissions,
            "hash_value": self.hash_value,
            "hash_algorithm": self.hash_algorithm,
            "is_hidden": self.is_hidden,
            "is_symlink": self.is_symlink,
            "error": self.error,
            # Intelligence additions
            "classification": self.classification.to_dict(),
            "category": self.effective_category.value,
            "action": self.effective_action.value,
            "language": self.language,
            "is_binary": self.is_binary,
            "mime_type": self.mime_type,
            "priority_score": self.priority_score,
            "token_count": self.token_count,
            "is_entry_point": self.is_entry_point,
            "is_secret": self.is_secret,
            "secret_alerts": self.secret_alerts,
            "summary": self.summary,
            "user_override_action": self.user_override_action.value if self.user_override_action else None,
        }
