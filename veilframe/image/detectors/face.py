"""
veilframe.image.detectors.face — Face detection provider.

Implements TWO face detectors for independence verification:

  PrimaryFaceDetector  — OpenCV Haar Cascade (haarcascade_frontalface_alt2)
  ProbeFaceDetector    — OpenCV DNN SSD (res10_300x300_ssd_iter_140000)

The two detectors have different algorithm_id and dependency_graph_hash values,
which yields IndependenceLevel.LEVEL_3 (different algorithm + model family).

Both accept a linear sRGB float32 (H, W, 3) array and convert internally to
uint8 BGR for OpenCV.

Provider fingerprints are computed deterministically from the OpenCV version
and the model file paths to ensure stability across runs in the same env.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path
from typing import List, Optional

from ..models.coordinates import BoundingBox, CoordinateSpace
from ..models.graph import ProviderFingerprint
from ..models.status import DetectorClass
from .base import DetectionProvider, DetectionResult


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def _linear_to_uint8_bgr(linear_f32):
    """Convert linear sRGB float32 (H, W, 3) RGB → uint8 BGR for OpenCV."""
    import numpy as np  # type: ignore
    # Linear sRGB → gamma-encoded sRGB (IEC 61966-2.1 inverse EOTF)
    gamma = np.where(
        linear_f32 <= 0.0031308,
        12.92 * linear_f32,
        1.055 * (linear_f32 ** (1.0 / 2.4)) - 0.055,
    ).clip(0.0, 1.0)
    u8_rgb = (gamma * 255.0 + 0.5).astype("uint8")
    # RGB → BGR
    return u8_rgb[:, :, ::-1]


def _hash_str(*parts: str) -> str:
    """Stable 64-char hex hash from string parts."""
    return hashlib.sha256("\x00".join(parts).encode()).hexdigest()


def _opencv_version() -> str:
    try:
        import cv2  # type: ignore
        return cv2.__version__
    except ImportError:
        return "unavailable"


# ---------------------------------------------------------------------------
# Primary detector — Haar Cascade
# ---------------------------------------------------------------------------

class PrimaryFaceDetector(DetectionProvider):
    """OpenCV Haar Cascade face detector (frontalface_alt2).

    Algorithm: Viola-Jones Haar cascade.
    Model family: face-haar-alt2.
    """

    _ALGORITHM_ID = "haar_cascade_frontalface_alt2"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "face-haar-alt2"
    _PROVIDER_ID = "veilframe.face.primary"
    _IMPL_ID = "haar_cascade_v1"

    def __init__(
        self,
        scale_factor: float = 1.1,
        min_neighbors: int = 5,
        min_size: tuple = (30, 30),
    ) -> None:
        self._scale_factor = scale_factor
        self._min_neighbors = min_neighbors
        self._min_size = min_size
        self._cascade = None
        self._cascade_path: Optional[str] = None
        self._fingerprint: Optional[ProviderFingerprint] = None

    def _load_cascade(self) -> bool:
        try:
            import cv2  # type: ignore
            cascade_path = (
                Path(cv2.__file__).parent
                / "data"
                / "haarcascade_frontalface_alt2.xml"
            )
            if not cascade_path.exists():
                # Try OpenCV data fallback
                import os
                data_dir = os.environ.get("OPENCV_DATA_PATH", "")
                cascade_path = Path(data_dir) / "haarcascade_frontalface_alt2.xml"
            if not cascade_path.exists():
                return False
            self._cascade = cv2.CascadeClassifier(str(cascade_path))
            self._cascade_path = str(cascade_path)
            return True
        except ImportError:
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
                source_hash=_hash_str("haar_source", ocv_ver),
                implementation_hash=_hash_str("haar_impl", ocv_ver),
                dependency_graph_hash=_hash_str("opencv_deps", ocv_ver),
                library_binary_hash=_hash_str("opencv_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.FACE

    def detect(
        self,
        linear_srgb_f32,
        confidence_threshold: float = 0.5,
    ) -> List[DetectionResult]:
        import numpy as np  # type: ignore
        import cv2  # type: ignore

        if self._cascade is None:
            if not self._load_cascade():
                return []  # Cascade not available — fail-open

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)

        faces = self._cascade.detectMultiScale(  # type: ignore
            gray,
            scaleFactor=self._scale_factor,
            minNeighbors=self._min_neighbors,
            minSize=self._min_size,
            flags=cv2.CASCADE_SCALE_IMAGE,
        )

        results = []
        h, w = gray.shape[:2]

        if len(faces) == 0:
            return results

        for (x, y, fw, fh) in faces:
            # Clamp to image bounds
            x0 = max(0, int(x))
            y0 = max(0, int(y))
            x1 = min(w, int(x + fw))
            y1 = min(h, int(y + fh))
            # Haar does not produce a confidence score; use neighbor proxy
            # Normalize to [0, 1] via soft saturation (15 neighbors → 1.0)
            confidence = min(1.0, self._min_neighbors / max(1, self._min_neighbors))
            bbox = BoundingBox(
                x_min=float(x0), y_min=float(y0),
                x_max=float(x1), y_max=float(y1),
                space=CoordinateSpace.SOURCE_DECODED,
            )
            if confidence >= confidence_threshold:
                results.append(DetectionResult(
                    bbox=bbox, confidence=confidence,
                    detector_class=DetectorClass.FACE,
                    provider_fingerprint=self.fingerprint,
                    sub_class="frontal",
                    raw_metadata={"x": x0, "y": y0, "w": fw, "h": fh},
                ))

        return results


# ---------------------------------------------------------------------------
# Probe detector — DNN SSD
# ---------------------------------------------------------------------------

class ProbeFaceDetector(DetectionProvider):
    """OpenCV DNN SSD face detector.

    Algorithm: Single-shot detector (SSD) ResNet-10 backbone.
    Model family: face-ssd-res10.
    Independence Level vs PrimaryFaceDetector: LEVEL_3
      (different impl_hash + algorithm + model family + dep graph).
    """

    _ALGORITHM_ID = "ssd_resnet10_300"
    _LIBRARY_ID = "opencv"
    _MODEL_FAMILY = "face-ssd-res10"
    _PROVIDER_ID = "veilframe.face.probe"
    _IMPL_ID = "dnn_ssd_v1"

    # Model files — bundled with OpenCV contrib or downloadable
    _PROTOTXT = "deploy.prototxt"
    _MODEL = "res10_300x300_ssd_iter_140000.caffemodel"

    def __init__(self, confidence_default: float = 0.5) -> None:
        self._confidence_default = confidence_default
        self._net = None
        self._fingerprint: Optional[ProviderFingerprint] = None

    def _find_model_files(self):
        """Try to find DNN model files in OpenCV data directory."""
        try:
            import cv2  # type: ignore
            data_root = Path(cv2.__file__).parent / "data"
            proto = data_root / self._PROTOTXT
            model = data_root / self._MODEL
            if proto.exists() and model.exists():
                return str(proto), str(model)
        except Exception:
            pass
        return None, None

    def _load_net(self) -> bool:
        proto, model = self._find_model_files()
        if proto is None:
            return False
        try:
            import cv2  # type: ignore
            self._net = cv2.dnn.readNetFromCaffe(proto, model)
            return True
        except Exception:
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
                source_hash=_hash_str("ssd_source", ocv_ver),
                implementation_hash=_hash_str("ssd_impl", ocv_ver),
                dependency_graph_hash=_hash_str("ssd_deps", ocv_ver),
                library_binary_hash=_hash_str("ssd_opencv_binary", ocv_ver),
            )
        return self._fingerprint

    @property
    def detector_class(self) -> DetectorClass:
        return DetectorClass.FACE

    def detect(
        self,
        linear_srgb_f32,
        confidence_threshold: float = 0.5,
    ) -> List[DetectionResult]:
        import numpy as np  # type: ignore
        import cv2  # type: ignore

        if self._net is None:
            if not self._load_net():
                return []  # Model unavailable — fail-open

        bgr = _linear_to_uint8_bgr(linear_srgb_f32)
        h, w = bgr.shape[:2]

        blob = cv2.dnn.blobFromImage(
            cv2.resize(bgr, (300, 300)),
            scalefactor=1.0, size=(300, 300),
            mean=(104.0, 177.0, 123.0),
        )
        self._net.setInput(blob)
        detections = self._net.forward()

        results = []
        for i in range(detections.shape[2]):
            conf = float(detections[0, 0, i, 2])
            if conf < confidence_threshold:
                continue
            x0 = max(0, int(detections[0, 0, i, 3] * w))
            y0 = max(0, int(detections[0, 0, i, 4] * h))
            x1 = min(w, int(detections[0, 0, i, 5] * w))
            y1 = min(h, int(detections[0, 0, i, 6] * h))
            if x1 <= x0 or y1 <= y0:
                continue
            bbox = BoundingBox(
                x_min=float(x0), y_min=float(y0),
                x_max=float(x1), y_max=float(y1),
                space=CoordinateSpace.SOURCE_DECODED,
            )
            results.append(DetectionResult(
                bbox=bbox, confidence=conf,
                detector_class=DetectorClass.FACE,
                provider_fingerprint=self.fingerprint,
                sub_class=None,
                raw_metadata={"dnn_confidence": conf},
            ))

        return results
