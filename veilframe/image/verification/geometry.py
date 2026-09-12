"""
veilframe.image.verification.geometry — GeometryIntegrityAuditor.

Enforces spec Section 2 — Geometry Contract:

    GeometryResidual = ||ObservedGeometry - ExpectedGeometry|| ≤ ε_geom

The gate evaluates the Geometry Contract BEFORE any fidelity metrics.
Any unauthorized crop, scale drift, aspect-ratio change, or padding
immediately triggers GEOMETRY_CONTRACT_VIOLATION → FAIL.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional

from ..models.coordinates import TransformationGeometryMap
from ..models.status import CheckStatus


class GeometryContractViolation(Exception):
    """Raised when observed output geometry violates the Geometry Contract."""
    def __init__(self, residual: float, tolerance: float, observed_w: int, observed_h: int,
                 expected_w: int, expected_h: int) -> None:
        super().__init__(
            f"GEOMETRY_CONTRACT_VIOLATION: residual={residual:.4f}px > "
            f"tolerance={tolerance:.4f}px "
            f"(observed={observed_w}×{observed_h}, expected={expected_w}×{expected_h})"
        )
        self.residual = residual
        self.tolerance = tolerance
        self.observed_w = observed_w
        self.observed_h = observed_h
        self.expected_w = expected_w
        self.expected_h = expected_h


@dataclass
class GeometryAuditResult:
    """Result of the geometry integrity check."""
    status: CheckStatus
    observed_width: int
    observed_height: int
    expected_width: int
    expected_height: int
    geometry_residual: float
    tolerance: float
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "status": self.status.value,
            "observed_width": self.observed_width,
            "observed_height": self.observed_height,
            "expected_width": self.expected_width,
            "expected_height": self.expected_height,
            "geometry_residual": self.geometry_residual,
            "tolerance": self.tolerance,
        }
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


class GeometryIntegrityAuditor:
    """Independent auditor enforcing the Geometry Contract.

    Usage
    -----
    auditor = GeometryIntegrityAuditor(geometry_map)
    result = auditor.audit(observed_width, observed_height)
    if result.status != CheckStatus.PASS:
        raise GeometryContractViolation(...)
    """

    def __init__(self, geometry_map: TransformationGeometryMap) -> None:
        self._map = geometry_map

    def audit(self, observed_width: int, observed_height: int) -> GeometryAuditResult:
        """Compare observed output dimensions against DAG-declared expected dims.

        The Geometry Contract is evaluated using the Euclidean pixel residual:
            ||ObservedGeometry - ExpectedGeometry||_2

        Returns a GeometryAuditResult.  The QualityGate must reject any result
        with status != PASS before computing any fidelity metrics.
        """
        residual = self._map.geometry_residual(observed_width, observed_height)
        tolerance = self._map.geometry_tolerance_pixels
        expected_w = self._map.output_width
        expected_h = self._map.output_height

        if residual > tolerance:
            reason = (
                f"GEOMETRY_CONTRACT_VIOLATION: residual={residual:.4f}px > "
                f"tolerance={tolerance:.4f}px "
                f"(observed={observed_width}×{observed_height}, "
                f"expected={expected_w}×{expected_h})"
            )
            return GeometryAuditResult(
                status=CheckStatus.FAIL,
                observed_width=observed_width,
                observed_height=observed_height,
                expected_width=expected_w,
                expected_height=expected_h,
                geometry_residual=residual,
                tolerance=tolerance,
                failure_reason=reason,
            )

        return GeometryAuditResult(
            status=CheckStatus.PASS,
            observed_width=observed_width,
            observed_height=observed_height,
            expected_width=expected_w,
            expected_height=expected_h,
            geometry_residual=residual,
            tolerance=tolerance,
        )
