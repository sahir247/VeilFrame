"""
Common utilities, data models, and statistical routines for attribution benchmarks.
"""
from .models import (
    BenchmarkEnvironment,
    SignalMetrics,
    DetectorMetrics,
    AttributionMetrics,
    BenchmarkResult,
    BenchmarkSuiteReport,
)
from .statistics import (
    hamming_distance,
    bit_error_rate,
    pearson_correlation,
    welch_psd,
    compute_pce_and_ncc,
    compute_roc_and_auc,
)
from .io import (
    compute_file_sha256,
    get_ffmpeg_build_info,
    decode_audio_pcm,
    decode_video_frames_yuv,
)

__all__ = [
    "BenchmarkEnvironment",
    "SignalMetrics",
    "DetectorMetrics",
    "AttributionMetrics",
    "BenchmarkResult",
    "BenchmarkSuiteReport",
    "hamming_distance",
    "bit_error_rate",
    "pearson_correlation",
    "welch_psd",
    "compute_pce_and_ncc",
    "compute_roc_and_auc",
    "compute_file_sha256",
    "get_ffmpeg_build_info",
    "decode_audio_pcm",
    "decode_video_frames_yuv",
]
