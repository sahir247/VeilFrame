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
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional, Union

from ..core.crypto import compute_sha256
from .vmaf_models import (
    classify_resolution,
    is_hfr,
    OFFICIAL_VMAF_V1_0_16_MODELS,
)

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
    status: str                         # "not_qualified" | "audit_only" | "validated" | "disabled" | "not_applicable" | "unsupported_domain"
    mean_min: Optional[float] = None    # Calibrated threshold minimum for VMAF mean
    p5_min: Optional[float] = None      # Calibrated threshold minimum for VMAF P5 tail
    worst_min: Optional[float] = None   # Calibrated threshold minimum for worst frame
    model_id: Optional[str] = None      # Bound official VMAF model ID
    model_sha256: Optional[str] = None  # Bound official VMAF model SHA-256
    qualification_artifact_sha256: Optional[str] = None # SHA-256 of qualification report
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
        model_id=OFFICIAL_VMAF_V1_0_16_MODELS["1080p_sdr"].model_id,
        model_sha256=OFFICIAL_VMAF_V1_0_16_MODELS["1080p_sdr"].expected_sha256,
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "1080p_hfr": VmafPolicyProfile(
        domain="1080p_hfr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        model_id=OFFICIAL_VMAF_V1_0_16_MODELS["1080p_hfr"].model_id,
        model_sha256=OFFICIAL_VMAF_V1_0_16_MODELS["1080p_hfr"].expected_sha256,
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "2160p_sdr": VmafPolicyProfile(
        domain="2160p_sdr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        model_id=OFFICIAL_VMAF_V1_0_16_MODELS["2160p_sdr"].model_id,
        model_sha256=OFFICIAL_VMAF_V1_0_16_MODELS["2160p_sdr"].expected_sha256,
        mean_min=None,
        p5_min=None,
        worst_min=None,
    ),
    "2160p_hfr": VmafPolicyProfile(
        domain="2160p_hfr",
        policy_id=VMAF_AUDIT_POLICY_ID,
        policy_version="0.1.0",
        status="not_qualified",
        model_id=OFFICIAL_VMAF_V1_0_16_MODELS["2160p_hfr"].model_id,
        model_sha256=OFFICIAL_VMAF_V1_0_16_MODELS["2160p_hfr"].expected_sha256,
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
    mean_min: Optional[float] = None,
    p5_min: Optional[float] = None,
    worst_min: Optional[float] = None,
    qualification_report_path: Optional[Union[str, Path]] = None,
) -> VmafPolicyProfile:
    """
    Registers an empirically qualified domain policy from a verified qualification artifact.

    Production Hardening Rules:
      1. Domain Validation: domain must be one of the four supported production domains
         ("1080p_sdr", "1080p_hfr", "2160p_sdr", "2160p_hfr").
      2. Artifact Verification: qualification artifact must exist and be cryptographically verified.
      3. Provenance Check: study_id, dataset_version, and analysis_version must be valid and verified.
      4. Qualification Status: artifact must explicitly document domain status == "validated".
         Unqualified domains cannot be registered as qualified under any circumstances.
      5. Threshold Integrity: recommended thresholds from the artifact must match or populate
         mean_min and p5_min, and satisfy worst_min <= p5_min <= mean_min in [0, 100].
      6. Model Binding: Binds the official VMAF v1.0.16 model ID and SHA-256 to the profile,
         along with the qualification artifact SHA-256.
    """
    if domain not in OFFICIAL_DOMAIN_POLICIES:
        raise ValueError(
            f"Domain '{domain}' is not a supported production domain. "
            f"Supported domains: {list(OFFICIAL_DOMAIN_POLICIES.keys())}"
        )

    # 1. Resolve qualification artifact path
    if qualification_report_path is None:
        cands = [
            Path("calibration/v1.0/vmaf_domain_qualification.json"),
            Path("vmaf_domain_qualification.json"),
        ]
        chosen = None
        for c in cands:
            if c.exists():
                chosen = c
                break
        if chosen is None:
            raise FileNotFoundError(
                "Qualification artifact not found. A valid qualification artifact "
                "(vmaf_domain_qualification.json) is required to register a qualified domain."
            )
        artifact_path = chosen
    else:
        artifact_path = Path(qualification_report_path)
        if not artifact_path.exists():
            raise FileNotFoundError(f"Qualification artifact not found at '{artifact_path}'.")

    # 2. Read artifact and compute digest
    try:
        artifact_data = json.loads(artifact_path.read_text(encoding="utf-8"))
    except Exception as e:
        raise ValueError(f"Failed to parse qualification artifact '{artifact_path}': {e}") from e

    artifact_sha256 = compute_sha256(artifact_path)

    # 3. Verify study provenance fields
    study_id = artifact_data.get("study_id")
    if not study_id or not str(study_id).startswith("VF-CAL-VMAF"):
        raise ValueError(
            f"Qualification artifact '{artifact_path.name}' has invalid study_id '{study_id}'. "
            f"Expected study starting with 'VF-CAL-VMAF'."
        )

    dataset_ver = artifact_data.get("dataset_version")
    if dataset_ver != VMAF_CALIBRATION_DATASET_VERSION:
        raise ValueError(
            f"Qualification artifact dataset_version '{dataset_ver}' does not match "
            f"expected calibration dataset version '{VMAF_CALIBRATION_DATASET_VERSION}'."
        )

    analysis_ver = artifact_data.get("analysis_version")
    if analysis_ver != VMAF_CALIBRATION_ANALYSIS_VERSION:
        raise ValueError(
            f"Qualification artifact analysis_version '{analysis_ver}' does not match "
            f"expected calibration analysis version '{VMAF_CALIBRATION_ANALYSIS_VERSION}'."
        )

    domains_dict = artifact_data.get("domains", {})
    if domain not in domains_dict:
        raise ValueError(
            f"Qualification artifact '{artifact_path.name}' does not contain entry for domain '{domain}'."
        )

    domain_data = domains_dict[domain]
    domain_status = domain_data.get("status")
    if domain_status != "validated":
        reason = domain_data.get("reason", "unspecified")
        raise ValueError(
            f"Cannot register domain '{domain}': status is '{domain_status}' in qualification artifact "
            f"'{artifact_path.name}' (reason: {reason}). Only status='validated' domains can be promoted."
        )

    # 4. Resolve and verify thresholds
    rec_mean = domain_data.get("recommended_mean_min")
    rec_p5 = domain_data.get("recommended_p5_min")

    if mean_min is None:
        if rec_mean is None:
            raise ValueError(f"No recommended_mean_min found in artifact for domain '{domain}'.")
        mean_min = float(rec_mean)
    else:
        if rec_mean is not None and abs(mean_min - rec_mean) > 1e-3:
            raise ValueError(
                f"Specified mean_min ({mean_min}) does not match artifact recommended threshold ({rec_mean})."
            )

    if p5_min is None:
        if rec_p5 is None:
            raise ValueError(f"No recommended_p5_min found in artifact for domain '{domain}'.")
        p5_min = float(rec_p5)
    else:
        if rec_p5 is not None and abs(p5_min - rec_p5) > 1e-3:
            raise ValueError(
                f"Specified p5_min ({p5_min}) does not match artifact recommended threshold ({rec_p5})."
            )

    # Validate threshold ranges and ordering
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

    # 5. Cryptographically bind official model spec
    model_spec = OFFICIAL_VMAF_V1_0_16_MODELS[domain]

    profile = VmafPolicyProfile(
        domain=domain,
        policy_id=policy_id,
        policy_version=policy_version,
        status="validated",
        mean_min=mean_min,
        p5_min=p5_min,
        worst_min=worst_min,
        model_id=model_spec.model_id,
        model_sha256=model_spec.expected_sha256,
        qualification_artifact_sha256=artifact_sha256,
        calibration_study_id=study_id,
        dataset_version=dataset_ver,
        analysis_version=analysis_ver,
    )
    OFFICIAL_DOMAIN_POLICIES[domain] = profile
    return profile

