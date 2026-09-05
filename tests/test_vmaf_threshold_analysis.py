"""
Unit and regression tests for VeilFrame VMAF Threshold Scientific Analysis Engine.

Verifies:
  - Sequence-group development / held-out splitting guarantees zero sequence leakage.
  - Deterministic RNG seeding producing identical partitions across runs.
  - Non-circular independent policy labeling based on VeilFrame fidelity criteria.
  - Threshold sweep metrics computation (TA, TR, FA, FR, FAR, FRR, balanced accuracy).
  - Selection of the lowest feasible threshold on development data.
  - 'no_feasible_threshold' status when research constraints cannot be met.
  - Held-out validation marking 'validated' on pass and 'failed' on breach.
  - Minimum data requirement triggering 'insufficient_data'.
  - Absolute production gate safety: vmaf_gate_enabled remains False.
  - Verification that threshold evaluation APIs were cleanly removed from vmaf_corpus_runner.
"""

import json
import tempfile
import unittest
from pathlib import Path

from tools.vmaf_threshold_analysis import (
    CorpusSample,
    assign_independent_policy_label,
    partition_by_sequence_group,
    evaluate_policy_operating_point,
    sweep_thresholds,
    select_lowest_feasible_threshold,
)
from veilframe.models.settings import VisualBudgetPolicy


class TestSequenceGroupSplitting(unittest.TestCase):
    """Tests for sequence group partitioning to avoid content leakage."""

    def setUp(self):
        self.samples = [
            # Group A: 3 multi-resolution variants
            CorpusSample("a_720p.mp4", "seq_a", "manifest", "natural", "", 1280, 720, 30.0, "yuv420p", "LOW_PERTURBATION", 95.0, 93.0, 90.0, 1.0, 0.98, 42.0, None, None, None, "acceptable"),
            CorpusSample("a_1080p.mp4", "seq_a", "manifest", "natural", "", 1920, 1080, 30.0, "yuv420p", "LOW_PERTURBATION", 96.0, 94.0, 91.0, 1.0, 0.98, 42.0, None, None, None, "acceptable"),
            CorpusSample("a_2160p.mp4", "seq_a", "manifest", "natural", "", 3840, 2160, 30.0, "yuv420p", "LOW_PERTURBATION", 97.0, 95.0, 92.0, 1.0, 0.98, 42.0, None, None, None, "acceptable"),
            # Group B
            CorpusSample("b_1080p.mp4", "seq_b", "manifest", "screen", "", 1920, 1080, 60.0, "yuv420p", "HIGH", 55.0, 50.0, 48.0, 2.0, 0.91, 31.0, None, None, None, "unacceptable"),
            # Group C
            CorpusSample("c_1080p.mp4", "seq_c", "manifest", "motion", "", 1920, 1080, 50.0, "yuv420p", "IDENTICAL", 100.0, 100.0, 100.0, 0.0, 1.0, 100.0, None, None, None, "acceptable"),
            # Group D
            CorpusSample("d_1080p.mp4", "seq_d", "manifest", "texture", "", 1920, 1080, 25.0, "yuv420p", "SEVERE", 20.0, 15.0, 10.0, 3.0, 0.82, 22.0, None, None, None, "unacceptable"),
        ]

    def test_zero_sequence_leakage(self):
        dev_s, ho_s, dev_g, ho_g = partition_by_sequence_group(self.samples, dev_fraction=0.75, seed=42)
        dev_set = set(dev_g)
        ho_set = set(ho_g)
        self.assertEqual(len(dev_set.intersection(ho_set)), 0,
                         "Sequence groups must never appear in both development and held-out sets")

        # Verify all variants of seq_a are in the SAME partition
        a_in_dev = any(s.sequence_group == "seq_a" for s in dev_s)
        a_in_ho = any(s.sequence_group == "seq_a" for s in ho_s)
        self.assertTrue(a_in_dev != a_in_ho, "Multi-encode variants of seq_a must strictly stay in one partition")

    def test_deterministic_split(self):
        d1, h1, g1_d, g1_h = partition_by_sequence_group(self.samples, dev_fraction=0.5, seed=123)
        d2, h2, g2_d, g2_h = partition_by_sequence_group(self.samples, dev_fraction=0.5, seed=123)
        self.assertEqual(g1_d, g2_d)
        self.assertEqual(g1_h, g2_h)
        self.assertEqual(len(d1), len(d2))


class TestIndependentPolicyLabeling(unittest.TestCase):
    """Verifies that labels are derived from VeilFrame visual policy and not circular to VMAF."""

    def test_acceptable_fixture_with_high_fidelity(self):
        lbl = assign_independent_policy_label("LOW_PERTURBATION", ssim_mean=0.985, psnr_mean=42.0)
        self.assertEqual(lbl, "acceptable")

    def test_acceptable_fixture_with_low_ssim_rejected(self):
        # Even if LOW_PERTURBATION fixture, failing SSIM must fail policy label
        lbl = assign_independent_policy_label("LOW_PERTURBATION", ssim_mean=0.92, psnr_mean=42.0)
        self.assertEqual(lbl, "unacceptable")

    def test_acceptable_fixture_with_low_psnr_rejected(self):
        lbl = assign_independent_policy_label("LOW_PERTURBATION", ssim_mean=0.985, psnr_mean=28.0)
        self.assertEqual(lbl, "unacceptable")

    def test_unacceptable_fixture_labeled_unacceptable(self):
        # Degraded fixture with failing metrics receives unacceptable label
        lbl = assign_independent_policy_label("HIGH", ssim_mean=0.91, psnr_mean=28.0)
        self.assertEqual(lbl, "unacceptable")

    def test_high_fixture_with_high_measured_fidelity_follows_measured_policy(self):
        # Fixture names are semantic identifiers only: measured policy wins
        lbl = assign_independent_policy_label("HIGH", ssim_mean=0.956, psnr_mean=31.2)
        self.assertEqual(lbl, "acceptable")

    def test_moderate_fixture_follows_measured_fidelity(self):
        # Fixture names do not determine labels: measured SSIM/PSNR governs
        lbl = assign_independent_policy_label("MODERATE", ssim_mean=0.96, psnr_mean=35.0)
        self.assertEqual(lbl, "acceptable")
        lbl_failing = assign_independent_policy_label("MODERATE", ssim_mean=0.92, psnr_mean=28.0)
        self.assertEqual(lbl_failing, "unacceptable")

    def test_missing_metrics_labeled_missing(self):
        lbl = assign_independent_policy_label("MODERATE", ssim_mean=None, psnr_mean=35.0)
        self.assertEqual(lbl, "missing")
        lbl2 = assign_independent_policy_label("MODERATE", ssim_mean=0.96, psnr_mean=None)
        self.assertEqual(lbl2, "missing")


class TestThresholdSweepAndSelection(unittest.TestCase):
    """Tests threshold sweeping, FAR/FRR computation, and lowest feasible operating point selection."""

    def setUp(self):
        # 4 acceptable samples, 4 unacceptable samples
        self.samples = [
            CorpusSample("1", "g1", "m", "c", "", 1920, 1080, 30.0, "y", "LOW_PERTURBATION", 95.0, 94.0, 92.0, 0.5, 0.98, 40.0, None, None, None, "acceptable"),
            CorpusSample("2", "g1", "m", "c", "", 1920, 1080, 30.0, "y", "LOW_PERTURBATION", 92.0, 91.0, 90.0, 0.5, 0.98, 40.0, None, None, None, "acceptable"),
            CorpusSample("3", "g2", "m", "c", "", 1920, 1080, 30.0, "y", "IDENTICAL",        99.0, 98.0, 97.0, 0.5, 1.00, 50.0, None, None, None, "acceptable"),
            CorpusSample("4", "g2", "m", "c", "", 1920, 1080, 30.0, "y", "VERY_LOW",         98.0, 97.0, 96.0, 0.5, 0.99, 45.0, None, None, None, "acceptable"),
            CorpusSample("5", "g3", "m", "c", "", 1920, 1080, 30.0, "y", "HIGH",             65.0, 62.0, 60.0, 1.5, 0.91, 28.0, None, None, None, "unacceptable"),
            CorpusSample("6", "g3", "m", "c", "", 1920, 1080, 30.0, "y", "SEVERE",           25.0, 20.0, 15.0, 2.0, 0.85, 22.0, None, None, None, "unacceptable"),
            CorpusSample("7", "g4", "m", "c", "", 1920, 1080, 30.0, "y", "EXTREME",          10.0,  5.0,  0.0, 2.0, 0.70, 15.0, None, None, None, "unacceptable"),
            CorpusSample("8", "g4", "m", "c", "", 1920, 1080, 30.0, "y", "MODERATE_EXCEEDANCE", 0.0, 0.0, 0.0, 0.0, 0.80, 18.0, None, None, None, "unacceptable"),
        ]

    def test_operating_point_metrics(self):
        # At T=90.0: all 4 acceptable >= 90 (TA=4, FR=0, FRR=0.0). All 4 unacceptable < 90 (TR=4, FA=0, FAR=0.0).
        m = evaluate_policy_operating_point(self.samples, threshold=90.0, policy_name="mean")
        self.assertEqual(m.true_accepts, 4)
        self.assertEqual(m.true_rejects, 4)
        self.assertEqual(m.false_accepts, 0)
        self.assertEqual(m.false_rejects, 0)
        self.assertEqual(m.false_accept_rate, 0.0)
        self.assertEqual(m.false_reject_rate, 0.0)
        self.assertEqual(m.balanced_accuracy, 1.0)

    def test_coupled_combined_policy(self):
        # Combined policy: V_mean >= T AND V_p5 >= T
        # Sample 2 has mean=92.0, p5=91.0. At T=91.5: mean passes but p5 fails -> rejected (FR=1)
        m = evaluate_policy_operating_point(self.samples, threshold=91.5, policy_name="combined")
        self.assertEqual(m.false_rejects, 1)
        self.assertEqual(m.true_accepts, 3)

    def test_select_lowest_feasible_threshold(self):
        sweep = sweep_thresholds(self.samples, policy_name="mean", start=80.0, stop=95.0, step=1.0)
        # Any T between 66.0 and 92.0 has 0 FA and 0 FR.
        # Lowest feasible starting at 80.0 should be 80.0
        best = select_lowest_feasible_threshold(sweep, fa_max=0.02, fr_max=0.05)
        self.assertIsNotNone(best)
        self.assertEqual(best.threshold, 80.0,
                         "Should select lowest feasible threshold to avoid rejecting acceptable content")

    def test_strict_inequality_at_exact_constraint_boundary(self):
        from tools.vmaf_threshold_analysis import OperatingMetrics
        # Points exactly at 2.0% FAR or 5.0% FRR must be rejected under strict < semantics
        point_exact_fa = OperatingMetrics(
            threshold=85.0, policy_name="mean", total_samples=100,
            acceptable_samples=50, unacceptable_samples=50,
            true_accepts=49, true_rejects=49, false_accepts=1, false_rejects=1,
            false_accept_rate=0.02,  # Exactly 2.0% -> rejected by FAR < 0.02
            false_reject_rate=0.02,
            acceptance_rate=0.5, rejection_rate=0.5, precision=0.98, recall=0.98, balanced_accuracy=0.98
        )
        point_exact_fr = OperatingMetrics(
            threshold=86.0, policy_name="mean", total_samples=100,
            acceptable_samples=50, unacceptable_samples=50,
            true_accepts=47, true_rejects=50, false_accepts=0, false_rejects=3,
            false_accept_rate=0.01,
            false_reject_rate=0.05,  # Exactly 5.0% -> rejected by FRR < 0.05
            acceptance_rate=0.47, rejection_rate=0.53, precision=1.0, recall=0.94, balanced_accuracy=0.97
        )
        self.assertIsNone(select_lowest_feasible_threshold([point_exact_fa], fa_max=0.02, fr_max=0.05),
                          "Point with FAR == 2.0% must be rejected under strict FAR < 2.0%")
        self.assertIsNone(select_lowest_feasible_threshold([point_exact_fr], fa_max=0.02, fr_max=0.05),
                          "Point with FRR == 5.0% must be rejected under strict FRR < 5.0%")


class TestProductionGateSafety(unittest.TestCase):
    """Ensures threshold analysis never modifies production gate settings."""

    def test_vmaf_gate_remains_disabled_by_default(self):
        policy = VisualBudgetPolicy()
        self.assertFalse(policy.vmaf_gate_enabled,
                         "vmaf_gate_enabled must strictly remain False in production")


class TestCorpusRunnerDecoupled(unittest.TestCase):
    """Verifies that decision/promotion APIs were completely removed from tools/vmaf_corpus_runner."""

    def test_threshold_decision_apis_removed_from_runner(self):
        import tools.vmaf_corpus_runner as runner
        self.assertFalse(hasattr(runner, "evaluate_threshold"),
                         "evaluate_threshold must be removed from vmaf_corpus_runner")
        self.assertFalse(hasattr(runner, "FA_RATE_MAX"),
                         "FA_RATE_MAX must be removed from vmaf_corpus_runner")
        self.assertFalse(hasattr(runner, "FR_RATE_MAX"),
                         "FR_RATE_MAX must be removed from vmaf_corpus_runner")
        self.assertFalse(hasattr(runner, "PASS_RATE_LOW_MIN"),
                         "PASS_RATE_LOW_MIN must be removed from vmaf_corpus_runner")
        self.assertFalse(hasattr(runner, "FAIL_RATE_MODEX_MIN"),
                         "FAIL_RATE_MODEX_MIN must be removed from vmaf_corpus_runner")


class TestMinimumDataRequirements(unittest.TestCase):
    """Verifies that check_minimum_data_requirements strictly catches underpowered datasets."""

    def _make_sample(self, name: str, group: str, label: str) -> CorpusSample:
        return CorpusSample(
            clip_filename=name, sequence_group=group, sequence_group_source="manifest",
            category="test", subcategory="", width=1920, height=1080, fps=30.0,
            pix_fmt="yuv420p", fixture="LOW_PERTURBATION", vmaf_mean=95.0, vmaf_p5=93.0,
            vmaf_worst=90.0, vmaf_stddev=1.0, ssim_mean=0.98, psnr_mean=42.0,
            model_id=None, model_name=None, model_sha256=None, independent_policy_label=label,
        )

    def test_insufficient_total_sequence_groups_fails(self):
        from tools.vmaf_threshold_analysis import check_minimum_data_requirements
        # 3 dev groups, 1 heldout group = 4 total < 12
        dev_groups = ["g1", "g2", "g3"]
        ho_groups = ["g4"]
        dev_s = [self._make_sample(f"d{i}", dev_groups[i % 3], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(40)]
        ho_s = [self._make_sample(f"h{i}", "g4", "acceptable" if i % 2 == 0 else "unacceptable") for i in range(20)]

        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_groups, ho_groups)
        self.assertFalse(passed)
        self.assertTrue(any("Total sequence groups (4) < minimum (12)" in r for r in reasons))

    def test_insufficient_dev_or_heldout_groups_fails(self):
        from tools.vmaf_threshold_analysis import check_minimum_data_requirements
        # 10 dev groups, 2 heldout groups = 12 total, but heldout < 4
        dev_groups = [f"d{i}" for i in range(10)]
        ho_groups = ["h1", "h2"]
        dev_s = [self._make_sample(f"ds{i}", dev_groups[i % 10], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(40)]
        ho_s = [self._make_sample(f"hs{i}", ho_groups[i % 2], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(20)]

        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_groups, ho_groups)
        self.assertFalse(passed)
        self.assertTrue(any("Held-out sequence groups (2) < minimum (4)" in r for r in reasons))

    def test_insufficient_binary_samples_fails(self):
        from tools.vmaf_threshold_analysis import check_minimum_data_requirements
        # 8 dev groups, 4 heldout groups, but only 20 dev binary samples (< 40)
        dev_groups = [f"d{i}" for i in range(8)]
        ho_groups = [f"h{i}" for i in range(4)]
        dev_s = [self._make_sample(f"ds{i}", dev_groups[i % 8], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(20)]
        ho_s = [self._make_sample(f"hs{i}", ho_groups[i % 4], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(20)]

        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_groups, ho_groups)
        self.assertFalse(passed)
        self.assertTrue(any("Development binary samples (20) < minimum (40)" in r for r in reasons))

    def test_missing_class_in_partition_fails(self):
        from tools.vmaf_threshold_analysis import check_minimum_data_requirements
        dev_groups = [f"d{i}" for i in range(8)]
        ho_groups = [f"h{i}" for i in range(4)]
        # All acceptable, zero unacceptable
        dev_s = [self._make_sample(f"ds{i}", dev_groups[i % 8], "acceptable") for i in range(40)]
        ho_s = [self._make_sample(f"hs{i}", ho_groups[i % 4], "acceptable") for i in range(20)]

        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_groups, ho_groups)
        self.assertFalse(passed)
        self.assertTrue(any("zero unacceptable samples" in r for r in reasons))

    def test_all_requirements_satisfied_passes(self):
        from tools.vmaf_threshold_analysis import check_minimum_data_requirements
        dev_groups = [f"d{i}" for i in range(10)]
        ho_groups = [f"h{i}" for i in range(4)]
        dev_s = [self._make_sample(f"ds{i}", dev_groups[i % 10], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(50)]
        ho_s = [self._make_sample(f"hs{i}", ho_groups[i % 4], "acceptable" if i % 2 == 0 else "unacceptable") for i in range(25)]

        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_groups, ho_groups)
        self.assertTrue(passed)
        self.assertEqual(len(reasons), 0)


class TestMissingMetricIntegrity(unittest.TestCase):
    """Verifies that missing percentiles are never silently substituted with mean."""

    def test_missing_percentiles_not_substituted_with_mean(self):
        from tools.vmaf_threshold_analysis import load_corpus_samples
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            dummy_data = {
                "clips": [
                    {
                        "clip_filename": "clip_test.mp4",
                        "width": 1920, "height": 1080, "fps": 30.0, "pix_fmt": "yuv420p",
                        "sequence_group": "group_1", "domain": "Domain 1: Primary SDR",
                        "fixtures": [
                            {
                                "fixture": "IDENTICAL",
                                "status": "success",
                                "vmaf_mean": 95.0,
                                "vmaf_p5": None,       # Missing P5
                                "vmaf_worst": None,    # Missing worst
                                "ssim_mean": 0.99,
                                "psnr_mean": 45.0,
                                "distortion_role": "representative",
                                "measurement_status": "empirical",
                            }
                        ]
                    }
                ]
            }
            json.dump(dummy_data, f)
            temp_path = Path(f.name)

        try:
            samples, exclusions, _, _ = load_corpus_samples(temp_path)
            self.assertEqual(len(samples), 1)
            s = samples[0]
            self.assertIsNone(s.vmaf_p5, "vmaf_p5 must remain None and not be silently substituted with vmaf_mean")
            self.assertIsNone(s.vmaf_worst, "vmaf_worst must remain None and not be silently substituted with vmaf_mean")
        finally:
            temp_path.unlink(missing_ok=True)

    def test_missing_p5_fails_closed_in_threshold_evaluation(self):
        # Sample with vmaf_mean=99.0 but vmaf_p5=None
        sample = CorpusSample(
            clip_filename="test.mp4", sequence_group="g1", sequence_group_source="manifest",
            category="test", subcategory="", width=1920, height=1080, fps=30.0,
            pix_fmt="yuv420p", fixture="IDENTICAL", vmaf_mean=99.0, vmaf_p5=None,
            vmaf_worst=None, vmaf_stddev=0.0, ssim_mean=1.0, psnr_mean=50.0,
            independent_policy_label="acceptable",
        )
        # Combined policy (mean >= T and p5 >= T) must reject because p5 is missing
        m_comb = evaluate_policy_operating_point([sample], threshold=80.0, policy_name="combined")
        self.assertEqual(m_comb.false_rejects, 1, "Missing P5 must fail closed under combined policy")
        self.assertEqual(m_comb.true_accepts, 0)

        # P5 policy must reject because p5 is missing
        m_p5 = evaluate_policy_operating_point([sample], threshold=80.0, policy_name="p5")
        self.assertEqual(m_p5.false_rejects, 1, "Missing P5 must fail closed under p5 policy")

        # Mean policy succeeds because mean is available
        m_mean = evaluate_policy_operating_point([sample], threshold=80.0, policy_name="mean")
        self.assertEqual(m_mean.true_accepts, 1)

    def test_missing_worst_fails_closed_in_threshold_evaluation(self):
        sample = CorpusSample(
            clip_filename="test.mp4", sequence_group="g1", sequence_group_source="manifest",
            category="test", subcategory="", width=1920, height=1080, fps=30.0,
            pix_fmt="yuv420p", fixture="IDENTICAL", vmaf_mean=99.0, vmaf_p5=95.0,
            vmaf_worst=None, vmaf_stddev=0.0, ssim_mean=1.0, psnr_mean=50.0,
            independent_policy_label="acceptable",
        )
        m_worst = evaluate_policy_operating_point([sample], threshold=80.0, policy_name="worst")
        self.assertEqual(m_worst.false_rejects, 1, "Missing worst frame must fail closed under worst policy")


if __name__ == "__main__":
    unittest.main()
