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

    def test_prompt_choice_interactive_mock(self):
        from unittest.mock import patch
        from veilframe.cli_ui import prompt_choice

        choices = ["Tool 1", "Tool 2", "Tool 3", "Exit"]

        # Simulate Down arrow key navigation followed by Enter
        with patch("sys.stdin.isatty", return_value=True), \
             patch("veilframe.cli_ui._read_single_key", side_effect=["DOWN", "DOWN", "ENTER"]):
            idx = prompt_choice("Select Operation", choices, default_idx=0)
            self.assertEqual(idx, 2)

        # Simulate Up arrow key navigation wrapping around
        with patch("sys.stdin.isatty", return_value=True), \
             patch("veilframe.cli_ui._read_single_key", side_effect=["UP", "ENTER"]):
            idx = prompt_choice("Select Operation", choices, default_idx=0)
            self.assertEqual(idx, 3)

        # Simulate numeric key fast pick '2'
        with patch("sys.stdin.isatty", return_value=True), \
             patch("veilframe.cli_ui._read_single_key", return_value="2"):
            idx = prompt_choice("Select Operation", choices, default_idx=0)
            self.assertEqual(idx, 1)

    def test_prompt_text_and_confirm(self):
        from unittest.mock import patch
        from veilframe.cli_ui import prompt_text, prompt_confirm

        with patch("builtins.input", return_value="test_path.mp4"):
            self.assertEqual(prompt_text("Enter file path"), "test_path.mp4")

        with patch("builtins.input", return_value="y"):
            self.assertTrue(prompt_confirm("Confirm operation"))

        with patch("builtins.input", return_value="n"):
            self.assertFalse(prompt_confirm("Confirm operation"))


if __name__ == "__main__":
    unittest.main()
