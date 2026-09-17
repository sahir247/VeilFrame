"""
Audio privacy pipeline and Electrical Network Frequency (ENF) mains notch filtering module.
Neutralizes ENF hum (50Hz/60Hz/100Hz/120Hz) and applies micro-pitch modulation.
"""
from typing import Optional, List
from ..models.settings import AudioPrivacySettings
from ..models.video_info import VideoInfo


def build_audio_filtergraph(settings: AudioPrivacySettings, video_info: Optional[VideoInfo] = None) -> str:
    """
    Builds FFmpeg audio filter chain (-af):
    1. IIR mains notch filtering (50Hz, 60Hz, 100Hz, 120Hz harmonics)
    2. Micro-pitch adjustment (0.99x)
    """
    if not settings.enabled:
        return ""

    filters: List[str] = []

    # 1. Mains ENF Notch Filtering
    if settings.enf_notch:
        freqs = settings.enf_frequencies or [50, 60, 100, 120]
        for f in freqs:
            filters.append(f"bandreject=f={f}:w=1.5")

    # 2. Micro-pitch adjustment (0.99x ratio)
    if settings.micro_pitch:
        ratio = round(float(settings.pitch_ratio or 0.99), 3)
        if 0.90 <= ratio <= 1.10 and ratio != 1.0:
            sample_rate = 48000
            if video_info and video_info.audio and video_info.audio.sample_rate > 0:
                sample_rate = video_info.audio.sample_rate

            scaled_rate = int(sample_rate * ratio)
            tempo_comp = round(1.0 / ratio, 4)
            filters.append(f"asetrate={scaled_rate},aresample={sample_rate},atempo={tempo_comp}")

    return ",".join(filters)
