"""
veilframe.image.fidelity.region_fidelity — Outside-mask fidelity computation.

Computes SSIM, PSNR, and MAE metrics ONLY in the COMPLEMENT of all redaction
masks (i.e., the public/non-redacted region).

Fidelity Contract (Spec §6.3):
  - SSIM(non-redacted) ≥ 0.95 — structural similarity must not degrade.
  - PSNR(non-redacted) ≥ 35 dB — peak signal-to-noise must not degrade.
  - MAE(non-redacted) ≤ 0.02 — mean absolute error ≤ 2% of pixel range.

Any metric below its threshold → FidelityContract FAIL.

The fidelity engine works in linear sRGB float32 space (values [0, 1]).
Metrics are computed in a sliding window over the fidelity evaluation space.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from ..models.audit import FidelityMetrics
from ..models.status import CheckStatus


@dataclass
class RegionFidelityResult:
    """Fidelity metrics computed in the non-redacted region.

    Fields
    ------
    status : CheckStatus
        PASS iff all three metrics meet thresholds.
    ssim : Optional[float]
        Mean SSIM over non-redacted windows.  None if not computed.
    psnr_db : Optional[float]
        Peak SNR in dB over non-redacted region.
    mae : Optional[float]
        Mean absolute error over non-redacted region.
    non_redacted_pixel_fraction : float
        Fraction of the canvas that was evaluated (should be 1 - redacted).
    failure_reasons : List[str]
    """
    status: CheckStatus
    ssim: Optional[float] = None
    psnr_db: Optional[float] = None
    mae: Optional[float] = None
    non_redacted_pixel_fraction: float = 0.0
    failure_reasons: List[str] = field(default_factory=list)

    def to_fidelity_metrics(self) -> FidelityMetrics:
        return FidelityMetrics(
            fidelity_contract_status=self.status,
            outside_mask_ssim=self.ssim,
            outside_mask_psnr_db=self.psnr_db,
            outside_mask_mae=self.mae,
            failure_reason="; ".join(self.failure_reasons) if self.failure_reasons else None,
        )

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "ssim": self.ssim,
            "psnr_db": self.psnr_db,
            "mae": self.mae,
            "non_redacted_pixel_fraction": self.non_redacted_pixel_fraction,
            "failure_reasons": self.failure_reasons,
        }


class RegionFidelityEngine:
    """Compute fidelity metrics in the non-redacted region.

    Thresholds (configurable at construction, defaults from spec):
      - ssim_threshold: 0.95
      - psnr_threshold_db: 35.0
      - mae_threshold: 0.02
    """

    def __init__(
        self,
        ssim_threshold: float = 0.95,
        psnr_threshold_db: float = 35.0,
        mae_threshold: float = 0.02,
        window_size: int = 8,
    ) -> None:
        self._ssim_thr = ssim_threshold
        self._psnr_thr = psnr_threshold_db
        self._mae_thr = mae_threshold
        self._win = window_size

    def compute(
        self,
        original: object,    # numpy float32 (H, W, 3) linear sRGB
        sanitized: object,   # numpy float32 (H, W, 3) linear sRGB
        redaction_mask: object,  # numpy bool/uint8 (H, W) — True = redacted pixel
    ) -> RegionFidelityResult:
        """Compute fidelity metrics in non-redacted region."""
        try:
            return self._compute_impl(original, sanitized, redaction_mask)
        except Exception as exc:
            return RegionFidelityResult(
                status=CheckStatus.UNKNOWN,
                failure_reasons=[f"FidelityEngine exception: {type(exc).__name__}: {exc}"],
            )

    def _compute_impl(self, original, sanitized, redaction_mask) -> RegionFidelityResult:
        import numpy as np  # type: ignore

        orig = np.asarray(original, dtype=np.float32)
        san = np.asarray(sanitized, dtype=np.float32)
        mask = np.asarray(redaction_mask, dtype=bool)

        h, w, c = orig.shape
        assert san.shape == orig.shape, "Shape mismatch between original and sanitized"
        assert mask.shape[:2] == (h, w), "Mask shape mismatch"

        # Non-redacted mask: True = public (should be identical)
        public_mask = ~mask

        n_public = int(np.sum(public_mask))
        n_total = h * w
        non_redacted_fraction = n_public / max(1, n_total)

        if n_public == 0:
            # 100% redacted — nothing to evaluate for fidelity
            return RegionFidelityResult(
                status=CheckStatus.PASS,
                ssim=1.0, psnr_db=float("inf"), mae=0.0,
                non_redacted_pixel_fraction=0.0,
            )

        # Extract public pixels
        orig_public = orig[public_mask]  # shape (n_public, 3)
        san_public = san[public_mask]

        # MAE
        mae = float(np.mean(np.abs(orig_public - san_public)))

        # PSNR
        mse = float(np.mean((orig_public - san_public) ** 2))
        if mse < 1e-12:
            psnr_db = float("inf")
        else:
            psnr_db = 10.0 * math.log10(1.0 / mse)

        # SSIM (simplified per-channel, averaged)
        ssim = self._compute_ssim(orig, san, public_mask, h, w)

        # Evaluate thresholds
        failure_reasons: List[str] = []
        if ssim < self._ssim_thr:
            failure_reasons.append(
                f"SSIM {ssim:.4f} < threshold {self._ssim_thr:.4f}"
            )
        if psnr_db < self._psnr_thr:
            failure_reasons.append(
                f"PSNR {psnr_db:.2f} dB < threshold {self._psnr_thr:.2f} dB"
            )
        if mae > self._mae_thr:
            failure_reasons.append(
                f"MAE {mae:.6f} > threshold {self._mae_thr:.6f}"
            )

        return RegionFidelityResult(
            status=CheckStatus.PASS if not failure_reasons else CheckStatus.FAIL,
            ssim=ssim,
            psnr_db=psnr_db,
            mae=mae,
            non_redacted_pixel_fraction=non_redacted_fraction,
            failure_reasons=failure_reasons,
        )

    def _compute_ssim(self, orig, san, public_mask, h, w) -> float:
        """Compute mean SSIM over non-masked windows."""
        import numpy as np  # type: ignore

        # Constants from SSIM paper (Wang et al. 2004)
        C1 = (0.01) ** 2
        C2 = (0.03) ** 2
        win = self._win
        ssim_values: List[float] = []

        for yi in range(0, h - win + 1, win):
            for xi in range(0, w - win + 1, win):
                window_mask = public_mask[yi: yi + win, xi: xi + win]
                if not np.any(window_mask):
                    continue  # Skip fully-redacted windows
                o = orig[yi: yi + win, xi: xi + win][window_mask]
                s = san[yi: yi + win, xi: xi + win][window_mask]
                if len(o) < 4:
                    continue
                mu1 = np.mean(o)
                mu2 = np.mean(s)
                sig1sq = np.var(o)
                sig2sq = np.var(s)
                sig12 = float(np.mean((o - mu1) * (s - mu2)))
                ssim = (
                    (2 * mu1 * mu2 + C1) * (2 * sig12 + C2)
                    / ((mu1 ** 2 + mu2 ** 2 + C1) * (sig1sq + sig2sq + C2))
                )
                ssim_values.append(float(ssim))

        if not ssim_values:
            return 1.0  # No evaluable windows → perfect (nothing changed)
        return float(sum(ssim_values) / len(ssim_values))
