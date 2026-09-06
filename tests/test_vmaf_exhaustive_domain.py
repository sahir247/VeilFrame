"""
Unit Tests for Exhaustive Decision-Boundary Domain Analysis [0.0, 100.0].
========================================================================
Verifies:
  - Exhaustive boundary search covers the entire valid metric domain [0.0, 100.0]
  - Values below 70.0 (e.g. 20.59, 35.17, 57.07) are evaluated
  - Exact boundaries at 0.0 and 100.0 are evaluated
  - Open intervals between adjacent observed scores are evaluated
  - Integer confusion matrix and error limits are reported
"""
import pytest
from tools.vmaf_threshold_analysis import (
    CorpusSample,
    evaluate_exhaustive_threshold_boundaries,
)


def _create_test_samples():
    return [
        CorpusSample(
            clip_filename="c1.mp4", sequence_group="g1", fixture="FX_LOW_BLUR",
            vmaf_mean=20.59, vmaf_p5=0.0, vmaf_worst=0.0,
            ssim_mean=0.9673, psnr_mean=33.41, independent_policy_label="acceptable",
        ),
        CorpusSample(
            clip_filename="c1.mp4", sequence_group="g1", fixture="FX_MOD_BLUR",
            vmaf_mean=39.87, vmaf_p5=1.62, vmaf_worst=0.09,
            ssim_mean=0.9768, psnr_mean=35.65, independent_policy_label="acceptable",
        ),
        CorpusSample(
            clip_filename="c2.mp4", sequence_group="g2", fixture="FX_QUANT_PASS",
            vmaf_mean=88.43, vmaf_p5=72.59, vmaf_worst=68.64,
            ssim_mean=0.9504, psnr_mean=30.72, independent_policy_label="acceptable",
        ),
        CorpusSample(
            clip_filename="c2.mp4", sequence_group="g2", fixture="FX_BRIGHT_FAIL",
            vmaf_mean=97.55, vmaf_p5=89.91, vmaf_worst=86.40,
            ssim_mean=0.9466, psnr_mean=28.29, independent_policy_label="unacceptable",
        ),
        CorpusSample(
            clip_filename="c3.mp4", sequence_group="g3", fixture="FX_NOISE_FAIL",
            vmaf_mean=88.18, vmaf_p5=72.09, vmaf_worst=63.32,
            ssim_mean=0.9051, psnr_mean=39.70, independent_policy_label="unacceptable",
        ),
    ]


def test_full_domain_evaluation_covers_sub_70_scores():
    samples = _create_test_samples()
    # Search over complete domain [0.0, 100.0]
    res = evaluate_exhaustive_threshold_boundaries(
        samples, policy_name="mean", domain_start=0.0, domain_stop=100.0
    )
    evaluations = res["evaluations"]
    evaluated_thresholds = [ev["threshold_evaluated"] for ev in evaluations]

    # Verify scores below 70 are explicitly evaluated
    assert 20.59 in evaluated_thresholds, "Must evaluate sub-70 score 20.59"
    assert 39.87 in evaluated_thresholds, "Must evaluate sub-70 score 39.87"

    # Verify boundaries at 0.0 and 100.0
    assert 0.0 in evaluated_thresholds, "Must evaluate left boundary 0.0"
    assert 100.0 in evaluated_thresholds, "Must evaluate right boundary 100.0"


def test_no_intermediate_intervals_skipped():
    samples = _create_test_samples()
    res = evaluate_exhaustive_threshold_boundaries(
        samples, policy_name="mean", domain_start=0.0, domain_stop=100.0
    )
    evaluations = res["evaluations"]

    # Check that for N distinct observed points, all N boundaries and open intervals exist
    observed = sorted(set(s.vmaf_mean for s in samples))
    exact_boundaries = [ev for ev in evaluations if ev["point_type"] == "exact_boundary"]
    assert len(exact_boundaries) == len(observed)

    intervals = [ev for ev in evaluations if ev["point_type"] == "open_interval"]
    assert len(intervals) == len(observed) - 1


def test_integer_confusion_matrix_reported():
    samples = _create_test_samples()
    res = evaluate_exhaustive_threshold_boundaries(
        samples, policy_name="mean", domain_start=0.0, domain_stop=100.0
    )
    for ev in res["evaluations"]:
        assert "true_accepts" in ev
        assert "true_rejects" in ev
        assert "false_accepts" in ev
        assert "false_rejects" in ev
        assert "acceptable_samples" in ev
        assert "unacceptable_samples" in ev
        assert ev["true_accepts"] + ev["false_rejects"] == ev["acceptable_samples"]
        assert ev["true_rejects"] + ev["false_accepts"] == ev["unacceptable_samples"]
