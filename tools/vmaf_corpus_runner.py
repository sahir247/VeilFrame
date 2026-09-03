#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VeilFrame VMAF Corpus Runner  (Phase B) — VMAF v1.0.16 Upgrade
==============================================================
Applies all 8 calibration fixtures to every clip in calibration_corpus/,
measures VMAF + SSIM + PSNR for each fixture x clip pair at native resolution,
and evaluates whether the Phase A candidate threshold generalises across content types.

Key enhancements for VMAF v1.0.16:
  - Supports .y4m containers alongside standard container formats.
  - Native resolution evaluation (removed forced 640x480 normalization).
  - FFprobe stream metadata extraction (dimensions, fps, pix_fmt, color space).
  - HDR detection and segregation (vmaf_status="not_applicable_hdr", preserves SSIM/PSNR).
  - Deterministic VMAF v1.0.16 model selection and SHA-256 verification.
  - Resumable execution: existing valid results in output JSON are preserved.
  - Sequence variant grouping metadata.

Usage:
    uv run python tools/vmaf_corpus_runner.py \
        --corpus calibration_corpus/ \
        --candidate vmaf_calibration_results.json \
        --out vmaf_corpus_results.json
"""

import argparse
import fractions
import json
import re
import statistics
import subprocess
import sys
import tempfile
import io
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Set

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

from veilframe.quality.vmaf_models import (
    VMAF_MODEL_VERSION,
    VmafModelSpec,
    VmafModelError,
    VmafNotApplicableHdrError,
    VmafUnsupportedResolutionError,
    select_vmaf_model,
    resolve_and_verify_model,
    detect_hdr,
    format_ffmpeg_filter_path,
    format_vmaf_model_filter_arg,
)
from veilframe.core.crypto import compute_sha256


# ── Constants ──────────────────────────────────────────────────────────── #

TOOL_VERSION = "1.1.0"

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

VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".ts", ".y4m"}


# ── Data models ────────────────────────────────────────────────────────── #

@dataclass
class PairResult:
    fixture:           str = ""
    vmaf_status:       str = "ok"  # "ok", "not_applicable_hdr", "unsupported_resolution", "error", "skipped"
    vmaf_model_name:   Optional[str] = None
    vmaf_model_sha256: Optional[str] = None
    vmaf_mean:         float = 0.0
    vmaf_p5:           float = 0.0
    vmaf_worst:        float = 0.0
    ssim_mean:         float = 0.0
    psnr_mean:         float = 0.0
    error:             Optional[str] = None


@dataclass
class ClipResult:
    clip_path:        str = ""
    clip_sha256:      str = ""
    sequence_group:   str = ""
    category:         str = ""
    subcategory:      str = ""
    width:            int = 0
    height:           int = 0
    fps:              float = 0.0
    pix_fmt:          str = ""
    is_hdr:           bool = False
    hdr_reason:       str = ""
    fixtures:         List[PairResult] = field(default_factory=list)


@dataclass
class CorpusReport:
    tool_version:         str = TOOL_VERSION
    vmaf_model_version:   str = VMAF_MODEL_VERSION
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


# ── FFmpeg & FFprobe helpers ───────────────────────────────────────────── #

def _run(cmd: List[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def get_video_stream_meta(path: Path) -> Dict[str, Any]:
    """
    Extracts stream metadata (width, height, fps, pix_fmt, color space) via ffprobe.
    """
    cmd = [
        "ffprobe", "-v", "error",
        "-select_streams", "v:0",
        "-show_entries",
        "stream=width,height,r_frame_rate,avg_frame_rate,pix_fmt,color_transfer,color_primaries,color_space",
        "-of", "json",
        str(path),
    ]
    r = _run(cmd, timeout=30)
    meta: Dict[str, Any] = {
        "width": 1920,
        "height": 1080,
        "fps": 30.0,
        "pix_fmt": "yuv420p",
        "color_transfer": "",
        "color_primaries": "",
        "color_space": "",
    }
    if r.returncode == 0 and r.stdout:
        try:
            data = json.loads(r.stdout)
            streams = data.get("streams", [])
            if streams:
                st = streams[0]
                meta["width"] = int(st.get("width", 1920))
                meta["height"] = int(st.get("height", 1080))
                meta["pix_fmt"] = str(st.get("pix_fmt", "yuv420p"))
                meta["color_transfer"] = str(st.get("color_transfer", ""))
                meta["color_primaries"] = str(st.get("color_primaries", ""))
                meta["color_space"] = str(st.get("color_space", ""))

                # Parse frame rate
                rate_str = st.get("r_frame_rate") or st.get("avg_frame_rate") or "30/1"
                try:
                    meta["fps"] = float(fractions.Fraction(rate_str))
                except Exception:
                    meta["fps"] = 30.0
        except Exception:
            pass

    return meta


def derive_sequence_group(path: Path) -> str:
    """
    Groups sequence variants by extracting the common prefix from the filename.
    e.g. 'crowd_run_1080p.y4m' -> 'crowd_run'.
    """
    stem = path.stem
    # Match alphanumeric token before resolution or variant suffix
    m = re.match(r"^([a-zA-Z0-9\-]+?)(?:_\d+p|_sdr|_hdr|_q\d+|_v\d+|$)", stem, re.IGNORECASE)
    if m:
        return m.group(1)
    parts = stem.split("_")
    return parts[0] if parts else stem


def get_category(clip: Path, corpus_root: Path) -> Tuple[str, str]:
    """Derive (category, subcategory) from path relative to corpus root."""
    try:
        rel = clip.relative_to(corpus_root)
        parts = rel.parts
        cat    = parts[0] if len(parts) > 1 else "root"
        subcat = parts[1] if len(parts) > 2 else ""
        return cat, subcat
    except ValueError:
        return "external", ""


# ── Fixture builders ───────────────────────────────────────────────────── #

def _encode(ref: Path, out: Path, vf: str, crf: int = 18):
    cmd = [
        "ffmpeg", "-y", "-i", str(ref), "-vf", vf,
        "-c:v", "libx264", "-preset", "fast", "-crf", str(crf),
        "-c:a", "copy", "-pix_fmt", "yuv420p", str(out),
    ]
    r = _run(cmd, timeout=180)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[-500:])


def build_fixture(name: str, ref: Path, out: Path, w: int, h: int):
    """Build distorted fixture at native w x h dimensions."""
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
        "SEVERE":              f"{base_scale},gblur=sigma=4,hue=s=0.3,curves=master='0/0 0.3/0.15 1/0.7'",
        "EXTREME":             f"{base_scale},gblur=sigma=8,hue=s=0.05,curves=master='0/0 1/0.35'",
    }.get(name)

    if not vf:
        raise ValueError(f"Unknown fixture: {name}")

    crf = 40 if name == "HIGH" else (51 if name == "EXTREME" else 18)
    _encode(ref, out, vf, crf)


# ── Pair measurement ───────────────────────────────────────────────────── #

def measure_pair(
    ref: Path,
    dist: Path,
    tmp: Path,
    vmaf_ok: bool,
    model_spec: Optional[VmafModelSpec] = None,
    model_path: Optional[Path] = None,
    is_hdr: bool = False,
    hdr_reason: str = "",
) -> PairResult:
    pr = PairResult()

    # SSIM measurement
    r_ssim = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]ssim",
        "-f", "null", "-",
    ], timeout=120)
    m = re.search(r"All:(\d+\.\d+)", r_ssim.stdout + r_ssim.stderr)
    if m:
        pr.ssim_mean = float(m.group(1))

    # PSNR measurement
    r_psnr = _run([
        "ffmpeg", "-y", "-i", str(ref), "-i", str(dist),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]psnr",
        "-f", "null", "-",
    ], timeout=120)
    m = re.search(r"average:([\d.]+|inf)", r_psnr.stdout + r_psnr.stderr)
    if m:
        val = m.group(1)
        pr.psnr_mean = 100.0 if val == "inf" else float(val)

    # VMAF handling
    if is_hdr:
        pr.vmaf_status = "not_applicable_hdr"
        pr.error = hdr_reason
        return pr

    if not vmaf_ok or not model_path:
        pr.vmaf_status = "skipped"
        return pr

    pr.vmaf_status = "ok"
    if model_spec:
        pr.vmaf_model_name = model_spec.filename
        pr.vmaf_model_sha256 = model_spec.expected_sha256

    vmaf_json = tmp / f"vmaf_{dist.stem}.json"
    escaped_json = format_ffmpeg_filter_path(vmaf_json)
    model_arg = format_vmaf_model_filter_arg(model_path)

    filt_v = (
        f"[0:v]setpts=PTS-STARTPTS[dist];"
        f"[1:v]setpts=PTS-STARTPTS[ref];"
        f"[dist][ref]libvmaf="
        f"{model_arg}:"
        f"log_fmt=json:log_path='{escaped_json}'"
    )
    rv = _run([
        "ffmpeg", "-y",
        "-i", str(dist),
        "-i", str(ref),
        "-filter_complex", filt_v,
        "-f", "null", "-",
    ], timeout=300)

    if rv.returncode == 0 and vmaf_json.exists():
        try:
            with open(vmaf_json) as f:
                data = json.load(f)
            frames = data.get("frames", [])
            scores = [fr["metrics"]["vmaf"] for fr in frames if "vmaf" in fr.get("metrics", {})]
            if scores:
                pr.vmaf_mean = statistics.mean(scores)
                pr.vmaf_worst = min(scores)

                def pct(p):
                    s = sorted(scores)
                    return s[max(0, int(len(s) * p / 100) - 1)]

                pr.vmaf_p5 = pct(5)
            else:
                pooled = data.get("pooled_metrics", {})
                pr.vmaf_mean = pooled.get("vmaf", {}).get("mean", 0.0)
                pr.vmaf_p5 = pooled.get("vmaf", {}).get("percentile5", 0.0)
                pr.vmaf_worst = pooled.get("vmaf", {}).get("min", 0.0)
        except Exception as e:
            pr.vmaf_status = "error"
            pr.error = f"VMAF parse: {e}"
        finally:
            vmaf_json.unlink(missing_ok=True)
    else:
        pr.vmaf_status = "error"
        pr.error = f"libvmaf execution failed:\n{rv.stderr[-400:]}"

    return pr


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
    Evaluates VMAF for SDR clips; falls back to SSIM/PSNR for HDR or VMAF-unavailable clips.
    """
    mean_min  = cand.get("vmaf_mean_min",  0.0)
    p5_min    = cand.get("vmaf_p5_min",    0.0)
    worst_min = cand.get("vmaf_worst_min", 0.0)

    def passes(pr: PairResult) -> bool:
        if pr.vmaf_status == "ok" and mean_min and pr.vmaf_mean > 0:
            return (pr.vmaf_mean  >= mean_min and
                    pr.vmaf_p5    >= p5_min   and
                    pr.vmaf_worst >= worst_min)
        # Fallback: SSIM/PSNR standard gate reference
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


# ── Resumable state loading ────────────────────────────────────────────── #

def load_existing_results(out_path: Path) -> Dict[str, Dict[str, PairResult]]:
    """
    Loads completed pair results from existing JSON for resumable execution.
    Returns: {clip_path: {fixture_name: PairResult}}
    """
    if not out_path.exists():
        return {}
    try:
        with open(out_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        cached = {}
        for c in data.get("clips", []):
            cp = c.get("clip_path")
            if not cp:
                continue
            fixtures_dict = {}
            for fx in c.get("fixtures", []):
                fname = fx.get("fixture")
                if fname and not fx.get("error"):
                    fixtures_dict[fname] = PairResult(**fx)
            cached[cp] = fixtures_dict
        return cached
    except Exception:
        return {}


# ── ASCII report ───────────────────────────────────────────────────────── #

def print_report(report: CorpusReport):
    print()
    print("=" * 75)
    print("  VeilFrame Phase B Corpus Validation Report (VMAF v1.0.16)")
    print("=" * 75)
    print(f"  Clips processed:         {report.total_clips}")
    print(f"  Total fixture pairs:     {report.total_pairs}")
    print(f"  VMAF model version:      {report.vmaf_model_version}")
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
    print("=" * 75)
    print()


# ── Entry point ────────────────────────────────────────────────────────── #

def main():
    parser = argparse.ArgumentParser(
        description="VeilFrame Phase B Corpus Validation Runner (VMAF v1.0.16)"
    )
    parser.add_argument("--corpus", type=Path, required=True,
        help="Root directory of calibration_corpus/")
    parser.add_argument("--candidate", type=Path,
        default=Path("vmaf_calibration_results.json"),
        help="Phase A calibration results JSON (for candidate threshold)")
    parser.add_argument("--model-root", type=Path, default=None,
        help="Custom VMAF model root directory (overrides $env:VMAF_MODEL_ROOT)")
    parser.add_argument("--mean-min", type=float, default=None,
        help="Override candidate VMAF mean minimum threshold (e.g. 85.0)")
    parser.add_argument("--p5-min", type=float, default=None,
        help="Override candidate VMAF P5 tail minimum threshold (e.g. 75.0)")
    parser.add_argument("--worst-min", type=float, default=None,
        help="Override candidate VMAF worst-frame minimum threshold (e.g. 70.0)")
    parser.add_argument("--no-resume", action="store_true",
        help="Do not resume; rerun all clip-fixture pairs from scratch")
    parser.add_argument("--out", type=Path,
        default=Path("vmaf_corpus_results.json"),
        help="Output JSON path")
    args = parser.parse_args()

    print()
    print(f"VeilFrame VMAF Corpus Runner  v{TOOL_VERSION} (VMAF v{VMAF_MODEL_VERSION})")
    print("=" * 65)

    # Load candidate threshold from Phase A results
    cand: Dict = {}
    if args.candidate.exists():
        try:
            with open(args.candidate, "r", encoding="utf-8") as f:
                phase_a = json.load(f)
            cand = phase_a.get("candidate_threshold", {})
            if not cand:
                print("  NOTE: candidate_threshold not found in Phase A results.")
        except Exception as e:
            print(f"  WARNING: Failed reading Phase A results: {e}")
    else:
        print(f"  WARNING: Phase A results not found at {args.candidate}")

    # Apply command-line overrides if provided
    if args.mean_min is not None:
        cand["vmaf_mean_min"] = args.mean_min
    if args.p5_min is not None:
        cand["vmaf_p5_min"] = args.p5_min
    if args.worst_min is not None:
        cand["vmaf_worst_min"] = args.worst_min

    if not cand:
        print("  VMAF gate evaluation will use SSIM/PSNR fallback.")
    else:
        print(f"  Candidate VMAF Mean Min:  {cand.get('vmaf_mean_min', 'n/a')}")
        print(f"  Candidate VMAF P5 Min:    {cand.get('vmaf_p5_min', 'n/a')}")
        print(f"  Candidate VMAF Worst Min: {cand.get('vmaf_worst_min', 'n/a')}")

    # Discover clips
    clips_paths = discover_clips(args.corpus)
    if not clips_paths:
        print(f"\n  No video clips found in {args.corpus}")
        print("  Populate the corpus first — supported extensions: " + ", ".join(sorted(VIDEO_EXTENSIONS)))
        sys.exit(0)

    print(f"  Corpus root:  {args.corpus}")
    print(f"  Clips found:  {len(clips_paths)}")
    print()

    vmaf_ok = "libvmaf" in subprocess.run(
        ["ffmpeg", "-filters"], capture_output=True, text=True
    ).stdout

    print(f"  libvmaf:  {'[OK]' if vmaf_ok else '[--] not available -- SSIM/PSNR only'}")
    print()

    cached_results = {} if args.no_resume else load_existing_results(args.out)
    if cached_results:
        print(f"  Resuming from {args.out} ({len(cached_results)} cached clips detected).")

    all_fixture_names = (ACCEPTABLE_FIXTURES + BOUNDARY_FIXTURES +
                         UNACCEPTABLE_FIXTURES)

    clip_results: List[ClipResult] = []
    total_pairs = 0

    with tempfile.TemporaryDirectory(prefix="vf_corpus_") as tmp_str:
        tmp = Path(tmp_str)

        for i, clip_path in enumerate(clips_paths, 1):
            cat, subcat = get_category(clip_path, args.corpus)
            seq_group = derive_sequence_group(clip_path)
            meta = get_video_stream_meta(clip_path)
            is_hdr_val, hdr_reason = detect_hdr(meta)
            clip_sha = compute_sha256(clip_path)

            w = meta["width"]
            h = meta["height"]
            fps = meta["fps"]

            print(f"  [{i}/{len(clips_paths)}] {clip_path.name} ({w}x{h} @ {fps:.2f}fps, {meta['pix_fmt']})")
            if is_hdr_val:
                print(f"      HDR detected ({hdr_reason}) — VMAF disabled for this clip.")

            cr = ClipResult(
                clip_path=str(clip_path),
                clip_sha256=clip_sha,
                sequence_group=seq_group,
                category=cat,
                subcategory=subcat,
                width=w,
                height=h,
                fps=fps,
                pix_fmt=meta["pix_fmt"],
                is_hdr=is_hdr_val,
                hdr_reason=hdr_reason,
            )

            # Resolve model for this resolution & frame-rate
            model_spec: Optional[VmafModelSpec] = None
            model_path: Optional[Path] = None
            if vmaf_ok and not is_hdr_val:
                try:
                    model_spec = select_vmaf_model(w, h, fps, is_hdr=False)
                    model_path = resolve_and_verify_model(model_spec, model_root=args.model_root)
                except VmafUnsupportedResolutionError as ure:
                    print(f"      Resolution unsupported for standard VMAF: {ure}")
                except VmafModelError as vme:
                    print(f"      VMAF model error: {vme}")

            cached_clip_fixtures = cached_results.get(str(clip_path), {})

            for fname in all_fixture_names:
                # Check for cached result if resuming
                if fname in cached_clip_fixtures:
                    cached_pr = cached_clip_fixtures[fname]
                    # Verify model hash matches if VMAF was evaluated
                    if (cached_pr.vmaf_status != "ok" or
                        not model_spec or
                        cached_pr.vmaf_model_sha256 == model_spec.expected_sha256):
                        cr.fixtures.append(cached_pr)
                        total_pairs += 1
                        print(
                            f"      {fname:<22}  [CACHED] VMAF={cached_pr.vmaf_mean:6.2f}  "
                            f"SSIM={cached_pr.ssim_mean:.4f}  PSNR={cached_pr.psnr_mean:.2f}dB",
                            flush=True,
                        )
                        continue

                dist = tmp / f"dist_{fname.lower()}.mp4"
                pr = PairResult(fixture=fname)
                try:
                    build_fixture(fname, clip_path, dist, w, h)
                    pr = measure_pair(
                        clip_path, dist, tmp, vmaf_ok,
                        model_spec=model_spec,
                        model_path=model_path,
                        is_hdr=is_hdr_val,
                        hdr_reason=hdr_reason,
                    )
                    pr.fixture = fname
                    total_pairs += 1
                    status_flag = f"[{pr.vmaf_status}]" if pr.vmaf_status != "ok" else ""
                    print(
                        f"      {fname:<22}  VMAF={pr.vmaf_mean:6.2f} {status_flag}  "
                        f"SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB",
                        flush=True,
                    )
                except Exception as e:
                    pr.error = str(e)
                    print(f"      {fname:<22}  ERROR: {e}", flush=True)
                finally:
                    dist.unlink(missing_ok=True)

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
        tool_version=TOOL_VERSION,
        vmaf_model_version=VMAF_MODEL_VERSION,
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

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(asdict(report), f, indent=2)
    print(f"\n  Results -> {args.out}")

    print_report(report)


if __name__ == "__main__":
    main()
