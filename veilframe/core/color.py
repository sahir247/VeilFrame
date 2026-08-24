"""
Color and Luminance drift transformation module (~1% perceptual perturbation budget).
Alters color histograms and ISP sensor-tuning profiles without visible degradation.
"""
from typing import Optional
from ..models.settings import ColorSettings
from ..models.video_info import VideoInfo


def build_color_filter(settings: ColorSettings, video_info: Optional[VideoInfo] = None) -> str:
    """
    Builds FFmpeg 'eq' filter for color/luminance perturbation:
    - Auto mode: contrast=1.015, brightness=0.005, gamma=0.985, saturation=1.02 (~1% bounded drift)
    - Manual mode: applies user specified parameters.
    """
    if not settings.enabled:
        return ""

    if settings.mode == "auto":
        c = 1.015
        b = 0.005
        g = 0.985
        s = 1.02
    else:
        c = round(float(settings.contrast), 4)
        b = round(float(settings.brightness), 4)
        g = round(float(settings.gamma), 4)
        s = round(float(settings.saturation), 4)

    return f"eq=contrast={c}:brightness={b}:gamma={g}:saturation={s}"
