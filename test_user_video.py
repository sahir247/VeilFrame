#!/usr/bin/env python3
"""
VeilFrame — Video Sanitization & Verification Test Runner.

Usage:
    python test_user_video.py <path_to_video>
"""
import sys
import os
from pathlib import Path

# Set UTF-8 encoding
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from veilframe.core.analyzer import analyze_video
from veilframe.core.pipeline import run_pipeline
from veilframe.presets.manager import PresetManager

if len(sys.argv) > 1:
    src_path = Path(sys.argv[1])
else:
    print("Usage: python test_user_video.py <path_to_video>")
    sys.exit(0)

if not src_path.exists():
    print(f"Error: File not found at: {src_path}")
    sys.exit(1)

print(f"Analyzing: {src_path.name}")
info = analyze_video(src_path)
if info.video:
    print(f"Resolution: {info.video.resolution_str}")
    print(f"FPS: {info.video.fps_str}")
    print(f"Codec: {info.video.codec} ({info.video.codec_long_name})")
    print(f"Bitrate: {info.video.bitrate_str}")
if info.audio:
    print(f"Audio: {info.audio.codec} @ {info.audio.sample_rate_str}, {info.audio.channels_str}")
print(f"Duration: {info.duration_str}")
print(f"File Size: {info.size_str}")

pm = PresetManager()
settings = pm.apply_preset("5% Bounded Forensic Disruption")

# Ensure all individual components are explicitly enabled in Auto mode
settings.crop.enabled = True
settings.crop.mode = "auto"
settings.resize.enabled = True
settings.resize.mode = "auto"
settings.fps.enabled = True
settings.fps.mode = "auto"
settings.trim.enabled = True
settings.trim.mode = "auto"
settings.noise.enabled = True
settings.noise.mode = "auto"
settings.color.enabled = True
settings.color.mode = "auto"
settings.audio_privacy.enabled = True
settings.audio_privacy.mode = "auto"
settings.quantization.forced_gop = True
settings.quantization.normalize_timestamps = True
settings.quantization.epoch_zero = True

dst_path = src_path.with_name(src_path.stem + "_veilframe_sanitized" + src_path.suffix)
print(f"\nTarget Output: {dst_path}")

def log_progress(pct, msg):
    print(f"[{pct:5.1f}%] {msg}")

print("\nStarting VeilFrame Sanitization Pipeline...")
report = run_pipeline(src_path, dst_path, settings, progress_callback=log_progress)
print("\n" + "=" * 50)
print(report.format_text())
print("=" * 50)
print(f"\nSaved sanitized video to: {dst_path}")
