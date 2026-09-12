"""
veilframe.image.models.report — Audit records and residual profiles.

These types are populated by the execution and verification pipeline
and collected into the ImageAuditManifest.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from .status import CheckStatus, DetectorClass, RedactionClass, SourceDependence


# ---------------------------------------------------------------------------
# Per-region redaction audit record
# ---------------------------------------------------------------------------

@dataclass
class RedactionAuditRecord:
    """Execution record for a single ConstantFill redaction.

    Every field is populated by the semantic sanitizer after executing the
    ConstantFill primitive.  The CompletenessAuditor verifies these records
    against the Transformation DAG.

    Fields
    ------
    task_id : str
        task_id from the corresponding TransformTask.
    detector_class : DetectorClass
        Which class of sensitive zone was redacted.
    mask_coverage_pct : float
        Percentage of the clipped polygon area that was filled.
        Must be 100.0 for a valid CONSTANT_FILL execution.
    replacement_coverage_pct : float
        Percentage of replacement pixels verified against fill_constant.
        Must be 100.0.
    source_dependence : SourceDependence
        Must be ABSENT for DESTRUCTIVE class operations.
    redaction_class : RedactionClass
        Must be DESTRUCTIVE.
    polygon_vertex_count : int
        Number of vertices in the mask polygon (post-clipping).
    canvas_width, canvas_height : int
        Output canvas dimensions used for clipping.
    anti_aliasing_present : bool
        Must be False — any True value triggers FAIL.
    execution_status : CheckStatus
        PASS iff all invariants hold; FAIL/UNKNOWN otherwise.
    failure_reason : Optional[str]
        Set when execution_status != PASS.
    """
    task_id: str
    detector_class: DetectorClass
    mask_coverage_pct: float
    replacement_coverage_pct: float
    source_dependence: SourceDependence
    redaction_class: RedactionClass
    polygon_vertex_count: int
    canvas_width: int
    canvas_height: int
    anti_aliasing_present: bool
    execution_status: CheckStatus
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "task_id": self.task_id,
            "detector_class": self.detector_class.value,
            "mask_coverage_pct": self.mask_coverage_pct,
            "replacement_coverage_pct": self.replacement_coverage_pct,
            "source_dependence": self.source_dependence.value,
            "redaction_class": self.redaction_class.value,
            "polygon_vertex_count": self.polygon_vertex_count,
            "canvas_width": self.canvas_width,
            "canvas_height": self.canvas_height,
            "anti_aliasing_present": self.anti_aliasing_present,
            "execution_status": self.execution_status.value,
        }
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


# ---------------------------------------------------------------------------
# Privacy residual profile (post-red-team summary)
# ---------------------------------------------------------------------------

@dataclass
class PrivacyResidualProfile:
    """Aggregated privacy residual findings from the Red-Team probe suite.

    Fields
    ------
    residual_metadata_fields : List[str]
        Names of any metadata fields detected in the output by adversarial
        probes.  Empty list indicates clean.
    residual_thumbnail_found : bool
        True if any adversarial thumbnail probe found an embedded thumbnail.
    residual_face_detections : int
        Count of face detections by adversarial probe on output pixels.
    residual_plate_detections : int
        Count of licence-plate detections by adversarial probe.
    residual_text_regions : int
        Count of text regions detected by adversarial probe.
    residual_code_detections : int
        Count of QR/barcode detections by adversarial probe.
    privacy_contract_status : CheckStatus
        PASS iff all residual counts are zero and no metadata found.
    failure_reasons : List[str]
        Human-readable list of detected violations.
    """
    residual_metadata_fields: List[str] = field(default_factory=list)
    residual_thumbnail_found: bool = False
    residual_face_detections: int = 0
    residual_plate_detections: int = 0
    residual_text_regions: int = 0
    residual_code_detections: int = 0
    privacy_contract_status: CheckStatus = CheckStatus.UNKNOWN
    failure_reasons: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "residual_metadata_fields": self.residual_metadata_fields,
            "residual_thumbnail_found": self.residual_thumbnail_found,
            "residual_face_detections": self.residual_face_detections,
            "residual_plate_detections": self.residual_plate_detections,
            "residual_text_regions": self.residual_text_regions,
            "residual_code_detections": self.residual_code_detections,
            "privacy_contract_status": self.privacy_contract_status.value,
            "failure_reasons": self.failure_reasons,
        }


# ---------------------------------------------------------------------------
# Linkage resistance report (Model C threat measurement)
# ---------------------------------------------------------------------------

@dataclass
class LinkageResistanceReport:
    """Perceptual linkage resistance measurements (Model C threat).

    IMPORTANT: These are MEASUREMENTS ONLY — not pass/fail verdicts.
    The QualityGate does not use these values for pass/fail determination.
    They are included in the audit manifest for transparency.

    Fields
    ------
    raw_source_hash : str
        SHA-256 hex of exact source file bytes.
    final_artifact_hash : str
        SHA-256 hex of the final output file bytes.
    hashes_differ : bool
        True iff raw_source_hash != final_artifact_hash (trivially true
        after any sanitization, but explicitly verified).
    phash_hamming_distance : Optional[int]
        pHash Hamming distance between source and output (0-64).
        None if computation was not possible.
    average_hash_hamming_distance : Optional[int]
        Average hash Hamming distance (0-64).
    dct_distribution_divergence : Optional[float]
        Kullback–Leibler divergence of DCT coefficient histograms.
    calibration_status : str
        "experimental" — these metrics are not normatively required for PASS.
    """
    raw_source_hash: str
    final_artifact_hash: str
    hashes_differ: bool
    phash_hamming_distance: Optional[int] = None
    average_hash_hamming_distance: Optional[int] = None
    dct_distribution_divergence: Optional[float] = None
    calibration_status: str = "experimental"

    def to_dict(self) -> dict:
        d: dict = {
            "raw_source_hash": self.raw_source_hash,
            "final_artifact_hash": self.final_artifact_hash,
            "hashes_differ": self.hashes_differ,
            "calibration_status": self.calibration_status,
        }
        if self.phash_hamming_distance is not None:
            d["phash_hamming_distance"] = self.phash_hamming_distance
        if self.average_hash_hamming_distance is not None:
            d["average_hash_hamming_distance"] = self.average_hash_hamming_distance
        if self.dct_distribution_divergence is not None:
            d["dct_distribution_divergence"] = self.dct_distribution_divergence
        return d
