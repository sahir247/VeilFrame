"""
Comprehensive Production Unit & Adversarial Tests — VMAF Policy Engine.

Verifies the 12-step production architecture:
  1. Separation of concerns: provider measures, policy resolves and decides.
  2. Policy modes: disabled, audit, validated_model, validated_global.
  3. Production v1 default: audit mode (measurement on, rejection off).
  4. Authoritative gate: SSIM/PSNR authority (VMAF cannot rescue failing SSIM/PSNR).
  5. Fail-closed invariants: missing required P5 fails closed in validated modes.
  6. Zero metric substitution: missing P5 is None, never substituted by mean.
  7. Unqualified domain fallback: all 4 current domains fall back to audit mode.
  8. Full cryptographic provenance: emitted in verdict and signed manifest.
"""
import json
import tempfile
import unittest
from pathlib import Path
from typing import List, Optional

from veilframe.models.settings import VisualBudgetPolicy
from veilframe.models.video_info import (
    NativeDomainMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
    ThreeTierQualityVerdict,
    QualityMetricStats,
)
from veilframe.quality.models import QualityResult
from veilframe.quality.gate import QualityGate
from veilframe.quality.vmaf_models import classify_resolution, select_vmaf_model, VmafUnsupportedResolutionError
from veilframe.quality.vmaf_policy import (
    classify_technical_domain,
    resolve_vmaf_policy,
    register_qualified_domain,
    OFFICIAL_DOMAIN_POLICIES,
    VmafPolicyProfile,
    VMAF_AUDIT_POLICY_ID,
    VMAF_CALIBRATION_STUDY_ID,
)


def _make_mock_qualification_artifact(
    tmp_path: Path,
    domain: str = "1080p_sdr",
    status: str = "validated",
    rec_mean: float = 92.0,
    rec_p5: float = 90.0,
    study_id: str = "VF-CAL-VMAF-MOCK-2026-09",
    dataset_version: str = "1.2.0",
    analysis_version: str = "1.2.0",
) -> Path:
    artifact_path = tmp_path / "mock_domain_qualification.json"
    data = {
        "study_id": study_id,
        "dataset_version": dataset_version,
        "analysis_version": analysis_version,
        "domains": {
            domain: {
                "domain": domain,
                "status": status,
                "recommended_mean_min": rec_mean,
                "recommended_p5_min": rec_p5,
                "reason": "Empirically qualified in mock test suite",
            }
        },
    }
    artifact_path.write_text(json.dumps(data), encoding="utf-8")
    return artifact_path


def _clean_results(
    vmaf_mean: Optional[float] = 95.0,
    vmaf_p5: Optional[float] = 92.0,
    include_vmaf: bool = True,
    model_name: str = "vmaf_v1.0.16_3d0h",
    model_sha256: str = "cdb62c255f17a17b6dc2b97fba5429c4b303aa5523a8b0d0316d8a112cfd893f",
) -> List[QualityResult]:
    """Provides passing SSIM/PSNR and optional VMAF results."""
    res = [
        QualityResult(provider_name="ffmpeg-native", metric_name="ssim", mean=0.985, minimum=0.960, p1=0.965, p5=0.970, p95=0.990),
        QualityResult(provider_name="ffmpeg-native", metric_name="psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
    ]
    if include_vmaf and vmaf_mean is not None:
        res.append(
            QualityResult(
                provider_name="libvmaf",
                metric_name="vmaf",
                mean=vmaf_mean,
                minimum=vmaf_p5 - 5.0 if vmaf_p5 is not None else None,
                p1=vmaf_p5 - 2.0 if vmaf_p5 is not None else None,
                p5=vmaf_p5,
                p95=vmaf_mean + 2.0,
                model_name=model_name,
                model_sha256=model_sha256,
                evidence_sha256="deadbeef12345678",
            )
        )
    return res


def _failing_ssim_results(vmaf_mean: float = 99.0) -> List[QualityResult]:
    """Failing SSIM but perfect VMAF score."""
    return [
        QualityResult(provider_name="ffmpeg-native", metric_name="ssim", mean=0.820, minimum=0.750, p1=0.760, p5=0.780, p95=0.850),
        QualityResult(provider_name="ffmpeg-native", metric_name="psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
        QualityResult(provider_name="libvmaf", metric_name="vmaf", mean=vmaf_mean, p5=vmaf_mean - 1.0, minimum=vmaf_mean - 2.0),
    ]


def _zero_policy_score() -> TransformationPolicyScore:
    return TransformationPolicyScore(passed=True, violations=[])


def _zero_temporal() -> TemporalIntegrityMetrics:
    return TemporalIntegrityMetrics(violations=[])


def _native_metrics_1080p() -> NativeDomainMetrics:
    return NativeDomainMetrics(
        resolution_ref="1920x1080",
        resolution_trans="1920x1080",
        fps_ref=29.97,
        fps_trans=29.97,
    )


class TestVmafPolicyResolutionAndDomains(unittest.TestCase):
    """Tests technical domain classification and policy resolution."""

    def test_explicit_domain_classification(self):
        # 1080p SDR
        self.assertEqual(classify_technical_domain(1920, 1080, 29.97), "1080p_sdr")
        self.assertEqual(classify_technical_domain(1080, 1920, 25.0), "1080p_sdr")
        self.assertEqual(classify_technical_domain(1808, 1080, 30.0), "1080p_sdr")

        # 1080p HFR
        self.assertEqual(classify_technical_domain(1920, 1080, 60.0), "1080p_hfr")
        self.assertEqual(classify_technical_domain(1808, 1080, 50.0), "1080p_hfr")

        # 2160p SDR & HFR
        self.assertEqual(classify_technical_domain(3840, 2160, 25.0), "2160p_sdr")
        self.assertEqual(classify_technical_domain(3840, 2160, 50.0), "2160p_hfr")
        self.assertEqual(classify_technical_domain(4096, 2160, 59.94), "2160p_hfr")

        # Secondary (720p / SD)
        self.assertEqual(classify_technical_domain(1280, 720, 60.0), "secondary")
        self.assertEqual(classify_technical_domain(640, 480, 30.0), "secondary")

        # Unsupported (1440p)
        self.assertEqual(classify_technical_domain(2560, 1440, 60.0), "unsupported")

        # HDR
        self.assertEqual(classify_technical_domain(3840, 2160, 60.0, is_hdr=True), "hdr")

    def test_all_four_initial_domains_are_not_qualified(self):
        for domain in ("1080p_sdr", "1080p_hfr", "2160p_sdr", "2160p_hfr"):
            profile = resolve_vmaf_policy(domain, gate_mode="validated_model")
            self.assertEqual(profile.status, "not_qualified")
            self.assertIsNone(profile.mean_min)
            self.assertIsNone(profile.p5_min)


class TestVmafPolicyModesInQualityGate(unittest.TestCase):
    """Tests QualityGate behaviour across disabled, audit, validated_model, validated_global modes."""

    def test_production_default_is_audit_mode_and_gate_disabled(self):
        policy = VisualBudgetPolicy()
        self.assertEqual(policy.vmaf_gate_mode, "audit")
        self.assertFalse(policy.vmaf_gate_enabled)

    def test_audit_mode_low_score_never_rejects_output(self):
        """In audit mode, catastrophic VMAF scores are recorded as audit evidence but do NOT reject."""
        policy = VisualBudgetPolicy(vmaf_gate_mode="audit")
        results = _clean_results(vmaf_mean=42.0, vmaf_p5=30.0)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(len(verdict.tier2_violations), 0)
        self.assertIsNotNone(verdict.vmaf_verdict)
        self.assertEqual(verdict.vmaf_verdict["status"], "audit_only")
        self.assertEqual(verdict.vmaf_verdict["decision"], "AUDIT")
        self.assertEqual(verdict.vmaf_verdict["mean"], 42.0)

    def test_audit_mode_missing_p5_flags_audit_incomplete_without_rejection(self):
        """When P5 is missing in audit mode, verdict records AUDIT_INCOMPLETE but passes output."""
        policy = VisualBudgetPolicy(vmaf_gate_mode="audit")
        results = _clean_results(vmaf_mean=95.0, vmaf_p5=None)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(verdict.vmaf_verdict["decision"], "AUDIT_INCOMPLETE")
        self.assertIsNone(verdict.vmaf_verdict["p5"])

    def test_audit_mode_missing_vmaf_provider_does_not_reject(self):
        """In audit mode, absence of VMAF provider records unavailable but does not fail output."""
        policy = VisualBudgetPolicy(vmaf_gate_mode="audit")
        results = _clean_results(include_vmaf=False)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(verdict.vmaf_verdict["status"], "unavailable")
        self.assertEqual(verdict.vmaf_verdict["decision"], "AUDIT_INCOMPLETE")

    def test_disabled_mode_bypasses_vmaf(self):
        policy = VisualBudgetPolicy(vmaf_gate_mode="disabled")
        results = _clean_results(vmaf_mean=10.0, vmaf_p5=5.0)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.vmaf_verdict["status"], "disabled")
        self.assertEqual(verdict.vmaf_verdict["decision"], "DISABLED")

    def test_validated_model_mode_unqualified_domain_falls_back_to_audit(self):
        """If validated_model mode is requested for an unpromoted domain, it falls back to audit."""
        policy = VisualBudgetPolicy(vmaf_gate_mode="validated_model")
        results = _clean_results(vmaf_mean=70.0, vmaf_p5=65.0)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(verdict.vmaf_verdict["status"], "not_qualified")
        self.assertEqual(verdict.vmaf_verdict["decision"], "AUDIT_FALLBACK")
        self.assertIn("not qualified", verdict.vmaf_verdict["note"])

    def test_ssim_psnr_safety_authority_cannot_be_rescued_by_vmaf(self):
        """Adversarial check: Perfect VMAF (99.0) CANNOT rescue failing SSIM."""
        for mode in ("audit", "validated_model", "validated_global", "disabled"):
            policy = VisualBudgetPolicy(vmaf_gate_mode=mode)
            results = _failing_ssim_results(vmaf_mean=99.0)
            gate = QualityGate(policy)

            verdict = gate.evaluate(
                results=results,
                native_metrics=_native_metrics_1080p(),
                temporal_metrics=_zero_temporal(),
                policy_score=_zero_policy_score(),
            )

            self.assertFalse(verdict.all_passed, f"Mode {mode} allowed failing SSIM to pass!")
            self.assertEqual(verdict.overall_verdict, "REJECT")
            self.assertFalse(verdict.tier2_fidelity_passed)
            self.assertTrue(any("Mean SSIM" in v for v in verdict.tier2_violations))

    def test_dynamically_promoted_domain_enforces_calibrated_thresholds(self):
        """Verifies that when a domain is legitimately promoted via artifact, its thresholds are strictly enforced."""
        domain = "1080p_sdr"
        original_profile = OFFICIAL_DOMAIN_POLICIES[domain]

        with tempfile.TemporaryDirectory() as tmp_dir:
            mock_art = _make_mock_qualification_artifact(Path(tmp_dir), domain=domain, rec_mean=92.0, rec_p5=90.0)
            try:
                # Promote domain dynamically via valid qualification artifact
                register_qualified_domain(
                    domain=domain,
                    policy_id="vmaf-1080p-sdr-promoted-test",
                    policy_version="1.0.0",
                    mean_min=92.0,
                    p5_min=90.0,
                    worst_min=85.0,
                    qualification_report_path=mock_art,
                )

                policy = VisualBudgetPolicy(vmaf_gate_mode="validated_model")
                gate = QualityGate(policy)

                # 1. Passing results (mean=95.0, p5=92.0)
                passing = _clean_results(vmaf_mean=95.0, vmaf_p5=92.0)
                v_pass = gate.evaluate(passing, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertTrue(v_pass.all_passed)
                self.assertEqual(v_pass.vmaf_verdict["status"], "validated")
                self.assertEqual(v_pass.vmaf_verdict["decision"], "PASS")

                # 2. Failing mean (mean=90.0 < 92.0)
                failing_mean = _clean_results(vmaf_mean=90.0, vmaf_p5=90.0)
                v_fail_mean = gate.evaluate(failing_mean, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertFalse(v_fail_mean.all_passed)
                self.assertEqual(v_fail_mean.vmaf_verdict["decision"], "REJECT")
                self.assertTrue(any("Mean VMAF" in v for v in v_fail_mean.tier2_violations))

                # 3. Failing P5 tail (mean=95.0 >= 92.0, but p5=88.0 < 90.0)
                failing_p5 = _clean_results(vmaf_mean=95.0, vmaf_p5=88.0)
                v_fail_p5 = gate.evaluate(failing_p5, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertFalse(v_fail_p5.all_passed)
                self.assertEqual(v_fail_p5.vmaf_verdict["decision"], "REJECT")
                self.assertTrue(any("P5 VMAF" in v for v in v_fail_p5.tier2_violations))

                # 4. Missing P5 in validated mode fails closed
                missing_p5 = _clean_results(vmaf_mean=95.0, vmaf_p5=None)
                v_miss = gate.evaluate(missing_p5, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertFalse(v_miss.all_passed)
                self.assertTrue(any("VMAF P5 percentile unavailable" in v for v in v_miss.tier2_violations))

            finally:
                # Restore original unpromoted state
                OFFICIAL_DOMAIN_POLICIES[domain] = original_profile

    def test_verdict_carries_full_calibration_provenance(self):
        policy = VisualBudgetPolicy(vmaf_gate_mode="audit")
        results = _clean_results(vmaf_mean=96.0, vmaf_p5=93.0)
        gate = QualityGate(policy)

        verdict = gate.evaluate(
            results=results,
            native_metrics=_native_metrics_1080p(),
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )

        self.assertIsNotNone(verdict.policy_provenance)
        self.assertEqual(verdict.policy_provenance["study_id"], VMAF_CALIBRATION_STUDY_ID)
        self.assertEqual(verdict.policy_provenance["policy_version"], "1.0.0")
        self.assertEqual(verdict.policy_provenance["dataset_version"], "1.2.0")

    def test_worst_min_threshold_validation_and_enforcement(self):
        # 1. QualityGate validation: worst_min cannot exceed p5_min or mean_min
        with self.assertRaises(ValueError):
            QualityGate(VisualBudgetPolicy(
                vmaf_gate_mode="validated_global",
                vmaf_mean_min=90.0,
                vmaf_p5_min=80.0,
                vmaf_worst_min=85.0,  # 85 > 80 (p5_min) -> invalid
            ))

        with self.assertRaises(ValueError):
            QualityGate(VisualBudgetPolicy(
                vmaf_gate_mode="validated_global",
                vmaf_mean_min=80.0,
                vmaf_p5_min=80.0,
                vmaf_worst_min=90.0,  # 90 > 80 (mean_min) -> invalid
            ))

        with self.assertRaises(ValueError):
            QualityGate(VisualBudgetPolicy(
                vmaf_gate_mode="validated_global",
                vmaf_worst_min=-5.0,  # < 0 -> invalid
            ))

        # 2. register_qualified_domain validation: domain must be supported
        with self.assertRaises(ValueError):
            register_qualified_domain("test_domain", "p-test", "1.0", mean_min=90.0, p5_min=85.0, worst_min=88.0)

        # 3. Enforcement when properly registered and worst-frame score fails
        domain = "1080p_sdr"
        orig = OFFICIAL_DOMAIN_POLICIES[domain]
        with tempfile.TemporaryDirectory() as tmp_dir:
            mock_art = _make_mock_qualification_artifact(Path(tmp_dir), domain=domain, rec_mean=90.0, rec_p5=85.0)
            try:
                register_qualified_domain(domain, "p-worst", "1.0", mean_min=90.0, p5_min=85.0, worst_min=80.0, qualification_report_path=mock_art)
                gate = QualityGate(VisualBudgetPolicy(vmaf_gate_mode="validated_model"))
                # Passing mean (92) and p5 (86), but failing worst frame (75 < 80)
                res = _clean_results(vmaf_mean=92.0, vmaf_p5=86.0)
                res[2].minimum = 75.0
                verdict = gate.evaluate(res, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertFalse(verdict.all_passed)
                self.assertEqual(verdict.vmaf_verdict["decision"], "REJECT")
                self.assertTrue(any("Worst-Frame VMAF" in v for v in verdict.tier2_violations))
            finally:
                OFFICIAL_DOMAIN_POLICIES[domain] = orig

    def test_registration_blocks_unqualified_domains_from_real_baseline(self):
        """All 4 domains in the frozen v1.0 baseline are not_qualified; attempting to promote any must fail."""
        for domain in ("1080p_sdr", "1080p_hfr", "2160p_sdr", "2160p_hfr"):
            with self.assertRaises(ValueError) as cm:
                register_qualified_domain(
                    domain=domain,
                    policy_id="attempted-promotion",
                    policy_version="1.0.0",
                    mean_min=90.0,
                    p5_min=85.0,
                )
            self.assertIn("status is 'not_qualified'", str(cm.exception))

    def test_registration_rejects_missing_artifact(self):
        with self.assertRaises(FileNotFoundError):
            register_qualified_domain(
                domain="1080p_sdr",
                policy_id="test",
                policy_version="1.0.0",
                qualification_report_path=Path("nonexistent_qualification_report.json"),
            )

    def test_registration_binds_model_and_artifact_sha256(self):
        domain = "1080p_sdr"
        orig = OFFICIAL_DOMAIN_POLICIES[domain]
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_p = Path(tmp_dir)
            mock_art = _make_mock_qualification_artifact(tmp_p, domain=domain, rec_mean=92.0, rec_p5=90.0)
            try:
                profile = register_qualified_domain(
                    domain=domain,
                    policy_id="vmaf-bound-test",
                    policy_version="1.0.0",
                    qualification_report_path=mock_art,
                )
                self.assertEqual(profile.status, "validated")
                self.assertEqual(profile.mean_min, 92.0)
                self.assertEqual(profile.p5_min, 90.0)
                self.assertEqual(profile.model_id, "vmaf_v1.0.16_3d0h")
                self.assertEqual(profile.model_sha256, "cdb62c255f17a17b6dc2b97fba5429c4b303aa5523a8b0d0316d8a112cfd893f")
                self.assertIsNotNone(profile.qualification_artifact_sha256)
            finally:
                OFFICIAL_DOMAIN_POLICIES[domain] = orig

    def test_validated_mode_rejects_tampered_model_sha256(self):
        """In validated mode, evidence with a mismatched model SHA-256 fails closed."""
        domain = "1080p_sdr"
        orig = OFFICIAL_DOMAIN_POLICIES[domain]
        with tempfile.TemporaryDirectory() as tmp_dir:
            mock_art = _make_mock_qualification_artifact(Path(tmp_dir), domain=domain, rec_mean=92.0, rec_p5=90.0)
            try:
                register_qualified_domain(
                    domain=domain,
                    policy_id="vmaf-model-binding-test",
                    policy_version="1.0.0",
                    qualification_report_path=mock_art,
                )
                gate = QualityGate(VisualBudgetPolicy(vmaf_gate_mode="validated_model"))
                tampered_results = _clean_results(
                    vmaf_mean=95.0,
                    vmaf_p5=92.0,
                    model_sha256="deadbeefbadf00d0000000000000000000000000000000000000000000000000",
                )
                verdict = gate.evaluate(tampered_results, _native_metrics_1080p(), _zero_temporal(), _zero_policy_score())
                self.assertFalse(verdict.all_passed)
                self.assertEqual(verdict.vmaf_verdict["decision"], "REJECT")
                self.assertTrue(any("does not match qualified policy binding" in v for v in verdict.tier2_violations))
            finally:
                OFFICIAL_DOMAIN_POLICIES[domain] = orig

    def test_hdr_propagation_in_quality_gate(self):
        """Issue 3: Gate consumes actual HDR status from native metrics and segregates HDR content."""
        policy = VisualBudgetPolicy(vmaf_gate_mode="audit")
        gate = QualityGate(policy)

        # 1. Native domain metrics with explicit is_hdr=True
        hdr_metrics = NativeDomainMetrics(
            resolution_ref="3840x2160",
            resolution_trans="3840x2160",
            fps_ref=60.0,
            fps_trans=60.0,
            is_hdr=True,
            hdr_reason="HDR transfer characteristic: smpte2084",
        )
        verdict = gate.evaluate(
            results=_clean_results(vmaf_mean=95.0, vmaf_p5=92.0),
            native_metrics=hdr_metrics,
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )
        self.assertTrue(verdict.all_passed)
        self.assertEqual(verdict.vmaf_verdict["status"], "not_applicable")
        self.assertEqual(verdict.vmaf_verdict["decision"], "NOT_APPLICABLE_HDR")
        self.assertEqual(verdict.vmaf_verdict["domain"], "hdr")

        # 2. Native domain metrics with colorspace cues (BT.2020 / SMPTE 2084)
        cue_metrics = NativeDomainMetrics(
            resolution_ref="1920x1080",
            resolution_trans="1920x1080",
            fps_ref=29.97,
            fps_trans=29.97,
            colorspace_ref="bt2020nc",
            is_hdr=False,
        )
        verdict_cue = gate.evaluate(
            results=_clean_results(vmaf_mean=95.0, vmaf_p5=92.0),
            native_metrics=cue_metrics,
            temporal_metrics=_zero_temporal(),
            policy_score=_zero_policy_score(),
        )
        self.assertEqual(verdict_cue.vmaf_verdict["status"], "not_applicable")
        self.assertEqual(verdict_cue.vmaf_verdict["decision"], "NOT_APPLICABLE_HDR")
        self.assertEqual(verdict_cue.vmaf_verdict["domain"], "hdr")


if __name__ == "__main__":
    unittest.main()

