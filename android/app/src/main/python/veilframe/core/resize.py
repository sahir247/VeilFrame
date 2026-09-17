"""
Resize transformation module supporting subtle auto-perturbation and manual scaling.
"""
from typing import Optional, Tuple
from ..models.settings import ResizeSettings
from ..models.video_info import VideoInfo


def _make_even(val: int) -> int:
    """Ensure dimension is an even integer for video codec compatibility."""
    val = int(round(val))
    return val if val % 2 == 0 else val - 1


def calculate_resize(settings: ResizeSettings, video_info: Optional[VideoInfo] = None) -> Optional[Tuple[int, int]]:
    """
    Calculates (width, height) for scaling.
    - If disabled: returns None
    - If auto: applies a subtle 99.8% micro-scaling (e.g. 2160x3840 -> 2156x3832) aligned to even dimensions
    - If manual: computes user dimensions, optionally preserving aspect ratio.
    """
    if not settings.enabled:
        return None

    src_w = video_info.video.width if (video_info and video_info.video and video_info.video.width > 0) else 1920
    src_h = video_info.video.height if (video_info and video_info.video and video_info.video.height > 0) else 1080

    if settings.mode == "auto":
        # Subtle 99.8% micro-scaling
        target_w = _make_even(max(16, round(src_w * 0.998)))
        target_h = _make_even(max(16, round(src_h * 0.998)))
        return (target_w, target_h)

    # Manual mode
    target_w = settings.width if settings.width > 0 else src_w
    target_h = settings.height if settings.height > 0 else src_h

    if settings.maintain_aspect and src_w > 0 and src_h > 0:
        aspect = src_w / src_h
        # Adapt to whichever dimension was primary or clamp
        if target_w / target_h > aspect:
            target_w = round(target_h * aspect)
        else:
            target_h = round(target_w / aspect)

    target_w = _make_even(max(16, target_w))
    target_h = _make_even(max(16, target_h))

    return (target_w, target_h)


def build_resize_filter(settings: ResizeSettings, video_info: Optional[VideoInfo] = None) -> str:
    """Builds FFmpeg scale filter string e.g. 'scale=1916:1076:flags=lanczos'."""
    dims = calculate_resize(settings, video_info)
    if not dims:
        return ""
    w, h = dims
    return f"scale={w}:{h}:flags=lanczos"
