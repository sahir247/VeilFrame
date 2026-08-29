"""
VeilFrame Forensic Attribution Benchmark CLI.
=============================================

Runs empirical detector benchmarks (Perceptual Hash, ENF, Motion, PRNU)
across reference and transformed video streams or synthetic evaluation corpora.

Usage:
  uv run python tools/run_attribution_benchmarks.py --ref original.mp4 --trans transformed.mp4
  uv run python tools/run_attribution_benchmarks.py --synthetic --output-json results.json
"""
import argparse
import json
import sys
from pathlib import Path
from typing import Optional

import numpy as np

# Ensure repository root is on PYTHONPATH
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from research.attribution_benchmarks.common.models import BenchmarkEnvironment
from research.attribution_benchmarks.benchmark_runner import run_benchmark_on_pair
from research.attribution_benchmarks.detectors.perceptual_hash import evaluate_perceptual_hash_benchmark
from research.attribution_benchmarks.detectors.enf import evaluate_enf_benchmark
from research.attribution_benchmarks.detectors.motion import evaluate_motion_benchmark
from research.attribution_benchmarks.detectors.prnu import (
    evaluate_prnu_pair_benchmark,
    evaluate_prnu_corpus_benchmark,
)
from research.attribution_benchmarks.datasets.corpus import (
    generate_synthetic_evaluation_corpus,
    generate_synthetic_audio_pair,
)


def _print_banner():
    print("=" * 68)
    print("      VEILFRAME FORENSIC ATTRIBUTION BENCHMARK SUITE        ")
    print("=" * 68)


def _print_benchmark_result(res):
    name = res.benchmark_name.upper()
    status = res.status.upper()
    print(f"\n[+] Suite: {name} [{status}]")
    print(f"    Version: {res.benchmark_version}")

    # Layer 1: Signal Metrics
    print("    -- Layer 1: Signal Metrics --")
    for k, v in res.signal_metrics.values.items():
        if isinstance(v, float):
            unit = res.signal_metrics.units.get(k, "")
            print(f"      * {k}: {v:.4f} {unit}")
        elif isinstance(v, dict):
            print(f"      * {k}: (nested dictionary with {len(v)} items)")
        else:
            print(f"      * {k}: {v}")

    # Layer 2: Detector Metrics
    print("    -- Layer 2: Detector Metrics --")
    dm = res.detector_metrics
    print(f"      * Detector: {dm.detector_name} ({dm.algorithm})")
    print(f"      * Match Score: {dm.match_score:.4f} (Threshold: {dm.threshold:.4f})")
    print(f"      * Match Status: {dm.match_status}")

    # Layer 3: Attribution Metrics
    print("    -- Layer 3: Attribution Metrics --")
    am = res.attribution_metrics
    if am.classification:
        print(f"      * Pair Classification: {am.classification}")
    if am.true_positive_rate is not None:
        print(f"      * TPR: {am.true_positive_rate:.4f}, FPR: {am.false_positive_rate:.4f}, AUC: {am.area_under_curve:.4f}")
    if am.summary:
        for sk, sv in am.summary.items():
            print(f"      * {sk}: {sv}")


def main():
    parser = argparse.ArgumentParser(description="VeilFrame Forensic Attribution Benchmark Suite")
    parser.add_argument("--ref", type=Path, help="Path to reference (original) video file")
    parser.add_argument("--trans", type=Path, help="Path to transformed (sanitized) video file")
    parser.add_argument("--synthetic", action="store_true", help="Execute self-contained synthetic benchmark evaluation")
    parser.add_argument("--output-json", type=Path, help="Optional path to output structured JSON report")
    parser.add_argument("--max-frames", type=int, default=60, help="Maximum video frames to sample")
    args = parser.parse_args()

    _print_banner()

    if args.synthetic:
        print("\n[*] Generating deterministic synthetic multi-camera evaluation corpus...")
        corpus = generate_synthetic_evaluation_corpus(num_cameras=3, num_frames=20, seed=42)
        ref_audio, trans_audio = generate_synthetic_audio_pair(duration_sec=3.0, enf_freq=50.0, apply_notch=True)

        env = BenchmarkEnvironment(
            numpy_version=np.__version__,
            random_seed=42,
            sampling_configuration={"num_cameras": 3, "num_frames": 20, "audio_duration_sec": 3.0},
        )

        cam_a_ref = corpus["camera_A"]["ref"]
        cam_a_trans = corpus["camera_A"]["trans"]

        print("[*] Running Perceptual Hash benchmark on synthetic clips...")
        res_phash = evaluate_perceptual_hash_benchmark(cam_a_ref, cam_a_trans, threshold=10, env=env)
        _print_benchmark_result(res_phash)

        print("[*] Running Electrical Network Frequency (ENF) benchmark...")
        res_enf = evaluate_enf_benchmark(ref_audio, trans_audio, sample_rate=1000, env=env)
        _print_benchmark_result(res_enf)

        print("[*] Running Temporal Motion Frame-Delta benchmark...")
        res_motion = evaluate_motion_benchmark(cam_a_ref, cam_a_trans, env=env)
        _print_benchmark_result(res_motion)

        print("[*] Running PRNU Multi-Camera Attribution Corpus benchmark...")
        res_prnu_corpus = evaluate_prnu_corpus_benchmark(corpus, pce_threshold=60.0, env=env)
        _print_benchmark_result(res_prnu_corpus)

        print("\n" + "=" * 68)
        print("          SYNTHETIC ATTRIBUTION BENCHMARK COMPLETE          ")
        print("=" * 68)

        if args.output_json:
            combined = {
                "benchmark_mode": "synthetic_corpus",
                "perceptual_hash": res_phash.to_dict(),
                "enf": res_enf.to_dict(),
                "motion": res_motion.to_dict(),
                "prnu_corpus": res_prnu_corpus.to_dict(),
            }
            args.output_json.parent.mkdir(parents=True, exist_ok=True)
            args.output_json.write_text(json.dumps(combined, indent=2), encoding="utf-8")
            print(f"\n[+] Wrote benchmark report to: {args.output_json}")
        return

    if not args.ref or not args.trans:
        print("[-] Error: Please specify both --ref and --trans video files, or use --synthetic.")
        parser.print_help()
        sys.exit(1)

    print(f"\n[*] Reference Video:   {args.ref}")
    print(f"[*] Transformed Video: {args.trans}")
    print(f"[*] Sampling Frames:   {args.max_frames}")

    report = run_benchmark_on_pair(
        ref_video_path=args.ref,
        trans_video_path=args.trans,
        max_frames=args.max_frames,
    )

    for b in report.benchmarks.values():
        _print_benchmark_result(b)

    print("\n" + "=" * 68)
    print("                 BENCHMARK SUMMARY FINDINGS                 ")
    print("=" * 68)
    for k, v in report.summary_findings.items():
        print(f"  * {k:32s}: {v}")
    print("=" * 68)

    if args.output_json:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(report.to_dict(), indent=2), encoding="utf-8")
        print(f"\n[+] Wrote benchmark report to: {args.output_json}")


if __name__ == "__main__":
    main()
