"""
veilframe.image.detectors.plate — License plate detection provider.

Primary: OpenCV HAAR cascade for Russian plates (as a broad proxy).
Probe: contour-based morphological detector (fully different algorithm + impl).

Both detect objects in SOURCE_DECODED space and return DetectionResult.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import List, Optional

from ..models.coordinates import BoundingBox, CoordinateSpace
from ..models.graph import ProviderFingerprint
from ..models.status import DetectorClass
from .base import DetectionProvider, DetectionResult
from .face import _linear_to_uint8_bgr, _hash_str, _opencv_version


class PrimaryPlateDetector(DetectionProvider):
    """OpenCV HAAR cascade license plate detector (primary)."""

    _ALGORITHM_ID = "haar_cascade_licence_plate"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "plate-haar"
    _PROVIDER_ID = "veilframe.plate.primary"
    _IMPL_ID = "haar_plate_v1"

    def __init__(self) -> None:
        self._cascade = None
        self._fingerprint: Optional[ProviderFingerprint] = None

    def _load_cascade(self) -> bool:
        try:
            import cv2  # type: ignore
            # OpenCV ships haarcascade_russian_plate_number.xml in cv2/data
            data_dir = Path(cv2.__file__).parent / "data"
            for candidate in [
                "haarcascade_russian_plate_number.xml",
                "haarcascade_licence_plate.xml",
            ]:
                path = data_dir / candidate
                if path.exists():
                    self._cascade = cv2.CascadeClassifier(str(path))
                    return True
        except ImportError:
            pass
        return False

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
                source_hash=_hash_str("plate_haar_src", ocv_ver),
                implementation_hash=_hash_str("plate_haar_impl", ocv_ver),
                dependency_graph_hash=_hash_str("plate_deps", ocv_ver),
                library_binary_hash=_hash_str("plate_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.LICENSE_PLATE

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        import cv2  # type: ignore
        if self._cascade is None:
            if not self._load_cascade():
                return []
        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        plates = self._cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=3)
        results = []
        h, w = gray.shape[:2]
        if len(plates) == 0:
            return results
        for (x, y, pw, ph) in plates:
            bbox = BoundingBox(
                x_min=float(max(0, x)), y_min=float(max(0, y)),
                x_max=float(min(w, x + pw)), y_max=float(min(h, y + ph)),
                space=CoordinateSpace.SOURCE_DECODED,
            )
            results.append(DetectionResult(
                bbox=bbox, confidence=0.7,
                detector_class=DetectorClass.LICENSE_PLATE,
                provider_fingerprint=self.fingerprint,
            ))
        return results


class ProbePlateDetector(DetectionProvider):
    """Morphological contour-based plate detector (probe).

    Independent from Haar cascade: different algorithm + model family.
    """

    _ALGORITHM_ID = "morphological_contour_aspect_filter"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "plate-morph"
    _PROVIDER_ID = "veilframe.plate.probe"
    _IMPL_ID = "morph_plate_v1"

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
                source_hash=_hash_str("morph_src", ocv_ver),
                implementation_hash=_hash_str("morph_impl", ocv_ver),
                dependency_graph_hash=_hash_str("morph_deps", ocv_ver),
                library_binary_hash=_hash_str("morph_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.LICENSE_PLATE

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        """Detect license plates via morphological close + contour aspect filter."""
        import cv2  # type: ignore
        import numpy as np  # type: ignore

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape[:2]

        # Morphological gradient to highlight plate edges
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (17, 3))
        morph = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel)
        _, thresh = cv2.threshold(morph, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        results = []
        for cnt in contours:
            x, y, cw, ch = cv2.boundingRect(cnt)
            if ch == 0:
                continue
            aspect = cw / ch
            area_frac = (cw * ch) / (w * h)
            # Plate aspect 2:1 – 6:1, area 0.1% – 10%
            if 2.0 <= aspect <= 6.0 and 0.001 <= area_frac <= 0.10:
                bbox = BoundingBox(
                    x_min=float(max(0, x)), y_min=float(max(0, y)),
                    x_max=float(min(w, x + cw)), y_max=float(min(h, y + ch)),
                    space=CoordinateSpace.SOURCE_DECODED,
                )
                results.append(DetectionResult(
                    bbox=bbox, confidence=0.6,
                    detector_class=DetectorClass.LICENSE_PLATE,
                    provider_fingerprint=self.fingerprint,
                ))

        return results
