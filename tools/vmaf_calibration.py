#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys, io
# Force UTF-8 on Windows terminals that default to cp1252
if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
"""
VeilFrame VMAF Calibration Laboratory  (Phase A)
=================================================
Generates 8 synthetic distortion fixtures on a severity axis, measures the
full perceptual metric distribution for each, and produces a calibration report
that informs whether a VMAF gate threshold is scientifically justified.

Fixture severity axis:
    IDENTICAL            — ref vs exact copy                (upper bound)
    VERY_LOW             — σ=0.5 imperceptible noise
    LOW_PERTURBATION     — σ=2 + 99.8% scale               (VeilFrame typical)
    MODERATE             — σ=8 + slight blur                (near policy edge)
    MODERATE_EXCEEDANCE  — 10% crop + moderate blur         (exceeds policy)
    HIGH                 — heavy HF noise + compression
    SEVERE               — heavy blur + colour degradation
    EXTREME              — near-total distortion

Per fixture, records:
    VMAF  → mean, median, P1, P5, P25, P75, P95, worst (min), std dev
    ADM2  → mean
    VIF   → mean per octave scale 0–3
    SSIM  → mean
    PSNR  → mean

Calibration metadata block:
    FFmpeg version, libvmaf version, VMAF model name + SHA-256,
    fixture generator version, fixture parameters, timestamp.

Output:
    vmaf_calibration_results.json  — full metric corpus
    stdout                         — ASCII table + threshold analysis

Usage:
    python tools/vmaf_calibration.py [--ref PATH] [--duration SEC] [--out JSON]
    python tools/vmaf_calibration.py --keep-fixtures --duration 10

Requirements:
    - FFmpeg with libvmaf support (libvmaf >= 2.x, HD model recommended)
    - Python 3.9+  (stdlib only — no third-party packages)
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from veilframe.quality.vmaf_models import (
    VMAF_MODEL_VERSION,
    VmafModelSpec,
    VmafModelError,
    select_vmaf_model,
    resolve_and_verify_model,
    format_ffmpeg_filter_path,
    format_vmaf_model_filter_arg,
)
from veilframe.core.crypto import compute_sha256

# ── Version ────────────────────────────────────────────────────────────── #

CALIBRATION_TOOL_VERSION = "1.1.0"

# ── Fixture definitions ────────────────────────────────────────────────── #

FIXTURE_SEVERITY_AXIS = [
    "IDENTICAL",
    "VERY_LOW",
    "LOW_PERTURBATION",
    "MODERATE",
    "MODERATE_EXCEEDANCE",
    "HIGH",
    "SEVERE",
    "EXTREME",
]

ACCEPTABLE   = ["IDENTICAL", "VERY_LOW", "LOW_PERTURBATION"]
BOUNDARY     = ["MODERATE", "MODERATE_EXCEEDANCE"]
UNACCEPTABLE = ["HIGH", "SEVERE", "EXTREME"]

FIXTURE_DESCRIPTIONS = {
    "IDENTICAL":            "Ref vs exact copy — absolute upper bound (expect ~100)",
    "VERY_LOW":             "σ=0.5 noise — theoretically imperceptible",
    "LOW_PERTURBATION":     "σ=2 + 99.8% scale — VeilFrame privacy typical",
    "MODERATE":             "σ=8 + slight blur (σ=0.8) — near policy budget edge",
    "MODERATE_EXCEEDANCE":  "10% crop + moderate blur (σ=1.5) — exceeds policy",
    "HIGH":                 "σ=18 HF noise + CRF=40 recompression artefacts",
    "SEVERE":               "Heavy blur (σ=4) + colour degradation (sat=0.3)",
    "EXTREME":              "Near-total distortion: severe blur + posterisation",
}

FIXTURE_PARAMS = {
    "IDENTICAL":            {},
    "VERY_LOW":             {"noise_sigma": 0.5},
    "LOW_PERTURBATION":     {"noise_sigma": 2, "scale_factor": 0.998},
    "MODERATE":             {"noise_sigma": 8, "blur_sigma": 0.8},
    "MODERATE_EXCEEDANCE":  {"crop_pct": 0.10, "blur_sigma": 1.5},
    "HIGH":                 {"noise_sigma": 18, "crf": 40},
    "SEVERE":               {"blur_sigma": 4.0, "saturation": 0.3},
    "EXTREME":              {"blur_sigma": 8.0, "posterize_bits": 3},
}

# ── Default reference clip parameters (1080p SDR 30fps) ─────────────────── #

DEFAULT_REF_W, DEFAULT_REF_H, DEFAULT_REF_FPS = 1920, 1080, 30

# ── Existing VeilFrame gate thresholds (for cross-reference) ───────────── #

GATE_SSIM = 0.95
GATE_PSNR = 30.0


# ── Data models ────────────────────────────────────────────────────────── #

@dataclass
class PercentileStats:
    mean:   float = 0.0
    median: float = 0.0
    p1:     float = 0.0
    p5:     float = 0.0
    p25:    float = 0.0
    p75:    float = 0.0
    p95:    float = 0.0
    worst:  float = 0.0   # min (worst-case frame)
    std_dev: float = 0.0
    frame_count: int = 0

    @classmethod
    def from_list(cls, values: List[float]) -> "PercentileStats":
        if not values:
            return cls()
        s = sorted(values)
        n = len(s)

        def pct(p: float) -> float:
            idx = max(0, int(n * p / 100) - 1)
            return s[idx]

        return cls(
            mean=statistics.mean(values),
            median=statistics.median(values),
            p1=pct(1),
            p5=pct(5),
            p25=pct(25),
            p75=pct(75),
            p95=pct(95),
            worst=min(values),
            std_dev=statistics.stdev(values) if n > 1 else 0.0,
            frame_count=n,
        )


@dataclass
class VmafDistribution:
    vmaf:        PercentileStats = field(default_factory=PercentileStats)
    adm2_mean:   float = 0.0
    vif_scale0:  float = 0.0
    vif_scale1:  float = 0.0
    vif_scale2:  float = 0.0
    vif_scale3:  float = 0.0


@dataclass
class FidelityMetrics:
    ssim_mean: float = 0.0
    psnr_mean: float = 0.0


@dataclass
class FixtureResult:
    fixture:     str = ""
    description: str = ""
    params:      Dict = field(default_factory=dict)
    vmaf:        VmafDistribution = field(default_factory=VmafDistribution)
    fidelity:    FidelityMetrics  = field(default_factory=FidelityMetrics)
    vmaf_available: bool = False
    error:       Optional[str] = None


@dataclass
class CalibrationMetadata:
    tool_version:       str = CALIBRATION_TOOL_VERSION
    timestamp_utc:      str = ""
    ffmpeg_version:     str = ""
    libvmaf_version:    str = ""
    vmaf_model_version: str = VMAF_MODEL_VERSION
    vmaf_model_name:    str = ""
    vmaf_model_sha256:  str = ""
    reference_clip:     str = ""
    reference_sha256:   str = ""
    duration_sec:       int = 0
    resolution:         str = ""
    fps:                float = DEFAULT_REF_FPS


# ── FFmpeg helpers ─────────────────────────────────────────────────────── #

def _run(cmd: List[str], timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def ffmpeg_ok() -> bool:
    try:
        return _run(["ffmpeg", "-version"]).returncode == 0
    except Exception:
        return False


def libvmaf_available() -> bool:
    try:
        return "libvmaf" in _run(["ffmpeg", "-filters"]).stdout
    except Exception:
        return False


def get_ffmpeg_version() -> str:
    try:
        r = _run(["ffmpeg", "-version"])
        for line in r.stdout.splitlines():
            if line.startswith("ffmpeg version"):
                return line.split()[2]
    except Exception:
        pass
    return "unknown"


def get_libvmaf_version() -> str:
    """Parse libvmaf version from ffmpeg -version output."""
    try:
        r = _run(["ffmpeg", "-version"])
        m = re.search(r"libvmaf\s+([\d.]+)", r.stdout)
        if m:
            return m.group(1)
        # Also try stderr
        m = re.search(r"libvmaf\s+([\d.]+)", r.stderr)
        if m:
            return m.group(1)
    except Exception:
        pass
    return "unknown"


def check_monotonicity_diagnostics(results: List[FixtureResult]) -> List[str]:
    """
    Performs diagnostic monotonicity validation across fixture severity axis.
    Reports ordering and any non-monotonic inversions without brittle failure.
    Enforces gross sanity check between extremes (IDENTICAL vs EXTREME).
    """
    diagnostics = []
    vmaf_scores = {r.fixture: r.vmaf.vmaf.mean for r in results if r.vmaf_available and r.vmaf.vmaf.frame_count > 0}
    if len(vmaf_scores) < 2:
        return ["Diagnostic check skipped: insufficient VMAF fixture scores."]

    prev_name = None
    prev_score = None
    for name in FIXTURE_SEVERITY_AXIS:
        if name not in vmaf_scores:
            continue
        score = vmaf_scores[name]
        if prev_score is not None:
            if score > prev_score:
                diagnostics.append(f"Inversion noted: {name} ({score:.2f}) > {prev_name} ({prev_score:.2f})")
            else:
                diagnostics.append(f"Monotonic decrease: {prev_name} ({prev_score:.2f}) >= {name} ({score:.2f})")
        prev_name = name
        prev_score = score

    # Gross sanity check
    if "IDENTICAL" in vmaf_scores and "EXTREME" in vmaf_scores:
        ident = vmaf_scores["IDENTICAL"]
        extreme = vmaf_scores["EXTREME"]
        if ident < 90.0:
            diagnostics.append(f"WARNING: IDENTICAL VMAF ({ident:.2f}) is unusually low (< 90.0).")
        if extreme > 60.0:
            diagnostics.append(f"WARNING: EXTREME VMAF ({extreme:.2f}) is unusually high (> 60.0).")
        if ident <= extreme:
            raise RuntimeError(f"FATAL: Metric inversion between extremes: IDENTICAL ({ident:.2f}) <= EXTREME ({extreme:.2f})")

    return diagnostics


# ── Reference clip generation ──────────────────────────────────────────── #

def generate_reference(out: Path, duration: int, width: int = DEFAULT_REF_W, height: int = DEFAULT_REF_H, fps: int = DEFAULT_REF_FPS):
    """Generate a varied synthetic reference clip using testsrc2."""
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i",
        f"testsrc2=size={width}x{height}:rate={fps}:duration={duration}",
        "-f", "lavfi", "-i",
        f"sine=frequency=440:sample_rate=48000:duration={duration}",
        "-c:v", "libx264", "-preset", "fast", "-crf", "18",
        "-c:a", "aac", "-b:a", "128k",
        "-pix_fmt", "yuv420p", str(out),
    ]
    r = _run(cmd, timeout=90)
    if r.returncode != 0:
        raise RuntimeError(f"Reference clip generation failed:\n{r.stderr[-600:]}")


# -- Fixture builders ----------------------------------------------------- #

def _encode(ref: Path, out: Path, vf: str, crf: int = 18, extra: List[str] = None):
    cmd = [
        "ffmpeg", "-y", "-i", str(ref),
        "-vf", vf,
        "-c:v", "libx264", "-preset", "fast", f"-crf", str(crf),
        "-c:a", "copy", "-pix_fmt", "yuv420p",
    ]
    if extra:
        cmd += extra
    cmd.append(str(out))
    r = _run(cmd, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"Fixture encode failed [{out.name}]:\n{r.stderr[-600:]}")


def build_fixture(name: str, ref: Path, out: Path, width: int = DEFAULT_REF_W, height: int = DEFAULT_REF_H):
    if name == "IDENTICAL":
        # Re-encode at lossless-ish quality so the SSIM/PSNR filter runs correctly
        # (direct copy bypasses lavfi comparison in some FFmpeg builds)
        _encode(ref, out, f"scale={width}:{height}", crf=0)

    elif name == "VERY_LOW":
        _encode(ref, out, f"scale={width}:{height},noise=alls=0.5:allf=t")

    elif name == "LOW_PERTURBATION":
        sw = int(width * 0.998)
        sh = int(height * 0.998)
        _encode(ref, out, f"scale={sw}:{sh},scale={width}:{height},noise=alls=2:allf=t")

    elif name == "MODERATE":
        _encode(ref, out, f"scale={width}:{height},noise=alls=8:allf=t,gblur=sigma=0.8")

    elif name == "MODERATE_EXCEEDANCE":
        cw = int(width * 0.90)
        ch = int(height * 0.90)
        _encode(ref, out, f"crop={cw}:{ch},scale={width}:{height},gblur=sigma=1.5")

    elif name == "HIGH":
        # Two noise passes: temporal then uniform -- allf=t+g is not portable
        _encode(ref, out,
            f"scale={width}:{height},noise=alls=12:allf=t,noise=alls=12:allf=u",
            crf=40)

    elif name == "SEVERE":
        _encode(ref, out,
            f"scale={width}:{height},gblur=sigma=4,hue=s=0.3,"
            "curves=master='0/0 0.3/0.15 1/0.7'")

    elif name == "EXTREME":
        # Near-total distortion: max blur + colour crush + heavy recompression
        # (geq not portable to older FFmpeg builds)
        _encode(ref, out,
            f"scale={width}:{height},gblur=sigma=8,"
            "hue=s=0.05,curves=master='0/0 1/0.35'",
            crf=51)


    else:
        raise ValueError(f"Unknown fixture: {name}")


# ── Measurement ────────────────────────────────────────────────────────── #

def _percentile_from_list(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = max(0, int(len(s) * p / 100) - 1)
    return s[idx]


def measure_vmaf(ref: Path, dist: Path, vmaf_json: Path, model_path: Path) -> VmafDistribution:
    """Run FFmpeg libvmaf filter with explicit verified model path and parse results."""
    escaped_json = format_ffmpeg_filter_path(vmaf_json)
    model_arg = format_vmaf_model_filter_arg(model_path)
    filt = (
        f"[0:v]setpts=PTS-STARTPTS[dist];"
        f"[1:v]setpts=PTS-STARTPTS[ref];"
        f"[dist][ref]libvmaf="
        f"{model_arg}:"
        f"log_fmt=json:log_path='{escaped_json}':"
        f"feature='name=adm|name=vif'"
    )
    r = _run(
        ["ffmpeg", "-y", "-i", str(dist), "-i", str(ref),
         "-filter_complex", filt, "-f", "null", "-"],
        timeout=300,
    )
    if r.returncode != 0:
        raise RuntimeError(f"libvmaf failed:\n{r.stderr[-800:]}")

    with open(vmaf_json) as f:
        data = json.load(f)

    frames = data.get("frames", [])
    pooled = data.get("pooled_metrics", {})

    def frame_vals(key: str) -> List[float]:
        return [fr["metrics"][key] for fr in frames if key in fr.get("metrics", {})]

    def pool_mean(key: str) -> float:
        return pooled.get(key, {}).get("mean", 0.0)

    vmaf_vals = frame_vals("vmaf")
    dist = VmafDistribution(
        vmaf=PercentileStats.from_list(vmaf_vals) if vmaf_vals else PercentileStats(
            mean=pool_mean("vmaf"),
        ),
        adm2_mean=pool_mean("adm2") or (
            statistics.mean(frame_vals("adm2")) if frame_vals("adm2") else 0.0
        ),
        vif_scale0=pool_mean("vif_scale0") or (
            statistics.mean(frame_vals("vif_scale0")) if frame_vals("vif_scale0") else 0.0
        ),
        vif_scale1=pool_mean("vif_scale1") or (
            statistics.mean(frame_vals("vif_scale1")) if frame_vals("vif_scale1") else 0.0
        ),
        vif_scale2=pool_mean("vif_scale2") or (
            statistics.mean(frame_vals("vif_scale2")) if frame_vals("vif_scale2") else 0.0
        ),
        vif_scale3=pool_mean("vif_scale3") or (
            statistics.mean(frame_vals("vif_scale3")) if frame_vals("vif_scale3") else 0.0
        ),
    )
    return dist


def measure_ssim_psnr(ref: Path, dist: Path) -> FidelityMetrics:
    """
    Measure SSIM and PSNR via ffmpeg lavfi.
    Uses two separate filter-complex calls for maximum FFmpeg version compat
    (the bundled binary is an older build that doesn't support named-pad graphs
    for ssim/psnr reliably).
    """
    ssim_val = psnr_val = 0.0

    # SSIM
    r_ssim = _run(
        ["ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
         "-filter_complex", "[0:v][1:v]ssim",
         "-f", "null", "-"],
        timeout=120,
    )
    m = re.search(r"All:([\d.]+)", r_ssim.stdout + r_ssim.stderr)
    if m:
        ssim_val = float(m.group(1))

    # PSNR
    r_psnr = _run(
        ["ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
         "-filter_complex", "[0:v][1:v]psnr",
         "-f", "null", "-"],
        timeout=120,
    )
    m = re.search(r"average:([\d.]+|inf)", r_psnr.stdout + r_psnr.stderr)
    if m:
        val = m.group(1)
        psnr_val = 100.0 if val == "inf" else float(val)

    return FidelityMetrics(ssim_mean=ssim_val, psnr_mean=psnr_val)



# ── Calibration run ────────────────────────────────────────────────────── #

def run_calibration(
    ref: Path,
    tmp: Path,
    vmaf_ok: bool,
    model_path: Optional[Path] = None,
    width: int = DEFAULT_REF_W,
    height: int = DEFAULT_REF_H,
) -> List[FixtureResult]:
    results = []
    for name in FIXTURE_SEVERITY_AXIS:
        print(f"  [{name}] Building fixture…", flush=True)
        dist = tmp / f"dist_{name.lower()}.mp4"
        vmaf_json = tmp / f"vmaf_{name.lower()}.json"
        res = FixtureResult(
            fixture=name,
            description=FIXTURE_DESCRIPTIONS[name],
            params=FIXTURE_PARAMS[name],
            vmaf_available=vmaf_ok and bool(model_path),
        )
        try:
            build_fixture(name, ref, dist, width=width, height=height)
        except Exception as e:
            res.error = f"Fixture build: {e}"
            results.append(res)
            print(f"    ✗ {e}", flush=True)
            continue

        try:
            res.fidelity = measure_ssim_psnr(ref, dist)
        except Exception as e:
            res.error = (res.error or "") + f" | SSIM/PSNR: {e}"

        if vmaf_ok and model_path:
            try:
                print(f"  [{name}] Measuring VMAF + ADM2 + VIF…", flush=True)
                res.vmaf = measure_vmaf(ref, dist, vmaf_json, model_path=model_path)
            except Exception as e:
                res.error = (res.error or "") + f" | VMAF: {e}"
                print(f"    ✗ VMAF: {e}", flush=True)

        print(
            f"  [{name}]  "
            f"VMAF={res.vmaf.vmaf.mean:6.2f}  "
            f"P5={res.vmaf.vmaf.p5:6.2f}  "
            f"worst={res.vmaf.vmaf.worst:6.2f}  "
            f"SSIM={res.fidelity.ssim_mean:.4f}  "
            f"PSNR={res.fidelity.psnr_mean:.2f}dB",
            flush=True,
        )
        results.append(res)
    return results


# ── ASCII report ───────────────────────────────────────────────────────── #

COL_W = 24

def _bar(value: float, max_val: float = 100.0, width: int = 20) -> str:
    filled = int(round(value / max_val * width))
    return "#" * filled + "." * (width - filled)


def print_ascii_table(results: List[FixtureResult], vmaf_ok: bool):
    print()
    print("=" * 105)
    print("  VeilFrame VMAF Calibration  --  Phase A Synthetic Fixtures")
    print("=" * 105)

    if vmaf_ok:
        hdr = (
            f"{'FIXTURE':<22}  "
            f"{'mean':>7} {'P5':>7} {'worst':>7} {'stddev':>7}  "
            f"{'ADM2':>7} {'VIF0':>6}  "
            f"{'SSIM':>7} {'PSNR':>8}  "
            f"GATE(current)   BAR"
        )
    else:
        hdr = (
            f"{'FIXTURE':<22}  "
            f"{'VMAF':>7}  "
            f"{'SSIM':>7} {'PSNR':>8}  "
            f"GATE(current)"
        )

    print(hdr)
    print("-" * len(hdr))

    for r in results:
        ssim_ok = r.fidelity.ssim_mean >= GATE_SSIM
        psnr_ok = r.fidelity.psnr_mean >= GATE_PSNR
        gate = f"SSIM{'✓' if ssim_ok else '✗'} PSNR{'✓' if psnr_ok else '✗'}"

        if vmaf_ok and r.vmaf.vmaf.frame_count > 0:
            bar = _bar(r.vmaf.vmaf.mean)
            print(
                f"  {r.fixture:<20}  "
                f"{r.vmaf.vmaf.mean:>7.2f} "
                f"{r.vmaf.vmaf.p5:>7.2f} "
                f"{r.vmaf.vmaf.worst:>7.2f} "
                f"{r.vmaf.vmaf.std_dev:>7.2f}  "
                f"{r.vmaf.adm2_mean:>7.4f} "
                f"{r.vmaf.vif_scale0:>6.4f}  "
                f"{r.fidelity.ssim_mean:>7.4f} "
                f"{r.fidelity.psnr_mean:>7.2f}dB  "
                f"{gate:<16}  {bar}"
            )
        elif r.error and not r.fidelity.ssim_mean:
            print(f"  {r.fixture:<20}  ERROR: {r.error}")
        else:
            print(
                f"  {r.fixture:<20}  "
                f"{'SKIP':>7}  "
                f"{r.fidelity.ssim_mean:>7.4f} "
                f"{r.fidelity.psnr_mean:>7.2f}dB  "
                f"{gate}"
            )

    print("-" * len(hdr))
    if not vmaf_ok:
        print()
        print("  VMAF columns SKIPPED -- libvmaf not in this FFmpeg build.")
        print("  Install a libvmaf-enabled FFmpeg and re-run to collect VMAF data.")
    print()


def print_threshold_analysis(results: List[FixtureResult]):
    """
    Compute candidate threshold and false-accept / false-reject risk.

    Acceptable fixtures (should PASS gate): IDENTICAL, VERY_LOW, LOW_PERTURBATION
    Unacceptable fixtures (should FAIL gate): HIGH, SEVERE, EXTREME
    Boundary fixtures (MODERATE, MODERATE_EXCEEDANCE): inform the gap.
    """
    vmaf_by = {r.fixture: r.vmaf.vmaf for r in results if r.vmaf.vmaf.frame_count > 0}
    if not vmaf_by:
        print("  Threshold analysis requires libvmaf — skipped.")
        return

    ACCEPTABLE   = ["IDENTICAL", "VERY_LOW", "LOW_PERTURBATION"]
    UNACCEPTABLE = ["HIGH", "SEVERE", "EXTREME"]
    BOUNDARY     = ["MODERATE", "MODERATE_EXCEEDANCE"]

    def safe_get(name: str, attr: str) -> Optional[float]:
        s = vmaf_by.get(name)
        return getattr(s, attr, None) if s else None

    print("=" * 80)
    print("  Threshold Analysis  (synthetic fixtures only -- validate with real corpus)")
    print("=" * 80)

    print()
    print(f"  {'Fixture':<22}  {'Mean':>7}  {'P5':>7}  {'Worst':>7}  Role")
    print(f"  {'-'*22}  {'-'*7}  {'-'*7}  {'-'*7}  -------------")

    for name in FIXTURE_SEVERITY_AXIS:
        s = vmaf_by.get(name)
        if not s:
            continue
        role = (
            "✓ ACCEPTABLE"   if name in ACCEPTABLE   else
            "— BOUNDARY"     if name in BOUNDARY     else
            "✗ UNACCEPTABLE"
        )
        print(f"  {name:<22}  {s.mean:>7.2f}  {s.p5:>7.2f}  {s.worst:>7.2f}  {role}")

    print()

    # Compute candidate threshold
    accept_means  = [vmaf_by[n].mean  for n in ACCEPTABLE  if n in vmaf_by]
    accept_p5s    = [vmaf_by[n].p5    for n in ACCEPTABLE  if n in vmaf_by]
    accept_worsts = [vmaf_by[n].worst for n in ACCEPTABLE  if n in vmaf_by]
    reject_means  = [vmaf_by[n].mean  for n in UNACCEPTABLE if n in vmaf_by]

    if not accept_means or not reject_means:
        print("  Insufficient data for threshold computation.")
        return

    # Conservative: 5% below worst acceptable
    margin = 0.05
    cand_mean  = min(accept_means)  * (1 - margin)
    cand_p5    = min(accept_p5s)    * (1 - margin)
    cand_worst = min(accept_worsts) * (1 - margin)

    # Separation check
    lowest_acceptable = min(accept_means)
    highest_reject    = max(reject_means)
    separation        = lowest_acceptable - highest_reject

    print(f"  Candidate threshold (synthetic, 5% margin from worst acceptable):")
    print(f"    vmaf_mean_min  = {cand_mean:.1f}")
    print(f"    vmaf_p5_min    = {cand_p5:.1f}")
    print(f"    vmaf_worst_min = {cand_worst:.1f}")
    print()
    print(f"  Separation (lowest acceptable − highest unacceptable):")
    print(f"    Lowest acceptable mean:   {lowest_acceptable:.2f}")
    print(f"    Highest unacceptable mean:{highest_reject:.2f}")
    print(f"    Gap:                      {separation:.2f} points")
    print()

    if separation >= 10:
        verdict = "✓ CLEAR — separation justifies a hard gate threshold."
        risk = "Low false-accept risk (unacceptable fixtures well below threshold)."
    elif separation >= 5:
        verdict = "~ MODERATE — threshold feasible; validate with real-world corpus first."
        risk = "Moderate risk — boundary fixtures may require per-content tuning."
    else:
        verdict = "✗ INSUFFICIENT — do NOT promote VMAF to gate yet."
        risk = "High risk of false accepts or false rejects. Extend corpus first."

    print(f"  Corpus separation verdict:  {verdict}")
    print(f"  Risk assessment:            {risk}")
    print()
    print("  Next step:  Run Phase B (real-content corpus) before setting final thresholds.")
    print("  See ROADMAP.md Phase B for corpus structure.")
    print()
    print("=" * 80)


# ── Entry point ────────────────────────────────────────────────────────── #

def main():
    parser = argparse.ArgumentParser(
        description="VeilFrame VMAF Calibration Laboratory — Phase A (VMAF v1.0.16)"
    )
    parser.add_argument("--ref",      type=Path, default=None,
        help="Existing reference video. If omitted, synthetic clip is generated.")
    parser.add_argument("--duration", type=int,  default=5,
        help="Duration (sec) of synthetic reference clip (default: 5)")
    parser.add_argument("--width",    type=int,  default=DEFAULT_REF_W,
        help=f"Width of reference clip (default: {DEFAULT_REF_W})")
    parser.add_argument("--height",   type=int,  default=DEFAULT_REF_H,
        help=f"Height of reference clip (default: {DEFAULT_REF_H})")
    parser.add_argument("--fps",      type=float,default=float(DEFAULT_REF_FPS),
        help=f"Frame rate of reference clip (default: {DEFAULT_REF_FPS})")
    parser.add_argument("--model-root", type=Path, default=None,
        help="Custom VMAF model root directory (overrides $env:VMAF_MODEL_ROOT)")
    parser.add_argument("--out",      type=Path,
        default=Path("vmaf_calibration_results.json"),
        help="Output JSON path")
    parser.add_argument("--keep-fixtures", action="store_true",
        help="Keep distortion fixture files after measurement")
    args = parser.parse_args()

    print()
    print("VeilFrame VMAF Calibration Laboratory  v" + CALIBRATION_TOOL_VERSION)
    print("=" * 65)

    if not ffmpeg_ok():
        print("ERROR: ffmpeg not found on PATH.", file=sys.stderr)
        sys.exit(1)

    ffmpeg_ver  = get_ffmpeg_version()
    vmaf_ok     = libvmaf_available()
    libvmaf_ver = get_libvmaf_version() if vmaf_ok else "n/a"

    resolved_model_path: Optional[Path] = None
    model_name = "n/a"
    model_sha = "n/a"
    model_spec = None

    if vmaf_ok:
        try:
            model_spec = select_vmaf_model(args.width, args.height, args.fps, is_hdr=False)
            resolved_model_path = resolve_and_verify_model(model_spec, model_root=args.model_root)
            model_name = model_spec.filename
            model_sha = model_spec.expected_sha256
        except VmafModelError as exc:
            print(f"ERROR: VMAF model setup failed: {exc}", file=sys.stderr)
            sys.exit(1)

    print(f"  FFmpeg:           {ffmpeg_ver}")
    print(f"  libvmaf:          {'[OK] ' + libvmaf_ver if vmaf_ok else '[--] not in this build'}")
    if vmaf_ok and model_spec:
        print(f"  VMAF model ver:   {model_spec.version}")
        print(f"  VMAF model:       {model_name}")
        print(f"  Model SHA-256:    {model_sha}")
        print(f"  Model path:       {resolved_model_path}")
    print()

    meta = CalibrationMetadata(
        timestamp_utc    = datetime.datetime.utcnow().isoformat() + "Z",
        ffmpeg_version   = ffmpeg_ver,
        libvmaf_version  = libvmaf_ver,
        vmaf_model_version = VMAF_MODEL_VERSION,
        vmaf_model_name  = model_name,
        vmaf_model_sha256= model_sha,
        duration_sec     = args.duration,
        resolution       = f"{args.width}x{args.height}",
        fps              = args.fps,
    )

    with tempfile.TemporaryDirectory(prefix="vf_calib_") as tmp_str:
        tmp = Path(tmp_str)

        if args.ref and args.ref.exists():
            ref = args.ref
            meta.reference_clip = str(ref)
            meta.reference_sha256 = compute_sha256(ref)
            print(f"  Reference: {ref.name}  ({ref.stat().st_size // 1024} KB)")
        else:
            ref = tmp / "reference.mp4"
            print(f"  Generating {args.duration}s synthetic reference ({args.width}x{args.height} @ {args.fps}fps)…")
            generate_reference(ref, args.duration, width=args.width, height=args.height, fps=int(args.fps))
            meta.reference_clip = f"synthetic (testsrc2 {args.width}x{args.height})"
            meta.reference_sha256 = compute_sha256(ref)
            print(f"  Reference: {ref.name}  ({ref.stat().st_size // 1024} KB)")

        print()
        print("  Running fixtures…")
        results = run_calibration(
            ref, tmp, vmaf_ok,
            model_path=resolved_model_path,
            width=args.width,
            height=args.height,
        )

        if args.keep_fixtures:
            fix_dir = args.out.parent / "vmaf_fixtures"
            fix_dir.mkdir(exist_ok=True)
            for name in FIXTURE_SEVERITY_AXIS:
                src = tmp / f"dist_{name.lower()}.mp4"
                if src.exists():
                    shutil.copy2(str(src), str(fix_dir / src.name))
            print(f"  Fixtures saved → {fix_dir}")

        # Candidate threshold
        vmaf_by = {r.fixture: r.vmaf.vmaf for r in results if r.vmaf_available and r.vmaf}
        accept_means  = [vmaf_by[n].mean  for n in ACCEPTABLE  if n in vmaf_by]
        accept_p5s    = [vmaf_by[n].p5    for n in ACCEPTABLE  if n in vmaf_by]
        accept_worsts = [vmaf_by[n].worst for n in ACCEPTABLE  if n in vmaf_by]
        cand_threshold = None
        if accept_means:
            cand_threshold = {
                "vmaf_mean_min": round(min(accept_means) * 0.95, 1),
                "vmaf_p5_min": round(min(accept_p5s) * 0.95, 1),
                "vmaf_worst_min": round(min(accept_worsts) * 0.95, 1),
            }

        diag_msgs = check_monotonicity_diagnostics(results) if vmaf_ok else []

        out_data = {
            "schema": "veilframe-vmaf-calibration-v1",
            "metadata": asdict(meta),
            "gate_reference": {"ssim_min": GATE_SSIM, "psnr_db_min": GATE_PSNR},
            "candidate_threshold": cand_threshold,
            "monotonicity_diagnostics": diag_msgs,
            "fixtures": FIXTURE_SEVERITY_AXIS,
            "results": [asdict(r) for r in results],
        }
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w") as f:
            json.dump(out_data, f, indent=2)
        print(f"\n  Results → {args.out}")

    print_ascii_table(results, vmaf_ok)
    if vmaf_ok:
        print_threshold_analysis(results)
        if diag_msgs:
            print("  Monotonicity Diagnostics:")
            for msg in diag_msgs:
                print(f"    • {msg}")
            print()
    else:
        print("  Run on a machine with libvmaf FFmpeg to obtain VMAF calibration data.")


if __name__ == "__main__":
    main()
