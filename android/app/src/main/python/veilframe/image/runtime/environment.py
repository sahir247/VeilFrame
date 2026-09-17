"""
veilframe.image.runtime.environment — RuntimeEnvironment & EnvironmentHash.

Captures a deterministic fingerprint of the execution environment that is
committed into the EvidencePreimage, binding the signed manifest to the
specific library versions used during sanitization.
"""

from __future__ import annotations

import hashlib
import json
import platform
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, Optional


@dataclass(frozen=True)
class RuntimeEnvironment:
    """Immutable snapshot of the execution environment.

    Fields
    ------
    python_version : str
        sys.version_info as "major.minor.micro".
    platform_system : str
        platform.system() (e.g. "Windows", "Linux").
    platform_machine : str
        platform.machine() (e.g. "AMD64", "x86_64").
    veilframe_version : str
        Installed veilframe package version.
    opencv_version : Optional[str]
        Detected cv2.__version__ or None.
    numpy_version : Optional[str]
        Detected numpy.__version__ or None.
    captured_at_utc : str
        ISO 8601 UTC timestamp of capture (e.g. "2025-01-15T12:00:00Z").
    environment_hash : str
        SHA-256 of canonical environment JSON (computed at construction).
    """
    python_version: str
    platform_system: str
    platform_machine: str
    veilframe_version: str
    opencv_version: Optional[str]
    numpy_version: Optional[str]
    captured_at_utc: str
    environment_hash: str = field(init=False)

    def __post_init__(self) -> None:
        canonical = json.dumps(self._hashable_dict(), sort_keys=True, ensure_ascii=True)
        object.__setattr__(self, "environment_hash", hashlib.sha256(canonical.encode()).hexdigest())

    def _hashable_dict(self) -> dict:
        return {
            "python_version": self.python_version,
            "platform_system": self.platform_system,
            "platform_machine": self.platform_machine,
            "veilframe_version": self.veilframe_version,
            "opencv_version": self.opencv_version,
            "numpy_version": self.numpy_version,
            "captured_at_utc": self.captured_at_utc,
        }

    def to_dict(self) -> dict:
        d = self._hashable_dict()
        d["environment_hash"] = self.environment_hash
        return d

    @staticmethod
    def capture() -> "RuntimeEnvironment":
        """Probe the current environment and return a frozen snapshot."""
        pv = sys.version_info
        py_ver = f"{pv.major}.{pv.minor}.{pv.micro}"

        opencv_ver: Optional[str] = None
        try:
            import cv2  # type: ignore
            opencv_ver = cv2.__version__
        except ImportError:
            pass

        numpy_ver: Optional[str] = None
        try:
            import numpy as np  # type: ignore
            numpy_ver = np.__version__
        except ImportError:
            pass

        # veilframe version
        try:
            from importlib.metadata import version as pkg_version
            vf_ver = pkg_version("veilframe")
        except Exception:
            vf_ver = "unknown"

        now = datetime.now(tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

        return RuntimeEnvironment(
            python_version=py_ver,
            platform_system=platform.system(),
            platform_machine=platform.machine(),
            veilframe_version=vf_ver,
            opencv_version=opencv_ver,
            numpy_version=numpy_ver,
            captured_at_utc=now,
        )
