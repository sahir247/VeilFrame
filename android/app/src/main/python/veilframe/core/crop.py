"""
Crop transformation module supporting asymmetric spatial geometry perturbation and manual coordinate cropping.
"""
from typing import Optional, Tuple
from ..models.settings import CropSettings
from ..models.video_info import VideoInfo


def _make_even(val: int) -> int:
    """Ensure dimension is even for video codec compatibility (yuv420p)."""
    val = int(round(val))
    return val if val % 2 == 0 else val - 1


def calculate_crop(settings: CropSettings, video_info: Optional[VideoInfo] = None) -> Optional[Tuple[int, int, int, int]]:
    """
    Calculates (x, y, width, height) crop rectangle.
    - If disabled: returns None
    - If auto (asymmetric): applies asymmetric bounding crop (e.g. left 1.5%, right 1.0%, top 1.8%, bottom 0.7%)
      to break spatial pHash grids and defeat scale/offset sweep inversion attacks.
    - If manual: validates user specified bounds and ensures even dimensions.
    """
    if not settings.enabled:
        return None

    src_w = video_info.video.width if (video_info and video_info.video and video_info.video.width > 0) else 1920
    src_h = video_info.video.height if (video_info and video_info.video and video_info.video.height > 0) else 1080

    if settings.mode == "auto":
        if getattr(settings, "asymmetric", True):
            # Asymmetric geometry perturbation: Left 1.5%, Right 1.0%, Top 1.8%, Bottom 0.7% (~2.5% total surface)
            off_left = max(2, _make_even(int(src_w * 0.015)))
            off_right = max(2, _make_even(int(src_w * 0.010)))
            off_top = max(2, _make_even(int(src_h * 0.018)))
            off_bottom = max(2, _make_even(int(src_h * 0.007)))

            target_w = _make_even(src_w - off_left - off_right)
            target_h = _make_even(src_h - off_top - off_bottom)
            x = off_left
            y = off_top
        else:
            # Symmetric subtle crop
            margin_x = max(2, _make_even(int(src_w * 0.002)) or 2)
            margin_y = max(2, _make_even(int(src_h * 0.002)) or 2)
            target_w = _make_even(src_w - (margin_x * 2))
            target_h = _make_even(src_h - (margin_y * 2))
            x = margin_x
            y = margin_y

        if target_w < 16 or target_h < 16:
            return None
        return (x, y, target_w, target_h)

    # Manual mode
    if settings.width > 0 and settings.height > 0:
        w = min(settings.width, src_w)
        h = min(settings.height, src_h)
        x = max(0, min(settings.x, src_w - w))
        y = max(0, min(settings.y, src_h - h))
    else:
        # Left, Right, Top, Bottom format
        x = max(0, settings.left)
        y = max(0, settings.top)
        w = max(16, src_w - x - max(0, settings.right))
        h = max(16, src_h - y - max(0, settings.bottom))

    w = _make_even(w)
    h = _make_even(h)
    x = _make_even(x)
    y = _make_even(y)

    if w <= 0 or h <= 0 or x + w > src_w or y + h > src_h:
        return None

    return (x, y, w, h)


def build_crop_filter(settings: CropSettings, video_info: Optional[VideoInfo] = None) -> str:
    """Builds FFmpeg crop filter string e.g. 'crop=1872:1052:28:18'."""
    rect = calculate_crop(settings, video_info)
    if not rect:
        return ""
    x, y, w, h = rect
    return f"crop={w}:{h}:{x}:{y}"
