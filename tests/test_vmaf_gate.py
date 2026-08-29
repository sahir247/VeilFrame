"""
Adversarial Unit Tests — VMAF Gate (Tier 2b).

Tests QualityGate.evaluate() in isolation using synthetic QualityResult fixtures.
No FFmpeg dependency. No real video files. Pure gate logic.

Adversarial property tested for every important claim:
    "Every important claim made by the system should have at least one
     adversarial test attempting to falsify it."

Claims under test:
  1. Gate disabled by default (vmaf_gate_enabled=False) → VMAF never affects verdict.
  2. Gate enabled + VMAF below mean_min → Tier 2 REJECT.
  3. Gate enabled + VMAF below p5_min only → Tier 2 REJECT (tail matters).
  4. Gate enabled + VMAF above both thresholds → verdict unchanged (PASS if rest pass).
  5. Gate enabled + VMAF unavailable (no provider result) → violation surfaced.
  6. Gate disabled + VMAF unavailable → no violation, gate unchanged.
  7. VMAF above threshold cannot rescue a failing SSIM/PSNR verdict.
  8. Gate disabled + VMAF score of 0 (catastrophic) → still PASS at gate level
     (v1.1 backward-compat invariant: VMAF does not decide when gate is off).
  9. Multiple simultaneous violations → all appear in t2_violations list.
 10. Gate enabled + perfect VMAF + bad SSIM → still REJECT (SSIM wins, not VMAF).
"""
import unittest
from typing import List

from veilframe.quality.gate import QualityGate
from veilframe.quality.models import QualityResult
from veilframe.models.settings import VisualBudgetPolicy
from veilframe.models.video_info import (
    NativeDomainMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
)


# ── Fixture helpers ────────────────────────────────────────────────────────── #

def _passing_policy(**overrides) -> VisualBudgetPolicy:
    """A policy that passes a clean distortion-free signal."""
    kwargs = dict(
        ssim_mean_min=0.95,
        ssim_p5_min=0.90,
        ssim_worst_min=0.85,
        psnr_mean_min_db=30.0,
        psnr_worst_min_db=25.0,
        vmaf_gate_enabled=False,
        vmaf_mean_min=75.0,
        vmaf_p5_min=60.0,
    )
    kwargs.update(overrides)
    return VisualBudgetPolicy(**kwargs)


def _clean_results(include_vmaf: bool = False, vmaf_mean: float = 95.0,
                   vmaf_p5: float = 90.0) -> List[QualityResult]:
    """SSIM/PSNR results that comfortably pass all structural thresholds."""
    results = [
        QualityResult(provider_name="test-fixture", metric_name="ssim", mean=0.980, minimum=0.960, p1=0.965, p5=0.970, p95=0.990),
        QualityResult(provider_name="test-fixture", metric_name="psnr", mean=42.0,  minimum=38.0,  p1=38.5,  p5=39.0,  p95=45.0),
    ]
    if include_vmaf:
        results.append(
            QualityResult(provider_name="test-fixture", metric_name="vmaf", mean=vmaf_mean, minimum=vmaf_p5 - 5,
                          p1=vmaf_p5 - 2, p5=vmaf_p5, p95=vmaf_mean + 2)
        )
    return results


def _failing_ssim_results() -> List[QualityResult]:
    """Results with SSIM below every threshold."""
    return [
        QualityResult(provider_name="test-fixture", metric_name="ssim", mean=0.80, minimum=0.70, p1=0.72, p5=0.75, p95=0.85),
        QualityResult(provider_name="test-fixture", metric_name="psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
    ]


def _zero_policy_score() -> TransformationPolicyScore:
    """Policy score with no violations."""
    return TransformationPolicyScore(
        spatial_score_pct=0.0,
        temporal_score_pct=0.0,
        luminance_score_pct=0.0,
        chroma_score_pct=0.0,
        frequency_score_pct=0.0,
        aggregate_policy_score_pct=0.0,
        policy_ceiling_pct=5.0,
        passed=True,
        violations=[],
    )


def _zero_temporal() -> TemporalIntegrityMetrics:
    """Temporal metrics with no anomalies."""
    return TemporalIntegrityMetrics(
        missing_frames=0,
        duplicate_frames=0,
        reordered_frames=0,
        timestamp_drift_max_sec=0.0,
        violations=[],
    )


def _make_gate(policy: VisualBudgetPolicy) -> QualityGate:
    return QualityGate(policy)


def _evaluate(gate: QualityGate, results: List[QualityResult]):
    return gate.evaluate(
        results=results,
        native_metrics=NativeDomainMetrics(),
        temporal_metrics=_zero_temporal(),
        policy_score=_zero_policy_score(),
    )


# ── Test class ─────────────────────────────────────────────────────────────── #

class TestVmafGateAdversarial(unittest.TestCase):
    """
    Adversarial tests for VMAF Tier 2b gate logic.
    No FFmpeg. No real files. Pure QualityGate unit tests.
    """

    # ── Claim 1: gate disabled by default → VMAF never affects verdict ──── #

    def test_gate_disabled_vmaf_catastrophic_still_passes(self):
        """
        Claim: When vmaf_gate_enabled=False, a VMAF score of 0.0 must not
        cause a REJECT. The v1.1 invariant holds: VMAF is evidence only.

        Adversarial input: VMAF mean=0.0, p5=0.0 — the worst possible score.
        """
        policy = _passing_policy(vmaf_gate_enabled=False)
        results = _clean_results(include_vmaf=True, vmaf_mean=0.0, vmaf_p5=0.0)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertTrue(
            verdict.all_passed,
            "VMAF score of 0.0 must not affect gate verdict when vmaf_gate_enabled=False"
        )
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(verdict.tier2_violations, [],
                         "No Tier 2 violations expected when VMAF gate is disabled")

    def test_gate_disabled_no_vmaf_provider_still_passes(self):
        """
        Claim: Gate disabled + no VMAF results → no VMAF-related violation.
        """
        policy = _passing_policy(vmaf_gate_enabled=False)
        results = _clean_results(include_vmaf=False)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertTrue(verdict.all_passed)
        vmaf_viols = [v for v in verdict.tier2_violations if "vmaf" in v.lower()]
        self.assertEqual(vmaf_viols, [],
                         "No VMAF violations must appear when gate is disabled")

    # ── Claim 2: gate enabled + mean below threshold → REJECT ───────────── #

    def test_gate_enabled_mean_below_threshold_rejects(self):
        """
        Claim: VMAF mean=50.0 with vmaf_mean_min=75.0 and gate enabled → REJECT.
        Adversarial: Score is well below threshold.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        results = _clean_results(include_vmaf=True, vmaf_mean=50.0, vmaf_p5=62.0)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertFalse(verdict.all_passed,
                         "Gate must REJECT when VMAF mean is below vmaf_mean_min")
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertTrue(any("VMAF" in v and "mean" in v.lower() or "Mean VMAF" in v
                            for v in verdict.tier2_violations),
                        f"Expected Mean VMAF violation, got: {verdict.tier2_violations}")

    # ── Claim 3: gate enabled + only p5 fails → REJECT (tail matters) ───── #

    def test_gate_enabled_p5_below_threshold_rejects(self):
        """
        Claim: VMAF mean passes but P5 fails → gate must still REJECT.
        Tail sensitivity is mandatory for temporal robustness.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        # mean=80 (passes), p5=55 (fails p5 threshold of 60)
        results = _clean_results(include_vmaf=True, vmaf_mean=80.0, vmaf_p5=55.0)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertFalse(verdict.all_passed,
                         "Gate must REJECT when VMAF P5 is below vmaf_p5_min even if mean passes")
        self.assertTrue(any("P5 VMAF" in v for v in verdict.tier2_violations),
                        f"Expected P5 VMAF violation, got: {verdict.tier2_violations}")

    # ── Claim 4: gate enabled + VMAF above thresholds → no extra violation ─ #

    def test_gate_enabled_vmaf_above_both_thresholds_passes(self):
        """
        Claim: VMAF above thresholds adds no violations — verdict driven by
        SSIM/PSNR/Policy/Temporal as normal.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        results = _clean_results(include_vmaf=True, vmaf_mean=92.0, vmaf_p5=88.0)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertTrue(verdict.all_passed)
        vmaf_viols = [v for v in verdict.tier2_violations if "VMAF" in v]
        self.assertEqual(vmaf_viols, [],
                         "No VMAF violations when score is above both thresholds")

    # ── Claim 5: gate enabled + provider absent → violation surfaced ─────── #

    def test_gate_enabled_provider_absent_surfaces_violation(self):
        """
        Claim: vmaf_gate_enabled=True with no VMAF provider result must NOT
        silently pass. A violation must appear warning the operator.

        Adversarial: Gate is armed but libvmaf isn't in the FFmpeg build.
        The gate must not pretend this is safe.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        results = _clean_results(include_vmaf=False)  # No VMAF results
        verdict = _evaluate(_make_gate(policy), results)

        self.assertFalse(verdict.all_passed,
                         "Gate must not silently PASS when vmaf_gate_enabled=True "
                         "but provider returned no VMAF results")
        vmaf_viols = [v for v in verdict.tier2_violations if "vmaf" in v.lower()]
        self.assertGreater(len(vmaf_viols), 0,
                           "A violation must be surfaced when gate is armed but provider absent")

    # ── Claim 6: gate disabled + VMAF absent → silent, no violation ──────── #

    def test_gate_disabled_provider_absent_silent(self):
        """
        Claim: vmaf_gate_enabled=False + no VMAF results → completely silent.
        This is the normal v1.1 runtime behaviour.
        """
        policy = _passing_policy(vmaf_gate_enabled=False)
        results = _clean_results(include_vmaf=False)
        verdict = _evaluate(_make_gate(policy), results)

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.tier2_violations, [])

    # ── Claim 7: perfect VMAF cannot rescue a bad SSIM verdict ───────────── #

    def test_vmaf_above_threshold_cannot_rescue_failing_ssim(self):
        """
        Claim: VMAF is additive — it can only contribute violations, never
        suppress SSIM/PSNR violations. A perfect VMAF score with failing SSIM
        must still REJECT.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        # Combine: failing SSIM + perfect VMAF
        results = _failing_ssim_results()
        results.append(
            QualityResult(provider_name="test-fixture", metric_name="vmaf", mean=99.0, minimum=98.0,
                          p1=98.5, p5=99.0, p95=99.5)
        )
        verdict = _evaluate(_make_gate(policy), results)

        self.assertFalse(verdict.all_passed,
                         "A perfect VMAF score must not prevent SSIM violation from triggering REJECT")
        self.assertFalse(verdict.tier2_fidelity_passed)
        # Specifically: SSIM violations must be present
        ssim_viols = [v for v in verdict.tier2_violations if "SSIM" in v]
        self.assertGreater(len(ssim_viols), 0,
                           "SSIM violations must appear even when VMAF is perfect")

    # ── Claim 8: gate disabled + VMAF=0 → backward-compat PASS ──────────── #

    def test_v1_1_backward_compat_vmaf_zero_gate_disabled(self):
        """
        Claim: Existing v1.1 callers with vmaf_gate_enabled=False (the default)
        and a VMAF score of 0 must get the same gate verdict as if VMAF were
        never measured. This is the core non-regression guarantee.
        """
        default_policy = VisualBudgetPolicy()  # vmaf_gate_enabled=False by default
        self.assertFalse(default_policy.vmaf_gate_enabled,
                         "vmaf_gate_enabled must default to False")

        results_with_vmaf_zero = _clean_results(include_vmaf=True, vmaf_mean=0.0, vmaf_p5=0.0)
        results_without_vmaf   = _clean_results(include_vmaf=False)

        verdict_with    = _evaluate(_make_gate(default_policy), results_with_vmaf_zero)
        verdict_without = _evaluate(_make_gate(default_policy), results_without_vmaf)

        self.assertEqual(verdict_with.all_passed, verdict_without.all_passed,
                         "Gate verdict must be identical with or without VMAF when gate is disabled")
        self.assertEqual(verdict_with.overall_verdict, verdict_without.overall_verdict)

    # ── Claim 9: multiple violations → all appear in list ────────────────── #

    def test_both_vmaf_violations_appear_simultaneously(self):
        """
        Claim: When both mean and P5 fail, both violations must appear in
        tier2_violations — not just the first one encountered.
        """
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        # mean=40 (fails), p5=30 (fails)
        results = _clean_results(include_vmaf=True, vmaf_mean=40.0, vmaf_p5=30.0)
        verdict = _evaluate(_make_gate(policy), results)

        mean_viols = [v for v in verdict.tier2_violations if "Mean VMAF" in v]
        p5_viols   = [v for v in verdict.tier2_violations if "P5 VMAF" in v]

        self.assertGreater(len(mean_viols), 0, "Mean VMAF violation must appear")
        self.assertGreater(len(p5_viols),   0, "P5 VMAF violation must appear")

    # ── Claim 10: perfect VMAF + bad SSIM → SSIM wins ────────────────────── #

    def test_ssim_violation_takes_precedence_over_vmaf_pass(self):
        """
        Claim: VMAF contributing PASS cannot mask SSIM failing — the violations
        accumulate independently and the gate rejects if any tier 2 violation exists.
        """
        policy = _passing_policy(
            vmaf_gate_enabled=True,
            vmaf_mean_min=75.0,
            vmaf_p5_min=60.0,
            ssim_mean_min=0.95,
        )
        # SSIM below threshold, VMAF perfect
        results = [
            QualityResult(provider_name="test-fixture", metric_name="ssim", mean=0.88, minimum=0.80,
                          p1=0.82, p5=0.84, p95=0.90),
            QualityResult(provider_name="test-fixture", metric_name="psnr", mean=40.0, minimum=36.0,
                          p1=36.5, p5=37.0, p95=43.0),
            QualityResult(provider_name="test-fixture", metric_name="vmaf", mean=98.0, minimum=96.0,
                          p1=96.5, p5=97.0, p95=99.0),
        ]
        verdict = _evaluate(_make_gate(policy), results)

        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)

        ssim_viols = [v for v in verdict.tier2_violations if "SSIM" in v]
        vmaf_viols = [v for v in verdict.tier2_violations if "VMAF" in v]

        self.assertGreater(len(ssim_viols), 0, "SSIM violation must be present")
        self.assertEqual(len(vmaf_viols), 0,
                         "No VMAF violations when VMAF score exceeds thresholds")

    # ── Gate field defaults ───────────────────────────────────────────────── #

    def test_policy_vmaf_fields_have_correct_defaults(self):
        """Claim: VisualBudgetPolicy defaults must keep VMAF gate disabled."""
        p = VisualBudgetPolicy()
        self.assertFalse(p.vmaf_gate_enabled)
        self.assertIsInstance(p.vmaf_mean_min, float)
        self.assertIsInstance(p.vmaf_p5_min, float)
        # Thresholds must be plausible (not negative, not > 100)
        self.assertGreater(p.vmaf_mean_min, 0.0)
        self.assertLessEqual(p.vmaf_mean_min, 100.0)
        self.assertGreater(p.vmaf_p5_min, 0.0)
        self.assertLessEqual(p.vmaf_p5_min, 100.0)


class TestVmafGateBoundaryConditions(unittest.TestCase):
    """Boundary tests — exact threshold edge cases."""

    def test_vmaf_mean_exactly_at_threshold_passes(self):
        """Mean VMAF exactly equal to vmaf_mean_min must pass (>= semantics)."""
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        results = _clean_results(include_vmaf=True, vmaf_mean=75.0, vmaf_p5=62.0)
        verdict = _evaluate(_make_gate(policy), results)
        mean_viols = [v for v in verdict.tier2_violations if "Mean VMAF" in v]
        self.assertEqual(mean_viols, [],
                         "Mean VMAF exactly at threshold must not generate a violation (>= semantics)")

    def test_vmaf_p5_exactly_at_threshold_passes(self):
        """P5 VMAF exactly equal to vmaf_p5_min must pass."""
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        results = _clean_results(include_vmaf=True, vmaf_mean=80.0, vmaf_p5=60.0)
        verdict = _evaluate(_make_gate(policy), results)
        p5_viols = [v for v in verdict.tier2_violations if "P5 VMAF" in v]
        self.assertEqual(p5_viols, [], "P5 VMAF exactly at threshold must not violate (>= semantics)")

    def test_vmaf_mean_one_epsilon_below_threshold_rejects(self):
        """Mean VMAF one float-epsilon below threshold must REJECT."""
        import sys
        policy = _passing_policy(vmaf_gate_enabled=True, vmaf_mean_min=75.0, vmaf_p5_min=60.0)
        # Use a clearly sub-threshold value, not float-epsilon (VMAF scores aren't that precise)
        results = _clean_results(include_vmaf=True, vmaf_mean=74.99, vmaf_p5=62.0)
        verdict = _evaluate(_make_gate(policy), results)
        mean_viols = [v for v in verdict.tier2_violations if "Mean VMAF" in v]
        self.assertGreater(len(mean_viols), 0,
                           "Mean VMAF 74.99 with threshold 75.0 must generate a violation")


if __name__ == "__main__":
    unittest.main()
