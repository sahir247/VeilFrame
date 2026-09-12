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
from PySide6.QtCore import Qt, QThread, Signal, QTimer, QUrl
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QIcon, QKeySequence, QShortcut, QDesktopServices

from ..core.resources import get_ffmpeg_path
from ..core.deps_manager import is_ffmpeg_installed, is_ffprobe_installed
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
from .dialogs import AboutDialog, EnvironmentDoctorDialog, prompt_missing_dependency


# ── Helpers ──────────────────────────────────────────────────────────── #

def _detect_ffmpeg_version() -> str:
    """Return short FFmpeg version string, e.g. '7.1.1', or '?' on failure."""
    try:
        p = get_ffmpeg_path()
        result = subprocess.run(
            [str(p), "-version"],
            capture_output=True, text=True, timeout=3,
        )
        for line in result.stdout.splitlines():
            if "ffmpeg version" in line:
                parts = line.split("version")
                if len(parts) > 1:
                    return parts[1].split()[0]
    except Exception:
        pass
    return "?"



VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".flv", ".ts", ".wmv"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".tiff", ".tif", ".bmp", ".gif", ".ico", ".ppm"}


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
            ext = self.dst.suffix.lower()
            if ext in (".jpg", ".jpeg"):
                target_fmt = "JPEG"
            elif ext == ".png":
                target_fmt = "PNG"
            elif ext == ".webp":
                target_fmt = "WEBP"
            elif ext in (".tif", ".tiff"):
                target_fmt = "TIFF"
            elif ext == ".bmp":
                target_fmt = "BMP"
            else:
                target_fmt = getattr(self.policy, "target_format", None) or "JPEG"

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

        self.lbl_main = QLabel("Drop a Video or Image File Here")
        self.lbl_main.setAlignment(Qt.AlignCenter)
        self.lbl_main.setStyleSheet(
            "color: #c0c0c0; font-size: 14px; font-weight: 600;"
            " background: transparent; letter-spacing: 0.2px;"
        )
        lay.addWidget(self.lbl_main)

        self.lbl_sub = QLabel("VIDEO: MP4 · MOV · MKV · WebM · AVI   |   IMAGE: JPEG · PNG · WebP")
        self.lbl_sub.setAlignment(Qt.AlignCenter)
        self.lbl_sub.setStyleSheet(
            "color: #555555; font-size: 10px; background: transparent; letter-spacing: 0.5px;"
        )
        lay.addWidget(self.lbl_sub)

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

    def set_mode_hint(self, is_image: bool):
        if is_image:
            self.lbl_main.setText("Drop an Image File Here")
            self.lbl_sub.setText("IMAGE PRIVACY COMPILER: JPEG · PNG · WebP · TIFF · BMP")
        else:
            self.lbl_main.setText("Drop a Video File Here")
            self.lbl_sub.setText("VIDEO PRIVACY SANITIZER: MP4 · MOV · MKV · WebM · AVI · TS")

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
    installRequested = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("card")
        self.setFixedHeight(34)
        self.setStyleSheet(
            "background: #181818; border: 1px solid #2e2e2e; border-radius: 4px;"
        )
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 0, 12, 0)
        lay.setSpacing(16)

        _s_ok   = "color: #3fb768; font-size: 11px; font-weight: 600;"
        _s_err  = "color: #ef4444; font-size: 11px; font-weight: 600;"
        _s_mute = "color: #555555; font-size: 11px; font-weight: 600;"
        _s_sep  = "color: #303030; font-size: 11px;"

        self._ok   = _s_ok
        self._err  = _s_err
        self._mute = _s_mute

        self._lbl_ffmpeg = QLabel("●  FFmpeg  --")
        self._lbl_ffmpeg.setStyleSheet(_s_mute)
        lay.addWidget(self._lbl_ffmpeg)

        sep1 = QLabel("|")
        sep1.setStyleSheet(_s_sep)
        lay.addWidget(sep1)

        self._lbl_cv = QLabel("●  OpenCV  active")
        self._lbl_cv.setStyleSheet(_s_ok)
        lay.addWidget(self._lbl_cv)

        sep2 = QLabel("|")
        sep2.setStyleSheet(_s_sep)
        lay.addWidget(sep2)

        self._lbl_gate = QLabel("●  QualityGate  Fail-Closed (5 Contracts)")
        self._lbl_gate.setStyleSheet("color: #3fb768; font-size: 11px; font-weight: 600;")
        lay.addWidget(self._lbl_gate)

        lay.addStretch()

        self._note = QLabel("Providers measure. VeilFrame decides.")
        self._note.setStyleSheet("color: #484848; font-size: 10px; font-style: italic;")
        lay.addWidget(self._note)

    def populate(self, ffmpeg_version: str):
        if ffmpeg_version and ffmpeg_version != "?":
            self._lbl_ffmpeg.setText(f"●  FFmpeg  {ffmpeg_version}")
            self._lbl_ffmpeg.setStyleSheet(self._ok)
            self._lbl_ffmpeg.setCursor(Qt.ArrowCursor)
            self._lbl_ffmpeg.setToolTip(f"FFmpeg binary version {ffmpeg_version} operational.")
            self._lbl_ffmpeg.mousePressEvent = None
        else:
            self._lbl_ffmpeg.setText("○  FFmpeg  Not Found (Click to Install)")
            self._lbl_ffmpeg.setStyleSheet(self._err + " text-decoration: underline;")
            self._lbl_ffmpeg.setCursor(Qt.PointingHandCursor)
            self._lbl_ffmpeg.setToolTip("Click here to automatically download and install FFmpeg.")
            self._lbl_ffmpeg.mousePressEvent = lambda ev: self.installRequested.emit()



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
        self._set_mode(False)
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

        # Mode toggle buttons (Segmented Control)
        mode_box = QFrame()
        mode_box.setStyleSheet("background: #181818; border: 1px solid #333333; border-radius: 5px; padding: 2px;")
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

        self.btn_doctor = QPushButton("Check Environment")
        self.btn_doctor.setStyleSheet("background-color: #222222; color: #d0d0d0; border: 1px solid #383838; padding: 5px 12px; border-radius: 4px;")
        self.btn_doctor.clicked.connect(self._show_environment_doctor)
        hdr.addWidget(self.btn_doctor)

        btn_about = QPushButton("About / Help")
        btn_about.setStyleSheet("background-color: #222222; color: #d0d0d0; border: 1px solid #383838; padding: 5px 12px; border-radius: 4px;")
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
        self.provider_bar.installRequested.connect(
            lambda: prompt_missing_dependency(self, "FFmpeg", on_success=self._detect_providers)
        )
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

        self.btn_open_folder = QPushButton("Open Output Folder")
        self.btn_open_folder.setStyleSheet(
            "background-color: #1e293b; color: #38bdf8; border: 1px solid #0284c7; "
            "border-radius: 4px; padding: 6px 14px; font-weight: 600; font-size: 11px;"
        )
        self.btn_open_folder.hide()
        self.btn_open_folder.clicked.connect(self._open_output_folder)
        btn_lay.addWidget(self.btn_open_folder)

        self.btn_copy_path = QPushButton("Copy File Path")
        self.btn_copy_path.setStyleSheet(
            "background-color: #262626; color: #d0d0d0; border: 1px solid #404040; "
            "border-radius: 4px; padding: 6px 14px; font-size: 11px;"
        )
        self.btn_copy_path.hide()
        self.btn_copy_path.clicked.connect(self._copy_output_path)
        btn_lay.addWidget(self.btn_copy_path)

        btn_lay.addStretch()

        self.btn_process = QPushButton("PROCESS MEDIA")
        self.btn_process.setObjectName("primaryAction")
        self.btn_process.setEnabled(False)
        self.btn_process.clicked.connect(self.start_processing)
        btn_lay.addWidget(self.btn_process)

        action_lay.addLayout(btn_lay)
        main_lay.addWidget(action_box)

        # Global Keyboard Shortcuts
        QShortcut(QKeySequence("Ctrl+O"), self, self.browse_file)
        QShortcut(QKeySequence("Ctrl+Return"), self, self.start_processing)
        QShortcut(QKeySequence("Ctrl+Enter"), self, self.start_processing)
        QShortcut(QKeySequence("Escape"), self, self._cancel_job)
        QShortcut(QKeySequence("F1"), self, self._show_about)
        QShortcut(QKeySequence("F5"), self, self._show_environment_doctor)

    def _set_mode(self, is_image: bool):
        self.is_image_mode = is_image
        self.btn_mode_image.setChecked(is_image)
        self.btn_mode_video.setChecked(not is_image)

        _active_style = (
            "background-color: #2563eb; color: #ffffff; font-weight: 700; "
            "border: none; border-radius: 4px; padding: 5px 14px; font-size: 11px;"
        )
        _inactive_style = (
            "background-color: transparent; color: #888888; font-weight: 500; "
            "border: none; border-radius: 4px; padding: 5px 14px; font-size: 11px;"
        )

        if is_image:
            self.btn_mode_image.setStyleSheet(_active_style)
            self.btn_mode_video.setStyleSheet(_inactive_style)
            self.drop_zone.set_mode_hint(True)
            self.video_info_widget.hide()
            self.processing_panel.hide()
            self.image_info_widget.show()
            self.image_processing_panel.show()
            self.btn_process.setText("SANITIZE IMAGE & VERIFY")
        else:
            self.btn_mode_video.setStyleSheet(_active_style)
            self.btn_mode_image.setStyleSheet(_inactive_style)
            self.drop_zone.set_mode_hint(False)
            self.image_info_widget.hide()
            self.image_processing_panel.hide()
            self.video_info_widget.show()
            self.processing_panel.show()
            self.btn_process.setText("PROCESS VIDEO")

    def _detect_providers(self):
        ver = _detect_ffmpeg_version()
        self.provider_bar.populate(ver)

    def _open_output_folder(self):
        if self.dst_path and self.dst_path.parent.exists():
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.dst_path.parent)))

    def _copy_output_path(self):
        if self.dst_path:
            QApplication.clipboard().setText(str(self.dst_path.resolve()))
            self.lbl_status.setText(f"Copied output path to clipboard: {self.dst_path.name}")

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
        if not is_ffmpeg_installed() or not is_ffprobe_installed():
            missing_tool = "FFprobe" if not is_ffprobe_installed() else "FFmpeg"
            installed = prompt_missing_dependency(
                self, missing_tool,
                on_success=lambda: (self._detect_providers(), self._load_video(path)),
            )
            if not installed:
                self.lbl_status.setText(f"Video loading cancelled — {missing_tool} is required.")
                return

        self.lbl_status.setText(f"Analyzing {path.name}…")
        QApplication.processEvents()
        try:
            info = analyze_video(path)
            self.src_path = path
            self.current_video_info = info
            self.video_info_widget.set_video_info(info)
            self.processing_panel.set_video_info(info)
            self.report_widget.clear()
            self.btn_open_folder.hide()
            self.btn_copy_path.hide()
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
            self.btn_open_folder.hide()
            self.btn_copy_path.hide()
            self.btn_process.setEnabled(True)
            self.lbl_status.setText(f"Loaded Image: {path.name} — ready to compile & sanitize.")
        except Exception as e:
            QMessageBox.critical(self, "Image Error", f"Failed to load image:\n{e}")
            self.lbl_status.setText("Failed to load image file.")

    # ── Processing ────────────────────────────────────────────────────── #

    def start_processing(self):
        if not self.src_path:
            return

        self.btn_open_folder.hide()
        self.btn_copy_path.hide()

        if self.is_image_mode:
            self._start_image_processing()
        else:
            self._start_video_processing()

    def _start_video_processing(self):
        if not self.src_path or not self.current_video_info:
            return

        if not is_ffmpeg_installed() or not is_ffprobe_installed():
            missing_tool = "FFprobe" if not is_ffprobe_installed() else "FFmpeg"
            installed = prompt_missing_dependency(
                self, missing_tool,
                on_success=lambda: (self._detect_providers(), self._start_video_processing()),
            )
            if not installed:
                self.lbl_status.setText(f"Video processing cancelled — {missing_tool} is required.")
                return

        if self.processing_panel.is_format_conversion_enabled():
            target_ext = self.processing_panel.get_target_extension() or self.src_path.suffix.lower()
        else:
            target_ext = self.src_path.suffix.lower() if self.src_path.suffix.lower() in VIDEO_EXTENSIONS else ".mp4"

        default_name = f"{self.src_path.stem}_cleaned{target_ext}"
        default_out = self.src_path.with_name(default_name)

        out_path_str, _ = QFileDialog.getSaveFileName(
            self, "Save Sanitized Video", str(default_out),
            "MP4 Video (*.mp4);;MKV Video (*.mkv);;WebM Video (*.webm);;QuickTime MOV (*.mov);;AVI Video (*.avi);;MPEG-TS (*.ts);;All Files (*.*)",
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

        if self.image_processing_panel.is_format_conversion_enabled():
            target_ext = self.image_processing_panel.get_target_extension() or self.src_path.suffix.lower()
        else:
            target_ext = self.src_path.suffix.lower()

        default_name = f"{self.src_path.stem}_sanitized{target_ext}"
        default_out = self.src_path.with_name(default_name)

        out_path_str, _ = QFileDialog.getSaveFileName(
            self, "Save Sanitized Image", str(default_out),
            "JPEG Image (*.jpg *.jpeg);;PNG Image (*.png);;WebP Image (*.webp);;TIFF Image (*.tiff *.tif);;BMP Image (*.bmp);;GIF Image (*.gif);;ICO Icon (*.ico);;PPM Image (*.ppm);;All Files (*.*)",
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
        self.btn_open_folder.show()
        self.btn_copy_path.show()
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
            self.btn_open_folder.show()
            self.btn_copy_path.show()
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
        dlg = AboutDialog(self)
        dlg.exec()

    def _show_environment_doctor(self):
        dlg = EnvironmentDoctorDialog(self)
        dlg.exec()
        self._detect_providers()

