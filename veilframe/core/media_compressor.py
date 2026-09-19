"""
veilframe.core.media_compressor — High-performance cross-platform media compression and editing engine.
Provides unified image and video compression, visual trimming, scaling, color filtering, watermarking, and metadata control.
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, Optional, Tuple, Union

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFont, ImageOps


def _apply_image_filter(img: Image.Image, filter_name: str) -> Image.Image:
    """Applies a color transformation filter to a PIL Image."""
    filter_lower = filter_name.lower().strip()
    if filter_lower in ("none", "default"):
        return img

    if filter_lower == "grayscale":
        return ImageOps.grayscale(img).convert("RGB")

    if filter_lower in ("negative", "invert"):
        if img.mode != "RGB":
            rgb_img = img.convert("RGB")
            return ImageOps.invert(rgb_img)
        return ImageOps.invert(img)

    if filter_lower == "contrast":
        enhancer = ImageEnhance.Contrast(img)
        return enhancer.enhance(1.4)

    if filter_lower == "sepia":
        arr = np.array(img.convert("RGB"), dtype=np.float32)
        r = arr[:, :, 0] * 0.393 + arr[:, :, 1] * 0.769 + arr[:, :, 2] * 0.189
        g = arr[:, :, 0] * 0.349 + arr[:, :, 1] * 0.686 + arr[:, :, 2] * 0.168
        b = arr[:, :, 0] * 0.272 + arr[:, :, 1] * 0.534 + arr[:, :, 2] * 0.131
        sepia_arr = np.stack([np.clip(r, 0, 255), np.clip(g, 0, 255), np.clip(b, 0, 255)], axis=-1)
        return Image.fromarray(sepia_arr.astype(np.uint8))

    if filter_lower == "vintage":
        enhancer = ImageEnhance.Color(img)
        img_boost = enhancer.enhance(0.8)
        enhancer_con = ImageEnhance.Contrast(img_boost)
        img_con = enhancer_con.enhance(1.15)
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


def build_atempo_chain(speed: float) -> str:
    """
    Builds a chained FFmpeg atempo filter string for arbitrary speed multipliers (0.25x to 4.0x).
    FFmpeg atempo filter is strictly bounded to [0.5, 2.0] per filter instance.
    """
    if abs(speed - 1.0) < 0.01 or speed <= 0.0:
        return ""
    tempo_factors = []
    current = speed
    while current > 2.0:
        tempo_factors.append(2.0)
        current /= 2.0
    while current < 0.5:
        tempo_factors.append(0.5)
        current /= 0.5
    if abs(current - 1.0) >= 0.01:
        tempo_factors.append(current)
    return ",".join(f"atempo={f:.3f}".rstrip("0").rstrip(".") for f in tempo_factors)


def calculate_whatsapp_status_bufsize(base_kbps: int, duration_sec: float) -> int:
    """
    Calculates duration-dependent bufsize for WhatsApp Status rate control.
    Exact bucket comparisons:
    <6.0s -> base / 2
    6.0s to <11.0s -> floor(base / 1.5)
    11.0s to <16.0s -> base
    >=16.0s -> floor(base * 1.5)
    """
    safe_dur = max(0.0, float(duration_sec))
    if safe_dur < 6.0:
        return base_kbps // 2
    elif safe_dur < 11.0:
        return int(base_kbps / 1.5)
    elif safe_dur < 16.0:
        return base_kbps
    else:
        return int(base_kbps * 1.5)


def _apply_text_watermark(
    img: Image.Image,
    watermark_config: Dict[str, Any],
) -> Image.Image:
    """
    Renders a configurable text watermark overlay on a PIL Image matching TextWatermarkConfig.
    Supports 9 spatial anchor positions, custom alpha opacity, font scaling, and drop-shadow contrast.
    """
    text = str(watermark_config.get("text", "")).strip()
    if not text:
        return img

    size = int(watermark_config.get("size", 36))
    color_val = watermark_config.get("color", "white")
    pos_val = str(watermark_config.get("position", "bottom-right")).lower().strip().replace("_", "-")
    opacity = float(watermark_config.get("opacity", 1.0))
    alpha_byte = int(max(0.0, min(1.0, opacity)) * 255)

    # Determine RGBA text color
    if isinstance(color_val, str):
        color_lower = color_val.lower()
        if color_lower == "white":
            rgb = (255, 255, 255)
        elif color_lower == "black":
            rgb = (0, 0, 0)
        elif color_lower == "red":
            rgb = (239, 68, 68)
        elif color_lower == "green":
            rgb = (34, 197, 94)
        elif color_lower == "blue":
            rgb = (59, 130, 246)
        elif color_lower == "yellow":
            rgb = (234, 179, 8)
        elif color_lower == "gray":
            rgb = (156, 163, 175)
        else:
            rgb = (255, 255, 255)
        fill_color = (*rgb, alpha_byte)
    elif isinstance(color_val, (list, tuple)):
        if len(color_val) >= 3:
            fill_color = (int(color_val[0]), int(color_val[1]), int(color_val[2]), alpha_byte)
        else:
            fill_color = (255, 255, 255, alpha_byte)
    else:
        fill_color = (255, 255, 255, alpha_byte)

    font = None
    for font_name in ("arial.ttf", "DejaVuSans.ttf", "segoeui.ttf", "helvetica.ttf"):
        try:
            font = ImageFont.truetype(font_name, size)
            break
        except Exception:
            continue
    if font is None:
        font = ImageFont.load_default()

    # Create overlay
    base = img.convert("RGBA")
    txt_layer = Image.new("RGBA", base.size, (255, 255, 255, 0))
    draw = ImageDraw.Draw(txt_layer)

    bbox = draw.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    w, h = base.size
    padding = max(16, int(min(w, h) * 0.03))

    # 9 spatial anchor positions
    if pos_val in ("top-left", "topleft"):
        x, y = padding, padding
    elif pos_val in ("top-center", "topcenter"):
        x, y = (w - tw) // 2, padding
    elif pos_val in ("top-right", "topright"):
        x, y = w - tw - padding, padding
    elif pos_val in ("center-left", "centerleft"):
        x, y = padding, (h - th) // 2
    elif pos_val in ("center", "middle"):
        x, y = (w - tw) // 2, (h - th) // 2
    elif pos_val in ("center-right", "centerright"):
        x, y = w - tw - padding, (h - th) // 2
    elif pos_val in ("bottom-left", "bottomleft"):
        x, y = padding, h - th - padding
    elif pos_val in ("bottom-center", "bottomcenter"):
        x, y = (w - tw) // 2, h - th - padding
    else:  # bottom-right
        x, y = w - tw - padding, h - th - padding

    # Shadow for contrast
    shadow_alpha = int(alpha_byte * 0.7)
    draw.text((x + 2, y + 2), text, fill=(0, 0, 0, shadow_alpha), font=font)
    draw.text((x, y), text, fill=fill_color, font=font)

    combined = Image.alpha_composite(base, txt_layer)
    if img.mode != "RGBA":
        return combined.convert(img.mode)
    return combined


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
    goal: str = "percentage",
    target_size_kb: Optional[int] = None,
    flip_h: bool = False,
    flip_v: bool = False,
    text_watermark: Optional[Dict[str, Any]] = None,
    bg_color: Optional[str] = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """
    Compresses and transforms an image file with complete dimension, rotation,
    flip, text watermark, color filter, and quality / target size controls.
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

    try:
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

            # 3. Flips
            if flip_h:
                img = ImageOps.mirror(img)
            if flip_v:
                img = ImageOps.flip(img)

            # 4. Rotate
            if abs(rotate_deg) > 0.001:
                img = img.rotate(-rotate_deg, expand=True, resample=Image.Resampling.BICUBIC)

            # 5. Resize
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

            # 6. Color Filter
            img = _apply_image_filter(img, filter_name)

            # 7. Text Watermark
            if text_watermark:
                img = _apply_text_watermark(img, text_watermark)

            # 8. Background fill for transparent images & format compatibility
            has_alpha = img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info)
            bg_clean = (bg_color or "transparent").lower().strip()

            target_fmt = (format or out_p.suffix.lstrip(".").upper() or "JPEG").upper()
            if target_fmt in ("JPG", "JPEG"):
                target_fmt = "JPEG"
            elif target_fmt not in ("PNG", "WEBP"):
                target_fmt = "JPEG"

            if has_alpha:
                rgba_img = img.convert("RGBA")
                if bg_clean == "white":
                    solid_bg = Image.new("RGBA", rgba_img.size, (255, 255, 255, 255))
                    solid_bg.paste(rgba_img, mask=rgba_img.split()[-1])
                    img = solid_bg
                elif bg_clean == "black":
                    solid_bg = Image.new("RGBA", rgba_img.size, (0, 0, 0, 255))
                    solid_bg.paste(rgba_img, mask=rgba_img.split()[-1])
                    img = solid_bg
                elif target_fmt == "JPEG":
                    # JPEG cannot represent alpha; default background is solid white
                    solid_bg = Image.new("RGB", rgba_img.size, (255, 255, 255))
                    solid_bg.paste(rgba_img, mask=rgba_img.split()[-1])
                    img = solid_bg

            # Final mode alignment for target format
            if target_fmt == "JPEG":
                if img.mode != "RGB":
                    img = img.convert("RGB")
            elif target_fmt in ("PNG", "WEBP"):
                if img.mode not in ("RGB", "RGBA"):
                    img = img.convert("RGBA" if has_alpha and bg_clean in ("transparent", "none") else "RGB")

            # 9. Save & Compress (Target Size ceiling solver vs Quality percentage)
            save_kwargs: Dict[str, Any] = {}
            if not strip_exif and "exif" in img.info:
                save_kwargs["exif"] = img.info["exif"]

            is_target_size = (goal == "target_size" or target_size_kb is not None) and (target_size_kb is not None and target_size_kb > 0)
            solver_note = None

            if is_target_size:
                target_bytes = target_size_kb * 1024
                if target_fmt in ("JPEG", "WEBP"):
                    # Binary search: maximize quality subject to output_size <= target_size
                    best_q = 5
                    low, high = 5, 95
                    achieved = False

                    while low <= high:
                        mid = (low + high) // 2
                        buf = io.BytesIO()
                        img.save(buf, format=target_fmt, quality=mid, optimize=True, **save_kwargs)
                        size = buf.tell()
                        if size <= target_bytes:
                            best_q = mid
                            achieved = True
                            low = mid + 1  # Try higher quality under ceiling
                        else:
                            high = mid - 1

                    img.save(out_p, format=target_fmt, quality=best_q, optimize=True, **save_kwargs)
                    if not achieved:
                        solver_note = f"Minimum quality (5%) output exceeds target ceiling ({out_p.stat().st_size // 1024} KB > {target_size_kb} KB); consider downscaling resolution."
                elif target_fmt == "PNG":
                    # PNG is lossless; evaluate compression levels 1-9
                    best_level = 9
                    min_size = float("inf")
                    achieved = False

                    for level in range(1, 10):
                        buf = io.BytesIO()
                        img.save(buf, format="PNG", optimize=True, compress_level=level, **save_kwargs)
                        size = buf.tell()
                        if size < min_size:
                            min_size = size
                            best_level = level
                        if size <= target_bytes:
                            achieved = True
                            best_level = level
                            break

                    img.save(out_p, format="PNG", optimize=True, compress_level=best_level, **save_kwargs)
                    if not achieved:
                        solver_note = f"Lossless PNG size floor is {int(min_size // 1024)} KB; target {target_size_kb} KB unachievable in lossless PNG without lossy re-encoding (consider JPEG or WebP)."
            else:
                if target_fmt in ("JPEG", "WEBP"):
                    save_kwargs["quality"] = max(1, min(100, int(quality)))
                    save_kwargs["optimize"] = True
                elif target_fmt == "PNG":
                    save_kwargs["optimize"] = True
                    level = max(0, min(9, int((100 - quality) / 11)))
                    save_kwargs["compress_level"] = level

                img.save(out_p, format=target_fmt, **save_kwargs)

        output_size = out_p.stat().st_size
        ratio = (1.0 - (output_size / input_size)) * 100.0 if input_size > 0 else 0.0

        return {
            "success": True,
            "input_path": str(in_p),
            "output_path": str(out_p),
            "format": target_fmt,
            "input_size": input_size,
            "output_size": output_size,
            "size_bytes": output_size,
            "savings_percent": round(max(0.0, ratio), 2),
            "dimensions": (target_w, target_h),
            "note": solver_note,
            "error": None,
        }
    except Exception as e:
        return {
            "success": False,
            "input_path": str(in_p),
            "output_path": str(out_p),
            "format": format or "JPEG",
            "input_size": input_size,
            "output_size": 0,
            "size_bytes": 0,
            "savings_percent": 0.0,
            "dimensions": (0, 0),
            "error": str(e),
        }


def _find_ffmpeg() -> str:
    """Finds the available ffmpeg binary, honoring Android Bridge when in Android runtime."""
    try:
        from .android_bridge import is_android, android_bridge
        if is_android():
            method, target = android_bridge.resolve_ffmpeg_binary_or_jni()
            if target:
                return target
    except Exception:
        pass
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

    try:
        cmd = [ffmpeg_bin, "-i", str(input_path)]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        output = res.stderr or res.stdout
        for line in output.splitlines():
            if "Duration:" in line:
                dur_str = line.split("Duration:")[1].split(",")[0].strip()
                parts = dur_str.split(":")
                if len(parts) == 3:
                    h, m, s = float(parts[0]), float(parts[1]), float(parts[2])
                    return h * 3600 + m * 60 + s
    except Exception:
        pass

    return 10.0


def compress_video(
    input_path: str | Path,
    output_path: str | Path,
    trim_start: Optional[float] = None,
    trim_end: Optional[float] = None,
    start_time: Optional[float] = None,
    end_time: Optional[float] = None,
    target_size_mb: Optional[float] = None,
    resolution: Optional[str] = None,
    aspect_ratio: Optional[str] = None,
    crop_aspect: Optional[str] = None,
    speed: float = 1.0,
    audio_action: str = "keep",
    crf: int = 28,
    format: Optional[str] = None,
    container_format: Optional[str] = None,
    codec: str = "libx264",
    strip_metadata: bool = True,
    flip_h: bool = False,
    flip_v: bool = False,
    rotate: int = 0,
    fps: Optional[int] = None,
    volume: float = 1.0,
    audio_codec: str = "aac",
    audio_channels: str = "keep",
    audio_bitrate: Optional[str] = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """
    Compresses and edits video content according to trim, resolution, aspect,
    speed, audio volume/channels, flip/rotation transforms, codec, and size constraints.
    """
    in_p = Path(input_path).resolve()
    out_p = Path(output_path).resolve()
    out_p.parent.mkdir(parents=True, exist_ok=True)

    if not in_p.exists():
        return {
            "success": False,
            "input_path": str(in_p),
            "output_path": str(out_p),
            "input_size": 0,
            "output_size": 0,
            "size_bytes": 0,
            "savings_percent": 0.0,
            "duration": 0.0,
            "error": f"Input video not found: {in_p}",
        }

    input_size = in_p.stat().st_size
    ffmpeg_bin = _find_ffmpeg()

    actual_trim_start = trim_start if trim_start is not None else (start_time if start_time is not None else kwargs.get("start_time", 0.0))
    actual_trim_end = trim_end if trim_end is not None else (end_time if end_time is not None else kwargs.get("end_time", None))
    actual_aspect = aspect_ratio or crop_aspect or kwargs.get("crop_aspect", None)

    try:
        total_duration = _get_video_duration(in_p, ffmpeg_bin)
        effective_start = max(0.0, float(actual_trim_start))
        effective_end = float(actual_trim_end) if actual_trim_end is not None and actual_trim_end > 0 else total_duration
        if effective_end <= effective_start:
            effective_end = total_duration
        effective_dur = max(0.1, effective_end - effective_start)
        out_ext = (format or container_format or out_p.suffix.lstrip(".")).lower()

        cmd = [ffmpeg_bin, "-y", "-hide_banner"]

        # Fast seek before input
        if effective_start > 0.05:
            cmd.extend(["-ss", f"{effective_start:.3f}"])

        cmd.extend(["-i", str(in_p)])

        # Explicit duration trimming (-t)
        if effective_start > 0.05 or effective_end < total_duration - 0.05:
            cmd.extend(["-t", f"{effective_dur:.3f}"])

        # 1. Video Filters (Framing, Flip, Rotation, Downscaling)
        vf_filters = []
        if actual_aspect:
            aspect_clean = str(actual_aspect).split()[0].strip()
            if aspect_clean == "9:16":
                vf_filters.append("crop=ih*9/16:ih")
            elif aspect_clean == "1:1":
                vf_filters.append("crop=min(iw\\,ih):min(iw\\,ih)")
            elif aspect_clean == "16:9":
                vf_filters.append("crop=iw:iw*9/16")
            elif aspect_clean == "4:3":
                vf_filters.append("crop=ih*4/3:ih")

        if flip_h:
            vf_filters.append("hflip")
        if flip_v:
            vf_filters.append("vflip")

        if rotate in (90,):
            vf_filters.append("transpose=1")
        elif rotate in (180,):
            vf_filters.append("hflip,vflip")
        elif rotate in (270,):
            vf_filters.append("transpose=2")

        is_whatsapp_status = bool(
            kwargs.get("whatsapp_status")
            or kwargs.get("preset") in ("whatsapp_status", "WhatsApp Status")
            or kwargs.get("target_preset") in ("whatsapp_status", "WhatsApp Status")
        )

        if is_whatsapp_status:
            status_res = str(kwargs.get("whatsapp_status_resolution", resolution or "hd")).lower()
            is_fhd = "fhd" in status_res or "1080" in status_res
            target_w, target_h = (1080, 1920) if is_fhd else (720, 1280)
            base_maxrate = 3800 if is_fhd else 1900
            bufsize = calculate_whatsapp_status_bufsize(base_maxrate, effective_dur)
            vf_filters.append(f"scale={target_w}:{target_h}:force_original_aspect_ratio=increase,crop={target_w}:{target_h},format=yuv420p")
        elif resolution:
            res_clean = resolution.lower()
            if "1080p" in res_clean:
                vf_filters.append("scale=-2:1080")
            elif "720p" in res_clean:
                vf_filters.append("scale=-2:720")
            elif "480p" in res_clean:
                vf_filters.append("scale=-2:480")
            elif "360p" in res_clean:
                vf_filters.append("scale=-2:360")

        # 2. Speed and Audio Filters
        af_filters = []
        strip_audio = audio_action == "mute" or audio_codec == "mute" or out_ext == "gif"

        # Audio Volume
        if abs(volume - 1.0) > 0.01 and volume >= 0.0 and not strip_audio:
            af_filters.append(f"volume={volume:.2f}")

        # Audio Channels
        if audio_channels == "mono" and not strip_audio:
            af_filters.append("pan=mono|c0=0.5*c0+0.5*c1")

        # Playback speed
        if abs(speed - 1.0) > 0.01 and speed > 0.1:
            pts_factor = 1.0 / speed
            vf_filters.append(f"setpts={pts_factor:.4f}*PTS")
            if not strip_audio:
                tempo_chain = build_atempo_chain(speed)
                if tempo_chain:
                    af_filters.append(tempo_chain)
            effective_dur = effective_dur / speed

        # 3. Custom FPS
        if fps is not None and fps > 0 and out_ext != "gif":
            cmd.extend(["-r", str(int(fps))])

        # 4. Video Codec & Quality Encoding
        if out_ext == "gif":
            # GIF Animation Output Mode: Two-pass palette generation pipeline
            strip_audio = True
            gif_fps = fps if (fps is not None and fps > 0) else 15
            base_vf = [f"fps={gif_fps}"] + [f for f in vf_filters if not f.startswith("setpts")]
            base_chain = ",".join(base_vf) if base_vf else f"fps={gif_fps}"
            gif_vf = f"{base_chain},split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3"
            cmd.extend(["-vf", gif_vf, "-c:v", "gif", "-loop", "0"])
        else:
            # Enforce copy codec incompatibility rule:
            # Stream copy cannot coexist with spatial or temporal video filter graphs
            has_video_transforms = bool(vf_filters or (fps is not None and fps > 0))
            if "copy" in codec.lower() and has_video_transforms:
                resolved_codec = "libx264"
            else:
                resolved_codec = codec
                if "265" in resolved_codec or "hevc" in resolved_codec:
                    resolved_codec = "libx265"
                elif "vp9" in resolved_codec:
                    resolved_codec = "libvpx-vp9"
                elif "av1" in resolved_codec:
                    resolved_codec = "libsvtav1"
                elif "copy" in resolved_codec:
                    resolved_codec = "copy"
                else:
                    resolved_codec = "libx264"

            if vf_filters:
                cmd.extend(["-vf", ",".join(vf_filters)])

            cmd.extend(["-c:v", resolved_codec])

            if resolved_codec != "copy":
                if is_whatsapp_status:
                    cmd.extend(["-crf", "23", "-maxrate", f"{base_maxrate}k", "-bufsize", f"{bufsize}k", "-r", "29.97"])
                elif target_size_mb is not None and target_size_mb > 0 and effective_dur > 0.2:
                    target_bits = target_size_mb * 8 * 1024 * 1024 * 0.95
                    audio_bits = 0 if strip_audio else 128 * 1024 * effective_dur
                    video_bits = max(50 * 1024 * effective_dur, target_bits - audio_bits)
                    target_bitrate_kbps = int(video_bits / (effective_dur * 1024))
                    cmd.extend(["-b:v", f"{target_bitrate_kbps}k", "-maxrate", f"{int(target_bitrate_kbps * 1.3)}k", "-bufsize", f"{target_bitrate_kbps * 2}k"])
                else:
                    cmd.extend(["-crf", str(crf), "-preset", "fast"])

        # 5. Audio Settings & Compatibility Validation
        if strip_audio or out_ext == "gif":
            cmd.append("-an")
        else:
            resolved_a_codec = "aac"
            if "mp3" in audio_codec.lower():
                resolved_a_codec = "libmp3lame"
            elif "opus" in audio_codec.lower():
                resolved_a_codec = "libopus"
            elif "flac" in audio_codec.lower():
                resolved_a_codec = "flac"
            elif "copy" in audio_codec.lower():
                # If audio filters (gain, channels, speed) are requested, copy cannot be used
                resolved_a_codec = "aac" if af_filters else "copy"

            # Container compatibility validation
            if out_ext == "webm" and resolved_a_codec not in ("libopus", "libvorbis"):
                resolved_a_codec = "libopus"
            elif out_ext in ("mp4", "mov") and resolved_a_codec == "libopus":
                resolved_a_codec = "aac"

            if af_filters and resolved_a_codec != "copy":
                cmd.extend(["-af", ",".join(af_filters)])

            cmd.extend(["-c:a", resolved_a_codec])
            if resolved_a_codec not in ("copy", "flac"):
                if is_whatsapp_status:
                    cmd.extend(["-ar", "44100", "-b:a", "128k"])
                else:
                    a_bitrate = audio_bitrate or ("64k" if audio_action == "aac_64k" else ("256k" if audio_action == "aac_256k" else "128k"))
                    cmd.extend(["-b:a", a_bitrate])

        # 6. Metadata Privacy Scrubbing
        if strip_metadata:
            cmd.extend(["-map_metadata", "-1", "-map_chapters", "-1"])

        if out_ext in ("mp4", "mov"):
            cmd.extend(["-movflags", "+faststart"])

        cmd.append(str(out_p))

        # Execute FFmpeg (Android bridge vs Desktop subprocess)
        try:
            from .android_bridge import is_android, android_bridge
            if is_android():
                rc, logs = android_bridge.execute_ffmpeg_android(cmd[1:])
                if rc != 0:
                    raise RuntimeError(f"FFmpegKit execution failed (code {rc}):\n{logs}")
                if not out_p.exists() or out_p.stat().st_size == 0:
                    raise RuntimeError(f"FFmpeg produced empty output at {out_p}")
                output_size = out_p.stat().st_size
                ratio = (1.0 - (output_size / input_size)) * 100.0 if input_size > 0 else 0.0
                return {
                    "success": True,
                    "input_path": str(in_p),
                    "output_path": str(out_p),
                    "input_size": input_size,
                    "output_size": output_size,
                    "size_bytes": output_size,
                    "savings_percent": round(max(0.0, ratio), 2),
                    "duration": effective_dur,
                    "error": None,
                }
        except Exception as e:
            if "is_android" in locals() and is_android():
                raise

        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if proc.returncode != 0:
            raise RuntimeError(f"FFmpeg execution failed (code {proc.returncode}):\n{proc.stderr}")

        if not out_p.exists() or out_p.stat().st_size == 0:
            raise RuntimeError(f"FFmpeg produced empty output at {out_p}")

        output_size = out_p.stat().st_size
        ratio = (1.0 - (output_size / input_size)) * 100.0 if input_size > 0 else 0.0

        return {
            "success": True,
            "input_path": str(in_p),
            "output_path": str(out_p),
            "input_size": input_size,
            "output_size": output_size,
            "size_bytes": output_size,
            "savings_percent": round(max(0.0, ratio), 2),
            "duration": effective_dur,
            "error": None,
        }
    except Exception as e:
        return {
            "success": False,
            "input_path": str(in_p),
            "output_path": str(out_p),
            "input_size": input_size,
            "output_size": 0,
            "size_bytes": 0,
            "savings_percent": 0.0,
            "duration": 0.0,
            "error": str(e),
        }
