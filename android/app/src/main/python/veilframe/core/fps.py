"""
Frame rate transformation module supporting subtle auto-perturbation and decimal FPS.
"""
from typing import Optional
from ..models.settings import FpsSettings
from ..models.video_info import VideoInfo


def calculate_fps(settings: FpsSettings, video_info: Optional[VideoInfo] = None) -> Optional[float]:
    """
    Calculates target frame rate.
    - If disabled: returns None
    - If auto: applies subtle ~99.8% micro-shift (e.g. 60 fps -> 59.8 fps, 30 fps -> 29.94 fps)
    - If manual: returns user specified decimal fps.
    """
    if not settings.enabled:
        return None

    src_fps = video_info.video.fps if (video_info and video_info.video and video_info.video.fps > 0) else 30.0

    if settings.mode == "auto":
        # Subtle micro-shift e.g. 60.0 -> 59.8, 30.0 -> 29.94
        target = round(src_fps * 0.998, 3)
        if target <= 1.0:
            target = round(src_fps, 3)
        return target

    # Manual mode
    target = round(float(settings.fps), 3)
    return max(1.0, min(240.0, target))


def build_fps_arg(settings: FpsSettings, video_info: Optional[VideoInfo] = None) -> list:
    """Returns FFmpeg CLI arguments e.g. ['-r', '59.8'] or empty list."""
    fps = calculate_fps(settings, video_info)
    if fps is None:
        return []
    # Format with clean decimal representation
    fps_str = f"{fps:.3f}".rstrip("0").rstrip(".")
    return ["-r", fps_str]
