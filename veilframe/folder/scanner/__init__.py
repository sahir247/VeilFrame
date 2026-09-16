"""
veilframe.folder.scanner — Fast directory walker, scan configuration, and execution pipeline.
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
    "ScanConfig",
    "ScanProfile",
    "HashAlgorithm",
    "FolderScanner",
    "DirectoryWalker",
    "CancellationToken",
]
