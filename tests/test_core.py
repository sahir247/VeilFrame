"""
Automated unit and integration test suite for Video Privacy & Processing Engine v1.
"""
import sys
import unittest
import tempfile
import subprocess
from pathlib import Path

# Add parent directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from privacy_cleaner.models.video_info import VideoInfo, VideoStreamInfo, AudioStreamInfo, MetadataInfo
from privacy_cleaner.models.settings import (
    ProcessingSettings,
    CropSettings,
    ResizeSettings,
    FpsSettings,
    TrimSettings,
    NoiseSettings,
    ColorSettings,
    AudioPrivacySettings,
    QuantizationSettings,
    CodecSettings,
    QualitySettings,
    PrivacySettings,
)
from privacy_cleaner.core.crop import calculate_crop, build_crop_filter
from privacy_cleaner.core.resize import calculate_resize, build_resize_filter
from privacy_cleaner.core.fps import calculate_fps, build_fps_arg
from privacy_cleaner.core.trim import calculate_trim, build_trim_args, parse_timestamp
from privacy_cleaner.core.noise import calculate_noise_strength, build_noise_filter, get_noise_level_label
from privacy_cleaner.core.color import build_color_filter
from privacy_cleaner.core.audio_pipeline import build_audio_filtergraph
from privacy_cleaner.core.analyzer import analyze_video
from privacy_cleaner.core.verifier import verify_output
from privacy_cleaner.core.pipeline import run_pipeline
from privacy_cleaner.presets.manager import PresetManager
from privacy_cleaner.core.resources import get_ffmpeg_path, get_ffprobe_path


class TestModelsAndTransformations(unittest.TestCase):
    def setUp(self):
        self.mock_info = VideoInfo(
            duration=100.0,
            video=VideoStreamInfo(
                width=2160,
                height=3840,
                fps=60.0,
                avg_fps=60.0,
                duration=100.0,
                codec="h264",
            ),
            audio=AudioStreamInfo(
                codec="aac",
                sample_rate=48000,
                channels=2,
            ),
        )

    def test_crop_auto_and_manual(self):
        # Disabled
        c_off = CropSettings(enabled=False)
        self.assertIsNone(calculate_crop(c_off, self.mock_info))
        self.assertEqual(build_crop_filter(c_off, self.mock_info), "")

        # Auto: asymmetric border micro-crop
        c_auto = CropSettings(enabled=True, mode="auto", asymmetric=True)
        crop_rect = calculate_crop(c_auto, self.mock_info)
        self.assertIsNotNone(crop_rect)
        x, y, w, h = crop_rect
        self.assertGreater(x, 0)
        self.assertGreater(y, 0)
        self.assertEqual(w % 2, 0)
        self.assertEqual(h % 2, 0)
        self.assertLess(w, 2160)
        self.assertLess(h, 3840)
        self.assertTrue(build_crop_filter(c_auto, self.mock_info).startswith("crop="))

        # Manual
        c_man = CropSettings(enabled=True, mode="manual", left=10, right=10, top=20, bottom=20)
        crop_man = calculate_crop(c_man, self.mock_info)
        self.assertEqual(crop_man, (10, 20, 2140, 3800))

    def test_resize_auto_and_manual(self):
        # Disabled
        r_off = ResizeSettings(enabled=False)
        self.assertIsNone(calculate_resize(r_off, self.mock_info))
        self.assertEqual(build_resize_filter(r_off, self.mock_info), "")

        # Auto: subtle 99.8% scaling
        r_auto = ResizeSettings(enabled=True, mode="auto")
        dims = calculate_resize(r_auto, self.mock_info)
        self.assertIsNotNone(dims)
        w, h = dims
        self.assertEqual(w % 2, 0)
        self.assertEqual(h % 2, 0)
        self.assertEqual(w, 2156)
        self.assertEqual(h, 3832)
        self.assertIn("scale=2156:3832", build_resize_filter(r_auto, self.mock_info))

        # Manual
        r_man = ResizeSettings(enabled=True, mode="manual", width=1920, height=1080, maintain_aspect=False)
        dims_man = calculate_resize(r_man, self.mock_info)
        self.assertEqual(dims_man, (1920, 1080))

    def test_fps_auto_and_manual(self):
        # Disabled
        f_off = FpsSettings(enabled=False)
        self.assertIsNone(calculate_fps(f_off, self.mock_info))
        self.assertEqual(build_fps_arg(f_off, self.mock_info), [])

        # Auto: 60.0 -> 59.88 (approx ~99.8%)
        f_auto = FpsSettings(enabled=True, mode="auto")
        target_fps = calculate_fps(f_auto, self.mock_info)
        self.assertEqual(target_fps, 59.88)
        self.assertEqual(build_fps_arg(f_auto, self.mock_info), ["-r", "59.88"])

        # Manual
        f_man = FpsSettings(enabled=True, mode="manual", fps=59.5)
        self.assertEqual(calculate_fps(f_man, self.mock_info), 59.5)
        self.assertEqual(build_fps_arg(f_man, self.mock_info), ["-r", "59.5"])

    def test_trim_auto_and_manual(self):
        # Disabled
        t_off = TrimSettings(enabled=False)
        self.assertIsNone(calculate_trim(t_off, self.mock_info))
        self.assertEqual(build_trim_args(t_off, self.mock_info), [])

        # Auto: 100s -> 99.8s
        t_auto = TrimSettings(enabled=True, mode="auto")
        trim_res = calculate_trim(t_auto, self.mock_info)
        self.assertEqual(trim_res, (0.0, 99.8))
        self.assertEqual(build_trim_args(t_auto, self.mock_info), ["-t", "99.800"])

        # Manual
        t_man = TrimSettings(enabled=True, mode="manual", start=5.0, duration=20.0)
        self.assertEqual(calculate_trim(t_man, self.mock_info), (5.0, 20.0))
        self.assertEqual(build_trim_args(t_man, self.mock_info), ["-ss", "5.000", "-t", "20.000"])

    def test_noise_engine(self):
        # Disabled / OFF -> absolutely no noise filter
        n_off = NoiseSettings(enabled=False, strength=0)
        self.assertEqual(build_noise_filter(n_off, self.mock_info), "")
        label, cat = get_noise_level_label(0)
        self.assertEqual(label, "Disabled")

        # Enabled at minimum (strength=1) -> low-amplitude temporal noise
        n_min = NoiseSettings(enabled=True, mode="manual", strength=1)
        self.assertEqual(build_noise_filter(n_min, self.mock_info), "noise=alls=1:allf=t+u")
        lbl, cat = get_noise_level_label(1)
        self.assertEqual(lbl, "Extremely Subtle")
        self.assertEqual(cat, "subtle")

        # Auto noise -> subtle default
        n_auto = NoiseSettings(enabled=True, mode="auto")
        self.assertEqual(calculate_noise_strength(n_auto), 2)
        self.assertIn("noise=alls=", build_noise_filter(n_auto, self.mock_info))

        # Higher levels
        lbl, cat = get_noise_level_label(20)
        self.assertEqual(lbl, "Subtle")
        lbl, cat = get_noise_level_label(45)
        self.assertEqual(lbl, "Noticeable / Visible")
        lbl, cat = get_noise_level_label(80)
        self.assertEqual(lbl, "Strong")

    def test_color_drift(self):
        # Disabled
        c_off = ColorSettings(enabled=False)
        self.assertEqual(build_color_filter(c_off, self.mock_info), "")

        # Auto (~1% bounded drift)
        c_auto = ColorSettings(enabled=True, mode="auto")
        col_f = build_color_filter(c_auto, self.mock_info)
        self.assertEqual(col_f, "eq=contrast=1.015:brightness=0.005:gamma=0.985:saturation=1.02")

        # Manual
        c_man = ColorSettings(enabled=True, mode="manual", contrast=1.02, brightness=0.01, gamma=0.99, saturation=1.03)
        self.assertEqual(build_color_filter(c_man, self.mock_info), "eq=contrast=1.02:brightness=0.01:gamma=0.99:saturation=1.03")

    def test_audio_pipeline(self):
        # Disabled
        a_off = AudioPrivacySettings(enabled=False)
        self.assertEqual(build_audio_filtergraph(a_off, self.mock_info), "")

        # Enabled with ENF Notch (50/60/100/120Hz) + Micro-pitch (0.99x)
        a_on = AudioPrivacySettings(enabled=True, enf_notch=True, micro_pitch=True, pitch_ratio=0.99)
        af = build_audio_filtergraph(a_on, self.mock_info)
        self.assertIn("bandreject=f=50:w=1.5", af)
        self.assertIn("bandreject=f=60:w=1.5", af)
        self.assertIn("bandreject=f=100:w=1.5", af)
        self.assertIn("bandreject=f=120:w=1.5", af)
        self.assertIn("asetrate=", af)
        self.assertIn("atempo=", af)

    def test_presets(self):
        pm = PresetManager()
        names = pm.get_preset_names()
        self.assertIn("5% Bounded Forensic Disruption", names)
        self.assertIn("Privacy Clean", names)
        self.assertIn("Custom", names)
        self.assertIn("Export", names)

        s_bounded = pm.apply_preset("5% Bounded Forensic Disruption")
        self.assertTrue(s_bounded.crop.enabled)
        self.assertTrue(s_bounded.resize.enabled)
        self.assertTrue(s_bounded.noise.enabled)
        self.assertEqual(s_bounded.noise.strength, 8)
        self.assertTrue(s_bounded.color.enabled)
        self.assertTrue(s_bounded.audio_privacy.enabled)
        self.assertTrue(s_bounded.quantization.forced_gop)
        self.assertEqual(s_bounded.quantization.gop_size, 48)


class TestPipelineEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = get_ffmpeg_path()
        cls.ffprobe = get_ffprobe_path()

    def test_end_to_end_5pct_bounded_forensic_disruption(self):
        """Creates a synthetic video with metadata, GPS, and comments, then runs the 5% Bounded Forensic Disruption pipeline."""
        with tempfile.TemporaryDirectory(prefix="pvc_test_") as td:
            tmp = Path(td)
            raw_vid = tmp / "sample_with_leaks.mp4"
            clean_vid = tmp / "sample_bounded_clean.mp4"

            # 1. Create a 2-second test MP4 with synthetic metadata and test bars
            cmd_create = [
                str(self.ffmpeg), "-hide_banner", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-metadata", "title=Classified Footage",
                "-metadata", "comment=Confidential personal remarks",
                "-metadata", "location=+37.7749-122.4194/",
                "-metadata", "make=Sony",
                "-metadata", "model=Alpha 7 IV",
                "-metadata", "creation_time=2025-06-15T14:30:00.000000Z",
                "-metadata", "encoder=Surveillance Ingest Engine",
                str(raw_vid),
            ]
            subprocess.run(cmd_create, capture_output=True, check=True)

            # Verify input video contains metadata leaks
            input_info = analyze_video(raw_vid)
            self.assertTrue(input_info.metadata.has_privacy_leaks)
            self.assertIsNotNone(input_info.metadata.creation_date)
            self.assertIsNotNone(input_info.metadata.comment)

            # 2. Run the 5% Bounded Forensic Disruption Pipeline
            pm = PresetManager()
            settings = pm.apply_preset("5% Bounded Forensic Disruption")

            progress_logs = []
            def on_progress(pct, msg):
                progress_logs.append((pct, msg))

            report = run_pipeline(
                src_path=raw_vid,
                dst_path=clean_vid,
                settings=settings,
                progress_callback=on_progress,
            )

            # 3. Inspect Verification Report
            self.assertTrue(clean_vid.exists())
            self.assertTrue(report.all_passed)
            self.assertTrue(report.metadata_passed)
            self.assertTrue(report.container_passed)
            self.assertTrue(report.stream_passed)
            self.assertEqual(report.gps, "NONE")
            self.assertEqual(report.camera, "NONE")
            self.assertEqual(report.device, "NONE")
            self.assertEqual(report.mod_date, "NONE")
            self.assertEqual(report.comment, "NONE")
            self.assertEqual(report.encoder, "NONE")
            self.assertEqual(report.chapters, "NONE")
            self.assertEqual(report.attachments, "NONE")
            self.assertEqual(report.summary_statement, "Embedded metadata successfully sanitized.")

            # Print formatted report for visibility
            try:
                print("\n" + report.format_text())
            except UnicodeEncodeError:
                print("\n" + report.format_text().encode("ascii", "replace").decode("ascii"))


if __name__ == "__main__":
    unittest.main()
