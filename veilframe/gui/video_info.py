"""
Widget for displaying parsed input video stream details and detected metadata leaks.
"""
from typing import Optional
from PySide6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QGridLayout,
    QLabel,
    QGroupBox,
    QScrollArea,
    QFrame,
)
from PySide6.QtCore import Qt
from ..models.video_info import VideoInfo


class VideoInfoWidget(QGroupBox):
    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("INPUT INFORMATION", parent)
        self._init_ui()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setSpacing(10)

        # Grid of core properties
        grid = QGridLayout()
        grid.setHorizontalSpacing(20)
        grid.setVerticalSpacing(6)

        def add_field(row: int, col: int, label_text: str):
            lbl_title = QLabel(label_text)
            lbl_title.setStyleSheet("color: #94a3b8; font-weight: 500;")
            lbl_val = QLabel("—")
            lbl_val.setStyleSheet("color: #f8fafc; font-weight: 600;")
            lbl_val.setTextInteractionFlags(Qt.TextSelectableByMouse)
            grid.addWidget(lbl_title, row, col * 2)
            grid.addWidget(lbl_val, row, col * 2 + 1)
            return lbl_val

        self.val_res = add_field(0, 0, "Resolution:")
        self.val_fps = add_field(1, 0, "Frame Rate:")
        self.val_codec = add_field(2, 0, "Video Codec:")
        self.val_dur = add_field(3, 0, "Duration:")

        self.val_aspect = add_field(0, 1, "Aspect Ratio:")
        self.val_v_bitrate = add_field(1, 1, "Video Bitrate:")
        self.val_pix_fmt = add_field(2, 1, "Pixel Format:")
        self.val_size = add_field(3, 1, "File Size:")

        self.val_audio_codec = add_field(0, 2, "Audio Codec:")
        self.val_audio_chan = add_field(1, 2, "Audio Channels:")
        self.val_audio_rate = add_field(2, 2, "Sample Rate:")
        self.val_audio_bitrate = add_field(3, 2, "Audio Bitrate:")

        main_lay.addLayout(grid)

        # Divider line
        line = QFrame()
        line.setFrameShape(QFrame.HLine)
        line.setStyleSheet("background-color: #334155;")
        main_lay.addWidget(line)

        # Privacy status tag indicator row
        leak_lay = QHBoxLayout()
        self.lbl_leak_title = QLabel("Metadata Detection:")
        self.lbl_leak_title.setStyleSheet("color: #94a3b8; font-weight: 500;")
        self.lbl_leak_status = QLabel("No file loaded")
        self.lbl_leak_status.setStyleSheet("color: #64748b; font-weight: 600;")
        leak_lay.addWidget(self.lbl_leak_title)
        leak_lay.addWidget(self.lbl_leak_status)
        leak_lay.addStretch()

        main_lay.addLayout(leak_lay)

    def set_video_info(self, info: Optional[VideoInfo]):
        if not info:
            self.clear()
            return

        v = info.video
        a = info.audio
        meta = info.metadata

        # Video properties
        if v:
            self.val_res.setText(v.resolution_str)
            self.val_fps.setText(v.fps_str)
            self.val_codec.setText((v.codec_long_name or v.codec).upper())
            self.val_aspect.setText(v.aspect_ratio)
            self.val_v_bitrate.setText(v.bitrate_str)
            self.val_pix_fmt.setText(v.pixel_format or "Unknown")
        else:
            self.val_res.setText("None")
            self.val_fps.setText("—")
            self.val_codec.setText("None")
            self.val_aspect.setText("—")
            self.val_v_bitrate.setText("—")
            self.val_pix_fmt.setText("—")

        self.val_dur.setText(info.duration_str)
        self.val_size.setText(info.size_str)

        # Audio properties
        if a:
            self.val_audio_codec.setText((a.codec_long_name or a.codec).upper())
            self.val_audio_chan.setText(a.channels_str)
            self.val_audio_rate.setText(a.sample_rate_str)
            self.val_audio_bitrate.setText(f"{a.bitrate / 1000:.0f} kbps" if a.bitrate else "—")
        else:
            self.val_audio_codec.setText("None")
            self.val_audio_chan.setText("—")
            self.val_audio_rate.setText("—")
            self.val_audio_bitrate.setText("—")

        # Metadata leaks
        leaks = []
        if meta.gps:
            leaks.append("GPS Coordinates")
        if meta.camera_make or meta.camera_model:
            leaks.append(f"Camera ({meta.camera_make or ''} {meta.camera_model or ''}".strip() + ")")
        if meta.creation_date:
            leaks.append("Timestamps")
        if meta.encoder or meta.software:
            leaks.append("Encoder/Software Tags")
        if meta.comment:
            leaks.append("Comments")
        if meta.chapters_count > 0:
            leaks.append(f"{meta.chapters_count} Chapters")
        if meta.attachments_count > 0:
            leaks.append(f"{meta.attachments_count} Attachments")

        if leaks:
            self.lbl_leak_status.setText(f"⚠️ Detected: {', '.join(leaks)}")
            self.lbl_leak_status.setStyleSheet("color: #f59e0b; font-weight: 600;")
        else:
            self.lbl_leak_status.setText("✓ Standard / Minimal Container Tags")
            self.lbl_leak_status.setStyleSheet("color: #10b981; font-weight: 600;")

    def clear(self):
        for lbl in (
            self.val_res, self.val_fps, self.val_codec, self.val_dur,
            self.val_aspect, self.val_v_bitrate, self.val_pix_fmt, self.val_size,
            self.val_audio_codec, self.val_audio_chan, self.val_audio_rate, self.val_audio_bitrate,
        ):
            lbl.setText("—")
        self.lbl_leak_status.setText("No file loaded")
        self.lbl_leak_status.setStyleSheet("color: #64748b; font-weight: 600;")
