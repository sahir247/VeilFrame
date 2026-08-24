import sys
import os
from pathlib import Path

# Set UTF-8 encoding
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from privacy_cleaner.core.analyzer import analyze_video
from privacy_cleaner.core.pipeline import run_pipeline
from privacy_cleaner.presets.manager import PresetManager

src_path = Path(r"C:\Users\parve\Documents\O+ Connect\二娃📷_7667807976921837942_不要被琐事困住 去看这辽阔的世界吧_风和自由_川西秘境_趁热跳进14度毕棚沟 _解锁毕棚沟n种打开方式___2160x3840_0.mp4")

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

dst_path = src_path.with_name(src_path.stem + "_cleaned_auto.mp4")
print(f"\nTarget Output: {dst_path}")

def log_progress(pct, msg):
    print(f"[{pct:5.1f}%] {msg}")

print("\nStarting Two-Pass Privacy Cleaning Pipeline...")
report = run_pipeline(src_path, dst_path, settings, progress_callback=log_progress)
print("\n" + "=" * 50)
print(report.format_text())
print("=" * 50)
print(f"\nSaved sanitized video to: {dst_path}")
