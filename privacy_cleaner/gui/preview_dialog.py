"""
Interactive side-by-side preview dialog for visual comparison of original vs processed frames.
"""
import tempfile
import subprocess
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QDialog,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QProgressBar,
    QMessageBox,
    QWidget,
)
from PySide6.QtGui import QPixmap, QImage
from PySide6.QtCore import Qt, QThread, Signal

from ..core.resources import get_ffmpeg_path
from ..core.encoder import build_filter_complex
from ..models.settings import ProcessingSettings
from ..models.video_info import VideoInfo


class FrameExtractorThread(QThread):
    done = Signal(str, str)  # orig_path, proc_path
    error = Signal(str)

    def __init__(self, src: Path, settings: ProcessingSettings, video_info: Optional[VideoInfo]):
        super().__init__()
        self.src = src
        self.settings = settings
        self.video_info = video_info

    def run(self):
        try:
            ffmpeg = get_ffmpeg_path()
            timestamp = "00:00:01.000"
            if self.settings.trim.enabled and self.settings.trim.start > 0:
                timestamp = f"{self.settings.trim.start:.3f}"

            with tempfile.NamedTemporaryFile(suffix="_orig.png", delete=False) as f_orig, \
                 tempfile.NamedTemporaryFile(suffix="_proc.png", delete=False) as f_proc:
                orig_path = Path(f_orig.name)
                proc_path = Path(f_proc.name)

            # 1. Extract original frame
            cmd_orig = [
                str(ffmpeg), "-hide_banner", "-y",
                "-ss", timestamp,
                "-i", str(self.src),
                "-vframes", "1",
                "-q:v", "2",
                str(orig_path),
            ]
            subprocess.run(cmd_orig, capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)

            # 2. Extract processed frame with filters
            vf = build_filter_complex(self.settings, self.video_info)
            cmd_proc = [
                str(ffmpeg), "-hide_banner", "-y",
                "-ss", timestamp,
                "-i", str(self.src),
            ]
            if vf:
                cmd_proc += ["-vf", vf]
            cmd_proc += [
                "-vframes", "1",
                "-q:v", "2",
                str(proc_path),
            ]
            subprocess.run(cmd_proc, capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)

            self.done.emit(str(orig_path), str(proc_path))
        except Exception as e:
            self.error.emit(str(e))


class PreviewDialog(QDialog):
    def __init__(self, src: Path, settings: ProcessingSettings, video_info: Optional[VideoInfo], parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("Visual Comparison — Original vs Processed")
        self.resize(1000, 650)
        self.src = src
        self.settings = settings
        self.video_info = video_info
        self.orig_tmp: Optional[Path] = None
        self.proc_tmp: Optional[Path] = None

        self._init_ui()
        self._load_frames()

    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setSpacing(12)

        # Header info
        hdr_lay = QHBoxLayout()
        lbl_info = QLabel("Compare visual effects (Noise, Crop, Resize) between Original and Processed frame:")
        lbl_info.setStyleSheet("color: #38bdf8; font-weight: 600; font-size: 13px;")
        hdr_lay.addWidget(lbl_info)
        hdr_lay.addStretch()
        lay.addLayout(hdr_lay)

        # Side-by-side frames container
        frames_lay = QHBoxLayout()

        # Left: Original
        v_left = QVBoxLayout()
        v_left.addWidget(QLabel("ORIGINAL FRAME", alignment=Qt.AlignCenter))
        self.scroll_orig = QScrollArea()
        self.lbl_img_orig = QLabel("Loading original frame...", alignment=Qt.AlignCenter)
        self.lbl_img_orig.setStyleSheet("color: #64748b; background: #090d16;")
        self.scroll_orig.setWidget(self.lbl_img_orig)
        self.scroll_orig.setWidgetResizable(True)
        v_left.addWidget(self.scroll_orig)
        frames_lay.addLayout(v_left)

        # Right: Processed
        v_right = QVBoxLayout()
        v_right.addWidget(QLabel("PROCESSED FRAME", alignment=Qt.AlignCenter))
        self.scroll_proc = QScrollArea()
        self.lbl_img_proc = QLabel("Rendering filter chain...", alignment=Qt.AlignCenter)
        self.lbl_img_proc.setStyleSheet("color: #64748b; background: #090d16;")
        self.scroll_proc.setWidget(self.lbl_img_proc)
        self.scroll_proc.setWidgetResizable(True)
        v_right.addWidget(self.scroll_proc)
        frames_lay.addLayout(v_right)

        lay.addLayout(frames_lay, 1)

        # Bottom Bar
        btm_lay = QHBoxLayout()
        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        btm_lay.addWidget(self.progress)

        self.lbl_disclaimer = QLabel(
            "Note: Minimum noise mode is designed to be visually subtle under normal viewing."
        )
        self.lbl_disclaimer.setStyleSheet("color: #64748b; font-size: 11px; font-style: italic;")
        btm_lay.addWidget(self.lbl_disclaimer)
        btm_lay.addStretch()

        btn_close = QPushButton("Close Preview")
        btn_close.clicked.connect(self.accept)
        btm_lay.addWidget(btn_close)
        lay.addLayout(btm_lay)

    def _load_frames(self):
        self.worker = FrameExtractorThread(self.src, self.settings, self.video_info)
        self.worker.done.connect(self._on_frames_ready)
        self.worker.error.connect(self._on_frame_error)
        self.worker.start()

    def _on_frames_ready(self, orig_path: str, proc_path: str):
        self.progress.hide()
        self.orig_tmp = Path(orig_path)
        self.proc_tmp = Path(proc_path)

        pix_orig = QPixmap(orig_path)
        pix_proc = QPixmap(proc_path)

        # Scale nicely for scroll view
        max_w = 460
        if not pix_orig.isNull():
            scaled_orig = pix_orig.scaledToWidth(max_w, Qt.SmoothTransformation)
            self.lbl_img_orig.setPixmap(scaled_orig)
        if not pix_proc.isNull():
            scaled_proc = pix_proc.scaledToWidth(max_w, Qt.SmoothTransformation)
            self.lbl_img_proc.setPixmap(scaled_proc)

    def _on_frame_error(self, err: str):
        self.progress.hide()
        self.lbl_img_orig.setText("Extraction failed")
        self.lbl_img_proc.setText("Extraction failed")
        QMessageBox.warning(self, "Preview Failed", f"Could not render preview frame:\n{err}")

    def closeEvent(self, event):
        # Cleanup temporary image files
        for p in (self.orig_tmp, self.proc_tmp):
            if p and p.exists():
                try:
                    p.unlink()
                except Exception:
                    pass
        super().closeEvent(event)
