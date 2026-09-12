"""
veilframe.gui.image_panel — Image Privacy Compiler controls and configuration panel.

Provides controls for:
  • Active Detectors (Face, License Plate, Text, QR/Barcode)
  • Solid Redaction Fill (Color, Expansion Margin, Tolerance)
  • Container Sanitization (EXIF/XMP/IPTC/ICC purge, Thumbnail stripping)
  • Threat Model Selection (Anonymous Share, Strict Forensics)
"""

from pathlib import Path
from typing import Optional, List
from PIL import Image

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QCheckBox, QSpinBox, QComboBox,
    QGroupBox, QFrame, QPushButton, QColorDialog,
)
from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor

from ..image.models.status import DetectorClass
from ..image.models.policy import ImagePrivacyPolicy, ThreatModelConfig, create_default_policy
from .controls import NoWheelComboBox, FocusWheelSpinBox, create_section_reset_button


class ImageInfoWidget(QFrame):
    """Card displaying source image metadata."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setObjectName("card")
        self.setStyleSheet("background: #222222; border: 1px solid #333333; border-radius: 6px;")
        
        lay = QVBoxLayout(self)
        lay.setContentsMargins(14, 12, 14, 12)
        lay.setSpacing(8)

        hdr = QHBoxLayout()
        title = QLabel("SOURCE IMAGE SPECIFICATION")
        title.setStyleSheet("font-size: 10px; font-weight: 700; letter-spacing: 1.5px; color: #606060;")
        hdr.addWidget(title)
        hdr.addStretch()
        lay.addLayout(hdr)

        self.grid = QGridLayout()
        self.grid.setHorizontalSpacing(16)
        self.grid.setVerticalSpacing(4)

        self._lbl_format = QLabel("—")
        self._lbl_dims = QLabel("—")
        self._lbl_channels = QLabel("—")
        self._lbl_size = QLabel("—")
        self._lbl_hash = QLabel("—")

        for lbl in (self._lbl_format, self._lbl_dims, self._lbl_channels, self._lbl_size, self._lbl_hash):
            lbl.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 500;")
            lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)

        self.grid.addWidget(QLabel("Format:"), 0, 0)
        self.grid.addWidget(self._lbl_format, 0, 1)
        self.grid.addWidget(QLabel("Dimensions:"), 0, 2)
        self.grid.addWidget(self._lbl_dims, 0, 3)

        self.grid.addWidget(QLabel("Channels:"), 1, 0)
        self.grid.addWidget(self._lbl_channels, 1, 1)
        self.grid.addWidget(QLabel("File Size:"), 1, 2)
        self.grid.addWidget(self._lbl_size, 1, 3)

        self.grid.addWidget(QLabel("SHA-256 Digest:"), 2, 0)
        self.grid.addWidget(self._lbl_hash, 2, 1, 1, 3)

        # Style field name labels
        for r in range(3):
            for c in (0, 2):
                item = self.grid.itemAtPosition(r, c)
                if item and item.widget():
                    item.widget().setStyleSheet("color: #606060; font-size: 11px;")

        lay.addLayout(self.grid)

    def set_image_file(self, path: Path):
        try:
            with Image.open(path) as img:
                w, h = img.size
                fmt = img.format or path.suffix.upper().lstrip(".")
                mode = img.mode
                channels = len(img.getbands())
            size_kb = path.stat().st_size / 1024.0
            size_str = f"{size_kb / 1024.0:.2f} MB" if size_kb >= 1024 else f"{size_kb:.1f} KB"

            import hashlib
            digest = hashlib.sha256(path.read_bytes()).hexdigest()

            self._lbl_format.setText(str(fmt))
            self._lbl_dims.setText(f"{w} x {h} px")
            self._lbl_channels.setText(f"{channels} ({mode})")
            self._lbl_size.setText(size_str)
            self._lbl_hash.setText(f"{digest[:32]}...{digest[-8:]}")
        except Exception as e:
            self._lbl_format.setText("Error loading metadata")
            self._lbl_dims.setText(str(e))

    def clear(self):
        self._lbl_format.setText("—")
        self._lbl_dims.setText("—")
        self._lbl_channels.setText("—")
        self._lbl_size.setText("—")
        self._lbl_hash.setText("—")


class ImageProcessingPanel(QWidget):
    """Settings and configuration panel for Image Privacy Sanitization."""

    settingsChanged = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.policy: ImagePrivacyPolicy = create_default_policy()
        self._fill_color: tuple = (0.0, 0.0, 0.0)
        self._init_ui()

    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(12)

        # 1. Threat Model & Profile Card
        profile_box = QGroupBox("IMAGE PRIVACY THREAT MODEL")
        pr_lay = QVBoxLayout(profile_box)
        pr_row = QHBoxLayout()
        pr_row.addWidget(QLabel("Threat Profile:"))
        self.combo_threat = NoWheelComboBox()
        self.combo_threat.addItems([
            "Anonymous Share (Metadata Stripping + Visual Redaction)",
            "Strict Forensic (Full Zero-Residue Sanitization)",
        ])
        self.combo_threat.currentIndexChanged.connect(self._on_threat_changed)
        pr_row.addWidget(self.combo_threat, 1)

        btn_reset_pr = create_section_reset_button(self._reset_to_defaults, tooltip="Reset image privacy parameters")
        pr_row.addWidget(btn_reset_pr)
        pr_lay.addLayout(pr_row)

        self.lbl_threat_desc = QLabel("Standard privacy profile: Strips EXIF/XMP/IPTC/thumbnails and redacts sensitive zones with solid destructive fill.")
        self.lbl_threat_desc.setStyleSheet("color: #606060; font-size: 11px;")
        self.lbl_threat_desc.setWordWrap(True)
        pr_lay.addWidget(self.lbl_threat_desc)
        lay.addWidget(profile_box)

        # 2. Semantic Detectors Card
        det_box = QGroupBox("SEMANTIC DETECTION & REDACTION PROVIDERS")
        det_lay = QVBoxLayout(det_box)
        det_lay.setSpacing(10)

        det_grid = QGridLayout()
        det_grid.setHorizontalSpacing(16)
        det_grid.setVerticalSpacing(8)

        self.cb_face = QCheckBox("Facial Identity Redaction (Haar + Multi-Scale)")
        self.cb_face.setChecked(True)
        self.cb_face.setStyleSheet("font-weight: 600; color: #d0d0d0;")
        det_grid.addWidget(self.cb_face, 0, 0)

        self.cb_plate = QCheckBox("Vehicle License Plates (Aspect Ratio & Edge Filtering)")
        self.cb_plate.setChecked(True)
        self.cb_plate.setStyleSheet("font-weight: 600; color: #d0d0d0;")
        det_grid.addWidget(self.cb_plate, 0, 1)

        self.cb_text = QCheckBox("Document / Scene Text (MSER Density Gated)")
        self.cb_text.setChecked(True)
        self.cb_text.setStyleSheet("font-weight: 600; color: #d0d0d0;")
        det_grid.addWidget(self.cb_text, 1, 0)

        self.cb_code = QCheckBox("QR Codes & Barcodes (Finder Pattern Detection)")
        self.cb_code.setChecked(True)
        self.cb_code.setStyleSheet("font-weight: 600; color: #d0d0d0;")
        det_grid.addWidget(self.cb_code, 1, 1)

        for cb in (self.cb_face, self.cb_plate, self.cb_text, self.cb_code):
            cb.toggled.connect(self._on_control_changed)

        det_lay.addLayout(det_grid)
        lay.addWidget(det_box)

        # 3. Redaction Appearance & Geometry Card
        geom_box = QGroupBox("REDACTION GEOMETRY & SOLID FILL PARAMETERS")
        geom_lay = QVBoxLayout(geom_box)
        geom_lay.setSpacing(10)

        g_row1 = QHBoxLayout()
        g_row1.addWidget(QLabel("Bounding Box Expansion Margin:"))
        self.spin_margin = FocusWheelSpinBox()
        self.spin_margin.setRange(0, 100)
        self.spin_margin.setValue(10)
        self.spin_margin.setSuffix(" px")
        self.spin_margin.valueChanged.connect(self._on_control_changed)
        g_row1.addWidget(self.spin_margin)
        g_row1.addSpacing(20)

        g_row1.addWidget(QLabel("Solid Fill Color:"))
        self.btn_color = QPushButton("Black (0, 0, 0)")
        self.btn_color.setStyleSheet("background-color: #000000; color: #ffffff; border: 1px solid #404040;")
        self.btn_color.clicked.connect(self._pick_color)
        g_row1.addWidget(self.btn_color)
        g_row1.addStretch()
        geom_lay.addLayout(g_row1)

        # Metadata toggles
        g_row2 = QHBoxLayout()
        self.cb_meta = QCheckBox("Strip Container Metadata (EXIF, XMP, IPTC, ICC Profiles)")
        self.cb_meta.setChecked(True)
        self.cb_meta.toggled.connect(self._on_control_changed)
        g_row2.addWidget(self.cb_meta)

        self.cb_thumb = QCheckBox("Eradicate Embedded Thumbnails & Preview Streams")
        self.cb_thumb.setChecked(True)
        self.cb_thumb.toggled.connect(self._on_control_changed)
        g_row2.addWidget(self.cb_thumb)
        g_row2.addStretch()
        geom_lay.addLayout(g_row2)

        lay.addWidget(geom_box)

    def _on_threat_changed(self, idx: int):
        if idx == 0:
            self.lbl_threat_desc.setText("Standard privacy profile: Strips EXIF/XMP/IPTC/thumbnails and redacts sensitive zones with solid destructive fill.")
        else:
            self.lbl_threat_desc.setText("Strict forensic profile: Maximizes redaction margins, enforces zero-tolerance container sanitization, and full probe verification.")
        self._on_control_changed()

    def _pick_color(self):
        col = QColorDialog.getColor(QColor(0, 0, 0), self, "Select Solid Redaction Fill Color")
        if col.isValid():
            r, g, b = col.redF(), col.greenF(), col.blueF()
            self._fill_color = (r, g, b)
            rgb_255 = (int(round(r * 255)), int(round(g * 255)), int(round(b * 255)))
            hex_str = col.name()
            text_color = "#ffffff" if (r + g + b) / 3.0 < 0.5 else "#000000"
            self.btn_color.setText(f"RGB {rgb_255}")
            self.btn_color.setStyleSheet(f"background-color: {hex_str}; color: {text_color}; border: 1px solid #404040;")
            self._on_control_changed()

    def _reset_to_defaults(self):
        self.combo_threat.setCurrentIndex(0)
        self.cb_face.setChecked(True)
        self.cb_plate.setChecked(True)
        self.cb_text.setChecked(True)
        self.cb_code.setChecked(True)
        self.spin_margin.setValue(10)
        self.cb_meta.setChecked(True)
        self.cb_thumb.setChecked(True)
        self._fill_color = (0.0, 0.0, 0.0)
        self.btn_color.setText("Black (0, 0, 0)")
        self.btn_color.setStyleSheet("background-color: #000000; color: #ffffff; border: 1px solid #404040;")
        self._on_control_changed()

    def _on_control_changed(self):
        self.settingsChanged.emit()

    def get_policy(self) -> ImagePrivacyPolicy:
        detectors: List[DetectorClass] = []
        if self.cb_face.isChecked():
            detectors.append(DetectorClass.FACE)
        if self.cb_plate.isChecked():
            detectors.append(DetectorClass.LICENSE_PLATE)
        if self.cb_text.isChecked():
            detectors.append(DetectorClass.TEXT)
        if self.cb_code.isChecked():
            detectors.append(DetectorClass.QR_CODE)

        threat_ident = "threat-strict-v1" if self.combo_threat.currentIndex() == 1 else "threat-standard-v1"

        return ImagePrivacyPolicy(
            policy_id="ui_configured_policy",
            policy_version="2.0.0",
            threat_model=ThreatModelConfig(
                threat_identity=threat_ident,
                model_a_enabled=self.cb_meta.isChecked(),
                model_b_enabled=True,
                model_c_enabled=True,
                model_d_enabled=(self.combo_threat.currentIndex() == 1),
            ),
            active_detector_classes=detectors,
            fill_color_rgb=self._fill_color,
            redact_metadata=self.cb_meta.isChecked(),
            redact_thumbnails=self.cb_thumb.isChecked(),
            expansion_margin_px=self.spin_margin.value(),
            max_mask_expansion_tolerance_px=5,
        )
