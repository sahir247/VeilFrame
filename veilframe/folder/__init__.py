"""
veilframe.folder — High-performance configurable folder analyzer subsystem.
"""

from veilframe.folder.config import HashAlgorithm, ScanConfig, ScanProfile
from veilframe.folder.database import FolderDatabase
from veilframe.folder.duplicate import DuplicateFinder
from veilframe.folder.exporter import FolderExporter
from veilframe.folder.hasher import (
    ParallelHashEngine,
    compute_file_hash,
    compute_quick_fingerprint,
)
from veilframe.folder.models import (
    DuplicateGroup,
    ExtensionStat,
    FileRecord,
    FolderRecord,
    ScanError,
    ScanResult,
    ScanStats,
    format_bytes,
)
from veilframe.folder.scanner import FolderScanner
from veilframe.folder.statistics import StatisticsEngine

__all__ = [
    "ScanConfig",
    "ScanProfile",
    "HashAlgorithm",
    "FileRecord",
    "FolderRecord",
    "ScanError",
    "DuplicateGroup",
    "ExtensionStat",
    "ScanStats",
    "ScanResult",
    "format_bytes",
    "FolderScanner",
    "FolderDatabase",
    "DuplicateFinder",
    "StatisticsEngine",
    "FolderExporter",
    "compute_file_hash",
    "compute_quick_fingerprint",
    "ParallelHashEngine",
]
