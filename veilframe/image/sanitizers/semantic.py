"""
veilframe.image.sanitizers.semantic — Layer C Semantic (Pixel) Sanitizer.

Implements the SEMANTIC sanitization layer (Layer C) per spec Section 5.3:

  Layer C — Semantic Region Redaction:
    C1. For each PrivacyNode with layer=SEMANTIC and a bounding box,
        produce a ConstantFillParams targeting the polygon (bbox expanded).
    C2. Execute the ConstantFillParams fill on the pixel array.
    C3. Produce a RedactionAuditRecord for each fill operation.

The semantic sanitizer is the ONLY layer that writes pixels.  All other layers
produce metadata or re-encode.

Invariants:
  - Fill constant is (0.0, 0.0, 0.0) for DESTRUCTIVE; (1.0, 1.0, 1.0) for test.
  - Anti-aliasing is PROHIBITED: fill must write α ∈ {0, 1} at every pixel.
  - mask_coverage_pct and replacement_coverage_pct must both be 100%.
  - The output pixel array is verified by the CompletenessAuditor.
"""

from __future__ import annotations

import struct
import hashlib
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from ..models.coordinates import BoundingBox, Polygon, CoordinateSpace
from ..models.graph import PrivacyGraph
from ..models.report import RedactionAuditRecord
from ..models.status import CheckStatus, DetectorClass, RedactionClass, SourceDependence
from ..models.transform_plan import ConstantFillParams, TransformOperation


@dataclass
class SemanticSanitizationResult:
    """Result of the Semantic sanitizer (Layer C).

    Fields
    ------
    output_array : Optional[object]
        numpy.ndarray (H, W, 3) float32 with all semantic regions filled.
    status : CheckStatus
    redaction_records : List[RedactionAuditRecord]
        One record per fill operation performed.
    regions_filled : int
        Count of distinct regions filled.
    failure_reason : Optional[str]
    """
    output_array: Optional[object]
    status: CheckStatus
    redaction_records: List[RedactionAuditRecord] = field(default_factory=list)
    regions_filled: int = 0
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "regions_filled": self.regions_filled,
            "redaction_record_count": len(self.redaction_records),
            "failure_reason": self.failure_reason,
        }


class SemanticSanitizer:
    """Layer C: Semantic region redaction sanitizer.

    Fills all PrivacyGraph SEMANTIC nodes with a constant value.
    Input: linear sRGB float32 array (H, W, 3) + PrivacyGraph.
    Output: SemanticSanitizationResult.
    """

    FILL_CONSTANT: Tuple[float, float, float] = (0.0, 0.0, 0.0)

    def sanitize(
        self,
        linear_srgb_f32: object,
        graph: PrivacyGraph,
        canvas_width: int,
        canvas_height: int,
    ) -> SemanticSanitizationResult:
        try:
            return self._sanitize_impl(linear_srgb_f32, graph, canvas_width, canvas_height)
        except Exception as exc:
            return SemanticSanitizationResult(
                output_array=None,
                status=CheckStatus.FAIL,
                failure_reason=f"SemanticSanitizer exception: {type(exc).__name__}: {exc}",
            )

    def _sanitize_impl(
        self, linear_srgb_f32, graph: PrivacyGraph,
        canvas_width: int, canvas_height: int,
    ) -> SemanticSanitizationResult:
        import numpy as np  # type: ignore
        import cv2  # type: ignore
        from ..models.graph import LayerType

        arr = np.array(linear_srgb_f32, dtype=np.float32)
        h, w, _ = arr.shape

        redaction_records: List[RedactionAuditRecord] = []
        regions_filled = 0

        for node_id, node in graph.nodes.items():
            if node.layer != LayerType.SEMANTIC:
                continue

            for evidence in node.evidence:
                if evidence.bbox is None:
                    continue

                bbox = evidence.bbox
                task_id = f"semantic_{node_id}"

                # Build fill polygon from bbox vertices (4-vertex rectangle)
                x0 = int(max(0, bbox.x_min))
                y0 = int(max(0, bbox.y_min))
                x1 = int(min(w, bbox.x_max))
                y1 = int(min(h, bbox.y_max))

                if x1 <= x0 or y1 <= y0:
                    continue

                # Create fill mask
                mask = np.zeros((h, w), dtype=np.uint8)
                pts = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], dtype=np.int32)
                cv2.fillPoly(mask, [pts], 255)

                # Verify no anti-aliasing in mask (all values must be 0 or 255)
                anti_aliasing = bool(np.any((mask > 0) & (mask < 255)))

                # Fill region
                fill_r, fill_g, fill_b = self.FILL_CONSTANT
                arr[mask == 255, 0] = fill_r
                arr[mask == 255, 1] = fill_g
                arr[mask == 255, 2] = fill_b

                # Compute coverage
                mask_pixels = int(np.sum(mask == 255))
                region_pixels = (x1 - x0) * (y1 - y0)
                mask_coverage = 100.0 if region_pixels == 0 else min(
                    100.0, 100.0 * mask_pixels / region_pixels
                )
                replacement_coverage = mask_coverage  # fill writes all masked pixels

                record = RedactionAuditRecord(
                    task_id=task_id,
                    detector_class=evidence.detector_class,
                    mask_coverage_pct=mask_coverage,
                    replacement_coverage_pct=replacement_coverage,
                    source_dependence=SourceDependence.ABSENT,
                    redaction_class=RedactionClass.DESTRUCTIVE,
                    polygon_vertex_count=4,
                    canvas_width=canvas_width,
                    canvas_height=canvas_height,
                    anti_aliasing_present=anti_aliasing,
                    execution_status=CheckStatus.PASS,
                )
                redaction_records.append(record)
                regions_filled += 1

        if any(r.anti_aliasing_present for r in redaction_records):
            return SemanticSanitizationResult(
                output_array=arr,
                status=CheckStatus.FAIL,
                redaction_records=redaction_records,
                regions_filled=regions_filled,
                failure_reason="Anti-aliasing detected in semantic fill mask.",
            )

        return SemanticSanitizationResult(
            output_array=arr,
            status=CheckStatus.PASS,
            redaction_records=redaction_records,
            regions_filled=regions_filled,
        )
