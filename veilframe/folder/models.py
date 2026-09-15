"""
veilframe.folder.models — Data records and statistics models for folder analysis.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional


@dataclass
class FileRecord:
    """Individual file metadata and analysis record."""
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
        }


@dataclass
class FolderRecord:
    """Folder node metadata and aggregated metrics."""
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
        }


@dataclass
class ScanError:
    """Record of a filesystem or hashing access/permission error."""
    id: int = 0
    path: str = ""
    error_type: str = ""
    message: str = ""
    timestamp: float = field(default_factory=lambda: datetime.now().timestamp())

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "path": self.path,
            "error_type": self.error_type,
            "message": self.message,
            "timestamp": datetime.fromtimestamp(self.timestamp).isoformat(),
        }


@dataclass
class DuplicateGroup:
    """Group of identical files sharing size and cryptographic hash."""
    size: int = 0
    hash_value: str = ""
    files: List[FileRecord] = field(default_factory=list)

    @property
    def file_count(self) -> int:
        return len(self.files)

    @property
    def wasted_bytes(self) -> int:
        if len(self.files) <= 1:
            return 0
        return (len(self.files) - 1) * self.size

    def to_dict(self) -> Dict[str, Any]:
        return {
            "size": self.size,
            "size_formatted": format_bytes(self.size),
            "hash_value": self.hash_value,
            "file_count": self.file_count,
            "wasted_bytes": self.wasted_bytes,
            "wasted_formatted": format_bytes(self.wasted_bytes),
            "files": [f.to_dict() for f in self.files],
        }


@dataclass
class ExtensionStat:
    """Metrics aggregated per file extension."""
    extension: str = ""
    file_count: int = 0
    total_size: int = 0
    percentage_files: float = 0.0
    percentage_size: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "extension": self.extension,
            "file_count": self.file_count,
            "total_size": self.total_size,
            "total_size_formatted": format_bytes(self.total_size),
            "percentage_files": round(self.percentage_files, 2),
            "percentage_size": round(self.percentage_size, 2),
        }


@dataclass
class ScanStats:
    """Summary dashboard metrics for a completed or in-progress scan."""
    root_path: str = ""
    total_files: int = 0
    total_folders: int = 0
    total_size_bytes: int = 0
    duration_seconds: float = 0.0
    scan_speed_files_per_sec: float = 0.0
    max_depth: int = 0
    empty_files_count: int = 0
    empty_folders_count: int = 0
    largest_files: List[FileRecord] = field(default_factory=list)
    smallest_files: List[FileRecord] = field(default_factory=list)
    extension_distribution: List[ExtensionStat] = field(default_factory=list)
    duplicate_groups: List[DuplicateGroup] = field(default_factory=list)
    duplicate_wasted_bytes: int = 0
    error_count: int = 0

    def format_total_size(self) -> str:
        return format_bytes(self.total_size_bytes)

    def format_wasted_size(self) -> str:
        return format_bytes(self.duplicate_wasted_bytes)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "root_path": self.root_path,
            "total_files": self.total_files,
            "total_folders": self.total_folders,
            "total_size_bytes": self.total_size_bytes,
            "total_size_formatted": self.format_total_size(),
            "duration_seconds": round(self.duration_seconds, 3),
            "scan_speed_files_per_sec": round(self.scan_speed_files_per_sec, 1),
            "max_depth": self.max_depth,
            "empty_files_count": self.empty_files_count,
            "empty_folders_count": self.empty_folders_count,
            "largest_files": [f.to_dict() for f in self.largest_files],
            "smallest_files": [f.to_dict() for f in self.smallest_files],
            "extension_distribution": [e.to_dict() for e in self.extension_distribution],
            "duplicate_groups": [d.to_dict() for d in self.duplicate_groups],
            "duplicate_wasted_bytes": self.duplicate_wasted_bytes,
            "duplicate_wasted_formatted": self.format_wasted_size(),
            "error_count": self.error_count,
        }


def format_bytes(byte_count: int) -> str:
    """Format bytes into human-readable representation (B, KB, MB, GB, TB)."""
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
class ScanResult:
    """Encapsulation of a completed folder scan."""
    config: Any
    root_path: str
    files: List[FileRecord] = field(default_factory=list)
    folders: List[FolderRecord] = field(default_factory=list)
    errors: List[ScanError] = field(default_factory=list)
    stats: Optional[ScanStats] = None
    db_path: str = ":memory:"
    is_cancelled: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return {
            "root_path": self.root_path,
            "config": self.config.to_dict() if hasattr(self.config, "to_dict") else str(self.config),
            "stats": self.stats.to_dict() if self.stats else None,
            "files_count": len(self.files),
            "folders_count": len(self.folders),
            "errors_count": len(self.errors),
            "is_cancelled": self.is_cancelled,
        }

