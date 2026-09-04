"""
QualityGate — VeilFrame's verdict engine.

Consumes generic QualityResult objects from any number of QualityProvider instances.
The gate does not make verdict decisions based on provider identity (operates purely on metric values).

Gate predicate (v1.1 → v1.2 promotion path):

    Tier 1  — Transformation Policy Score
    Tier 2a — Rendered Fidelity: SSIM mean/P5/worst + PSNR mean/worst
    Tier 2b — VMAF Gate (DISABLED by default; activates when
               policy.vmaf_gate_enabled=True AND VMAF results are present).
               Do NOT set vmaf_gate_enabled=True until threshold is
               corpus-justified via vmaf_calibration.py + vmaf_corpus_runner.py.
    Tier 3  — Temporal Integrity

    overall_pass = tier1 AND tier2a AND tier2b AND tier3

When vmaf_gate_enabled=False (default):
    - VMAF results still appear in the manifest as evidence.
    - The gate verdict is unchanged (identical to v1.1 behaviour).
    - Zero gate-semantic regression.

When vmaf_gate_enabled=True (after calibration):
    - A VMAF result absence is treated as a gate failure (missing required evidence).
    - A VMAF result presence is evaluated against vmaf_mean_min / vmaf_p5_min.

Architectural invariant: Providers measure. VeilFrame decides.
"""
from typing import Any, Dict, List, Optional

from .models import QualityResult
from ..models.video_info import (
    NativeDomainMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
    ThreeTierQualityVerdict,
    QualityMetricStats,
)
from ..models.settings import VisualBudgetPolicy


class QualityGate:
    """
    Evaluates quality results from multiple providers and produces a ThreeTierQualityVerdict.

    The gate is the exclusive owner of verdict logic. It must never be
    bypassed, and providers must never be given access to policy thresholds.
    """

    def __init__(self, policy: VisualBudgetPolicy):
        self._policy = policy
        self._validate_policy(policy)

    def _validate_policy(self, policy: VisualBudgetPolicy) -> None:
        """Validates that policy constraints and thresholds are well-formed."""
        if policy.vmaf_gate_enabled or getattr(policy, "vmaf_gate_mode", "audit") in ("validated_global", "validated_model"):
            if policy.vmaf_mean_min < 0.0 or policy.vmaf_mean_min > 100.0:
                raise ValueError(f"Invalid vmaf_mean_min: {policy.vmaf_mean_min}. Must be in [0, 100].")
            if policy.vmaf_p5_min < 0.0 or policy.vmaf_p5_min > 100.0:
                raise ValueError(f"Invalid vmaf_p5_min: {policy.vmaf_p5_min}. Must be in [0, 100].")
            if policy.vmaf_p5_min > policy.vmaf_mean_min:
                raise ValueError(
                    f"vmaf_p5_min ({policy.vmaf_p5_min}) cannot exceed vmaf_mean_min ({policy.vmaf_mean_min})."
                )
            if getattr(policy, "vmaf_worst_min", None) is not None:
                if policy.vmaf_worst_min < 0.0 or policy.vmaf_worst_min > 100.0:
                    raise ValueError(f"Invalid vmaf_worst_min: {policy.vmaf_worst_min}. Must be in [0, 100].")
                if policy.vmaf_worst_min > policy.vmaf_p5_min:
                    raise ValueError(
                        f"vmaf_worst_min ({policy.vmaf_worst_min}) cannot exceed vmaf_p5_min ({policy.vmaf_p5_min})."
                    )
                if policy.vmaf_worst_min > policy.vmaf_mean_min:
                    raise ValueError(
                        f"vmaf_worst_min ({policy.vmaf_worst_min}) cannot exceed vmaf_mean_min ({policy.vmaf_mean_min})."
                    )

    def evaluate(
        self,
        results: List[QualityResult],
        native_metrics: NativeDomainMetrics,
        temporal_metrics: TemporalIntegrityMetrics,
        policy_score: TransformationPolicyScore,
    ) -> ThreeTierQualityVerdict:
        """
        Evaluates all three validation tiers and returns a verdict.

        Args:
            results:          QualityResult list from all active providers.
            native_metrics:   Native-domain stream geometry audit.
            temporal_metrics: Pre-resampling temporal integrity audit.
            policy_score:     Application-defined 5% policy budget evaluation.

        Returns:
            ThreeTierQualityVerdict with overall PASS/REJECT and VMAF provenance.
        """
        policy = self._policy

        # Extract metrics from provider results
        ssim_stats = self._extract_stats(results, "ssim")
        psnr_stats = self._extract_stats(results, "psnr")
        vmaf_stats = self._extract_stats(results, "vmaf")
        vmaf_available = self._has_metric(results, "vmaf")
        vmaf_result = next((r for r in results if r.metric_name == "vmaf"), None)

        all_violations: List[str] = []

        # ── Tier 1: Transformation Policy Score ──────────────────────────── #
        t1_violations = list(policy_score.violations)
        t1_passed = len(t1_violations) == 0
        all_violations.extend(t1_violations)

        # ── Tier 2a: Rendered Visual Fidelity (SSIM + PSNR) ──────────────── #
        # SSIM and PSNR remain the authoritative quality safety gate.
        t2_violations: List[str] = []
        if ssim_stats.mean < policy.ssim_mean_min:
            t2_violations.append(
                f"Mean SSIM ({ssim_stats.mean:.4f}) below constraint (>= {policy.ssim_mean_min:.4f})"
            )
        if ssim_stats.p5 is not None and ssim_stats.p5 < policy.ssim_p5_min:
            t2_violations.append(
                f"P5 Tail SSIM ({ssim_stats.p5:.4f}) below constraint (>= {policy.ssim_p5_min:.4f})"
            )
        if ssim_stats.min_val is not None and ssim_stats.min_val < policy.ssim_worst_min:
            t2_violations.append(
                f"Worst-Frame SSIM ({ssim_stats.min_val:.4f}) below constraint (>= {policy.ssim_worst_min:.4f})"
            )
        if psnr_stats.mean < policy.psnr_mean_min_db:
            t2_violations.append(
                f"Mean PSNR ({psnr_stats.mean:.2f} dB) below constraint (>= {policy.psnr_mean_min_db:.1f} dB)"
            )
        if psnr_stats.min_val is not None and psnr_stats.min_val < policy.psnr_worst_min_db:
            t2_violations.append(
                f"Worst-Frame PSNR ({psnr_stats.min_val:.2f} dB) below constraint (>= {policy.psnr_worst_min_db:.1f} dB)"
            )

        # ── Tier 2b: VMAF Policy Evaluation ─────────────────────────────── #
        # Technical classification from native stream geometry:
        w = h = 0
        if native_metrics:
            for res_str in (native_metrics.resolution_trans, native_metrics.resolution_ref):
                if res_str and "x" in res_str:
                    parts = res_str.split("x")
                    try:
                        w, h = int(parts[0]), int(parts[1])
                        break
                    except Exception:
                        pass
        fps = 30.0
        if native_metrics:
            fps = native_metrics.fps_trans or native_metrics.fps_ref or 30.0

        from .vmaf_policy import classify_technical_domain, resolve_vmaf_policy
        tech_domain = classify_technical_domain(w, h, fps, is_hdr=False)
        gate_mode = getattr(policy, "vmaf_gate_mode", "validated_global" if policy.vmaf_gate_enabled else "audit")
        vmaf_profile = resolve_vmaf_policy(tech_domain, gate_mode=gate_mode)

        vmaf_verdict_data: Optional[Dict[str, Any]] = None
        policy_provenance_data: Optional[Dict[str, Any]] = {
            "study_id": vmaf_profile.calibration_study_id,
            "dataset_version": vmaf_profile.dataset_version,
            "policy_version": vmaf_profile.policy_version,
            "analysis_version": vmaf_profile.analysis_version,
        }

        if gate_mode == "disabled":
            vmaf_verdict_data = {
                "status": "disabled",
                "gate_mode": "disabled",
                "domain": tech_domain,
                "policy_id": vmaf_profile.policy_id,
                "decision": "DISABLED",
            }
        elif gate_mode == "audit":
            # Production v1 baseline: Measurement only. NEVER rejects production output.
            if vmaf_available and vmaf_result:
                is_p5_missing = (vmaf_result.p5 is None)
                decision = "AUDIT_INCOMPLETE" if is_p5_missing else "AUDIT"
                note = "VMAF P5 percentile missing; audit incomplete" if is_p5_missing else "Audit measurement recorded"
                vmaf_verdict_data = {
                    "status": "audit_only",
                    "gate_mode": "audit",
                    "domain": tech_domain,
                    "model_id": vmaf_result.model_name or "unknown",
                    "model_sha256": vmaf_result.model_sha256,
                    "evidence_sha256": vmaf_result.evidence_sha256,
                    "mean": vmaf_stats.mean,
                    "p5": vmaf_stats.p5,
                    "worst": vmaf_stats.min_val,
                    "policy_id": vmaf_profile.policy_id,
                    "mean_min": None,
                    "p5_min": None,
                    "decision": decision,
                    "note": note,
                }
            else:
                vmaf_verdict_data = {
                    "status": "unavailable",
                    "gate_mode": "audit",
                    "domain": tech_domain,
                    "policy_id": vmaf_profile.policy_id,
                    "decision": "AUDIT_INCOMPLETE",
                    "note": "VMAF provider produced no results; audit incomplete",
                }
        elif gate_mode == "validated_model":
            # Domain-specific model gating:
            if vmaf_profile.status == "validated":
                # Validated domain: enforces empirical thresholds
                if not vmaf_available:
                    t2_violations.append(
                        f"VMAF gate armed for validated domain '{tech_domain}' but libvmaf provider produced no results"
                        " — verification cannot pass without required VMAF evidence"
                    )
                    vmaf_decision = "REJECT"
                else:
                    vmaf_v = []
                    if vmaf_profile.mean_min is not None and vmaf_stats.mean < vmaf_profile.mean_min:
                        vmaf_v.append(
                            f"Mean VMAF ({vmaf_stats.mean:.2f}) below domain threshold (>= {vmaf_profile.mean_min:.1f})"
                        )
                    if vmaf_profile.p5_min is not None:
                        if vmaf_result and vmaf_result.p5 is None:
                            vmaf_v.append(
                                f"VMAF P5 percentile unavailable (full per-frame JSON evidence required for P5 >= {vmaf_profile.p5_min:.1f})"
                            )
                        elif vmaf_stats.p5 is not None and vmaf_stats.p5 < vmaf_profile.p5_min:
                            vmaf_v.append(
                                f"P5 VMAF ({vmaf_stats.p5:.2f}) below domain threshold (>= {vmaf_profile.p5_min:.1f})"
                            )
                    if vmaf_profile.worst_min is not None:
                        if vmaf_result and vmaf_result.minimum is None:
                            vmaf_v.append(
                                f"VMAF worst-frame score unavailable (per-frame evidence required for min >= {vmaf_profile.worst_min:.1f})"
                            )
                        elif vmaf_stats.min_val is not None and vmaf_stats.min_val < vmaf_profile.worst_min:
                            vmaf_v.append(
                                f"Worst-Frame VMAF ({vmaf_stats.min_val:.2f}) below domain threshold (>= {vmaf_profile.worst_min:.1f})"
                            )
                    t2_violations.extend(vmaf_v)
                    vmaf_decision = "PASS" if len(vmaf_v) == 0 else "REJECT"

                vmaf_verdict_data = {
                    "status": "validated",
                    "gate_mode": "validated_model",
                    "domain": tech_domain,
                    "model_id": vmaf_result.model_name if vmaf_result else "unknown",
                    "model_sha256": vmaf_result.model_sha256 if vmaf_result else None,
                    "evidence_sha256": vmaf_result.evidence_sha256 if vmaf_result else None,
                    "mean": vmaf_stats.mean if vmaf_available else None,
                    "p5": vmaf_stats.p5 if vmaf_available else None,
                    "worst": vmaf_stats.min_val if vmaf_available else None,
                    "policy_id": vmaf_profile.policy_id,
                    "mean_min": vmaf_profile.mean_min,
                    "p5_min": vmaf_profile.p5_min,
                    "decision": vmaf_decision,
                }
            else:
                # Unqualified domain: falls back to audit mode with SSIM/PSNR authority
                vmaf_decision = "AUDIT_FALLBACK"
                vmaf_verdict_data = {
                    "status": vmaf_profile.status,
                    "gate_mode": "validated_model",
                    "domain": tech_domain,
                    "model_id": vmaf_result.model_name if vmaf_result else "unknown",
                    "model_sha256": vmaf_result.model_sha256 if vmaf_result else None,
                    "evidence_sha256": vmaf_result.evidence_sha256 if vmaf_result else None,
                    "mean": vmaf_stats.mean if vmaf_available else None,
                    "p5": vmaf_stats.p5 if vmaf_available else None,
                    "worst": vmaf_stats.min_val if vmaf_available else None,
                    "policy_id": vmaf_profile.policy_id,
                    "mean_min": None,
                    "p5_min": None,
                    "decision": vmaf_decision,
                    "note": f"Domain '{tech_domain}' is not qualified; falling back to audit mode with SSIM/PSNR authority",
                }
        elif gate_mode == "validated_global":
            # Global scalar gate (preserves backward-compat for legacy vmaf_gate_enabled=True)
            if not vmaf_available:
                t2_violations.append(
                    "VMAF gate is enabled (calibrated Tier 2b) but libvmaf provider produced no results"
                    " — verification cannot pass without required VMAF evidence"
                )
                vmaf_decision = "REJECT"
            else:
                vmaf_v = []
                if vmaf_stats.mean < policy.vmaf_mean_min:
                    vmaf_v.append(
                        f"Mean VMAF ({vmaf_stats.mean:.2f}) below gate threshold"
                        f" (>= {policy.vmaf_mean_min:.1f}) — calibrated Tier 2b"
                    )
                if vmaf_result and vmaf_result.p5 is None:
                    vmaf_v.append(
                        f"VMAF P5 percentile unavailable (full per-frame JSON evidence required for P5 >= {policy.vmaf_p5_min:.1f})"
                        f" — calibrated Tier 2b"
                    )
                elif vmaf_stats.p5 is not None and vmaf_stats.p5 < policy.vmaf_p5_min:
                    vmaf_v.append(
                        f"P5 VMAF ({vmaf_stats.p5:.2f}) below gate threshold"
                        f" (>= {policy.vmaf_p5_min:.1f}) — calibrated Tier 2b"
                    )
                if getattr(policy, "vmaf_worst_min", None) is not None:
                    if vmaf_result and vmaf_result.minimum is None:
                        vmaf_v.append(
                            f"VMAF worst-frame score unavailable (per-frame evidence required for min >= {policy.vmaf_worst_min:.1f})"
                            f" — calibrated Tier 2b"
                        )
                    elif vmaf_stats.min_val is not None and vmaf_stats.min_val < policy.vmaf_worst_min:
                        vmaf_v.append(
                            f"Worst-Frame VMAF ({vmaf_stats.min_val:.2f}) below gate threshold"
                            f" (>= {policy.vmaf_worst_min:.1f}) — calibrated Tier 2b"
                        )
                t2_violations.extend(vmaf_v)
                vmaf_decision = "PASS" if len(vmaf_v) == 0 else "REJECT"

            vmaf_verdict_data = {
                "status": "validated_global",
                "gate_mode": "validated_global",
                "domain": tech_domain,
                "model_id": vmaf_result.model_name if vmaf_result else "unknown",
                "model_sha256": vmaf_result.model_sha256 if vmaf_result else None,
                "evidence_sha256": vmaf_result.evidence_sha256 if vmaf_result else None,
                "mean": vmaf_stats.mean if vmaf_available else None,
                "p5": vmaf_stats.p5 if vmaf_available else None,
                "worst": vmaf_stats.min_val if vmaf_available else None,
                "policy_id": "vmaf-global-unqualified",
                "mean_min": policy.vmaf_mean_min,
                "p5_min": policy.vmaf_p5_min,
                "decision": vmaf_decision,
            }

        t2_passed = len(t2_violations) == 0
        all_violations.extend(t2_violations)

        # ── Tier 3: Temporal Integrity ────────────────────────────────────── #
        t3_violations = list(temporal_metrics.violations)
        t3_passed = len(t3_violations) == 0
        all_violations.extend(t3_violations)

        all_passed = t1_passed and t2_passed and t3_passed
        overall_verdict = "PASS" if all_passed else "REJECT"

        return ThreeTierQualityVerdict(
            tier1_policy_passed=t1_passed,
            tier1_violations=t1_violations,
            tier2_fidelity_passed=t2_passed,
            tier2_violations=t2_violations,
            tier3_temporal_passed=t3_passed,
            tier3_violations=t3_violations,
            overall_verdict=overall_verdict,
            all_passed=all_passed,
            vmaf_verdict=vmaf_verdict_data,
            policy_provenance=policy_provenance_data,
        )

    def _extract_stats(
        self,
        results: List[QualityResult],
        metric_name: str,
    ) -> QualityMetricStats:
        """Finds the first QualityResult matching metric_name and converts to QualityMetricStats."""
        for r in results:
            if r.metric_name == metric_name:
                return QualityMetricStats(
                    mean=r.mean,
                    min_val=r.minimum,
                    p1=r.p1,
                    p5=r.p5,
                    p95=r.p95,
                )
        return QualityMetricStats()

    def _has_metric(self, results: List[QualityResult], metric_name: str) -> bool:
        """Returns True if at least one QualityResult with the given metric_name is present."""
        return any(r.metric_name == metric_name for r in results)
