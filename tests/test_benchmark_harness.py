"""
Tests for pipeline stage timing instrumentation and benchmark harness.
"""
import unittest
import tempfile
import shutil
from pathlib import Path

from veilframe.core.pipeline import run_pipeline
from veilframe.models.settings import ProcessingSettings, VisualBudgetPolicy
from veilframe.presets.manager import PresetManager
from veilframe.core.verifier import VerificationReport
from tools.benchmark_performance import generate_synthetic_video, run_benchmark


class TestBenchmarkAndStageInstrumentation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="vf_bench_test_"))
        cls.sample_video = cls.temp_dir / "bench_sample.mp4"
        generate_synthetic_video(
            dst_path=cls.sample_video,
            duration_sec=1.5,
            fps=30,
            resolution="320x240",
        )

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(str(cls.temp_dir), ignore_errors=True)

    def test_pipeline_populates_stage_timings_and_metrics(self):
        """Pipeline execution must populate stage_timings and throughput stats."""
        dst = self.temp_dir / "out_instrumented.mp4"
        settings = ProcessingSettings()
        settings.quality_gate = VisualBudgetPolicy(sample_count=5)
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

        # Check ASCII report includes performance section
        text = report.format_text()
        self.assertIn("Pipeline Performance & Stage Latencies", text)
        self.assertIn("Encode Throughput:", text)
        self.assertIn("Audit Throughput:", text)

    def test_benchmark_harness_execution(self):
        """Benchmark harness must return complete dictionary of metrics."""
        result = run_benchmark(
            input_video=self.sample_video,
            preset_name="Privacy Clean",
            output_dir=self.temp_dir / "runs",
            keep_output=False,
        )

        self.assertIn("media_specs", result)
        self.assertIn("stage_latencies_sec", result)
        self.assertIn("performance", result)
        self.assertIn("quality_gate", result)

        perf = result["performance"]
        self.assertGreater(perf["encode_fps"], 0.0)
        self.assertGreater(perf["audit_fps"], 0.0)
        self.assertGreater(perf["total_latency_sec"], 0.0)
        self.assertGreater(perf["real_time_factor"], 0.0)

        qg = result["quality_gate"]
        self.assertEqual(qg["verdict"], "PASS")
        self.assertTrue(qg["tier1_policy_passed"])
        self.assertTrue(qg["tier2_fidelity_passed"])
        self.assertTrue(qg["tier3_temporal_passed"])


if __name__ == "__main__":
    unittest.main()
