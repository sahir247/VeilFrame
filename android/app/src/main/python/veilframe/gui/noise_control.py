"""
Noise engine GUI control widget with dynamic intensity badges, Auto/Manual modes, and disclaimers.
"""
from typing import Optional
from PySide6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QCheckBox,
    QRadioButton,
    QButtonGroup,
    QPushButton,
    QGroupBox,
    QFrame,
)
from PySide6.QtCore import Qt, Signal
from ..models.settings import NoiseSettings
from ..core.noise import get_noise_level_label, calculate_noise_strength
from .controls import FocusWheelSlider, create_section_reset_button


class NoiseControlWidget(QGroupBox):
    previewRequested = Signal()
    settingsChanged = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("NOISE ENGINE", parent)
        self._init_ui()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setSpacing(10)

        # Header row: Enable Checkbox + Auto/Manual Radio buttons + Preview + Reset
        top_lay = QHBoxLayout()
        self.cb_enable = QCheckBox("Enable Noise")
        self.cb_enable.setChecked(False)
        self.cb_enable.stateChanged.connect(self._on_enable_toggled)
        top_lay.addWidget(self.cb_enable)

        top_lay.addSpacing(20)

        self.btn_group = QButtonGroup(self)
        self.rb_auto = QRadioButton("● Auto (Subtle)")
        self.rb_manual = QRadioButton("○ Manual")
        self.rb_auto.setChecked(True)
        self.btn_group.addButton(self.rb_auto)
        self.btn_group.addButton(self.rb_manual)
        self.rb_auto.toggled.connect(self._on_mode_toggled)

        top_lay.addWidget(self.rb_auto)
        top_lay.addWidget(self.rb_manual)
        top_lay.addStretch()

        self.btn_preview = QPushButton("Preview Effect")
        self.btn_preview.setToolTip("Compare original vs noise-processed frame")
        self.btn_preview.clicked.connect(self.previewRequested.emit)
        top_lay.addWidget(self.btn_preview)

        self.btn_reset = create_section_reset_button(self._reset_section)
        top_lay.addWidget(self.btn_reset)

        main_lay.addLayout(top_lay)

        # Slider and Intensity Badge Row
        slider_lay = QHBoxLayout()
        lbl_strength = QLabel("Strength:")
        lbl_strength.setStyleSheet("color: #606060; font-size: 11px;")
        slider_lay.addWidget(lbl_strength)

        self.slider = FocusWheelSlider(Qt.Horizontal)
        self.slider.setRange(0, 100)
        self.slider.setValue(1)
        self.slider.setTickInterval(10)
        self.slider.valueChanged.connect(self._on_slider_changed)
        slider_lay.addWidget(self.slider, 1)

        self.lbl_value = QLabel("1 / 100")
        self.lbl_value.setStyleSheet("color: #d0d0d0; font-weight: 500; min-width: 50px;")
        slider_lay.addWidget(self.lbl_value)

        # Dynamic Badge: Subtle / Visible / Strong
        self.lbl_badge = QLabel("Extremely Subtle")
        self.lbl_badge.setAlignment(Qt.AlignCenter)
        self.lbl_badge.setStyleSheet(
            "background-color: #162a1e; color: #3fb768; border: 1px solid #1e4a2e; "
            "border-radius: 3px; padding: 2px 8px; font-weight: 600; font-size: 10px;"
        )
        slider_lay.addWidget(self.lbl_badge)

        main_lay.addLayout(slider_lay)

        # Subtle scale ticks indicator
        scale_lay = QHBoxLayout()
        scale_lay.setContentsMargins(70, 0, 150, 0)
        lbl_min = QLabel("Minimum (Low amplitude)")
        lbl_min.setStyleSheet("color: #555555; font-size: 10px;")
        lbl_mid = QLabel("Subtle  →  Visible")
        lbl_mid.setStyleSheet("color: #555555; font-size: 10px;")
        lbl_max = QLabel("Maximum")
        lbl_max.setStyleSheet("color: #555555; font-size: 10px;")
        scale_lay.addWidget(lbl_min)
        scale_lay.addStretch()
        scale_lay.addWidget(lbl_mid)
        scale_lay.addStretch()
        scale_lay.addWidget(lbl_max)
        main_lay.addLayout(scale_lay)

        # Disclaimer note
        self.lbl_note = QLabel(
            "Note: Lowest enabled level generates very-low-amplitude temporal noise intended to be visually subtle. "
            "Perceptibility may vary based on source material and display contrast."
        )
        self.lbl_note.setWordWrap(True)
        self.lbl_note.setStyleSheet("color: #505050; font-size: 10px; font-style: italic;")
        main_lay.addWidget(self.lbl_note)

        self._update_controls_state()

    def _on_enable_toggled(self):
        self._update_controls_state()
        self.settingsChanged.emit()

    def _on_mode_toggled(self):
        self._update_controls_state()
        self.settingsChanged.emit()

    def _on_slider_changed(self, val: int):
        self.lbl_value.setText(f"{val} / 100")
        self._update_badge(val)
        self.settingsChanged.emit()

    def _update_badge(self, val: int):
        if not self.cb_enable.isChecked() or val == 0:
            self.lbl_badge.setText("OFF")
            self.lbl_badge.setStyleSheet(
                "background-color: #202020; color: #505050; border: 1px solid #303030; "
                "border-radius: 3px; padding: 2px 8px; font-weight: 600; font-size: 10px;"
            )
            return

        label, cat = get_noise_level_label(val)
        self.lbl_badge.setText(label)
        if cat == "subtle":
            self.lbl_badge.setStyleSheet(
                "background-color: #162a1e; color: #3fb768; border: 1px solid #1e4a2e; "
                "border-radius: 3px; padding: 2px 8px; font-weight: 600; font-size: 10px;"
            )
        elif cat == "visible":
            self.lbl_badge.setStyleSheet(
                "background-color: #2a1f0e; color: #c97f1a; border: 1px solid #4a3010; "
                "border-radius: 3px; padding: 2px 8px; font-weight: 600; font-size: 10px;"
            )
        else:  # strong
            self.lbl_badge.setStyleSheet(
                "background-color: #2e1414; color: #d84040; border: 1px solid #4a1818; "
                "border-radius: 3px; padding: 2px 8px; font-weight: 600; font-size: 10px;"
            )

    def _update_controls_state(self):
        enabled = self.cb_enable.isChecked()
        self.rb_auto.setEnabled(enabled)
        self.rb_manual.setEnabled(enabled)
        is_manual = enabled and self.rb_manual.isChecked()

        self.slider.setEnabled(is_manual)
        self.btn_preview.setEnabled(enabled)

        if not enabled:
            self._update_badge(0)
        elif self.rb_auto.isChecked():
            # Auto defaults to subtle level 2
            self.lbl_value.setText("Auto (2)")
            self._update_badge(2)
        else:
            self.lbl_value.setText(f"{self.slider.value()} / 100")
            self._update_badge(self.slider.value())

    def get_settings(self) -> NoiseSettings:
        enabled = self.cb_enable.isChecked()
        mode = "auto" if self.rb_auto.isChecked() else "manual"
        strength = self.slider.value() if mode == "manual" else 2
        return NoiseSettings(
            enabled=enabled,
            mode=mode,
            strength=strength,
            prnu_mode=getattr(self, "_prnu_mode", "gaussian"),
            cfa_pattern=getattr(self, "_cfa_pattern", "RGGB"),
            cfa_gamma=getattr(self, "_cfa_gamma", 0.6),
            hash_perturbation_enabled=getattr(self, "_hash_perturbation_enabled", False),
            hash_perturbation_budget=getattr(self, "_hash_perturbation_budget", 0.02),
        )

    def set_settings(self, settings: NoiseSettings):
        self._prnu_mode = getattr(settings, "prnu_mode", "gaussian")
        self._cfa_pattern = getattr(settings, "cfa_pattern", "RGGB")
        self._cfa_gamma = getattr(settings, "cfa_gamma", 0.6)
        self._hash_perturbation_enabled = getattr(settings, "hash_perturbation_enabled", False)
        self._hash_perturbation_budget = getattr(settings, "hash_perturbation_budget", 0.02)
        self.cb_enable.setChecked(settings.enabled)
        if settings.mode == "auto":
            self.rb_auto.setChecked(True)
        else:
            self.rb_manual.setChecked(True)
        self.slider.setValue(settings.strength if settings.strength > 0 else 1)
        self._update_controls_state()

    def _reset_section(self):
        """Reset noise engine controls to subtle default state."""
        self.cb_enable.setChecked(False)
        self.rb_auto.setChecked(True)
        self.slider.setValue(1)
        self._update_controls_state()
        self.settingsChanged.emit()
