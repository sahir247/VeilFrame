"""
Calibration Regression Tests
============================
Guards against silent SSIM/PSNR score drift caused by:
  - FFmpeg upgrades
  - Changes to fixture-generation filter chains
  - Transformation policy changes

Each test generates fixture pairs, measures SSIM/PSNR, and asserts that
scores have not drifted from established baselines and stay within the
VeilFrame quality gate criteria:
    SSIM >= 0.9500
    PSNR >= 30.0 dB
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parent.parent

# ── Baselines (calibrated from synthetic fixtures) ──────────────────────── #

BASELINE_SSIM_IDENTICAL        = 1.0
BASELINE_SSIM_LOW_PERTURBATION = 0.9797

BASELINE_PSNR_IDENTICAL        = 100.0   # inf -> 100 dB for exact copy
BASELINE_PSNR_LOW_PERTURBATION = 39.05

# ── Reference clip parameters ────────────────────────────────────────────── #

REF_W, REF_H, REF_FPS, REF_DUR = 320, 240, 30, 3


# ── Helpers ──────────────────────────────────────────────────────────────── #

def _ffmpeg_ok() -> bool:
    try:
        return subprocess.run(
            ["ffmpeg", "-version"], capture_output=True, timeout=5
        ).returncode == 0
    except Exception:
        return False


def _run(cmd, timeout=120):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def _generate_ref(out: Path):
    r = _run([
        "ffmpeg", "-y",
        "-f", "lavfi", "-i",
        f"testsrc2=size={REF_W}x{REF_H}:rate={REF_FPS}:duration={REF_DUR}",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={REF_DUR}",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:a", "aac", "-b:a", "96k", "-pix_fmt", "yuv420p", str(out),
    ])
    if r.returncode != 0:
        raise RuntimeError(f"ref gen failed: {r.stderr[-300:]}")


def _encode_fixture(ref: Path, out: Path, vf: str, crf: int = 18):
    r = _run([
        "ffmpeg", "-y", "-i", str(ref),
        "-vf", vf, "-c:v", "libx264", "-preset", "ultrafast",
        f"-crf", str(crf), "-c:a", "copy", "-pix_fmt", "yuv420p", str(out),
    ])
    if r.returncode != 0:
        raise RuntimeError(f"fixture encode failed: {r.stderr[-300:]}")


def _measure_ssim_psnr(ref: Path, dist: Path):
    ssim_val = psnr_val = 0.0

    # SSIM
    r_ssim = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        f"[0:v]scale={REF_W}:{REF_H}[r];[1:v]scale={REF_W}:{REF_H}[d];[d][r]ssim",
        "-f", "null", "-",
    ])
    m_ssim = re.search(r"All:(\d+\.\d+)", r_ssim.stdout + r_ssim.stderr)
    if m_ssim:
        ssim_val = float(m_ssim.group(1))

    # PSNR
    r_psnr = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        f"[0:v]scale={REF_W}:{REF_H}[r];[1:v]scale={REF_W}:{REF_H}[d];[d][r]psnr",
        "-f", "null", "-",
    ])
    m_psnr = re.search(r"average:([\d.]+|inf)", r_psnr.stdout + r_psnr.stderr)
    if m_psnr:
        val = m_psnr.group(1)
        psnr_val = 100.0 if val == "inf" else float(val)

    return ssim_val, psnr_val


# ── Test class ────────────────────────────────────────────────────────────── #

@unittest.skipUnless(_ffmpeg_ok(), "ffmpeg not on PATH")
class TestCalibrationRegression(unittest.TestCase):
    """
    Regression suite for SSIM and PSNR calibration baselines.
    """

    _tmp_dir  = None
    _ref_path = None

    @classmethod
    def setUpClass(cls):
        cls._tmp_dir = tempfile.mkdtemp(prefix="vf_calib_reg_")
        cls._ref_path = Path(cls._tmp_dir) / "ref.mp4"
        _generate_ref(cls._ref_path)

    @classmethod
    def tearDownClass(cls):
        if cls._tmp_dir:
            shutil.rmtree(cls._tmp_dir, ignore_errors=True)

    def _assert_ssim(self, measured: float, baseline: float, label: str):
        drift = abs(measured - baseline)
        self.assertLessEqual(
            drift, 0.005,
            f"{label}: SSIM drifted {drift:.5f} "
            f"(measured={measured:.5f}, baseline={baseline:.5f})"
        )

    # ── SSIM/PSNR regressions ────────────────────────────────────────────── #

    def test_ssim_identical_no_drift(self):
        """SSIM for exact copy must be 1.0 (within 0.001 for codec rounding)."""
        dist = Path(self._tmp_dir) / "dist_identical.mp4"
        shutil.copy2(str(self._ref_path), str(dist))
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self.assertGreaterEqual(
            ssim, 0.999,
            f"SSIM for IDENTICAL fixture dropped to {ssim:.5f} — unexpected."
        )

    def test_ssim_low_perturbation_above_gate(self):
        """LOW_PERTURBATION must stay above the VeilFrame SSIM gate (>= 0.95, PSNR >= 30)."""
        dist = Path(self._tmp_dir) / "dist_low_pert.mp4"
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        _encode_fixture(
            self._ref_path, dist,
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        ssim, psnr = _measure_ssim_psnr(self._ref_path, dist)
        self.assertGreaterEqual(
            ssim, 0.95,
            f"LOW_PERTURBATION SSIM={ssim:.4f} fell below gate threshold 0.95"
        )
        self.assertGreaterEqual(
            psnr, 30.0,
            f"LOW_PERTURBATION PSNR={psnr:.2f} dB fell below gate threshold 30.0"
        )

    def test_ssim_severe_below_gate(self):
        """SEVERE fixture must produce SSIM well below gate (< 0.90)."""
        dist = Path(self._tmp_dir) / "dist_severe.mp4"
        _encode_fixture(
            self._ref_path, dist,
            "gblur=sigma=4,hue=s=0.3,curves=master='0/0 0.3/0.15 1/0.7'"
        )
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self.assertLess(
            ssim, 0.90,
            f"SEVERE fixture unexpectedly produced SSIM={ssim:.4f} >= 0.90. "
            "Check fixture filter chain."
        )

    def test_ssim_baseline_low_perturbation(self):
        """SSIM baseline regression for LOW_PERTURBATION."""
        dist = Path(self._tmp_dir) / "dist_lp_baseline.mp4"
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        _encode_fixture(
            self._ref_path, dist,
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self._assert_ssim(ssim, BASELINE_SSIM_LOW_PERTURBATION,
                          "LOW_PERTURBATION SSIM baseline")


if __name__ == "__main__":
    unittest.main()
