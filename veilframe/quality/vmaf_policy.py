"""
VeilFrame Production VMAF Policy Resolution & Provenance Engine.

Architectural Principles:
1. Separation of Concerns: Providers measure. Policy resolves and decides.
2. Technical Classification: video -> technical classification -> verified model + hash -> qualified policy profile -> decision.
3. Explicit Policy Modes:
     - "disabled": VMAF gate inactive.
     - "audit": Mandatory measurement and evidence, but CANNOT reject production output. (VeilFrame v1 default).
     - "validated_model": Gate based on a separately qualified threshold for a specific domain/model.
     - "validated_global": Global scalar gate (unqualified per empirical research).
4. No Manufactured Thresholds: Unqualified domains remain status="not_qualified" with audit fallback.
5. Strict Provenance: Every evaluation records model_id, model_sha256, policy_id, study_id, and dataset version.
"""
from dataclasses import dataclass
from typing import Any, Dict, Optional

from .vmaf_models import classify_resolution, is_hfr

VMAF_CALIBRATION_STUDY_ID = "VF-CAL-VMAF-2026-09"
VMAF_CALIBRATION_DATASET_VERSION = "1.2.0"
VMAF_CALIBRATION_ANALYSIS_VERSION = "1.2.0"
VMAF_AUDIT_POLICY_ID = "vmaf-policy-0"


@dataclass(frozen=True)
class VmafPolicyProfile:
    """
    Formally qualified or audit policy profile for a specific VMAF domain.
    """
    domain: str                         # e.g. "1080p_sdr", "1080p_hfr", "2160p_sdr", "2160p_hfr"
    policy_id: str                      # e.g. "vmaf-policy-0" (audit baseline) or "vmaf-1080p-sdr-v1"
    policy_version: str                 # policy version string
    status: str                         # "not_qualified" | "audit_only" | "validated" | "disabled" | "not_applicable"
    mean_min: Optional[float] = None    # Calibrated threshold minimum for VMAF mean
    p5_min: Optional[float] = None      # Calibrated threshold minimum for VMAF P5 tail
    worst_min: Optional[float] = None   # Calibrated threshold minimum for worst frame
    calibration_study_id: str = VMAF_CALIBRATION_STUDY_ID
    dataset_version: str = VMAF_CALIBRATION_DATASET_VERSION
    analysis_version: str = VMAF_CALIBRATION_ANALYSIS_VERSION


# ── Official Domain Policy Registry ────────────────────────────────────── #
# Invariance: "Never manufacture a threshold just because a model exists."
# Empirical research confirmed all 4 domains are currently NOT_QUALIFIED:
#   - 2160p SDR & HFR: Insufficient sequence groups (1 < 3 required for split).
#   - 1080p HFR: NO_FEASIBLE_THRESHOLD on development set.
#   - 1080p SDR: Fails held-out validation (held-out FRR = 33.33% > 5.0%).
# Therefore, all 4 domains default to status="not_qualified".
OFFICIAL_DOMAIN_POLICIES: Dict[str, VmafPolicyProfile] = {
    "1080p_sdr": VmafPolicyProfile(
        domain="1080p_sdr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "1080p_hfr": VmafPolicyProfile(
        domain="1080p_hfr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "2160p_sdr": VmafPolicyProfile(
        domain="2160p_sdr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "2160p_hfr": VmafPolicyProfile(
        domain="2160p_hfr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
}


def classify_technical_domain(
    width: int,
    height: int,
    fps: float,
    is_hdr: bool = False,
) -> str:
    """
    Resolves the technical domain string from video characteristics:
      - "1080p_sdr" | "1080p_hfr"
      - "2160p_sdr" | "2160p_hfr"
      - "secondary" (720p/SD)
      - "unsupported" (1440p / intermediate)
      - "hdr" (High Dynamic Range)
    """
    if is_hdr:
        return "hdr"

    res_class = classify_resolution(width, height)
    if res_class in ("secondary", "unsupported"):
        return res_class

    hfr_flag = is_hfr(fps)
    return f"{res_class}_{'hfr' if hfr_flag else 'sdr'}"


def resolve_vmaf_policy(
    domain: str,
    gate_mode: str = "audit",
) -> VmafPolicyProfile:
    """
    Resolves the VMAF policy profile based on technical domain and configured gate mode.

    Modes:
      - "disabled": Gate is disabled.
      - "audit": Returns audit baseline profile (never rejects).
      - "validated_model": Returns qualified domain profile if validated,
                           otherwise returns profile with status="not_qualified" (triggers audit fallback).
      - "validated_global": Returns profile with status="not_qualified" (global scalar failed qualification).
    """
    if gate_mode == "disabled":
        return VmafPolicyProfile(
            domain=domain,
            policy_id="vmaf-policy-disabled",
            policy_version="0.0.0",
            status="disabled",
        )

    if gate_mode == "audit":
        return VmafPolicyProfile(
            domain=domain,
            policy_id=VMAF_AUDIT_POLICY_ID,
            policy_version="1.0.0",
            status="audit_only",
            mean_min=None,
            p5_min=None,
            worst_min=None,
        )

    if gate_mode == "validated_global":
        # Global scalar failed qualification: research confirmed NO_FEASIBLE_THRESHOLD
        return VmafPolicyProfile(
            domain=domain,
            policy_id="vmaf-global-unqualified",
            policy_version="0.0.0",
            status="not_qualified",
            mean_min=None,
            p5_min=None,
            worst_min=None,
        )

    # validated_model mode:
    if domain in ("hdr", "not_applicable_hdr"):
        return VmafPolicyProfile(
            domain=domain,
            policy_id=VMAF_AUDIT_POLICY_ID,
            policy_version="1.0.0",
            status="not_applicable",
        )

    if domain in ("secondary", "unsupported"):
        return VmafPolicyProfile(
            domain=domain,
            policy_id=VMAF_AUDIT_POLICY_ID,
            policy_version="1.0.0",
            status="unsupported_domain",
        )

    # Look up registered domain
    profile = OFFICIAL_DOMAIN_POLICIES.get(domain)
    if profile is not None:
        return profile

    return VmafPolicyProfile(
        domain=domain,
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.0.0",
        status="not_qualified",
    )


def register_qualified_domain(
    domain: str,
    policy_id: str,
    policy_version: str,
    mean_min: float,
    p5_min: float,
    worst_min: Optional[float] = None,
) -> None:
    """
    Registers an empirically qualified domain policy once it passes independent
    FAR < 2.0%, FRR < 5.0%, and untouched held-out validation.
    """
    if mean_min < 0.0 or mean_min > 100.0:
        raise ValueError(f"Invalid mean_min: {mean_min}. Must be in [0, 100].")
    if p5_min < 0.0 or p5_min > 100.0:
        raise ValueError(f"Invalid p5_min: {p5_min}. Must be in [0, 100].")
    if p5_min > mean_min:
        raise ValueError(f"p5_min ({p5_min}) cannot exceed mean_min ({mean_min}).")
    if worst_min is not None:
        if worst_min < 0.0 or worst_min > 100.0:
            raise ValueError(f"Invalid worst_min: {worst_min}. Must be in [0, 100].")
        if worst_min > p5_min:
            raise ValueError(f"worst_min ({worst_min}) cannot exceed p5_min ({p5_min}).")
        if worst_min > mean_min:
            raise ValueError(f"worst_min ({worst_min}) cannot exceed mean_min ({mean_min}).")

    OFFICIAL_DOMAIN_POLICIES[domain] = VmafPolicyProfile(
        domain=domain,
        policy_id=policy_id,
        policy_version=policy_version,
        status="validated",
        mean_min=mean_min,
        p5_min=p5_min,
        worst_min=worst_min,
    )
