"""
veilframe.image.redteam.probes.face_probe — Face detection attack probe.

Runs the PrimaryFaceDetector against the sanitized pixel array.
Any face detected in the sanitized output → FAIL.
"""

from __future__ import annotations

from typing import List, Optional

from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class FaceProbe(AttackProbe):
    """Attack: run face detector on sanitized output to find residual faces."""

    @property
    def name(self) -> str:
        return "face"

    def attack(
        self,
        original_bytes: bytes,
        sanitized_bytes: bytes,
        original_array: Optional[object] = None,
        sanitized_array: Optional[object] = None,
    ) -> ProbeResult:
        if sanitized_array is None:
            # Decode sanitized bytes to pixel array
            try:
                import io
                import numpy as np  # type: ignore
                from PIL import Image  # type: ignore
                img = Image.open(io.BytesIO(sanitized_bytes)).convert("RGB")
                sanitized_array = np.array(img, dtype=np.float32) / 255.0
            except Exception as e:
                return ProbeResult(
                    probe_name=self.name,
                    status=CheckStatus.UNKNOWN,
                    failure_reason=f"Could not decode sanitized bytes: {e}",
                )

        try:
            from ...detectors.face import PrimaryFaceDetector
            detector = PrimaryFaceDetector()
            detections = detector.safe_detect(sanitized_array, confidence_threshold=0.5)
        except Exception as e:
            return ProbeResult(
                probe_name=self.name,
                status=CheckStatus.UNKNOWN,
                failure_reason=f"Face detector error: {e}",
            )

        if detections:
            findings = [
                f"Face detected at bbox {d.bbox} confidence={d.confidence:.2f}"
                for d in detections
            ]
            return ProbeResult(
                probe_name=self.name,
                status=CheckStatus.FAIL,
                failure_reason=f"{len(detections)} face(s) detected in sanitized output",
                findings=findings,
            )

        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
