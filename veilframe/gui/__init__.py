"""
GUI package for VeilFrame v1.1.
"""
from .main_window import MainWindow
from .video_info import VideoInfoWidget
from .processing_panel import ProcessingPanel
from .noise_control import NoiseControlWidget
from .report_view import ReportViewWidget
from .preview_dialog import PreviewDialog
from .quality_panel import QualityPanel
from .theme import DARK_THEME_QSS, badge_style

__all__ = [
    "MainWindow",
    "VideoInfoWidget",
    "ProcessingPanel",
    "NoiseControlWidget",
    "ReportViewWidget",
    "PreviewDialog",
    "QualityPanel",
    "DARK_THEME_QSS",
    "badge_style",
]
