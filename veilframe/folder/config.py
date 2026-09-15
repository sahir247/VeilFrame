"""
veilframe.folder.config — Scan configuration model and predefined profiles.

Implements the fundamental principle:
  "Scan only what the user asks for, and only read file contents when a
   selected feature actually requires it."
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, Set


class HashAlgorithm(str, Enum):
    NONE = "none"
    SHA256 = "sha256"
    SHA1 = "sha1"
    MD5 = "md5"


class ScanProfile(str, Enum):
    QUICK = "quick"              # Name + Extension + Size + Modified
    FULL_METADATA = "full_meta"  # All filesystem metadata (no hashing)
    INTEGRITY = "integrity"      # Full metadata + SHA-256
    DUPLICATES = "duplicates"    # Size + Quick Fingerprint + SHA-256 on collisions
    CUSTOM = "custom"            # Fully customizable


@dataclass
class ScanConfig:
    """Configurable scanner settings. Every expensive operation is optional."""

    # 1. Fundamental Identity (Almost Free)
    include_name: bool = True
    include_extension: bool = True
    include_size: bool = True

    # 2. Extended Filesystem Metadata (Stat queries)
    include_created: bool = False
    include_modified: bool = True
    include_accessed: bool = False
    include_permissions: bool = False
    include_hidden: bool = False

    # 3. Cryptographic Content Hashing (Expensive I/O)
    include_hash: bool = False
    hash_algorithm: str = "sha256"      # "sha256", "sha1", "md5", or "none"
    chunk_size_bytes: int = 1024 * 1024 # 1 MiB streaming chunks

    # 4. Duplicate Detection Mode
    detect_duplicates: bool = False
    duplicate_staged_hash: bool = True  # Use Size -> Fingerprint -> SHA-256

    # 5. Traversal Controls
    recursive: bool = True
    max_depth: Optional[int] = None
    follow_symlinks: bool = False
    excluded_dirs: Set[str] = field(default_factory=lambda: {
        ".git", ".svn", ".hg", "node_modules", "__pycache__", ".venv", "venv", ".idea", ".vscode"
    })
    excluded_extensions: Set[str] = field(default_factory=set)

    # 6. Concurrency & Performance
    hash_workers: int = 0  # 0 = Auto-detect based on CPU cores & storage

    def __post_init__(self) -> None:
        if isinstance(self.excluded_dirs, (list, tuple)):
            self.excluded_dirs = set(self.excluded_dirs)
        if isinstance(self.excluded_extensions, (list, tuple)):
            self.excluded_extensions = set(self.excluded_extensions)
        self.hash_algorithm = self.hash_algorithm.lower()
        if self.hash_algorithm == "none":
            self.include_hash = False

    @property
    def requires_stat(self) -> bool:
        """True if any metadata field requires calling os.DirEntry.stat()."""
        return (
            self.include_size
            or self.include_created
            or self.include_modified
            or self.include_accessed
            or self.include_permissions
            or self.include_hash
            or self.detect_duplicates
        )

    @property
    def effective_hash_workers(self) -> int:
        """Calculate optimal thread pool worker count."""
        if self.hash_workers > 0:
            return self.hash_workers
        # Auto: use min(8, max(2, os.cpu_count() or 2))
        cpus = os.cpu_count() or 2
        return min(8, max(2, cpus))

    @classmethod
    def from_profile(cls, profile: ScanProfile | str, **overrides) -> ScanConfig:
        """Factory constructor for pre-configured scan profiles."""
        if isinstance(profile, str):
            profile = ScanProfile(profile.lower())

        if profile == ScanProfile.QUICK:
            cfg = cls(
                include_name=True,
                include_extension=True,
                include_size=True,
                include_modified=True,
                include_created=False,
                include_accessed=False,
                include_permissions=False,
                include_hidden=False,
                include_hash=False,
                detect_duplicates=False,
            )
        elif profile == ScanProfile.FULL_METADATA:
            cfg = cls(
                include_name=True,
                include_extension=True,
                include_size=True,
                include_modified=True,
                include_created=True,
                include_accessed=True,
                include_permissions=True,
                include_hidden=True,
                include_hash=False,
                detect_duplicates=False,
            )
        elif profile == ScanProfile.INTEGRITY:
            cfg = cls(
                include_name=True,
                include_extension=True,
                include_size=True,
                include_modified=True,
                include_created=True,
                include_accessed=False,
                include_permissions=True,
                include_hidden=True,
                include_hash=True,
                hash_algorithm="sha256",
                detect_duplicates=True,
            )
        elif profile == ScanProfile.DUPLICATES:
            cfg = cls(
                include_name=True,
                include_extension=True,
                include_size=True,
                include_modified=True,
                include_created=False,
                include_accessed=False,
                include_permissions=False,
                include_hidden=False,
                include_hash=False,
                detect_duplicates=True,
                duplicate_staged_hash=True,
            )
        else:  # CUSTOM
            cfg = cls()

        for k, v in overrides.items():
            if hasattr(cfg, k):
                setattr(cfg, k, v)
        return cfg

    def to_dict(self) -> dict:
        return {
            "include_name": self.include_name,
            "include_extension": self.include_extension,
            "include_size": self.include_size,
            "include_created": self.include_created,
            "include_modified": self.include_modified,
            "include_accessed": self.include_accessed,
            "include_permissions": self.include_permissions,
            "include_hidden": self.include_hidden,
            "include_hash": self.include_hash,
            "hash_algorithm": self.hash_algorithm,
            "detect_duplicates": self.detect_duplicates,
            "duplicate_staged_hash": self.duplicate_staged_hash,
            "recursive": self.recursive,
            "max_depth": self.max_depth,
            "follow_symlinks": self.follow_symlinks,
            "excluded_dirs": sorted(list(self.excluded_dirs)),
            "excluded_extensions": sorted(list(self.excluded_extensions)),
            "hash_workers": self.hash_workers,
        }
