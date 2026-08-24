"""
Trim and duration transformation module supporting subtle auto-perturbation and manual range.
"""
from typing import Optional, Tuple, List
from ..models.settings import TrimSettings
from ..models.video_info import VideoInfo


def parse_timestamp(ts: Optional[str]) -> Optional[float]:
    """Parses 'hh:mm:ss.xxx', 'mm:ss.xxx', or numeric strings into seconds."""
    if ts is None:
        return None
    if isinstance(ts, (int, float)):
        return float(ts)
    s = str(ts).strip()
    if not s:
        return None
    try:
        if ":" in s:
            parts = s.split(":")
            parts = [float(p) for p in parts]
            if len(parts) == 3:
                return parts[0] * 3600 + parts[1] * 60 + parts[2]
            elif len(parts) == 2:
                return parts[0] * 60 + parts[1]
        return float(s)
    except Exception:
        return None


def calculate_trim(settings: TrimSettings, video_info: Optional[VideoInfo] = None) -> Optional[Tuple[float, Optional[float]]]:
    """
    Calculates (start_seconds, duration_seconds).
    - If disabled: returns None
    - If auto: applies subtle 99.8% micro-trim (e.g. 100s -> 99.8s)
    - If manual: computes from start/end/duration.
    """
    if not settings.enabled:
        return None

    src_dur = video_info.duration if (video_info and video_info.duration > 0) else 60.0

    if settings.mode == "auto":
        # Subtle micro-trim: shave 0.2% off total length (e.g. 100s -> 99.8s)
        target_dur = round(max(0.1, src_dur * 0.998), 3)
        return (0.0, target_dur)

    # Manual mode
    start = max(0.0, float(settings.start or 0.0))
    if settings.duration is not None and settings.duration > 0:
        dur = float(settings.duration)
        return (start, dur)
    elif settings.end is not None and settings.end > start:
        dur = float(settings.end - start)
        return (start, dur)

    return (start, None)


def build_trim_args(settings: TrimSettings, video_info: Optional[VideoInfo] = None) -> List[str]:
    """Returns FFmpeg CLI arguments e.g. ['-ss', '0.000', '-t', '99.800']."""
    trim = calculate_trim(settings, video_info)
    if not trim:
        return []
    start, dur = trim
    args = []
    if start > 0:
        args += ["-ss", f"{start:.3f}"]
    if dur is not None and dur > 0:
        args += ["-t", f"{dur:.3f}"]
    return args
