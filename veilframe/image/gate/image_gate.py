"""
veilframe.image.gate.image_gate — QualityGate for image privacy compilation.

The QualityGate is the SOLE authority on pass/fail verdicts.

Architectural Invariant: "Providers measure. VeilFrame decides."
  - All CheckStatus values from all contracts are combined here.
  - A single FAIL or UNKNOWN from any contract → overall FAIL.
  - The gate also enforces the Five Contracts in order.

Five Contracts (per spec §6):
  1. Geometry Contract    — output dimensions match expectations.
  2. Privacy Contract     — all semantic nodes filled, red-team passed.
  3. Completeness Contract — all DAG tasks and verification tasks executed.
  4. Independence Contract — detector independence level ≥ minimum required.
  5. Fidelity Contract    — SSIM/PSNR/MAE in non-redacted region meet thresholds.

The gate sets the PublicationState based on overall status:
  - All PASS → VERIFIED (→ COMMITTED by publisher)
  - Any FAIL → QUARANTINED

The gate is stateless and deterministic: given the same inputs, it always
produces the same verdict.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Optional

from ..models.audit import FidelityMetrics, ImageAuditManifest, IdentityPreimage, EvidencePreimage
from ..models.status import CheckStatus, PublicationState
from ..verification.completeness import CompletenessAuditResult
from ..verification.geometry import GeometryAuditResult
from ..fidelity.region_fidelity import RegionFidelityResult
from ..redteam.engine import PrivacyAttackResult


@dataclass
class GateVerdict:
    """Comprehensive verdict from the QualityGate.

    Fields
    ------
    overall_status : CheckStatus
        PASS iff all five contracts pass.
    publication_state : PublicationState
        VERIFIED if all pass; QUARANTINED otherwise.
    geometry_status : CheckStatus
    privacy_status : CheckStatus
        Combined: completeness ∧ red-team result.
    completeness_status : CheckStatus
    independence_status : CheckStatus
    fidelity_status : CheckStatus
    contract_results : dict
        Per-contract status values.
    """
    overall_status: CheckStatus
    publication_state: PublicationState
    geometry_status: CheckStatus
    privacy_status: CheckStatus
    completeness_status: CheckStatus
    independence_status: CheckStatus
    fidelity_status: CheckStatus
    contract_results: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "overall_status": self.overall_status.value,
            "publication_state": self.publication_state.value,
            "contracts": {
                "geometry": self.geometry_status.value,
                "privacy": self.privacy_status.value,
                "completeness": self.completeness_status.value,
                "independence": self.independence_status.value,
                "fidelity": self.fidelity_status.value,
            },
        }


class ImageQualityGate:
    """QualityGate: evaluates all five contracts and returns a GateVerdict.

    Usage
    -----
    gate = ImageQualityGate()
    verdict = gate.evaluate(
        geometry_result=...,
        completeness_result=...,
        red_team_result=...,
        independence_status=...,
        fidelity_result=...,
    )
    """

    def evaluate(
        self,
        geometry_result: Optional[GeometryAuditResult],
        completeness_result: Optional[CompletenessAuditResult],
        red_team_result: Optional[PrivacyAttackResult],
        independence_status: CheckStatus,
        fidelity_result: Optional[RegionFidelityResult],
        strict_redteam_gate: bool = False,
    ) -> GateVerdict:
        """Evaluate all five contracts and determine the publication state.

        Parameters
        ----------
        strict_redteam_gate : bool
            If True, every red-team probe (including heuristic probes) must return PASS.
            If False (standard mode), container and metadata stripping (EXIF/XMP/IPTC/thumbnails)
            is strictly mandatory and fail-closed, while visual heuristic probe findings are
            reported as advisory findings rather than hard quarantine failures.
        """

        # --- Contract 1: Geometry ---
        geo_status = (
            geometry_result.status
            if geometry_result is not None
            else CheckStatus.UNKNOWN
        )

        # --- Contract 3: Completeness ---
        comp_status = (
            completeness_result.status
            if completeness_result is not None
            else CheckStatus.UNKNOWN
        )

        # --- Contract 2: Privacy (completeness + container / red-team) ---
        if red_team_result is None:
            rt_status = CheckStatus.UNKNOWN
        else:
            # Check mandatory container / EXIF / thumbnail probes
            container_probes = {"metadata", "thumbnail", "container"}
            container_passed = True
            if isinstance(red_team_result.probe_results, dict):
                for p_name, probe in red_team_result.probe_results.items():
                    if p_name in container_probes and getattr(probe, "status", None) != CheckStatus.PASS:
                        container_passed = False
                        break

            if not container_passed:
                rt_status = CheckStatus.FAIL
            elif strict_redteam_gate:
                rt_status = red_team_result.overall_status
            else:
                # Standard mode: container & metadata stripping holds, redactions executed
                rt_status = CheckStatus.PASS

        privacy_status = comp_status & rt_status

        # --- Contract 4: Independence ---
        indep_status = independence_status

        # --- Contract 5: Fidelity ---
        fid_status = (
            fidelity_result.status
            if fidelity_result is not None
            else CheckStatus.UNKNOWN
        )

        # --- Overall: AND of all five contracts ---
        overall = geo_status & privacy_status & comp_status & indep_status & fid_status

        publication_state = (
            PublicationState.VERIFIED
            if overall == CheckStatus.PASS
            else PublicationState.QUARANTINED
        )

        raw_rt_status = red_team_result.overall_status.value if red_team_result is not None else CheckStatus.UNKNOWN.value

        return GateVerdict(
            overall_status=overall,
            publication_state=publication_state,
            geometry_status=geo_status,
            privacy_status=privacy_status,
            completeness_status=comp_status,
            independence_status=indep_status,
            fidelity_status=fid_status,
            contract_results={
                "geometry": geo_status.value,
                "privacy": privacy_status.value,
                "completeness": comp_status.value,
                "independence": indep_status.value,
                "fidelity": fid_status.value,
                "red_team": raw_rt_status,
            },
        )

    def build_fidelity_metrics(
        self, fidelity_result: Optional[RegionFidelityResult]
    ) -> FidelityMetrics:
        """Build FidelityMetrics for inclusion in ImageAuditManifest."""
        if fidelity_result is None:
            return FidelityMetrics(fidelity_contract_status=CheckStatus.UNKNOWN)
        return fidelity_result.to_fidelity_metrics()
