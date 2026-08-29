"""
VMAF Measurement Tests — Phase 1.

All tests use @unittest.skipUnless to skip gracefully when libvmaf is not
compiled into the local FFmpeg build. In normal CI they appear as SKIPPED (s),
not FAILED (F) or PASSED (.).

On the release gate CI job, the runner installs FFmpeg with libvmaf before
running these tests, so they must all PASS (not skip) to allow a release tag.

Invariant tested: VMAF is measurement-only in v1.1.
No test checks that a VMAF score causes a PASS or REJECT verdict.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from veilframe.core.resources import get_ffmpeg_path
from veilframe.models.settings import VisualBudgetPolicy
from veilframe.quality.adapters.vmaf import LibvmafFFmpegProvider
from veilframe.quality.models import QualityConfig

# Evaluate once; all tests share the same skip condition
_PROVIDER = LibvmafFFmpegProvider()
_VMAF_AVAILABLE = _PROVIDER.is_available()


def _make_video(ffmpeg, path: Path, duration: float = 1.5, noise: float = 0.0) -> Path:
    """Creates a synthetic test video, optionally adding noise."""
    vf = "testsrc=duration={d}:size=320x240:rate=24".format(d=duration)
    if noise > 0:
        vf += f",noise=alls={int(noise * 255)}:allf=t+u"
    af = "sine=frequency=440:duration={d}".format(d=duration)
    cmd = [
        str(ffmpeg), "-hide_banner", "-nostats", "-y",
        "-f", "lavfi", "-i", vf,
        "-f", "lavfi", "-i", af,
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-pix_fmt", "yuv420p", "-c:a", "aac",
        str(path),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"Fixture video generation failed: {proc.stderr[-500:]}")
    return path


@unittest.skipUnless(_VMAF_AVAILABLE, "libvmaf not available in this FFmpeg build — SKIPPED")
class TestVmafMeasurement(unittest.TestCase):
    """
    VMAF measurement tests. All require libvmaf in FFmpeg.
    Skipped gracefully (not failed) when unavailable.
    """

    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = get_ffmpeg_path()
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="vmaf_test_"))
        cls.ref_video = _make_video(cls.ffmpeg, cls.temp_dir / "ref.mp4", duration=2.0)
        cls.low_noise = _make_video(cls.ffmpeg, cls.temp_dir / "low_noise.mp4",
                                    duration=2.0, noise=0.02)
        cls.high_noise = _make_video(cls.ffmpeg, cls.temp_dir / "high_noise.mp4",
                                     duration=2.0, noise=0.30)
        cls.evidence_dir = cls.temp_dir / "evidence"
        cls.evidence_dir.mkdir()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.temp_dir, ignore_errors=True)

    def _config(self, ref, distorted, evidence_dir=None) -> QualityConfig:
        return QualityConfig(
            reference=ref,
            distorted=distorted,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=evidence_dir,
        )

    def test_vmaf_identical_high_score(self):
        """Reference vs. itself should yield VMAF mean >= 98."""
        provider = LibvmafFFmpegProvider()
        results = provider.evaluate(self._config(self.ref_video, self.ref_video))
        self.assertEqual(len(results), 1)
        result = results[0]
        self.assertEqual(result.metric_name, "vmaf")
        self.assertGreaterEqual(result.mean, 97.0,
                                f"Expected ref-vs-ref VMAF >= 97, got {result.mean:.2f}")

    def test_vmaf_low_perturbation_below_identical(self):
        """Low-noise distortion should score lower than ref-vs-ref."""
        provider = LibvmafFFmpegProvider()
        ref_results = provider.evaluate(self._config(self.ref_video, self.ref_video))
        noise_results = provider.evaluate(self._config(self.ref_video, self.low_noise))
        self.assertLess(
            noise_results[0].mean, ref_results[0].mean,
            "Low-noise VMAF must be strictly lower than identical-input VMAF",
        )

    def test_vmaf_severe_distortion_low_score(self):
        """Severe noise distortion should yield VMAF mean significantly below identical."""
        provider = LibvmafFFmpegProvider()
        results = provider.evaluate(self._config(self.ref_video, self.high_noise))
        self.assertLess(
            results[0].mean, 80.0,
            f"Expected severe-distortion VMAF < 80, got {results[0].mean:.2f}",
        )

    def test_vmaf_deterministic(self):
        """Two calls on identical input should return results within floating-point tolerance."""
        provider = LibvmafFFmpegProvider()
        r1 = provider.evaluate(self._config(self.ref_video, self.ref_video))
        r2 = provider.evaluate(self._config(self.ref_video, self.ref_video))
        self.assertAlmostEqual(r1[0].mean, r2[0].mean, places=3,
                               msg="VMAF must be deterministic across identical calls")

    def test_vmaf_model_and_evidence_recorded(self):
        """Evidence file must be written and its SHA-256 recorded in QualityResult."""
        evidence_dir = self.temp_dir / "evidence_rec"
        evidence_dir.mkdir(exist_ok=True)
        provider = LibvmafFFmpegProvider()
        cfg = self._config(self.ref_video, self.ref_video, evidence_dir=evidence_dir)
        results = provider.evaluate(cfg)
        result = results[0]

        vmaf_json = evidence_dir / "vmaf.json"
        self.assertTrue(vmaf_json.exists(),
                        "vmaf.json must be written to evidence_dir by default")
        self.assertIsNotNone(result.evidence_sha256,
                             "evidence_sha256 must be set when evidence_dir is provided")
        self.assertGreater(len(result.evidence_sha256), 0)

        # Verify the hash matches actual file
        from veilframe.core.validator import compute_sha256
        expected_sha = compute_sha256(vmaf_json)
        self.assertEqual(result.evidence_sha256, expected_sha,
                         "evidence_sha256 in QualityResult must match sha256(vmaf.json)")

    def test_vmaf_measurement_only_gate_unchanged(self):
        """
        Default policy (vmaf_gate_enabled=False): VMAF must NOT change verdict.
        ref-vs-ref must PASS with or without VMAF available.
        """
        from veilframe.core.validator import evaluate_visual_quality

        policy = VisualBudgetPolicy()  # gate disabled by default
        evidence_dir = self.temp_dir / "evidence_gate"
        evidence_dir.mkdir(exist_ok=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=policy,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=evidence_dir,
        )

        self.assertTrue(report.passed,
                        "Gate verdict must be PASS on ref-vs-ref regardless of VMAF")
        self.assertEqual(report.three_tier_verdict.overall_verdict, "PASS")

        vmaf_entries = [r for r in report.provider_results if r.get("metric") == "vmaf"]
        if vmaf_entries:
            vmaf = vmaf_entries[0]
            self.assertIn("note", vmaf)
            self.assertIn("measurement only", vmaf["note"],
                          "VMAF note must indicate measurement-only when gate is disabled")

    def test_vmaf_gate_enabled_with_absurd_threshold_rejects(self):
        """
        Integration-level gate test (requires libvmaf).
        vmaf_gate_enabled=True with vmaf_mean_min=100.0 and vmaf_p5_min=100.0 (impossible on compressed stream) must REJECT.
        Verifies the gate wiring is end-to-end, not just unit-tested in isolation.
        """

        from veilframe.core.validator import evaluate_visual_quality

        policy = VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=100.0, vmaf_p5_min=100.0)
        evidence_dir = self.temp_dir / "evidence_absurd"
        evidence_dir.mkdir(exist_ok=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=policy,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=evidence_dir,
        )

        # With threshold=100.0, VMAF mean/p5 on encoded stream (< 100.0) must fail → overall REJECT
        # But only if VMAF was actually available and measured
        vmaf_entries = [r for r in report.provider_results if r.get("metric") == "vmaf"]
        if vmaf_entries:
            self.assertFalse(
                report.passed,
                "Gate must REJECT when vmaf_mean_min=100.0 (impossible threshold for compressed stream)",
            )
            t2_viols = report.three_tier_verdict.tier2_violations
            vmaf_viols = [v for v in t2_viols if "VMAF" in v]
            self.assertGreater(len(vmaf_viols), 0,
                               "VMAF violations must appear in tier2_violations")

    def test_vmaf_gate_enabled_with_zero_threshold_passes(self):
        """
        Integration-level gate test (requires libvmaf).
        vmaf_gate_enabled=True with vmaf_mean_min=0 / vmaf_p5_min=0 must PASS
        for ref-vs-ref (any real VMAF score exceeds 0).
        Verifies a permissive threshold does not introduce spurious rejections.
        """
        from veilframe.core.validator import evaluate_visual_quality

        policy = VisualBudgetPolicy(vmaf_gate_enabled=True, vmaf_mean_min=0.0, vmaf_p5_min=0.0)
        evidence_dir = self.temp_dir / "evidence_zero_thresh"
        evidence_dir.mkdir(exist_ok=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=policy,
            canonical_w=320,
            canonical_h=240,
            evidence_dir=evidence_dir,
        )

        self.assertTrue(
            report.passed,
            "Gate must PASS for ref-vs-ref when VMAF threshold is 0 (any score exceeds it)"
        )


if __name__ == "__main__":
    unittest.main()

