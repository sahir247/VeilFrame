#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tests for clustered bootstrap uncertainty estimation and clustered ROC computation
in tools/vmaf_threshold_analysis.py.
"""

import pytest
from tools.vmaf_threshold_analysis import (
    CorpusSample,
    compute_clustered_bootstrap_ci,
    compute_clustered_roc,
    evaluate_policy_operating_point,
)


def _make_mock_samples():
    samples = []
    # Group 1: Pristine, all pass
    for i in range(4):
        samples.append(
            CorpusSample(
                clip_filename="c1.mp4",
                sequence_group="group_1",
                vmaf_mean=98.0,
                vmaf_p5=96.0,
                vmaf_worst=94.0,
                ssim_mean=0.98,
                psnr_mean=42.0,
                independent_policy_label="acceptable",
            )
        )
    # Group 2: Boundary pass
    for i in range(4):
        samples.append(
            CorpusSample(
                clip_filename="c2.mp4",
                sequence_group="group_2",
                vmaf_mean=92.0,
                vmaf_p5=90.5,
                vmaf_worst=89.0,
                ssim_mean=0.955,
                psnr_mean=31.0,
                independent_policy_label="acceptable",
            )
        )
    # Group 3: Boundary fail (high VMAF false accept risk)
    for i in range(4):
        samples.append(
            CorpusSample(
                clip_filename="c3.mp4",
                sequence_group="group_3",
                vmaf_mean=91.0,
                vmaf_p5=90.0,
                vmaf_worst=88.5,
                ssim_mean=0.940,
                psnr_mean=29.0,
                independent_policy_label="unacceptable",
            )
        )
    # Group 4: Severe fail
    for i in range(4):
        samples.append(
            CorpusSample(
                clip_filename="c4.mp4",
                sequence_group="group_4",
                vmaf_mean=75.0,
                vmaf_p5=70.0,
                vmaf_worst=65.0,
                ssim_mean=0.88,
                psnr_mean=24.0,
                independent_policy_label="unacceptable",
            )
        )
    return samples


def test_clustered_bootstrap_resamples_by_group():
    samples = _make_mock_samples()
    boot = compute_clustered_bootstrap_ci(
        samples,
        threshold=90.0,
        policy_name="combined",
        n_bootstraps=500,
        confidence_level=0.95,
        seed=42,
    )

    assert boot["n_clusters"] == 4
    assert boot["n_bootstraps"] == 500
    assert 0.0 <= boot["far_ci"][0] <= boot["far_ci"][1] <= 1.0
    assert 0.0 <= boot["frr_ci"][0] <= boot["frr_ci"][1] <= 1.0
    assert boot["far_std_err"] >= 0.0
    assert boot["frr_std_err"] >= 0.0


def test_clustered_roc_auc_validity():
    samples = _make_mock_samples()
    roc = compute_clustered_roc(
        samples,
        policy_name="combined",
        domain_start=70.0,
        domain_stop=100.0,
    )

    assert "auc" in roc
    assert 0.0 <= roc["auc"] <= 1.0
    assert len(roc["roc_curve"]) > 0
    # Every point has valid fpr and tpr
    for pt in roc["roc_curve"]:
        assert 0.0 <= pt["fpr"] <= 1.0
        assert 0.0 <= pt["tpr"] <= 1.0
