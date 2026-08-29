"""
VeilFrame UI 2.0 — Video info widget.

Changes from v1.0:
  - Metadata leak items displayed as colored pill badges (not inline styled text)
  - Added SHA-256 row (computed on file load, shown as truncated hex)
"""
import hashlib
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QGroupBox, QFrame,
)
from PySide6.QtCore import Qt
from ..models.video_info import VideoInfo
from .theme import badge_style


def _field_label(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet("color: #888888; font-size: 11px;")
    return lbl


def _field_value(text: str = "—") -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 500;")
    lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
    return lbl


def _pill(text: str, variant: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet(badge_style(variant))
    lbl.setFixedHeight(22)
    return lbl


def _compute_sha256_short(path: Path) -> str:
    """Return first 20 hex chars of SHA-256 for a file."""
    try:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        digest = h.hexdigest()
        return digest[:20] + "…"
    except Exception:
        return "—"


class VideoInfoWidget(QGroupBox):
    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("INPUT INFORMATION", parent)
        self._file_path: Optional[Path] = None
        self._init_ui()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setSpacing(10)
        main_lay.setContentsMargins(10, 8, 10, 10)

        # Property grid
        grid = QGridLayout()
        grid.setHorizontalSpacing(24)
        grid.setVerticalSpacing(5)

        def field(row: int, col: int, label: str):
            lbl_key = _field_label(label)
            lbl_val = _field_value()
            grid.addWidget(lbl_key, row, col * 2)
            grid.addWidget(lbl_val, row, col * 2 + 1)
            return lbl_val

        self.val_res          = field(0, 0, "Resolution")
        self.val_fps          = field(1, 0, "Frame Rate")
        self.val_codec        = field(2, 0, "Video Codec")
        self.val_dur          = field(3, 0, "Duration")

        self.val_aspect       = field(0, 1, "Aspect Ratio")
        self.val_v_bitrate    = field(1, 1, "Video Bitrate")
        self.val_pix_fmt      = field(2, 1, "Pixel Format")
        self.val_size         = field(3, 1, "File Size")

        self.val_audio_codec  = field(0, 2, "Audio Codec")
        self.val_audio_chan   = field(1, 2, "Audio Channels")
        self.val_audio_rate   = field(2, 2, "Sample Rate")
        self.val_audio_bitrate= field(3, 2, "Audio Bitrate")

        main_lay.addLayout(grid)

        # SHA-256 row
        sha_row = QHBoxLayout()
        sha_row.addWidget(_field_label("Input SHA-256:"))
        self.val_sha256 = _field_value("—")
        sha_row.addWidget(self.val_sha256)
        sha_row.addStretch()
        main_lay.addLayout(sha_row)

        # Divider
        line = QFrame()
        line.setObjectName("hline")
        line.setFrameShape(QFrame.HLine)
        line.setFixedHeight(1)
        line.setStyleSheet("background: #2e2e2e; border: none;")
        main_lay.addWidget(line)

        # Metadata leak pill badges row
        leak_header = QHBoxLayout()
        title_lbl = _field_label("Metadata Detection:")
        leak_header.addWidget(title_lbl)
        self._leak_pills_lay = QHBoxLayout()
        self._leak_pills_lay.setSpacing(6)
        leak_header.addLayout(self._leak_pills_lay)
        leak_header.addStretch()
        main_lay.addLayout(leak_header)

        # Initial placeholder pill
        self._no_file_pill = _pill("No file loaded", "skip")
        self._leak_pills_lay.addWidget(self._no_file_pill)

    def set_video_info(self, info: Optional[VideoInfo]):
        if not info:
            self.clear()
            return

        v = info.video
        a = info.audio
        meta = info.metadata

        if v:
            self.val_res.setText(v.resolution_str)
            self.val_fps.setText(v.fps_str)
            self.val_codec.setText((v.codec_long_name or v.codec).upper())
            self.val_aspect.setText(v.aspect_ratio or "—")
            self.val_v_bitrate.setText(v.bitrate_str)
            self.val_pix_fmt.setText(v.pixel_format or "Unknown")
        else:
            for lbl in (self.val_res, self.val_fps, self.val_codec,
                        self.val_aspect, self.val_v_bitrate, self.val_pix_fmt):
                lbl.setText("None")

        self.val_dur.setText(info.duration_str)
        self.val_size.setText(info.size_str)

        if a:
            self.val_audio_codec.setText((a.codec_long_name or a.codec).upper())
            self.val_audio_chan.setText(a.channels_str)
            self.val_audio_rate.setText(a.sample_rate_str)
            self.val_audio_bitrate.setText(f"{a.bitrate / 1000:.0f} kbps" if a.bitrate else "—")
        else:
            for lbl in (self.val_audio_codec, self.val_audio_chan,
                        self.val_audio_rate, self.val_audio_bitrate):
                lbl.setText("—")

        # SHA-256 (synchronous — files are usually local; large files use chunked reads)
        self._file_path = Path(info.file_path) if info.file_path else None
        if self._file_path and self._file_path.exists():
            self.val_sha256.setText(_compute_sha256_short(self._file_path))
        else:
            self.val_sha256.setText("—")

        # Metadata leak pills
        self._rebuild_leak_pills(meta)

    def _rebuild_leak_pills(self, meta):
        # Clear existing pills
        while self._leak_pills_lay.count():
            item = self._leak_pills_lay.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        leaks = []
        if meta.gps:
            leaks.append(("GPS", "fail"))
        if meta.camera_make or meta.camera_model:
            make_str = f"{meta.camera_make or ''} {meta.camera_model or ''}".strip()
            leaks.append((f"Camera ({make_str})", "fail"))
        if meta.creation_date:
            leaks.append(("Timestamps", "warn"))
        if meta.encoder or meta.software:
            leaks.append(("Encoder/SW Tags", "warn"))
        if meta.comment:
            leaks.append(("Comments", "warn"))
        if meta.chapters_count > 0:
            leaks.append((f"{meta.chapters_count} Chapters", "warn"))
        if meta.attachments_count > 0:
            leaks.append((f"{meta.attachments_count} Attachments", "warn"))

        if leaks:
            for text, variant in leaks:
                self._leak_pills_lay.addWidget(_pill(text, variant))
        else:
            self._leak_pills_lay.addWidget(_pill("Clean / Minimal Tags", "pass"))

    def clear(self):
        for lbl in (
            self.val_res, self.val_fps, self.val_codec, self.val_dur,
            self.val_aspect, self.val_v_bitrate, self.val_pix_fmt, self.val_size,
            self.val_audio_codec, self.val_audio_chan,
            self.val_audio_rate, self.val_audio_bitrate,
        ):
            lbl.setText("—")
        self.val_sha256.setText("—")

        while self._leak_pills_lay.count():
            item = self._leak_pills_lay.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self._no_file_pill = _pill("No file loaded", "skip")
        self._leak_pills_lay.addWidget(self._no_file_pill)
