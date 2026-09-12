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

    from pathlib import Path
    from PySide6.QtGui import QIcon

    app = QApplication(sys.argv)
    app.setApplicationName("VeilFrame")
    app.setOrganizationName("VeilFrame")

    # Set explicit font point size to prevent QFont::setPointSize <= 0 warning
    app_font = app.font()
    if app_font.pointSize() <= 0:
        app_font.setPointSize(10)
    app.setFont(app_font)

    app.setStyleSheet(DARK_THEME_QSS)

    icon_path = Path(__file__).resolve().parent / "resources" / "icon.svg"
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))

    # Global UX filter: Dropdowns only controlled via click/keys, Spinboxes require focus for wheel
    from .gui.controls import UXWheelEventFilter
    wheel_filter = UXWheelEventFilter(app)
    app.installEventFilter(wheel_filter)

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
