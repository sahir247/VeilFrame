#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Provenance and Chain-of-Evidence Validation Tests
================================================
Ensures that any dataset submitted as empirical evidence for calibration or qualification
contains an unbroken cryptographic chain of evidence:
  1. Source video identity and SHA-256 hash.
  2. Sequence group attribution (Cardinal Independence Rule).
  3. Distorted video file identity and SHA-256 hash.
  4. Measured SSIM, PSNR, VMAF mean, VMAF P5, and VMAF worst.
  5. VMAF model identity, model version, and model SHA-256.
  6. Rejects any simulated/synthetic placeholder results from empirical qualification.
"""

import json
import pytest
from pathlib import Path


REQUIRED_PROVENANCE_FIELDS = {
    "clip_filename",
    "sequence_group",
    "fixture",
    "ssim_mean",
    "psnr_mean",
    "vmaf_mean",
    "vmaf_p5",
    "vmaf_worst",
    "model_id",
    "evidence_path",
}


def validate_pair_provenance(fixture_data: dict, clip_data: dict) -> tuple[bool, list[str]]:
    """
    Validates that an evaluation pair possesses complete evidentiary provenance.
    Returns (is_valid, missing_or_invalid_reasons).
    """
    reasons = []

    # Check clip-level provenance
    if not clip_data.get("clip_filename"):
        reasons.append("Missing clip_filename")
    if not clip_data.get("sequence_group"):
        reasons.append("Missing sequence_group")

    # Check fixture-level metrics
    st = fixture_data.get("status")
    is_hdr = clip_data.get("is_hdr", False) or (st == "not_applicable_hdr")

    if is_hdr:
        # HDR samples must not have SDR VMAF model computed (fail-closed segregation)
        assert fixture_data.get("vmaf_mean") is None, "HDR sample must not fabricate SDR VMAF"
        return (True, [])

    for field in ["fixture", "ssim_mean", "psnr_mean", "vmaf_mean"]:
        val = fixture_data.get(field)
        if val is None:
            reasons.append(f"Missing required metric/field: '{field}'")

    # Check model provenance
    if not fixture_data.get("model_id"):
        reasons.append("Missing model_id")

    # Check evidence path
    if not fixture_data.get("evidence_path"):
        reasons.append("Missing evidence_path")

    # Check simulated flag
    if clip_data.get("is_simulated") or fixture_data.get("is_simulated"):
        reasons.append("Pair is explicitly marked as simulated")

    return (len(reasons) == 0, reasons)


def test_frozen_baseline_corpus_has_complete_provenance():
    """Frozen baseline v1.0 must possess valid provenance for all measured pairs."""
    results_path = Path("calibration/v1.0/corpus_results.json")
    assert results_path.exists(), "Frozen baseline results file must exist"
    data = json.loads(results_path.read_text(encoding="utf-8"))

    for clip in data.get("clips", []):
        for fx in clip.get("fixtures", []):
            valid, reasons = validate_pair_provenance(fx, clip)
            assert valid, f"Baseline pair {clip.get('clip_filename')} / {fx.get('fixture')} failed: {reasons}"


def test_simulated_dataset_is_flagged_and_rejected_as_empirical_evidence():
    """Verifies that simulated datasets are rejected from counting as real empirical measurements."""
    simulated_path = Path("calibration/data/expanded_corpus_results.json")
    if not simulated_path.exists():
        pytest.skip("Expanded corpus results file does not exist")

    data = json.loads(simulated_path.read_text(encoding="utf-8"))
    if data.get("measurement_status") == "empirical":
        # Dataset has been promoted to real empirical measurement; tested by test_empirical_expanded_dataset_has_real_provenance
        return

    # Must declare boundary targeting / simulation
    is_simulated = data.get("simulation_mode", False) or data.get("boundary_targeting", False)
    assert is_simulated, "Dataset must declare its generation nature"

    # Any simulated dataset must NOT be certified as empirical evidence
    assert data.get("study_id") != "VF-CAL-VMAF-2026-09", (
        "Simulated dataset must not use frozen baseline empirical study ID"
    )


def test_empirical_expanded_dataset_has_real_provenance():
    """When expanded_corpus_results.json is generated empirically, verify all empirical fields."""
    exp_path = Path("calibration/data/expanded_corpus_results.json")
    if not exp_path.exists():
        pytest.skip("Expanded corpus results file does not exist")
    data = json.loads(exp_path.read_text(encoding="utf-8"))
    if data.get("measurement_status") != "empirical":
        pytest.skip("Expanded corpus results file is not yet empirical")

    assert data.get("measurement_status") == "empirical"
    assert not data.get("simulation_mode")
    assert data.get("ffmpeg_version")
    for clip in data.get("clips", []):
        assert clip.get("measurement_status") == "empirical"
        assert clip.get("clip_sha256")
        for fx in clip.get("fixtures", []):
            assert fx.get("measurement_status") == "empirical"
            assert fx.get("evidence_sha256")
            assert fx.get("distorted_sha256")
            assert fx.get("ssim_mean") is not None
            assert fx.get("psnr_mean") is not None
            assert fx.get("vmaf_mean") is not None
            assert fx.get("vmaf_p5") is not None
            assert fx.get("vmaf_worst") is not None
            assert fx.get("model_id") == "vmaf_v1.0.16_3d0h"


def test_empirical_qualification_requires_full_provenance_chain():
    """
    Every empirical evaluation pair submitted for qualification must provide
    the complete 13-point provenance chain:
      1. Source video
      2. Source SHA-256
      3. Distortion name
      4. Distorted file / evidence SHA-256
      5. Measured SSIM
      6. Measured PSNR
      7. Measured VMAF mean
      8. Measured VMAF P5
      9. Measured VMAF worst
      10. VMAF model ID
      11. VMAF model SHA-256
      12. FFmpeg / tool version
      13. Sequence group
    """
    # Test on a dataset that claims empirical validity
    baseline_path = Path("calibration/v1.0/corpus_results.json")
    data = json.loads(baseline_path.read_text(encoding="utf-8"))

    assert data.get("ffmpeg_version"), "FFmpeg/tool version must be recorded"
    assert data.get("vmaf_model_version"), "VMAF model version must be recorded"

    for clip in data.get("clips", []):
        if clip.get("is_hdr"):
            continue
        assert clip.get("clip_filename"), "1. Source video must be specified"
        assert clip.get("clip_sha256"), "2. Source SHA-256 must be recorded"
        assert clip.get("sequence_group"), "13. Sequence group must be specified"

        for fx in clip.get("fixtures", []):
            assert fx.get("fixture"), "3. Distortion name must be specified"
            assert fx.get("evidence_sha256"), "4. Evidence/distorted SHA-256 must be recorded"
            assert fx.get("ssim_mean") is not None, "5. Exact measured SSIM must be recorded"
            assert fx.get("psnr_mean") is not None, "6. Exact measured PSNR must be recorded"
            assert fx.get("vmaf_mean") is not None, "7. Exact measured VMAF mean must be recorded"
            assert fx.get("vmaf_p5") is not None, "8. Exact measured VMAF P5 must be recorded"
            assert fx.get("vmaf_worst") is not None, "9. Exact measured VMAF worst must be recorded"
            assert fx.get("model_id"), "10. VMAF model ID must be recorded"
            assert fx.get("model_sha256"), "11. VMAF model SHA-256 must be recorded"


def test_simulated_result_cannot_enter_empirical_qualification(tmp_path):
    """Proves that a simulated result cannot enter the empirical qualification dataset."""
    from tools.vmaf_threshold_analysis import load_corpus_samples

    dummy_simulated = {
        "study_id": "VF-CAL-SIM-TEST",
        "simulation_mode": True,
        "clips": [
            {
                "clip_filename": "sim_sample.mp4",
                "sequence_group": "sim_group",
                "domain": "Domain 1: Primary SDR",
                "measurement_status": "simulated",
                "is_simulated": True,
                "fixtures": [
                    {
                        "fixture": "BOUNDARY_PASS_SSIM_960",
                        "status": "success",
                        "measurement_status": "simulated",
                        "is_simulated": True,
                        "ssim_mean": 0.960,
                        "psnr_mean": 33.0,
                        "vmaf_mean": 92.5,
                        "vmaf_p5": 89.0,
                        "vmaf_worst": 87.0,
                    }
                ]
            }
        ]
    }
    dummy_file = tmp_path / "simulated_results.json"
    dummy_file.write_text(json.dumps(dummy_simulated), encoding="utf-8")

    # In default empirical mode, simulated records MUST be rejected
    primary, exclusions, secondary, hdr = load_corpus_samples(dummy_file, allow_simulated=False)
    assert len(primary) == 0, "Simulated samples must NOT enter empirical primary dataset"
    assert exclusions["simulated_data_rejected"] == 1, "Simulated sample must be accounted in simulated_data_rejected"

    # When allow_simulated=True is explicitly passed for research simulation, it is loaded
    primary_sim, _, _, _ = load_corpus_samples(dummy_file, allow_simulated=True)
    assert len(primary_sim) == 1, "Explicit simulation mode should permit loading for research"
    assert primary_sim[0].measurement_status == "simulated"


