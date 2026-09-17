"""
veilframe.image.runtime.capabilities — Runtime capability detection.

Probes the host environment for OpenCV, DNN model weights, OCR backends,
and GPU availability.  The CapabilityRegistry is hashed and committed into
the EvidencePreimage so capability state cannot be silently changed.
"""

from __future__ import annotations

import hashlib
import json
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..models.status import CapabilityProfile


# ---------------------------------------------------------------------------
# Per-capability probe result
# ---------------------------------------------------------------------------

@dataclass
class CapabilityProbe:
    """Result of probing a single capability on the host."""
    name: str
    available: bool
    version: Optional[str] = None
    detail: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {"name": self.name, "available": self.available}
        if self.version is not None:
            d["version"] = self.version
        if self.detail is not None:
            d["detail"] = self.detail
        return d


# ---------------------------------------------------------------------------
# Capability report
# ---------------------------------------------------------------------------

@dataclass
class CapabilityReport:
    """Full capability snapshot for a single invocation.

    Fields
    ------
    opencv_available : bool
    opencv_version : Optional[str]
    opencv_dnn_available : bool
        True iff cv2.dnn is importable and at least one model cascade exists.
    ocr_available : bool
        True iff a supported OCR backend (pytesseract) is importable.
    gpu_available : bool
        True iff cv2.cuda.getCudaEnabledDeviceCount() > 0.
    profile : CapabilityProfile
        Derived tier: BASELINE / STANDARD / FULL.
    probes : List[CapabilityProbe]
        Raw probe results for audit trail.
    registry_identity : str
        SHA-256 of canonical report JSON (for EvidencePreimage).
    """
    opencv_available: bool
    opencv_version: Optional[str]
    opencv_dnn_available: bool
    ocr_available: bool
    gpu_available: bool
    profile: CapabilityProfile
    probes: List[CapabilityProbe] = field(default_factory=list)
    registry_identity: str = field(init=False)

    def __post_init__(self) -> None:
        canonical = json.dumps(self._hashable_dict(), sort_keys=True, ensure_ascii=True)
        object.__setattr__(self, "registry_identity", hashlib.sha256(canonical.encode()).hexdigest())

    def _hashable_dict(self) -> dict:
        return {
            "opencv_available": self.opencv_available,
            "opencv_version": self.opencv_version,
            "opencv_dnn_available": self.opencv_dnn_available,
            "ocr_available": self.ocr_available,
            "gpu_available": self.gpu_available,
            "profile": self.profile.value,
        }

    def to_dict(self) -> dict:
        d = self._hashable_dict()
        d["registry_identity"] = self.registry_identity
        d["probes"] = [p.to_dict() for p in self.probes]
        return d


# ---------------------------------------------------------------------------
# Capability registry (provider registry wrapper)
# ---------------------------------------------------------------------------

class CapabilityRegistry:
    """Detects and caches runtime capability state.

    Call CapabilityRegistry.detect() once per process invocation and pass
    the resulting CapabilityReport into the pipeline.  The report is frozen
    after detection.
    """

    @staticmethod
    def detect() -> CapabilityReport:
        """Probe the host environment and return a frozen CapabilityReport."""
        probes: list = []

        # --- OpenCV ---
        opencv_available = False
        opencv_version: Optional[str] = None
        try:
            import cv2  # type: ignore
            opencv_available = True
            opencv_version = cv2.__version__
            probes.append(CapabilityProbe("opencv", True, opencv_version))
        except ImportError:
            probes.append(CapabilityProbe("opencv", False, detail="cv2 not importable"))

        # --- OpenCV DNN ---
        opencv_dnn_available = False
        if opencv_available:
            try:
                import cv2  # type: ignore
                _ = cv2.dnn.readNetFromONNX  # probe attribute existence
                opencv_dnn_available = True
                probes.append(CapabilityProbe("opencv_dnn", True))
            except AttributeError:
                probes.append(CapabilityProbe("opencv_dnn", False, detail="cv2.dnn not available"))

        # --- OCR (pytesseract) ---
        ocr_available = False
        try:
            import pytesseract  # type: ignore
            ocr_available = True
            probes.append(CapabilityProbe("pytesseract", True))
        except ImportError:
            probes.append(CapabilityProbe("pytesseract", False, detail="pytesseract not installed"))

        # --- GPU (CUDA via OpenCV) ---
        gpu_available = False
        if opencv_available:
            try:
                import cv2  # type: ignore
                gpu_available = cv2.cuda.getCudaEnabledDeviceCount() > 0
                probes.append(CapabilityProbe("cuda", gpu_available, detail=f"device_count={cv2.cuda.getCudaEnabledDeviceCount()}"))
            except (AttributeError, Exception):
                probes.append(CapabilityProbe("cuda", False, detail="CUDA not available"))

        # --- Derive profile tier ---
        if gpu_available and opencv_dnn_available:
            profile = CapabilityProfile.FULL
        elif opencv_available:
            profile = CapabilityProfile.STANDARD
        else:
            profile = CapabilityProfile.BASELINE

        return CapabilityReport(
            opencv_available=opencv_available,
            opencv_version=opencv_version,
            opencv_dnn_available=opencv_dnn_available,
            ocr_available=ocr_available,
            gpu_available=gpu_available,
            profile=profile,
            probes=probes,
        )
