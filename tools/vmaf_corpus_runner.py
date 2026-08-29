#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VeilFrame VMAF Corpus Runner  (Phase B)
=======================================
Applies all 8 calibration fixtures to every clip in calibration_corpus/,
measures VMAF + SSIM + PSNR for each fixture x clip pair, and evaluates
whether the Phase A candidate threshold generalises across content types.

Usage:
    python tools/vmaf_corpus_runner.py \
        --corpus calibration_corpus/ \
        --candidate vmaf_calibration_results.json \
        --out vmaf_corpus_results.json

Decision criterion (threshold accepted when ALL met):
    - LOW_PERTURBATION passes on >= 95% of corpus clips
    - MODERATE_EXCEEDANCE fails on >= 90% of corpus clips
    - False-accept rate < 2%
    - False-reject rate < 5%

Requirements:
    - FFmpeg with libvmaf support
    - Phase A results JSON (for candidate threshold)
    - Python 3.9+  (stdlib only)
"""

import argparse
import json
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import io
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

# ── Constants ──────────────────────────────────────────────────────────── #

TOOL_VERSION = "1.0.0"

# Acceptable fixtures — should PASS gate
ACCEPTABLE_FIXTURES   = ["IDENTICAL", "VERY_LOW", "LOW_PERTURBATION"]
# Boundary fixtures — inform gap but don't anchor threshold
BOUNDARY_FIXTURES     = ["MODERATE", "MODERATE_EXCEEDANCE"]
# Unacceptable fixtures — should FAIL gate
UNACCEPTABLE_FIXTURES = ["HIGH", "SEVERE", "EXTREME"]

# Decision thresholds
FA_RATE_MAX = 0.02   # Max false-accept rate (unacceptable clips passing)
FR_RATE_MAX = 0.05   # Max false-reject rate (acceptable clips failing)
PASS_RATE_LOW_MIN   = 0.95   # LOW_PERTURBATION must pass on >= 95% of clips
FAIL_RATE_MODEX_MIN = 0.90   # MODERATE_EXCEEDANCE must fail on >= 90% of clips

NORMALIZE_W, NORMALIZE_H = 640, 480

FIXTURE_DESCRIPTIONS = {
    "IDENTICAL":            "Exact copy",
    "VERY_LOW":             "sigma=0.5 noise",
    "LOW_PERTURBATION":     "sigma=2 + 99.8% scale (VeilFrame typical)",
    "MODERATE":             "sigma=8 + slight blur",
    "MODERATE_EXCEEDANCE":  "10% crop + moderate blur",
    "HIGH":                 "sigma=18 noise + CRF=40",
    "SEVERE":               "Heavy blur + colour degradation",
    "EXTREME":              "Near-total distortion",
}

VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".ts"}


# ── Data models ────────────────────────────────────────────────────────── #

@dataclass
class PairResult:
    fixture:   str = ""
    vmaf_mean: float = 0.0
    vmaf_p5:   float = 0.0
    vmaf_worst: float = 0.0
    ssim_mean: float = 0.0
    psnr_mean: float = 0.0
    error: Optional[str] = None


@dataclass
class ClipResult:
    clip_path:    str = ""
    category:     str = ""
    subcategory:  str = ""
    fixtures:     List[PairResult] = field(default_factory=list)


@dataclass
class CorpusReport:
    tool_version:         str = TOOL_VERSION
    candidate_threshold:  Dict = field(default_factory=dict)
    total_clips:          int = 0
    total_pairs:          int = 0
    false_accept_rate:    float = 0.0
    false_reject_rate:    float = 0.0
    low_pert_pass_rate:   float = 0.0
    modex_fail_rate:      float = 0.0
    threshold_accepted:   bool = False
    recommendation:       str = ""
    clips:                List[ClipResult] = field(default_factory=list)


# ── FFmpeg helpers ─────────────────────────────────────────────────────── #

def _run(cmd: List[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def _encode(ref: Path, out: Path, vf: str, crf: int = 18):
    cmd = [
        "ffmpeg", "-y", "-i", str(ref), "-vf", vf,
        "-c:v", "libx264", "-preset", "fast", f"-crf", str(crf),
        "-c:a", "copy", "-pix_fmt", "yuv420p", str(out),
    ]
    r = _run(cmd, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[-400:])


def build_fixture(name: str, ref: Path, out: Path, w: int, h: int):
    """Build distorted fixture normalised to w×h."""
    base_scale = f"scale={w}:{h}"

    if name == "IDENTICAL":
        _encode(ref, out, base_scale, crf=0)
        return

    vf = {
        "VERY_LOW":            f"{base_scale},noise=alls=0.5:allf=t",
        "LOW_PERTURBATION":    f"scale={int(w*0.998)}:{int(h*0.998)},scale={w}:{h},noise=alls=2:allf=t",
        "MODERATE":            f"{base_scale},noise=alls=8:allf=t,gblur=sigma=0.8",
        "MODERATE_EXCEEDANCE": f"crop={int(w*0.90)}:{int(h*0.90)},scale={w}:{h},gblur=sigma=1.5",
        "HIGH":                f"{base_scale},noise=alls=12:allf=t,noise=alls=12:allf=u",
        "SEVERE":              f"{base_scale},gblur=sigma=4,hue=s=0.3,curves=master=0/0 0.3/0.15 1/0.7",
        "EXTREME":             f"{base_scale},gblur=sigma=8,hue=s=0.1,curves=master=0/0 1/0.4",
    }.get(name)

    if not vf:
        raise ValueError(f"Unknown fixture: {name}")

    crf = 40 if name == "HIGH" else 18
    _encode(ref, out, vf, crf)


def measure_pair(ref: Path, dist: Path, tmp: Path, vmaf_ok: bool) -> PairResult:
    pr = PairResult()

    # SSIM
    r_ssim = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        f"[0:v]scale={NORMALIZE_W}:{NORMALIZE_H}[r];[1:v]scale={NORMALIZE_W}:{NORMALIZE_H}[d];[d][r]ssim",
        "-f", "null", "-",
    ])
    m = re.search(r"All:(\d+\.\d+)", r_ssim.stdout + r_ssim.stderr)
    if m:
        pr.ssim_mean = float(m.group(1))

    # PSNR
    r_psnr = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        f"[0:v]scale={NORMALIZE_W}:{NORMALIZE_H}[r];[1:v]scale={NORMALIZE_W}:{NORMALIZE_H}[d];[d][r]psnr",
        "-f", "null", "-",
    ])
    m = re.search(r"average:([\d.]+|inf)", r_psnr.stdout + r_psnr.stderr)
    if m:
        val = m.group(1)
        pr.psnr_mean = 100.0 if val == "inf" else float(val)

    # VMAF
    if vmaf_ok:
        vmaf_json = tmp / "vmaf_pair.json"
        escaped_json = str(vmaf_json).replace("\\", "/").replace(":", "\\\\:")
        filt_v = (
            f"[0:v]scale={NORMALIZE_W}:{NORMALIZE_H},setpts=PTS-STARTPTS[ref];"
            f"[1:v]scale={NORMALIZE_W}:{NORMALIZE_H},setpts=PTS-STARTPTS[dist];"
            f"[dist][ref]libvmaf=log_fmt=json:log_path={escaped_json}"
        )
        rv = _run(["ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
                   "-filter_complex", filt_v, "-f", "null", "-"])
        if rv.returncode == 0 and vmaf_json.exists():
            try:
                with open(vmaf_json) as f:
                    data = json.load(f)
                frames = data.get("frames", [])
                scores = [fr["metrics"]["vmaf"] for fr in frames
                          if "vmaf" in fr.get("metrics", {})]
                if scores:
                    pr.vmaf_mean  = statistics.mean(scores)
                    pr.vmaf_worst = min(scores)

                    def pct(p):
                        s = sorted(scores)
                        return s[max(0, int(len(s) * p / 100) - 1)]

                    pr.vmaf_p5 = pct(5)
                else:
                    pooled = data.get("pooled_metrics", {})
                    pr.vmaf_mean  = pooled.get("vmaf", {}).get("mean", 0.0)
                    pr.vmaf_p5    = pooled.get("vmaf", {}).get("percentile5", 0.0)
                    pr.vmaf_worst = pooled.get("vmaf", {}).get("min", 0.0)
            except Exception as e:
                pr.error = f"VMAF parse: {e}"

    return pr


def get_video_dims(path: Path) -> Tuple[int, int]:
    """Return (width, height) of first video stream via ffprobe."""
    r = _run(["ffprobe", "-v", "error", "-select_streams", "v:0",
              "-show_entries", "stream=width,height",
              "-of", "csv=p=0", str(path)])
    m = re.search(r"(\d+),(\d+)", r.stdout)
    if m:
        return int(m.group(1)), int(m.group(2))
    return NORMALIZE_W, NORMALIZE_H


def get_category(clip: Path, corpus_root: Path) -> Tuple[str, str]:
    """Derive (category, subcategory) from path relative to corpus root."""
    rel = clip.relative_to(corpus_root)
    parts = rel.parts
    cat    = parts[0] if len(parts) > 1 else "unknown"
    subcat = parts[1] if len(parts) > 2 else ""
    return cat, subcat


# ── Corpus discovery ───────────────────────────────────────────────────── #

def discover_clips(corpus_root: Path) -> List[Path]:
    clips = []
    for ext in VIDEO_EXTENSIONS:
        clips.extend(corpus_root.rglob(f"*{ext}"))
    return sorted(clips)


# ── Threshold evaluation ───────────────────────────────────────────────── #

def evaluate_threshold(
    clips: List[ClipResult],
    cand: Dict,
) -> Tuple[float, float, float, float]:
    """
    Returns (false_accept_rate, false_reject_rate,
             low_pert_pass_rate, modex_fail_rate).
    """
    mean_min  = cand.get("vmaf_mean_min",  0.0)
    p5_min    = cand.get("vmaf_p5_min",    0.0)
    worst_min = cand.get("vmaf_worst_min", 0.0)

    def passes(pr: PairResult) -> bool:
        if mean_min and pr.vmaf_mean > 0:
            return (pr.vmaf_mean  >= mean_min and
                    pr.vmaf_p5    >= p5_min   and
                    pr.vmaf_worst >= worst_min)
        # Fallback: SSIM/PSNR only
        return pr.ssim_mean >= 0.95 and pr.psnr_mean >= 30.0

    fa = fr = low_pass = low_total = modex_fail = modex_total = 0

    for clip in clips:
        for pr in clip.fixtures:
            if pr.fixture in ACCEPTABLE_FIXTURES:
                if not passes(pr):
                    fr += 1
                if pr.fixture == "LOW_PERTURBATION":
                    low_total += 1
                    if passes(pr):
                        low_pass += 1
            elif pr.fixture in UNACCEPTABLE_FIXTURES:
                if passes(pr):
                    fa += 1
            if pr.fixture == "MODERATE_EXCEEDANCE":
                modex_total += 1
                if not passes(pr):
                    modex_fail += 1

    total_accept = sum(
        len([p for p in c.fixtures if p.fixture in ACCEPTABLE_FIXTURES])
        for c in clips
    )
    total_reject = sum(
        len([p for p in c.fixtures if p.fixture in UNACCEPTABLE_FIXTURES])
        for c in clips
    )

    fa_rate = fa / max(total_reject, 1)
    fr_rate = fr / max(total_accept, 1)
    low_rate = low_pass / max(low_total, 1)
    modex_rate = modex_fail / max(modex_total, 1)

    return fa_rate, fr_rate, low_rate, modex_rate


# ── ASCII report ───────────────────────────────────────────────────────── #

def print_report(report: CorpusReport):
    print()
    print("=" * 70)
    print("  VeilFrame Phase B Corpus Validation Report")
    print("=" * 70)
    print(f"  Clips processed:         {report.total_clips}")
    print(f"  Total fixture pairs:     {report.total_pairs}")
    print()
    print("  Candidate threshold:")
    ct = report.candidate_threshold
    print(f"    VMAF mean  >= {ct.get('vmaf_mean_min',  'n/a')}")
    print(f"    VMAF P5    >= {ct.get('vmaf_p5_min',    'n/a')}")
    print(f"    VMAF worst >= {ct.get('vmaf_worst_min', 'n/a')}")
    print()
    print("  Evaluation results:")
    print(f"    LOW_PERTURBATION pass rate:      {report.low_pert_pass_rate*100:.1f}%  "
          f"(need >= {PASS_RATE_LOW_MIN*100:.0f}%)  "
          f"{'[OK]' if report.low_pert_pass_rate >= PASS_RATE_LOW_MIN else '[FAIL]'}")
    print(f"    MODERATE_EXCEEDANCE fail rate:   {report.modex_fail_rate*100:.1f}%  "
          f"(need >= {FAIL_RATE_MODEX_MIN*100:.0f}%)  "
          f"{'[OK]' if report.modex_fail_rate >= FAIL_RATE_MODEX_MIN else '[FAIL]'}")
    print(f"    False-accept rate:               {report.false_accept_rate*100:.2f}%  "
          f"(need < {FA_RATE_MAX*100:.0f}%)  "
          f"{'[OK]' if report.false_accept_rate < FA_RATE_MAX else '[FAIL]'}")
    print(f"    False-reject rate:               {report.false_reject_rate*100:.2f}%  "
          f"(need < {FR_RATE_MAX*100:.0f}%)  "
          f"{'[OK]' if report.false_reject_rate < FR_RATE_MAX else '[FAIL]'}")
    print()
    verdict = "[ACCEPTED]" if report.threshold_accepted else "[NOT ACCEPTED]"
    print(f"  Corpus threshold verdict:  {verdict}")
    print(f"  Recommendation:  {report.recommendation}")
    print("=" * 70)
    print()


# ── Entry point ────────────────────────────────────────────────────────── #

def main():
    parser = argparse.ArgumentParser(
        description="VeilFrame Phase B Corpus Validation Runner"
    )
    parser.add_argument("--corpus", type=Path, required=True,
        help="Root directory of calibration_corpus/")
    parser.add_argument("--candidate", type=Path,
        default=Path("vmaf_calibration_results.json"),
        help="Phase A calibration results JSON (for candidate threshold)")
    parser.add_argument("--out", type=Path,
        default=Path("vmaf_corpus_results.json"),
        help="Output JSON path")
    args = parser.parse_args()

    print()
    print(f"VeilFrame VMAF Corpus Runner  v{TOOL_VERSION}")
    print("=" * 50)

    # Load candidate threshold from Phase A results
    cand: Dict = {}
    if args.candidate.exists():
        with open(args.candidate) as f:
            phase_a = json.load(f)
        # Look for threshold block written by vmaf_calibration.py
        cand = phase_a.get("candidate_threshold", {})
        if not cand:
            print("  NOTE: candidate_threshold not found in Phase A results.")
            print("  VMAF gate evaluation will use SSIM/PSNR fallback.")
    else:
        print(f"  WARNING: Phase A results not found at {args.candidate}")
        print("  SSIM/PSNR fallback will be used for threshold evaluation.")

    # Discover clips
    clips_paths = discover_clips(args.corpus)
    if not clips_paths:
        print(f"\n  No video clips found in {args.corpus}")
        print("  Populate the corpus first — see calibration_corpus/README.md")
        sys.exit(0)

    print(f"  Corpus root:  {args.corpus}")
    print(f"  Clips found:  {len(clips_paths)}")
    print()

    vmaf_ok = "libvmaf" in subprocess.run(
        ["ffmpeg", "-filters"], capture_output=True, text=True
    ).stdout

    print(f"  libvmaf:  {'[OK]' if vmaf_ok else '[--] not available -- SSIM/PSNR only'}")
    print()

    all_fixture_names = (ACCEPTABLE_FIXTURES + BOUNDARY_FIXTURES +
                         UNACCEPTABLE_FIXTURES)

    clip_results: List[ClipResult] = []
    total_pairs = 0

    with tempfile.TemporaryDirectory(prefix="vf_corpus_") as tmp_str:
        tmp = Path(tmp_str)

        for i, clip_path in enumerate(clips_paths, 1):
            cat, subcat = get_category(clip_path, args.corpus)
            print(f"  [{i}/{len(clips_paths)}] {clip_path.name}  ({cat}/{subcat})")
            w, h = get_video_dims(clip_path)
            cr = ClipResult(
                clip_path=str(clip_path),
                category=cat,
                subcategory=subcat,
            )

            for fname in all_fixture_names:
                dist = tmp / f"dist_{fname.lower()}.mp4"
                pr = PairResult(fixture=fname)
                try:
                    build_fixture(fname, clip_path, dist, w, h)
                    pr = measure_pair(clip_path, dist, tmp, vmaf_ok)
                    pr.fixture = fname
                    total_pairs += 1
                    print(
                        f"      {fname:<22}  VMAF={pr.vmaf_mean:6.2f}  "
                        f"SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB",
                        flush=True,
                    )
                except Exception as e:
                    pr.error = str(e)
                    print(f"      {fname:<22}  ERROR: {e}", flush=True)
                cr.fixtures.append(pr)

            clip_results.append(cr)

    # Evaluate threshold
    fa_rate, fr_rate, low_rate, modex_rate = evaluate_threshold(clip_results, cand)

    accepted = (
        low_rate   >= PASS_RATE_LOW_MIN  and
        modex_rate >= FAIL_RATE_MODEX_MIN and
        fa_rate    < FA_RATE_MAX         and
        fr_rate    < FR_RATE_MAX
    )

    if accepted:
        rec = "Threshold accepted -- proceed to Phase C (gate promotion) with these values."
    elif fa_rate >= FA_RATE_MAX:
        rec = "False-accept rate too high -- raise vmaf_mean_min or expand corpus."
    elif fr_rate >= FR_RATE_MAX:
        rec = "False-reject rate too high -- lower vmaf_worst_min or check LOW_PERTURBATION fixtures."
    else:
        rec = "Threshold not stable across content types -- expand corpus or revise fixture definitions."

    report = CorpusReport(
        candidate_threshold=cand,
        total_clips=len(clip_results),
        total_pairs=total_pairs,
        false_accept_rate=fa_rate,
        false_reject_rate=fr_rate,
        low_pert_pass_rate=low_rate,
        modex_fail_rate=modex_rate,
        threshold_accepted=accepted,
        recommendation=rec,
        clips=clip_results,
    )

    with open(args.out, "w") as f:
        json.dump(asdict(report), f, indent=2)
    print(f"\n  Results -> {args.out}")

    print_report(report)


if __name__ == "__main__":
    main()
