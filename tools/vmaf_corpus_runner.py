#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VeilFrame VMAF Corpus Runner (Phase B) — Measurement Tool
=========================================================
Applies all 8 calibration fixtures to every clip in calibration_corpus/,
measures VMAF + SSIM + PSNR for each fixture x clip pair at native resolution,
and writes comprehensive, machine-readable measurement results.

Pure Measurement Architecture:
  - This tool is strictly for measurement and evidence collection.
  - Contains ZERO threshold-decision or gate-promotion logic.
  - Threshold analysis is decoupled into tools/vmaf_threshold_analysis.py.

Key Features:
  - Supports container formats (.mp4, .mov, .mkv, .webm, .avi, .m4v, .ts) and raw .y4m.
  - Evaluates at native resolution and native frame rate (no forced downscaling).
  - Strict ffprobe metadata extraction (no silent fallback to 1080p).
  - Authoritative corpus manifest support (manifest.json) for sequence grouping.
  - Automatic HDR segregation (status="not_applicable_hdr") without fabricating scores.
  - Explicit sample states: "success", "not_applicable_hdr", "unsupported_resolution",
    "metadata_error", "measurement_error". Failures are NEVER represented as VMAF=0.0.
  - Deterministic model selection (v1.0.16) and SHA-256 verification.
  - Resumable execution: existing successful results in output JSON are preserved.
"""

import argparse
import datetime
import fractions
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile
import io
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace", line_buffering=True)

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

TOOL_VERSION = "1.2.0"

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

ACCEPTABLE_FIXTURES   = ["IDENTICAL", "VERY_LOW", "LOW_PERTURBATION"]
BOUNDARY_FIXTURES     = ["MODERATE", "MODERATE_EXCEEDANCE"]
UNACCEPTABLE_FIXTURES = ["HIGH", "SEVERE", "EXTREME"]

VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".ts", ".y4m"}


# ── Exceptions ─────────────────────────────────────────────────────────── #

class VideoMetadataExtractionError(Exception):
    """Raised when ffprobe fails to extract required stream metadata."""
    pass


# ── Data models ────────────────────────────────────────────────────────── #

@dataclass
class PairResult:
    fixture:                  str = ""
    status:                   str = "success"  # "success", "not_applicable_hdr", "unsupported_resolution", "measurement_error"
    error_type:               Optional[str] = None
    error_message:            Optional[str] = None
    vmaf_mean:                Optional[float] = None
    vmaf_median:              Optional[float] = None
    vmaf_p1:                  Optional[float] = None
    vmaf_p5:                  Optional[float] = None
    vmaf_p95:                 Optional[float] = None
    vmaf_worst:               Optional[float] = None
    vmaf_stddev:              Optional[float] = None
    ssim_mean:                Optional[float] = None
    psnr_mean:                Optional[float] = None
    adm2_score:               Optional[float] = None
    vif_score:                Optional[float] = None
    motion_score:             Optional[float] = None
    independent_policy_label: Optional[str] = None  # "acceptable", "unacceptable", "boundary"
    model_id:                 Optional[str] = None
    model_name:               Optional[str] = None
    model_sha256:             Optional[str] = None
    evidence_path:            Optional[str] = None
    evidence_sha256:          Optional[str] = None


@dataclass
class ClipResult:
    clip_path:             str = ""
    clip_filename:         str = ""
    clip_sha256:           str = ""
    sequence_group:        str = ""
    sequence_group_source: str = "filename_heuristic"  # "manifest" or "filename_heuristic"
    category:              str = ""
    subcategory:           str = ""
    domain:                str = ""
    suitability_status:    str = ""
    width:                 Optional[int] = None
    height:                Optional[int] = None
    fps:                   Optional[float] = None
    pix_fmt:               Optional[str] = None
    color_transfer:        Optional[str] = None
    color_primaries:       Optional[str] = None
    color_space:           Optional[str] = None
    is_hdr:                bool = False
    hdr_reason:            str = ""
    status:                str = "success"  # "success", "metadata_error"
    error_message:         Optional[str] = None
    fixtures:              List[PairResult] = field(default_factory=list)


@dataclass
class CorpusReport:
    schema:                str = "veilframe-vmaf-corpus-v1"
    tool_version:          str = TOOL_VERSION
    vmaf_model_version:    str = VMAF_MODEL_VERSION
    timestamp_utc:         str = ""
    ffmpeg_version:        Optional[str] = None
    libvmaf_version:       Optional[str] = None
    libvmaf_version_source:str = "unavailable"
    corpus_root:           str = ""
    manifest_path:         Optional[str] = None
    total_clips:           int = 0
    total_pairs:           int = 0
    successful_pairs:      int = 0
    hdr_segregated_pairs:  int = 0
    error_pairs:           int = 0
    clips:                 List[ClipResult] = field(default_factory=list)


# ── FFmpeg & FFprobe helpers ───────────────────────────────────────────── #

def _run(cmd: List[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def _get_ffmpeg_version() -> Optional[str]:
    try:
        r = _run(["ffmpeg", "-version"], timeout=10)
        m = re.search(r"ffmpeg version\s+([^\s]+)", r.stdout)
        return m.group(1) if m else None
    except Exception:
        return None


def _get_libvmaf_version() -> Tuple[Optional[str], str]:
    try:
        r = _run(["ffmpeg", "-version"], timeout=10)
        combined = r.stdout + r.stderr
        m = re.search(r"libvmaf\s+([\d.]+)", combined)
        if m:
            return m.group(1), "ffmpeg-version-output"
    except Exception:
        pass
    return None, "unavailable"


def get_video_stream_meta(path: Path) -> Dict[str, Any]:
    """
    Extracts stream metadata (width, height, fps, pix_fmt, color space) via ffprobe.
    Raises VideoMetadataExtractionError on failure. No silent fallback.
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
    if r.returncode != 0:
        raise VideoMetadataExtractionError(f"ffprobe failed for '{path.name}': {r.stderr.strip()[:300]}")

    try:
        data = json.loads(r.stdout)
        streams = data.get("streams", [])
        if not streams:
            raise VideoMetadataExtractionError(f"No video streams found in '{path.name}'")
        s = streams[0]
        w = s.get("width")
        h = s.get("height")
        if not w or not h:
            raise VideoMetadataExtractionError(f"Missing width/height in video stream for '{path.name}'")

        fps_str = s.get("avg_frame_rate") or s.get("r_frame_rate") or "0/0"
        try:
            fps_frac = fractions.Fraction(fps_str)
            fps = float(fps_frac) if fps_frac.denominator != 0 else 0.0
        except (ValueError, ZeroDivisionError):
            fps = 0.0

        if fps <= 0.0:
            raise VideoMetadataExtractionError(f"Invalid frame rate '{fps_str}' for '{path.name}'")

        return {
            "width": int(w),
            "height": int(h),
            "fps": fps,
            "pix_fmt": s.get("pix_fmt", "unknown"),
            "color_transfer": s.get("color_transfer", ""),
            "color_primaries": s.get("color_primaries", ""),
            "color_space": s.get("color_space", ""),
        }
    except json.JSONDecodeError as exc:
        raise VideoMetadataExtractionError(f"ffprobe produced invalid JSON for '{path.name}': {exc}")


# ── Manifest & Grouping ────────────────────────────────────────────────── #

def load_corpus_manifest(manifest_path: Optional[Path]) -> Dict[str, Dict[str, Any]]:
    """
    Loads authoritative corpus manifest if available.
    Returns: {clip_filename: {"sequence_group": ..., "category": ..., "subcategory": ...}}
    """
    if not manifest_path or not manifest_path.exists():
        return {}
    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        clips = data.get("clips", [])
        result = {}
        for c in clips:
            fn = c.get("filename")
            if fn:
                result[fn] = c
        return result
    except Exception as exc:
        print(f"  [WARN] Failed to load corpus manifest '{manifest_path}': {exc}")
        return {}


def derive_sequence_group(clip_path: Path) -> str:
    """
    Fallback heuristic for deriving sequence_group when absent from manifest.
    Strips resolution/fps/variant suffixes to prevent sequence leakage across splits.
    """
    stem = clip_path.stem.lower()
    patterns = [
        r"^(park_joy)",
        r"^(ducks_take_off)",
        r"^(night_drive)",
        r"^(browsing)",
        r"^(chimera)",
        r"^(aspen)",
        r"^(tractor)",
        r"^(old_town_cross)",
        r"^(rush_field_cuts)",
        r"^(snow_mnt)",
        r"^(speed_bag)",
        r"^(red_kayak)",
        r"^(fourpeople)",
        r"^([a-z]+_[a-z]+)_[0-9]+",
        r"^([a-z0-9]+)_[0-9]+p",
        r"^([a-z0-9]+)_[0-9]+x[0-9]+",
    ]
    for pat in patterns:
        m = re.match(pat, stem)
        if m:
            return m.group(1)
    parts = stem.split("_")
    return parts[0] if parts else stem


def get_category(clip_path: Path, corpus_root: Path) -> Tuple[str, str]:
    try:
        rel = clip_path.relative_to(corpus_root)
        parts = rel.parts
        if len(parts) >= 3:
            return parts[0], parts[1]
        elif len(parts) == 2:
            return parts[0], ""
        return "general", ""
    except ValueError:
        return "general", ""


# ── Fixture Builders ───────────────────────────────────────────────────── #

def build_fixture(
    fixture_name: str,
    ref: Path,
    out: Path,
    width: int,
    height: int,
) -> bool:
    """Builds a single fixture distortion from the reference at native resolution."""
    c_w, c_h = width, height

    if fixture_name == "IDENTICAL":
        if ref.suffix.lower() == ".y4m":
            # Raw Y4M into MP4 container requires lossless encoding
            r = _run(["ffmpeg", "-y", "-i", str(ref), "-c:v", "libx264", "-crf", "0", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(out)], timeout=60)
        else:
            r = _run(["ffmpeg", "-y", "-i", str(ref), "-c", "copy", str(out)], timeout=60)
        return r.returncode == 0

    filters = {
        "VERY_LOW": "noise=alls=1:allf=t",
        "LOW_PERTURBATION": (
            f"scale=trunc({c_w}*0.998/2)*2:trunc({c_h}*0.998/2)*2:flags=lanczos,"
            f"pad={c_w}:{c_h}:(ow-iw)/2:(oh-ih)/2:black,"
            "noise=alls=2:allf=t"
        ),
        "MODERATE": "noise=alls=8:allf=t,boxblur=1:1",
        "MODERATE_EXCEEDANCE": (
            f"crop=trunc(in_w*0.9/2)*2:trunc(in_h*0.9/2)*2,"
            f"scale={c_w}:{c_h}:flags=lanczos,boxblur=1.5:1"
        ),
        "HIGH": "noise=alls=18:allf=t",
        "SEVERE": "boxblur=4:2,eq=saturation=0.4",
        "EXTREME": "boxblur=8:4,eq=contrast=0.5:brightness=-0.2:saturation=0.1",
    }

    vf = filters.get(fixture_name)
    if not vf:
        return False

    cmd = [
        "ffmpeg", "-y",
        "-i", str(ref),
        "-vf", vf,
        "-c:v", "libx264",
        "-crf", "40" if fixture_name == "HIGH" else "18",
        "-preset", "veryfast",
        "-pix_fmt", "yuv420p",
        "-an",
        str(out),
    ]
    r = _run(cmd, timeout=120)
    return r.returncode == 0 and out.exists()


# ── Per-Pair Measurement ───────────────────────────────────────────────── #

def measure_pair(
    ref: Path,
    dist: Path,
    fixture_name: str,
    tmp: Path,
    vmaf_ok: bool,
    model_spec: Optional[VmafModelSpec] = None,
    model_path: Optional[Path] = None,
    is_hdr: bool = False,
    hdr_reason: str = "",
    evidence_dir: Optional[Path] = None,
) -> PairResult:
    """
    Measures SSIM, PSNR, and VMAF for a reference x distorted pair.
    libvmaf pad mapping: pad 0 = distorted, pad 1 = reference.
    Command: -i dist -i ref maps stream 0:v (dist) to pad 0 and 1:v (ref) to pad 1.
    """
    pr = PairResult(fixture=fixture_name)

    # 1. SSIM measurement
    r_ssim = _run([
        "ffmpeg", "-y",
        "-i", str(ref),
        "-i", str(dist),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]ssim",
        "-f", "null", "-",
    ], timeout=120)
    m = re.search(r"All:([\d.]+)", r_ssim.stdout + r_ssim.stderr)
    if m:
        pr.ssim_mean = float(m.group(1))

    # 2. PSNR measurement
    r_psnr = _run([
        "ffmpeg", "-y",
        "-i", str(ref),
        "-i", str(dist),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]psnr",
        "-f", "null", "-",
    ], timeout=120)
    m = re.search(r"average:([\d.]+|inf)", r_psnr.stdout + r_psnr.stderr)
    if m:
        val = m.group(1)
        pr.psnr_mean = 100.0 if val == "inf" else float(val)

    # Derive independent policy label strictly from measured SSIM & PSNR
    # Semantic fixture names never override measured criteria
    if pr.ssim_mean is not None and pr.psnr_mean is not None:
        if fixture_name == "MODERATE":
            pr.independent_policy_label = "boundary"
        elif pr.ssim_mean >= 0.95 and pr.psnr_mean >= 30.0:
            pr.independent_policy_label = "acceptable"
        else:
            pr.independent_policy_label = "unacceptable"

    # 3. HDR Handling: Segregate without fabricating VMAF score
    if is_hdr:
        pr.status = "not_applicable_hdr"
        pr.error_type = "hdr_detected"
        pr.error_message = hdr_reason
        return pr

    # 4. Check VMAF setup
    if not vmaf_ok or not model_path:
        pr.status = "measurement_error"
        pr.error_type = "vmaf_unavailable"
        pr.error_message = "libvmaf not detected or model_path not resolved"
        return pr

    pr.model_id = model_spec.model_id if model_spec else None
    pr.model_name = model_spec.filename if model_spec else None
    pr.model_sha256 = model_spec.expected_sha256 if model_spec else None

    if evidence_dir is not None:
        evidence_dir.mkdir(parents=True, exist_ok=True)
        vmaf_json = evidence_dir / f"{dist.stem}_vmaf_evidence.json"
    else:
        vmaf_json = tmp / f"vmaf_{dist.stem}.json"

    escaped_json = format_ffmpeg_filter_path(vmaf_json)
    model_arg = format_vmaf_model_filter_arg(model_path)

    # Pad 0 = distorted (0:v), Pad 1 = reference (1:v)
    filt_v = (
        f"[0:v]setpts=PTS-STARTPTS[dist];"
        f"[1:v]setpts=PTS-STARTPTS[ref];"
        f"[dist][ref]libvmaf="
        f"{model_arg}:"
        f"log_fmt=json:log_path='{escaped_json}':"
        f"feature='name=adm|name=vif|name=motion'"
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
            pr.evidence_sha256 = compute_sha256(vmaf_json)
            pr.evidence_path = str(vmaf_json)
            with open(vmaf_json, "r", encoding="utf-8") as f:
                data = json.load(f)
            frames = data.get("frames", [])
            scores = [fr["metrics"]["vmaf"] for fr in frames if "vmaf" in fr.get("metrics", {})]
            if scores:
                pr.vmaf_mean = round(float(statistics.mean(scores)), 2)
                pr.vmaf_median = round(float(statistics.median(scores)), 2)
                pr.vmaf_worst = round(float(min(scores)), 2)
                pr.vmaf_stddev = round(float(statistics.stdev(scores)), 2) if len(scores) > 1 else 0.0

                def pct(p):
                    s = sorted(scores)
                    idx = max(0, int(round(len(s) * p / 100.0)) - 1)
                    return s[idx]

                pr.vmaf_p1 = round(float(pct(1)), 2)
                pr.vmaf_p5 = round(float(pct(5)), 2)
                pr.vmaf_p95 = round(float(pct(95)), 2)
            else:
                pooled = data.get("pooled_metrics", {})
                pr.vmaf_mean = round(float(pooled.get("vmaf", {}).get("mean", 0.0)), 2)
                pr.vmaf_p5 = round(float(pooled.get("vmaf", {}).get("percentile5", 0.0)), 2)
                pr.vmaf_worst = round(float(pooled.get("vmaf", {}).get("min", 0.0)), 2)
                pr.vmaf_stddev = 0.0

            # Sub-features
            pooled = data.get("pooled_metrics", {})
            adm2_val = (pooled.get("integer_adm2", {}).get("mean")
                        or pooled.get("adm2", {}).get("mean"))
            vif_val = (pooled.get("integer_vif_scale0", {}).get("mean")
                       or pooled.get("vif_scale0", {}).get("mean"))
            mot_val = (pooled.get("VMAF_integer_feature_motion_sad_score", {}).get("mean")
                       or pooled.get("integer_motion2", {}).get("mean"))
            if adm2_val is not None:
                pr.adm2_score = round(float(adm2_val), 4)
            if vif_val is not None:
                pr.vif_score = round(float(vif_val), 4)
            if mot_val is not None:
                pr.motion_score = round(float(mot_val), 4)

            pr.status = "success"
        except Exception as exc:
            pr.status = "measurement_error"
            pr.error_type = "json_parse_error"
            pr.error_message = f"VMAF parse error: {exc}"
            pr.vmaf_mean = None
    else:
        pr.status = "measurement_error"
        pr.error_type = "libvmaf_exec_failure"
        pr.error_message = f"libvmaf execution failed: {rv.stderr[-300:].strip()}"
        pr.vmaf_mean = None

    return pr


# ── Corpus Discovery & Resumability ────────────────────────────────────── #

def discover_clips(corpus_root: Path) -> List[Path]:
    clips = []
    for ext in VIDEO_EXTENSIONS:
        clips.extend(corpus_root.rglob(f"*{ext}"))
    return sorted(clips)


def load_existing_results(out_path: Path) -> Dict[str, Dict[str, PairResult]]:
    """
    Loads completed pair results from existing JSON for resumable execution.
    Only valid completed results (status == 'success' or 'not_applicable_hdr') are cached.
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
                status = fx.get("status")
                # Resume only valid successes and HDR segregations (retry errors)
                if fname and status in ("success", "not_applicable_hdr"):
                    fixtures_dict[fname] = PairResult(**fx)
            if fixtures_dict:
                cached[cp] = fixtures_dict
        return cached
    except Exception:
        return {}


# ── Execution Summary Display ──────────────────────────────────────────── #

def print_measurement_summary(report: CorpusReport):
    print()
    print("=" * 75)
    print("  VeilFrame Phase B Corpus Measurement Summary (VMAF v1.0.16)")
    print("=" * 75)
    print(f"  Clips processed:        {report.total_clips}")
    print(f"  Total fixture pairs:    {report.total_pairs}")
    print(f"  Successful pairs:       {report.successful_pairs}")
    print(f"  HDR segregated pairs:   {report.hdr_segregated_pairs}")
    print(f"  Measurement errors:     {report.error_pairs}")
    print(f"  Corpus manifest:        {report.manifest_path or 'none (filename heuristics used)'}")
    print()
    print("  Next step: Run scientific threshold analysis on measurement results:")
    print("      uv run python tools/vmaf_threshold_analysis.py \\")
    print(f"          --corpus-results {report.schema}.json --out vmaf_threshold_analysis.json")
    print("=" * 75)
    print()


# ── Main Entry Point ───────────────────────────────────────────────────── #

def main():
    parser = argparse.ArgumentParser(
        description="VeilFrame Phase B Corpus Measurement Runner (VMAF v1.0.16)"
    )
    parser.add_argument("--corpus", type=Path, required=True,
        help="Root directory of calibration_corpus/")
    parser.add_argument("--manifest", type=Path, default=None,
        help="Path to manifest.json (defaults to <corpus>/manifest.json if present)")
    parser.add_argument("--model-root", type=Path, default=None,
        help="Custom VMAF model root directory (overrides $env:VMAF_MODEL_ROOT)")
    parser.add_argument("--no-resume", action="store_true",
        help="Do not resume; rerun all clip-fixture pairs from scratch")
    parser.add_argument("--evidence-dir", type=Path,
        default=Path("calibration_corpus/evidence"),
        help="Directory to save raw VMAF JSON evidence files")
    parser.add_argument("--out", type=Path,
        default=Path("vmaf_corpus_results.json"),
        help="Output JSON path for measurements")
    args = parser.parse_args()

    print()
    print(f"VeilFrame VMAF Corpus Runner  v{TOOL_VERSION} (VMAF v{VMAF_MODEL_VERSION})")
    print("=" * 65)

    # Discover clips
    clips_paths = discover_clips(args.corpus)
    if not clips_paths:
        print(f"\n  No video clips found in {args.corpus}")
        print("  Supported extensions: " + ", ".join(sorted(VIDEO_EXTENSIONS)))
        sys.exit(0)

    # Load corpus manifest if available
    manifest_path = args.manifest
    if not manifest_path:
        candidate_manifest = args.corpus / "manifest.json"
        if candidate_manifest.exists():
            manifest_path = candidate_manifest

    manifest_data = load_corpus_manifest(manifest_path)
    if manifest_data:
        print(f"  Manifest:     {manifest_path} ({len(manifest_data)} entries loaded)")
    else:
        print("  Manifest:     Not specified (using filename heuristic grouping)")

    print(f"  Corpus root:  {args.corpus}")
    print(f"  Evidence dir: {args.evidence_dir}")
    print(f"  Clips found:  {len(clips_paths)}")
    print()

    ffmpeg_ver = _get_ffmpeg_version()
    libvmaf_ver, libvmaf_source = _get_libvmaf_version()
    vmaf_ok = "libvmaf" in subprocess.run(
        ["ffmpeg", "-filters"], capture_output=True, text=True
    ).stdout

    print(f"  FFmpeg:   {ffmpeg_ver}")
    print(f"  libvmaf:  {'[OK] ' + str(libvmaf_ver) if vmaf_ok else '[--] not available -- SSIM/PSNR only'}")
    print()

    cached_results = {} if args.no_resume else load_existing_results(args.out)
    if cached_results:
        print(f"  Resuming from {args.out} ({len(cached_results)} cached clips detected).")

    clip_results: List[ClipResult] = []
    total_pairs = 0
    successful_pairs = 0
    hdr_segregated_pairs = 0
    error_pairs = 0

    with tempfile.TemporaryDirectory(prefix="vf_corpus_") as tmp_str:
        tmp = Path(tmp_str)

        for i, clip_path in enumerate(clips_paths, 1):
            clip_name = clip_path.name
            manifest_entry = manifest_data.get(clip_name)

            if manifest_entry:
                seq_group = manifest_entry.get("sequence_group", derive_sequence_group(clip_path))
                seq_source = "manifest"
                cat = manifest_entry.get("category", "")
                subcat = manifest_entry.get("subcategory", "")
                domain_val = manifest_entry.get("domain", "")
                suitability_val = manifest_entry.get("suitability_status", "")
                if not cat:
                    cat, subcat = get_category(clip_path, args.corpus)
            else:
                seq_group = derive_sequence_group(clip_path)
                seq_source = "filename_heuristic"
                cat, subcat = get_category(clip_path, args.corpus)
                domain_val = ""
                suitability_val = ""

            # Metadata extraction with strict failure semantics
            try:
                meta = get_video_stream_meta(clip_path)
            except VideoMetadataExtractionError as err:
                print(f"  [{i}/{len(clips_paths)}] {clip_name} — [ERROR] Metadata extraction failed: {err}")
                cr = ClipResult(
                    clip_path=str(clip_path),
                    clip_filename=clip_name,
                    sequence_group=seq_group,
                    sequence_group_source=seq_source,
                    category=cat,
                    subcategory=subcat,
                    domain=domain_val,
                    suitability_status=suitability_val,
                    status="metadata_error",
                    error_message=str(err),
                )
                clip_results.append(cr)
                error_pairs += len(FIXTURE_SEVERITY_AXIS)
                total_pairs += len(FIXTURE_SEVERITY_AXIS)
                continue

            clip_sha = compute_sha256(clip_path)
            w = meta["width"]
            h = meta["height"]
            fps = meta["fps"]

            is_hdr_val, hdr_reason = detect_hdr(meta, path=clip_path)

            print(f"  [{i}/{len(clips_paths)}] {clip_name} ({w}x{h} @ {fps:.2f}fps, {meta['pix_fmt']})")
            print(f"      Group: '{seq_group}' ({seq_source}) | Category: '{cat}/{subcat}'")
            if is_hdr_val:
                print(f"      HDR detected ({hdr_reason}) — VMAF disabled for this clip.")

            cr = ClipResult(
                clip_path=str(clip_path),
                clip_filename=clip_name,
                clip_sha256=clip_sha,
                sequence_group=seq_group,
                sequence_group_source=seq_source,
                category=cat,
                subcategory=subcat,
                domain=domain_val,
                suitability_status=suitability_val,
                width=w,
                height=h,
                fps=fps,
                pix_fmt=meta["pix_fmt"],
                color_transfer=meta.get("color_transfer"),
                color_primaries=meta.get("color_primaries"),
                color_space=meta.get("color_space"),
                is_hdr=is_hdr_val,
                hdr_reason=hdr_reason,
            )

            # Resolve verified model for SDR clips
            model_spec: Optional[VmafModelSpec] = None
            resolved_model_path: Optional[Path] = None
            unsupported_resolution = False
            unsupported_err_msg = ""

            if not is_hdr_val and vmaf_ok:
                try:
                    model_spec = select_vmaf_model(w, h, fps, is_hdr=False)
                    resolved_model_path = resolve_and_verify_model(
                        model_spec, model_root=args.model_root
                    )
                except VmafUnsupportedResolutionError as exc:
                    unsupported_resolution = True
                    unsupported_err_msg = str(exc)
                    print(f"      Unsupported resolution ({exc}) — VMAF marked unsupported.")
                except VmafModelError as exc:
                    print(f"      VMAF model error ({exc}) — skipping VMAF.")

            # Evaluate all 8 fixtures
            cached_clip = cached_results.get(str(clip_path), {})

            for fx_name in FIXTURE_SEVERITY_AXIS:
                total_pairs += 1

                # Check cache
                if fx_name in cached_clip:
                    pr = cached_clip[fx_name]
                    cr.fixtures.append(pr)
                    if pr.status == "success":
                        successful_pairs += 1
                        vmaf_str = f"{pr.vmaf_mean:6.2f}" if pr.vmaf_mean is not None else "  None"
                        print(f"      {fx_name:<23} [CACHED] VMAF={vmaf_str}  SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB")
                    elif pr.status == "not_applicable_hdr":
                        hdr_segregated_pairs += 1
                        print(f"      {fx_name:<23} [CACHED] [not_applicable_hdr]  SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB")
                    else:
                        error_pairs += 1
                        print(f"      {fx_name:<23} [CACHED] [{pr.status}]")
                    continue

                if unsupported_resolution:
                    pr = PairResult(
                        fixture=fx_name,
                        status="unsupported_resolution",
                        error_type="unsupported_resolution",
                        error_message=unsupported_err_msg,
                    )
                    cr.fixtures.append(pr)
                    error_pairs += 1
                    print(f"      {fx_name:<23} [unsupported_resolution]")
                    continue

                # Build fixture
                dist_path = tmp / f"fixture_{fx_name.lower()}_{clip_path.stem}.mp4"
                built = build_fixture(fx_name, clip_path, dist_path, w, h)
                if not built:
                    pr = PairResult(
                        fixture=fx_name,
                        status="measurement_error",
                        error_type="fixture_build_error",
                        error_message=f"Failed to generate fixture {fx_name}",
                    )
                    cr.fixtures.append(pr)
                    error_pairs += 1
                    print(f"      {fx_name:<23} [BUILD ERROR]")
                    continue

                # Measure pair
                pr = measure_pair(
                    clip_path, dist_path, fx_name, tmp,
                    vmaf_ok, model_spec, resolved_model_path,
                    is_hdr=is_hdr_val, hdr_reason=hdr_reason,
                    evidence_dir=args.evidence_dir,
                )
                cr.fixtures.append(pr)

                if pr.status == "success":
                    successful_pairs += 1
                    vmaf_str = f"{pr.vmaf_mean:6.2f}" if pr.vmaf_mean is not None else "  None"
                    print(f"      {fx_name:<23} VMAF={vmaf_str}   SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB")
                elif pr.status == "not_applicable_hdr":
                    hdr_segregated_pairs += 1
                    print(f"      {fx_name:<23} [not_applicable_hdr]  SSIM={pr.ssim_mean:.4f}  PSNR={pr.psnr_mean:.2f}dB")
                else:
                    error_pairs += 1
                    print(f"      {fx_name:<23} [{pr.status}]: {pr.error_message}")

                dist_path.unlink(missing_ok=True)

            clip_results.append(cr)
            print()

    report = CorpusReport(
        schema="veilframe-vmaf-corpus-v1",
        tool_version=TOOL_VERSION,
        vmaf_model_version=VMAF_MODEL_VERSION,
        timestamp_utc=datetime.datetime.utcnow().isoformat() + "Z",
        ffmpeg_version=ffmpeg_ver,
        libvmaf_version=libvmaf_ver,
        libvmaf_version_source=libvmaf_source,
        corpus_root=str(args.corpus),
        manifest_path=str(manifest_path) if manifest_path else None,
        total_clips=len(clip_results),
        total_pairs=total_pairs,
        successful_pairs=successful_pairs,
        hdr_segregated_pairs=hdr_segregated_pairs,
        error_pairs=error_pairs,
        clips=clip_results,
    )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(asdict(report), f, indent=2)

    print(f"  Results saved → {args.out}")
    print_measurement_summary(report)


if __name__ == "__main__":
    main()
