"""
veilframe.image.detectors.code — QR/barcode detection provider.

Primary: OpenCV QRCodeDetector (built-in since OpenCV 4.x).
Probe: Structural gradient-based barcode localization (different algorithm).
"""

from __future__ import annotations

from typing import List, Optional

from ..models.coordinates import BoundingBox, CoordinateSpace
from ..models.graph import ProviderFingerprint
from ..models.status import DetectorClass
from .base import DetectionProvider, DetectionResult
from .face import _linear_to_uint8_bgr, _hash_str, _opencv_version


class PrimaryQRCodeDetector(DetectionProvider):
    """OpenCV built-in QR code detector (primary)."""

    _ALGORITHM_ID = "opencv_qrcode_detector"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "qr-opencv"
    _PROVIDER_ID = "veilframe.code.primary"
    _IMPL_ID = "qr_opencv_v1"

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
                source_hash=_hash_str("qr_src", ocv_ver),
                implementation_hash=_hash_str("qr_impl", ocv_ver),
                dependency_graph_hash=_hash_str("qr_deps", ocv_ver),
                library_binary_hash=_hash_str("qr_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.QR_CODE

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        import cv2  # type: ignore
        import numpy as np  # type: ignore

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        h, w = bgr.shape[:2]

        try:
            detector = cv2.QRCodeDetector()
            retval, points, _ = detector.detectAndDecode(bgr)
        except Exception:
            return []

        results = []
        if points is not None and len(points) > 0:
            pts = points.reshape(-1, 2)
            x_min = max(0, int(pts[:, 0].min()))
            y_min = max(0, int(pts[:, 1].min()))
            x_max = min(w, int(pts[:, 0].max()))
            y_max = min(h, int(pts[:, 1].max()))
            if x_max > x_min and y_max > y_min:
                bbox = BoundingBox(
                    x_min=float(x_min), y_min=float(y_min),
                    x_max=float(x_max), y_max=float(y_max),
                    space=CoordinateSpace.SOURCE_DECODED,
                )
                results.append(DetectionResult(
                    bbox=bbox, confidence=0.95,
                    detector_class=DetectorClass.QR_CODE,
                    provider_fingerprint=self.fingerprint,
                    raw_metadata={"decoded": retval or ""},
                ))

        return results


class ProbeQRCodeDetector(DetectionProvider):
    """Gradient-based barcode localizer (probe, different algorithm)."""

    _ALGORITHM_ID = "gradient_barcode_localizer"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "barcode-gradient"
    _PROVIDER_ID = "veilframe.code.probe"
    _IMPL_ID = "gradient_barcode_v1"

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
                source_hash=_hash_str("grad_bc_src", ocv_ver),
                implementation_hash=_hash_str("grad_bc_impl", ocv_ver),
                dependency_graph_hash=_hash_str("grad_bc_deps", ocv_ver),
                library_binary_hash=_hash_str("grad_bc_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.QR_CODE

    def detect(self, linear_srgb_f32, confidence_threshold: float = 0.5) -> List[DetectionResult]:
        """Detect barcode-like regions via Scharr gradient magnitude."""
        import cv2  # type: ignore
        import numpy as np  # type: ignore

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape[:2]

        ddx = cv2.Scharr(gray, cv2.CV_32F, 1, 0)
        ddy = cv2.Scharr(gray, cv2.CV_32F, 0, 1)
        mag = cv2.subtract(np.abs(ddx), np.abs(ddy))
        mag = cv2.convertScaleAbs(mag)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (21, 7))
        closed = cv2.morphologyEx(mag, cv2.MORPH_CLOSE, kernel)
        _, thresh = cv2.threshold(closed, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        results = []
        for cnt in contours:
            x, y, cw, ch = cv2.boundingRect(cnt)
            if cw < 20 or ch < 20:
                continue
            area_frac = (cw * ch) / (w * h)
            if area_frac > 0.25:
                continue
            bbox = BoundingBox(
                x_min=float(max(0, x)), y_min=float(max(0, y)),
                x_max=float(min(w, x + cw)), y_max=float(min(h, y + ch)),
                space=CoordinateSpace.SOURCE_DECODED,
            )
            confidence = 0.55
            if confidence >= confidence_threshold:
                results.append(DetectionResult(
                    bbox=bbox, confidence=confidence,
                    detector_class=DetectorClass.QR_CODE,
                    provider_fingerprint=self.fingerprint,
                ))

        return results
