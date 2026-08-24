"""
Noise engine module supporting subtle auto-perturbation, normalized 0-100 strength, and filter building.
"""
from typing import Optional, Tuple
from ..models.settings import NoiseSettings
from ..models.video_info import VideoInfo


def get_noise_level_label(strength: int) -> Tuple[str, str]:
    """
    Returns (label, category) for a given 0-100 strength:
    0: Disabled
    1-10: Extremely Subtle (intended to be visually imperceptible under normal viewing)
    11-30: Subtle
    31-60: Noticeable / Visible
    61-100: Strong
    """
    if strength <= 0:
        return ("Disabled", "off")
    elif strength <= 10:
        return ("Extremely Subtle", "subtle")
    elif strength <= 30:
        return ("Subtle", "subtle")
    elif strength <= 60:
        return ("Noticeable / Visible", "visible")
    else:
        return ("Strong", "strong")


def calculate_noise_strength(settings: NoiseSettings) -> int:
    """
    Calculates 0-100 normalized noise strength.
    - If disabled: returns 0
    - If auto: returns subtle perturbation level (e.g. 2)
    - If manual: returns slider value clamped to 0-100.
    """
    if not settings.enabled:
        return 0

    if settings.mode == "auto":
        # Subtle default perturbation
        return 2

    return max(0, min(100, int(settings.strength)))


def build_noise_filter(settings: NoiseSettings, video_info: Optional[VideoInfo] = None) -> str:
    """
    Builds FFmpeg noise filter string.
    - Strength 0 / Disabled: returns "" (no noise filter added)
    - Strength 1-10: very-low-amplitude temporal noise (e.g. amp=1-2)
    - Strength 11-30: subtle noise (amp=3-6)
    - Strength 31-60: noticeable noise (amp=7-14)
    - Strength 61-100: strong noise (amp=15-25)
    """
    strength = calculate_noise_strength(settings)
    if strength <= 0:
        return ""

    if strength == 1:
        # Minimum level: lowest amplitude temporal noise
        amp = 1
    else:
        # Scale smoothly up to 25
        amp = max(1, round(1 + (strength - 1) * 0.24))

    # 'alls' is noise strength for all components (luma and chroma)
    # 'allf=t+u' enables temporal + uniform noise pattern to disrupt frame fingerprinting
    return f"noise=alls={amp}:allf=t+u"
