"""
veilframe.folder.scanner — Re-export shim for FolderScanner.
"""

from veilframe.folder.scanner.cancellation import CancellationToken
from veilframe.folder.scanner.scan_config import (
    HashAlgorithm,
    ScanConfig,
    ScanProfile,
)
from veilframe.folder.scanner.scanner import FolderScanner
from veilframe.folder.scanner.walker import DirectoryWalker

__all__ = [
    "FolderScanner",
    "ScanConfig",
    "ScanProfile",
    "HashAlgorithm",
    "DirectoryWalker",
    "CancellationToken",
]
