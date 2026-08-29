"""
Quality provider adapters package for VeilFrame.
"""
from .ffmpeg import FFmpegNativeProvider
from .vmaf import LibvmafFFmpegProvider

__all__ = [
    "FFmpegNativeProvider",
    "LibvmafFFmpegProvider",
]
