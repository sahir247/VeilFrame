"""
VeilFrame UI 2.0 — Main window.

Changes from v1.0:
  - Title updated to VeilFrame v1.1
  - Provider status bar (below drop zone, always visible)
  - Two-phase progress: indeterminate shimmer → determinate fill
  - VMAF evidence path passed to ReportViewWidget after processing
  - Gradient primary action button + cancel button with objectName
"""
import subprocess
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QLineEdit, QFileDialog,
    QProgressBar, QMessageBox, QScrollArea, QFrame,
    QTabWidget, QApplication, QSizePolicy,
)
from PySide6.QtCore import Qt, QThread, Signal, QTimer
from PySide6.QtGui import QDragEnterEvent, QDropEvent

from ..core.analyzer import analyze_video
from ..core.pipeline import run_pipeline
from ..core.verifier import VerificationReport
from ..models.video_info import VideoInfo
from ..models.settings import ProcessingSettings
from ..presets.manager import PresetManager
from .video_info import VideoInfoWidget
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


def _detect_libvmaf_available() -> bool:
    """Return True if the local FFmpeg build includes libvmaf."""
    try:
        result = subprocess.run(
            ["ffmpeg", "-filters"],
            capture_output=True, text=True, timeout=8,
        )
        return "libvmaf" in result.stdout
    except Exception:
        return False


# ── Worker thread ─────────────────────────────────────────────────────── #

class PipelineWorker(QThread):
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

        lbl_main = QLabel("Drop a Video File Here")
        lbl_main.setAlignment(Qt.AlignCenter)
        lbl_main.setStyleSheet(
            "color: #c0c0c0; font-size: 14px; font-weight: 600;"
            " background: transparent; letter-spacing: 0.2px;"
        )
        lay.addWidget(lbl_main)

        lbl_sub = QLabel("MP4  ·  MOV  ·  MKV  ·  WebM  ·  AVI  ·  M4V  ·  TS")
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
        self.btn_browse = QPushButton("Browse File")
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
    """
    Slim always-visible bar showing runtime provider availability.
    Populated once on startup; does not poll during processing.
    """

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

        self._lbl_vmaf = QLabel("libvmaf  --")
        self._lbl_vmaf.setStyleSheet(_s_mute)
        lay.addWidget(self._lbl_vmaf)

        sep2 = QLabel("|")
        sep2.setStyleSheet(_s_sep)
        lay.addWidget(sep2)

        self._lbl_gate = QLabel("Gate  SSIM + PSNR  active")
        self._lbl_gate.setStyleSheet("color: #3fb768; font-size: 11px; font-weight: 600;")
        lay.addWidget(self._lbl_gate)

        lay.addStretch()

        self._note = QLabel("Providers measure. VeilFrame decides.")
        self._note.setStyleSheet("color: #383838; font-size: 10px; font-style: italic;")
        lay.addWidget(self._note)

    def populate(self, ffmpeg_version: str, vmaf_available: bool):
        if ffmpeg_version and ffmpeg_version != "?":
            self._lbl_ffmpeg.setText(f"FFmpeg  {ffmpeg_version}")
            self._lbl_ffmpeg.setStyleSheet(self._ok)
        else:
            self._lbl_ffmpeg.setText("FFmpeg  not found")
            self._lbl_ffmpeg.setStyleSheet(self._err)

        if vmaf_available:
            self._lbl_vmaf.setText("libvmaf  available")
            self._lbl_vmaf.setStyleSheet(self._ok)
        else:
            self._lbl_vmaf.setText("libvmaf  not in build")
            self._lbl_vmaf.setStyleSheet(self._mute)


# ── Main Window ───────────────────────────────────────────────────────── #

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("VeilFrame v1.1 — Privacy-Focused Media Sanitization")
        self.resize(960, 960)
        self.setMinimumSize(820, 720)

        self.preset_mgr = PresetManager()
        self.src_path: Optional[Path] = None
        self.dst_path: Optional[Path] = None
        self.current_info: Optional[VideoInfo] = None
        self.worker: Optional[PipelineWorker] = None
        self._vmaf_available: bool = False

        self._init_ui()

        # Detect providers in background after window is shown
        QTimer.singleShot(200, self._detect_providers)

    # ── UI construction ──────────────────────────────────────────────── #

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
        subtitle_lbl = QLabel("Privacy-Focused Media Sanitization  v1.1")
        subtitle_lbl.setStyleSheet(
            "font-size: 11px; color: #555555; letter-spacing: 0.3px; background: transparent;"
        )
        title_col.addWidget(title_lbl)
        title_col.addWidget(subtitle_lbl)
        hdr.addLayout(title_col)
        hdr.addStretch()

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
        self.drop_zone.fileDropped.connect(self.load_video)
        self.drop_zone.browseClicked.connect(self.browse_file)
        content_lay.addWidget(self.drop_zone)

        # 2. Provider status bar
        self.provider_bar = ProviderStatusBar()
        content_lay.addWidget(self.provider_bar)

        # 3. Input info
        self.video_info_widget = VideoInfoWidget()
        content_lay.addWidget(self.video_info_widget)

        # 4. Processing controls
        self.processing_panel = ProcessingPanel(self.preset_mgr)
        self.processing_panel.noise_widget.previewRequested.connect(self._open_preview)
        content_lay.addWidget(self.processing_panel)

        # 5. Report view (3-tab)
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

        self.lbl_status = QLabel("Ready — load a video file to begin.")
        self.lbl_status.setStyleSheet("color: #555555; font-size: 11px;")
        action_lay.addWidget(self.lbl_status)

        btn_lay = QHBoxLayout()

        self.btn_cancel = QPushButton("Cancel")
        self.btn_cancel.setObjectName("cancelAction")
        self.btn_cancel.hide()
        self.btn_cancel.clicked.connect(self._cancel_job)
        btn_lay.addWidget(self.btn_cancel)

        btn_lay.addStretch()

        self.btn_process = QPushButton("PROCESS VIDEO")
        self.btn_process.setObjectName("primaryAction")
        self.btn_process.setEnabled(False)
        self.btn_process.clicked.connect(self.start_processing)
        btn_lay.addWidget(self.btn_process)

        action_lay.addLayout(btn_lay)
        main_lay.addWidget(action_box)

    # ── Provider detection ────────────────────────────────────────────── #

    def _detect_providers(self):
        ver = _detect_ffmpeg_version()
        self._vmaf_available = _detect_libvmaf_available()
        self.provider_bar.populate(ver, self._vmaf_available)

    # ── File loading ──────────────────────────────────────────────────── #

    def browse_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Select Video File", "",
            "Video Files (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.flv *.ts *.wmv);;All Files (*.*)",
        )
        if path:
            self.load_video(path)

    def load_video(self, file_path_str: str):
        path = Path(file_path_str)
        if not path.exists():
            QMessageBox.critical(self, "Error", f"File does not exist:\n{file_path_str}")
            return

        self.lbl_status.setText(f"Analyzing {path.name}…")
        QApplication.processEvents()

        try:
            info = analyze_video(path)
            self.src_path = path
            self.current_info = info
            self.video_info_widget.set_video_info(info)
            self.processing_panel.set_video_info(info)
            self.report_widget.clear()
            self.btn_process.setEnabled(True)
            self.lbl_status.setText(f"Loaded: {path.name}  ({info.duration_str}, {info.size_str})")
        except Exception as e:
            QMessageBox.critical(self, "Analysis Error", f"Failed to analyze video:\n{e}")
            self.lbl_status.setText("Failed to load video file.")

    # ── Processing ────────────────────────────────────────────────────── #

    def start_processing(self):
        if not self.src_path or not self.current_info:
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

        # UI: phase 1 — indeterminate shimmer
        self.btn_process.setEnabled(False)
        self.btn_cancel.show()
        self.progress_bar.setRange(0, 0)   # indeterminate
        self.progress_bar.show()
        self.lbl_status.setText("Initializing two-pass privacy pipeline…")

        self.worker = PipelineWorker(self.src_path, dst_path, settings)
        self.worker.progress.connect(self._on_worker_progress)
        self.worker.finished.connect(self._on_worker_finished)
        self.worker.failed.connect(self._on_worker_failed)
        self.worker.start()

    def _on_worker_progress(self, pct: float, msg: str):
        # Switch from indeterminate to determinate once we have a real percentage
        if self.progress_bar.maximum() == 0 and pct > 0:
            self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(int(pct))
        self.lbl_status.setText(msg)

    def _on_worker_finished(self, report: VerificationReport):
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(100)
        QTimer.singleShot(600, self.progress_bar.hide)
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Processing, quality gate, and verification complete.")

        # Determine VMAF evidence sibling path
        vmaf_evidence_path: Optional[Path] = None
        if self.dst_path:
            candidate = self.dst_path.with_name(f"{self.dst_path.stem}_vmaf.json")
            if candidate.exists():
                vmaf_evidence_path = candidate

        self.report_widget.set_report(report, vmaf_evidence_path=vmaf_evidence_path)

        verdict = "PASS" if (report.all_passed and (not report.quality_report or report.quality_report.passed)) else "REJECT"
        QMessageBox.information(
            self, "Processing Complete",
            f"Verdict: {verdict}\n\nOutput saved to:\n{report.file_path}",
        )

    def _on_worker_failed(self, err_msg: str):
        self.progress_bar.hide()
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Processing failed.")
        QMessageBox.critical(self, "Processing Failed", f"An error occurred:\n{err_msg}")

    def _cancel_job(self):
        if self.worker and self.worker.isRunning():
            self.lbl_status.setText("Cancelling…")
            self.worker.cancel()

    # ── Dialogs ───────────────────────────────────────────────────────── #

    def _open_preview(self):
        if not self.src_path or not self.current_info:
            QMessageBox.information(self, "Load Video", "Please load a video first.")
            return
        settings = self.processing_panel.get_settings()
        dlg = PreviewDialog(self.src_path, settings, self.current_info, self)
        dlg.exec()

    def _show_about(self):
        vmaf_status = "Available" if self._vmaf_available else "Not in this FFmpeg build"
        QMessageBox.about(
            self, "About VeilFrame v1.1",
            "<h3>VeilFrame v1.1 — Privacy-Focused Media Sanitization</h3>"
            "<p><b>Quality Engine Architecture (v1.1):</b></p>"
            "<ul>"
            "<li><b>VeilFrame Sanitizer:</b> Zeroes container metadata, drops SEI NALs, "
            "and applies bounded perturbations across spatial, temporal, frequency (PRNU dither), "
            "and audio ENF domains.</li>"
            "<li><b>VeilFrame Quality Gate (v4.0):</b> Independent read-only three-tier gate: "
            "Tier 1 policy score, Tier 2 SSIM ≥ 0.95 / PSNR ≥ 30.0 dB, Tier 3 temporal integrity.</li>"
            "<li><b>FFmpegNativeProvider:</b> SSIM + PSNR measured via libavfilter lavfi.</li>"
            f"<li><b>LibvmafFFmpegProvider:</b> VMAF evidence (measurement only in v1.1) — {vmaf_status}.</li>"
            "<li><b>VeilFrame Audit Engine:</b> Ed25519 signed audit manifests (v1.1.0 schema).</li>"
            "<li><b>VeilFrame Manifest Verifier:</b> Standalone third-party verifier.</li>"
            "</ul>"
            "<p><b>Invariant:</b> <i>Providers measure. VeilFrame decides.</i></p>"
            "<p><i>All processing occurs 100% locally — no network transmission.</i></p>",
        )
