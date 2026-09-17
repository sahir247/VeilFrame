"""
VeilFrame GUI — Media Compressor & Studio Panel.
Provides desktop GUI interface for unified image and video compression,
visual range trimming, target presets, downscaling, audio remixing,
and metadata privacy controls.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

from PySide6.QtCore import Qt, QThread, Signal, QUrl
from PySide6.QtGui import QDesktopServices, QDragEnterEvent, QDropEvent
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
    QLineEdit,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QRadioButton,
    QScrollArea,
    QSlider,
    QSpinBox,
    QDoubleSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..core.analyzer import analyze_video
from ..core.media_compressor import compress_image, compress_video

VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".flv", ".ts", ".wmv", ".gif"}
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
                self.progress.emit(0.2, "Processing image transformation & compression...")
                res = compress_image(**self.kwargs)
                self.progress.emit(1.0, "Image compression complete.")
                self.finished.emit(res)
            else:
                self.progress.emit(0.2, "Executing video compression & encoding pipeline...")
                res = compress_video(**self.kwargs)
                self.progress.emit(1.0, "Video compression complete.")
                self.finished.emit(res)
        except Exception as e:
            self.failed.emit(str(e))


class MediaCompressorPanel(QWidget):
    """Integrated Desktop Studio for Media Compression & Editing."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setAcceptDrops(True)
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
        main_lay.setContentsMargins(8, 8, 8, 8)
        main_lay.setSpacing(12)

        # ── 1. Target Selector & Drop-Zone Card ─────────────────────────── #
        self.drop_card = QFrame()
        self.drop_card.setObjectName("dropCard")
        self.drop_card.setStyleSheet("""
            QFrame#dropCard {
                background-color: #111827;
                border: 2px dashed #374151;
                border-radius: 8px;
                padding: 14px;
            }
            QFrame#dropCard:hover {
                border-color: #3b82f6;
            }
        """)
        d_lay = QVBoxLayout(self.drop_card)
        d_lay.setSpacing(8)

        top_row = QHBoxLayout()
        self.btn_select_file = QPushButton("Select Video or Image")
        self.btn_select_file.setStyleSheet("""
            QPushButton {
                background-color: #2563eb;
                color: #ffffff;
                font-weight: 700;
                padding: 8px 20px;
                border-radius: 6px;
                font-size: 12px;
            }
            QPushButton:hover { background-color: #3b82f6; }
        """)
        self.btn_select_file.clicked.connect(self._browse_file)
        top_row.addWidget(self.btn_select_file)

        self.lbl_drop_cue = QLabel("or drag and drop files here")
        self.lbl_drop_cue.setStyleSheet("color: #9ca3af; font-size: 11px; font-style: italic;")
        top_row.addWidget(self.lbl_drop_cue)
        top_row.addStretch()

        self.lbl_media_badge = QLabel("NO MEDIA")
        self.lbl_media_badge.setStyleSheet("""
            background-color: #374151;
            color: #d1d5db;
            font-size: 10px;
            font-weight: 700;
            padding: 3px 8px;
            border-radius: 4px;
        """)
        top_row.addWidget(self.lbl_media_badge)
        d_lay.addLayout(top_row)

        self.lbl_file_info = QLabel("Select or drop a video/image to begin compression.")
        self.lbl_file_info.setStyleSheet("color: #e5e7eb; font-size: 12px; font-weight: 500;")
        d_lay.addWidget(self.lbl_file_info)

        main_lay.addWidget(self.drop_card)

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

        # Video: Presets & Target Sizing
        preset_box = QGroupBox("TARGET PLATFORM & SIZE CEILING")
        p_grid = QGridLayout(preset_box)
        p_grid.addWidget(QLabel("Target Preset:"), 0, 0)

        self.combo_video_presets = QComboBox()
        self.combo_video_presets.addItems([
            "Auto Quality (Balanced CRF 28)",
            "WhatsApp (16 MB Target)",
            "Discord Standard (25 MB Target)",
            "Discord Nitro (50 MB Target)",
            "Email Attachment (8 MB Target)",
            "Web Streaming (10 MB Target)",
            "Custom Target Size (MB)",
        ])
        self.combo_video_presets.currentIndexChanged.connect(self._on_video_preset_changed)
        p_grid.addWidget(self.combo_video_presets, 0, 1)

        custom_mb_row = QHBoxLayout()
        self.lbl_custom_mb = QLabel("Custom Target Size:")
        self.lbl_custom_mb.setEnabled(False)
        custom_mb_row.addWidget(self.lbl_custom_mb)

        self.spin_target_mb = QDoubleSpinBox()
        self.spin_target_mb.setRange(0.5, 2048.0)
        self.spin_target_mb.setValue(20.0)
        self.spin_target_mb.setSuffix(" MB")
        self.spin_target_mb.setEnabled(False)
        custom_mb_row.addWidget(self.spin_target_mb)
        p_grid.addLayout(custom_mb_row, 1, 0, 1, 2)

        self.lbl_preset_detail = QLabel("Dynamically allocates multi-pass video bitrate to strictly fit within target ceiling.")
        self.lbl_preset_detail.setStyleSheet("color: #94a3b8; font-size: 11px;")
        p_grid.addWidget(self.lbl_preset_detail, 2, 0, 1, 2)
        v_lay.addWidget(preset_box)

        # Video: Timeline Range Trimmer
        trim_box = QGroupBox("TIMELINE RANGE TRIMMER")
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

        # Video: Encoding & Codecs
        vcodec_box = QGroupBox("VIDEO ENCODING & CODECS")
        vc_grid = QGridLayout(vcodec_box)

        vc_grid.addWidget(QLabel("Video Codec:"), 0, 0)
        self.combo_video_codec = QComboBox()
        self.combo_video_codec.addItems([
            "H.264 (libx264 - Universal Compatibility)",
            "H.265 / HEVC (libx265 - Superior Efficiency)",
            "VP9 (libvpx-vp9 - Web Friendly)",
            "AV1 (libsvtav1 - Next-Gen Compression)",
            "Copy Stream (No Transcoding)",
        ])
        self.combo_video_codec.currentIndexChanged.connect(self._on_video_codec_changed)
        vc_grid.addWidget(self.combo_video_codec, 0, 1)

        vc_grid.addWidget(QLabel("Output Container:"), 1, 0)
        self.combo_video_container = QComboBox()
        self.combo_video_container.addItems([
            "MP4 (.mp4 - Standard)",
            "MOV (.mov - Apple QuickTime)",
            "MKV (.mkv - Matroska)",
            "WebM (.webm - Web Stream)",
            "AVI (.avi - Legacy)",
            "GIF (.gif - Animated)",
        ])
        self.combo_video_container.currentIndexChanged.connect(self._on_video_container_changed)
        vc_grid.addWidget(self.combo_video_container, 1, 1)

        vc_grid.addWidget(QLabel("Frame Rate (FPS):"), 2, 0)
        self.combo_video_fps = QComboBox()
        self.combo_video_fps.addItems(["Keep Original", "60 fps", "30 fps", "24 fps", "15 fps"])
        vc_grid.addWidget(self.combo_video_fps, 2, 1)

        v_lay.addWidget(vcodec_box)

        # Video: Resolution, Transforms & Framing
        scale_box = QGroupBox("RESOLUTION & TRANSFORMS")
        sc_grid = QGridLayout(scale_box)

        sc_grid.addWidget(QLabel("Downscale Resolution:"), 0, 0)
        self.combo_video_res = QComboBox()
        self.combo_video_res.addItems(["Original", "1080p (Full HD)", "720p (HD)", "480p (SD)", "360p (Small)"])
        sc_grid.addWidget(self.combo_video_res, 0, 1)

        sc_grid.addWidget(QLabel("Aspect Framing:"), 1, 0)
        self.combo_video_aspect = QComboBox()
        self.combo_video_aspect.addItems(["Original", "9:16 (Reel / Shorts)", "1:1 (Square)", "16:9 (Landscape)", "4:3 (Classic)"])
        sc_grid.addWidget(self.combo_video_aspect, 1, 1)

        sc_grid.addWidget(QLabel("Rotation:"), 2, 0)
        self.combo_video_rotate = QComboBox()
        self.combo_video_rotate.addItems(["0° (No rotation)", "90° Clockwise", "180° Inverted", "270° Counter-Clockwise"])
        sc_grid.addWidget(self.combo_video_rotate, 2, 1)

        flip_row = QHBoxLayout()
        self.chk_video_flip_h = QCheckBox("Flip Horizontal")
        self.chk_video_flip_v = QCheckBox("Flip Vertical")
        flip_row.addWidget(self.chk_video_flip_h)
        flip_row.addWidget(self.chk_video_flip_v)
        flip_row.addStretch()
        sc_grid.addLayout(flip_row, 3, 0, 1, 2)

        sc_grid.addWidget(QLabel("Playback Speed:"), 4, 0)
        self.combo_video_speed = QComboBox()
        self.combo_video_speed.addItems(["0.25x", "0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x", "4.0x"])
        self.combo_video_speed.setCurrentIndex(3)
        sc_grid.addWidget(self.combo_video_speed, 4, 1)

        v_lay.addWidget(scale_box)

        # Video: Audio Stream & Channels
        audio_box = QGroupBox("AUDIO STREAM & VOLUME CONTROLS")
        a_grid = QGridLayout(audio_box)

        a_grid.addWidget(QLabel("Audio Stream Action:"), 0, 0)
        self.combo_video_audio = QComboBox()
        self.combo_video_audio.addItems([
            "Keep / Re-encode Audio",
            "Mute / Strip Audio Stream",
            "Compress AAC (128 kbps)",
            "Voice Mono (64 kbps)",
            "High Fidelity (256 kbps)",
        ])
        a_grid.addWidget(self.combo_video_audio, 0, 1)

        a_grid.addWidget(QLabel("Audio Codec:"), 1, 0)
        self.combo_audio_codec = QComboBox()
        self.combo_audio_codec.addItems([
            "AAC (Default)",
            "MP3 (libmp3lame)",
            "Opus (libopus)",
            "FLAC (Lossless)",
            "Copy Stream",
        ])
        a_grid.addWidget(self.combo_audio_codec, 1, 1)

        a_grid.addWidget(QLabel("Audio Channels:"), 2, 0)
        self.combo_audio_channels = QComboBox()
        self.combo_audio_channels.addItems(["Keep Original", "Stereo (2 Channels)", "Mono (1 Channel)"])
        a_grid.addWidget(self.combo_audio_channels, 2, 1)

        a_grid.addWidget(QLabel("Audio Volume:"), 3, 0)
        vol_row = QHBoxLayout()
        self.slider_audio_vol = QSlider(Qt.Horizontal)
        self.slider_audio_vol.setRange(0, 200)
        self.slider_audio_vol.setValue(100)
        self.slider_audio_vol.valueChanged.connect(lambda v: self.lbl_vol_val.setText(f"{v}%"))
        vol_row.addWidget(self.slider_audio_vol)

        self.lbl_vol_val = QLabel("100%")
        self.lbl_vol_val.setFixedWidth(45)
        self.lbl_vol_val.setStyleSheet("font-weight: 700; color: #38bdf8;")
        vol_row.addWidget(self.lbl_vol_val)
        a_grid.addLayout(vol_row, 3, 1)

        self.video_audio_box = audio_box
        v_lay.addWidget(audio_box)
        self.config_lay.addWidget(self.video_container)

        # === Image Controls Container ===
        self.image_container = QWidget()
        i_lay = QVBoxLayout(self.image_container)
        i_lay.setContentsMargins(0, 0, 0, 0)
        i_lay.setSpacing(12)

        # Image: Quality & Target Size Solver
        img_qual_box = QGroupBox("IMAGE COMPRESSION GOAL & SIZING")
        iq_grid = QGridLayout(img_qual_box)

        mode_row = QHBoxLayout()
        self.rb_goal_percent = QRadioButton("Percentage Quality")
        self.rb_goal_percent.setChecked(True)
        self.rb_goal_target = QRadioButton("Exact Target File Size (KB)")
        self.rb_goal_percent.toggled.connect(self._on_image_goal_changed)
        mode_row.addWidget(self.rb_goal_percent)
        mode_row.addWidget(self.rb_goal_target)
        mode_row.addStretch()
        iq_grid.addLayout(mode_row, 0, 0, 1, 2)

        # Percentage slider row
        self.lbl_slider_qual = QLabel("Compression Quality:")
        iq_grid.addWidget(self.lbl_slider_qual, 1, 0)
        self.q_slider_row = QHBoxLayout()
        self.slider_quality = QSlider(Qt.Horizontal)
        self.slider_quality.setRange(1, 100)
        self.slider_quality.setValue(85)
        self.slider_quality.valueChanged.connect(lambda v: self.lbl_qual_val.setText(f"{v}%"))
        self.q_slider_row.addWidget(self.slider_quality)

        self.lbl_qual_val = QLabel("85%")
        self.lbl_qual_val.setFixedWidth(40)
        self.lbl_qual_val.setStyleSheet("font-weight: 700; color: #38bdf8;")
        self.q_slider_row.addWidget(self.lbl_qual_val)
        iq_grid.addLayout(self.q_slider_row, 1, 1)

        # Target KB row
        self.lbl_target_kb = QLabel("Target Size (KB):")
        self.lbl_target_kb.setEnabled(False)
        iq_grid.addWidget(self.lbl_target_kb, 2, 0)

        self.spin_target_kb = QSpinBox()
        self.spin_target_kb.setRange(10, 50000)
        self.spin_target_kb.setValue(250)
        self.spin_target_kb.setSuffix(" KB")
        self.spin_target_kb.setEnabled(False)
        iq_grid.addWidget(self.spin_target_kb, 2, 1)

        iq_grid.addWidget(QLabel("Target Format:"), 3, 0)
        self.combo_image_format = QComboBox()
        self.combo_image_format.addItems(["Original / Auto", "JPEG (.jpg)", "PNG (.png)", "WebP (.webp)"])
        iq_grid.addWidget(self.combo_image_format, 3, 1)

        self.chk_strip_exif = QCheckBox("Strip EXIF & GPS Location Metadata (Zero-Leakage)")
        self.chk_strip_exif.setChecked(True)
        self.chk_strip_exif.setStyleSheet("color: #10b981; font-weight: 600;")
        iq_grid.addWidget(self.chk_strip_exif, 4, 0, 1, 2)

        i_lay.addWidget(img_qual_box)

        # Image: Transforms & Background Fill
        img_trans_box = QGroupBox("DIMENSIONS, FLIP & BACKGROUND FILL")
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

        img_flip_row = QHBoxLayout()
        self.chk_img_flip_h = QCheckBox("Flip Horizontal")
        self.chk_img_flip_v = QCheckBox("Flip Vertical")
        img_flip_row.addWidget(self.chk_img_flip_h)
        img_flip_row.addWidget(self.chk_img_flip_v)
        img_flip_row.addStretch()
        it_grid.addLayout(img_flip_row, 2, 0, 1, 2)

        it_grid.addWidget(QLabel("Background Fill (for Alpha):"), 3, 0)
        self.combo_image_bg = QComboBox()
        self.combo_image_bg.addItems(["Keep Transparent", "Solid White (#FFFFFF)", "Solid Black (#000000)"])
        it_grid.addWidget(self.combo_image_bg, 3, 1)

        it_grid.addWidget(QLabel("Color Filter:"), 4, 0)
        self.combo_image_filter = QComboBox()
        self.combo_image_filter.addItems([
            "None (Original)",
            "Grayscale",
            "Sepia",
            "Vintage",
            "Cool",
            "Warm",
            "Negative (Invert)",
            "Contrast Enhanced",
        ])
        it_grid.addWidget(self.combo_image_filter, 4, 1)

        i_lay.addWidget(img_trans_box)

        # Image: Text Watermark Overlay
        img_wm_box = QGroupBox("TEXT WATERMARK OVERLAY")
        iwm_lay = QVBoxLayout(img_wm_box)

        self.chk_enable_watermark = QCheckBox("Enable Text Watermark")
        self.chk_enable_watermark.toggled.connect(self._on_watermark_toggled)
        iwm_lay.addWidget(self.chk_enable_watermark)

        wm_grid = QGridLayout()
        wm_grid.addWidget(QLabel("Watermark Text:"), 0, 0)
        self.edit_wm_text = QLineEdit("CONFIDENTIAL")
        self.edit_wm_text.setEnabled(False)
        wm_grid.addWidget(self.edit_wm_text, 0, 1)

        wm_grid.addWidget(QLabel("Overlay Position:"), 1, 0)
        self.combo_wm_pos = QComboBox()
        self.combo_wm_pos.addItems([
            "Bottom Right",
            "Bottom Left",
            "Top Right",
            "Top Left",
            "Center",
        ])
        self.combo_wm_pos.setEnabled(False)
        wm_grid.addWidget(self.combo_wm_pos, 1, 1)

        wm_grid.addWidget(QLabel("Font Size:"), 2, 0)
        self.spin_wm_size = QSpinBox()
        self.spin_wm_size.setRange(10, 200)
        self.spin_wm_size.setValue(32)
        self.spin_wm_size.setSuffix(" pt")
        self.spin_wm_size.setEnabled(False)
        wm_grid.addWidget(self.spin_wm_size, 2, 1)

        iwm_lay.addLayout(wm_grid)
        i_lay.addWidget(img_wm_box)

        self.config_lay.addWidget(self.image_container)
        self.image_container.hide()

        scroll.setWidget(scroll_content)
        main_lay.addWidget(scroll, 1)

        # ── 3. Action Dock & Comparative Telemetry Card ─────────────────── #
        dock_box = QGroupBox("EXECUTION & COMPARATIVE INSPECTION")
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
                border-radius: 6px;
            }
            QPushButton:hover { background-color: #3b82f6; }
            QPushButton:disabled { background-color: #1f2937; color: #4b5563; }
        """)
        self.btn_compress_action.clicked.connect(self._start_compression)
        d_lay.addWidget(self.btn_compress_action)

        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.hide()
        d_lay.addWidget(self.progress_bar)

        self.lbl_status = QLabel("Ready. Select or drag a media file to configure compression.")
        self.lbl_status.setStyleSheet("color: #9ca3af; font-size: 11px;")
        d_lay.addWidget(self.lbl_status)

        # Result Details Frame with Comparative Badges
        self.result_frame = QFrame()
        self.result_frame.setStyleSheet("""
            QFrame {
                background-color: #0f172a;
                border: 1px solid #1e293b;
                border-radius: 6px;
                padding: 12px;
            }
        """)
        res_lay = QVBoxLayout(self.result_frame)
        res_lay.setSpacing(8)

        self.lbl_result_summary = QLabel("")
        self.lbl_result_summary.setStyleSheet("color: #10b981; font-weight: 700; font-size: 12px;")
        res_lay.addWidget(self.lbl_result_summary)

        res_btn_row = QHBoxLayout()
        self.btn_open_folder = QPushButton("Reveal in File Explorer")
        self.btn_open_folder.clicked.connect(self._open_output_folder)
        res_btn_row.addWidget(self.btn_open_folder)

        self.btn_view_media = QPushButton("Play / View Media")
        self.btn_view_media.clicked.connect(self._view_output_media)
        res_btn_row.addWidget(self.btn_view_media)

        self.btn_copy_path = QPushButton("Copy File Path")
        self.btn_copy_path.clicked.connect(self._copy_output_path)
        res_btn_row.addWidget(self.btn_copy_path)
        res_btn_row.addStretch()
        res_lay.addLayout(res_btn_row)

        self.result_frame.hide()
        d_lay.addWidget(self.result_frame)

        main_lay.addWidget(dock_box)

    # ── Drag & Drop Events ────────────────────────────────────────────── #

    def dragEnterEvent(self, event: QDragEnterEvent):
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
            self.drop_card.setStyleSheet("""
                QFrame#dropCard {
                    background-color: #1e293b;
                    border: 2px dashed #38bdf8;
                    border-radius: 8px;
                    padding: 14px;
                }
            """)

    def dragLeaveEvent(self, event):
        self.drop_card.setStyleSheet("""
            QFrame#dropCard {
                background-color: #111827;
                border: 2px dashed #374151;
                border-radius: 8px;
                padding: 14px;
            }
        """)

    def dropEvent(self, event: QDropEvent):
        self.dragLeaveEvent(event)
        urls = event.mimeData().urls()
        if urls:
            path = Path(urls[0].toLocalFile())
            if path.exists():
                self.load_file(path)

    # ── File Handling ─────────────────────────────────────────────────── #

    def _browse_file(self):
        filter_str = (
            "All Media Files (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts *.gif *.jpg *.jpeg *.png *.webp);;"
            "Videos (*.mp4 *.mov *.mkv *.webm *.avi *.m4v *.ts *.gif);;"
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
            self.lbl_media_badge.setText("IMAGE")
            self.lbl_media_badge.setStyleSheet("background-color: #047857; color: #ecfdf5; font-size: 10px; font-weight: 700; padding: 3px 8px; border-radius: 4px;")
            self.lbl_file_info.setText(f"Loaded Image: {path.name}  ({sz_kb:.1f} KB)")
            self.btn_compress_action.setEnabled(True)
            self.lbl_status.setText("Image loaded. Adjust quality, target size, scaling, or overlay, then click Compress.")
        else:
            self.media_type = "video"
            self.image_container.hide()
            self.video_container.show()
            self.lbl_media_badge.setText("VIDEO")
            self.lbl_media_badge.setStyleSheet("background-color: #1d4ed8; color: #eff6ff; font-size: 10px; font-weight: 700; padding: 3px 8px; border-radius: 4px;")
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
            self.lbl_status.setText("Video loaded. Select target preset, codecs, transforms, or timeline trim, then click Compress.")

    # ── Helpers & State Sync ──────────────────────────────────────────── #

    def _on_video_preset_changed(self, idx: int):
        desc_map = [
            "Balanced Constant Rate Factor (CRF 28) compression.",
            "WhatsApp ceiling: 16 MB maximum output with auto-bitrate.",
            "Discord ceiling: 25 MB standard upload allowance.",
            "Discord ceiling: 50 MB Nitro upload allowance.",
            "Email attachment ceiling: 8 MB guaranteed delivery.",
            "Web streaming optimized: 10 MB ceiling.",
            "Custom target ceiling: exact MB calculation based on duration.",
        ]
        if idx < len(desc_map):
            self.lbl_preset_detail.setText(desc_map[idx])

        is_custom = (idx == 6)
        self.lbl_custom_mb.setEnabled(is_custom)
        self.spin_target_mb.setEnabled(is_custom)

    def _on_video_container_changed(self, idx: int):
        is_gif = "GIF" in self.combo_video_container.currentText()
        if hasattr(self, "video_audio_box"):
            self.video_audio_box.setEnabled(not is_gif)
        self.combo_video_codec.setEnabled(not is_gif)
        if is_gif:
            self.lbl_status.setText("GIF Animation Mode: High-fidelity palettegen active; audio stream stripped.")
        else:
            self.lbl_status.setText("Ready to compress video.")

    def _on_video_codec_changed(self, idx: int):
        if "Copy" in self.combo_video_codec.currentText():
            self.lbl_status.setText("Stream Copy selected: Spatial/temporal transforms will auto-promote to H.264 re-encode.")

    def _on_image_goal_changed(self, checked: bool):
        is_percent = self.rb_goal_percent.isChecked()
        self.lbl_slider_qual.setEnabled(is_percent)
        self.slider_quality.setEnabled(is_percent)
        self.lbl_qual_val.setEnabled(is_percent)

        self.lbl_target_kb.setEnabled(not is_percent)
        self.spin_target_kb.setEnabled(not is_percent)

    def _on_watermark_toggled(self, checked: bool):
        self.edit_wm_text.setEnabled(checked)
        self.combo_wm_pos.setEnabled(checked)
        self.spin_wm_size.setEnabled(checked)

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
            # Image output configuration
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
            filt_raw = self.combo_image_filter.currentText()
            filt = "none"
            if "Grayscale" in filt_raw:
                filt = "grayscale"
            elif "Sepia" in filt_raw:
                filt = "sepia"
            elif "Vintage" in filt_raw:
                filt = "vintage"
            elif "Cool" in filt_raw:
                filt = "cool"
            elif "Warm" in filt_raw:
                filt = "warm"
            elif "Negative" in filt_raw:
                filt = "negative"
            elif "Contrast" in filt_raw:
                filt = "contrast"

            scale_factor = self.spin_image_scale.value() / 100.0

            # Background fill
            bg_choice = self.combo_image_bg.currentText()
            bg_color = None
            if "White" in bg_choice:
                bg_color = "#FFFFFF"
            elif "Black" in bg_choice:
                bg_color = "#000000"

            # Watermark
            wm_dict = None
            if self.chk_enable_watermark.isChecked():
                pos_map = {
                    0: "bottom-right",
                    1: "bottom-left",
                    2: "top-right",
                    3: "top-left",
                    4: "center",
                }
                wm_dict = {
                    "text": self.edit_wm_text.text().strip() or "CONFIDENTIAL",
                    "position": pos_map.get(self.combo_wm_pos.currentIndex(), "bottom-right"),
                    "font_size": self.spin_wm_size.value(),
                    "color": "#FFFFFF",
                }

            goal_mode = "target_size" if self.rb_goal_target.isChecked() else "percentage"

            kwargs = {
                "input_path": self.src_path,
                "output_path": dst,
                "quality": self.slider_quality.value(),
                "format": fmt,
                "scale": scale_factor,
                "rotate_deg": rot,
                "filter_name": filt,
                "strip_exif": self.chk_strip_exif.isChecked(),
                "goal": goal_mode,
                "target_size_kb": self.spin_target_kb.value() if goal_mode == "target_size" else None,
                "flip_h": self.chk_img_flip_h.isChecked(),
                "flip_v": self.chk_img_flip_v.isChecked(),
                "text_watermark": wm_dict,
                "bg_color": bg_color,
            }
        else:
            # Video output configuration
            cont_text = self.combo_video_container.currentText()
            if "MOV" in cont_text:
                ext = ".mov"
                cont_fmt = "mov"
            elif "MKV" in cont_text:
                ext = ".mkv"
                cont_fmt = "mkv"
            elif "WebM" in cont_text:
                ext = ".webm"
                cont_fmt = "webm"
            elif "AVI" in cont_text:
                ext = ".avi"
                cont_fmt = "avi"
            elif "GIF" in cont_text:
                ext = ".gif"
                cont_fmt = "gif"
            else:
                ext = ".mp4"
                cont_fmt = "mp4"

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
            preset_idx = self.combo_video_presets.currentIndex()
            target_mb = None
            crf = 28
            if preset_idx == 1:
                target_mb = 16.0
            elif preset_idx == 2:
                target_mb = 25.0
            elif preset_idx == 3:
                target_mb = 50.0
            elif preset_idx == 4:
                target_mb = 8.0
            elif preset_idx == 5:
                target_mb = 10.0
            elif preset_idx == 6:
                target_mb = self.spin_target_mb.value()

            # Video Codec
            codec_text = self.combo_video_codec.currentText()
            codec_val = "libx264"
            if "265" in codec_text or "HEVC" in codec_text:
                codec_val = "libx265"
            elif "VP9" in codec_text:
                codec_val = "libvpx-vp9"
            elif "AV1" in codec_text:
                codec_val = "libsvtav1"
            elif "Copy" in codec_text:
                codec_val = "copy"

            # Frame rate (FPS)
            fps_text = self.combo_video_fps.currentText()
            fps_val = None
            if "fps" in fps_text:
                fps_val = int(fps_text.replace("fps", "").strip())

            # Timeline Trim
            trim_s = self.spin_trim_start.value() if self.spin_trim_start.value() > 0 else None
            trim_e = self.spin_trim_end.value()
            if self.total_duration > 0 and abs(trim_e - self.total_duration) < 0.1:
                trim_e = None
            elif trim_e <= (trim_s or 0.0):
                trim_e = None

            # Resolution downscaling
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

            # Aspect ratio framing
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

            # Rotation
            rot_map = [0, 90, 180, 270]
            rot_val = rot_map[self.combo_video_rotate.currentIndex()]

            # Speed
            speed_val = float(self.combo_video_speed.currentText().split("x")[0])

            # Audio
            audio_text = self.combo_video_audio.currentText()
            audio_action_val = "keep"
            if "Mute" in audio_text:
                audio_action_val = "mute"
            elif "128" in audio_text:
                audio_action_val = "compress"
            elif "Voice" in audio_text:
                audio_action_val = "aac_64k"

            # Audio Codec
            ac_text = self.combo_audio_codec.currentText()
            ac_val = "aac"
            if "MP3" in ac_text:
                ac_val = "mp3"
            elif "Opus" in ac_text:
                ac_val = "opus"
            elif "FLAC" in ac_text:
                ac_val = "flac"
            elif "Copy" in ac_text:
                ac_val = "copy"

            # Audio Channels
            chan_text = self.combo_audio_channels.currentText()
            chan_val = "keep"
            if "Mono" in chan_text:
                chan_val = "mono"
            elif "Stereo" in chan_text:
                chan_val = "stereo"

            # Audio Volume
            vol_factor = self.slider_audio_vol.value() / 100.0

            if cont_fmt == "gif":
                audio_action_val = "mute"
                codec_val = "gif"

            has_transforms = bool(
                res_val
                or aspect_val
                or (rot_val != 0)
                or self.chk_video_flip_h.isChecked()
                or self.chk_video_flip_v.isChecked()
                or (speed_val != 1.0)
                or (fps_val is not None)
            )
            if codec_val == "copy" and has_transforms:
                self.lbl_status.setText("Notice: Stream copy cannot apply filters; re-encoding with H.264.")

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
                "audio": audio_action_val,
                "container_format": cont_fmt,
                "codec": codec_val,
                "flip_h": self.chk_video_flip_h.isChecked(),
                "flip_v": self.chk_video_flip_v.isChecked(),
                "rotate": rot_val,
                "fps": fps_val,
                "volume": vol_factor,
                "audio_codec": ac_val,
                "audio_channels": chan_val,
                "strip_metadata": True,
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
        savings = res.get("savings_percent", res.get("savings_pct", 0.0))

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

    def _view_output_media(self):
        if self.dst_path and self.dst_path.exists():
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.dst_path.resolve())))

    def _copy_output_path(self):
        if self.dst_path:
            QApplication.clipboard().setText(str(self.dst_path.resolve()))
            self.lbl_status.setText(f"Copied output path to clipboard: {self.dst_path.name}")
