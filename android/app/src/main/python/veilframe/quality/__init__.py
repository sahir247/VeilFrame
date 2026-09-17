"""
Quality measurement and gating package for VeilFrame.
"""
from .models import QualityConfig, QualityResult, PerFrameMetric
from .provider import QualityProvider
from .gate import QualityGate
from .adapters.ffmpeg import FFmpegNativeProvider

__all__ = [
    "QualityConfig",
    "QualityResult",
    "PerFrameMetric",
    "QualityProvider",
    "QualityGate",
    "FFmpegNativeProvider",
]
