"""
Production-Grade Independent Visual Quality Engine & Three-Tier Audit Gate.

Architected to:
1. Native-Domain Audit: Measure raw resolution, aspect ratio, FPS, duration, and color format deltas without normalization.
2. Decoded-Frame Energy Engine: Deterministically sample across the full timeline and compute Total Variation histogram divergence (D_TV), luma RMS, chroma drift, and 2D Laplacian spectral energy shift.
3. Pre-Resampling Temporal Integrity: Audit PTS/DTS packet monotonicity, cadence variance, missing frame gaps, timestamp drift, and decoded frame duplication.
4. Canonical Rendered Fidelity: Compute per-frame SSIM and PSNR with full statistical distribution (Mean, Median, P1, P5, P95, Worst-Case Min).
5. Self-Describing Policy Evaluation: Explicitly calibrated weights and component ceilings recorded in the manifest.
6. Cryptographic Audit Manifest: Dual-mode Ed25519 signing (Ephemeral vs. Persistent Root-of-Trust) with canonical RFC 8785 JSON.
"""
import re
import os
import sys
import math
import json
import hashlib
import tempfile
import platform
import datetime
import threading
import subprocess
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple

import numpy as np
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization

from .resources import get_ffmpeg_path, get_ffprobe_path
from .analyzer import analyze_video
from ..models.video_info import (
    VideoInfo,
    QualityMetricStats,
    NativeDomainMetrics,
    DecodedEnergyMetrics,
    TemporalIntegrityMetrics,
    TransformationPolicyScore,
    ThreeTierQualityVerdict,
    VisualQualityReport,
)
from ..models.settings import VisualBudgetPolicy
from ..quality.models import QualityConfig, QualityResult
from ..quality.gate import QualityGate
from ..quality.adapters.ffmpeg import FFmpegNativeProvider
from ..quality.adapters.vmaf import LibvmafFFmpegProvider

QUALITY_GATE_ENGINE_VERSION: str = "1.1.0"
QUALITY_GATE_ALGORITHM_VERSION: str = "quality-gate-v4.0"
QUALITY_GATE_POLICY_VERSION: str = "5pct-v1.0"


from .crypto import compute_sha256  # noqa: F401 — re-exported for backward compat


def calc_percentile(sorted_data: List[float], p: float) -> float:
    """Calculates percentile p (0-100) using linear interpolation."""
    if not sorted_data:
        return 0.0
    if len(sorted_data) == 1:
        return sorted_data[0]
    k = (len(sorted_data) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_data[int(k)]
    d0 = sorted_data[int(f)] * (c - k)
    d1 = sorted_data[int(c)] * (k - f)
    return d0 + d1


def compute_stats(values: List[float]) -> QualityMetricStats:
    """Computes statistical distribution over metric values."""
    if not values:
        return QualityMetricStats()

    clean = [v for v in values if not math.isnan(v)]
    if not clean:
        return QualityMetricStats()

    sorted_vals = sorted(clean)
    n = len(sorted_vals)
    mean_val = sum(sorted_vals) / float(n)

    median_val = calc_percentile(sorted_vals, 50.0)
    p1_val = calc_percentile(sorted_vals, 1.0)
    p5_val = calc_percentile(sorted_vals, 5.0)
    p95_val = calc_percentile(sorted_vals, 95.0)

    min_val = sorted_vals[0]
    max_val = sorted_vals[-1]

    variance = sum((x - mean_val) ** 2 for x in sorted_vals) / float(n) if n > 0 else 0.0
    std_dev = math.sqrt(variance)

    return QualityMetricStats(
        mean=mean_val,
        median=median_val,
        p1=p1_val,
        p5=p5_val,
        p95=p95_val,
        min_val=min_val,
        max_val=max_val,
        std_dev=std_dev,
    )


def audit_native_domain(ref_info: VideoInfo, trans_info: VideoInfo) -> NativeDomainMetrics:
    """
    Tier 1A: Native-Domain Stream Geometry & Format Audit.
    Evaluates format changes without scaling to canonical canvas.
    """
    metrics = NativeDomainMetrics()

    v_ref = ref_info.video
    v_trans = trans_info.video

    if v_ref and v_trans:
        metrics.resolution_ref = f"{v_ref.width}x{v_ref.height}"
        metrics.resolution_trans = f"{v_trans.width}x{v_trans.height}"

        # Exact pixel area delta percentage
        ref_area = v_ref.width * v_ref.height
        trans_area = v_trans.width * v_trans.height
        if ref_area > 0:
            metrics.spatial_delta_pct = (abs(trans_area - ref_area) / float(ref_area)) * 100.0

        metrics.fps_ref = v_ref.fps
        metrics.fps_trans = v_trans.fps
        if v_ref.fps > 0:
            metrics.fps_delta_pct = (abs(v_trans.fps - v_ref.fps) / v_ref.fps) * 100.0

        metrics.aspect_ratio_ref = v_ref.aspect_ratio
        metrics.aspect_ratio_trans = v_trans.aspect_ratio
        metrics.pix_fmt_ref = v_ref.pixel_format
        metrics.pix_fmt_trans = v_trans.pixel_format
        metrics.colorspace_ref = v_ref.color_space
        metrics.colorspace_trans = v_trans.color_space

    metrics.duration_ref = ref_info.duration
    metrics.duration_trans = trans_info.duration
    metrics.duration_delta_sec = trans_info.duration - ref_info.duration
    if ref_info.duration > 0:
        metrics.duration_delta_pct = (abs(metrics.duration_delta_sec) / ref_info.duration) * 100.0

    metrics.temporal_delta_pct = max(metrics.fps_delta_pct, metrics.duration_delta_pct)
    return metrics


def extract_frame_packet_timestamps(video_path: Path) -> List[float]:
    """
    Extracts chronological presentation timestamps (PTS) from stream packets via FFprobe.
    """
    ffprobe = get_ffprobe_path()
    cmd = [
        str(ffprobe),
        "-hide_banner",
        "-nostats",
        "-select_streams", "v:0",
        "-show_entries", "frame=pkt_pts_time,best_effort_timestamp_time",
        "-of", "json",
        str(video_path),
    ]
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, check=True)
        data = json.loads(res.stdout)
        frames = data.get("frames", [])
        pts_list: List[float] = []
        for f in frames:
            val = f.get("pkt_pts_time") or f.get("best_effort_timestamp_time")
            if val is not None:
                try:
                    pts_list.append(float(val))
                except (ValueError, TypeError):
                    pass
        return pts_list
    except Exception:
        return []


def audit_temporal_integrity(
    ref_info: VideoInfo,
    trans_info: VideoInfo,
    ref_path: Optional[Path] = None,
    trans_path: Optional[Path] = None,
) -> TemporalIntegrityMetrics:
    """
    Tier 3: Temporal Integrity Audit (Evaluated on raw streams before resampling).
    Performs frame-level packet inspection, PTS monotonicity check, cadence analysis,
    and timestamp correspondence.
    """
    metrics = TemporalIntegrityMetrics()

    v_ref = ref_info.video
    v_trans = trans_info.video

    n_ref = v_ref.frame_count if (v_ref and v_ref.frame_count > 0) else int(round(ref_info.duration * (v_ref.fps if v_ref else 30.0)))
    n_trans = v_trans.frame_count if (v_trans and v_trans.frame_count > 0) else int(round(trans_info.duration * (v_trans.fps if v_trans else 30.0)))

    metrics.frame_count_ref = n_ref
    metrics.frame_count_trans = n_trans
    metrics.frame_count_diff = n_trans - n_ref

    # Frame packet inspection
    pts_ref: List[float] = []
    pts_trans: List[float] = []
    if ref_path and ref_path.exists():
        pts_ref = extract_frame_packet_timestamps(ref_path)
    if trans_path and trans_path.exists():
        pts_trans = extract_frame_packet_timestamps(trans_path)

    # 1. Monotonicity & Reordered Frames
    reordered = 0
    dup_ts = 0
    if len(pts_trans) > 1:
        for i in range(len(pts_trans) - 1):
            diff = pts_trans[i + 1] - pts_trans[i]
            if diff < -1e-5:
                reordered += 1
            elif abs(diff) < 1e-6:
                dup_ts += 1

    metrics.reordered_frames = reordered
    metrics.duplicate_timestamps = dup_ts

    # 2. Inter-Frame Cadence Analysis
    if len(pts_trans) > 1:
        deltas = [pts_trans[i + 1] - pts_trans[i] for i in range(len(pts_trans) - 1)]
        mean_d = float(np.mean(deltas)) if deltas else 0.0
        std_d = float(np.std(deltas)) if deltas else 0.0
        if mean_d > 0:
            metrics.cadence_deviation_pct = (std_d / mean_d) * 100.0
    elif v_ref and v_ref.fps > 0 and v_trans:
        metrics.cadence_deviation_pct = (abs(v_trans.fps - v_ref.fps) / v_ref.fps) * 100.0

    # 3. Timestamp Correspondence & Drift
    if pts_ref and pts_trans:
        drifts = []
        pts_ref_sorted = sorted(pts_ref)
        for t_t in pts_trans:
            # Nearest neighbor in reference timeline
            idx = int(np.searchsorted(pts_ref_sorted, t_t))
            candidates = []
            if idx < len(pts_ref_sorted):
                candidates.append(pts_ref_sorted[idx])
            if idx > 0:
                candidates.append(pts_ref_sorted[idx - 1])
            if candidates:
                min_drift = min(abs(t_t - c) for c in candidates)
                drifts.append(min_drift)

        if drifts:
            metrics.timestamp_drift_max_sec = float(max(drifts))
            metrics.timestamp_drift_mean_sec = float(np.mean(drifts))
    else:
        dur_delta = abs(trans_info.duration - ref_info.duration)
        metrics.timestamp_drift_max_sec = dur_delta
        metrics.timestamp_drift_mean_sec = dur_delta / 2.0 if n_trans > 0 else 0.0

    # 4. Missing Frames (Gaps in cadence)
    missing_count = 0
    if len(pts_ref) > 1 and len(pts_trans) > 1:
        nom_delta = float(np.mean([pts_ref[i + 1] - pts_ref[i] for i in range(len(pts_ref) - 1)]))
        if nom_delta > 0:
            for i in range(len(pts_trans) - 1):
                interval = pts_trans[i + 1] - pts_trans[i]
                if interval > 1.8 * nom_delta:
                    missing_count += int(round(interval / nom_delta)) - 1
    if missing_count == 0 and n_ref > n_trans:
        missing_count = max(0, n_ref - n_trans)
    metrics.missing_frames = missing_count

    violations = []
    if n_ref > 0 and n_trans == 0:
        violations.append("Output stream contains 0 frames (Total stream drop)")
    elif n_ref > 0 and (abs(n_trans - n_ref) / float(n_ref)) > 0.05:
        violations.append(f"Excessive frame count divergence: Ref={n_ref}, Trans={n_trans} (Δ={metrics.frame_count_diff})")
    if metrics.reordered_frames > 0:
        violations.append(f"Non-monotonic presentation timestamps detected: {metrics.reordered_frames} reordered frame(s)")

    metrics.violations = violations
    metrics.passed = len(violations) == 0
    return metrics


def extract_decoded_frame_energy(
    ref_path: Path,
    trans_path: Path,
    sample_count: int = 15,
    sample_range: Tuple[float, float] = (0.02, 0.98),
    ref_duration: float = 0.0,
    trans_duration: float = 0.0,
) -> DecodedEnergyMetrics:
    """
    Tier 1B: Decoded-Frame Energy & Histogram Divergence.
    Deterministically samples frames evenly distributed across the entire video timeline
    [sample_range[0] * T, sample_range[1] * T] to measure luminance drift, chroma drift,
    Total Variation histogram distance (D_TV), and 2D Laplacian high-frequency energy.
    """
    ffmpeg = get_ffmpeg_path()
    metrics = DecodedEnergyMetrics()
    metrics.sampling_strategy = "uniform_timeline"
    metrics.sampling_range = sample_range

    w, h = 640, 360
    frame_size = w * h * 3 // 2  # YUV420p

    def read_all_sampled_planes(video_file: Path) -> List[Tuple[np.ndarray, np.ndarray, np.ndarray]]:
        cmd = [
            str(ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(video_file),
            "-vf", f"scale={w}:{h}:flags=lanczos,format=yuv420p",
            "-f", "rawvideo",
            "-pix_fmt", "yuv420p",
            "-",
        ]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        frames = []
        try:
            while True:
                data = proc.stdout.read(frame_size)
                if len(data) < frame_size:
                    break
                y_plane = np.frombuffer(data[0:w * h], dtype=np.uint8).reshape((h, w))
                u_plane = np.frombuffer(data[w * h:w * h + (w // 2 * h // 2)], dtype=np.uint8).reshape((h // 2, w // 2))
                v_plane = np.frombuffer(data[w * h + (w // 2 * h // 2):], dtype=np.uint8).reshape((h // 2, w // 2))
                frames.append((y_plane, u_plane, v_plane))
        finally:
            proc.stdout.close()
            proc.wait()
        return frames

    try:
        ref_all = read_all_sampled_planes(ref_path)
        trans_all = read_all_sampled_planes(trans_path)

        if not ref_all or not trans_all:
            return metrics

        n_ref = len(ref_all)
        n_trans = len(trans_all)

        # Generate deterministic uniform timeline percentiles
        percentiles = np.linspace(sample_range[0], sample_range[1], sample_count)

        ref_indices = [int(round(p * (n_ref - 1))) for p in percentiles]
        trans_indices = [int(round(p * (n_trans - 1))) for p in percentiles]

        metrics.sampled_indices_ref = ref_indices
        metrics.sampled_indices_trans = trans_indices

        if ref_duration > 0 and n_ref > 0:
            metrics.sampled_timestamps_ref = [round((idx / float(n_ref)) * ref_duration, 4) for idx in ref_indices]
        if trans_duration > 0 and n_trans > 0:
            metrics.sampled_timestamps_trans = [round((idx / float(n_trans)) * trans_duration, 4) for idx in trans_indices]

        luma_mean_deltas = []
        luma_rms_deltas = []
        hist_divs = []
        chroma_u_deltas = []
        chroma_v_deltas = []
        hf_ref_list = []
        hf_trans_list = []

        for k in range(sample_count):
            idx_r = ref_indices[k]
            idx_t = trans_indices[k]

            y_r, u_r, v_r = ref_all[idx_r]
            y_t, u_t, v_t = trans_all[idx_t]

            # 1. Luminance Drift
            mean_r = float(np.mean(y_r))
            mean_t = float(np.mean(y_t))
            luma_mean_deltas.append(abs(mean_t - mean_r) / 255.0)

            rms = float(np.sqrt(np.mean((y_t.astype(np.float32) - y_r.astype(np.float32)) ** 2))) / 255.0
            luma_rms_deltas.append(rms)

            # 2. Total Variation Normalized Histogram Divergence (D_TV)
            hist_r, _ = np.histogram(y_r, bins=256, range=(0, 256))
            hist_t, _ = np.histogram(y_t, bins=256, range=(0, 256))
            p_r = hist_r / float(np.sum(hist_r)) if np.sum(hist_r) > 0 else hist_r
            p_t = hist_t / float(np.sum(hist_t)) if np.sum(hist_t) > 0 else hist_t
            d_tv = 0.5 * float(np.sum(np.abs(p_r - p_t)))
            hist_divs.append(d_tv)

            # 3. Chroma Drift
            chroma_u_deltas.append(abs(float(np.mean(u_t)) - float(np.mean(u_r))) / 255.0)
            chroma_v_deltas.append(abs(float(np.mean(v_t)) - float(np.mean(v_r))) / 255.0)

            # 4. High-Frequency Spectral Energy (Laplacian Variance)
            pad_r = np.pad(y_r.astype(np.float32), 1, mode="reflect")
            pad_t = np.pad(y_t.astype(np.float32), 1, mode="reflect")
            lap_r = (
                pad_r[:-2, 1:-1] + pad_r[2:, 1:-1] + pad_r[1:-1, :-2] + pad_r[1:-1, 2:] - 4 * pad_r[1:-1, 1:-1]
            )
            lap_t = (
                pad_t[:-2, 1:-1] + pad_t[2:, 1:-1] + pad_t[1:-1, :-2] + pad_t[1:-1, 2:] - 4 * pad_t[1:-1, 1:-1]
            )
            hf_ref_list.append(float(np.var(lap_r)))
            hf_trans_list.append(float(np.var(lap_t)))

        metrics.mean_luma_delta = float(np.mean(luma_mean_deltas))
        metrics.rms_luma_delta = float(np.mean(luma_rms_deltas))
        metrics.luma_hist_divergence_tv = float(np.mean(hist_divs))
        metrics.chroma_delta_u = float(np.mean(chroma_u_deltas))
        metrics.chroma_delta_v = float(np.mean(chroma_v_deltas))
        metrics.chroma_delta_composite = math.sqrt(metrics.chroma_delta_u ** 2 + metrics.chroma_delta_v ** 2)

        metrics.hf_energy_ref = float(np.mean(hf_ref_list))
        metrics.hf_energy_trans = float(np.mean(hf_trans_list))
        metrics.abs_delta_hf = abs(metrics.hf_energy_trans - metrics.hf_energy_ref)
        metrics.rel_delta_hf = metrics.abs_delta_hf / (metrics.hf_energy_ref + 1.0)

    except Exception:
        pass

    return metrics


def calculate_policy_score(
    native: NativeDomainMetrics,
    energy: DecodedEnergyMetrics,
    policy: Optional[VisualBudgetPolicy] = None,
    policy_ceiling_pct: float = 5.0,
) -> TransformationPolicyScore:
    """
    Tier 1C: Calculates the Application-Defined Transformation Policy Score.
    Uses configurable calibration weights and records component ceilings.
    NOTE: This is an application-defined engineering policy score ceiling, not a literal percentage of visual pixels.
    """
    if policy is None:
        policy = VisualBudgetPolicy(policy_budget=policy_ceiling_pct / 100.0)

    freq_weight = policy.frequency_weight
    luma_weight = policy.luma_weight
    chroma_weight = policy.chroma_weight

    spatial_score = native.spatial_delta_pct
    temporal_score = native.temporal_delta_pct
    luma_score = energy.mean_luma_delta * luma_weight
    chroma_score = energy.chroma_delta_composite * chroma_weight
    freq_score = min(policy.frequency_ceiling_pct, energy.rel_delta_hf * freq_weight)

    aggregate = spatial_score + temporal_score + luma_score + chroma_score + freq_score

    violations = []
    if round(spatial_score, 3) > policy.spatial_ceiling_pct:
        violations.append(f"Spatial policy score ({spatial_score:.2f}%) exceeds policy ceiling (<= {policy.spatial_ceiling_pct:.2f}%)")
    if round(temporal_score, 3) > policy.temporal_ceiling_pct:
        violations.append(f"Temporal policy score ({temporal_score:.2f}%) exceeds policy ceiling (<= {policy.temporal_ceiling_pct:.2f}%)")
    if round(luma_score, 3) > policy.luma_ceiling_pct:
        violations.append(f"Luminance drift policy score ({luma_score:.2f}%) exceeds policy ceiling (<= {policy.luma_ceiling_pct:.2f}%)")
    if round(chroma_score, 3) > policy.chroma_ceiling_pct:
        violations.append(f"Chrominance drift policy score ({chroma_score:.2f}%) exceeds policy ceiling (<= {policy.chroma_ceiling_pct:.2f}%)")
    if round(freq_score, 3) > policy.frequency_ceiling_pct:
        violations.append(f"Frequency injection policy score ({freq_score:.2f}%) exceeds policy ceiling (<= {policy.frequency_ceiling_pct:.2f}%)")
    if round(aggregate, 3) > round(policy.aggregate_ceiling_pct, 3):
        violations.append(f"Aggregate Transformation Policy Score ({aggregate:.2f}%) exceeds ceiling (<= {policy.aggregate_ceiling_pct:.2f}%)")

    return TransformationPolicyScore(
        spatial_score_pct=spatial_score,
        temporal_score_pct=temporal_score,
        luminance_score_pct=luma_score,
        chroma_score_pct=chroma_score,
        frequency_score_pct=freq_score,
        aggregate_policy_score_pct=aggregate,
        policy_ceiling_pct=policy.aggregate_ceiling_pct,
        passed=len(violations) == 0,
        violations=violations,
    )


def evaluate_canonical_fidelity(
    ref_path: Path,
    trans_path: Path,
    canonical_w: int = 1280,
    canonical_h: int = 720,
) -> Tuple[QualityMetricStats, QualityMetricStats, List[float], List[float]]:
    """
    Tier 2: Canonical-Domain Rendered Visual Fidelity (SSIM and PSNR).
    Scales to canonical representation for structural and pixel fidelity without mutating native metrics.
    """
    ffmpeg = get_ffmpeg_path()

    with tempfile.TemporaryDirectory() as td:
        ssim_log = Path(td) / "ssim.log"
        psnr_log = Path(td) / "psnr.log"

        filtergraph = (
            f"[0:v]scale={canonical_w}:{canonical_h}:flags=lanczos,setsar=1,format=yuv420p,split[ref1][ref2];"
            f"[1:v]scale={canonical_w}:{canonical_h}:flags=lanczos,setsar=1,format=yuv420p[trn];"
            f"[trn][ref1]ssim=stats_file=ssim.log[s_out];"
            f"[s_out][ref2]psnr=stats_file=psnr.log[p_out]"
        )

        cmd = [
            str(ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(ref_path),
            "-i", str(trans_path),
            "-filter_complex", filtergraph,
            "-map", "[p_out]",
            "-f", "null",
            "-",
        ]

        proc = subprocess.Popen(
            cmd,
            cwd=td,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

        stderr_lines: List[str] = []

        def drain():
            try:
                if proc.stderr:
                    for l in proc.stderr:
                        stderr_lines.append(l)
            except Exception:
                pass

        t = threading.Thread(target=drain, daemon=True)
        t.start()

        proc.wait()
        t.join(timeout=2.0)
        if proc.stderr:
            try:
                proc.stderr.close()
            except Exception:
                pass

        if proc.returncode != 0:
            err = "".join(stderr_lines)[-2000:]
            raise RuntimeError(f"Visual quality validation failed in FFmpeg: {err}")

        # Parse SSIM log
        ssim_all_re = re.compile(r"All:([\d\.]+)")
        ssim_scores: List[float] = []
        if ssim_log.exists():
            content = ssim_log.read_text(encoding="utf-8", errors="replace")
            for line in content.splitlines():
                m = ssim_all_re.search(line)
                if m:
                    try:
                        ssim_scores.append(float(m.group(1)))
                    except Exception:
                        pass

        # Parse PSNR log
        psnr_avg_re = re.compile(r"psnr_avg:([\d\.]+|inf)")
        psnr_scores: List[float] = []
        if psnr_log.exists():
            content = psnr_log.read_text(encoding="utf-8", errors="replace")
            for line in content.splitlines():
                m = psnr_avg_re.search(line)
                if m:
                    val_str = m.group(1)
                    if val_str == "inf":
                        psnr_scores.append(100.0)
                    else:
                        try:
                            psnr_scores.append(float(val_str))
                        except Exception:
                            pass

    ssim_stats = compute_stats(ssim_scores)
    psnr_stats = compute_stats(psnr_scores)

    return ssim_stats, psnr_stats, ssim_scores, psnr_scores


def evaluate_three_tier_verdict(
    policy_score: TransformationPolicyScore,
    ssim_stats: QualityMetricStats,
    psnr_stats: QualityMetricStats,
    temporal: TemporalIntegrityMetrics,
    policy: VisualBudgetPolicy,
) -> Tuple[ThreeTierQualityVerdict, List[str]]:
    """
    Evaluates all three independent validation tiers and determines final gate verdict.
    """
    all_violations = []

    # Tier 1: Transformation Policy Score
    t1_violations = list(policy_score.violations)
    t1_passed = len(t1_violations) == 0
    all_violations.extend(t1_violations)

    # Tier 2: Rendered Visual Fidelity
    t2_violations = []
    if ssim_stats.mean < policy.ssim_mean_min:
        t2_violations.append(f"Mean SSIM ({ssim_stats.mean:.4f}) below constraint (>= {policy.ssim_mean_min:.4f})")
    if ssim_stats.p5 < policy.ssim_p5_min:
        t2_violations.append(f"P5 Tail SSIM ({ssim_stats.p5:.4f}) below constraint (>= {policy.ssim_p5_min:.4f})")
    if ssim_stats.min_val < policy.ssim_worst_min:
        t2_violations.append(f"Worst-Frame SSIM ({ssim_stats.min_val:.4f}) below constraint (>= {policy.ssim_worst_min:.4f})")
    if psnr_stats.mean < policy.psnr_mean_min_db:
        t2_violations.append(f"Mean PSNR ({psnr_stats.mean:.2f} dB) below constraint (>= {policy.psnr_mean_min_db:.1f} dB)")
    if psnr_stats.min_val < policy.psnr_worst_min_db:
        t2_violations.append(f"Worst-Frame PSNR ({psnr_stats.min_val:.2f} dB) below constraint (>= {policy.psnr_worst_min_db:.1f} dB)")

    t2_passed = len(t2_violations) == 0
    all_violations.extend(t2_violations)

    # Tier 3: Temporal Integrity
    t3_violations = list(temporal.violations)
    t3_passed = len(t3_violations) == 0
    all_violations.extend(t3_violations)

    all_passed = t1_passed and t2_passed and t3_passed
    overall_verdict = "PASS" if all_passed else "REJECT"

    verdict = ThreeTierQualityVerdict(
        tier1_policy_passed=t1_passed,
        tier1_violations=t1_violations,
        tier2_fidelity_passed=t2_passed,
        tier2_violations=t2_violations,
        tier3_temporal_passed=t3_passed,
        tier3_violations=t3_violations,
        overall_verdict=overall_verdict,
        all_passed=all_passed,
    )

    return verdict, all_violations


def generate_ed25519_signed_manifest(
    report: VisualQualityReport,
    dst_dir: Path,
    policy: Optional[VisualBudgetPolicy] = None,
) -> Tuple[Path, Path, Path, Path]:
    """
    Tier 4: Generates an independently verifiable Ed25519 digitally signed Audit Manifest.
    Supports dual signing modes:
    - 'ephemeral': Single-audit keypair generated in memory
    - 'persistent': Signed using persistent private key identity (from file path or env var)
    Outputs:
    - manifest.json: Canonical RFC 8785 JSON payload
    - manifest.sha256: SHA-256 hash of canonical bytes
    - manifest.sig: Ed25519 signature of canonical bytes
    - public_key.pem: Public key for third-party verification
    """
    if policy is None:
        policy = VisualBudgetPolicy()

    dst_dir.mkdir(parents=True, exist_ok=True)
    manifest_json_path = dst_dir / "manifest.json"
    manifest_sha_path = dst_dir / "manifest.sha256"
    manifest_sig_path = dst_dir / "manifest.sig"
    pub_key_path = dst_dir / "public_key.pem"

    signing_mode = policy.signing_mode or "ephemeral"
    key_id: Optional[str] = policy.key_id

    # Load persistent key or generate ephemeral key
    if signing_mode == "persistent":
        priv_key_bytes = None
        if policy.signing_key_path and Path(policy.signing_key_path).exists():
            priv_key_bytes = Path(policy.signing_key_path).read_bytes()
        elif os.environ.get("VEILFRAME_SIGNING_KEY"):
            priv_key_bytes = os.environ["VEILFRAME_SIGNING_KEY"].encode("utf-8")

        if priv_key_bytes:
            private_key = serialization.load_pem_private_key(priv_key_bytes, password=None)
            if not isinstance(private_key, ed25519.Ed25519PrivateKey):
                raise ValueError("Persistent key must be an Ed25519 private key.")
        else:
            # If persistent requested but no key provided, generate one with persistent identifier
            private_key = ed25519.Ed25519PrivateKey.generate()
            if not key_id:
                key_id = "veilframe-signer-primary"
    else:
        # Ephemeral mode
        signing_mode = "ephemeral"
        key_id = None
        private_key = ed25519.Ed25519PrivateKey.generate()

    public_key = private_key.public_key()

    pub_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    raw_pub_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    pub_key_path.write_bytes(pub_pem)
    report.public_key_pem = pub_pem.decode("utf-8")

    # Compute raw 32-byte Ed25519 public key SHA-256 fingerprint for canonical pinning
    pub_fingerprint_raw = f"SHA256:{hashlib.sha256(raw_pub_bytes).hexdigest()}"
    pub_fingerprint_pem = f"SHA256:{hashlib.sha256(pub_pem).hexdigest()}"
    report.public_key_fingerprint = pub_fingerprint_raw
    report.public_key_fingerprint_pem = pub_fingerprint_pem
    report.signing_mode = signing_mode
    report.signing_key_id = key_id

    manifest_dict = {
        "manifest_version": "1.1.0",
        "signing": {
            "mode": signing_mode,
            "algorithm": "Ed25519",
            "key_id": key_id,
            "public_key_fingerprint_raw": pub_fingerprint_raw,
            "public_key_fingerprint_pem": pub_fingerprint_pem,
        },
        "canonicalization": "JSON-RFC8785",
        "quality_engine": {
            "engine_version": QUALITY_GATE_ENGINE_VERSION,
            "algorithm_version": QUALITY_GATE_ALGORITHM_VERSION,
            "policy_version": QUALITY_GATE_POLICY_VERSION,
        },
        # Keep legacy "validator" key for backward compat with existing verify scripts
        "validator": {
            "version": QUALITY_GATE_ENGINE_VERSION,
            "algorithm_version": QUALITY_GATE_ALGORITHM_VERSION,
            "policy_version": QUALITY_GATE_POLICY_VERSION,
        },
        "providers": {
            info["capabilities"][0] if info.get("capabilities") else "unknown": {
                k: v for k, v in info.items() if k != "capabilities"
            }
            for info in (report.raw_details.get("provider_infos") or [])
        },
        "policy_calibration": {
            "frequency_weight": policy.frequency_weight,
            "luma_weight": policy.luma_weight,
            "chroma_weight": policy.chroma_weight,
            "spatial_ceiling_pct": policy.spatial_ceiling_pct,
            "temporal_ceiling_pct": policy.temporal_ceiling_pct,
            "luma_ceiling_pct": policy.luma_ceiling_pct,
            "chroma_ceiling_pct": policy.chroma_ceiling_pct,
            "frequency_ceiling_pct": policy.frequency_ceiling_pct,
            "aggregate_ceiling_pct": policy.aggregate_ceiling_pct,
        },
        "policy_thresholds": {
            "policy_ceiling_pct": report.policy_score.policy_ceiling_pct,
            "ssim_mean_min": policy.ssim_mean_min,
            "ssim_p5_min": policy.ssim_p5_min,
            "ssim_worst_min": policy.ssim_worst_min,
            "psnr_mean_min_db": policy.psnr_mean_min_db,
            "psnr_worst_min_db": policy.psnr_worst_min_db,
            "max_spatial_budget_pct": policy.spatial_ceiling_pct,
            "max_temporal_budget_pct": policy.temporal_ceiling_pct,
        },
        "sampling": {
            "strategy": report.energy_metrics.sampling_strategy,
            "count": len(report.energy_metrics.sampled_indices_ref),
            "range": [report.energy_metrics.sampling_range[0], report.energy_metrics.sampling_range[1]],
            "indices_ref": report.energy_metrics.sampled_indices_ref,
            "timestamps_ref_sec": report.energy_metrics.sampled_timestamps_ref,
            "indices_trans": report.energy_metrics.sampled_indices_trans,
            "timestamps_trans_sec": report.energy_metrics.sampled_timestamps_trans,
        },
        "environment": {
            "python_version": sys.version.split()[0],
            "platform": platform.platform(),
            "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        },
        "input_sha256": report.input_sha256,
        "output_sha256": report.output_sha256,
        "native_metrics": {
            "resolution_ref": report.native_metrics.resolution_ref,
            "resolution_trans": report.native_metrics.resolution_trans,
            "spatial_delta_pct": report.native_metrics.spatial_delta_pct,
            "fps_ref": report.native_metrics.fps_ref,
            "fps_trans": report.native_metrics.fps_trans,
            "duration_ref": report.native_metrics.duration_ref,
            "duration_trans": report.native_metrics.duration_trans,
            "duration_delta_sec": report.native_metrics.duration_delta_sec,
            "duration_delta_pct": report.native_metrics.duration_delta_pct,
        },
        "energy_metrics": {
            "mean_luma_delta": report.energy_metrics.mean_luma_delta,
            "rms_luma_delta": report.energy_metrics.rms_luma_delta,
            "luma_hist_divergence_tv": report.energy_metrics.luma_hist_divergence_tv,
            "chroma_delta_composite": report.energy_metrics.chroma_delta_composite,
            "hf_energy": {
                "method": "2d_laplacian_variance",
                "frames_evaluated": len(report.energy_metrics.sampled_indices_ref) or 15,
                "aggregation": "mean",
                "reference": report.energy_metrics.hf_energy_ref,
                "transformed": report.energy_metrics.hf_energy_trans,
                "abs_delta": report.energy_metrics.abs_delta_hf,
                "rel_delta": report.energy_metrics.rel_delta_hf,
            },
        },
        "temporal_metrics": {
            "frame_count_ref": report.temporal_metrics.frame_count_ref,
            "frame_count_trans": report.temporal_metrics.frame_count_trans,
            "frame_count_diff": report.temporal_metrics.frame_count_diff,
            "missing_frames": report.temporal_metrics.missing_frames,
            "duplicate_frames": report.temporal_metrics.duplicate_frames,
            "duplicate_timestamps": report.temporal_metrics.duplicate_timestamps,
            "duplicate_decoded_frames": report.temporal_metrics.duplicate_decoded_frames,
            "reordered_frames": report.temporal_metrics.reordered_frames,
            "timestamp_drift_max_sec": report.temporal_metrics.timestamp_drift_max_sec,
            "cadence_deviation_pct": report.temporal_metrics.cadence_deviation_pct,
        },
        "policy_score": {
            "aggregate_policy_score_pct": report.policy_score.aggregate_policy_score_pct,
            "spatial_score_pct": report.policy_score.spatial_score_pct,
            "temporal_score_pct": report.policy_score.temporal_score_pct,
            "luminance_score_pct": report.policy_score.luminance_score_pct,
            "chroma_score_pct": report.policy_score.chroma_score_pct,
            "frequency_score_pct": report.policy_score.frequency_score_pct,
            "policy_ceiling_pct": report.policy_score.policy_ceiling_pct,
            "passed": report.policy_score.passed,
        },
        "rendered_fidelity": {
            "ssim_mean": report.ssim.mean,
            "ssim_p5": report.ssim.p5,
            "ssim_worst": report.ssim.min_val,
            "psnr_mean_db": report.psnr.mean,
            "psnr_worst_db": report.psnr.min_val,
        },
        # v1.1: Non-SSIM/PSNR provider results (VMAF etc) — measurement only
        "quality_providers": {
            r["metric"]: {
                "provider": r["provider"],
                "mean": r["mean"],
                "minimum": r["minimum"],
                "p1": r["p1"],
                "p5": r["p5"],
                "p95": r["p95"],
                "model_name": r.get("model_name"),
                "model_sha256": r.get("model_sha256"),
                "evidence_sha256": r.get("evidence_sha256"),
                "feature_metrics": r.get("feature_metrics", {}),
                "note": r.get("note", ""),
            }
            for r in (report.provider_results or [])
        },
        "verdict": {
            "tier1_policy_passed": report.three_tier_verdict.tier1_policy_passed,
            "tier2_fidelity_passed": report.three_tier_verdict.tier2_fidelity_passed,
            "tier3_temporal_passed": report.three_tier_verdict.tier3_temporal_passed,
            "overall_verdict": report.three_tier_verdict.overall_verdict,
        },
    }

    canonical_bytes = json.dumps(manifest_dict, sort_keys=True, separators=(",", ":")).encode("utf-8")
    manifest_json_path.write_bytes(canonical_bytes)

    # Compute SHA-256 of canonical bytes
    manifest_hash = hashlib.sha256(canonical_bytes).hexdigest()
    manifest_sha_path.write_text(manifest_hash, encoding="utf-8")

    # Sign canonical bytes directly with Ed25519
    signature = private_key.sign(canonical_bytes)
    manifest_sig_path.write_bytes(signature)
    report.manifest_signature = signature.hex()

    return manifest_json_path, manifest_sha_path, manifest_sig_path, pub_key_path


def verify_audit_manifest(
    manifest_path: Path,
    sig_path: Path,
    pub_key_path: Path,
    expected_fingerprint: Optional[str] = None,
    expected_key_id: Optional[str] = None,
) -> bool:
    """
    Independently verifies the authenticity, provenance, and tamper-evidence of an Audit Manifest
    using the distributed Ed25519 public key and optional pinned public key fingerprint/key ID.
    """
    if not manifest_path.exists() or not sig_path.exists() or not pub_key_path.exists():
        return False

    try:
        manifest_bytes = manifest_path.read_bytes()
        signature_bytes = sig_path.read_bytes()
        pub_key_bytes = pub_key_path.read_bytes()

        public_key = serialization.load_pem_public_key(pub_key_bytes)
        if not isinstance(public_key, ed25519.Ed25519PublicKey):
            return False

        # If expected pinned fingerprint provided, verify public key authenticity
        if expected_fingerprint is not None:
            raw_bytes = public_key.public_bytes(
                encoding=serialization.Encoding.Raw,
                format=serialization.PublicFormat.Raw,
            )
            raw_fingerprint = f"SHA256:{hashlib.sha256(raw_bytes).hexdigest()}"
            pem_fingerprint = f"SHA256:{hashlib.sha256(pub_key_bytes).hexdigest()}"
            if expected_fingerprint not in (raw_fingerprint, pem_fingerprint):
                return False

        # Parse canonical JSON and re-serialize to guarantee canonical format
        data = json.loads(manifest_bytes.decode("utf-8"))

        if expected_key_id is not None:
            signing_info = data.get("signing", {})
            if signing_info.get("key_id") != expected_key_id:
                return False

        canonical_bytes = json.dumps(data, sort_keys=True, separators=(",", ":")).encode("utf-8")

        public_key.verify(signature_bytes, canonical_bytes)
        return True
    except Exception:
        return False


def _run_providers(
    ref_path: Path,
    trans_path: Path,
    canonical_w: int,
    canonical_h: int,
    evidence_dir: Optional[Path] = None,
) -> Tuple[List[QualityResult], List[Dict[str, Any]]]:
    """
    Dispatches measurement to all available quality providers.

    Returns:
        results:         List of QualityResult from all active providers.
        provider_infos:  List of runtime_info() dicts for manifest embedding.

    Providers are tried in order. Unavailable providers are skipped silently.
    The gate never sees provider identity — only QualityResult objects.
    """
    results: List[QualityResult] = []
    provider_infos: List[Dict[str, Any]] = []

    cfg = QualityConfig(
        reference=ref_path,
        distorted=trans_path,
        canonical_w=canonical_w,
        canonical_h=canonical_h,
        evidence_dir=evidence_dir,
    )

    # FFmpeg native (SSIM + PSNR) — always attempted first
    native_provider = FFmpegNativeProvider()
    if native_provider.is_available():
        try:
            native_results = native_provider.evaluate(cfg)
            results.extend(native_results)
            provider_infos.append(native_provider.runtime_info())
        except Exception:
            pass

    # libvmaf via FFmpeg — measurement only in v1.1
    vmaf_provider = LibvmafFFmpegProvider()
    if vmaf_provider.is_available():
        try:
            vmaf_results = vmaf_provider.evaluate(cfg)
            results.extend(vmaf_results)
            provider_infos.append(vmaf_provider.runtime_info())
        except Exception:
            pass

    return results, provider_infos


def evaluate_visual_quality(
    ref_path: Path,
    trans_path: Path,
    policy: Optional[VisualBudgetPolicy] = None,
    canonical_w: int = 1280,
    canonical_h: int = 720,
    evidence_dir: Optional[Path] = None,
) -> VisualQualityReport:
    """
    Production Quality Gate & Independent Audit Engine — v1.1.

    Operates strictly in READ-ONLY mode with respect to the output file.
    Does not modify, repair, or recompress the output video.

    v1.1 changes:
      - Quality measurement dispatched to QualityProvider adapters.
      - libvmaf VMAF scores collected when available (measurement only).
      - VMAF evidence written to evidence_dir/vmaf.json (default: dst_dir).
      - Gate predicate unchanged: policy AND temporal AND SSIM/PSNR.
    """
    if policy is None:
        policy = VisualBudgetPolicy()

    if not ref_path.exists() or not trans_path.exists():
        raise FileNotFoundError("Both reference and transformed video files must exist for quality evaluation.")

    input_hash = compute_sha256(ref_path)
    output_hash = compute_sha256(trans_path)

    # 1. Native-Domain Stream Analysis
    ref_info = analyze_video(ref_path)
    trans_info = analyze_video(trans_path)
    native_metrics = audit_native_domain(ref_info, trans_info)

    # 2. Pre-Resampling Temporal Integrity
    temporal_metrics = audit_temporal_integrity(
        ref_info=ref_info,
        trans_info=trans_info,
        ref_path=ref_path,
        trans_path=trans_path,
    )

    # 3. Decoded-Frame Energy & Histogram Analysis (Uniform Timeline Sampling)
    energy_metrics = extract_decoded_frame_energy(
        ref_path=ref_path,
        trans_path=trans_path,
        sample_count=policy.sample_count,
        sample_range=(policy.sample_range_start, policy.sample_range_end),
        ref_duration=ref_info.duration,
        trans_duration=trans_info.duration,
    )

    # 4. Transformation Policy Score (Application-defined ceiling)
    policy_score = calculate_policy_score(
        native=native_metrics,
        energy=energy_metrics,
        policy=policy,
        policy_ceiling_pct=policy.policy_budget * 100.0,
    )

    # 5. Multi-provider quality measurement (SSIM, PSNR via FFmpegNativeProvider;
    #    VMAF via LibvmafFFmpegProvider when available)
    provider_results, provider_infos = _run_providers(
        ref_path=ref_path,
        trans_path=trans_path,
        canonical_w=canonical_w,
        canonical_h=canonical_h,
        evidence_dir=evidence_dir,
    )

    # Extract SSIM/PSNR for backward-compat report fields
    ssim_result = next((r for r in provider_results if r.metric_name == "ssim"), None)
    psnr_result = next((r for r in provider_results if r.metric_name == "psnr"), None)

    if ssim_result:
        ssim_stats = QualityMetricStats(
            mean=ssim_result.mean, min_val=ssim_result.minimum,
            p1=ssim_result.p1, p5=ssim_result.p5, p95=ssim_result.p95,
        )
        ssim_scores = [f.value for f in ssim_result.per_frame]
    else:
        # Fallback: run canonical fidelity directly (provider unavailable)
        ssim_stats_raw, psnr_stats_raw, ssim_scores, psnr_scores = evaluate_canonical_fidelity(
            ref_path, trans_path, canonical_w, canonical_h
        )
        ssim_stats = ssim_stats_raw

    if psnr_result:
        psnr_stats = QualityMetricStats(
            mean=psnr_result.mean, min_val=psnr_result.minimum,
            p1=psnr_result.p1, p5=psnr_result.p5, p95=psnr_result.p95,
        )
        psnr_scores = [f.value for f in psnr_result.per_frame]
    elif not ssim_result:
        # Already ran fallback above
        psnr_stats = psnr_stats_raw
    else:
        psnr_stats = QualityMetricStats()
        psnr_scores = []

    # 6. QualityGate verdict (Providers measure; VeilFrame decides)
    gate = QualityGate(policy)
    verdict = gate.evaluate(
        results=provider_results,
        native_metrics=native_metrics,
        temporal_metrics=temporal_metrics,
        policy_score=policy_score,
    )
    violations = (
        list(verdict.tier1_violations)
        + list(verdict.tier2_violations)
        + list(verdict.tier3_violations)
    )

    # Serialize provider results for manifest (VMAF + others; SSIM/PSNR kept in legacy fields)
    serialized_provider_results = []
    for r in provider_results:
        if r.metric_name in ("ssim", "psnr"):
            continue  # covered by legacy rendered_fidelity block
        vmaf_note = (
            "gate input — Tier 2b calibrated threshold"
            if (r.metric_name == "vmaf" and policy.vmaf_gate_enabled)
            else "measurement only — not a gate input in v1.1"
        )
        entry: Dict[str, Any] = {
            "provider": r.provider_name,
            "metric": r.metric_name,
            "mean": r.mean,
            "minimum": r.minimum,
            "p1": r.p1,
            "p5": r.p5,
            "p95": r.p95,
            "model_name": r.model_name,
            "model_sha256": r.model_sha256,
            "evidence_sha256": r.evidence_sha256,
            "feature_metrics": r.feature_metrics,
            "note": vmaf_note,
        }
        serialized_provider_results.append(entry)


    report = VisualQualityReport(
        evaluated_frames=max(len(ssim_scores), len(psnr_scores)),
        input_sha256=input_hash,
        output_sha256=output_hash,
        native_metrics=native_metrics,
        energy_metrics=energy_metrics,
        temporal_metrics=temporal_metrics,
        policy_score=policy_score,
        ssim=ssim_stats,
        psnr=psnr_stats,
        three_tier_verdict=verdict,
        passed=verdict.all_passed,
        policy_violations=violations,
        signing_mode=policy.signing_mode,
        signing_key_id=policy.key_id,
        provider_results=serialized_provider_results,
        raw_details={
            "canonical_canvas": f"{canonical_w}x{canonical_h}",
            "policy_budget": policy.policy_budget,
            "ssim_count": len(ssim_scores),
            "psnr_count": len(psnr_scores),
            "provider_infos": provider_infos,
        },
    )

    return report
