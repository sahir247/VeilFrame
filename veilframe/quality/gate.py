"""
QualityGate — VeilFrame's verdict engine.

Consumes generic QualityResult objects from any number of QualityProvider instances.
The gate does not make verdict decisions based on provider identity (operates purely on metric values).

Gate predicate:

    Tier 1 — Transformation Policy Score
    Tier 2 — Rendered Visual Fidelity: SSIM (mean/P5/worst) + PSNR (mean/worst)
    Tier 3 — Temporal Integrity

    overall_pass = tier1 AND tier2 AND tier3

Architectural invariant: Providers measure. VeilFrame decides.
"""
from typing import List

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
    Evaluates quality results from providers and produces a ThreeTierQualityVerdict.

    The gate is the exclusive owner of verdict logic. It must never be
    bypassed, and providers must never be given access to policy thresholds.
    """

    def __init__(self, policy: VisualBudgetPolicy):
        self._policy = policy
        self._validate_policy(policy)

    def _validate_policy(self, policy: VisualBudgetPolicy) -> None:
        """Validates that policy constraints and thresholds are well-formed."""
        if policy.ssim_mean_min < 0.0 or policy.ssim_mean_min > 1.0:
            raise ValueError(f"Invalid ssim_mean_min: {policy.ssim_mean_min}. Must be in [0, 1].")
        if policy.psnr_mean_min_db < 0.0:
            raise ValueError(f"Invalid psnr_mean_min_db: {policy.psnr_mean_min_db}. Must be >= 0.")

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
            ThreeTierQualityVerdict with overall PASS/REJECT verdict.
        """
        policy = self._policy

        # Extract metrics from provider results
        ssim_stats = self._extract_stats(results, "ssim")
        psnr_stats = self._extract_stats(results, "psnr")

        all_violations: List[str] = []

        # ── Tier 1: Transformation Policy Score ──────────────────────────── #
        t1_violations = list(policy_score.violations)
        t1_passed = len(t1_violations) == 0
        all_violations.extend(t1_violations)

        # ── Tier 2: Rendered Visual Fidelity (SSIM + PSNR) ───────────────── #
        # SSIM and PSNR remain the authoritative quality safety gate.
        t2_violations: List[str] = []
        if not self._has_metric(results, "ssim"):
            t2_violations.append("Missing required quality measurement: SSIM")
        else:
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

        if not self._has_metric(results, "psnr"):
            t2_violations.append("Missing required quality measurement: PSNR")
        else:
            if psnr_stats.mean < policy.psnr_mean_min_db:
                t2_violations.append(
                    f"Mean PSNR ({psnr_stats.mean:.2f} dB) below constraint (>= {policy.psnr_mean_min_db:.1f} dB)"
                )
            if psnr_stats.min_val is not None and psnr_stats.min_val < policy.psnr_worst_min_db:
                t2_violations.append(
                    f"Worst-Frame PSNR ({psnr_stats.min_val:.2f} dB) below constraint (>= {policy.psnr_worst_min_db:.1f} dB)"
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
