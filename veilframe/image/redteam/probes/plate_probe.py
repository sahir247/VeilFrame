"""
veilframe.image.redteam.probes.plate_probe — License plate attack probe.
"""
from __future__ import annotations
from typing import List, Optional
from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class PlateProbe(AttackProbe):
    """Attack: run plate detector on sanitized output."""

    @property
    def name(self) -> str:
        return "plate"

    def attack(self, original_bytes, sanitized_bytes, original_array=None, sanitized_array=None) -> ProbeResult:
        if sanitized_array is None:
            try:
                import io, numpy as np
                from PIL import Image
                img = Image.open(io.BytesIO(sanitized_bytes)).convert("RGB")
                sanitized_array = np.array(img, dtype=np.float32) / 255.0
            except Exception as e:
                return ProbeResult(probe_name=self.name, status=CheckStatus.UNKNOWN,
                                   failure_reason=f"Decode error: {e}")
        try:
            from ...detectors.plate import PrimaryPlateDetector
            detections = PrimaryPlateDetector().safe_detect(sanitized_array)
        except Exception as e:
            return ProbeResult(probe_name=self.name, status=CheckStatus.UNKNOWN,
                               failure_reason=f"Plate detector error: {e}")
        if detections:
            return ProbeResult(probe_name=self.name, status=CheckStatus.FAIL,
                               failure_reason=f"{len(detections)} plate(s) detected in sanitized output",
                               findings=[f"Plate at {d.bbox}" for d in detections])
        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
