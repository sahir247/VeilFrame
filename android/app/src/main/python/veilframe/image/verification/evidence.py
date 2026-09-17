"""
veilframe.image.verification.evidence — Strongly-typed verification evidence artifacts.

Each verification component produces a typed evidence record.  These are
aggregated by the CompletenessAuditor and committed into the execution_record_hash
field of the EvidencePreimage.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Any

from ..models.status import CheckStatus, DetectorClass, IndependenceLevel


# ---------------------------------------------------------------------------
# Per-component evidence records
# ---------------------------------------------------------------------------

@dataclass
class RedTeamProbeEvidence:
    """Evidence from a single red-team adversarial probe."""
    probe_id: str
    probe_class: DetectorClass
    independence_level: IndependenceLevel
    detections_found: int          # > 0 means privacy leak
    status: CheckStatus
    provider_implementation_id: str
    provider_algorithm_id: str
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "probe_id": self.probe_id,
            "probe_class": self.probe_class.value,
            "independence_level": self.independence_level.value,
            "detections_found": self.detections_found,
            "status": self.status.value,
            "provider_implementation_id": self.provider_implementation_id,
            "provider_algorithm_id": self.provider_algorithm_id,
        }
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


@dataclass
class MetadataProbeEvidence:
    """Evidence from the metadata / container adversarial probes."""
    probe_id: str
    residual_fields: List[str]
    residual_thumbnail_found: bool
    container_valid: bool
    status: CheckStatus
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "probe_id": self.probe_id,
            "residual_fields": self.residual_fields,
            "residual_thumbnail_found": self.residual_thumbnail_found,
            "container_valid": self.container_valid,
            "status": self.status.value,
        }
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


@dataclass
class ExecutionRecord:
    """Aggregated evidence from the execution phase.

    Contains per-task records for all Transform and Verification tasks.
    Hashed into execution_record_hash for the EvidencePreimage.
    """
    dag_hash: str
    verification_plan_hash: str
    redteam_probes: List[RedTeamProbeEvidence] = field(default_factory=list)
    metadata_probes: List[MetadataProbeEvidence] = field(default_factory=list)
    extra_records: Dict[str, Any] = field(default_factory=dict)

    def compute_hash(self) -> str:
        """SHA-256 of canonical JSON — used as execution_record_hash."""
        d = {
            "dag_hash": self.dag_hash,
            "verification_plan_hash": self.verification_plan_hash,
            "redteam_probes": [p.to_dict() for p in self.redteam_probes],
            "metadata_probes": [p.to_dict() for p in self.metadata_probes],
        }
        canonical = json.dumps(d, sort_keys=True, ensure_ascii=True)
        return hashlib.sha256(canonical.encode()).hexdigest()

    def to_dict(self) -> dict:
        return {
            "dag_hash": self.dag_hash,
            "verification_plan_hash": self.verification_plan_hash,
            "execution_record_hash": self.compute_hash(),
            "redteam_probes": [p.to_dict() for p in self.redteam_probes],
            "metadata_probes": [p.to_dict() for p in self.metadata_probes],
        }
