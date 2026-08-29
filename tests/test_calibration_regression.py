"""
Calibration Regression Tests  (Phase F)
========================================
Guards against silent VMAF score drift caused by:
  - FFmpeg upgrades
  - libvmaf version changes
  - VMAF model updates
  - Changes to fixture-generation filter chains

Each test generates the exact same fixture pair as the Phase A calibration
tool, measures VMAF + SSIM/PSNR, and asserts that the score has not drifted
from the stored baseline by more than ALLOWED_DRIFT points.

The baseline values (BASELINE_VMAF_*) are intentionally left as rough ranges
rather than exact floats — they must be calibrated and filled in once the
first real VMAF run completes on a libvmaf-enabled machine.

Until then, all VMAF assertions are SKIPPED. SSIM/PSNR regression assertions
run on every CI pass.

Usage:
    # Normal CI (libvmaf optional)
    python -m pytest tests/test_calibration_regression.py -v

    # Release gate (libvmaf mandatory — SKIP == FAIL)
    VEILFRAME_REQUIRE_LIBVMAF=1 python -m pytest tests/test_calibration_regression.py -v
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

# ── Drift tolerance ────────────────────────────────────────────────────── #

ALLOWED_DRIFT = 0.5   # VMAF points — alert if score shifts by more than this

# ── Baselines (calibrated from Phase A synthetic fixtures) ─────────────── #
# Guard against silent score drift from FFmpeg/libvmaf upgrades.

BASELINE_VMAF_IDENTICAL        = 99.81   # expected: ~99.8+
BASELINE_VMAF_VERY_LOW         = 99.48   # expected: ~99.5
BASELINE_VMAF_LOW_PERTURBATION = 95.38   # expected: ~95.4 (VeilFrame typical)

# SSIM/PSNR baselines for the most-important fixtures:
BASELINE_SSIM_IDENTICAL        = 1.0
BASELINE_SSIM_LOW_PERTURBATION = 0.9797  # calibrated baseline

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


def _libvmaf_ok() -> bool:
    try:
        r = subprocess.run(["ffmpeg", "-filters"], capture_output=True,
                           text=True, timeout=8)
        return "libvmaf" in r.stdout
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


def _measure_vmaf(ref: Path, dist: Path, vmaf_json: Path) -> float:
    escaped_json = str(vmaf_json).replace("\\", "/").replace(":", "\\\\:")
    filt = (
        f"[0:v]setpts=PTS-STARTPTS[ref];"
        f"[1:v]setpts=PTS-STARTPTS[dist];"
        f"[dist][ref]libvmaf=log_fmt=json:log_path={escaped_json}"
    )
    r = _run(["ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
              "-filter_complex", filt, "-f", "null", "-"])
    if r.returncode != 0:
        raise RuntimeError(f"libvmaf: {r.stderr[-300:]}")

    with open(vmaf_json) as f:
        data = json.load(f)

    frames = data.get("frames", [])
    scores = [fr["metrics"]["vmaf"] for fr in frames
              if "vmaf" in fr.get("metrics", {})]
    if scores:
        return sum(scores) / len(scores)
    return data.get("pooled_metrics", {}).get("vmaf", {}).get("mean", 0.0)


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
    Regression suite for VMAF and SSIM/PSNR calibration baselines.
    Fails when score drifts by > ALLOWED_DRIFT from the stored baseline.
    """

    _require_vmaf = bool(os.environ.get("VEILFRAME_REQUIRE_LIBVMAF"))
    _vmaf_ok      = _libvmaf_ok()
    _tmp_dir      = None
    _ref_path     = None

    @classmethod
    def setUpClass(cls):
        cls._tmp_dir = tempfile.mkdtemp(prefix="vf_calib_reg_")
        cls._ref_path = Path(cls._tmp_dir) / "ref.mp4"
        _generate_ref(cls._ref_path)

    @classmethod
    def tearDownClass(cls):
        if cls._tmp_dir:
            shutil.rmtree(cls._tmp_dir, ignore_errors=True)

    def _vmaf_skip_or_fail(self):
        if self._require_vmaf:
            self.fail(
                "VEILFRAME_REQUIRE_LIBVMAF=1 but libvmaf is not in this FFmpeg build. "
                "Install a libvmaf-enabled FFmpeg for release gate validation."
            )
        self.skipTest("libvmaf not available — VMAF regression skipped")

    def _assert_vmaf(self, measured: float, baseline: float, label: str):
        drift = abs(measured - baseline)
        self.assertLessEqual(
            drift, ALLOWED_DRIFT,
            f"{label}: VMAF drifted {drift:.3f} points "
            f"(measured={measured:.2f}, baseline={baseline:.2f}, "
            f"allowed<={ALLOWED_DRIFT}). "
            f"Check FFmpeg / libvmaf / VMAF model version."
        )

    def _assert_ssim(self, measured: float, baseline: float, label: str):
        drift = abs(measured - baseline)
        self.assertLessEqual(
            drift, 0.005,
            f"{label}: SSIM drifted {drift:.5f} "
            f"(measured={measured:.5f}, baseline={baseline:.5f})"
        )

    # ── SSIM/PSNR regressions (always run) ──────────────────────────────── #

    def test_ssim_identical_no_drift(self):
        """SSIM for exact copy must be 1.0 (within 0.001 for codec rounding)."""
        dist = Path(self._tmp_dir) / "dist_identical.mp4"
        shutil.copy2(str(self._ref_path), str(dist))
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self.assertGreaterEqual(ssim, 0.999,
            f"SSIM for IDENTICAL fixture dropped to {ssim:.5f} — unexpected.")

    def test_ssim_low_perturbation_above_gate(self):
        """LOW_PERTURBATION must stay above the VeilFrame SSIM gate (>= 0.95)."""
        dist = Path(self._tmp_dir) / "dist_low_pert.mp4"
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        _encode_fixture(
            self._ref_path, dist,
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        ssim, psnr = _measure_ssim_psnr(self._ref_path, dist)
        self.assertGreaterEqual(ssim, 0.95,
            f"LOW_PERTURBATION SSIM={ssim:.4f} fell below gate threshold 0.95")
        self.assertGreaterEqual(psnr, 30.0,
            f"LOW_PERTURBATION PSNR={psnr:.2f} dB fell below gate threshold 30.0")

    def test_ssim_severe_below_gate(self):
        """SEVERE fixture must produce SSIM well below gate (< 0.90)."""
        dist = Path(self._tmp_dir) / "dist_severe.mp4"
        _encode_fixture(
            self._ref_path, dist,
            "gblur=sigma=4,hue=s=0.3,curves=master='0/0 0.3/0.15 1/0.7'"
        )
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self.assertLess(ssim, 0.90,
            f"SEVERE fixture unexpectedly produced SSIM={ssim:.4f} >= 0.90. "
            "Check fixture filter chain.")

    def test_ssim_baseline_low_perturbation(self):
        """SSIM baseline regression for LOW_PERTURBATION (enabled after first run)."""
        if BASELINE_SSIM_LOW_PERTURBATION is None:
            self.skipTest("BASELINE_SSIM_LOW_PERTURBATION not yet calibrated")
        dist = Path(self._tmp_dir) / "dist_lp_baseline.mp4"
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        _encode_fixture(
            self._ref_path, dist,
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        ssim, _ = _measure_ssim_psnr(self._ref_path, dist)
        self._assert_ssim(ssim, BASELINE_SSIM_LOW_PERTURBATION,
                          "LOW_PERTURBATION SSIM baseline")

    # ── VMAF regressions (skipped unless libvmaf available) ──────────────── #

    def _vmaf_fixture(self, name: str, vf: str, crf: int = 18) -> float:
        if not self._vmaf_ok:
            self._vmaf_skip_or_fail()
        dist = Path(self._tmp_dir) / f"dist_{name}.mp4"
        vmaf_j = Path(self._tmp_dir) / f"vmaf_{name}.json"
        shutil.copy2(str(self._ref_path), str(dist)) if vf == "IDENTICAL" \
            else _encode_fixture(self._ref_path, dist, vf, crf)
        return _measure_vmaf(self._ref_path, dist, vmaf_j)

    def test_vmaf_identical_near_100(self):
        """VMAF for exact copy must be >= 99.0."""
        score = self._vmaf_fixture("identical", "IDENTICAL")
        self.assertGreaterEqual(score, 99.0,
            f"VMAF for IDENTICAL fixture = {score:.2f} — expected ~100.0")

    def test_vmaf_low_perturbation_above_90(self):
        """
        VeilFrame LOW_PERTURBATION must produce VMAF >= 90.0.
        This is a minimum guard — the actual gate threshold will be
        higher once calibrated.
        """
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        score = self._vmaf_fixture(
            "low_pert",
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        self.assertGreaterEqual(score, 90.0,
            f"LOW_PERTURBATION VMAF={score:.2f} < 90.0 — fixture may be too aggressive.")

    def test_vmaf_severe_below_80(self):
        """SEVERE fixture must produce VMAF < 80.0."""
        score = self._vmaf_fixture(
            "severe",
            "gblur=sigma=4,hue=s=0.3,curves=master='0/0 0.3/0.15 1/0.7'"
        )
        self.assertLess(score, 80.0,
            f"SEVERE fixture VMAF={score:.2f} >= 80.0 — fixture may not be severe enough.")

    def test_vmaf_baseline_identical(self):
        """VMAF baseline regression for IDENTICAL (enabled after first calibrated run)."""
        if BASELINE_VMAF_IDENTICAL is None:
            self.skipTest("BASELINE_VMAF_IDENTICAL not yet calibrated")
        score = self._vmaf_fixture("identical_baseline", "IDENTICAL")
        self._assert_vmaf(score, BASELINE_VMAF_IDENTICAL, "IDENTICAL VMAF baseline")

    def test_vmaf_baseline_very_low(self):
        """VMAF baseline regression for VERY_LOW fixture."""
        if BASELINE_VMAF_VERY_LOW is None:
            self.skipTest("BASELINE_VMAF_VERY_LOW not yet calibrated")
        score = self._vmaf_fixture("very_low_baseline", "noise=alls=0.5:allf=t")
        self._assert_vmaf(score, BASELINE_VMAF_VERY_LOW, "VERY_LOW VMAF baseline")

    def test_vmaf_baseline_low_perturbation(self):
        """VMAF baseline regression for LOW_PERTURBATION — the critical VeilFrame fixture."""
        if BASELINE_VMAF_LOW_PERTURBATION is None:
            self.skipTest("BASELINE_VMAF_LOW_PERTURBATION not yet calibrated")
        sw, sh = int(REF_W * 0.998), int(REF_H * 0.998)
        score = self._vmaf_fixture(
            "lp_baseline",
            f"scale={sw}:{sh},scale={REF_W}:{REF_H},noise=alls=2:allf=t"
        )
        self._assert_vmaf(score, BASELINE_VMAF_LOW_PERTURBATION,
                          "LOW_PERTURBATION VMAF baseline")


if __name__ == "__main__":
    unittest.main()
