"""
Core processing and privacy engine package.
"""
from .resources import get_ffmpeg_path, get_ffprobe_path, find_executable, FFmpegNotFoundError
from .analyzer import analyze_video, probe_raw
from .crop import calculate_crop, build_crop_filter
from .resize import calculate_resize, build_resize_filter
from .fps import calculate_fps, build_fps_arg
from .trim import calculate_trim, build_trim_args, parse_timestamp
from .noise import calculate_noise_strength, build_noise_filter, get_noise_level_label
from .color import build_color_filter
from .audio_pipeline import build_audio_filtergraph
from .sanitizer import pre_sanitize, post_sanitize
from .encoder import run_encode_pass, build_encode_cmd
from .verifier import verify_output, VerificationReport
from .validator import (
    evaluate_visual_quality,
    generate_ed25519_signed_manifest,
    verify_audit_manifest,
    QUALITY_GATE_ENGINE_VERSION,
    QUALITY_GATE_ALGORITHM_VERSION,
    QUALITY_GATE_POLICY_VERSION,
)
from .pipeline import run_pipeline

__all__ = [
    "get_ffmpeg_path",
    "get_ffprobe_path",
    "find_executable",
    "FFmpegNotFoundError",
    "analyze_video",
    "probe_raw",
    "calculate_crop",
    "build_crop_filter",
    "calculate_resize",
    "build_resize_filter",
    "calculate_fps",
    "build_fps_arg",
    "calculate_trim",
    "build_trim_args",
    "parse_timestamp",
    "calculate_noise_strength",
    "build_noise_filter",
    "get_noise_level_label",
    "build_color_filter",
    "build_audio_filtergraph",
    "pre_sanitize",
    "post_sanitize",
    "run_encode_pass",
    "build_encode_cmd",
    "verify_output",
    "evaluate_visual_quality",
    "generate_ed25519_signed_manifest",
    "verify_audit_manifest",
    "QUALITY_GATE_ENGINE_VERSION",
    "QUALITY_GATE_ALGORITHM_VERSION",
    "QUALITY_GATE_POLICY_VERSION",
    "VerificationReport",
    "run_pipeline",
]
