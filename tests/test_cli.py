"""
Tests for VeilFrame Unified Command Line Interface (CLI).
"""
import json
import tempfile
import unittest
import subprocess
from pathlib import Path

from veilframe.core.resources import get_ffmpeg_path


class TestVeilFrameCLI(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="veilframe_cli_test_"))
        cls.ref_video = cls.temp_dir / "test_sample.mp4"

        # Generate a small 1-second reference video
        ffmpeg = get_ffmpeg_path()
        cmd = [
            str(ffmpeg), "-y",
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=1.0",
            "-f", "lavfi", "-i", "sine=frequency=1000:duration=1.0",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            str(cls.ref_video),
        ]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    def test_cli_version(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "--version"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        self.assertIn("VeilFrame", res.stdout)

    def test_cli_help(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "--help"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        self.assertIn("sanitize", res.stdout)
        self.assertIn("inspect", res.stdout)
        self.assertIn("audit", res.stdout)
        self.assertIn("doctor", res.stdout)
        self.assertIn("presets", res.stdout)

    def test_cli_doctor_json(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "doctor", "--json"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertIn("ffmpeg", data)
        self.assertIn("os", data)
        self.assertIn("python", data)
        self.assertTrue(data["ffmpeg"]["available"])

    def test_cli_presets_json(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "presets", "--json"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        names = [p["name"] for p in data]
        self.assertIn("5% Bounded Forensic Disruption", names)
        self.assertIn("10% Bounded Forensic Disruption", names)

    def test_cli_inspect_json(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "inspect", str(self.ref_video), "--json"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertEqual(data["video"]["width"], 160)
        self.assertEqual(data["video"]["height"], 120)
        self.assertIn("duration", data)

    def test_cli_audit_pass(self):
        import sys
        res = subprocess.run(
            [sys.executable, "-m", "veilframe.cli", "audit", str(self.ref_video), str(self.ref_video), "--json"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertEqual(data["verdict"], "PASS")
        self.assertTrue(data["passed"])

    def test_cli_sanitize_e2e_json(self):
        import sys
        out_vid = self.temp_dir / "sanitized_out.mp4"
        res = subprocess.run(
            [
                sys.executable, "-m", "veilframe.cli", "sanitize",
                str(self.ref_video),
                "-o", str(out_vid),
                "--preset", "5% Bounded Forensic Disruption",
                "--json",
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "success")
        self.assertTrue(out_vid.exists())


if __name__ == "__main__":
    unittest.main()
