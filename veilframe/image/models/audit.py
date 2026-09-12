"""
veilframe.image.models.audit — ImageAuditManifest with RFC 8785 preimages & Ed25519.

Two domain-separated canonical JSON preimages are defined:

  VEILFRAME-IDENTITY-V1 — Binds source, policy, DAG, and candidate output.
  VEILFRAME-EVIDENCE-V1 — Extends identity with execution evidence; signed.

The Ed25519 signature in ImageAuditManifest signs SHA-256(RFC8785(EvidencePreimage)).
Signature verification requires the trust anchor registry to be consulted.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional

from .status import CheckStatus, PublicationState, ArtifactTrustStatus
from .trust import SignerIdentity


# ---------------------------------------------------------------------------
# RFC 8785 canonical JSON helper
# ---------------------------------------------------------------------------

def _rfc8785_canonical(obj: dict) -> bytes:
    """Produce a deterministic JSON encoding suitable as a hash preimage.

    Uses json.dumps with sort_keys=True and no whitespace as an approximation
    of RFC 8785 JCS.  All string values are ASCII-safe (hex digests, enum
    values, ISO timestamps).

    For full RFC 8785 compliance a dedicated JCS library should be substituted
    in production; this implementation is sufficient for the schema domain.
    """
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


# ---------------------------------------------------------------------------
# VEILFRAME-IDENTITY-V1 preimage
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class IdentityPreimage:
    """RFC 8785 structured preimage for the identity hash.

    Domain: "VEILFRAME-IDENTITY-V1"

    Binds the source, policy, rule set, threat model, DAG, and candidate
    output into a single deterministic hash.  The IdentityHash is then
    included in the EvidencePreimage.

    All hash fields are lowercase hex SHA-256 digests.
    """
    candidate_output_hash: str    # SHA-256 of the raw candidate output bytes
    dag_hash: str                 # TransformationDAG.dag_hash
    graph_hash: str               # SHA-256 of canonical PrivacyGraph JSON
    policy_identity: str          # ImagePrivacyPolicy.identity
    raw_source_hash: str          # SHA-256 of exact input file bytes
    rule_set_identity: str        # NormativePolicyRuleSet.identity (== rule_set_hash)
    schema_version: str           # "2.0.0"
    threat_identity: str          # SHA-256 of canonical ThreatModelConfig JSON
    verification_plan_hash: str   # VerificationPlan.verification_plan_hash

    DOMAIN: str = field(default="VEILFRAME-IDENTITY-V1", init=False)

    def to_canonical_dict(self) -> dict:
        """Produce the RFC 8785-ordered dict (alphabetical keys)."""
        return {
            "$domain": self.DOMAIN,
            "candidate_output_hash": self.candidate_output_hash,
            "dag_hash": self.dag_hash,
            "graph_hash": self.graph_hash,
            "policy_identity": self.policy_identity,
            "raw_source_hash": self.raw_source_hash,
            "rule_set_identity": self.rule_set_identity,
            "schema_version": self.schema_version,
            "threat_identity": self.threat_identity,
            "verification_plan_hash": self.verification_plan_hash,
        }

    def compute_hash(self) -> str:
        """SHA-256(RFC8785(preimage)) — the IdentityHash."""
        return hashlib.sha256(_rfc8785_canonical(self.to_canonical_dict())).hexdigest()


# ---------------------------------------------------------------------------
# VEILFRAME-EVIDENCE-V1 preimage
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class EvidencePreimage:
    """RFC 8785 structured preimage for the evidence hash.

    Domain: "VEILFRAME-EVIDENCE-V1"

    Extends the IdentityHash with execution evidence and signer identity.
    The Ed25519 signature in ImageAuditManifest signs SHA-256(RFC8785(this)).

    Fields
    ------
    capability_registry_identity : str
        SHA-256 of the canonical CapabilityRegistry JSON.
    environment_hash : str
        RuntimeEnvironment.environment_hash.
    execution_record_hash : str
        SHA-256 of the canonical execution records JSON (DAG task records).
    final_artifact_hash : str
        SHA-256 of the bytes written to the committed output path.
        MUST equal candidate_output_hash for Publication Integrity Invariant.
    identity_hash : str
        IdentityPreimage.compute_hash().
    raw_source_hash : str
        SHA-256 of the exact input file bytes (redundant cross-check).
    signature_timestamp : str
        ISO 8601 UTC timestamp at signing (e.g. "2025-01-15T12:00:00Z").
    signer_identity : SignerIdentity
        Trust anchor identity of the signing key.
    """
    capability_registry_identity: str
    environment_hash: str
    execution_record_hash: str
    final_artifact_hash: str
    identity_hash: str
    raw_source_hash: str
    signature_timestamp: str
    signer_identity: SignerIdentity

    DOMAIN: str = field(default="VEILFRAME-EVIDENCE-V1", init=False)

    def to_canonical_dict(self) -> dict:
        return {
            "$domain": self.DOMAIN,
            "capability_registry_identity": self.capability_registry_identity,
            "environment_hash": self.environment_hash,
            "execution_record_hash": self.execution_record_hash,
            "final_artifact_hash": self.final_artifact_hash,
            "identity_hash": self.identity_hash,
            "raw_source_hash": self.raw_source_hash,
            "signature_timestamp": self.signature_timestamp,
            "signer_identity": self.signer_identity.to_dict(),
        }

    def compute_hash(self) -> str:
        """SHA-256(RFC8785(preimage)) — the EvidenceHash that is signed."""
        return hashlib.sha256(_rfc8785_canonical(self.to_canonical_dict())).hexdigest()


# ---------------------------------------------------------------------------
# Fidelity metrics summary (embedded in manifest)
# ---------------------------------------------------------------------------

@dataclass
class FidelityMetrics:
    """Outside-mask fidelity metrics computed in FIDELITY_EVALUATION space.

    All metrics are computed in linear_sRGB on the Y_linear luminance channel
    over the eroded expected-unmasked region.
    """
    outside_mask_ssim: Optional[float] = None
    outside_mask_psnr_db: Optional[float] = None
    outside_mask_mae: Optional[float] = None         # experimental
    psnr_is_exact_match: bool = False                # True when MSE == 0.0
    valid_ssim_windows: Optional[int] = None
    required_ssim_windows: Optional[int] = None
    fidelity_contract_status: CheckStatus = CheckStatus.UNKNOWN
    failure_reason: Optional[str] = None

    def to_dict(self) -> dict:
        d: dict = {
            "psnr_is_exact_match": self.psnr_is_exact_match,
            "fidelity_contract_status": self.fidelity_contract_status.value,
        }
        if self.outside_mask_ssim is not None:
            d["outside_mask_ssim"] = self.outside_mask_ssim
        if self.outside_mask_psnr_db is not None:
            d["outside_mask_psnr_db"] = self.outside_mask_psnr_db
        if self.outside_mask_mae is not None:
            d["outside_mask_mae"] = self.outside_mask_mae
        if self.valid_ssim_windows is not None:
            d["valid_ssim_windows"] = self.valid_ssim_windows
        if self.required_ssim_windows is not None:
            d["required_ssim_windows"] = self.required_ssim_windows
        if self.failure_reason is not None:
            d["failure_reason"] = self.failure_reason
        return d


# ---------------------------------------------------------------------------
# Image audit manifest (top-level signed document)
# ---------------------------------------------------------------------------

@dataclass
class ImageAuditManifest:
    """Top-level signed audit manifest for a sanitized image.

    Published atomically by the backend Publication Protocol after the
    QualityGate issues a PASS verdict.

    Fields
    ------
    schema_version : str
        "2.0.0"
    identity_preimage : IdentityPreimage
        The compiled identity preimage.
    identity_hash : str
        identity_preimage.compute_hash().
    evidence_preimage : EvidencePreimage
        The signed evidence preimage.
    evidence_hash : str
        evidence_preimage.compute_hash() — the value signed by Ed25519.
    ed25519_signature_hex : str
        Hex-encoded Ed25519 signature over evidence_hash bytes.
    public_key_pem : str
        PEM-encoded Ed25519 public key for standalone verification.
    overall_status : CheckStatus
        PASS iff all five contracts + invariants hold.
    publication_state : PublicationState
        Final state in the publication state machine.
    fidelity_metrics : FidelityMetrics
        Outside-mask fidelity evaluation results.
    privacy_contract_status : CheckStatus
        Aggregated red-team privacy verdict.
    geometry_contract_status : CheckStatus
        Geometry integrity verdict.
    integrity_contract_status : CheckStatus
        Container/format integrity verdict.
    completeness_contract_status : CheckStatus
        Execution completeness verdict.
    artifact_trust_status : ArtifactTrustStatus
        Registry-level trust evaluation of the signing key.
    failure_reasons : List[str]
        Aggregated failure reasons if overall_status != PASS.
    """
    schema_version: str
    identity_preimage: IdentityPreimage
    identity_hash: str
    evidence_preimage: EvidencePreimage
    evidence_hash: str
    ed25519_signature_hex: str
    public_key_pem: str
    overall_status: CheckStatus
    publication_state: PublicationState
    fidelity_metrics: FidelityMetrics
    privacy_contract_status: CheckStatus = CheckStatus.UNKNOWN
    geometry_contract_status: CheckStatus = CheckStatus.UNKNOWN
    integrity_contract_status: CheckStatus = CheckStatus.UNKNOWN
    completeness_contract_status: CheckStatus = CheckStatus.UNKNOWN
    artifact_trust_status: ArtifactTrustStatus = ArtifactTrustStatus.UNTRUSTED_SIGNER
    failure_reasons: List[str] = field(default_factory=list)

    def publication_integrity_holds(self) -> bool:
        """FinalArtifactHash == CandidateOutputHash."""
        return (
            self.evidence_preimage.final_artifact_hash
            == self.identity_preimage.candidate_output_hash
        )

    def to_dict(self) -> dict:
        return {
            "schema_version": self.schema_version,
            "identity_hash": self.identity_hash,
            "evidence_hash": self.evidence_hash,
            "ed25519_signature_hex": self.ed25519_signature_hex,
            "public_key_pem": self.public_key_pem,
            "overall_status": self.overall_status.value,
            "publication_state": self.publication_state.value,
            "publication_integrity_holds": self.publication_integrity_holds(),
            "privacy_contract_status": self.privacy_contract_status.value,
            "geometry_contract_status": self.geometry_contract_status.value,
            "integrity_contract_status": self.integrity_contract_status.value,
            "completeness_contract_status": self.completeness_contract_status.value,
            "artifact_trust_status": self.artifact_trust_status.value,
            "fidelity_metrics": self.fidelity_metrics.to_dict(),
            "identity_preimage": self.identity_preimage.to_canonical_dict(),
            "evidence_preimage": self.evidence_preimage.to_canonical_dict(),
            "failure_reasons": self.failure_reasons,
        }
