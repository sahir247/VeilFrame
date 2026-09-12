"""
veilframe.image.redteam.probes.code_probe — QR/barcode attack probe.
"""
from __future__ import annotations
from typing import Optional
from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class CodeProbe(AttackProbe):
    """Attack: run QR code detector on sanitized output."""

    @property
    def name(self) -> str:
        return "qr_code"

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
            from ...detectors.code import PrimaryQRCodeDetector
            detections = PrimaryQRCodeDetector().safe_detect(sanitized_array)
        except Exception as e:
            return ProbeResult(probe_name=self.name, status=CheckStatus.UNKNOWN,
                               failure_reason=f"QR detector error: {e}")
        if detections:
            return ProbeResult(probe_name=self.name, status=CheckStatus.FAIL,
                               failure_reason=f"{len(detections)} QR code(s) detected in sanitized output",
                               findings=[f"QR at {d.bbox}" for d in detections])
        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
