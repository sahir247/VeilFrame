"""
Encoder module responsible for building FFmpeg command lines and running transcode passes with progress tracking.
"""
import re
import threading
import subprocess
from pathlib import Path
from typing import Optional, Callable, List

from .resources import get_ffmpeg_path
from .crop import build_crop_filter
from .resize import build_resize_filter
from .noise import build_noise_filter
from .color import build_color_filter
from .audio_pipeline import build_audio_filtergraph
from .fps import build_fps_arg
from .trim import build_trim_args
from .sanitizer import build_sanitization_args
from ..models.settings import ProcessingSettings
from ..models.video_info import VideoInfo


def build_filter_complex(settings: ProcessingSettings, video_info: Optional[VideoInfo] = None) -> str:
    """Builds composite -vf filter chain combining crop, resize, noise, and color/luminance drift."""
    filters = []

    # 1. Spatial Crop (Asymmetric or Bounded)
    crop_f = build_crop_filter(settings.crop, video_info)
    if crop_f:
        filters.append(crop_f)

    # 2. Resample / Scale to Grid
    resize_f = build_resize_filter(settings.resize, video_info)
    if resize_f:
        filters.append(resize_f)

    # 3. Spatio-Temporal Dynamic Noise / Dither
    noise_f = build_noise_filter(settings.noise, video_info)
    if noise_f:
        filters.append(noise_f)

    # 4. Low-Frequency Color & Luminance Drift (~1% Budget)
    color_f = build_color_filter(settings.color, video_info)
    if color_f:
        filters.append(color_f)

    return ",".join(filters)


def build_encode_cmd(
    src: Path,
    dst: Path,
    settings: ProcessingSettings,
    video_info: Optional[VideoInfo] = None,
) -> List[str]:
    """Assembles the full FFmpeg command list for transcoding with deterministic quantization."""
    ffmpeg = get_ffmpeg_path()
    cmd = [
        str(ffmpeg),
        "-hide_banner",
        "-nostats",
        "-y",
        "-i", str(src),
    ]

    # Trim / duration
    cmd += build_trim_args(settings.trim, video_info)

    # Stream mapping: Primary video stream and audio if present
    cmd += ["-map", "0:v:0", "-map", "0:a?"]

    # Video filters (Crop -> Resize -> Noise -> Color)
    vf = build_filter_complex(settings, video_info)
    if vf:
        cmd += ["-vf", vf]

    # FPS
    cmd += build_fps_arg(settings.fps, video_info)

    # Hardware acceleration & codec selection
    hw_pref = getattr(settings.codec, "hw_accel", "auto") or "auto"
    codec_name = settings.codec.codec.lower() if settings.codec.mode == "manual" else "h264"
    
    selected_encoder = None
    if hw_pref != "cpu":
        try:
            from .resources import get_hardware_capabilities
            caps = get_hardware_capabilities()
            verified = [e["codec"] for e in caps.get("verified_encoders", [])]
            
            if hw_pref in ("nvenc", "nvidia"):
                candidates = ["h264_nvenc"] if "h264" in codec_name else (["hevc_nvenc"] if "hevc" in codec_name or "265" in codec_name else ["av1_nvenc"])
                for c in candidates:
                    if c in verified or hw_pref != "auto":
                        selected_encoder = c; break
            elif hw_pref in ("qsv", "intel"):
                candidates = ["h264_qsv"] if "h264" in codec_name else (["hevc_qsv"] if "hevc" in codec_name or "265" in codec_name else ["av1_qsv"])
                for c in candidates:
                    if c in verified or hw_pref != "auto":
                        selected_encoder = c; break
            elif hw_pref in ("amf", "amd"):
                candidates = ["h264_amf"] if "h264" in codec_name else (["hevc_amf"] if "hevc" in codec_name or "265" in codec_name else ["av1_amf"])
                for c in candidates:
                    if c in verified or hw_pref != "auto":
                        selected_encoder = c; break
            elif hw_pref in ("videotoolbox", "apple"):
                candidates = ["h264_videotoolbox"] if "h264" in codec_name else ["hevc_videotoolbox"]
                for c in candidates:
                    if c in verified or hw_pref != "auto":
                        selected_encoder = c; break
            elif hw_pref == "auto":
                if "h265" in codec_name or "hevc" in codec_name or "265" in codec_name:
                    for pref in ("hevc_nvenc", "hevc_qsv", "hevc_amf", "hevc_videotoolbox"):
                        if pref in verified:
                            selected_encoder = pref; break
                elif "av1" in codec_name:
                    for pref in ("av1_nvenc", "av1_qsv", "av1_amf"):
                        if pref in verified:
                            selected_encoder = pref; break
                else: # h264
                    for pref in ("h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox"):
                        if pref in verified:
                            selected_encoder = pref; break
        except Exception:
            selected_encoder = None

    if selected_encoder:
        cmd += ["-c:v", selected_encoder]
        if "hevc" in selected_encoder:
            cmd += ["-tag:v", "hvc1"]
    else:
        if codec_name in ("h265", "hevc", "libx265"):
            cmd += ["-c:v", "libx265", "-tag:v", "hvc1"]
        elif codec_name in ("av1", "libsvtav1", "svtav1"):
            cmd += ["-c:v", "libsvtav1"]
        else:
            cmd += ["-c:v", "libx264"]

    cmd += ["-pix_fmt", "yuv420p"]

    # Deterministic Quantization & Forced GOP restructuring
    q_settings = getattr(settings, "quantization", None)
    if q_settings and q_settings.forced_gop:
        gop = q_settings.gop_size or 48
        sc = q_settings.scene_change_threshold
        cmd += ["-g", str(gop), "-keyint_min", str(gop), "-sc_threshold", str(sc)]

    # Quality / Bitrate
    q_mode = settings.quality.mode
    crf_val = str(settings.quality.crf if q_mode == "crf" else 18)
    if q_mode == "bitrate":
        kbps = max(100, settings.quality.bitrate_kbps)
        cmd += [
            "-b:v", f"{kbps}k",
            "-maxrate", f"{int(kbps * 1.5)}k",
            "-bufsize", f"{int(kbps * 2)}k",
        ]
    else:
        if selected_encoder and "nvenc" in selected_encoder:
            cmd += ["-cq", crf_val]
        elif selected_encoder and "qsv" in selected_encoder:
            cmd += ["-global_quality", crf_val]
        elif selected_encoder and "videotoolbox" in selected_encoder:
            cmd += ["-q:v", crf_val]
        else:
            cmd += ["-crf", crf_val, "-preset", "medium"]

    # Audio re-encoding & Audio Privacy Pipeline (ENF notch & micro-pitch)
    cmd += ["-c:a", "aac", "-b:a", "192k"]
    af = build_audio_filtergraph(settings.audio_privacy, video_info)
    if af:
        cmd += ["-af", af]

    # In-stream sanitization and bitexact flags
    cmd += build_sanitization_args(dst.suffix or ".mp4")

    # Timestamp normalization (Epoch 0)
    if q_settings and q_settings.normalize_timestamps and q_settings.epoch_zero:
        cmd += ["-metadata", "creation_time=1970-01-01T00:00:00Z"]

    cmd.append(str(dst))
    return cmd


def run_encode_pass(
    src: Path,
    dst: Path,
    settings: ProcessingSettings,
    video_info: Optional[VideoInfo] = None,
    progress_callback: Optional[Callable[[float, str], None]] = None,
    cancel_check: Optional[Callable[[], bool]] = None,
) -> None:
    """
    Executes the FFmpeg transcode pass and reports progress with concurrent stderr draining.
    """
    cmd = build_encode_cmd(src, dst, settings, video_info)

    # Insert -progress pipe:1 before destination
    cmd.insert(-1, "-progress")
    cmd.insert(-1, "pipe:1")

    total_dur = video_info.duration if (video_info and video_info.duration > 0) else 0.0
    if settings.trim.enabled and settings.trim.duration:
        total_dur = min(total_dur or settings.trim.duration, settings.trim.duration)

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )

    stderr_lines: List[str] = []

    def drain_stderr():
        try:
            if proc.stderr:
                for err_line in proc.stderr:
                    stderr_lines.append(err_line)
                    if len(stderr_lines) > 200:
                        stderr_lines.pop(0)
        except Exception:
            pass

    stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
    stderr_thread.start()

    out_time_re = re.compile(r"out_time_us=(\d+)")
    fps_re = re.compile(r"fps=([\d\.]+)")

    current_fps = 0.0

    try:
        if proc.stdout:
            for line in proc.stdout:
                if cancel_check and cancel_check():
                    proc.terminate()
                    raise RuntimeError("Processing cancelled by user.")

                line = line.strip()
                if line.startswith("fps="):
                    m = fps_re.match(line)
                    if m:
                        try:
                            current_fps = float(m.group(1))
                        except Exception:
                            pass
                elif line.startswith("out_time_us="):
                    m = out_time_re.match(line)
                    if m and total_dur > 0:
                        try:
                            us = int(m.group(1))
                            sec = us / 1_000_000.0
                            pct = min(99.0, max(0.0, (sec / total_dur) * 100.0))
                            status = f"Encoding: {pct:.1f}% ({sec:.1f}s / {total_dur:.1f}s @ {current_fps:.1f} fps)"
                            if progress_callback:
                                progress_callback(pct, status)
                        except Exception:
                            pass
                elif line == "progress=end":
                    if progress_callback:
                        progress_callback(100.0, f"Encoding finished ({total_dur:.1f}s)")

        proc.wait()
        stderr_thread.join(timeout=2.0)

        if proc.returncode != 0:
            err_msg = "".join(stderr_lines)[-4000:]
            raise RuntimeError(f"FFmpeg encoding failed: {err_msg}")

    except Exception:
        proc.kill()
        raise
    finally:
        if proc.stdout:
            try:
                proc.stdout.close()
            except Exception:
                pass
        if proc.stderr:
            try:
                proc.stderr.close()
            except Exception:
                pass
