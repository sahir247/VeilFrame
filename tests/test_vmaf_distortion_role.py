"""
VeilFrame VMAF Distortion Role & Dataset Segregation Test Suite.
===============================================================
Verifies the architectural segregation of:
  - Representative calibration evidence (primary_calibration)
  - Adversarial policy stress-test evidence (adversarial_only)
  - Diagnostic observations (diagnostic_only)

Tests 10 mandatory properties:
  1. Representative data enters primary calibration.
  2. Adversarial data is excluded from primary calibration.
  3. Adversarial data is available to adversarial analysis.
  4. Diagnostic data is excluded from qualification.
  5. Missing distortion_role fails closed.
  6. Simulated data still cannot enter empirical calibration.
  7. Sequence-group independence remains unchanged.
  8. Fixture labels cannot determine policy labels.
  9. Chroma stress-test samples are classified correctly.
  10. Production qualification cannot be enabled by adversarial-only observations.
"""
import json
import tempfile
from pathlib import Path
import pytest

from tools.vmaf_threshold_analysis import (
    load_corpus_samples,
    assign_independent_policy_label,
    partition_by_sequence_group,
    CorpusSample,
)
from tools.vmaf_domain_qualification import run_domain_qualification


@pytest.fixture
def sample_dataset_json(tmp_path):
    """Creates a temporary corpus results JSON with representative, adversarial, and diagnostic samples."""
    data = {
        "study_id": "TEST-ROLE-CORPUS",
        "simulation_mode": False,
        "clips": [
            {
                "clip_filename": "clip_rep.mp4",
                "sequence_group": "group_rep",
                "domain": "Domain 1: Primary SDR",
                "width": 1920,
                "height": 1080,
                "fps": 30.0,
                "measurement_status": "empirical",
                "is_simulated": False,
                "fixtures": [
                    {
                        "fixture": "REP_PASS_01",
                        "status": "success",
                        "distortion_role": "representative",
                        "calibration_eligibility": "primary_calibration",
                        "exclusion_reason": "",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.980,
                        "psnr_mean": 38.0,
                        "vmaf_mean": 94.0,
                        "vmaf_p5": 91.0,
                        "vmaf_worst": 89.0,
                    },
                    {
                        "fixture": "REP_FAIL_01",
                        "status": "success",
                        "distortion_role": "representative",
                        "calibration_eligibility": "primary_calibration",
                        "exclusion_reason": "",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.920,
                        "psnr_mean": 28.0,
                        "vmaf_mean": 65.0,
                        "vmaf_p5": 58.0,
                        "vmaf_worst": 50.0,
                    },
                    {
                        "fixture": "ADV_STRESS_01",
                        "status": "success",
                        "distortion_role": "adversarial_policy_stress_test",
                        "calibration_eligibility": "adversarial_only",
                        "exclusion_reason": "Constructed chroma shift exposes metric decoupling",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.995,
                        "psnr_mean": 28.5,
                        "vmaf_mean": 97.5,
                        "vmaf_p5": 90.0,
                        "vmaf_worst": 88.0,
                    },
                    {
                        "fixture": "DIAG_SAMPLE_01",
                        "status": "success",
                        "distortion_role": "diagnostic",
                        "calibration_eligibility": "diagnostic_only",
                        "exclusion_reason": "Experimental probe not representative of production",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.960,
                        "psnr_mean": 32.0,
                        "vmaf_mean": 85.0,
                        "vmaf_p5": 80.0,
                        "vmaf_worst": 75.0,
                    },
                ]
            }
        ]
    }
    file_path = tmp_path / "test_corpus_results.json"
    file_path.write_text(json.dumps(data), encoding="utf-8")
    return file_path


# ── Test 1: Representative data enters primary calibration ──────────────── #

def test_representative_data_enters_primary_calibration(sample_dataset_json):
    primary, exclusions, secondary, hdr = load_corpus_samples(
        sample_dataset_json, dataset_mode="representative"
    )
    fixture_names = [s.fixture for s in primary]
    assert "REP_PASS_01" in fixture_names
    assert "REP_FAIL_01" in fixture_names
    for s in primary:
        assert s.distortion_role == "representative"
        assert s.calibration_eligibility == "primary_calibration"


# ── Test 2: Adversarial data is excluded from primary calibration ────────── #

def test_adversarial_data_is_excluded_from_primary_calibration(sample_dataset_json):
    primary, exclusions, secondary, hdr = load_corpus_samples(
        sample_dataset_json, dataset_mode="representative"
    )
    fixture_names = [s.fixture for s in primary]
    assert "ADV_STRESS_01" not in fixture_names
    assert exclusions["adversarial_stress_test_excluded"] == 1


# ── Test 3: Adversarial data is available to adversarial analysis ───────── #

def test_adversarial_data_is_available_to_adversarial_analysis(sample_dataset_json):
    # In adversarial mode, primary_samples contains adversarial observations
    primary, exclusions, secondary, hdr = load_corpus_samples(
        sample_dataset_json, dataset_mode="adversarial"
    )
    fixture_names = [s.fixture for s in primary]
    assert "ADV_STRESS_01" in fixture_names
    assert "REP_PASS_01" not in fixture_names

    # In all mode with return_adversarial=True, adversarial samples are separated
    primary_rep, exclusions, secondary, hdr, adv_samples = load_corpus_samples(
        sample_dataset_json, dataset_mode="all", return_adversarial=True
    )
    adv_names = [s.fixture for s in adv_samples]
    assert "ADV_STRESS_01" in adv_names


# ── Test 4: Diagnostic data is excluded from qualification ───────────────── #

def test_diagnostic_data_is_excluded_from_qualification(sample_dataset_json):
    primary, exclusions, secondary, hdr = load_corpus_samples(
        sample_dataset_json, dataset_mode="representative"
    )
    primary_names = [s.fixture for s in primary]
    assert "DIAG_SAMPLE_01" not in primary_names
    assert exclusions["diagnostic_excluded"] == 1
    # Diagnostic routed to secondary
    sec_names = [s.fixture for s in secondary]
    assert "DIAG_SAMPLE_01" in sec_names


# ── Test 5: Missing distortion_role fails closed ─────────────────────────── #

def test_missing_distortion_role_fails_closed(tmp_path):
    missing_data = {
        "clips": [
            {
                "clip_filename": "clip_missing.mp4",
                "sequence_group": "group_missing",
                "domain": "Domain 1: Primary SDR",
                "width": 1920,
                "height": 1080,
                "fps": 30.0,
                "measurement_status": "empirical",
                "fixtures": [
                    {
                        "fixture": "UNTAGGED_FIXTURE",
                        "status": "success",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.98,
                        "psnr_mean": 38.0,
                        "vmaf_mean": 94.0,
                        # No distortion_role!
                    }
                ]
            }
        ]
    }
    p = tmp_path / "missing_role.json"
    p.write_text(json.dumps(missing_data), encoding="utf-8")

    # In default mode, missing distortion_role is excluded (fails closed)
    primary, exclusions, _, _ = load_corpus_samples(p, dataset_mode="representative")
    assert len(primary) == 0, "Untagged fixture must NOT enter primary calibration"
    assert exclusions["missing_distortion_role"] == 1

    # In strict mode (fail_on_missing_role=True), raises ValueError
    with pytest.raises(ValueError, match="fails closed"):
        load_corpus_samples(p, dataset_mode="representative", fail_on_missing_role=True)


# ── Test 6: Simulated data still cannot enter empirical calibration ─────── #

def test_simulated_data_still_cannot_enter_empirical_calibration(tmp_path):
    sim_data = {
        "clips": [
            {
                "clip_filename": "sim.mp4",
                "sequence_group": "sim_grp",
                "domain": "Domain 1: Primary SDR",
                "width": 1920,
                "height": 1080,
                "fps": 30.0,
                "measurement_status": "simulated",
                "is_simulated": True,
                "fixtures": [
                    {
                        "fixture": "SIM_REP_FIXTURE",
                        "status": "success",
                        "distortion_role": "representative",
                        "calibration_eligibility": "primary_calibration",
                        "measurement_status": "simulated",
                        "is_simulated": True,
                        "ssim_mean": 0.98,
                        "psnr_mean": 38.0,
                        "vmaf_mean": 94.0,
                    }
                ]
            }
        ]
    }
    p = tmp_path / "sim_role.json"
    p.write_text(json.dumps(sim_data), encoding="utf-8")

    primary, exclusions, _, _ = load_corpus_samples(p, allow_simulated=False)
    assert len(primary) == 0
    assert exclusions["simulated_data_rejected"] == 1


# ── Test 7: Sequence-group independence remains unchanged ───────────────── #

def test_sequence_group_independence_remains_unchanged(sample_dataset_json):
    primary, _, _, _ = load_corpus_samples(sample_dataset_json, dataset_mode="all")
    # All fixtures share group_rep
    groups = set(s.sequence_group for s in primary)
    assert len(groups) == 1
    assert "group_rep" in groups

    dev, ho, dev_g, ho_g = partition_by_sequence_group(primary, dev_fraction=1.0)
    assert len(dev_g) == 1
    assert len(dev) == len(primary)


# ── Test 8: Fixture labels cannot determine policy labels ────────────────── #

def test_fixture_labels_cannot_determine_policy_labels():
    # Name contains PASS but metrics fail
    label_fail = assign_independent_policy_label("BOUNDARY_PASS_960", ssim_mean=0.92, psnr_mean=28.0)
    assert label_fail == "unacceptable"

    # Name contains FAIL but metrics pass
    label_pass = assign_independent_policy_label("BOUNDARY_FAIL_940", ssim_mean=0.98, psnr_mean=36.0)
    assert label_pass == "acceptable"


# ── Test 9: Chroma stress-test samples are classified correctly ─────────── #

def test_chroma_stress_test_samples_are_classified_correctly():
    results_path = Path("calibration/data/expanded_corpus_results.json")
    if not results_path.exists():
        pytest.skip("expanded_corpus_results.json not present")

    with open(results_path, "r", encoding="utf-8") as f:
        corpus = json.load(f)

    sintel_clip = next((c for c in corpus["clips"] if c["sequence_group"] == "sintel_trailer"), None)
    assert sintel_clip is not None, "sintel_trailer clip not found in corpus"

    chroma_fixtures = [fx for fx in sintel_clip["fixtures"] if "CHROMA" in fx["fixture"]]
    assert len(chroma_fixtures) >= 6, f"Expected >= 6 chroma fixtures, found {len(chroma_fixtures)}"

    for fx in chroma_fixtures:
        assert fx["distortion_role"] == "adversarial_policy_stress_test", (
            f"Fixture {fx['fixture']} must be tagged adversarial_policy_stress_test"
        )
        assert fx["calibration_eligibility"] == "adversarial_only", (
            f"Fixture {fx['fixture']} must be tagged adversarial_only"
        )
        assert len(fx.get("exclusion_reason", "")) > 0, (
            f"Fixture {fx['fixture']} must have a documented exclusion_reason"
        )


# ── Test 10: Production qualification cannot be enabled by adversarial-only observations ─ #

def test_production_qualification_cannot_be_enabled_by_adversarial_only_observations(tmp_path):
    adv_only_data = {
        "study_id": "TEST-ADV-QUAL",
        "simulation_mode": False,
        "clips": [
            {
                "clip_filename": "sintel_adv.mp4",
                "sequence_group": "sintel_trailer",
                "domain": "Domain 1: Primary SDR",
                "width": 1920,
                "height": 1080,
                "fps": 24.0,
                "measurement_status": "empirical",
                "fixtures": [
                    {
                        "fixture": "PSNR_BND_FAIL_01_CHROMA",
                        "status": "success",
                        "distortion_role": "adversarial_policy_stress_test",
                        "calibration_eligibility": "adversarial_only",
                        "exclusion_reason": "Chroma stress test",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.9954,
                        "psnr_mean": 29.95,
                        "vmaf_mean": 97.52,
                        "vmaf_p5": 90.23,
                        "vmaf_worst": 87.71,
                    },
                    {
                        "fixture": "PSNR_BND_FAIL_02_CHROMA",
                        "status": "success",
                        "distortion_role": "adversarial_policy_stress_test",
                        "calibration_eligibility": "adversarial_only",
                        "exclusion_reason": "Chroma stress test",
                        "measurement_status": "empirical",
                        "ssim_mean": 0.9949,
                        "psnr_mean": 28.79,
                        "vmaf_mean": 97.52,
                        "vmaf_p5": 90.23,
                        "vmaf_worst": 87.71,
                    },
                ]
            }
        ]
    }
    p = tmp_path / "adv_only_results.json"
    p.write_text(json.dumps(adv_only_data), encoding="utf-8")

    qual_report = run_domain_qualification(p)
    # Verify no domain can qualify from adversarial-only data
    for domain_name, d_summary in qual_report.get("domains", {}).items():
        assert d_summary["status"] == "not_qualified", (
            f"Domain {domain_name} cannot be qualified by adversarial-only observations"
        )
    # Verify adversarial findings are recorded in the report
    adv_report = qual_report.get("adversarial_stress_test", {})
    assert adv_report.get("total_samples") == 2
