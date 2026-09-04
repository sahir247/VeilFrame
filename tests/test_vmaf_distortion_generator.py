#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tests for tools/vmaf_distortion_generator.py
Validates closed-loop boundary targeting, non-circular policy labeling,
and Cardinal Sequence Independence Rule preservation.
"""

import json
import pytest
from pathlib import Path

from tools.vmaf_distortion_generator import (
    DEFAULT_TARGETS,
    DistortionTarget,
    simulate_distortion_metrics,
    generate_boundary_dataset,
)


def test_default_targets_cover_boundary_bands():
    targets = DEFAULT_TARGETS
    assert len(targets) >= 10, "Should have dense targets"

    # Verify presence of boundary targets
    boundary_targets = [t for t in targets if t.category in ("near_boundary_pass", "near_boundary_fail")]
    assert len(boundary_targets) >= 5, "Must have multiple boundary transition targets"

    ssim_vals = [t.target_ssim for t in targets if t.target_ssim is not None]
    assert any(0.940 <= s <= 0.960 for s in ssim_vals), "Must target SSIM near 0.9500"


def test_independent_policy_labeling_rule(tmp_path):
    out_file = tmp_path / "test_results.json"
    ref_seqs = [
        {
            "sequence_group_id": "test_group_alpha",
            "filename": "alpha.mp4",
            "category": "people_faces",
            "width": 1920,
            "height": 1080,
            "fps": 30.0,
            "domain_target": "1080p_sdr",
        }
    ]

    res = generate_boundary_dataset(
        reference_sequences=ref_seqs,
        output_results_path=out_file,
        simulate=True,
    )

    assert out_file.exists()
    with open(out_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    assert len(data["clips"]) == 1
    clip = data["clips"][0]
    assert clip["sequence_group"] == "test_group_alpha"

    # Verify every fixture's label follows SSIM >= 0.9500 and PSNR >= 30.00
    for fx in clip["fixtures"]:
        ssim = fx["ssim_mean"]
        psnr = fx["psnr_mean"]
        label = fx["independent_policy_label"]

        if ssim >= 0.9500 and psnr >= 30.00:
            assert label == "acceptable", f"Sample SSIM={ssim}, PSNR={psnr} should be acceptable"
        else:
            assert label == "unacceptable", f"Sample SSIM={ssim}, PSNR={psnr} should be unacceptable"


def test_cardinal_independence_preserved_across_fixtures(tmp_path):
    out_file = tmp_path / "test_independence.json"
    ref_seqs = [
        {"sequence_group_id": "group_one", "filename": "g1.mp4"},
        {"sequence_group_id": "group_two", "filename": "g2.mp4"},
    ]

    res = generate_boundary_dataset(
        reference_sequences=ref_seqs,
        output_results_path=out_file,
        simulate=True,
    )

    with open(out_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    for clip in data["clips"]:
        expected_grp = clip["sequence_group"]
        # All fixtures must inherit the clip's sequence_group
        for fx in clip["fixtures"]:
            assert "sequence_group" not in fx or fx["sequence_group"] == expected_grp
