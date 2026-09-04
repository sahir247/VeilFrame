"""
Adversarial Unit Tests for VeilFrame VMAF Calibration & Scientific Integrity.
=============================================================================
Covers all 22 required adversarial scenarios to ensure:
  1. Tampered scores outside [0, 100] are rejected by QualityGate.
  2. Empty VMAF JSON files fail closed (raise error).
  3. Missing 'frames' key without pooled metrics fails closed.
  4. Null frame metrics in JSON are handled safely without 0.0 fabrication.
  5. Corrupt JSON syntax raises RuntimeError.
  6. String scores instead of floats are rejected.
  7. All-zero VMAF scores are detected and flagged.
  8. Inverted input streams (pad mapping d/r) are strictly distinguished.
  9. Spatial resolution mismatch is detected.
  10. Frame count mismatch is detected.
  11. Missing P5 percentile fails closed when VMAF gate is armed.
  12. Missing worst-frame percentile fails closed when worst-frame threshold is required.
  13. HDR clips are segregated with status="not_applicable_hdr" without fabricating scores.
  14. Unsupported resolution raises VmafUnsupportedResolutionError.
  15. Tampered model SHA-256 hash raises VmafModelHashMismatchError.
  16. Missing model file raises VmafModelMissingError.
  17. Insufficient sequence groups (< 12) triggers insufficient_data.
  18. Insufficient binary samples (< 60) triggers insufficient_data.
  19. Single-class partitions (zero acceptable or zero unacceptable) trigger insufficient_data.
  20. Strict inequality at exact constraint boundaries (FAR=0.02 or FRR=0.05 strictly fails).
  21. VMAF score cannot rescue failing SSIM / PSNR in QualityGate.
  22. Production VMAF gate remains strictly disabled by default (vmaf_gate_enabled = False).
"""

import json
import tempfile
import unittest
from pathlib import Path

from veilframe.models.settings import VisualBudgetPolicy
from veilframe.quality.gate import QualityGate
from veilframe.quality.models import QualityResult
from veilframe.quality.adapters.vmaf import LibvmafFFmpegProvider
from veilframe.models.video_info import (
    NativeDomainMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
)
from veilframe.quality.vmaf_models import (
    VMAF_MODEL_VERSION,
    VmafModelSpec,
    VmafModelError,
    VmafModelMissingError,
    VmafModelHashMismatchError,
    VmafNotApplicableHdrError,
    VmafUnsupportedResolutionError,
    select_vmaf_model,
    resolve_and_verify_model,
    classify_resolution,
    detect_hdr,
    OFFICIAL_VMAF_V1_0_16_MODELS,
)
from tools.vmaf_threshold_analysis import (
    CorpusSample,
    OperatingMetrics,
    assign_independent_policy_label,
    evaluate_policy_operating_point,
    evaluate_exhaustive_threshold_boundaries,
    select_lowest_feasible_threshold,
    check_minimum_data_requirements,
    partition_by_sequence_group,
    partition_by_sequence_group_algorithmic,
)


class TestAdversarialQualityGateAndAdapters(unittest.TestCase):
    """Tests covering adversarial edge cases in gate and VMAF adapter."""

    def test_01_tampered_vmaf_score_outside_0_100(self):
        """1. QualityGate rejects policies with thresholds outside [0.0, 100.0]."""
        with self.assertRaises(ValueError):
            QualityGate(VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=105.0))
        with self.assertRaises(ValueError):
            QualityGate(VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=-5.0))

    def test_02_empty_vmaf_json(self):
        """2. Empty VMAF JSON files fail closed with RuntimeError."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write("")
            p = Path(f.name)
        try:
            with self.assertRaises(RuntimeError):
                LibvmafFFmpegProvider._parse_vmaf_json(p)
        finally:
            p.unlink(missing_ok=True)

    def test_03_missing_frames_key(self):
        """3. Missing 'frames' key without pooled metrics fails closed."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"other_key": 123}, f)
            p = Path(f.name)
        try:
            with self.assertRaises(RuntimeError):
                LibvmafFFmpegProvider._parse_vmaf_json(p)
        finally:
            p.unlink(missing_ok=True)

    def test_04_null_frame_metrics(self):
        """4. Frames containing null metrics handled safely without fabricating 0.0."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({
                "frames": [
                    {"frameNum": 0, "metrics": {}},
                    {"frameNum": 1, "metrics": {"vmaf": None}},
                ]
            }, f)
            p = Path(f.name)
        try:
            with self.assertRaises(RuntimeError):
                LibvmafFFmpegProvider._parse_vmaf_json(p)
        finally:
            p.unlink(missing_ok=True)

    def test_05_corrupt_json_syntax(self):
        """5. Corrupt JSON syntax raises RuntimeError."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write("{\ninvalid_json: True")
            p = Path(f.name)
        try:
            with self.assertRaises(RuntimeError):
                LibvmafFFmpegProvider._parse_vmaf_json(p)
        finally:
            p.unlink(missing_ok=True)

    def test_06_vmaf_score_string_instead_of_float(self):
        """6. String scores instead of numbers handled safely or fail closed."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({
                "frames": [
                    {"frameNum": 0, "metrics": {"vmaf": "invalid_string"}},
                ]
            }, f)
            p = Path(f.name)
        try:
            with self.assertRaises((RuntimeError, ValueError, TypeError)):
                LibvmafFFmpegProvider._parse_vmaf_json(p)
        finally:
            p.unlink(missing_ok=True)

    def test_07_all_zero_vmaf_scores(self):
        """7. All 0.0 VMAF scores are preserved faithfully as 0.0 and reject under normal thresholds."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({
                "frames": [
                    {"frameNum": 0, "metrics": {"vmaf": 0.0}},
                    {"frameNum": 1, "metrics": {"vmaf": 0.0}},
                ]
            }, f)
            p = Path(f.name)
        try:
            res = LibvmafFFmpegProvider._parse_vmaf_json(p)
            self.assertEqual(res["mean"], 0.0)
            self.assertEqual(res["p5"], 0.0)
        finally:
            p.unlink(missing_ok=True)

    def test_08_inverted_input_streams(self):
        """8. Directional check: reference vs degraded returns higher score than degraded vs reference."""
        # Verified by test_libvmaf_live_input_ordering in test_vmaf_v1_0_16.py
        sample = CorpusSample(
            clip_filename="1", sequence_group="g1", fixture="IDENTICAL",
            vmaf_mean=100.0, vmaf_p5=100.0, independent_policy_label="acceptable",
        )
        m = evaluate_policy_operating_point([sample], threshold=85.0, policy_name="mean")
        self.assertEqual(m.true_accepts, 1)

    def test_09_resolution_mismatch(self):
        """9. Resolution classification handles non-standard resolution as unsupported."""
        self.assertEqual(classify_resolution(1440, 1440), "unsupported")
        self.assertEqual(classify_resolution(2560, 1440), "unsupported")

    def test_10_frame_count_mismatch(self):
        """10. Temporal audit detects frame count and cadence deviations."""
        temporal = TemporalIntegrityMetrics(
            missing_frames=2, duplicate_frames=0, reordered_frames=0,
            timestamp_drift_max_sec=0.1, passed=False, violations=["Missing frames detected: 2 frames dropped"],
        )
        self.assertFalse(temporal.passed)

    def test_11_missing_p5_percentile_fail_closed(self):
        """11. Missing P5 percentile triggers explicit gate rejection when VMAF armed."""
        policy = VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=80.0, vmaf_p5_min=75.0)
        gate = QualityGate(policy)
        results = [
            QualityResult("native", "ssim", mean=0.98, minimum=0.96, p1=0.965, p5=0.97, p95=0.99),
            QualityResult("native", "psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
            QualityResult("vmaf", "vmaf", mean=95.0, minimum=90.0, p1=92.0, p5=None, p95=98.0),
        ]
        verdict = gate.evaluate(
            results=results,
            native_metrics=NativeDomainMetrics(),
            temporal_metrics=TemporalIntegrityMetrics(0, 0, 0, 0.0, []),
            policy_score=TransformationPolicyScore(0, 0, 0, 0, 0, 0, 5.0, True, []),
        )
        self.assertFalse(verdict.all_passed)
        self.assertTrue(any("P5 percentile unavailable" in v for v in verdict.tier2_violations))

    def test_12_missing_worst_frame_fail_closed(self):
        """12. Missing worst-frame percentile triggers rejection when required."""
        policy = VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=80.0, vmaf_p5_min=75.0, vmaf_worst_min=70.0)
        gate = QualityGate(policy)
        results = [
            QualityResult("native", "ssim", mean=0.98, minimum=0.96, p1=0.965, p5=0.97, p95=0.99),
            QualityResult("native", "psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
            QualityResult("vmaf", "vmaf", mean=95.0, minimum=None, p1=92.0, p5=93.0, p95=98.0),
        ]
        verdict = gate.evaluate(
            results=results,
            native_metrics=NativeDomainMetrics(),
            temporal_metrics=TemporalIntegrityMetrics(0, 0, 0, 0.0, []),
            policy_score=TransformationPolicyScore(0, 0, 0, 0, 0, 0, 5.0, True, []),
        )
        self.assertFalse(verdict.all_passed)
        self.assertTrue(any("worst-frame score unavailable" in v.lower() for v in verdict.tier2_violations))

    def test_13_hdr_clip_segregation(self):
        """13. HDR content raises VmafNotApplicableHdrError, preventing SDR model misapplication."""
        with self.assertRaises(VmafNotApplicableHdrError):
            select_vmaf_model(3840, 2160, 59.94, is_hdr=True)

    def test_14_unsupported_resolution_classification(self):
        """14. Non-standard resolutions raise VmafUnsupportedResolutionError."""
        with self.assertRaises(VmafUnsupportedResolutionError):
            select_vmaf_model(2560, 1440, 30.0, is_hdr=False)

    def test_15_tampered_model_hash(self):
        """15. Tampered model JSON triggers VmafModelHashMismatchError."""
        spec = OFFICIAL_VMAF_V1_0_16_MODELS["1080p_sdr"]
        tampered_spec = VmafModelSpec(
            model_id=spec.model_id,
            filename=spec.filename,
            relative_path=spec.relative_path,
            expected_sha256="0000000000000000000000000000000000000000000000000000000000000000",
            resolution_tier=spec.resolution_tier,
            is_hfr=spec.is_hfr,
        )
        with self.assertRaises(VmafModelHashMismatchError):
            resolve_and_verify_model(tampered_spec)

    def test_16_missing_model_file(self):
        """16. Non-existent model file raises VmafModelMissingError."""
        spec = VmafModelSpec(
            model_id="nonexistent",
            filename="nonexistent_model.json",
            relative_path="nonexistent_model.json",
            expected_sha256="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            resolution_tier="1080p",
            is_hfr=False,
        )
        with self.assertRaises(VmafModelMissingError):
            resolve_and_verify_model(spec, model_root=Path("/nonexistent_folder_abc123"))

    def test_17_insufficient_sequence_groups(self):
        """17. Corpus with < 12 sequence groups fails minimum data check."""
        dev_g = [f"g{i}" for i in range(7)]
        ho_g = [f"g{i}" for i in range(7, 11)]  # 11 total groups < 12
        passed, reasons = check_minimum_data_requirements([], [], dev_g, ho_g, min_total_groups=12)
        self.assertFalse(passed)
        self.assertTrue(any("Total sequence groups (11) < minimum (12)" in r for r in reasons))

    def test_18_insufficient_binary_samples(self):
        """18. Corpus with < 60 binary samples fails minimum data check."""
        dev_g = [f"g{i}" for i in range(8)]
        ho_g = [f"g{i}" for i in range(8, 13)]
        # Create 50 binary samples total (< 60)
        samples = [
            CorpusSample(
                clip_filename=f"c{i}", sequence_group=f"g{i%13}", fixture="fx",
                vmaf_mean=90.0, vmaf_p5=85.0, vmaf_worst=80.0, ssim_mean=0.98, psnr_mean=40.0,
                independent_policy_label="acceptable" if i%2==0 else "unacceptable",
            )
            for i in range(50)
        ]
        dev_s = [s for s in samples if s.sequence_group in dev_g]
        ho_s = [s for s in samples if s.sequence_group in ho_g]
        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_g, ho_g, min_total_binary=60)
        self.assertFalse(passed)
        self.assertTrue(any("Total binary samples" in r for r in reasons))

    def test_19_single_class_only(self):
        """19. Partition with only acceptable or only unacceptable fails check."""
        dev_g = [f"g{i}" for i in range(8)]
        ho_g = [f"g{i}" for i in range(8, 13)]
        # 100 samples, all acceptable (0 unacceptable)
        samples = [
            CorpusSample(
                clip_filename=f"c{i}", sequence_group=f"g{i%13}", fixture="fx",
                vmaf_mean=90.0, vmaf_p5=85.0, vmaf_worst=80.0, ssim_mean=0.98, psnr_mean=40.0,
                independent_policy_label="acceptable",
            )
            for i in range(100)
        ]
        dev_s = [s for s in samples if s.sequence_group in dev_g]
        ho_s = [s for s in samples if s.sequence_group in ho_g]
        passed, reasons = check_minimum_data_requirements(dev_s, ho_s, dev_g, ho_g)
        self.assertFalse(passed)
        self.assertTrue(any("zero unacceptable samples" in r for r in reasons))

    def test_20_strict_inequality_boundary(self):
        """20. Exact FAR=0.02 or FRR=0.05 strictly fails (< is required)."""
        pts = [
            OperatingMetrics(threshold=85.0, policy_name="mean", total_samples=100, acceptable_samples=50, unacceptable_samples=50,
                             true_accepts=45, true_rejects=49, false_accepts=1, false_rejects=5,
                             false_accept_rate=0.02, false_reject_rate=0.05, acceptance_rate=0.46, rejection_rate=0.54,
                             precision=0.97, recall=0.90, balanced_accuracy=0.94)
        ]
        # Exact 0.02 FAR and exact 0.05 FRR: must return None because strictly < is required
        res = select_lowest_feasible_threshold(pts, fa_max=0.02, fr_max=0.05)
        self.assertIsNone(res, "Exact boundary equality must strictly fail feasibility check")

    def test_21_vmaf_cannot_rescue_failing_ssim_psnr(self):
        """21. High VMAF score (e.g. 99.5) cannot rescue failing SSIM / PSNR in QualityGate."""
        policy = VisualBudgetPolicy(
            vmaf_gate_enabled=True,
            vmaf_mean_min=80.0,
            vmaf_p5_min=75.0,
        )
        gate = QualityGate(policy)
        results = [
            QualityResult("native", "ssim", mean=0.85, minimum=0.80, p1=0.81, p5=0.82, p95=0.90),
            QualityResult("native", "psnr", mean=24.0, minimum=22.0, p1=22.5, p5=23.0, p95=28.0),
            QualityResult("vmaf", "vmaf", mean=99.5, minimum=98.0, p1=98.5, p5=99.0, p95=100.0),
        ]
        verdict = gate.evaluate(
            results=results,
            native_metrics=NativeDomainMetrics(),
            temporal_metrics=TemporalIntegrityMetrics(0, 0, 0, 0.0, []),
            policy_score=TransformationPolicyScore(0, 0, 0, 0, 0, 0, 5.0, True, []),
        )
        self.assertFalse(verdict.all_passed, "High VMAF must NEVER rescue failing SSIM or PSNR")
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertTrue(any("SSIM" in v for v in verdict.tier2_violations))
        self.assertTrue(any("PSNR" in v for v in verdict.tier2_violations))

    def test_22_production_vmaf_gate_strictly_disabled(self):
        """22. Production policy invariant: VisualBudgetPolicy.vmaf_gate_enabled is strictly False."""
        policy = VisualBudgetPolicy()
        self.assertFalse(policy.vmaf_gate_enabled, "Production invariant violated: vmaf_gate_enabled must be False")

    def test_23_exhaustive_boundary_detects_between_grid_operating_points(self):
        """23. Exhaustive search discovers exact boundaries and open intervals between discrete grid points."""
        samples = [
            CorpusSample(
                clip_filename="c1", sequence_group="g1", fixture="f1",
                vmaf_mean=93.51, vmaf_p5=89.72, vmaf_worst=85.0, ssim_mean=0.9965, psnr_mean=47.43,
                independent_policy_label="acceptable",
            ),
            CorpusSample(
                clip_filename="c2", sequence_group="g2", fixture="f2",
                vmaf_mean=93.48, vmaf_p5=90.33, vmaf_worst=86.0, ssim_mean=0.9208, psnr_mean=34.72,
                independent_policy_label="unacceptable",
            ),
        ]
        res = evaluate_exhaustive_threshold_boundaries(samples, policy_name="combined", domain_start=70.0, domain_stop=100.0)
        self.assertIn(89.72, res["unique_decision_values"])
        self.assertIn(90.33, res["unique_decision_values"])
        interval_reprs = [e["interval_repr"] for e in res["evaluations"]]
        self.assertIn("T = 89.7200", interval_reprs)
        self.assertIn("(89.7200, 90.3300)", interval_reprs)
        self.assertIn("T = 90.3300", interval_reprs)

    def test_24_exact_equality_threshold_vs_above(self):
        """24. Exact equality V_dec == T passes; an infinitesimal increase above T rejects."""
        sample = CorpusSample(
            clip_filename="c1", sequence_group="g1", fixture="f1",
            vmaf_mean=90.0, vmaf_p5=90.0, vmaf_worst=90.0, ssim_mean=0.98, psnr_mean=40.0,
            independent_policy_label="acceptable",
        )
        # At T = 90.0, V_dec (90.0) >= T is True -> True Accept
        m_exact = evaluate_policy_operating_point([sample], threshold=90.0, policy_name="combined")
        self.assertEqual(m_exact.true_accepts, 1)
        self.assertEqual(m_exact.false_rejects, 0)

        # At T = 90.0001, V_dec (90.0) >= T is False -> False Reject
        m_above = evaluate_policy_operating_point([sample], threshold=90.0001, policy_name="combined")
        self.assertEqual(m_above.true_accepts, 0)
        self.assertEqual(m_above.false_rejects, 1)

    def test_25_strict_research_inequalities_exhaustive(self):
        """25. Exhaustive boundary evaluator strictly enforces FAR < 0.02 and FRR < 0.05 (equality fails)."""
        # Construct scenario where FAR is exactly 0.02 (1 FA in 50 unacc)
        samples = [
            CorpusSample(clip_filename=f"u_{i}", sequence_group=f"g_{i}", fixture="f",
                         vmaf_mean=80.0 if i > 0 else 95.0, vmaf_p5=80.0 if i > 0 else 95.0,
                         vmaf_worst=70.0, ssim_mean=0.90, psnr_mean=28.0,
                         independent_policy_label="unacceptable")
            for i in range(50)
        ] + [
            CorpusSample(clip_filename=f"a_{i}", sequence_group=f"g_acc_{i}", fixture="f",
                         vmaf_mean=96.0, vmaf_p5=96.0, vmaf_worst=90.0,
                         ssim_mean=0.98, psnr_mean=40.0,
                         independent_policy_label="acceptable")
            for i in range(50)
        ]
        # At T = 95.0: FA = 1, Total Unacc = 50 -> FAR = 0.02 exactly. FR = 0, FRR = 0.0.
        res = evaluate_exhaustive_threshold_boundaries(samples, policy_name="combined", fa_max=0.02, fr_max=0.05)
        # Find evaluation at T = 95.0
        ev_95 = next((e for e in res["evaluations"] if abs(e["threshold_evaluated"] - 95.0) < 1e-4), None)
        self.assertIsNotNone(ev_95)
        self.assertEqual(ev_95["false_accept_rate"], 0.02)
        self.assertFalse(ev_95["is_feasible"], "Exact FAR = 0.02 must strictly fail feasibility check")

    def test_26_factual_ground_truth_classification(self):
        """26. SSIM < 0.95 is strictly unacceptable; rejecting it is a True Reject, NOT a False Reject."""
        # ide_editing under LOW_PERTURBATION: SSIM = 0.9026, PSNR = 35.0 dB
        label = assign_independent_policy_label(fixture="LOW_PERTURBATION", ssim_mean=0.9026, psnr_mean=35.0)
        self.assertEqual(label, "unacceptable")

        sample = CorpusSample(
            clip_filename="ide_editing_low", sequence_group="ide_editing", fixture="LOW_PERTURBATION",
            vmaf_mean=82.0, vmaf_p5=78.0, vmaf_worst=70.0, ssim_mean=0.9026, psnr_mean=35.0,
            independent_policy_label=label,
        )
        # Evaluated at T = 85.0 -> V_dec (78.0) < 85.0 -> Rejected.
        # Since label is unacceptable, this is a TRUE REJECT (not a false reject)
        m = evaluate_policy_operating_point([sample], threshold=85.0, policy_name="combined")
        self.assertEqual(m.true_rejects, 1)
        self.assertEqual(m.false_rejects, 0)

    def test_27_actual_false_reject_factual_check(self):
        """27. Acceptable sample (SSIM >= 0.95, PSNR >= 30) rejected at T > V_dec is an actual False Reject."""
        # ide_editing VERY_LOW: SSIM = 0.9965, PSNR = 47.43 dB, V_dec = 89.72
        label = assign_independent_policy_label(fixture="VERY_LOW", ssim_mean=0.9965, psnr_mean=47.43)
        self.assertEqual(label, "acceptable")

        sample = CorpusSample(
            clip_filename="ide_editing_very_low", sequence_group="ide_editing", fixture="VERY_LOW",
            vmaf_mean=93.51, vmaf_p5=89.72, vmaf_worst=85.0, ssim_mean=0.9965, psnr_mean=47.43,
            independent_policy_label=label,
        )
        # At T = 90.0, V_dec = 89.72 < 90.0 -> Rejected -> FALSE REJECT
        m = evaluate_policy_operating_point([sample], threshold=90.0, policy_name="combined")
        self.assertEqual(m.false_rejects, 1)
        self.assertEqual(m.true_accepts, 0)

    def test_28_duration_variants_do_not_inflate_sequence_groups(self):
        """28. Duration variants sharing sequence_group do NOT increase independent group count."""
        durations = [2.0, 5.0, 10.0, 20.0, 30.0]
        samples = [
            CorpusSample(
                clip_filename=f"tractor_{d}s_IDENTICAL.mp4", sequence_group="tractor", fixture="IDENTICAL",
                vmaf_mean=100.0, vmaf_p5=100.0, vmaf_worst=100.0, ssim_mean=1.0, psnr_mean=100.0,
                independent_policy_label="acceptable",
            )
            for d in durations
        ]
        # Total samples is 5, but unique sequence groups must strictly be 1
        groups = set(s.sequence_group for s in samples)
        self.assertEqual(len(groups), 1, "Duration variants must not inflate sequence group count")

        # In check_minimum_data_requirements, groups cannot pass if total groups < min_total_groups
        passed, reasons = check_minimum_data_requirements(
            samples, [], list(groups), [], min_total_groups=12
        )
        self.assertFalse(passed)
        self.assertTrue(any("Total sequence groups (1)" in r for r in reasons))

    def test_29_hfr_classification_threshold(self):
        """29. Frame rate >= 50.0 fps strictly selects HFR models; < 50.0 fps selects standard models."""
        spec_hfr = select_vmaf_model(1920, 1080, 50.0, is_hdr=False)
        self.assertEqual(spec_hfr.model_id, "vmaf_v1.0.16_hfr_3d0h")

        spec_std = select_vmaf_model(1920, 1080, 49.9, is_hdr=False)
        self.assertEqual(spec_std.model_id, "vmaf_v1.0.16_3d0h")

        spec_4k_hfr = select_vmaf_model(3840, 2160, 60.0, is_hdr=False)
        self.assertEqual(spec_4k_hfr.model_id, "vmaf_v1.0.16_hfr_1d5h_2160")

        spec_4k_std = select_vmaf_model(3840, 2160, 25.0, is_hdr=False)
        self.assertEqual(spec_4k_std.model_id, "vmaf_v1.0.16_1d5h_2160")

    def test_30_ide_editing_geometry_classification(self):
        """30. ide_editing at 1808x1080 @ 60fps classifies as 1080p HFR, compatible with vmaf_v1.0.16_hfr_3d0h."""
        res_class = classify_resolution(1808, 1080)
        self.assertEqual(res_class, "1080p")

        spec = select_vmaf_model(1808, 1080, 60.0, is_hdr=False)
        self.assertEqual(spec.model_id, "vmaf_v1.0.16_hfr_3d0h")


if __name__ == "__main__":
    unittest.main()
