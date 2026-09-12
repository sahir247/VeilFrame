"""
veilframe.image.fidelity.boundary_audit — Redaction boundary precision auditor.

Audits that redaction boundaries are geometrically precise:
  - No pixel outside the mask polygon is altered (leakage).
  - No pixel inside the mask polygon is un-filled (coverage failure).

Works by comparing `original` and `sanitized` arrays at the boundary
of the provided redaction mask.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional

from ..models.status import CheckStatus


@dataclass
class BoundaryAuditResult:
    """Result of the redaction boundary audit.

    Fields
    ------
    status : CheckStatus
    leaked_pixels : int
        Pixels outside mask that changed value (should be 0).
    unfilled_pixels : int
        Pixels inside mask that were NOT filled (should be 0).
    fill_constant_verified : bool
        True iff all masked pixels hold the expected fill constant.
    failure_reasons : List[str]
    """
    status: CheckStatus
    leaked_pixels: int = 0
    unfilled_pixels: int = 0
    fill_constant_verified: bool = False
    failure_reasons: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "leaked_pixels": self.leaked_pixels,
            "unfilled_pixels": self.unfilled_pixels,
            "fill_constant_verified": self.fill_constant_verified,
            "failure_reasons": self.failure_reasons,
        }


class BoundaryAuditor:
    """Verifies pixel-level precision of redaction boundaries.

    Parameters
    ----------
    fill_constant : tuple
        Expected fill value (r, g, b) in linear sRGB [0, 1].
    tolerance : float
        Floating-point tolerance for fill constant comparison.
        Default 0.01 (allows for JPEG re-encoding rounding).
    """

    def __init__(
        self,
        fill_constant: tuple = (0.0, 0.0, 0.0),
        tolerance: float = 0.01,
    ) -> None:
        self._fill = fill_constant
        self._tol = tolerance

    def audit(
        self,
        original: object,
        sanitized: object,
        redaction_mask: object,
    ) -> BoundaryAuditResult:
        """Audit redaction boundary precision.

        Parameters
        ----------
        original : numpy.ndarray (H, W, 3) float32
        sanitized : numpy.ndarray (H, W, 3) float32
        redaction_mask : numpy.ndarray (H, W) bool — True = should be redacted
        """
        try:
            return self._audit_impl(original, sanitized, redaction_mask)
        except Exception as exc:
            return BoundaryAuditResult(
                status=CheckStatus.UNKNOWN,
                failure_reasons=[f"BoundaryAuditor exception: {type(exc).__name__}: {exc}"],
            )

    def _audit_impl(self, original, sanitized, redaction_mask) -> BoundaryAuditResult:
        import numpy as np  # type: ignore

        orig = np.asarray(original, dtype=np.float32)
        san = np.asarray(sanitized, dtype=np.float32)
        mask = np.asarray(redaction_mask, dtype=bool)

        fill = np.array(self._fill, dtype=np.float32)

        failure_reasons: List[str] = []

        # 1. Leakage check: pixels OUTSIDE mask should be identical
        outside = ~mask
        if np.any(outside):
            diff = np.abs(orig[outside] - san[outside])
            leaked = int(np.sum(np.any(diff > self._tol, axis=-1)))
            if leaked > 0:
                failure_reasons.append(
                    f"Leakage: {leaked} pixel(s) outside mask changed value"
                )
        else:
            leaked = 0

        # 2. Coverage check: pixels INSIDE mask should hold fill constant
        if np.any(mask):
            san_masked = san[mask]
            diff_from_fill = np.abs(san_masked - fill)
            unfilled = int(np.sum(np.any(diff_from_fill > self._tol, axis=-1)))
            fill_ok = (unfilled == 0)
            if not fill_ok:
                failure_reasons.append(
                    f"Coverage: {unfilled} pixel(s) inside mask NOT filled to constant"
                )
        else:
            unfilled = 0
            fill_ok = True

        return BoundaryAuditResult(
            status=CheckStatus.PASS if not failure_reasons else CheckStatus.FAIL,
            leaked_pixels=leaked,
            unfilled_pixels=unfilled,
            fill_constant_verified=fill_ok,
            failure_reasons=failure_reasons,
        )
