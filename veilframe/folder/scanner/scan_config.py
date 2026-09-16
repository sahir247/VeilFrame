"""
veilframe.folder.scanner.scan_config — Configuration model, profiles, and optimization parameters.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional, Set


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
    AI_READY = "ai_ready"        # Full Project Intelligence, Classification, and Token Estimation
    CUSTOM = "custom"            # Fully customizable


@dataclass
class ScanConfig:
    """Configurable scanner settings supporting traditional scanning and Project Intelligence."""

    # 1. Fundamental Identity
    include_name: bool = True
    include_extension: bool = True
    include_size: bool = True

    # 2. Extended Filesystem Metadata
    include_created: bool = False
    include_modified: bool = True
    include_accessed: bool = False
    include_permissions: bool = False
    include_hidden: bool = False

    # 3. Cryptographic Content Hashing
    include_hash: bool = False
    hash_algorithm: str = "sha256"
    chunk_size_bytes: int = 1024 * 1024

    # 4. Duplicate Detection Mode
    detect_duplicates: bool = False
    duplicate_staged_hash: bool = True

    # 5. Traversal Controls
    recursive: bool = True
    max_depth: Optional[int] = None
    follow_symlinks: bool = False
    excluded_dirs: Set[str] = field(default_factory=lambda: {
        ".git", ".svn", ".hg"
    })
    excluded_extensions: Set[str] = field(default_factory=set)

    # 6. Concurrency & Performance
    hash_workers: int = 0

    # 7. Project Intelligence & AI Bundle Settings
    enable_intelligence: bool = True
    detect_ecosystems: bool = True
    classify_files: bool = True
    scan_secrets: bool = True
    estimate_tokens: bool = True
    target_token_budget: Optional[int] = 128_000

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
            or self.enable_intelligence
        )

    @property
    def effective_hash_workers(self) -> int:
        if self.hash_workers > 0:
            return self.hash_workers
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
                enable_intelligence=False,
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
                enable_intelligence=False,
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
                enable_intelligence=False,
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
                enable_intelligence=False,
            )
        elif profile == ScanProfile.AI_READY:
            cfg = cls(
                include_name=True,
                include_extension=True,
                include_size=True,
                include_modified=True,
                include_created=True,
                include_accessed=False,
                include_permissions=False,
                include_hidden=True,
                include_hash=False,
                detect_duplicates=False,
                enable_intelligence=True,
                detect_ecosystems=True,
                classify_files=True,
                scan_secrets=True,
                estimate_tokens=True,
                target_token_budget=128_000,
            )
        else:  # CUSTOM
            cfg = cls()

        for k, v in overrides.items():
            if hasattr(cfg, k):
                setattr(cfg, k, v)
        return cfg

    def to_dict(self) -> Dict[str, Any]:
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
            "enable_intelligence": self.enable_intelligence,
            "detect_ecosystems": self.detect_ecosystems,
            "classify_files": self.classify_files,
            "scan_secrets": self.scan_secrets,
            "estimate_tokens": self.estimate_tokens,
            "target_token_budget": self.target_token_budget,
        }
