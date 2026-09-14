"""
Tests for pipeline stage timing instrumentation and throughput metrics.
"""
import unittest
import tempfile
import shutil
import subprocess
from pathlib import Path

from veilframe.core.pipeline import run_pipeline
from veilframe.core.resources import get_ffmpeg_path, get_subprocess_flags
from veilframe.models.settings import ProcessingSettings, VisualBudgetPolicy
from veilframe.core.verifier import VerificationReport


class TestPipelineStageInstrumentation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = get_ffmpeg_path()
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="vf_bench_test_"))
        cls.sample_video = cls.temp_dir / "bench_sample.mp4"

        cmd = [
            str(cls.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-f", "lavfi",
            "-i", "testsrc=duration=1.5:size=320x240:rate=30",
            "-f", "lavfi",
            "-i", "sine=frequency=1000:duration=1.5",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            str(cls.sample_video),
        ]
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            creationflags=get_subprocess_flags(),
        )
        if proc.returncode != 0:
            raise RuntimeError(f"Failed to generate test reference video: {proc.stderr}")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(str(cls.temp_dir), ignore_errors=True)

    def test_pipeline_populates_stage_timings_and_metrics(self):
        """Pipeline execution must populate stage_timings and throughput stats."""
        dst = self.temp_dir / "out_instrumented.mp4"
        settings = ProcessingSettings()
        settings.quality_gate = VisualBudgetPolicy()
        settings.quality_gate.enabled = True

        report: VerificationReport = run_pipeline(
            src_path=self.sample_video,
            dst_path=dst,
            settings=settings,
        )

        self.assertIsInstance(report.stage_timings, dict)
        self.assertIn("analyze", report.stage_timings)
        self.assertIn("encode", report.stage_timings)
        self.assertIn("quality_audit", report.stage_timings)
        self.assertIn("verify", report.stage_timings)
        self.assertIn("total", report.stage_timings)

        self.assertGreater(report.encode_fps, 0.0)
        self.assertGreater(report.audit_fps, 0.0)
        self.assertGreater(report.total_fps, 0.0)
        self.assertGreaterEqual(report.peak_ram_mb, 0.0)

        # Check formatted text report includes performance section
        text = report.format_text()
        self.assertIn("Pipeline Performance & Stage Latencies", text)
        self.assertIn("Encode Throughput:", text)
        self.assertIn("Audit Throughput:", text)


if __name__ == "__main__":
    unittest.main()
