"""
veilframe.folder.models.scan_result — Scan result encapsulation, metrics, errors, and duplicate groups.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Set

from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.models.folder_record import FolderRecord


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
    """Summary metrics for a completed or in-progress scan."""
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

    # Project Intelligence additions
    category_distribution: Dict[str, int] = field(default_factory=dict)
    action_distribution: Dict[str, int] = field(default_factory=dict)
    detected_ecosystems: Set[str] = field(default_factory=set)
    detected_languages: Dict[str, int] = field(default_factory=dict)
    estimated_total_tokens: int = 0
    secret_alerts_count: int = 0
    included_files_count: int = 0
    excluded_files_count: int = 0

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
            "category_distribution": self.category_distribution,
            "action_distribution": self.action_distribution,
            "detected_ecosystems": sorted(list(self.detected_ecosystems)),
            "detected_languages": self.detected_languages,
            "estimated_total_tokens": self.estimated_total_tokens,
            "secret_alerts_count": self.secret_alerts_count,
            "included_files_count": self.included_files_count,
            "excluded_files_count": self.excluded_files_count,
        }


@dataclass
class ScanResult:
    """Encapsulation of a completed folder scan and project intelligence analysis."""
    config: Any
    root_path: str
    files: List[FileRecord] = field(default_factory=list)
    folders: List[FolderRecord] = field(default_factory=list)
    errors: List[ScanError] = field(default_factory=list)
    stats: Optional[ScanStats] = None
    db_path: str = ":memory:"
    is_cancelled: bool = False
    project_context: Optional[Any] = None

    @property
    def ecosystems(self) -> Set[str]:
        if self.project_context and hasattr(self.project_context, "ecosystems"):
            return set(self.project_context.ecosystems)
        if self.stats and hasattr(self.stats, "detected_ecosystems"):
            return set(self.stats.detected_ecosystems)
        return set()

    @property
    def languages(self) -> Set[str]:
        if self.stats and self.stats.detected_languages:
            return set(self.stats.detected_languages.keys())
        langs = {f.language for f in self.files if f.language and f.language != "Unknown"}
        return langs

    @property
    def security_alerts(self) -> List[Any]:
        if self.project_context and hasattr(self.project_context, "security_alerts"):
            return self.project_context.security_alerts
        return []

    @property
    def project_graph(self) -> Optional[Any]:
        if self.project_context and hasattr(self.project_context, "graph"):
            return self.project_context.graph
        return None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "root_path": self.root_path,
            "config": self.config.to_dict() if hasattr(self.config, "to_dict") else str(self.config),
            "stats": self.stats.to_dict() if self.stats else None,
            "files_count": len(self.files),
            "folders_count": len(self.folders),
            "errors_count": len(self.errors),
            "is_cancelled": self.is_cancelled,
            "has_project_context": self.project_context is not None,
            "ecosystems": sorted(list(self.ecosystems)),
            "languages": sorted(list(self.languages)),
        }
