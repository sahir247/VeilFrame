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
        # 1080p class
        self.assertEqual(classify_resolution(1920, 1080), "1080p")
        self.assertEqual(classify_resolution(1280, 720), "1080p")
        self.assertEqual(classify_resolution(640, 480), "1080p")
        # Vertical mobile 1080x1920
        self.assertEqual(classify_resolution(1080, 1920), "1080p")

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

    def test_relative_path_escaping(self):
        path = Path("models/vmaf/model.json")
        escaped = format_ffmpeg_filter_path(path)
        self.assertEqual(escaped, "models/vmaf/model.json")


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


if __name__ == "__main__":
    unittest.main()
