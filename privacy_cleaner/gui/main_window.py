"""
Main application window for Video Privacy & Processing Engine v1.
"""
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QMainWindow,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QLineEdit,
    QFileDialog,
    QProgressBar,
    QMessageBox,
    QScrollArea,
    QFrame,
    QTabWidget,
    QApplication,
)
from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QIcon

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


class PipelineWorker(QThread):
    progress = Signal(float, str)  # percent, status message
    finished = Signal(object)      # VerificationReport
    failed = Signal(str)           # error message

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


class DropZoneWidget(QFrame):
    fileDropped = Signal(str)
    browseClicked = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setAcceptDrops(True)
        self._init_ui()

    def _init_ui(self):
        self.setObjectName("dropZone")
        self.setStyleSheet("""
            QFrame#dropZone {
                background-color: #1e293b;
                border: 2px dashed #475569;
                border-radius: 10px;
                padding: 18px;
            }
            QFrame#dropZone:hover {
                border-color: #38bdf8;
                background-color: #1e293b;
            }
        """)

        lay = QVBoxLayout(self)
        lay.setAlignment(Qt.AlignCenter)
        lay.setSpacing(8)

        self.lbl_icon = QLabel("🎬")
        self.lbl_icon.setAlignment(Qt.AlignCenter)
        self.lbl_icon.setStyleSheet("font-size: 28px; background: transparent;")
        lay.addWidget(self.lbl_icon)

        self.lbl_main = QLabel("Drag & Drop Video Here")
        self.lbl_main.setAlignment(Qt.AlignCenter)
        self.lbl_main.setStyleSheet("color: #f8fafc; font-size: 15px; font-weight: 600; background: transparent;")
        lay.addWidget(self.lbl_main)

        self.lbl_sub = QLabel("Supports MP4, MOV, MKV, WebM, AVI, M4V, TS")
        self.lbl_sub.setAlignment(Qt.AlignCenter)
        self.lbl_sub.setStyleSheet("color: #94a3b8; font-size: 12px; background: transparent;")
        lay.addWidget(self.lbl_sub)

        btn_row = QHBoxLayout()
        btn_row.addStretch()
        self.btn_browse = QPushButton("Browse Video File...")
        self.btn_browse.clicked.connect(self.browseClicked.emit)
        btn_row.addWidget(self.btn_browse)
        btn_row.addStretch()
        lay.addLayout(btn_row)

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            self.setStyleSheet("""
                QFrame#dropZone {
                    background-color: #0f2744;
                    border: 2px dashed #38bdf8;
                    border-radius: 10px;
                    padding: 18px;
                }
            """)

    def dragLeaveEvent(self, event):
        self.setStyleSheet("""
            QFrame#dropZone {
                background-color: #1e293b;
                border: 2px dashed #475569;
                border-radius: 10px;
                padding: 18px;
            }
        """)

    def dropEvent(self, event: QDropEvent):
        self.dragLeaveEvent(None)
        urls = event.mimeData().urls()
        if urls:
            file_path = urls[0].toLocalFile()
            if file_path:
                self.fileDropped.emit(file_path)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Video Privacy Cleaner v1")
        self.resize(920, 920)
        self.setMinimumSize(800, 700)

        self.preset_mgr = PresetManager()
        self.src_path: Optional[Path] = None
        self.current_info: Optional[VideoInfo] = None
        self.worker: Optional[PipelineWorker] = None

        self._init_ui()

    def _init_ui(self):
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)

        main_lay = QVBoxLayout(root)
        main_lay.setContentsMargins(16, 16, 16, 16)
        main_lay.setSpacing(12)

        # Header Title
        hdr_lay = QHBoxLayout()
        title_lbl = QLabel("VIDEO PRIVACY & PROCESSING ENGINE v1")
        title_lbl.setStyleSheet("font-size: 16px; font-weight: 800; color: #38bdf8; letter-spacing: 0.5px;")
        hdr_lay.addWidget(title_lbl)
        hdr_lay.addStretch()

        btn_about = QPushButton("About / Help")
        btn_about.clicked.connect(self._show_about)
        hdr_lay.addWidget(btn_about)
        main_lay.addLayout(hdr_lay)

        # Scroll area for scrollable content
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll_content = QWidget()
        content_lay = QVBoxLayout(scroll_content)
        content_lay.setContentsMargins(0, 0, 8, 0)
        content_lay.setSpacing(14)

        # 1. Drop Zone / Open File Bar
        self.drop_zone = DropZoneWidget()
        self.drop_zone.fileDropped.connect(self.load_video)
        self.drop_zone.browseClicked.connect(self.browse_file)
        content_lay.addWidget(self.drop_zone)

        # 2. Input Information Box
        self.video_info_widget = VideoInfoWidget()
        content_lay.addWidget(self.video_info_widget)

        # 3. Processing Controls Panel (with Presets, Noise, Privacy Checklist)
        self.processing_panel = ProcessingPanel(self.preset_mgr)
        self.processing_panel.noise_widget.previewRequested.connect(self._open_preview)
        content_lay.addWidget(self.processing_panel)

        # 4. Report View
        self.report_widget = ReportViewWidget()
        content_lay.addWidget(self.report_widget)

        scroll.setWidget(scroll_content)
        main_lay.addWidget(scroll, 1)

        # Bottom Action Bar
        action_box = QFrame()
        action_box.setStyleSheet("background-color: #0f172a; border-top: 1px solid #334155; padding-top: 8px;")
        action_lay = QVBoxLayout(action_box)
        action_lay.setContentsMargins(0, 4, 0, 0)
        action_lay.setSpacing(8)

        # Progress bar + Status Label
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.hide()
        action_lay.addWidget(self.progress_bar)

        self.lbl_status = QLabel("Ready. Load a video file to begin.")
        self.lbl_status.setStyleSheet("color: #94a3b8; font-size: 12px;")
        action_lay.addWidget(self.lbl_status)

        # Buttons row
        btn_lay = QHBoxLayout()
        self.btn_cancel = QPushButton("Cancel")
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

    def browse_file(self):
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Select Video File",
            "",
            "Video Files (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.flv *.ts *.wmv);;All Files (*.*)",
        )
        if path:
            self.load_video(path)

    def load_video(self, file_path_str: str):
        path = Path(file_path_str)
        if not path.exists():
            QMessageBox.critical(self, "Error", f"File does not exist:\n{file_path_str}")
            return

        self.lbl_status.setText(f"Analyzing {path.name}...")
        QApplication.processEvents()

        try:
            info = analyze_video(path)
            self.src_path = path
            self.current_info = info
            self.video_info_widget.set_video_info(info)
            self.processing_panel.set_video_info(info)
            self.report_widget.clear()
            self.btn_process.setEnabled(True)
            self.lbl_status.setText(f"Loaded: {path.name} ({info.duration_str}, {info.size_str})")
        except Exception as e:
            QMessageBox.critical(self, "Analysis Error", f"Failed to analyze video:\n{e}")
            self.lbl_status.setText("Failed to load video file.")

    def start_processing(self):
        if not self.src_path or not self.current_info:
            return

        # Prompt for save output destination
        default_out_name = f"{self.src_path.stem}_cleaned.mp4"
        default_out = self.src_path.with_name(default_out_name)

        out_path_str, _ = QFileDialog.getSaveFileName(
            self,
            "Save Sanitized Video",
            str(default_out),
            "MP4 Video (*.mp4);;MKV Video (*.mkv);;WebM Video (*.webm);;All Files (*.*)",
        )
        if not out_path_str:
            return

        dst_path = Path(out_path_str)
        if dst_path.resolve() == self.src_path.resolve():
            QMessageBox.warning(self, "Invalid Output", "Output destination cannot overwrite input file directly.")
            return

        settings = self.processing_panel.get_settings()

        # UI state for processing
        self.btn_process.setEnabled(False)
        self.btn_cancel.show()
        self.progress_bar.setValue(0)
        self.progress_bar.show()
        self.lbl_status.setText("Initializing two-pass privacy pipeline...")

        self.worker = PipelineWorker(self.src_path, dst_path, settings)
        self.worker.progress.connect(self._on_worker_progress)
        self.worker.finished.connect(self._on_worker_finished)
        self.worker.failed.connect(self._on_worker_failed)
        self.worker.start()

    def _on_worker_progress(self, pct: float, msg: str):
        self.progress_bar.setValue(int(pct))
        self.lbl_status.setText(msg)

    def _on_worker_finished(self, report: VerificationReport):
        self.progress_bar.hide()
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Processing & verification completed successfully.")
        self.report_widget.set_report(report)

        QMessageBox.information(
            self,
            "Processing Complete",
            f"Video successfully sanitized and verified!\n\nOutput saved to:\n{report.file_path}",
        )

    def _on_worker_failed(self, err_msg: str):
        self.progress_bar.hide()
        self.btn_cancel.hide()
        self.btn_process.setEnabled(True)
        self.lbl_status.setText("Processing failed.")
        QMessageBox.critical(self, "Processing Failed", f"An error occurred during processing:\n{err_msg}")

    def _cancel_job(self):
        if self.worker and self.worker.isRunning():
            self.lbl_status.setText("Cancelling processing...")
            self.worker.cancel()

    def _open_preview(self):
        if not self.src_path or not self.current_info:
            QMessageBox.information(self, "Load Video", "Please load a video first to preview filters.")
            return
        settings = self.processing_panel.get_settings()
        dlg = PreviewDialog(self.src_path, settings, self.current_info, self)
        dlg.exec()

    def _show_about(self):
        QMessageBox.about(
            self,
            "About Video Privacy Cleaner v1",
            "<h3>Video Privacy & Processing Engine v1</h3>"
            "<p><b>Two-Pass Privacy Sanitization Architecture:</b></p>"
            "<ul>"
            "<li><b>Pass 1 (Pre-Sanitize):</b> Strips existing container metadata, tags, chapters, and attachments.</li>"
            "<li><b>Pass 2 (Encode):</b> Applies visual/temporal transformations with clean audio and bitexact flags.</li>"
            "<li><b>Pass 3 (Post-Sanitize):</b> Removes encoder tags and writes clean container headers.</li>"
            "<li><b>Verification:</b> Fresh inspection generating a formal Privacy Report.</li>"
            "</ul>"
            "<p><b>Noise Control Behavior:</b></p>"
            "<ul>"
            "<li><b>OFF:</b> No noise filter added.</li>"
            "<li><b>ON + Minimum:</b> Low-amplitude temporal noise intended to be visually subtle under normal viewing.</li>"
            "</ul>"
            "<p><i>Note: The application performs file sanitization and video processing locally on your machine without network access. It does not claim to make media untraceable.</i></p>",
        )
