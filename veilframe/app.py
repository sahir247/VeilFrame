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

    import os
    os.environ.setdefault("QT_LOGGING_RULES", "qt.qpa.fonts=false;qt.text.font.db=false;qt.text.font=false")

    from pathlib import Path
    from PySide6.QtGui import QIcon, QFont
    from PySide6.QtCore import QLoggingCategory, qInstallMessageHandler

    QLoggingCategory.setFilterRules("qt.qpa.fonts=false\nqt.text.font.db=false\nqt.text.font=false\n")

    def _qt_message_filter(msg_type, context, message):
        if "DirectWrite: CreateFontFaceFromHDC() failed" in message:
            return
        if "OpenType support missing" in message:
            return
        if "QFont::setPointSize" in message:
            return
        if context and context.category in ("qt.qpa.fonts", "qt.text.font.db", "qt.text.font"):
            return
        sys.stderr.write(f"{message}\n")

    qInstallMessageHandler(_qt_message_filter)

    app = QApplication(sys.argv)
    app.setApplicationName("VeilFrame")
    app.setOrganizationName("VeilFrame")

    # Set clean explicit application font with positive point size
    app_font = QFont("Segoe UI", 10)
    app.setFont(app_font)

    app.setStyleSheet(DARK_THEME_QSS)

    icon_path = Path(__file__).resolve().parent / "resources" / "icon.svg"
    if icon_path.exists():
        app.setWindowIcon(QIcon(str(icon_path)))

    # Global UX filters: Dropdown/Spinbox wheel safety + Button Click Micro-Animation Feedback
    from .gui.controls import UXWheelEventFilter
    from .gui.theme import ButtonClickBlinkFilter
    wheel_filter = UXWheelEventFilter(app)
    app.installEventFilter(wheel_filter)
    blink_filter = ButtonClickBlinkFilter(app)
    app.installEventFilter(blink_filter)

    window = MainWindow()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
