"""
veilframe.image.redteam.probes.text_probe — Text detection attack probe.
"""
from __future__ import annotations
from typing import Optional
from ...models.status import CheckStatus
from .base import AttackProbe, ProbeResult


class TextProbe(AttackProbe):
    """Attack: run text detector on sanitized output."""

    @property
    def name(self) -> str:
        return "text"

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
            from ...detectors.text import MSERTextDetector
            detections = MSERTextDetector().safe_detect(sanitized_array, confidence_threshold=0.7)
        except Exception as e:
            return ProbeResult(probe_name=self.name, status=CheckStatus.UNKNOWN,
                               failure_reason=f"Text detector error: {e}")
        # Only flag high-confidence text detections (MSER is noisy)
        high_conf = [d for d in detections if d.confidence >= 0.8]
        if high_conf:
            return ProbeResult(probe_name=self.name, status=CheckStatus.FAIL,
                               failure_reason=f"{len(high_conf)} high-confidence text region(s) in sanitized output",
                               findings=[f"Text at {d.bbox} conf={d.confidence:.2f}" for d in high_conf[:5]])
        return ProbeResult(probe_name=self.name, status=CheckStatus.PASS)
