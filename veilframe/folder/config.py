"""
veilframe.folder.config — Re-export shim for ScanConfig and ScanProfile.
"""

from veilframe.folder.scanner.scan_config import (
    HashAlgorithm,
    ScanConfig,
    ScanProfile,
)

__all__ = [
    "HashAlgorithm",
    "ScanProfile",
    "ScanConfig",
]
