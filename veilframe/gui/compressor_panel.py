"""
VeilFrame GUI — Media Compressor & Studio Panel.
Provides desktop GUI interface for unified image and video compression,
visual range trimming, target presets, downscaling, and metadata controls.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

from PySide6.QtCore import Qt, QThread, Signal, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSlider,
    QSpinBox,
    QDoubleSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..core.analyzer import analyze_video
from ..core.media_compressor import compress_image, compress_video

VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".flv", ".ts", ".wmv"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".tiff", ".tif", ".bmp"}


class CompressWorker(QThread):
    progress = Signal(float, str)
    finished = Signal(dict)
    failed = Signal(str)

    def __init__(self, media_type: str, kwargs: dict):
        super().__init__()
        self.media_type = media_type
        self.kwargs = kwargs

    def run(self):
        try:
            if self.media_type == "image":
                self.progress.emit(0.2, "Processing image transformation...")
                res = compress_image(**self.kwargs)
                self.progress.emit(1.0, "Image compression complete.")
                self.finished.emit(res)
            else:
                self.progress.emit(0.2, "Executing video compression & encoding...")
                res = compress_video(**self.kwargs)
                self.progress.emit(1.0, "Video compression complete.")
                self.finished.emit(res)
        except Exception as e:
            self.failed.emit(str(e))


class MediaCompressorPanel(QWidget):
    """Integrated Desktop Studio for Media Compression & Editing."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.src_path: Optional[Path] = None
        self.dst_path: Optional[Path] = None
        self.media_type = "video"  # "video" or "image"
        self.total_duration = 0.0
        self.orig_width = 0
        self.orig_height = 0
        self.worker: Optional[CompressWorker] = None

        self._init_ui()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(0, 0, 0, 0)
        main_lay.setSpacing(12)

        # ── 1. Target Selector Card ─────────────────────────────────────── #
        sel_box = QGroupBox("SOURCE MEDIA SELECTION")
        s_lay = QVBoxLayout(sel_box)
        s_row = QHBoxLayout()

        self.btn_select_file = QPushButton("Select Video or Image to Compress")
        self.btn_select_file.setStyleSheet("""
            QPushButton {
                background-color: #2563eb;
                color: #ffffff;
                font-weight: 700;
                padding: 8px 18px;
                border-radius: 4px;
                font-size: 12px;
            }
            QPushButton:hover { background-color: #3b82f6; }
        """)
        self.btn_select_file.clicked.connect(self._browse_file)
        s_row.addWidget(self.btn_select_file)

        self.lbl_file_info = QLabel("No media file loaded.")
        self.lbl_file_info.setStyleSheet("color: #909090; font-size: 11px;")
        s_row.addWidget(self.lbl_file_info, 1)

        s_lay.addLayout(s_row)
        main_lay.addWidget(sel_box)

        # ── 2. Scrollable Configuration Area ────────────────────────────── #
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll_content = QWidget()
        self.config_lay = QVBoxLayout(scroll_content)
        self.config_lay.setContentsMargins(0, 0, 0, 0)
        self.config_lay.setSpacing(12)

        # === Video Controls Container ===
        self.video_container = QWidget()
        v_lay = QVBoxLayout(self.video_container)
        v_lay.setContentsMargins(0, 0, 0, 0)
        v_lay.setSpacing(12)

        # Video: Presets
        preset_box = QGroupBox("TARGET PLATFORM / SIZE PRESET")
        p_grid = QGridLayout(preset_box)
        p_grid.addWidget(QLabel("Target Preset:"), 0, 0)

        self.combo_video_presets = QComboBox()
        self.combo_video_presets.addItems([
            "Auto Quality (Balanced CRF 28)",
            "WhatsApp (16 MB Target)",
            "Discord Standard (25 MB Target)",
            "Discord Nitro (50 MB Target)",
            "Email Attachment (8 MB Target)",
            "Web Video (10 MB Target)",
        ])
        self.combo_video_presets.currentIndexChanged.connect(self._on_video_preset_changed)
        p_grid.addWidget(self.combo_video_presets, 0, 1)

        self.lbl_preset_detail = QLabel("Dynamically allocates multi-pass video bitrate to strictly fit within target ceiling.")
        self.lbl_preset_detail.setStyleSheet("color: #707070; font-size: 11px;")
        p_grid.addWidget(self.lbl_preset_detail, 1, 0, 1, 2)
        v_lay.addWidget(preset_box)

        # Video: Timeline Range Trimmer
        trim_box = QGroupBox("VISUAL TIMELINE RANGE TRIMMER")
        t_lay = QVBoxLayout(trim_box)

        t_row = QHBoxLayout()
        t_row.addWidget(QLabel("Start (sec):"))
        self.spin_trim_start = QDoubleSpinBox()
        self.spin_trim_start.setRange(0.0, 99999.0)
        self.spin_trim_start.setSingleStep(0.5)
        self.spin_trim_start.setValue(0.0)
        self.spin_trim_start.valueChanged.connect(self._update_trim_duration)
        t_row.addWidget(self.spin_trim_start)

        t_row.addWidget(QLabel("End (sec):"))
        self.spin_trim_end = QDoubleSpinBox()
        self.spin_trim_end.setRange(0.0, 99999.0)
        self.spin_trim_end.setSingleStep(0.5)
        self.spin_trim_end.setValue(0.0)
        self.spin_trim_end.valueChanged.connect(self._update_trim_duration)
        t_row.addWidget(self.spin_trim_end)

        self.lbl_trim_dur = QLabel("Duration: 0.0s (Full)")
        self.lbl_trim_dur.setStyleSheet("color: #38bdf8; font-weight: 600; font-size: 11px;")
        t_row.addWidget(self.lbl_trim_dur)
        t_lay.addLayout(t_row)

        quick_trim_row = QHBoxLayout()
        self.btn_trim_story = QPushButton("Story (15s)")
        self.btn_trim_story.clicked.connect(lambda: self._apply_quick_trim(0.0, 15.0))
        quick_trim_row.addWidget(self.btn_trim_story)

        self.btn_trim_status = QPushButton("Status (30s)")
        self.btn_trim_status.clicked.connect(lambda: self._apply_quick_trim(0.0, 30.0))
        quick_trim_row.addWidget(self.btn_trim_status)

        self.btn_trim_mid = QPushButton("Middle Half")
        self.btn_trim_mid.clicked.connect(self._trim_middle_half)
        quick_trim_row.addWidget(self.btn_trim_mid)

        self.btn_trim_full = QPushButton("Full Duration")
        self.btn_trim_full.clicked.connect(self._trim_full)
        quick_trim_row.addWidget(self.btn_trim_full)

        t_lay.addLayout(quick_trim_row)
        v_lay.addWidget(trim_box)

        # Video: Scaling & Framing
        scale_box = QGroupBox("RESOLUTION & FRAMING")
        sc_grid = QGridLayout(scale_box)

        sc_grid.addWidget(QLabel("Downscale Resolution:"), 0, 0)
        self.combo_video_res = QComboBox()
        self.combo_video_res.addItems(["Original", "1080p (Full HD)", "720p (HD)", "480p (SD)", "360p (Small)"])
        sc_grid.addWidget(self.combo_video_res, 0, 1)

        sc_grid.addWidget(QLabel("Aspect Ratio:"), 1, 0)
        self.combo_video_aspect = QComboBox()
        self.combo_video_aspect.addItems(["Original", "9:16 (Reel / Shorts)", "1:1 (Square)", "16:9 (Landscape)", "4:3 (Classic)"])
        sc_grid.addWidget(self.combo_video_aspect, 1, 1)

        sc_grid.addWidget(QLabel("Playback Speed:"), 2, 0)
        self.combo_video_speed = QComboBox()
        self.combo_video_speed.addItems(["0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x"])
        self.combo_video_speed.setCurrentIndex(2)
        sc_grid.addWidget(self.combo_video_speed, 2, 1)

        sc_grid.addWidget(QLabel("Audio Stream:"), 3, 0)
        self.combo_video_audio = QComboBox()
        self.combo_video_audio.addItems(["Keep Audio", "Mute / Strip Audio Stream", "Compress AAC (128 kbps)", "Voice Mono (64 kbps)"])
        sc_grid.addWidget(self.combo_video_audio, 3, 1)

        sc_grid.addWidget(QLabel("Output Container:"), 4, 0)
        self.combo_video_container = QComboBox()
        self.combo_video_container.addItems(["MP4 (.mp4)", "MKV (.mkv)", "WebM (.webm)"])
        sc_grid.addWidget(self.combo_video_container, 4, 1)

        v_lay.addWidget(scale_box)
        self.config_lay.addWidget(self.video_container)

        # === Image Controls Container ===
        self.image_container = QWidget()
        i_lay = QVBoxLayout(self.image_container)
        i_lay.setContentsMargins(0, 0, 0, 0)
        i_lay.setSpacing(12)

        # Image: Quality & Format
        img_qual_box = QGroupBox("IMAGE QUALITY & FORMAT")
        iq_grid = QGridLayout(img_qual_box)

        iq_grid.addWidget(QLabel("Compression Quality:"), 0, 0)
        q_row = QHBoxLayout()
        self.slider_quality = QSlider(Qt.Horizontal)
        self.slider_quality.setRange(1, 100)
        self.slider_quality.setValue(85)
        self.slider_quality.valueChanged.connect(lambda v: self.lbl_qual_val.setText(f"{v}%"))
        q_row.addWidget(self.slider_quality)

        self.lbl_qual_val = QLabel("85%")
        self.lbl_qual_val.setFixedWidth(40)
        self.lbl_qual_val.setStyleSheet("font-weight: 700; color: #38bdf8;")
        q_row.addWidget(self.lbl_qual_val)
        iq_grid.addLayout(q_row, 0, 1)

        iq_grid.addWidget(QLabel("Target Format:"), 1, 0)
        self.combo_image_format = QComboBox()
        self.combo_image_format.addItems(["Original / Auto", "JPEG (.jpg)", "PNG (.png)", "WebP (.webp)"])
        iq_grid.addWidget(self.combo_image_format, 1, 1)

        self.chk_strip_exif = QCheckBox("Strip EXIF & Location Metadata (Zero-Leakage)")
        self.chk_strip_exif.setChecked(True)
        self.chk_strip_exif.setStyleSheet("color: #3fb768; font-weight: 600;")
        iq_grid.addWidget(self.chk_strip_exif, 2, 0, 1, 2)

        i_lay.addWidget(img_qual_box)

        # Image: Dimensions & Transformations
        img_trans_box = QGroupBox("DIMENSIONS & COLOR TRANSFORMATIONS")
        it_grid = QGridLayout(img_trans_box)

        it_grid.addWidget(QLabel("Scale Percentage:"), 0, 0)
        self.spin_image_scale = QSpinBox()
        self.spin_image_scale.setRange(10, 200)
        self.spin_image_scale.setValue(100)
        self.spin_image_scale.setSuffix("%")
        it_grid.addWidget(self.spin_image_scale, 0, 1)

        it_grid.addWidget(QLabel("Rotation:"), 1, 0)
        self.combo_image_rotate = QComboBox()
        self.combo_image_rotate.addItems(["0° (No rotation)", "90° Clockwise", "180° Inverted", "270° Counter-Clockwise"])
        it_grid.addWidget(self.combo_image_rotate, 1, 1)

        it_grid.addWidget(QLabel("Color Filter:"), 2, 0)
        self.combo_image_filter = QComboBox()
        self.combo_image_filter.addItems(["None (Original)", "Grayscale", "Sepia", "Vintage", "Cool", "Warm"])
        it_grid.addWidget(self.combo_image_filter, 2, 1)

        i_lay.addWidget(img_trans_box)
        self.config_lay.addWidget(self.image_container)

        self.image_container.hide()

        scroll.setWidget(scroll_content)
        main_lay.addWidget(scroll, 1)

        # ── 3. Action Dock & Telemetry Card ─────────────────────────────── #
        dock_box = QGroupBox("EXECUTION & RESULTS")
        d_lay = QVBoxLayout(dock_box)

        self.btn_compress_action = QPushButton("COMPRESS & EXPORT")
        self.btn_compress_action.setEnabled(False)
        self.btn_compress_action.setStyleSheet("""
            QPushButton {
                background-color: #2563eb;
                color: #ffffff;
                font-size: 13px;
                font-weight: 800;
                letter-spacing: 1px;
                padding: 10px 20px;
                border-radius: 5px;
            }
            QPushButton:hover { background-color: #3b82f6; }
            QPushButton:disabled { background-color: #2b2b2b; color: #555555; }
        """)
        self.btn_compress_action.clicked.connect(self._start_compression)
        d_lay.addWidget(self.btn_compress_action)

        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.hide()
        d_lay.addWidget(self.progress_bar)

        self.lbl_status = QLabel("Ready. Select a media file to configure compression.")
        self.lbl_status.setStyleSheet("color: #888888; font-size: 11px;")
        d_lay.addWidget(self.lbl_status)

        # Result Details Frame
        self.result_frame = QFrame()
        self.result_frame.setStyleSheet("background-color: #141414; border: 1px solid #2e2e2e; border-radius: 5px; padding: 10px;")
        res_lay = QVBoxLayout(self.result_frame)
        self.lbl_result_summary = QLabel("")
        self.lbl_result_summary.setStyleSheet("color: #3fb768; font-weight: 700; font-size: 12px;")
        res_lay.addWidget(self.lbl_result_summary)

        res_btn_row = QHBoxLayout()
        self.btn_open_folder = QPushButton("Open Output Folder")
        self.btn_open_folder.clicked.connect(self._open_output_folder)
        res_btn_row.addWidget(self.btn_open_folder)

        self.btn_copy_path = QPushButton("Copy File Path")
        self.btn_copy_path.clicked.connect(self._copy_output_path)
        res_btn_row.addWidget(self.btn_copy_path)
        res_btn_row.addStretch()
        res_lay.addLayout(res_btn_row)

        self.result_frame.hide()
        d_lay.addWidget(self.result_frame)

        main_lay.addWidget(dock_box)

    # ── File Handling ─────────────────────────────────────────────────── #

    def _browse_file(self):
        filter_str = (
            "All Media Files (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts *.jpg *.jpeg *.png *.webp);;"
            "Videos (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts);;"
            "Images (*.jpg *.jpeg *.png *.webp);;"
            "All Files (*.*)"
        )
        path, _ = QFileDialog.getOpenFileName(self, "Select Media File to Compress", "", filter_str)
        if path:
            self.load_file(Path(path))

    def load_file(self, path: Path):
        if not path.exists():
            return
        self.src_path = path
        self.result_frame.hide()
        ext = path.suffix.lower()

        if ext in IMAGE_EXTS:
            self.media_type = "image"
            self.video_container.hide()
            self.image_container.show()
            sz_kb = path.stat().st_size / 1024
            self.lbl_file_info.setText(f"Loaded Image: {path.name}  ({sz_kb:.1f} KB)")
            self.btn_compress_action.setEnabled(True)
            self.lbl_status.setText("Image loaded. Adjust quality, scaling, or color filter, then click Compress.")
        else:
            self.media_type = "video"
            self.image_container.hide()
            self.video_container.show()
            try:
                info = analyze_video(path)
                self.total_duration = float(info.duration_seconds)
                self.orig_width = int(info.width)
                self.orig_height = int(info.height)
                self.spin_trim_start.setMaximum(self.total_duration)
                self.spin_trim_end.setMaximum(self.total_duration)
                self.spin_trim_end.setValue(self.total_duration)
                self.lbl_file_info.setText(
                    f"Loaded Video: {path.name}  ({info.duration_str}, {info.size_str}, {self.orig_width}x{self.orig_height})"
                )
            except Exception:
                self.total_duration = 0.0
                sz_mb = path.stat().st_size / (1024 * 1024)
                self.lbl_file_info.setText(f"Loaded Video: {path.name}  ({sz_mb:.1f} MB)")

            self.btn_compress_action.setEnabled(True)
            self.lbl_status.setText("Video loaded. Select target platform preset or timeline trim, then click Compress.")

    # ── Helpers ──────────────────────────────────────────────────────── #

    def _on_video_preset_changed(self, idx: int):
        desc_map = [
            "Balanced Constant Rate Factor (CRF 28) compression.",
            "WhatsApp ceiling: 16 MB maximum output with auto-bitrate.",
            "Discord ceiling: 25 MB standard upload allowance.",
            "Discord ceiling: 50 MB Nitro upload allowance.",
            "Email attachment ceiling: 8 MB guaranteed delivery.",
            "Web streaming optimized: 10 MB ceiling.",
        ]
        if idx < len(desc_map):
            self.lbl_preset_detail.setText(desc_map[idx])

    def _update_trim_duration(self):
        s = self.spin_trim_start.value()
        e = self.spin_trim_end.value()
        dur = max(0.0, e - s) if e > s else 0.0
        self.lbl_trim_dur.setText(f"Duration: {dur:.1f}s")

    def _apply_quick_trim(self, s: float, e: float):
        self.spin_trim_start.setValue(s)
        if self.total_duration > 0:
            self.spin_trim_end.setValue(min(e, self.total_duration))
        else:
            self.spin_trim_end.setValue(e)

    def _trim_middle_half(self):
        if self.total_duration > 0:
            s = self.total_duration * 0.25
            e = self.total_duration * 0.75
            self.spin_trim_start.setValue(s)
            self.spin_trim_end.setValue(e)

    def _trim_full(self):
        self.spin_trim_start.setValue(0.0)
        self.spin_trim_end.setValue(self.total_duration)

    # ── Compression Execution ─────────────────────────────────────────── #

    def _start_compression(self):
        if not self.src_path or not self.src_path.exists():
            return

        if self.media_type == "image":
            # Image output
            fmt_choice = self.combo_image_format.currentText()
            if "JPEG" in fmt_choice:
                ext = ".jpg"
                fmt = "JPEG"
            elif "PNG" in fmt_choice:
                ext = ".png"
                fmt = "PNG"
            elif "WebP" in fmt_choice:
                ext = ".webp"
                fmt = "WEBP"
            else:
                ext = self.src_path.suffix.lower()
                fmt = None

            default_name = f"{self.src_path.stem}_compressed{ext}"
            default_out = self.src_path.with_name(default_name)
            out_str, _ = QFileDialog.getSaveFileName(self, "Save Compressed Image", str(default_out), "Image Files (*.*)")
            if not out_str:
                return

            dst = Path(out_str)
            if dst.resolve() == self.src_path.resolve():
                QMessageBox.warning(self, "Invalid Path", "Output cannot overwrite source file.")
                return

            rot_map = [0.0, 90.0, 180.0, 270.0]
            rot = rot_map[self.combo_image_rotate.currentIndex()]
            filt = self.combo_image_filter.currentText().split()[0].lower()
            scale_factor = self.spin_image_scale.value() / 100.0

            kwargs = {
                "input_path": self.src_path,
                "output_path": dst,
                "quality": self.slider_quality.value(),
                "format": fmt,
                "scale": scale_factor,
                "rotate_deg": rot,
                "filter_name": filt,
                "strip_exif": self.chk_strip_exif.isChecked(),
            }
        else:
            # Video output
            cont_text = self.combo_video_container.currentText()
            if "MKV" in cont_text:
                ext = ".mkv"
            elif "WebM" in cont_text:
                ext = ".webm"
            else:
                ext = ".mp4"

            default_name = f"{self.src_path.stem}_compressed{ext}"
            default_out = self.src_path.with_name(default_name)
            out_str, _ = QFileDialog.getSaveFileName(self, "Save Compressed Video", str(default_out), "Video Files (*.*)")
            if not out_str:
                return

            dst = Path(out_str)
            if dst.resolve() == self.src_path.resolve():
                QMessageBox.warning(self, "Invalid Path", "Output cannot overwrite source file.")
                return

            # Target MB
            preset_text = self.combo_video_presets.currentText()
            target_mb = None
            crf = 28
            if "16 MB" in preset_text:
                target_mb = 16.0
            elif "25 MB" in preset_text:
                target_mb = 25.0
            elif "50 MB" in preset_text:
                target_mb = 50.0
            elif "8 MB" in preset_text:
                target_mb = 8.0
            elif "10 MB" in preset_text:
                target_mb = 10.0

            # Trim
            trim_s = self.spin_trim_start.value() if self.spin_trim_start.value() > 0 else None
            trim_e = self.spin_trim_end.value()
            if self.total_duration > 0 and abs(trim_e - self.total_duration) < 0.1:
                trim_e = None
            elif trim_e <= (trim_s or 0.0):
                trim_e = None

            # Res
            res_text = self.combo_video_res.currentText()
            res_val = None
            if "1080p" in res_text:
                res_val = "1080p"
            elif "720p" in res_text:
                res_val = "720p"
            elif "480p" in res_text:
                res_val = "480p"
            elif "360p" in res_text:
                res_val = "360p"

            # Aspect
            aspect_text = self.combo_video_aspect.currentText()
            aspect_val = None
            if "9:16" in aspect_text:
                aspect_val = "9:16"
            elif "1:1" in aspect_text:
                aspect_val = "1:1"
            elif "16:9" in aspect_text:
                aspect_val = "16:9"
            elif "4:3" in aspect_text:
                aspect_val = "4:3"

            # Speed
            speed_val = float(self.combo_video_speed.currentText().split("x")[0])

            # Audio
            audio_text = self.combo_video_audio.currentText()
            audio_val = "keep"
            if "Mute" in audio_text:
                audio_val = "mute"
            elif "128" in audio_text:
                audio_val = "compress"
            elif "Voice" in audio_text:
                audio_val = "voice"

            kwargs = {
                "input_path": self.src_path,
                "output_path": dst,
                "target_mb": target_mb,
                "crf": crf,
                "trim_start": trim_s,
                "trim_end": trim_e,
                "resolution": res_val,
                "aspect": aspect_val,
                "speed": speed_val,
                "audio": audio_val,
            }

        self.dst_path = dst
        self.btn_compress_action.setEnabled(False)
        self.progress_bar.setRange(0, 0)
        self.progress_bar.show()
        self.lbl_status.setText("Compressing media file...")

        self.worker = CompressWorker(self.media_type, kwargs)
        self.worker.progress.connect(self._on_worker_progress)
        self.worker.finished.connect(self._on_worker_finished)
        self.worker.failed.connect(self._on_worker_failed)
        self.worker.start()

    def _on_worker_progress(self, pct: float, msg: str):
        self.lbl_status.setText(msg)

    def _on_worker_finished(self, res: dict):
        self.progress_bar.hide()
        self.btn_compress_action.setEnabled(True)
        orig_sz = res.get("input_size", 0)
        out_sz = res.get("output_size", 0)
        savings = res.get("savings_pct", 0.0)

        orig_str = f"{orig_sz / (1024*1024):.2f} MB" if orig_sz > 1024*1024 else f"{orig_sz / 1024:.1f} KB"
        out_str = f"{out_sz / (1024*1024):.2f} MB" if out_sz > 1024*1024 else f"{out_sz / 1024:.1f} KB"

        self.lbl_status.setText("Compression finished successfully ✓")
        self.lbl_result_summary.setText(
            f"✓ Complete: {orig_str} → {out_str} ({savings:+.1f}% space saved)\nSaved to: {self.dst_path.name}"
        )
        self.result_frame.show()

    def _on_worker_failed(self, err: str):
        self.progress_bar.hide()
        self.btn_compress_action.setEnabled(True)
        self.lbl_status.setText("Compression encountered an error.")
        QMessageBox.critical(self, "Compression Error", f"Failed to compress media:\n{err}")

    def _open_output_folder(self):
        if self.dst_path and self.dst_path.parent.exists():
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.dst_path.parent)))

    def _copy_output_path(self):
        if self.dst_path:
            QApplication.clipboard().setText(str(self.dst_path.resolve()))
            self.lbl_status.setText(f"Copied output path to clipboard: {self.dst_path.name}")
