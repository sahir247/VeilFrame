"""
VeilFrame UI 2.0 — Main window.

Dual-Mode Architecture:
  • Video Privacy Sanitizer (Multi-pass pipeline, PRNU/ENF/DCT bounded perturbation, 3-tier QualityGate).
  • Image Privacy Compiler (Multi-layer container purge, linear sRGB normalization, isolated ConstantFill semantic redaction, 7-probe adversarial red-team verification, 5-contract QualityGate).
"""

import subprocess
from pathlib import Path
from typing import Optional, Union

from PySide6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QFileDialog,
    QProgressBar, QMessageBox, QScrollArea, QFrame,
    QTabWidget, QApplication, QButtonGroup, QRadioButton,
)
from PySide6.QtCore import Qt, QThread, Signal, QTimer
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QIcon

from ..core.analyzer import analyze_video
from ..core.pipeline import run_pipeline
from ..core.verifier import VerificationReport
from ..models.video_info import VideoInfo
from ..models.settings import ProcessingSettings
from ..presets.manager import PresetManager

from ..image.pipeline import ImagePrivacyPipeline, ImageSanitizationResult
from ..image.models.policy import ImagePrivacyPolicy
from ..image.models.status import CheckStatus

from .video_info import VideoInfoWidget
from .image_panel import ImageInfoWidget, ImageProcessingPanel
from .processing_panel import ProcessingPanel
from .report_view import ReportViewWidget
from .preview_dialog import PreviewDialog


# ── Helpers ──────────────────────────────────────────────────────────── #

def _detect_ffmpeg_version() -> str:
    """Return short FFmpeg version string, e.g. '7.1.1', or '?' on failure."""
    try:
        result = subprocess.run(
            ["ffmpeg", "-version"],
            capture_output=True, text=True, timeout=5,
        )
        for line in result.stdout.splitlines():
            if line.startswith("ffmpeg version"):
                return line.split()[2]
    except Exception:
        pass
    return "?"


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".flv", ".ts", ".wmv"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


# ── Worker threads ─────────────────────────────────────────────────────── #

class VideoPipelineWorker(QThread):
    progress = Signal(float, str)   # percent, status message
    finished = Signal(object)       # VerificationReport
    failed = Signal(str)            # error message

    def __init__(self, src: Path, dst: Path, settings: ProcessingSettings):
        super().__init__()
        self.src = src
        self.dst = dst
        self.settings = settings
        self._is_cancelled = False

    def cancel(self):
        self._is_cancelled = True

    def run(self):
        try:
            report = run_pipeline(
                src_path=self.src,
                dst_path=self.dst,
                settings=self.settings,
                progress_callback=self._on_progress,
                cancel_check=lambda: self._is_cancelled,
            )
            self.finished.emit(report)
        except Exception as e:
            self.failed.emit(str(e))

    def _on_progress(self, pct: float, msg: str):
        self.progress.emit(pct, msg)


class ImagePipelineWorker(QThread):
    finished = Signal(object)       # ImageSanitizationResult
    failed = Signal(str)            # error message

    def __init__(self, src: Path, dst: Path, policy: ImagePrivacyPolicy):
        super().__init__()
        self.src = src
        self.dst = dst
        self.policy = policy

    def run(self):
        try:
            pipeline = ImagePrivacyPipeline(policy=self.policy)
            target_fmt = "PNG" if self.dst.suffix.lower() == ".png" else "WEBP" if self.dst.suffix.lower() == ".webp" else "JPEG"
            result = pipeline.run(
                input_source=self.src,
                output_path=self.dst,
                target_format=target_fmt,
            )
            self.finished.emit(result)
        except Exception as e:
            self.failed.emit(str(e))


# ── Drop Zone ─────────────────────────────────────────────────────────── #

class DropZoneWidget(QFrame):
    fileDropped = Signal(str)
    browseClicked = Signal()

    _BASE_STYLE = """
        QFrame#dropZone {
            background-color: #1e1e1e;
            border: 2px dashed #363636;
            border-radius: 6px;
        }
        QFrame#dropZone:hover {
            border-color: #4a4a4a;
            background-color: #222222;
        }
    """
    _ACTIVE_STYLE = """
        QFrame#dropZone {
            background-color: #1e2a3a;
            border: 2px dashed #3570e6;
            border-radius: 6px;
        }
    """

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("dropZone")
        self.setAcceptDrops(True)
        self.setFixedHeight(130)
        self.setStyleSheet(self._BASE_STYLE)
        self._init_ui()

    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setAlignment(Qt.AlignCenter)
        lay.setSpacing(4)
        lay.setContentsMargins(20, 16, 20, 16)

        lbl_main = QLabel("Drop a Video or Image File Here")
        lbl_main.setAlignment(Qt.AlignCenter)
        lbl_main.setStyleSheet(
            "color: #c0c0c0; font-size: 14px; font-weight: 600;"
            " background: transparent; letter-spacing: 0.2px;"
        )
        lay.addWidget(lbl_main)

        lbl_sub = QLabel("VIDEO: MP4 · MOV · MKV · WebM · AVI   |   IMAGE: JPEG · PNG · WebP")
        lbl_sub.setAlignment(Qt.AlignCenter)
        lbl_sub.setStyleSheet(
            "color: #555555; font-size: 10px; background: transparent; letter-spacing: 0.5px;"
        )
        lay.addWidget(lbl_sub)

        spacer = QLabel("")
        spacer.setFixedHeight(4)
        lay.addWidget(spacer)

        btn_row = QHBoxLayout()
        btn_row.addStretch()
        self.btn_browse = QPushButton("Browse Media File")
        self.btn_browse.clicked.connect(self.browseClicked.emit)
        btn_row.addWidget(self.btn_browse)
        btn_row.addStretch()
        lay.addLayout(btn_row)

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            self.setStyleSheet(self._ACTIVE_STYLE)

    def dragLeaveEvent(self, event):
        self.setStyleSheet(self._BASE_STYLE)

    def dropEvent(self, event: QDropEvent):
        self.setStyleSheet(self._BASE_STYLE)
        urls = event.mimeData().urls()
        if urls:
            file_path = urls[0].toLocalFile()
            if file_path:
                self.fileDropped.emit(file_path)


# ── Provider Status Bar ───────────────────────────────────────────────── #

class ProviderStatusBar(QFrame):
    """Slim bar showing runtime provider availability."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("card")
        self.setFixedHeight(34)
        self.setStyleSheet(
            "background: #1e1e1e; border: 1px solid #2e2e2e; border-radius: 4px;"
        )
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 0, 12, 0)
        lay.setSpacing(16)

        _s_ok   = "color: #3fb768; font-size: 11px; font-weight: 600;"
        _s_err  = "color: #d84040; font-size: 11px; font-weight: 600;"
        _s_mute = "color: #555555; font-size: 11px; font-weight: 600;"
        _s_sep  = "color: #303030; font-size: 11px;"

        self._ok   = _s_ok
        self._err  = _s_err
        self._mute = _s_mute

        self._lbl_ffmpeg = QLabel("FFmpeg  --")
        self._lbl_ffmpeg.setStyleSheet(_s_mute)
        lay.addWidget(self._lbl_ffmpeg)

        sep1 = QLabel("|")
        sep1.setStyleSheet(_s_sep)
        lay.addWidget(sep1)

        self._lbl_cv = QLabel("OpenCV  active")
        self._lbl_cv.setStyleSheet(_s_ok)
        lay.addWidget(self._lbl_cv)

        sep2 = QLabel("|")
        sep2.setStyleSheet(_s_sep)
        lay.addWidget(sep2)

        self._lbl_gate = QLabel("QualityGate  Fail-Closed (5 Contracts)")
        self._lbl_gate.setStyleSheet("color: #3fb768; font-size: 11px; font-weight: 600;")
        lay.addWidget(self._lbl_gate)

        lay.addStretch()

        self._note = QLabel("Providers measure. VeilFrame decides.")
        self._note.setStyleSheet("color: #383838; font-size: 10px; font-style: italic;")
        lay.addWidget(self._note)

    def populate(self, ffmpeg_version: str):
        if ffmpeg_version and ffmpeg_version != "?":
            self._lbl_ffmpeg.setText(f"FFmpeg  {ffmpeg_version}")
            self._lbl_ffmpeg.setStyleSheet(self._ok)
        else:
            self._lbl_ffmpeg.setText("FFmpeg  not found")
            self._lbl_ffmpeg.setStyleSheet(self._err)


# ── Main Window ───────────────────────────────────────────────────────── #

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("VeilFrame v2.0 — Auditable Multimedia Privacy Compiler")
        self.resize(1000, 960)
        self.setMinimumSize(840, 720)

        icon_path = Path(__file__).resolve().parent.parent / "resources" / "icon.svg"
        if icon_path.exists():
            self.setWindowIcon(QIcon(str(icon_path)))

        self.preset_mgr = PresetManager()
        self.src_path: Optional[Path] = None
        self.dst_path: Optional[Path] = None
        self.current_video_info: Optional[VideoInfo] = None
        self.is_image_mode = False

        self.video_worker: Optional[VideoPipelineWorker] = None
        self.image_worker: Optional[ImagePipelineWorker] = None

        self._init_ui()
        QTimer.singleShot(200, self._detect_providers)

    def _init_ui(self):
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)

        main_lay = QVBoxLayout(root)
        main_lay.setContentsMargins(16, 14, 16, 14)
        main_lay.setSpacing(10)

        # Header
        hdr = QHBoxLayout()
        title_col = QVBoxLayout()
        title_col.setSpacing(2)

        title_lbl = QLabel("VEILFRAME")
        title_lbl.setStyleSheet(
            "font-size: 18px; font-weight: 900; letter-spacing: 3px;"
            " color: #d8d8d8; background: transparent;"
        )
        subtitle_lbl = QLabel("Auditable Multimedia Privacy Compiler  v2.0")
        subtitle_lbl.setStyleSheet(
            "font-size: 11px; color: #555555; letter-spacing: 0.3px; background: transparent;"
        )
        title_col.addWidget(title_lbl)
        title_col.addWidget(subtitle_lbl)
        hdr.addLayout(title_col)
        hdr.addStretch()

        # Mode toggle buttons
        mode_box = QFrame()
        mode_box.setStyleSheet("background: #181818; border: 1px solid #333333; border-radius: 4px; padding: 2px;")
        mode_lay = QHBoxLayout(mode_box)
        mode_lay.setContentsMargins(2, 2, 2, 2)
        mode_lay.setSpacing(4)

        self.btn_mode_video = QPushButton("Video Sanitizer")
        self.btn_mode_video.setCheckable(True)
        self.btn_mode_video.setChecked(True)
        self.btn_mode_video.clicked.connect(lambda: self._set_mode(False))
        mode_lay.addWidget(self.btn_mode_video)

        self.btn_mode_image = QPushButton("Image Privacy Compiler")
        self.btn_mode_image.setCheckable(True)
        self.btn_mode_image.setChecked(False)
        self.btn_mode_image.clicked.connect(lambda: self._set_mode(True))
        mode_lay.addWidget(self.btn_mode_image)

        hdr.addWidget(mode_box)
        hdr.addSpacing(10)

        btn_about = QPushButton("About / Help")
        btn_about.clicked.connect(self._show_about)
        hdr.addWidget(btn_about)
        main_lay.addLayout(hdr)

        # Scrollable content
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll_content = QWidget()
        content_lay = QVBoxLayout(scroll_content)
        content_lay.setContentsMargins(0, 0, 8, 0)
        content_lay.setSpacing(12)

        # 1. Drop zone
        self.drop_zone = DropZoneWidget()
        self.drop_zone.fileDropped.connect(self.load_media_file)
        self.drop_zone.browseClicked.connect(self.browse_file)
        content_lay.addWidget(self.drop_zone)

        # 2. Provider status bar
        self.provider_bar = ProviderStatusBar()
        content_lay.addWidget(self.provider_bar)

        # 3. Input Info Cards (Video & Image)
        self.video_info_widget = VideoInfoWidget()
        content_lay.addWidget(self.video_info_widget)

        self.image_info_widget = ImageInfoWidget()
        self.image_info_widget.hide()
        content_lay.addWidget(self.image_info_widget)

        # 4. Processing panels (Video & Image)
        self.processing_panel = ProcessingPanel(self.preset_mgr)
        self.processing_panel.noise_widget.previewRequested.connect(self._open_preview)
        content_lay.addWidget(self.processing_panel)

        self.image_processing_panel = ImageProcessingPanel()
        self.image_processing_panel.hide()
        content_lay.addWidget(self.image_processing_panel)

        # 5. Report view
        self.report_widget = ReportViewWidget()
        content_lay.addWidget(self.report_widget)

        scroll.setWidget(scroll_content)
        main_lay.addWidget(scroll, 1)

        # Bottom action bar
        action_box = QFrame()
        action_box.setStyleSheet(
            "background-color: #141414; border-top: 1px solid #2a2a2a; padding-top: 6px;"
        )
        action_lay = QVBoxLayout(action_box)
        action_lay.setContentsMargins(0, 4, 0, 0)
        action_lay.setSpacing(6)

        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.hide()
        action_lay.addWidget(self.progress_bar)

        self.lbl_status = QLabel("Ready — load a video or image file to begin.")
        self.lbl_status.setStyleSheet("color: #555555; font-size: 11px;")
        action_lay.addWidget(self.lbl_status)

        btn_lay = QHBoxLayout()

        self.btn_cancel = QPushButton("Cancel")
        self.btn_cancel.setObjectName("cancelAction")
        self.btn_cancel.hide()
        self.btn_cancel.clicked.connect(self._cancel_job)
        btn_lay.addWidget(self.btn_cancel)

        btn_lay.addStretch()

        self.btn_process = QPushButton("PROCESS MEDIA")
        self.btn_process.setObjectName("primaryAction")
        self.btn_process.setEnabled(False)
        self.btn_process.clicked.connect(self.start_processing)
        btn_lay.addWidget(self.btn_process)

        action_lay.addLayout(btn_lay)
        main_lay.addWidget(action_box)

    def _set_mode(self, is_image: bool):
        self.is_image_mode = is_image
        self.btn_mode_image.setChecked(is_image)
        self.btn_mode_video.setChecked(not is_image)

        if is_image:
            self.video_info_widget.hide()
            self.processing_panel.hide()
            self.image_info_widget.show()
            self.image_processing_panel.show()
            self.btn_process.setText("SANITIZE IMAGE & VERIFY")
        else:
            self.image_info_widget.hide()
            self.image_processing_panel.hide()
            self.video_info_widget.show()
            self.processing_panel.show()
            self.btn_process.setText("PROCESS VIDEO")

    def _detect_providers(self):
        ver = _detect_ffmpeg_version()
        self.provider_bar.populate(ver)

    # ── File loading ──────────────────────────────────────────────────── #

    def browse_file(self):
        filter_str = (
            "All Supported Media (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts *.jpg *.jpeg *.png *.webp);;"
            "Video Files (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts);;"
            "Image Files (*.jpg *.jpeg *.png *.webp);;"
            "All Files (*.*)"
        )
        path, _ = QFileDialog.getOpenFileName(self, "Select Media File", "", filter_str)
        if path:
            self.load_media_file(path)

    def load_media_file(self, file_path_str: str):
        path = Path(file_path_str)
        if not path.exists():
            QMessageBox.critical(self, "Error", f"File does not exist:\n{file_path_str}")
            return

        ext = path.suffix.lower()
        if ext in IMAGE_EXTENSIONS:
            self._set_mode(True)
            self._load_image(path)
        else:
            self._set_mode(False)
            self._load_video(path)

    def _load_video(self, path: Path):
        self.lbl_status.setText(f"Analyzing {path.name}…")
        QApplication.processEvents()
        try:
            info = analyze_video(path)
            self.src_path = path
            self.current_video_info = info
            self.video_info_widget.set_video_info(info)
            self.processing_panel.set_video_info(info)
            self.report_widget.clear()
            self.btn_process.setEnabled(True)
            self.lbl_status.setText(f"Loaded Video: {path.name}  ({info.duration_str}, {info.size_str})")
        except Exception as e:
            QMessageBox.critical(self, "Analysis Error", f"Failed to analyze video:\n{e}")
            self.lbl_status.setText("Failed to load video file.")

    def _load_image(self, path: Path):
        self.lbl_status.setText(f"Loading image {path.name}…")
        QApplication.processEvents()
        try:
            self.src_path = path
            self.image_info_widget.set_image_file(path)
            self.report_widget.clear()
            self.btn_process.setEnabled(True)
            self.lbl_status.setText(f"Loaded Image: {path.name} — ready to compile & sanitize.")
        except Exception as e:
            QMessageBox.critical(self, "Image Error", f"Failed to load image:\n{e}")
            self.lbl_status.setText("Failed to load image file.")

    # ── Processing ────────────────────────────────────────────────────── #

    def start_processing(self):
        if not self.src_path:
            return

        if self.is_image_mode:
            self._start_image_processing()
        else:
            self._start_video_processing()

    def _start_video_processing(self):
        if not self.src_path or not self.current_video_info:
            return

        default_name = f"{self.src_path.stem}_cleaned.mp4"
        default_out = self.src_path.with_name(default_name)

        out_path_str, _ = QFileDialog.getSaveFileName(
            self, "Save Sanitized Video", str(default_out),
            "MP4 Video (*.mp4);;MKV Video (*.mkv);;WebM Video (*.webm);;All Files (*.*)",
        )
        if not out_path_str:
            return

        dst_path = Path(out_path_str)
        if dst_path.resolve() == self.src_path.resolve():
            QMessageBox.warning(self, "Invalid Output", "Output cannot overwrite input file.")
            return

        self.dst_path = dst_path
        settings = self.processing_panel.get_settings()

        self.btn_process.setEnabled(False)
        self.btn_cancel.show()
        self.progress_bar.setRange(0, 0)
        self.progress_bar.show()
        self.lbl_status.setText("Initializing multi-pass video privacy pipeline…")

        self.video_worker = VideoPipelineWorker(self.src_path, dst_path, settings)
        self.video_worker.progress.connect(self._on_video_progress)
        self.video_worker.finished.connect(self._on_video_finished)
        self.video_worker.failed.connect(self._on_video_failed)
        self.video_worker.start()

    def _start_image_processing(self):
        if not self.src_path:
            return

        ext = self.src_path.suffix.lower()
        default_name = f"{self.src_path.stem}_sanitized{ext}"
        default_out = self.src_path.with_name(default_name)

        out_path_str, _ = QFileDialog.getSaveFileName(
            self, "Save Sanitized Image", str(default_out),
            "JPEG Image (*.jpg *.jpeg);;PNG Image (*.png);;WebP Image (*.webp);;All Files (*.*)",
        )
        if not out_path_str:
            return

        dst_path = Path(out_path_str)
        if dst_path.resolve() == self.src_path.resolve():
            QMessageBox.warning(self, "Invalid Output", "Output cannot overwrite input file.")
            return

        self.dst_path = dst_path
        policy = self.image_processing_panel.get_policy()

        self.btn_process.setEnabled(False)
        self.btn_cancel.hide()
        self.progress_bar.setRange(0, 0)
        self.progress_bar.show()
        self.lbl_status.setText("Executing Image Privacy Compiler & Adversarial Red-Team...")

        self.image_worker = ImagePipelineWorker(self.src_path, dst_path, policy)
        self.image_worker.finished.connect(self._on_image_finished)
        self.image_worker.failed.connect(self._on_image_failed)
        self.image_worker.start()

    def _on_video_progress(self, pct: float, msg: str):
        if self.progress_bar.maximum() == 0 and pct > 0:
            self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(int(pct))
        self.lbl_status.setText(msg)

    def _on_video_finished(self, report: VerificationReport):
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(100)
        QTimer.singleShot(600, self.progress_bar.hide)
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Video processing, QualityGate, and verification complete.")

        self.report_widget.set_report(report)
        verdict = "PASS" if (report.all_passed and (not report.quality_report or report.quality_report.passed)) else "REJECT"
        QMessageBox.information(
            self, "Processing Complete",
            f"Verdict: {verdict}\n\nOutput saved to:\n{report.file_path}",
        )

    def _on_video_failed(self, err_msg: str):
        self.progress_bar.hide()
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Video processing failed.")
        QMessageBox.critical(self, "Processing Failed", f"An error occurred:\n{err_msg}")

    def _on_image_finished(self, result: ImageSanitizationResult):
        self.progress_bar.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText(f"Image sanitization complete — Verdict: {result.status.name}")

        self.report_widget.set_image_report(result)

        if result.is_success:
            QMessageBox.information(
                self, "Sanitization & Verification Succeeded",
                f"Status: PASS (5 Contracts & Red-Team Probes Verified)\n\n"
                f"Output saved to:\n{self.dst_path}\n\n"
                f"Cryptographic Audit Manifest published alongside output.",
            )
        else:
            QMessageBox.warning(
                self, "Sanitization Gate Failed",
                f"Status: {result.status.name} (QUARANTINED)\n\n"
                f"Failure reasons:\n" + "\n".join(result.manifest.failure_reasons if result.manifest else ["Unknown error"]),
            )

    def _on_image_failed(self, err_msg: str):
        self.progress_bar.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Image sanitization failed.")
        QMessageBox.critical(self, "Image Processing Failed", f"An error occurred:\n{err_msg}")

    def _cancel_job(self):
        if self.video_worker and self.video_worker.isRunning():
            self.lbl_status.setText("Cancelling video job…")
            self.video_worker.cancel()

    def _open_preview(self):
        if not self.src_path or not self.current_video_info:
            QMessageBox.information(self, "Load Video", "Please load a video first.")
            return
        settings = self.processing_panel.get_settings()
        dlg = PreviewDialog(self.src_path, settings, self.current_video_info, self)
        dlg.exec()

    def _show_about(self):
        QMessageBox.about(
            self, "About VeilFrame v2.0",
            "<h3>VeilFrame v2.0 — Auditable Multimedia Privacy Compiler</h3>"
            "<p><b>Core Subsystems:</b></p>"
            "<ul>"
            "<li><b>Video Privacy Sanitizer:</b> Container atom purge, SEI NAL stripping, Bayer CFA PRNU noise, "
            "2D DCT block dither, audio ENF filtering, and 3-tier QualityGate.</li>"
            "<li><b>Image Privacy Compiler:</b> Multi-layer container sanitization, linear sRGB normalization, "
            "isolated ConstantFill solid redaction (faces, plates, text, QR), 7 adversarial red-team probes, "
            "and 5 normative contracts.</li>"
            "<li><b>Cryptographic Provenance:</b> RFC 8785 canonical JSON with Ed25519 digital signatures.</li>"
            "</ul>"
            "<p><b>Invariant:</b> <i>Providers measure. VeilFrame decides.</i></p>"
            "<p><i>All operations run 100% locally. Zero network transmission.</i></p>",
        )
