"""
VeilFrame Quality Architecture Contract & Regression Tests
==========================================================
Verifies the production quality architecture:
1. Authoritative production policy: SSIM >= 0.9500 and PSNR >= 30.0 dB.
2. Complete absence of VMAF from runtime imports, models, and manifests.
3. Fail-closed semantics for missing or invalid required quality measurements.
4. Clean-environment dependency independence (evaluates successfully with zero libvmaf presence).
"""

import importlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from veilframe.core.resources import get_ffmpeg_path
from veilframe.models.settings import VisualBudgetPolicy
from veilframe.models.video_info import (
    NativeDomainMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
    QualityMetricStats,
)
from veilframe.quality.gate import QualityGate
from veilframe.quality.models import QualityResult, QualityConfig
from veilframe.quality.adapters.ffmpeg import FFmpegNativeProvider


class TestQualityArchitecture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = get_ffmpeg_path()
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="qa_arch_test_"))
        cls.ref_video = cls.temp_dir / "ref.mp4"
        cmd = [
            str(cls.ffmpeg), "-hide_banner", "-nostats", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=1.0:size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1.0",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
            "-pix_fmt", "yuv420p", "-c:a", "aac", str(cls.ref_video),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise RuntimeError(f"Failed to create test fixture: {proc.stderr}")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.temp_dir, ignore_errors=True)

    def _make_dummy_inputs(self, ssim_mean=0.96, psnr_mean=35.0):
        results = [
            QualityResult(
                provider_name="ffmpeg-native",
                metric_name="ssim",
                mean=ssim_mean,
                minimum=ssim_mean - 0.02,
                p1=ssim_mean - 0.015,
                p5=ssim_mean - 0.01,
                p95=ssim_mean + 0.01,
            ),
            QualityResult(
                provider_name="ffmpeg-native",
                metric_name="psnr",
                mean=psnr_mean,
                minimum=psnr_mean - 2.0,
                p1=psnr_mean - 1.5,
                p5=psnr_mean - 1.0,
                p95=psnr_mean + 1.0,
            ),
        ]
        native_metrics = NativeDomainMetrics()
        temporal_metrics = TemporalIntegrityMetrics()
        policy_score = TransformationPolicyScore(
            spatial_score_pct=1.0,
            temporal_score_pct=1.0,
            luminance_score_pct=1.0,
            chroma_score_pct=1.0,
            frequency_score_pct=1.0,
            aggregate_policy_score_pct=1.0,
            policy_ceiling_pct=5.0,
            passed=True,
            violations=[],
        )
        return results, native_metrics, temporal_metrics, policy_score

    def test_authoritative_policy_defaults_and_no_vmaf_fields(self):
        """VisualBudgetPolicy defaults to SSIM >= 0.9500, PSNR >= 30.0 dB, and has zero VMAF fields."""
        policy = VisualBudgetPolicy()
        self.assertEqual(policy.ssim_mean_min, 0.9500)
        self.assertEqual(policy.psnr_mean_min_db, 30.0)

        # Assert no VMAF fields exist on VisualBudgetPolicy
        vmaf_field_names = [
            "vmaf_gate_mode", "vmaf_gate_enabled", "vmaf_mean_min",
            "vmaf_p5_min", "vmaf_worst_min", "vmaf_model_path", "vmaf_audit_mode"
        ]
        for field in vmaf_field_names:
            self.assertFalse(
                hasattr(policy, field),
                f"VisualBudgetPolicy must not have VMAF attribute '{field}'"
            )

    def test_quality_gate_passes_above_threshold(self):
        """QualityGate passes when SSIM >= 0.95 and PSNR >= 30 dB."""
        policy = VisualBudgetPolicy()
        gate = QualityGate(policy)
        results, native, temporal, score = self._make_dummy_inputs(ssim_mean=0.96, psnr_mean=35.0)
        verdict = gate.evaluate(results, native, temporal, score)
        self.assertTrue(verdict.all_passed)
        self.assertTrue(verdict.tier2_fidelity_passed)
        self.assertEqual(verdict.overall_verdict, "PASS")
        self.assertEqual(len(verdict.tier2_violations), 0)

    def test_quality_gate_rejects_ssim_below_threshold(self):
        """QualityGate rejects when SSIM < 0.9500 with explicit violation."""
        policy = VisualBudgetPolicy()
        gate = QualityGate(policy)
        results, native, temporal, score = self._make_dummy_inputs(ssim_mean=0.9400, psnr_mean=35.0)
        verdict = gate.evaluate(results, native, temporal, score)
        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertEqual(verdict.overall_verdict, "REJECT")
        self.assertTrue(any("Mean SSIM" in v for v in verdict.tier2_violations))

    def test_quality_gate_rejects_psnr_below_threshold(self):
        """QualityGate rejects when PSNR < 30.0 dB with explicit violation."""
        policy = VisualBudgetPolicy()
        gate = QualityGate(policy)
        results, native, temporal, score = self._make_dummy_inputs(ssim_mean=0.9600, psnr_mean=28.0)
        verdict = gate.evaluate(results, native, temporal, score)
        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertEqual(verdict.overall_verdict, "REJECT")
        self.assertTrue(any("Mean PSNR" in v for v in verdict.tier2_violations))

    def test_quality_gate_fail_closed_missing_metric(self):
        """Fail-closed semantics: missing required quality metric fails the gate."""
        policy = VisualBudgetPolicy()
        gate = QualityGate(policy)

        # 1. Missing PSNR
        results_no_psnr, native, temporal, score = self._make_dummy_inputs()
        results_no_psnr = [r for r in results_no_psnr if r.metric_name != "psnr"]
        verdict = gate.evaluate(results_no_psnr, native, temporal, score)
        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertIn("Missing required quality measurement: PSNR", verdict.tier2_violations)

        # 2. Missing SSIM
        results_no_ssim, native, temporal, score = self._make_dummy_inputs()
        results_no_ssim = [r for r in results_no_ssim if r.metric_name != "ssim"]
        verdict = gate.evaluate(results_no_ssim, native, temporal, score)
        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)
        self.assertIn("Missing required quality measurement: SSIM", verdict.tier2_violations)

    def test_no_vmaf_modules_or_adapters_in_production(self):
        """No VMAF modules or adapters exist in veilframe package."""
        with self.assertRaises(ModuleNotFoundError):
            importlib.import_module("veilframe.quality.adapters.vmaf")

        with self.assertRaises(ModuleNotFoundError):
            importlib.import_module("veilframe.quality.vmaf_models")

        with self.assertRaises(ModuleNotFoundError):
            importlib.import_module("veilframe.quality.vmaf_policy")

        import veilframe.quality as quality_pkg
        self.assertFalse(hasattr(quality_pkg, "LibvmafFFmpegProvider"))

        import veilframe.quality.adapters as adapters_pkg
        self.assertFalse(hasattr(adapters_pkg, "LibvmafFFmpegProvider"))

    def test_signed_manifest_v1_1_0_structure(self):
        """generate_ed25519_signed_manifest creates valid v1.1.0 manifest with no VMAF keys."""
        from veilframe.core.validator import (
            evaluate_visual_quality,
            generate_ed25519_signed_manifest,
        )

        report = evaluate_visual_quality(
            self.ref_video, self.ref_video, canonical_w=160, canonical_h=120
        )
        out_dir = self.temp_dir / "manifest_test"
        paths = generate_ed25519_signed_manifest(report, out_dir)
        manifest_file = paths[0]
        self.assertTrue(manifest_file.exists())

        manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
        self.assertEqual(manifest_data.get("manifest_version"), "1.1.0")
        self.assertIn("rendered_fidelity", manifest_data)
        self.assertIn("ssim_mean", manifest_data["rendered_fidelity"])
        self.assertIn("psnr_mean_db", manifest_data["rendered_fidelity"])

        # Ensure no VMAF keys appear anywhere in manifest
        manifest_str = json.dumps(manifest_data).lower()
        self.assertNotIn("vmaf", manifest_str)

    def test_cli_doctor_no_libvmaf(self):
        """CLI doctor command functions cleanly without libvmaf and mentions no VMAF."""
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "doctor", "--json"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertIn("ffmpeg", data)
        self.assertNotIn("libvmaf", res.stdout.lower())

    def test_clean_environment_dependency_independence(self):
        """
        Dependency Independence:
        In an environment where libvmaf is completely unavailable,
        VeilFrame imports and runs quality evaluation successfully using SSIM and PSNR only.
        """
        from veilframe.quality.adapters.ffmpeg import FFmpegNativeProvider
        from veilframe.core.validator import evaluate_visual_quality

        provider = FFmpegNativeProvider()
        # Ensure provider is available (standard ffmpeg)
        self.assertTrue(provider.is_available())

        # Mock subprocess or filters output so that even if the host ffmpeg had libvmaf,
        # it is completely invisible / unavailable to the application
        def mock_run_cmd(*args, **kwargs):
            cmd = args[0] if args else kwargs.get("args", [])
            # If checking filters or version for libvmaf, simulate complete absence
            if "-filters" in cmd:
                return subprocess.CompletedProcess(
                    args=cmd, returncode=0,
                    stdout=" ... ssim ... psnr ... ", stderr=""
                )
            return original_run(*args, **kwargs)

        original_run = subprocess.run
        with patch("subprocess.run", side_effect=mock_run_cmd):
            # Run full production quality evaluation on reference video
            report = evaluate_visual_quality(
                self.ref_video,
                self.ref_video,
                canonical_w=160,
                canonical_h=120,
            )
            self.assertIsNotNone(report)
            self.assertTrue(report.passed)
            self.assertGreaterEqual(report.ssim.mean, 0.9500)
            self.assertGreaterEqual(report.psnr.mean, 30.0)
            self.assertFalse(hasattr(report, "vmaf_verdict"))

    def test_source_code_zero_vmaf_imports(self):
        """Scans all veilframe production source code to ensure zero active imports or references to libvmaf or vmaf adapters."""
        pkg_dir = Path(__file__).parent.parent / "veilframe"
        for py_file in pkg_dir.rglob("*.py"):
            content = py_file.read_text(encoding="utf-8")
            for line in content.splitlines():
                stripped = line.strip()
                if stripped.startswith("#") or stripped.startswith('"""') or stripped.startswith("*"):
                    continue
                self.assertNotIn(
                    "libvmaf",
                    stripped.lower(),
                    f"Forbidden 'libvmaf' reference in {py_file.name}: {stripped}",
                )
                self.assertNotIn(
                    "vmaf_adapter",
                    stripped.lower(),
                    f"Forbidden 'vmaf_adapter' reference in {py_file.name}: {stripped}",
                )


if __name__ == "__main__":
    unittest.main()
