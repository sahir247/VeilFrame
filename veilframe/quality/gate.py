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
from typing import List, Optional

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
        if policy.vmaf_gate_enabled:
            if policy.vmaf_mean_min < 0.0 or policy.vmaf_mean_min > 100.0:
                raise ValueError(f"Invalid vmaf_mean_min: {policy.vmaf_mean_min}. Must be in [0, 100].")
            if policy.vmaf_p5_min < 0.0 or policy.vmaf_p5_min > 100.0:
                raise ValueError(f"Invalid vmaf_p5_min: {policy.vmaf_p5_min}. Must be in [0, 100].")
            if policy.vmaf_p5_min > policy.vmaf_mean_min:
                raise ValueError(
                    f"vmaf_p5_min ({policy.vmaf_p5_min}) cannot exceed vmaf_mean_min ({policy.vmaf_mean_min})."
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
            ThreeTierQualityVerdict with overall PASS/REJECT.
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
        t2_violations: List[str] = []
        if ssim_stats.mean < policy.ssim_mean_min:
            t2_violations.append(
                f"Mean SSIM ({ssim_stats.mean:.4f}) below constraint (>= {policy.ssim_mean_min:.4f})"
            )
        if ssim_stats.p5 < policy.ssim_p5_min:
            t2_violations.append(
                f"P5 Tail SSIM ({ssim_stats.p5:.4f}) below constraint (>= {policy.ssim_p5_min:.4f})"
            )
        if ssim_stats.min_val < policy.ssim_worst_min:
            t2_violations.append(
                f"Worst-Frame SSIM ({ssim_stats.min_val:.4f}) below constraint (>= {policy.ssim_worst_min:.4f})"
            )
        if psnr_stats.mean < policy.psnr_mean_min_db:
            t2_violations.append(
                f"Mean PSNR ({psnr_stats.mean:.2f} dB) below constraint (>= {policy.psnr_mean_min_db:.1f} dB)"
            )
        if psnr_stats.min_val < policy.psnr_worst_min_db:
            t2_violations.append(
                f"Worst-Frame PSNR ({psnr_stats.min_val:.2f} dB) below constraint (>= {policy.psnr_worst_min_db:.1f} dB)"
            )

        # ── Tier 2b: VMAF Gate (calibration-gated) ───────────────────────── #
        # Activates only when vmaf_gate_enabled=True AND VMAF results exist.
        # VMAF provider absence with gate enabled is NOT a silent pass:
        #   → it appends a violation unless the caller documented the absence.
        # This preserves the adversarial property:
        #   "a missing provider cannot silently suppress a REJECT signal."
        if policy.vmaf_gate_enabled:
            if vmaf_available:
                if vmaf_stats.mean < policy.vmaf_mean_min:
                    t2_violations.append(
                        f"Mean VMAF ({vmaf_stats.mean:.2f}) below gate threshold"
                        f" (>= {policy.vmaf_mean_min:.1f}) — calibrated Tier 2b"
                    )
                if vmaf_result and vmaf_result.p5 is None:
                    t2_violations.append(
                        f"VMAF P5 percentile unavailable (full per-frame JSON evidence required for P5 >= {policy.vmaf_p5_min:.1f})"
                        f" — calibrated Tier 2b"
                    )
                elif vmaf_stats.p5 < policy.vmaf_p5_min:
                    t2_violations.append(
                        f"P5 VMAF ({vmaf_stats.p5:.2f}) below gate threshold"
                        f" (>= {policy.vmaf_p5_min:.1f}) — calibrated Tier 2b"
                    )
            else:
                # Provider unavailable with gate armed: fails Tier 2b.
                # When vmaf_gate_enabled=True, VMAF evaluation is mandatory.
                t2_violations.append(
                    "VMAF gate is enabled (calibrated Tier 2b) but libvmaf provider produced no results"
                    " — verification cannot pass without required VMAF evidence"
                )

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
