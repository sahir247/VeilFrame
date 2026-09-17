"""
veilframe.core.media_compressor — High-performance cross-platform media compression and editing engine.
Provides unified image and video compression, visual trimming, scaling, color filtering, and metadata control.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
from PIL import Image, ImageEnhance, ImageOps


def _apply_image_filter(img: Image.Image, filter_name: str) -> Image.Image:
    """Applies a color transformation filter to a PIL Image."""
    filter_lower = filter_name.lower().strip()
    if filter_lower in ("none", "default"):
        return img

    if filter_lower == "grayscale":
        return ImageOps.grayscale(img).convert("RGB")

    if filter_lower == "sepia":
        arr = np.array(img.convert("RGB"), dtype=np.float32)
        r = arr[:, :, 0] * 0.393 + arr[:, :, 1] * 0.769 + arr[:, :, 2] * 0.189
        g = arr[:, :, 0] * 0.349 + arr[:, :, 1] * 0.686 + arr[:, :, 2] * 0.168
        b = arr[:, :, 0] * 0.272 + arr[:, :, 1] * 0.534 + arr[:, :, 2] * 0.131
        sepia_arr = np.stack([np.clip(r, 0, 255), np.clip(g, 0, 255), np.clip(b, 0, 255)], axis=-1)
        return Image.fromarray(sepia_arr.astype(np.uint8))

    if filter_lower == "vintage":
        # Slight warm fade with contrast adjustment
        enhancer = ImageEnhance.Color(img)
        img_boost = enhancer.enhance(0.8)
        enhancer_con = ImageEnhance.Contrast(img_boost)
        img_con = enhancer_con.enhance(1.15)
        # Tint slightly yellow
        arr = np.array(img_con.convert("RGB"), dtype=np.float32)
        arr[:, :, 0] = np.clip(arr[:, :, 0] * 1.06, 0, 255)
        arr[:, :, 1] = np.clip(arr[:, :, 1] * 1.02, 0, 255)
        arr[:, :, 2] = np.clip(arr[:, :, 2] * 0.90, 0, 255)
        return Image.fromarray(arr.astype(np.uint8))

    if filter_lower == "cool":
        arr = np.array(img.convert("RGB"), dtype=np.float32)
        arr[:, :, 0] = np.clip(arr[:, :, 0] * 0.92, 0, 255)
        arr[:, :, 2] = np.clip(arr[:, :, 2] * 1.12, 0, 255)
        return Image.fromarray(arr.astype(np.uint8))

    if filter_lower == "warm":
        arr = np.array(img.convert("RGB"), dtype=np.float32)
        arr[:, :, 0] = np.clip(arr[:, :, 0] * 1.12, 0, 255)
        arr[:, :, 2] = np.clip(arr[:, :, 2] * 0.88, 0, 255)
        return Image.fromarray(arr.astype(np.uint8))

    return img


def compress_image(
    input_path: str | Path,
    output_path: str | Path,
    quality: int = 85,
    format: Optional[str] = None,
    width: Optional[int] = None,
    height: Optional[int] = None,
    strip_exif: bool = True,
    filter_name: str = "default",
    rotate_deg: float = 0.0,
    crop_box: Optional[Tuple[int, int, int, int]] = None,
    scale: float = 1.0,
    keep_aspect: bool = True,
    **kwargs: Any,
) -> Dict[str, Any]:
    """
    Compresses and transforms an image file with complete dimension, rotation,
    color filter, and quality controls.
    Supports both Chaquopy positional ordering and named keyword arguments.
    """
    # Accommodate callers that pass (input_path, output_path, format, quality, ...)
    if isinstance(quality, str) and (format is None or isinstance(format, (int, float))):
        real_format = quality
        real_quality = int(format) if format is not None else 85
        format = real_format
        quality = real_quality

    in_p = Path(input_path).resolve()
    out_p = Path(output_path).resolve()
    out_p.parent.mkdir(parents=True, exist_ok=True)

    if not in_p.exists():
        raise FileNotFoundError(f"Input image not found: {in_p}")

    input_size = in_p.stat().st_size

    with Image.open(in_p) as img:
        # 1. Orientation correction if present
        img = ImageOps.exif_transpose(img)
        orig_w, orig_h = img.size

        # 2. Crop
        if crop_box is not None:
            left, top, right, bottom = crop_box
            left = max(0, min(orig_w - 1, left))
            top = max(0, min(orig_h - 1, top))
            right = max(left + 1, min(orig_w, right))
            bottom = max(top + 1, min(orig_h, bottom))
            img = img.crop((left, top, right, bottom))

        # 3. Rotate
        if abs(rotate_deg) > 0.001:
            img = img.rotate(-rotate_deg, expand=True, resample=Image.Resampling.BICUBIC)

        # 4. Resize
        curr_w, curr_h = img.size
        target_w, target_h = curr_w, curr_h

        if width is not None and height is not None and width > 0 and height > 0:
            if keep_aspect:
                aspect = curr_w / curr_h
                if width / height > aspect:
                    target_w = round(height * aspect)
                    target_h = height
                else:
                    target_w = width
                    target_h = round(width / aspect)
            else:
                target_w, target_h = width, height
        elif scale > 0 and abs(scale - 1.0) > 0.001:
            target_w = max(1, round(curr_w * scale))
            target_h = max(1, round(curr_h * scale))

        if target_w != curr_w or target_h != curr_h:
            img = img.resize((target_w, target_h), Image.Resampling.LANCZOS)

        # 5. Color Filter
        img = _apply_image_filter(img, filter_name)

        # 6. Format Determination
        target_fmt = (format or out_p.suffix.lstrip(".").upper() or "JPEG").upper()
        if target_fmt in ("JPG", "JPEG"):
            target_fmt = "JPEG"
            if img.mode in ("RGBA", "P"):
                img = img.convert("RGB")
        elif target_fmt == "PNG":
            if img.mode not in ("RGB", "RGBA"):
                img = img.convert("RGBA")
        elif target_fmt == "WEBP":
            if img.mode not in ("RGB", "RGBA"):
                img = img.convert("RGB")

        # 7. Save & Compress
        save_kwargs: Dict[str, Any] = {}
        if target_fmt in ("JPEG", "WEBP"):
            save_kwargs["quality"] = max(1, min(100, int(quality)))
            save_kwargs["optimize"] = True
        elif target_fmt == "PNG":
            save_kwargs["optimize"] = True
            # Map quality 1-100 to compress_level 9-1
            level = max(0, min(9, int((100 - quality) / 11)))
            save_kwargs["compress_level"] = level

        if not strip_exif and "exif" in img.info:
            save_kwargs["exif"] = img.info["exif"]

        img.save(out_p, format=target_fmt, **save_kwargs)

    output_size = out_p.stat().st_size
    ratio = (1.0 - (output_size / input_size)) * 100.0 if input_size > 0 else 0.0

    return {
        "input_path": str(in_p),
        "output_path": str(out_p),
        "format": target_fmt,
        "input_size": input_size,
        "output_size": output_size,
        "savings_percent": round(max(0.0, ratio), 2),
        "dimensions": (target_w, target_h),
    }


def _find_ffmpeg() -> str:
    """Finds the available ffmpeg binary."""
    try:
        from .resources import get_ffmpeg_path
        p = get_ffmpeg_path()
        if p and Path(p).exists():
            return str(p)
    except Exception:
        pass
    sys_ffmpeg = shutil.which("ffmpeg")
    if sys_ffmpeg:
        return sys_ffmpeg
    return "ffmpeg"


def _get_video_duration(input_path: str | Path, ffmpeg_bin: str) -> float:
    """Probes video duration via ffprobe or ffmpeg."""
    try:
        from .resources import find_executable
        ffprobe_bin = str(find_executable("ffprobe"))
    except Exception:
        ffprobe_bin = shutil.which("ffprobe")

    if ffprobe_bin and Path(ffprobe_bin).exists():
        try:
            cmd = [
                str(ffprobe_bin),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                str(input_path),
            ]
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
            if res.returncode == 0 and res.stdout.strip():
                return float(res.stdout.strip())
        except Exception:
            pass

    # Fallback to ffmpeg -i inspection
    try:
        cmd = [ffmpeg_bin, "-i", str(input_path)]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        for line in res.stderr.splitlines():
            if "Duration:" in line:
                part = line.split("Duration:")[1].split(",")[0].strip()
                h, m, s = part.split(":")
                return float(h) * 3600 + float(m) * 60 + float(s)
    except Exception:
        pass
    return 0.0


def compress_video(
    input_path: str | Path,
    output_path: str | Path,
    start_time: Optional[float] = None,
    end_time: Optional[float] = None,
    target_size_mb: Optional[float] = None,
    resolution: Optional[str] = None,
    crop_aspect: Optional[str] = None,
    speed: float = 1.0,
    audio_action: str | bool = "keep",
    crf: int = 28,
    container_format: Optional[str] = None,
    codec: str = "libx264",
    strip_metadata: bool = True,
    **kwargs: Any,
) -> Dict[str, Any]:
    """
    Compresses and transforms a video with precision range trimming, target size
    bitrate calculation, resolution downscaling, aspect ratio cropping, speed adjustment,
    audio stream manipulation, and metadata scrubbing.
    Supports both Chaquopy positional ordering and named keyword arguments.
    """
    # Parse audio_action / strip_audio
    strip_audio = False
    audio_bitrate = "128k"
    if isinstance(audio_action, bool):
        strip_audio = audio_action
    elif isinstance(audio_action, str):
        act = audio_action.lower().strip()
        if act in ("mute", "strip", "true"):
            strip_audio = True
        elif act == "aac_64k":
            audio_bitrate = "64k"
        elif act == "aac_128k":
            audio_bitrate = "128k"

    in_p = Path(input_path).resolve()
    out_p = Path(output_path).resolve()
    out_p.parent.mkdir(parents=True, exist_ok=True)

    if not in_p.exists():
        raise FileNotFoundError(f"Input video not found: {in_p}")

    ffmpeg_bin = _find_ffmpeg()
    input_size = in_p.stat().st_size
    total_dur = _get_video_duration(in_p, ffmpeg_bin)

    cmd = [ffmpeg_bin, "-y"]

    # 1. Trimming (-ss and -to before -i for fast seek)
    effective_dur = total_dur
    if start_time is not None and start_time >= 0:
        cmd.extend(["-ss", f"{start_time:.3f}"])
        if end_time is not None and end_time > start_time:
            cmd.extend(["-to", f"{end_time:.3f}"])
            effective_dur = end_time - start_time
        elif total_dur > start_time:
            effective_dur = total_dur - start_time
    elif end_time is not None and end_time > 0:
        cmd.extend(["-to", f"{end_time:.3f}"])
        effective_dur = end_time

    cmd.extend(["-i", str(in_p)])

    # 2. Video Filters (scale, crop, speed/setpts)
    vf_filters = []

    # Aspect ratio cropping
    if crop_aspect:
        aspect_clean = crop_aspect.replace(" ", "").lower()
        if aspect_clean == "1:1":
            vf_filters.append("crop='min(iw,ih)':'min(iw,ih)'")
        elif aspect_clean in ("9:16", "reel", "story"):
            vf_filters.append("crop='ih*(9/16)':ih")
        elif aspect_clean in ("16:9", "landscape"):
            vf_filters.append("crop=iw:'iw*(9/16)'")
        elif aspect_clean == "4:3":
            vf_filters.append("crop='ih*(4/3)':ih")

    # Resolution scaling
    if resolution:
        res_lower = resolution.lower().strip()
        if "1080" in res_lower:
            vf_filters.append("scale='min(1920,iw)':-2")
        elif "720" in res_lower:
            vf_filters.append("scale='min(1280,iw)':-2")
        elif "480" in res_lower:
            vf_filters.append("scale='min(854,iw)':-2")
        elif "360" in res_lower:
            vf_filters.append("scale='min(640,iw)':-2")
        elif "x" in res_lower:
            try:
                w, h = res_lower.split("x")
                vf_filters.append(f"scale={int(w)}:{int(h)}")
            except Exception:
                pass

    # Playback speed
    af_filters = []
    if abs(speed - 1.0) > 0.05 and speed > 0.1:
        vf_filters.append(f"setpts={1.0 / speed:.4f}*PTS")
        if not strip_audio:
            af_filters.append(f"atempo={speed:.4f}")
        effective_dur = effective_dur / speed

    if vf_filters:
        cmd.extend(["-vf", ",".join(vf_filters)])

    # 3. Bitrate / Quality Encoding
    cmd.extend(["-c:v", codec])
    if target_size_mb is not None and target_size_mb > 0 and effective_dur > 0.2:
        # Compute video bitrate to fit within target size
        target_bits = target_size_mb * 8 * 1024 * 1024 * 0.95  # 5% safety margin for mux overhead
        audio_bits = 0 if strip_audio else 128 * 1024 * effective_dur
        video_bits = max(50 * 1024 * effective_dur, target_bits - audio_bits)
        target_bitrate_kbps = int(video_bits / (effective_dur * 1024))
        cmd.extend(["-b:v", f"{target_bitrate_kbps}k", "-maxrate", f"{int(target_bitrate_kbps * 1.3)}k", "-bufsize", f"{target_bitrate_kbps * 2}k"])
    else:
        cmd.extend(["-crf", str(crf), "-preset", "fast"])

    # 4. Audio settings
    if strip_audio:
        cmd.append("-an")
    else:
        if af_filters:
            cmd.extend(["-af", ",".join(af_filters)])
        cmd.extend(["-c:a", "aac", "-b:a", audio_bitrate])

    # 5. Metadata Privacy Scrubbing
    if strip_metadata:
        cmd.extend(["-map_metadata", "-1"])

    cmd.extend(["-movflags", "+faststart", str(out_p)])

    # Execute FFmpeg
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"FFmpeg execution failed (code {proc.returncode}):\n{proc.stderr}")

    if not out_p.exists() or out_p.stat().st_size == 0:
        raise RuntimeError(f"FFmpeg produced empty output at {out_p}")

    output_size = out_p.stat().st_size
    ratio = (1.0 - (output_size / input_size)) * 100.0 if input_size > 0 else 0.0

    return {
        "input_path": str(in_p),
        "output_path": str(out_p),
        "input_size": input_size,
        "output_size": output_size,
        "savings_percent": round(max(0.0, ratio), 2),
        "duration": effective_dur,
    }
