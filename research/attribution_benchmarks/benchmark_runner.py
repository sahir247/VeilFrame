"""
Attribution benchmark suite orchestrator.
========================================

Runs all 4 forensic detector suites (Perceptual Hash, ENF, Motion, PRNU)
against reference and transformed video streams, producing a unified 3-layer BenchmarkSuiteReport.
"""
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union
import numpy as np

from .common.models import (
    BenchmarkEnvironment,
    BenchmarkResult,
    BenchmarkSuiteReport,
)
from .common.io import (
    compute_file_sha256,
    get_ffmpeg_build_info,
    decode_audio_pcm,
    decode_video_frames_yuv,
)
from .detectors.perceptual_hash import evaluate_perceptual_hash_benchmark
from .detectors.enf import evaluate_enf_benchmark
from .detectors.motion import evaluate_motion_benchmark
from .detectors.prnu import evaluate_prnu_pair_benchmark, evaluate_prnu_corpus_benchmark


def run_benchmark_on_pair(
    ref_video_path: Union[str, Path],
    trans_video_path: Union[str, Path],
    max_frames: int = 60,
    target_res: Tuple[int, int] = (320, 240),
    audio_sample_rate: int = 1000,
    manifest_hash: Optional[str] = None,
    random_seed: Optional[int] = None,
) -> BenchmarkSuiteReport:
    """
    Executes the complete attribution benchmark suite on a reference vs. transformed video pair.
    """
    ref_p = Path(ref_video_path)
    trans_p = Path(trans_video_path)

    ref_sha = compute_file_sha256(ref_p) if ref_p.exists() else "unknown"
    trans_sha = compute_file_sha256(trans_p) if trans_p.exists() else "unknown"

    ffmpeg_info = get_ffmpeg_build_info()

    env = BenchmarkEnvironment(
        numpy_version=np.__version__,
        ffmpeg_version=ffmpeg_info.get("version", "unknown"),
        dataset_manifest_hash=manifest_hash,
        reference_sha256=ref_sha,
        transformed_sha256=trans_sha,
        sampling_configuration={
            "max_frames": max_frames,
            "target_resolution": list(target_res),
            "audio_sample_rate": audio_sample_rate,
        },
        random_seed=random_seed,
    )

    # 1. Decode video frames
    ref_frames = decode_video_frames_yuv(ref_p, max_frames=max_frames, target_res=target_res)
    trans_frames = decode_video_frames_yuv(trans_p, max_frames=max_frames, target_res=target_res)

    # 2. Decode audio streams
    ref_audio, _ = decode_audio_pcm(ref_p, target_sample_rate=audio_sample_rate)
    trans_audio, _ = decode_audio_pcm(trans_p, target_sample_rate=audio_sample_rate)

    benchmarks: Dict[str, BenchmarkResult] = {}

    # Run Suite 1: Perceptual Hashes
    res_phash = evaluate_perceptual_hash_benchmark(ref_frames, trans_frames, threshold=10, env=env)
    benchmarks["perceptual_hash"] = res_phash

    # Run Suite 2: ENF Spectral Power
    res_enf = evaluate_enf_benchmark(ref_audio, trans_audio, sample_rate=audio_sample_rate, env=env)
    benchmarks["enf"] = res_enf

    # Run Suite 3: Motion & Frame Delta
    res_motion = evaluate_motion_benchmark(ref_frames, trans_frames, correlation_threshold=0.85, env=env)
    benchmarks["motion"] = res_motion

    # Run Suite 4: PRNU Sensor Fingerprint
    res_prnu = evaluate_prnu_pair_benchmark(ref_frames, trans_frames, pce_threshold=60.0, env=env)
    benchmarks["prnu"] = res_prnu

    # Aggregate Findings Summary
    summary = {
        "phash_hamming_distance_mean": res_phash.signal_metrics.values.get("phash_hamming_mean"),
        "phash_match_status": res_phash.detector_metrics.match_status,
        "enf_attenuation_max_db": res_enf.signal_metrics.values.get("max_attenuation_db"),
        "enf_match_status": res_enf.detector_metrics.match_status,
        "motion_frame_delta_corr": res_motion.signal_metrics.values.get("frame_delta_correlation_mean"),
        "motion_match_status": res_motion.detector_metrics.match_status,
        "prnu_pce_score": res_prnu.detector_metrics.match_score,
        "prnu_match_status": res_prnu.detector_metrics.match_status,
    }

    return BenchmarkSuiteReport(
        suite_version="0.1.0",
        timestamp_utc=datetime.now(timezone.utc).isoformat(),
        benchmarks=benchmarks,
        summary_findings=summary,
        environment=env,
    )
