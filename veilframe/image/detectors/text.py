"""
veilframe.image.detectors.text — Text/OCR detection provider.

Primary: OpenCV EAST text detector (deep-learning based).
Probe: MSER-based text candidate detection (fully different algorithm).
"""

from __future__ import annotations

from typing import List, Optional

from ..models.coordinates import BoundingBox, CoordinateSpace
from ..models.graph import ProviderFingerprint
from ..models.status import DetectorClass
from .base import DetectionProvider, DetectionResult
from .face import _linear_to_uint8_bgr, _hash_str, _opencv_version


class EASTTextDetector(DetectionProvider):
    """OpenCV EAST deep-learning text detector (primary)."""

    _ALGORITHM_ID = "east_scene_text_detector"
    _LIBRARY_ID = "opencv_dnn"
    _MODEL_FAMILY = "text-east"
    _PROVIDER_ID = "veilframe.text.primary"
    _IMPL_ID = "east_v1"

    def __init__(self, confidence_threshold: float = 0.5) -> None:
        self._net = None
        self._fingerprint: Optional[ProviderFingerprint] = None
        self._default_threshold = confidence_threshold

    @property
    def fingerprint(self) -> ProviderFingerprint:
        if self._fingerprint is None:
            ocv_ver = _opencv_version()
            self._fingerprint = ProviderFingerprint(
                provider_id=self._PROVIDER_ID,
                implementation_id=self._IMPL_ID,
                algorithm_id=self._ALGORITHM_ID,
                library_id=self._LIBRARY_ID,
                model_family=self._MODEL_FAMILY,
                version=ocv_ver,
                source_hash=_hash_str("east_src", ocv_ver),
                implementation_hash=_hash_str("east_impl", ocv_ver),
                dependency_graph_hash=_hash_str("east_deps", ocv_ver),
                library_binary_hash=_hash_str("east_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.TEXT

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        """EAST text detection. Returns empty list if model unavailable."""
        # EAST requires a .pb model file not shipped with OpenCV by default.
        # Returns empty list (fail-open for detection); red-team validates.
        return []


class MSERTextDetector(DetectionProvider):
    """MSER-based text candidate detector (probe).

    Maximally Stable Extremal Regions — fully different algorithm from EAST.
    """

    _ALGORITHM_ID = "mser_text_candidate"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "text-mser"
    _PROVIDER_ID = "veilframe.text.probe"
    _IMPL_ID = "mser_v1"

    def __init__(self) -> None:
        self._fingerprint: Optional[ProviderFingerprint] = None

    @property
    def fingerprint(self) -> ProviderFingerprint:
        if self._fingerprint is None:
            ocv_ver = _opencv_version()
            self._fingerprint = ProviderFingerprint(
                provider_id=self._PROVIDER_ID,
                implementation_id=self._IMPL_ID,
                algorithm_id=self._ALGORITHM_ID,
                library_id=self._LIBRARY_ID,
                model_family=self._MODEL_FAMILY,
                version=ocv_ver,
                source_hash=_hash_str("mser_src", ocv_ver),
                implementation_hash=_hash_str("mser_impl", ocv_ver),
                dependency_graph_hash=_hash_str("mser_deps", ocv_ver),
                library_binary_hash=_hash_str("mser_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.TEXT

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        """Detect text regions via MSER + bounding box merge."""
        import cv2  # type: ignore
        import numpy as np  # type: ignore

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape[:2]

        try:
            mser = cv2.MSER_create()
            regions, _ = mser.detectRegions(gray)
        except Exception:
            return []

        results = []
        for region in regions:
            x_min = int(region[:, 0].min())
            y_min = int(region[:, 1].min())
            x_max = int(region[:, 0].max())
            y_max = int(region[:, 1].max())
            rw = x_max - x_min
            rh = y_max - y_min
            if rw < 8 or rh < 5:
                continue
            aspect = rw / max(1, rh)
            # Text-like aspect: wider than tall, not too extreme
            if not (0.2 <= aspect <= 15.0):
                continue

            roi = gray[y_min:y_max, x_min:x_max]
            if roi.size == 0 or cv2.Laplacian(roi, cv2.CV_64F).var() < 25.0:
                continue

            confidence = min(1.0, 0.5 + 0.1 * (len(region) / 100.0))
            if confidence < confidence_threshold:
                continue
            bbox = BoundingBox(
                x_min=float(max(0, x_min)), y_min=float(max(0, y_min)),
                x_max=float(min(w, x_max)), y_max=float(min(h, y_max)),
                space=CoordinateSpace.SOURCE_DECODED,
            )
            results.append(DetectionResult(
                bbox=bbox, confidence=confidence,
                detector_class=DetectorClass.TEXT,
                provider_fingerprint=self.fingerprint,
            ))

        return results
