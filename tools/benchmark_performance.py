#!/usr/bin/env python3
"""
VeilFrame Long-Form Performance & Quality Gate Benchmark Harness.

Instruments and measures:
- Stage-level latencies (Analyze, Pre-Sanitize, Encode, Post-Sanitize, Quality Gate, Verify)
- Processing throughputs: Encode FPS, Quality Audit FPS, Overall FPS, and Real-Time Factor (RTF)
- Peak resident memory (RSS / Working Set)
- Three-Tier Quality Gate verdict and statistical metric distribution (SSIM, PSNR, Spectral, Temporal)
- Supports both real video inputs and synthetic 1080p60 clips.
"""
import sys
import time
import json
import shutil
import argparse
import subprocess
from pathlib import Path
from typing import Optional, Dict, Any

from veilframe.core.resources import get_ffmpeg_path
from veilframe.core.analyzer import analyze_video
from veilframe.core.pipeline import run_pipeline
from veilframe.presets.manager import PresetManager
from veilframe.models.settings import ProcessingSettings, VisualBudgetPolicy
from veilframe.core.verifier import VerificationReport


def generate_synthetic_video(
    dst_path: Path,
    duration_sec: float = 5.0,
    fps: int = 60,
    resolution: str = "1920x1080",
) -> Path:
    """Generates a synthetic high-framerate test video using FFmpeg testsrc2."""
    ffmpeg = str(get_ffmpeg_path())
    dst_path = Path(dst_path).resolve()
    dst_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"[*] Generating synthetic {resolution} @ {fps}fps ({duration_sec:.1f}s) -> {dst_path.name}...")
    cmd = [
        ffmpeg,
        "-hide_banner",
        "-nostats",
        "-y",
        "-f", "lavfi",
        "-i", f"testsrc2=size={resolution}:rate={fps}:duration={duration_sec}",
        "-f", "lavfi",
        "-i", f"sine=frequency=1000:duration={duration_sec}",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "veryfast",
        "-crf", "18",
        "-c:a", "aac",
        "-b:a", "128k",
        str(dst_path),
    ]
    t0 = time.perf_counter()
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    elapsed = time.perf_counter() - t0
    print(f"[+] Synthetic generation complete in {elapsed:.2f}s ({duration_sec / elapsed:.2f}x real-time)")
    return dst_path


def run_benchmark(
    input_video: Path,
    preset_name: str = "5%",
    output_dir: Optional[Path] = None,
    keep_output: bool = False,
) -> Dict[str, Any]:
    """Runs full pipeline benchmark on the given input video."""
    input_video = Path(input_video).resolve()
    if not input_video.exists():
        raise FileNotFoundError(f"Input video does not exist: {input_video}")

    if output_dir is None:
        output_dir = input_video.parent / "benchmark_runs"
    output_dir.mkdir(parents=True, exist_ok=True)

    output_video = output_dir / f"bench_{input_video.stem}_{preset_name}.mp4"

    # Analyze input
    input_info = analyze_video(input_video)
    v_info = input_info.video
    w = v_info.width if v_info else 0
    h = v_info.height if v_info else 0
    fps = v_info.fps if v_info else 0.0
    duration = input_info.duration
    frames = v_info.frame_count if (v_info and v_info.frame_count > 0) else int(round(duration * (fps or 30.0)))
    size_mb = input_info.size_bytes / (1024 * 1024)

    # Load preset settings
    pm = PresetManager()
    settings: ProcessingSettings = pm.apply_preset(preset_name)
    if settings.quality_gate is None:
        settings.quality_gate = VisualBudgetPolicy()
    settings.quality_gate.enabled = True

    print("\n" + "=" * 60)
    print("VEILFRAME PIPELINE PERFORMANCE BENCHMARK")
    print("=" * 60)
    print(f"Input File:        {input_video.name}")
    print(f"Resolution:        {w}x{h}")
    print(f"Frame Rate:        {fps:.2f} FPS")
    print(f"Duration:          {duration:.2f}s ({frames} frames)")
    print(f"File Size:         {size_mb:.2f} MB")
    print(f"Selected Preset:   {preset_name.upper()}")
    print("-" * 60)

    # Execute pipeline with timing
    def progress_callback(pct: float, msg: str):
        print(f"[{pct:5.1f}%] {msg}")

    t_start = time.perf_counter()
    report: VerificationReport = run_pipeline(
        src_path=input_video,
        dst_path=output_video,
        settings=settings,
        progress_callback=progress_callback,
    )
    total_time = time.perf_counter() - t_start

    # Clean up output video if not keeping
    if not keep_output and output_video.exists():
        output_video.unlink()

    # Calculate real-time factor
    rtf = duration / total_time if total_time > 0 else 0.0

    print("\n" + "=" * 60)
    print("BENCHMARK RESULTS & STAGE BREAKDOWN")
    print("=" * 60)
    print(f"{'Stage':<26} {'Latency (s)':<14} {'Share (%)'}")
    print("-" * 60)
    for stage, elapsed in report.stage_timings.items():
        if stage == "total":
            continue
        share = (elapsed / total_time) * 100.0 if total_time > 0 else 0.0
        print(f"{stage:<26} {elapsed:10.3f}s     {share:6.1f}%")
    print("-" * 60)
    print(f"{'TOTAL PIPELINE':<26} {total_time:10.3f}s     100.0%")
    print(f"{'Real-Time Factor (RTF)':<26} {rtf:10.2f}x ({'Faster' if rtf >= 1.0 else 'Slower'} than real-time)")
    print("-" * 60)
    print("THROUGHPUT METRICS:")
    print(f"  Encoding Throughput:     {report.encode_fps:8.2f} FPS")
    print(f"  Quality Audit Throughput:{report.audit_fps:8.2f} FPS")
    print(f"  Overall Throughput:      {report.total_fps:8.2f} FPS")
    print(f"  Peak Resident Memory:    {report.peak_ram_mb:8.2f} MB")
    print("-" * 60)

    qr = report.quality_report
    substages = qr.raw_details.get("substage_latencies", {}) if qr and qr.raw_details else {}
    if substages:
        print("AUDIT SUB-STAGE LATENCIES:")
        print(f"  Timestamp Audit Latency: {substages.get('temporal_audit_sec', 0.0):8.3f}s")
        print(f"  Energy Audit Latency:    {substages.get('energy_audit_sec', 0.0):8.3f}s")
        print(f"  Fidelity Audit (SSIM):   {substages.get('fidelity_audit_sec', 0.0):8.3f}s")
        print(f"  Native Stream Audit:     {substages.get('native_audit_sec', 0.0):8.3f}s")
        print("-" * 60)

    if qr:
        print("QUALITY GATE AUDIT:")
        print(f"  Overall Verdict:         {qr.three_tier_verdict.overall_verdict}")
        print(f"  Tier 1 Policy Score:     {'PASS' if qr.three_tier_verdict.tier1_policy_passed else 'FAIL'} ({qr.policy_score.aggregate_policy_score_pct:.2f}% / Max {qr.policy_score.policy_ceiling_pct:.1f}%)")
        print(f"  Tier 2 SSIM Mean:        {qr.ssim.mean:.4f} (P5={qr.ssim.p5:.4f}, Worst={qr.ssim.min_val:.4f})")
        print(f"  Tier 2 PSNR Mean:        {qr.psnr.mean:.2f} dB (Worst={qr.psnr.min_val:.2f} dB)")
        print(f"  Tier 3 Temporal Cadence: {qr.temporal_metrics.cadence_deviation_pct:.2f}% (Drift={qr.temporal_metrics.timestamp_drift_max_sec:.4f}s)")
        print(f"  Tier 3 Reordered Frames: {qr.temporal_metrics.reordered_frames}")
        print(f"  Tier 3 Missing Frames:   {qr.temporal_metrics.missing_frames}")
    print("=" * 60 + "\n")

    bench_data = {
        "input_file": str(input_video),
        "preset": preset_name,
        "media_specs": {
            "width": w,
            "height": h,
            "fps": fps,
            "duration_sec": duration,
            "total_frames": frames,
            "size_mb": size_mb,
        },
        "stage_latencies_sec": report.stage_timings,
        "substage_latencies_sec": substages,
        "performance": {
            "total_latency_sec": total_time,
            "real_time_factor": rtf,
            "encode_fps": report.encode_fps,
            "audit_fps": report.audit_fps,
            "overall_fps": report.total_fps,
            "peak_memory_mb": report.peak_ram_mb,
        },
        "quality_gate": {
            "verdict": qr.three_tier_verdict.overall_verdict if qr else "N/A",
            "tier1_policy_passed": qr.three_tier_verdict.tier1_policy_passed if qr else None,
            "tier2_fidelity_passed": qr.three_tier_verdict.tier2_fidelity_passed if qr else None,
            "tier3_temporal_passed": qr.three_tier_verdict.tier3_temporal_passed if qr else None,
            "ssim_mean": qr.ssim.mean if qr else None,
            "ssim_p5": qr.ssim.p5 if qr else None,
            "ssim_worst": qr.ssim.min_val if qr else None,
            "psnr_mean_db": qr.psnr.mean if qr else None,
            "psnr_worst_db": qr.psnr.min_val if qr else None,
            "cadence_deviation_pct": qr.temporal_metrics.cadence_deviation_pct if qr else None,
            "timestamp_drift_max_sec": qr.temporal_metrics.timestamp_drift_max_sec if qr else None,
        } if qr else None,
    }

    return bench_data


def main():
    parser = argparse.ArgumentParser(description="VeilFrame Performance Benchmark Harness")
    parser.add_argument("video", nargs="?", default=None, help="Path to input video file to benchmark")
    parser.add_argument("--synthetic", action="store_true", help="Generate a synthetic video for benchmarking")
    parser.add_argument("--duration", type=float, default=10.0, help="Duration in seconds for synthetic video (default: 10.0)")
    parser.add_argument("--fps", type=int, default=60, help="Framerate for synthetic video (default: 60)")
    parser.add_argument("--resolution", type=str, default="1920x1080", help="Resolution for synthetic video (default: 1920x1080)")
    parser.add_argument("--preset", type=str, default="5%", help="Preset to use (default: 5%)")
    parser.add_argument("--output-json", type=str, default=None, help="Path to save benchmark JSON results")
    parser.add_argument("--keep-output", action="store_true", help="Retain encoded output media file")

    args = parser.parse_args()

    input_path: Optional[Path] = None
    temp_synthetic_dir: Optional[Path] = None

    if args.synthetic or args.video is None:
        temp_synthetic_dir = Path("scratch/benchmark_synth")
        temp_synthetic_dir.mkdir(parents=True, exist_ok=True)
        synth_file = temp_synthetic_dir / f"synthetic_{args.resolution}_{args.fps}fps_{int(args.duration)}s.mp4"
        generate_synthetic_video(
            dst_path=synth_file,
            duration_sec=args.duration,
            fps=args.fps,
            resolution=args.resolution,
        )
        input_path = synth_file
    else:
        input_path = Path(args.video)

    try:
        data = run_benchmark(
            input_video=input_path,
            preset_name=args.preset,
            keep_output=args.keep_output,
        )

        if args.output_json:
            out_json = Path(args.output_json).resolve()
            out_json.parent.mkdir(parents=True, exist_ok=True)
            out_json.write_text(json.dumps(data, indent=2), encoding="utf-8")
            print(f"[+] Benchmark results saved to: {out_json}")

    finally:
        # Clean up synthetic video if generated and not explicitly kept
        if temp_synthetic_dir and not args.keep_output:
            shutil.rmtree(str(temp_synthetic_dir), ignore_errors=True)


if __name__ == "__main__":
    main()
