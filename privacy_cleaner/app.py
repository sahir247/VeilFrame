"""
Application entry point and GUI bootstrap for Video Privacy Cleaner v1.
"""
import sys
from PySide6.QtWidgets import QApplication, QMessageBox
from PySide6.QtCore import Qt

from .gui.main_window import MainWindow
from .gui.theme import DARK_THEME_QSS
from .core.resources import get_ffmpeg_path, get_ffprobe_path, FFmpegNotFoundError


def main():
    # Force UTF-8 stream decoding for Windows terminals
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    if hasattr(sys.stderr, "reconfigure"):
        try:
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    # Enable High DPI pixmaps
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
    )

    app = QApplication(sys.argv)
    app.setApplicationName("Video Privacy Cleaner")
    app.setOrganizationName("PrivacyEngine")
    app.setStyleSheet(DARK_THEME_QSS)

    # Check for FFmpeg / FFprobe binaries
    try:
        get_ffmpeg_path()
        get_ffprobe_path()
    except FFmpegNotFoundError as e:
        QMessageBox.critical(
            None,
            "FFmpeg Missing",
            f"{e}\n\nPlease place 'ffmpeg.exe' and 'ffprobe.exe' in 'resources/ffmpeg/' or add them to PATH.",
        )
        sys.exit(1)

    window = MainWindow()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
