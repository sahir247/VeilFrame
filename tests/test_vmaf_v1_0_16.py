"""
Unit and integration tests for VeilFrame VMAF v1.0.16 model architecture.

Verifies:
  - Model registry and official SHA-256 values.
  - VMAF_MODEL_ROOT environment variable resolution and fallback.
  - Model verification with cryptographic hash check and tamper detection.
  - Rejection of missing models and corrupted hashes without silent fallback.
  - Deterministic model selection across resolution tiers (1080p, 4K) and framerates (standard vs HFR).
  - Orientation-safe resolution classification (horizontal, vertical).
  - Explicit rejection of unsupported intermediate resolutions (e.g., 1440p).
  - HDR detection and segregation (VmafNotApplicableHdrError).
  - FFmpeg filter path escaping on Windows.
  - LibvmafFFmpegProvider audit_mode invariant (strict model_path requirement).
"""
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from veilframe.quality.vmaf_models import (
    VMAF_MODEL_VERSION,
    OFFICIAL_VMAF_V1_0_16_MODELS,
    VmafModelSpec,
    VmafModelMissingError,
    VmafModelHashMismatchError,
    VmafNotApplicableHdrError,
    VmafUnsupportedResolutionError,
    get_vmaf_model_root,
    resolve_and_verify_model,
    classify_resolution,
    is_hfr,
    detect_hdr,
    select_vmaf_model,
    format_ffmpeg_filter_path,
)
from veilframe.quality.adapters.vmaf import LibvmafFFmpegProvider


class TestVmafModelConfiguration(unittest.TestCase):
    """Tests for model root resolution and configuration."""

    def test_model_root_env_override(self):
        with patch.dict(os.environ, {"VMAF_MODEL_ROOT": "D:/custom/vmaf/model"}):
            root = get_vmaf_model_root()
            self.assertEqual(root, Path("D:/custom/vmaf/model"))

    def test_model_root_explicit_override(self):
        override = Path("E:/another/model")
        root = get_vmaf_model_root(override_path=override)
        self.assertEqual(root, override)

    def test_model_root_default_fallback(self):
        env = dict(os.environ)
        env.pop("VMAF_MODEL_ROOT", None)
        with patch.dict(os.environ, env, clear=True):
            root = get_vmaf_model_root()
            self.assertEqual(root, Path.home() / "vmaf" / "model")


class TestVmafModelVerification(unittest.TestCase):
    """Tests for model file discovery, SHA-256 integrity check, and tampering detection."""

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="vmaf_model_test_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_missing_model_raises_cleanly(self):
        spec = OFFICIAL_VMAF_V1_0_16_MODELS["1080p_sdr"]
        with self.assertRaises(VmafModelMissingError) as ctx:
            resolve_and_verify_model(spec, model_root=self.temp_dir)
        self.assertIn("not found under", str(ctx.exception))

    def test_tampered_model_hash_mismatch_raises(self):
        spec = OFFICIAL_VMAF_V1_0_16_MODELS["1080p_sdr"]
        model_file = self.temp_dir / spec.relative_path
        model_file.parent.mkdir(parents=True, exist_ok=True)
        model_file.write_text('{"tampered": true}', encoding="utf-8")

        with self.assertRaises(VmafModelHashMismatchError) as ctx:
            resolve_and_verify_model(spec, model_root=self.temp_dir)
        self.assertIn("integrity check failed", str(ctx.exception))
        self.assertIn(spec.expected_sha256, str(ctx.exception))

    def test_valid_model_resolves_and_verifies(self):
        # Create a mock model matching a known hash
        from veilframe.core.crypto import compute_sha256

        mock_content = '{"version": "1.0.16", "name": "mock"}'
        mock_file = self.temp_dir / "vmaf_v1.0.16" / "mock.json"
        mock_file.parent.mkdir(parents=True, exist_ok=True)
        mock_file.write_text(mock_content, encoding="utf-8")
        expected_sha = compute_sha256(mock_file)

        mock_spec = VmafModelSpec(
            model_id="mock",
            filename="mock.json",
            relative_path="vmaf_v1.0.16/mock.json",
            expected_sha256=expected_sha,
            resolution_tier="1080p",
            is_hfr=False,
        )
        resolved = resolve_and_verify_model(mock_spec, model_root=self.temp_dir)
        self.assertEqual(resolved, mock_file)


class TestVmafModelSelection(unittest.TestCase):
    """Tests for deterministic model selection logic."""

    def test_hfr_boundary_policy(self):
        # Standard frame rates (non-HFR)
        self.assertFalse(is_hfr(23.976))
        self.assertFalse(is_hfr(24.0))
        self.assertFalse(is_hfr(25.0))
        self.assertFalse(is_hfr(29.97))
        self.assertFalse(is_hfr(30.0))
        self.assertFalse(is_hfr(48.0))
        self.assertFalse(is_hfr(49.0))

        # High frame rates (HFR >= 50.0)
        self.assertTrue(is_hfr(50.0))
        self.assertTrue(is_hfr(59.94))
        self.assertTrue(is_hfr(60.0))
        self.assertTrue(is_hfr(120.0))

    def test_resolution_classification(self):
        # True 1080p class
        self.assertEqual(classify_resolution(1920, 1080), "1080p")
        self.assertEqual(classify_resolution(1080, 1920), "1080p")
        self.assertEqual(classify_resolution(1808, 1080), "1080p")

        # Secondary class (720p, SD)
        self.assertEqual(classify_resolution(1280, 720), "secondary")
        self.assertEqual(classify_resolution(720, 1280), "secondary")
        self.assertEqual(classify_resolution(640, 480), "secondary")

        # 2160p class (4K)
        self.assertEqual(classify_resolution(3840, 2160), "2160p")
        self.assertEqual(classify_resolution(4096, 2160), "2160p")
        # Vertical 4K
        self.assertEqual(classify_resolution(2160, 3840), "2160p")

        # Unsupported intermediate resolutions
        self.assertEqual(classify_resolution(2560, 1440), "unsupported")
        self.assertEqual(classify_resolution(3000, 2000), "unsupported")

    def test_selection_1080p_sdr(self):
        spec = select_vmaf_model(1920, 1080, 29.97)
        self.assertEqual(spec.model_id, "vmaf_v1.0.16_3d0h")
        self.assertEqual(spec.filename, "vmaf_v1.0.16_3d0h.json")
        self.assertFalse(spec.is_hfr)

    def test_selection_1080p_hfr(self):
        spec = select_vmaf_model(1920, 1080, 59.94)
        self.assertEqual(spec.model_id, "vmaf_v1.0.16_hfr_3d0h")
        self.assertEqual(spec.filename, "vmaf_v1.0.16_hfr_3d0h.json")
        self.assertTrue(spec.is_hfr)

    def test_selection_4k_sdr(self):
        spec = select_vmaf_model(3840, 2160, 24.0)
        self.assertEqual(spec.model_id, "vmaf_v1.0.16_1d5h_2160")
        self.assertEqual(spec.filename, "vmaf_v1.0.16_1d5h_2160.json")
        self.assertFalse(spec.is_hfr)

    def test_selection_4k_hfr(self):
        spec = select_vmaf_model(3840, 2160, 60.0)
        self.assertEqual(spec.model_id, "vmaf_v1.0.16_hfr_1d5h_2160")
        self.assertEqual(spec.filename, "vmaf_v1.0.16_hfr_1d5h_2160.json")
        self.assertTrue(spec.is_hfr)

    def test_unsupported_resolution_raises(self):
        with self.assertRaises(VmafUnsupportedResolutionError):
            select_vmaf_model(2560, 1440, 30.0)

    def test_hdr_rejection_raises(self):
        with self.assertRaises(VmafNotApplicableHdrError):
            select_vmaf_model(1920, 1080, 30.0, is_hdr=True)


class TestHdrDetection(unittest.TestCase):
    """Tests for FFprobe HDR stream characteristics detection."""

    def test_detect_smpte2084(self):
        is_hdr_flag, reason = detect_hdr({"color_transfer": "smpte2084"})
        self.assertTrue(is_hdr_flag)
        self.assertIn("smpte2084", reason)

    def test_detect_arib_std_b67(self):
        is_hdr_flag, reason = detect_hdr({"color_transfer": "arib-std-b67"})
        self.assertTrue(is_hdr_flag)
        self.assertIn("arib-std-b67", reason)

    def test_detect_bt2020_pq(self):
        is_hdr_flag, reason = detect_hdr({
            "color_primaries": "bt2020",
            "color_transfer": "linear",
        })
        self.assertTrue(is_hdr_flag)

    def test_detect_sdr_normal(self):
        is_hdr_flag, reason = detect_hdr({
            "color_transfer": "bt709",
            "color_primaries": "bt709",
            "color_space": "bt709",
        })
        self.assertFalse(is_hdr_flag)
        self.assertEqual(reason, "")


class TestFfmpegPathEscaping(unittest.TestCase):
    """Tests for FFmpeg filter path formatting on Windows and POSIX."""

    def test_windows_drive_letter_escaping(self):
        path = Path("C:/models/vmaf/model.json")
        escaped = format_ffmpeg_filter_path(path)
        self.assertEqual(escaped, r"C\:/models/vmaf/model.json")

    def test_model_filter_arg_formatting(self):
        from veilframe.quality.vmaf_models import format_vmaf_model_filter_arg
        path = Path("C:/models/vmaf/model.json")
        arg = format_vmaf_model_filter_arg(path)
        self.assertEqual(arg, r"model='path=C\\\:/models/vmaf/model.json'")

    def test_spaces_in_path_escaping(self):
        from veilframe.quality.vmaf_models import format_vmaf_model_filter_arg
        path = Path("C:/Program Files/vmaf models/model 1.0.16.json")
        escaped = format_ffmpeg_filter_path(path)
        self.assertEqual(escaped, r"C\:/Program Files/vmaf models/model 1.0.16.json")
        arg = format_vmaf_model_filter_arg(path)
        self.assertEqual(arg, r"model='path=C\\\:/Program Files/vmaf models/model 1.0.16.json'")

    def test_windows_backslash_path_escaping(self):
        path = Path(r"C:\Users\test\vmaf\model.json")
        escaped = format_ffmpeg_filter_path(path)
        self.assertEqual(escaped, r"C\:/Users/test/vmaf/model.json")


class TestVmafAdapterAuditContract(unittest.TestCase):
    """Verifies that LibvmafFFmpegProvider in audit_mode strictly enforces model_path."""

    def test_audit_mode_missing_model_path_unavailable(self):
        provider = LibvmafFFmpegProvider(model_path=None, audit_mode=True)
        self.assertFalse(provider.is_available(),
                         "audit_mode=True with model_path=None must return False for is_available()")

    def test_audit_mode_nonexistent_model_path_unavailable(self):
        provider = LibvmafFFmpegProvider(
            model_path=Path("C:/nonexistent/model.json"),
            audit_mode=True,
        )
        self.assertFalse(provider.is_available(),
                         "audit_mode=True with nonexistent model_path must return False for is_available()")


class TestLibvmafLiveInputOrdering(unittest.TestCase):
    """
    Live integration test verifying FFmpeg libvmaf input ordering with actual execution:
    pad 0 = distorted, pad 1 = reference.
    Runs only when FFmpeg with libvmaf is present; skips cleanly otherwise.
    """

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="vmaf_ordering_test_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_live_input_ordering_identical_vs_degraded(self):
        import subprocess
        from veilframe.quality.models import QualityConfig

        provider = LibvmafFFmpegProvider(audit_mode=False)
        if not provider.is_available():
            raise unittest.SkipTest("libvmaf not available on this system")

        ref_path = self.temp_dir / "clean_ref.mp4"
        dist_path = self.temp_dir / "heavy_dist.mp4"

        # Generate 1-second 320x240 testsrc reference
        r1 = subprocess.run([
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
            "-t", "1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            str(ref_path),
        ], capture_output=True, text=True)
        if r1.returncode != 0:
            raise unittest.SkipTest("FFmpeg synthetic video generation failed")

        # Generate heavily degraded distorted version (boxblur 15 + high noise)
        r2 = subprocess.run([
            "ffmpeg", "-y",
            "-i", str(ref_path),
            "-vf", "boxblur=15:15,noise=alls=30:allf=t",
            "-c:v", "libx264", "-crf", "35", "-pix_fmt", "yuv420p",
            str(dist_path),
        ], capture_output=True, text=True)
        if r2.returncode != 0:
            raise unittest.SkipTest("FFmpeg distortion video generation failed")

        # 1. Identical pair (ref vs ref) -> VMAF near 100
        cfg_ident = QualityConfig(
            reference=ref_path,
            distorted=ref_path,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=self.temp_dir / "ident_evidence",
        )
        res_ident = provider.evaluate(cfg_ident)
        self.assertEqual(len(res_ident), 1)
        self.assertGreaterEqual(res_ident[0].mean, 90.0,
                                "Identical reference vs reference must achieve high VMAF (>= 90)")

        # 2. Heavily degraded pair (distorted vs ref) -> VMAF substantially lower
        cfg_degraded = QualityConfig(
            reference=ref_path,
            distorted=dist_path,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=self.temp_dir / "degraded_evidence",
        )
        res_degraded = provider.evaluate(cfg_degraded)
        self.assertEqual(len(res_degraded), 1)
        self.assertLess(res_degraded[0].mean, 50.0,
                        "Heavily blurred/noised clip must score significantly lower (< 50) than clean reference")
        self.assertGreater(res_ident[0].mean - res_degraded[0].mean, 40.0,
                           "Separation between identical and degraded pair must exceed 40 points")


class TestLibvmafMissingMetricsFailClosed(unittest.TestCase):
    """
    Verifies that LibvmafFFmpegProvider and QualityGate fail-closed when
    VMAF metric measurements are missing or malformed, and never manufacture 0.0.
    """

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="vmaf_missing_test_"))

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_parse_vmaf_json_empty_frames_raises_runtime_error(self):
        """Empty frames array without pooled metrics must raise RuntimeError, never 0.0."""
        json_file = self.temp_dir / "empty_frames.json"
        json_file.write_text('{"frames": []}', encoding="utf-8")

        with self.assertRaises(RuntimeError) as ctx:
            LibvmafFFmpegProvider._parse_vmaf_json(json_file)
        self.assertIn("contains no valid VMAF frame scores", str(ctx.exception))

    def test_parse_vmaf_json_corrupt_file_raises_runtime_error(self):
        """Malformed JSON must raise RuntimeError."""
        json_file = self.temp_dir / "corrupt.json"
        json_file.write_text('{invalid_json: 123', encoding="utf-8")

        with self.assertRaises(RuntimeError) as ctx:
            LibvmafFFmpegProvider._parse_vmaf_json(json_file)
        self.assertIn("Failed to parse vmaf.json", str(ctx.exception))

    def test_parse_vmaf_json_missing_vmaf_key_raises_runtime_error(self):
        """Frames containing other metrics but no 'vmaf' key must raise RuntimeError."""
        json_file = self.temp_dir / "no_vmaf_key.json"
        json_file.write_text('{"frames": [{"frameNum": 0, "metrics": {"psnr": 40.0}}]}', encoding="utf-8")

        with self.assertRaises(RuntimeError) as ctx:
            LibvmafFFmpegProvider._parse_vmaf_json(json_file)
        self.assertIn("contains no valid VMAF frame scores", str(ctx.exception))

    def test_parse_vmaf_json_pooled_only_preserves_none_percentiles(self):
        """Pooled-only JSON must preserve None for uncomputed percentiles, never 0.0."""
        json_file = self.temp_dir / "pooled_only.json"
        json_file.write_text(
            '{"pooled_metrics": {"vmaf": {"mean": 94.5, "min": 88.0}}, "frames": []}',
            encoding="utf-8",
        )

        raw = LibvmafFFmpegProvider._parse_vmaf_json(json_file)
        self.assertEqual(raw["mean"], 94.5)
        self.assertEqual(raw["min"], 88.0)
        self.assertIsNone(raw["p1"], "P1 must be None when per-frame stats are absent (not 0.0)")
        self.assertIsNone(raw["p5"], "P5 must be None when per-frame stats are absent (not 0.0)")
        self.assertIsNone(raw["p95"], "P95 must be None when per-frame stats are absent (not 0.0)")

    def test_parse_stderr_summary_missing_score_raises_runtime_error(self):
        """FFmpeg stderr output with no VMAF score pattern must raise RuntimeError, never 0.0."""
        stderr = "frame= 100 fps= 50 q=-0.0 Lsize=N/A time=00:00:04.00 bitrate=N/A speed=1.5x\nvideo:100kB audio:0kB"

        with self.assertRaises(RuntimeError) as ctx:
            LibvmafFFmpegProvider._parse_stderr_summary(stderr)
        self.assertIn("Failed to parse VMAF score", str(ctx.exception))

    def test_parse_stderr_summary_valid_score_preserves_none_percentiles(self):
        """Parsed stderr summary must leave percentiles as None (absent, not 0.0)."""
        stderr = "[Parsed_libvmaf_0 @ 000001] VMAF score = 93.421000\n[libx264 @ 000002] frame I:2"

        raw = LibvmafFFmpegProvider._parse_stderr_summary(stderr)
        self.assertAlmostEqual(raw["mean"], 93.421, places=3)
        self.assertIsNone(raw["min"], "Min must be None from stderr summary (not 0.0)")
        self.assertIsNone(raw["p1"], "P1 must be None from stderr summary (not 0.0)")
        self.assertIsNone(raw["p5"], "P5 must be None from stderr summary (not 0.0)")
        self.assertIsNone(raw["p95"], "P95 must be None from stderr summary (not 0.0)")

    def test_gate_rejects_when_p5_unavailable(self):
        """When vmaf_gate_enabled=True, missing P5 (None) must trigger explicit rejection."""
        from veilframe.quality.gate import QualityGate
        from veilframe.quality.models import QualityResult
        from veilframe.models.settings import VisualBudgetPolicy
        from veilframe.models.video_info import (
            NativeDomainMetrics, TemporalIntegrityMetrics, TransformationPolicyScore
        )

        policy = VisualBudgetPolicy(
            vmaf_gate_enabled=True,
            vmaf_mean_min=80.0,
            vmaf_p5_min=75.0,
        )
        gate = QualityGate(policy)

        # Result with valid mean but p5=None (e.g. from stderr summary)
        results = [
            QualityResult("native", "ssim", mean=0.98, minimum=0.96, p1=0.965, p5=0.97, p95=0.99),
            QualityResult("native", "psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
            QualityResult("vmaf", "vmaf", mean=95.0, minimum=90.0, p1=None, p5=None, p95=None),
        ]

        verdict = gate.evaluate(
            results=results,
            native_metrics=NativeDomainMetrics(),
            temporal_metrics=TemporalIntegrityMetrics(0, 0, 0, 0.0, []),
            policy_score=TransformationPolicyScore(0, 0, 0, 0, 0, 0, 5.0, True, []),
        )

        self.assertFalse(verdict.all_passed)
        self.assertFalse(verdict.tier2_fidelity_passed)
        p5_viols = [v for v in verdict.tier2_violations if "P5 percentile unavailable" in v]
        self.assertGreater(len(p5_viols), 0, "Missing P5 must produce explicit unavailable violation")

    def test_gate_rejects_when_worst_frame_unavailable(self):
        """When vmaf_gate_enabled=True and vmaf_worst_min is set, missing min (None) must reject."""
        from veilframe.quality.gate import QualityGate
        from veilframe.quality.models import QualityResult
        from veilframe.models.settings import VisualBudgetPolicy
        from veilframe.models.video_info import (
            NativeDomainMetrics, TemporalIntegrityMetrics, TransformationPolicyScore
        )

        policy = VisualBudgetPolicy(
            vmaf_gate_enabled=True,
            vmaf_mean_min=80.0,
            vmaf_p5_min=75.0,
            vmaf_worst_min=70.0,
        )
        gate = QualityGate(policy)

        # Result with valid mean and p5, but minimum=None
        results = [
            QualityResult("native", "ssim", mean=0.98, minimum=0.96, p1=0.965, p5=0.97, p95=0.99),
            QualityResult("native", "psnr", mean=42.0, minimum=38.0, p1=38.5, p5=39.0, p95=45.0),
            QualityResult("vmaf", "vmaf", mean=95.0, minimum=None, p1=92.0, p5=93.0, p95=97.0),
        ]

        verdict = gate.evaluate(
            results=results,
            native_metrics=NativeDomainMetrics(),
            temporal_metrics=TemporalIntegrityMetrics(0, 0, 0, 0.0, []),
            policy_score=TransformationPolicyScore(0, 0, 0, 0, 0, 0, 5.0, True, []),
        )

        self.assertFalse(verdict.all_passed)
        worst_viols = [v for v in verdict.tier2_violations if "worst-frame score unavailable" in v]
        self.assertGreater(len(worst_viols), 0, "Missing worst-frame score must produce explicit unavailable violation")


if __name__ == "__main__":
    unittest.main()
