#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tests for multi-policy evaluation (mean, p5, combined, worst) in
tools/vmaf_threshold_analysis.py.
"""

import pytest
from tools.vmaf_threshold_analysis import (
    CorpusSample,
    evaluate_multi_policy_comparison,
    evaluate_policy_operating_point,
)


def test_combined_policy_is_more_restrictive_than_mean():
    samples = [
        # Sample with high mean but low p5 (temporal dip / flicker)
        CorpusSample(
            clip_filename="dip.mp4",
            sequence_group="group_dip",
            vmaf_mean=95.0,
            vmaf_p5=86.0,
            vmaf_worst=80.0,
            ssim_mean=0.96,
            psnr_mean=35.0,
            independent_policy_label="acceptable",
        ),
        # Uniform sample
        CorpusSample(
            clip_filename="uniform.mp4",
            sequence_group="group_uni",
            vmaf_mean=96.0,
            vmaf_p5=95.0,
            vmaf_worst=93.0,
            ssim_mean=0.97,
            psnr_mean=38.0,
            independent_policy_label="acceptable",
        ),
    ]

    policies = evaluate_multi_policy_comparison(samples, threshold=90.0)

    # Under threshold 90.0:
    # 'mean' accepts both (95.0 >= 90.0, 96.0 >= 90.0) -> 2 true accepts
    assert policies["mean"].true_accepts == 2
    # 'combined' accepts only the uniform sample (dip has p5=86.0 < 90.0) -> 1 true accept
    assert policies["combined"].true_accepts == 1
    assert policies["combined"].false_rejects == 1


def test_missing_p5_fails_closed_under_combined_policy():
    samples = [
        CorpusSample(
            clip_filename="missing_p5.mp4",
            sequence_group="group_missing",
            vmaf_mean=98.0,
            vmaf_p5=None,  # Missing P5
            vmaf_worst=None,
            ssim_mean=0.99,
            psnr_mean=45.0,
            independent_policy_label="acceptable",
        ),
    ]

    m_mean = evaluate_policy_operating_point(samples, threshold=90.0, policy_name="mean")
    assert m_mean.true_accepts == 1

    m_comb = evaluate_policy_operating_point(samples, threshold=90.0, policy_name="combined")
    # Must fail closed when P5 is missing
    assert m_comb.true_accepts == 0
    assert m_comb.false_rejects == 1
