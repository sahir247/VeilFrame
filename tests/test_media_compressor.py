"""
Tests for VeilFrame Unified Media Compressor Engine (Image & Video).
Validates precision range trimming, downscaling, aspect cropping, color filters, and compression ratio.
"""

import os
import shutil
import tempfile
import unittest
import subprocess
from pathlib import Path
from PIL import Image

from veilframe.core.media_compressor import compress_image, compress_video
from veilframe.core.resources import get_ffmpeg_path


class TestMediaCompressor(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="veilframe_compressor_test_"))
        
        # 1. Create a test reference image (400x300 RGB gradient)
        cls.test_image = cls.temp_dir / "sample_source.png"
        img = Image.new("RGB", (400, 300), color=(120, 180, 240))
        for x in range(400):
            for y in range(300):
                if (x + y) % 20 < 10:
                    img.putpixel((x, y), (x % 256, y % 256, (x * y) % 256))
        img.save(cls.test_image, format="PNG")

        # 2. Create a 3-second reference test video
        cls.test_video = cls.temp_dir / "sample_source.mp4"
        ffmpeg = get_ffmpeg_path()
        if ffmpeg.exists():
            cmd = [
                str(ffmpeg), "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=3.0",
                "-f", "lavfi", "-i", "sine=frequency=1000:duration=3.0",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                str(cls.test_video),
            ]
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.temp_dir, ignore_errors=True)

    # -------------------------------------------------------------------------
    # Image Compression & Studio Tests
    # -------------------------------------------------------------------------

    def test_image_compress_basic_jpeg(self):
        out_file = self.temp_dir / "out_basic.jpg"
        res = compress_image(
            input_path=self.test_image,
            output_path=out_file,
            quality=75,
            format="JPEG",
        )
        self.assertTrue(out_file.exists())
        self.assertGreater(out_file.stat().st_size, 0)
        self.assertEqual(res["format"], "JPEG")
        self.assertEqual(res["dimensions"], (400, 300))

    def test_image_compress_resize(self):
        out_file = self.temp_dir / "out_resized.jpg"
        res = compress_image(
            input_path=self.test_image,
            output_path=out_file,
            quality=80,
            width=200,
            height=150,
            keep_aspect=True,
        )
        self.assertTrue(out_file.exists())
        with Image.open(out_file) as im:
            self.assertEqual(im.size, (200, 150))
        self.assertEqual(res["dimensions"], (200, 150))

    def test_image_compress_rotate(self):
        out_file = self.temp_dir / "out_rotated.jpg"
        res = compress_image(
            input_path=self.test_image,
            output_path=out_file,
            quality=80,
            rotate_deg=90.0,
        )
        self.assertTrue(out_file.exists())
        with Image.open(out_file) as im:
            # 400x300 rotated 90 degrees becomes 300x400
            self.assertEqual(im.size, (300, 400))
        self.assertEqual(res["dimensions"], (300, 400))

    def test_image_compress_crop(self):
        out_file = self.temp_dir / "out_cropped.jpg"
        res = compress_image(
            input_path=self.test_image,
            output_path=out_file,
            quality=80,
            crop_box=(50, 50, 250, 200),
        )
        self.assertTrue(out_file.exists())
        with Image.open(out_file) as im:
            self.assertEqual(im.size, (200, 150))
        self.assertEqual(res["dimensions"], (200, 150))

    def test_image_filters(self):
        for flt in ["Grayscale", "Sepia", "Vintage", "Cool", "Warm"]:
            out_file = self.temp_dir / f"out_filter_{flt}.jpg"
            res = compress_image(
                input_path=self.test_image,
                output_path=out_file,
                quality=75,
                filter_name=flt,
            )
            self.assertTrue(out_file.exists())
            self.assertGreater(out_file.stat().st_size, 0)

    def test_image_formats_png_webp(self):
        png_out = self.temp_dir / "out_test.png"
        res_png = compress_image(self.test_image, png_out, quality=85, format="PNG")
        self.assertTrue(png_out.exists())
        self.assertEqual(res_png["format"], "PNG")

        webp_out = self.temp_dir / "out_test.webp"
        res_webp = compress_image(self.test_image, webp_out, quality=75, format="WEBP")
        self.assertTrue(webp_out.exists())
        self.assertEqual(res_webp["format"], "WEBP")

    # -------------------------------------------------------------------------
    # Video Compression & Timeline Trimming Tests
    # -------------------------------------------------------------------------

    def test_video_trim_middle_arbitrary_range(self):
        """Verify user can trim middle portion of video (e.g. 1.0s to 2.0s of a 3.0s video)."""
        if not self.test_video.exists():
            self.skipTest("FFmpeg test video not available")

        out_file = self.temp_dir / "out_trim_middle.mp4"
        res = compress_video(
            input_path=self.test_video,
            output_path=out_file,
            start_time=1.0,
            end_time=2.0,
            crf=28,
        )
        self.assertTrue(out_file.exists())
        self.assertGreater(out_file.stat().st_size, 0)
        # Trimmed duration should be ~1.0 second
        self.assertAlmostEqual(res["duration"], 1.0, delta=0.2)

    def test_video_resolution_scaling(self):
        if not self.test_video.exists():
            self.skipTest("FFmpeg test video not available")

        out_file = self.temp_dir / "out_scale_360p.mp4"
        res = compress_video(
            input_path=self.test_video,
            output_path=out_file,
            resolution="360p",
            crf=30,
        )
        self.assertTrue(out_file.exists())
        self.assertGreater(out_file.stat().st_size, 0)

    def test_video_audio_mute(self):
        if not self.test_video.exists():
            self.skipTest("FFmpeg test video not available")

        out_file = self.temp_dir / "out_muted.mp4"
        res = compress_video(
            input_path=self.test_video,
            output_path=out_file,
            audio_action="mute",
            crf=28,
        )
        self.assertTrue(out_file.exists())
        self.assertGreater(out_file.stat().st_size, 0)

    def test_video_speed_adjustment(self):
        if not self.test_video.exists():
            self.skipTest("FFmpeg test video not available")

        out_file = self.temp_dir / "out_speed_15x.mp4"
        res = compress_video(
            input_path=self.test_video,
            output_path=out_file,
            speed=1.5,
            crf=28,
        )
        self.assertTrue(out_file.exists())
        self.assertGreater(out_file.stat().st_size, 0)
        # 3.0s at 1.5x should be ~2.0s
        self.assertAlmostEqual(res["duration"], 2.0, delta=0.3)


if __name__ == "__main__":
    unittest.main()
