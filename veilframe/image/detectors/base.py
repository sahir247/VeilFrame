"""
veilframe.image.detectors.base — Abstract detector provider protocol.

All detector providers MUST implement this protocol.  Providers are registered
with the DetectorRegistry and queried by the Privacy Graph builder.

Architectural Invariant: "Providers measure. VeilFrame decides."
  - Providers return raw detections with confidence scores.
  - VeilFrame's Privacy Graph builder decides whether to include a node.
  - VeilFrame's QualityGate decides pass/fail — NOT the provider.

Provider fingerprint commitment:
  Every provider MUST return a stable ProviderFingerprint that is committed
  into the EvidencePreimage.  If the fingerprint changes, the evidence hash
  changes, and the manifest is invalidated.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional

import hashlib

from ..models.coordinates import BoundingBox, CoordinateSpace
from ..models.graph import DetectorEvidence, ProviderFingerprint
from ..models.status import CheckStatus, DetectorClass


# ---------------------------------------------------------------------------
# Detection result (returned by providers)
# ---------------------------------------------------------------------------

@dataclass
class DetectionResult:
    """A single detected region returned by a detection provider.

    Fields
    ------
    bbox : BoundingBox
        Axis-aligned bounding box in SOURCE_DECODED coordinate space.
    confidence : float
        Detection confidence in [0.0, 1.0].
    detector_class : DetectorClass
        What was detected.
    provider_fingerprint : ProviderFingerprint
        Stable fingerprint of the provider that produced this detection.
    sub_class : Optional[str]
        Sub-class hint (e.g. "frontal", "profile" for faces).
    raw_metadata : dict
        Provider-specific metadata for audit trail (non-normative).
    """
    bbox: BoundingBox
    confidence: float
    detector_class: DetectorClass
    provider_fingerprint: ProviderFingerprint
    sub_class: Optional[str] = None
    raw_metadata: dict = field(default_factory=dict)

    def to_evidence(self) -> DetectorEvidence:
        """Convert to a DetectorEvidence record for PrivacyGraph inclusion."""
        return DetectorEvidence(
            detector_class=self.detector_class,
            confidence=self.confidence,
            fingerprint=self.provider_fingerprint,
            bbox=self.bbox,
            raw_metadata=self.raw_metadata,
        )


# ---------------------------------------------------------------------------
# Provider protocol
# ---------------------------------------------------------------------------

class DetectionProvider(ABC):
    """Abstract base class for all VeilFrame detection providers.

    Subclasses implement detect() to return a list of DetectionResult objects.
    The provider is stateless — all state is passed via detect().
    """

    @property
    @abstractmethod
    def fingerprint(self) -> ProviderFingerprint:
        """Return the stable fingerprint of this provider."""
        ...

    @property
    @abstractmethod
    def detector_class(self) -> DetectorClass:
        """Return the DetectorClass this provider handles."""
        ...

    @abstractmethod
    def detect(
        self,
        linear_srgb_f32: object,  # numpy.ndarray shape (H, W, 3)
        confidence_threshold: float = 0.5,
    ) -> List[DetectionResult]:
        """Detect sensitive regions in the image array.

        Parameters
        ----------
        linear_srgb_f32 : numpy.ndarray
            Float32 array of shape (H, W, 3) in linear sRGB, [0.0, 1.0].
            The provider MUST NOT write to this array.
        confidence_threshold : float
            Minimum confidence to include a detection.  Default 0.5.

        Returns
        -------
        List[DetectionResult]
            All detections above the threshold.  Empty list if none.
        """
        ...

    def safe_detect(
        self,
        linear_srgb_f32: object,
        confidence_threshold: float = 0.5,
    ) -> List[DetectionResult]:
        """Exception-safe wrapper around detect().

        Returns empty list on any exception (fail-OPEN for detection,
        fail-CLOSED at gate — missing detections are caught by red-team).
        """
        try:
            return self.detect(linear_srgb_f32, confidence_threshold)
        except Exception:
            return []


# ---------------------------------------------------------------------------
# Fingerprint factory helper
# ---------------------------------------------------------------------------

def make_fingerprint(
    provider_id: str,
    implementation_id: str,
    algorithm_id: str,
    library_id: str,
    model_family: str,
    version: str,
    source_hash: str,
    implementation_hash: str,
    dependency_graph_hash: str,
    library_binary_hash: str,
    model_hash: Optional[str] = None,
) -> ProviderFingerprint:
    """Convenience factory for ProviderFingerprint with validation."""
    return ProviderFingerprint(
        provider_id=provider_id,
        implementation_id=implementation_id,
        algorithm_id=algorithm_id,
        library_id=library_id,
        model_family=model_family,
        version=version,
        source_hash=source_hash,
        implementation_hash=implementation_hash,
        dependency_graph_hash=dependency_graph_hash,
        library_binary_hash=library_binary_hash,
        model_hash=model_hash,
    )
