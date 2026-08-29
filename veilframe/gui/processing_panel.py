"""
Processing panel with Auto/Manual controls for Crop, Resize, FPS, Trim, Color Drift, Audio Privacy, Codec, Quality, and Privacy.
"""
from typing import Optional
from PySide6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QGridLayout,
    QLabel,
    QComboBox,
    QCheckBox,
    QRadioButton,
    QButtonGroup,
    QSpinBox,
    QDoubleSpinBox,
    QGroupBox,
    QFrame,
)
from PySide6.QtCore import Qt, Signal

from ..models.settings import (
    ProcessingSettings,
    CropSettings,
    ResizeSettings,
    FpsSettings,
    TrimSettings,
    ColorSettings,
    AudioPrivacySettings,
    QuantizationSettings,
    CodecSettings,
    QualitySettings,
    PrivacySettings,
    VisualBudgetPolicy,
)
from ..models.video_info import VideoInfo
from ..presets.manager import PresetManager
from .noise_control import NoiseControlWidget


class ProcessingPanel(QWidget):
    settingsChanged = Signal()
    presetChanged = Signal(str)

    def __init__(self, preset_mgr: PresetManager, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.preset_mgr = preset_mgr
        self.video_info: Optional[VideoInfo] = None
        self._updating_ui = False
        self._init_ui()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(0, 0, 0, 0)
        main_lay.setSpacing(12)

        # 1. Preset Profile Header
        preset_box = QGroupBox("PROCESSING PRESET")
        p_lay = QVBoxLayout(preset_box)
        p_row = QHBoxLayout()
        p_row.addWidget(QLabel("Profile:"))
        self.combo_presets = QComboBox()
        for name in self.preset_mgr.get_preset_names():
            self.combo_presets.addItem(name)
        self.combo_presets.currentTextChanged.connect(self._on_preset_selected)
        p_row.addWidget(self.combo_presets, 1)
        p_lay.addLayout(p_row)

        self.lbl_preset_desc = QLabel(self.preset_mgr.get_preset_description(self.combo_presets.currentText()))
        self.lbl_preset_desc.setStyleSheet("color: #606060; font-size: 11px;")
        self.lbl_preset_desc.setWordWrap(True)
        p_lay.addWidget(self.lbl_preset_desc)
        main_lay.addWidget(preset_box)

        # 2. Main Transformations Card
        proc_box = QGroupBox("SPATIAL & TEMPORAL TRANSFORMATION CONTROLS")
        proc_lay = QVBoxLayout(proc_box)
        proc_lay.setSpacing(14)

        # --- Section Helper ---
        def create_toggle_header(title: str, default_auto_text: str = "● Auto (Subtle)"):
            row = QHBoxLayout()
            cb_enable = QCheckBox(title)
            cb_enable.setStyleSheet("font-weight: 600; color: #d0d0d0;")

            bg = QButtonGroup(self)
            rb_auto = QRadioButton(default_auto_text)
            rb_manual = QRadioButton("○ Manual")
            rb_auto.setChecked(True)
            bg.addButton(rb_auto)
            bg.addButton(rb_manual)

            row.addWidget(cb_enable)
            row.addSpacing(16)
            row.addWidget(rb_auto)
            row.addWidget(rb_manual)
            row.addStretch()
            return cb_enable, rb_auto, rb_manual, row

        # --- CROP ---
        self.crop_enable, self.crop_auto, self.crop_manual, crop_hdr = create_toggle_header("Crop (Asymmetric pHash Disruption)")
        proc_lay.addLayout(crop_hdr)

        crop_inputs_lay = QHBoxLayout()
        crop_inputs_lay.setContentsMargins(20, 0, 0, 0)
        self.crop_left = QSpinBox(); self.crop_left.setRange(0, 5000); self.crop_left.setPrefix("L: ")
        self.crop_right = QSpinBox(); self.crop_right.setRange(0, 5000); self.crop_right.setPrefix("R: ")
        self.crop_top = QSpinBox(); self.crop_top.setRange(0, 5000); self.crop_top.setPrefix("T: ")
        self.crop_bottom = QSpinBox(); self.crop_bottom.setRange(0, 5000); self.crop_bottom.setPrefix("B: ")
        for sp in (self.crop_left, self.crop_right, self.crop_top, self.crop_bottom):
            crop_inputs_lay.addWidget(sp)
            sp.valueChanged.connect(self._on_control_changed)

        self.lbl_crop_preview = QLabel("Auto: Asymmetric edge crop (1.5% L, 1.0% R, 1.8% T, 0.7% B)")
        self.lbl_crop_preview.setStyleSheet("color: #606060; font-size: 11px;")
        crop_inputs_lay.addWidget(self.lbl_crop_preview)
        crop_inputs_lay.addStretch()
        proc_lay.addLayout(crop_inputs_lay)

        proc_lay.addWidget(self._create_divider())

        # --- RESIZE ---
        self.resize_enable, self.resize_auto, self.resize_manual, resize_hdr = create_toggle_header("Resize (Lanczos Grid Resample)")
        proc_lay.addLayout(resize_hdr)

        resize_inputs_lay = QHBoxLayout()
        resize_inputs_lay.setContentsMargins(20, 0, 0, 0)
        self.resize_w = QSpinBox(); self.resize_w.setRange(16, 16384); self.resize_w.setValue(1920); self.resize_w.setPrefix("W: ")
        self.resize_h = QSpinBox(); self.resize_h.setRange(16, 16384); self.resize_h.setValue(1080); self.resize_h.setPrefix("H: ")
        self.resize_aspect = QCheckBox("Maintain Aspect Ratio")
        self.resize_aspect.setChecked(True)
        resize_inputs_lay.addWidget(self.resize_w)
        resize_inputs_lay.addWidget(QLabel("×"))
        resize_inputs_lay.addWidget(self.resize_h)
        resize_inputs_lay.addWidget(self.resize_aspect)
        resize_inputs_lay.addStretch()
        self.resize_w.valueChanged.connect(self._on_control_changed)
        self.resize_h.valueChanged.connect(self._on_control_changed)
        self.resize_aspect.stateChanged.connect(self._on_control_changed)
        proc_lay.addLayout(resize_inputs_lay)

        proc_lay.addWidget(self._create_divider())

        # --- FPS ---
        self.fps_enable, self.fps_auto, self.fps_manual, fps_hdr = create_toggle_header("Frame Rate (FPS)")
        proc_lay.addLayout(fps_hdr)

        fps_inputs_lay = QHBoxLayout()
        fps_inputs_lay.setContentsMargins(20, 0, 0, 0)
        self.fps_val = QDoubleSpinBox()
        self.fps_val.setRange(1.0, 240.0)
        self.fps_val.setDecimals(3)
        self.fps_val.setValue(30.0)
        self.fps_val.setSuffix(" fps")
        self.fps_val.valueChanged.connect(self._on_control_changed)
        fps_inputs_lay.addWidget(self.fps_val)
        fps_inputs_lay.addStretch()
        proc_lay.addLayout(fps_inputs_lay)

        proc_lay.addWidget(self._create_divider())

        # --- TRIM / DURATION ---
        self.trim_enable, self.trim_auto, self.trim_manual, trim_hdr = create_toggle_header("Duration / Micro-Time Warp")
        proc_lay.addLayout(trim_hdr)

        trim_inputs_lay = QHBoxLayout()
        trim_inputs_lay.setContentsMargins(20, 0, 0, 0)
        trim_inputs_lay.addWidget(QLabel("Start:"))
        self.trim_start = QDoubleSpinBox(); self.trim_start.setRange(0.0, 999999.0); self.trim_start.setDecimals(3); self.trim_start.setSuffix("s")
        trim_inputs_lay.addWidget(self.trim_start)

        trim_inputs_lay.addWidget(QLabel("Duration:"))
        self.trim_dur = QDoubleSpinBox(); self.trim_dur.setRange(0.1, 999999.0); self.trim_dur.setDecimals(3); self.trim_dur.setValue(60.0); self.trim_dur.setSuffix("s")
        trim_inputs_lay.addWidget(self.trim_dur)

        self.lbl_trim_summary = QLabel("Timeline: 0.00s → 60.00s")
        self.lbl_trim_summary.setStyleSheet("color: #606060; font-size: 11px;")
        trim_inputs_lay.addWidget(self.lbl_trim_summary)
        trim_inputs_lay.addStretch()

        self.trim_start.valueChanged.connect(self._on_control_changed)
        self.trim_dur.valueChanged.connect(self._on_control_changed)
        proc_lay.addLayout(trim_inputs_lay)

        main_lay.addWidget(proc_box)

        # 3. Color & Luminance Drift Card (~1% Budget)
        color_box = QGroupBox("COLOR & LUMINANCE DRIFT (~1% BUDGET)")
        color_lay = QVBoxLayout(color_box)

        self.color_enable, self.color_auto, self.color_manual, color_hdr = create_toggle_header("Low-Frequency Color Drift")
        color_lay.addLayout(color_hdr)

        color_inputs_lay = QHBoxLayout()
        color_inputs_lay.setContentsMargins(20, 0, 0, 0)
        self.col_contrast = QDoubleSpinBox(); self.col_contrast.setRange(0.5, 2.0); self.col_contrast.setDecimals(3); self.col_contrast.setValue(1.015); self.col_contrast.setPrefix("Contrast: ")
        self.col_bright = QDoubleSpinBox(); self.col_bright.setRange(-0.5, 0.5); self.col_bright.setDecimals(3); self.col_bright.setValue(0.005); self.col_bright.setPrefix("Bright: ")
        self.col_gamma = QDoubleSpinBox(); self.col_gamma.setRange(0.5, 2.0); self.col_gamma.setDecimals(3); self.col_gamma.setValue(0.985); self.col_gamma.setPrefix("Gamma: ")
        self.col_sat = QDoubleSpinBox(); self.col_sat.setRange(0.5, 2.0); self.col_sat.setDecimals(3); self.col_sat.setValue(1.02); self.col_sat.setPrefix("Sat: ")

        for sp in (self.col_contrast, self.col_bright, self.col_gamma, self.col_sat):
            color_inputs_lay.addWidget(sp)
            sp.valueChanged.connect(self._on_control_changed)
        color_inputs_lay.addStretch()
        color_lay.addLayout(color_inputs_lay)
        main_lay.addWidget(color_box)

        # 4. Noise Engine Widget
        self.noise_widget = NoiseControlWidget()
        self.noise_widget.settingsChanged.connect(self._on_control_changed)
        main_lay.addWidget(self.noise_widget)

        # 5. Audio Domain Privacy Card
        audio_box = QGroupBox("AUDIO DOMAIN PRIVACY & ENF NOTCH FILTERING")
        audio_lay = QVBoxLayout(audio_box)

        self.audio_enable = QCheckBox("Enable Audio Privacy Pipeline")
        self.audio_enable.setChecked(True)
        self.audio_enable.setStyleSheet("font-weight: 600; color: #d0d0d0;")
        audio_lay.addWidget(self.audio_enable)

        audio_opts_lay = QHBoxLayout()
        audio_opts_lay.setContentsMargins(20, 0, 0, 0)
        self.cb_enf_notch = QCheckBox("IIR Mains ENF Notch (50Hz / 60Hz / 100Hz / 120Hz)")
        self.cb_enf_notch.setChecked(True)
        self.cb_enf_notch.setToolTip("Neutralizes Electrical Network Frequency hum tracing in power grids")
        self.cb_micro_pitch = QCheckBox("Phase/Pitch Micro-Shift (0.99x)")
        self.cb_micro_pitch.setChecked(True)
        self.cb_micro_pitch.setToolTip("Disrupts acoustic audio fingerprinting while maintaining comprehension")
        audio_opts_lay.addWidget(self.cb_enf_notch)
        audio_opts_lay.addWidget(self.cb_micro_pitch)
        audio_opts_lay.addStretch()
        audio_lay.addLayout(audio_opts_lay)

        self.audio_enable.stateChanged.connect(self._on_control_changed)
        self.cb_enf_notch.stateChanged.connect(self._on_control_changed)
        self.cb_micro_pitch.stateChanged.connect(self._on_control_changed)
        main_lay.addWidget(audio_box)

        # 6. Deterministic Quantization & Encoding
        quant_box = QGroupBox("DETERMINISTIC QUANTIZATION & CODEC")
        quant_lay = QVBoxLayout(quant_box)

        quant_opts_lay = QHBoxLayout()
        self.cb_forced_gop = QCheckBox("Forced IDR/GOP Restructuring (GOP: 48, SC: 0)")
        self.cb_forced_gop.setChecked(True)
        self.cb_forced_gop.setToolTip("Enforces rigid keyframe cadence to disrupt motion vector temporal hashes")
        self.cb_epoch_zero = QCheckBox("Timestamp Normalization (Epoch 0: 1970-01-01)")
        self.cb_epoch_zero.setChecked(True)
        quant_opts_lay.addWidget(self.cb_forced_gop)
        quant_opts_lay.addWidget(self.cb_epoch_zero)
        quant_opts_lay.addStretch()
        quant_lay.addLayout(quant_opts_lay)

        codec_row = QHBoxLayout()
        codec_row.addWidget(QLabel("Codec:"))
        self.combo_codec_mode = QComboBox()
        self.combo_codec_mode.addItems(["Auto (H.264 / Best)", "Manual"])
        codec_row.addWidget(self.combo_codec_mode)

        self.combo_codec = QComboBox()
        self.combo_codec.addItems(["H.264 (libx264)", "H.265 / HEVC (libx265)", "AV1 (libsvtav1)"])
        codec_row.addWidget(self.combo_codec)

        codec_row.addSpacing(16)
        codec_row.addWidget(QLabel("Quality:"))
        self.combo_q_mode = QComboBox()
        self.combo_q_mode.addItems(["Auto (CRF 18-21)", "CRF", "Bitrate"])
        codec_row.addWidget(self.combo_q_mode)

        self.spin_crf = QSpinBox(); self.spin_crf.setRange(0, 51); self.spin_crf.setValue(21); self.spin_crf.setPrefix("CRF: ")
        self.spin_bitrate = QSpinBox(); self.spin_bitrate.setRange(100, 100000); self.spin_bitrate.setValue(12000); self.spin_bitrate.setSuffix(" kbps")
        codec_row.addWidget(self.spin_crf)
        codec_row.addWidget(self.spin_bitrate)
        codec_row.addStretch()

        self.combo_codec_mode.currentTextChanged.connect(self._on_control_changed)
        self.combo_codec.currentTextChanged.connect(self._on_control_changed)
        self.combo_q_mode.currentTextChanged.connect(self._on_control_changed)
        self.spin_crf.valueChanged.connect(self._on_control_changed)
        self.spin_bitrate.valueChanged.connect(self._on_control_changed)
        self.cb_forced_gop.stateChanged.connect(self._on_control_changed)
        self.cb_epoch_zero.stateChanged.connect(self._on_control_changed)
        quant_lay.addLayout(codec_row)

        main_lay.addWidget(quant_box)

        # 7. Privacy Sanitization Checklist
        priv_box = QGroupBox("PRIVACY SANITIZATION")
        priv_lay = QGridLayout(priv_box)
        priv_lay.setHorizontalSpacing(20)
        priv_lay.setVerticalSpacing(8)

        self.cb_priv_meta = QCheckBox("Remove metadata (EXIF/XMP/GPS/Camera)")
        self.cb_priv_meta.setChecked(True)
        self.cb_priv_comm = QCheckBox("Remove comments & descriptions")
        self.cb_priv_comm.setChecked(True)
        self.cb_priv_chap = QCheckBox("Remove chapters & embedded attachments")
        self.cb_priv_chap.setChecked(True)
        self.cb_priv_scrub = QCheckBox("Scrub container headers after encoding")
        self.cb_priv_scrub.setChecked(True)
        self.cb_priv_verify = QCheckBox("Verify output and generate Forensic Report")
        self.cb_priv_verify.setChecked(True)

        priv_lay.addWidget(self.cb_priv_meta, 0, 0)
        priv_lay.addWidget(self.cb_priv_comm, 0, 1)
        priv_lay.addWidget(self.cb_priv_chap, 1, 0)
        priv_lay.addWidget(self.cb_priv_scrub, 1, 1)
        priv_lay.addWidget(self.cb_priv_verify, 2, 0)

        for cb in (self.cb_priv_meta, self.cb_priv_comm, self.cb_priv_chap, self.cb_priv_scrub, self.cb_priv_verify):
            cb.stateChanged.connect(self._on_control_changed)

        main_lay.addWidget(priv_box)

        # 6. Quality & Fidelity Gate
        qg_box = QGroupBox("INDEPENDENT VISUAL QUALITY & FIDELITY GATE")
        qg_lay = QVBoxLayout(qg_box)
        qg_lay.setSpacing(8)

        self.cb_qg_enable = QCheckBox("Enable Visual Quality Gate (Audit rendered frames against SSIM & PSNR constraints)")
        self.cb_qg_enable.setChecked(True)
        self.cb_qg_enable.stateChanged.connect(self._on_control_changed)
        qg_lay.addWidget(self.cb_qg_enable)

        self.cb_qg_strict = QCheckBox("Strict Enforcement (Reject export if fidelity constraints are violated)")
        self.cb_qg_strict.setChecked(False)
        self.cb_qg_strict.stateChanged.connect(self._on_control_changed)
        qg_lay.addWidget(self.cb_qg_strict)

        qg_params_lay = QHBoxLayout()
        qg_params_lay.addWidget(QLabel("Min Mean SSIM:"))
        self.spin_qg_ssim = QDoubleSpinBox()
        self.spin_qg_ssim.setRange(0.50, 1.00)
        self.spin_qg_ssim.setSingleStep(0.01)
        self.spin_qg_ssim.setValue(0.95)
        self.spin_qg_ssim.valueChanged.connect(self._on_control_changed)
        qg_params_lay.addWidget(self.spin_qg_ssim)

        qg_params_lay.addWidget(QLabel("Min Mean PSNR (dB):"))
        self.spin_qg_psnr = QDoubleSpinBox()
        self.spin_qg_psnr.setRange(10.0, 60.0)
        self.spin_qg_psnr.setSingleStep(1.0)
        self.spin_qg_psnr.setValue(30.0)
        self.spin_qg_psnr.valueChanged.connect(self._on_control_changed)
        qg_params_lay.addWidget(self.spin_qg_psnr)
        qg_params_lay.addStretch()

        qg_lay.addLayout(qg_params_lay)
        main_lay.addWidget(qg_box)

        # Wire toggle listeners
        for cb, rb_a, rb_m in (
            (self.crop_enable, self.crop_auto, self.crop_manual),
            (self.resize_enable, self.resize_auto, self.resize_manual),
            (self.fps_enable, self.fps_auto, self.fps_manual),
            (self.trim_enable, self.trim_auto, self.trim_manual),
            (self.color_enable, self.color_auto, self.color_manual),
        ):
            cb.stateChanged.connect(self._update_all_states)
            rb_a.toggled.connect(self._update_all_states)
            rb_m.toggled.connect(self._update_all_states)

        self._update_all_states()

    def _create_divider(self) -> QFrame:
        line = QFrame()
        line.setFrameShape(QFrame.HLine)
        line.setStyleSheet("background-color: #2e2e2e;")
        return line

    def _on_preset_selected(self, name: str):
        if self._updating_ui or not name:
            return
        self.lbl_preset_desc.setText(self.preset_mgr.get_preset_description(name))
        settings = self.preset_mgr.apply_preset(name)
        self.set_settings(settings)
        self.presetChanged.emit(name)

    def _on_control_changed(self):
        if self._updating_ui:
            return
        self._update_all_states()
        self.settingsChanged.emit()

    def _update_all_states(self):
        # Crop
        crop_on = self.crop_enable.isChecked()
        self.crop_auto.setEnabled(crop_on)
        self.crop_manual.setEnabled(crop_on)
        crop_man = crop_on and self.crop_manual.isChecked()
        for sp in (self.crop_left, self.crop_right, self.crop_top, self.crop_bottom):
            sp.setEnabled(crop_man)

        # Resize
        res_on = self.resize_enable.isChecked()
        self.resize_auto.setEnabled(res_on)
        self.resize_manual.setEnabled(res_on)
        res_man = res_on and self.resize_manual.isChecked()
        self.resize_w.setEnabled(res_man)
        self.resize_h.setEnabled(res_man)
        self.resize_aspect.setEnabled(res_man)

        # FPS
        fps_on = self.fps_enable.isChecked()
        self.fps_auto.setEnabled(fps_on)
        self.fps_manual.setEnabled(fps_on)
        fps_man = fps_on and self.fps_manual.isChecked()
        self.fps_val.setEnabled(fps_man)

        # Trim
        trim_on = self.trim_enable.isChecked()
        self.trim_auto.setEnabled(trim_on)
        self.trim_manual.setEnabled(trim_on)
        trim_man = trim_on and self.trim_manual.isChecked()
        self.trim_start.setEnabled(trim_man)
        self.trim_dur.setEnabled(trim_man)

        # Color
        color_on = self.color_enable.isChecked()
        self.color_auto.setEnabled(color_on)
        self.color_manual.setEnabled(color_on)
        color_man = color_on and self.color_manual.isChecked()
        for sp in (self.col_contrast, self.col_bright, self.col_gamma, self.col_sat):
            sp.setEnabled(color_man)

        # Audio
        aud_on = self.audio_enable.isChecked()
        self.cb_enf_notch.setEnabled(aud_on)
        self.cb_micro_pitch.setEnabled(aud_on)

        # Codec
        codec_man = self.combo_codec_mode.currentIndex() == 1
        self.combo_codec.setEnabled(codec_man)

        # Quality
        q_idx = self.combo_q_mode.currentIndex()
        self.spin_crf.setVisible(q_idx in (0, 1))
        self.spin_crf.setEnabled(q_idx == 1)
        self.spin_bitrate.setVisible(q_idx == 2)
        self.spin_bitrate.setEnabled(q_idx == 2)

    def set_video_info(self, info: Optional[VideoInfo]):
        self.video_info = info
        if info:
            v = info.video
            if v:
                self.resize_w.setValue(v.width if v.width > 0 else 1920)
                self.resize_h.setValue(v.height if v.height > 0 else 1080)
                self.fps_val.setValue(v.fps if v.fps > 0 else 30.0)
            if info.duration > 0:
                self.trim_dur.setValue(info.duration)
                self.lbl_trim_summary.setText(f"Timeline: 0.00s → {info.duration:.2f}s")

    def get_settings(self) -> ProcessingSettings:
        c_mode = "manual" if self.combo_codec_mode.currentIndex() == 1 else "auto"
        c_text = self.combo_codec.currentText()
        if "HEVC" in c_text or "x265" in c_text:
            codec = "hevc"
        elif "AV1" in c_text:
            codec = "av1"
        else:
            codec = "h264"

        q_idx = self.combo_q_mode.currentIndex()
        q_mode = "auto" if q_idx == 0 else ("crf" if q_idx == 1 else "bitrate")

        return ProcessingSettings(
            preset_name=self.combo_presets.currentText(),
            crop=CropSettings(
                enabled=self.crop_enable.isChecked(),
                mode="auto" if self.crop_auto.isChecked() else "manual",
                asymmetric=True,
                left=self.crop_left.value(),
                right=self.crop_right.value(),
                top=self.crop_top.value(),
                bottom=self.crop_bottom.value(),
            ),
            resize=ResizeSettings(
                enabled=self.resize_enable.isChecked(),
                mode="auto" if self.resize_auto.isChecked() else "manual",
                width=self.resize_w.value(),
                height=self.resize_h.value(),
                maintain_aspect=self.resize_aspect.isChecked(),
            ),
            fps=FpsSettings(
                enabled=self.fps_enable.isChecked(),
                mode="auto" if self.fps_auto.isChecked() else "manual",
                fps=self.fps_val.value(),
            ),
            trim=TrimSettings(
                enabled=self.trim_enable.isChecked(),
                mode="auto" if self.trim_auto.isChecked() else "manual",
                start=self.trim_start.value(),
                duration=self.trim_dur.value(),
            ),
            noise=self.noise_widget.get_settings(),
            color=ColorSettings(
                enabled=self.color_enable.isChecked(),
                mode="auto" if self.color_auto.isChecked() else "manual",
                contrast=self.col_contrast.value(),
                brightness=self.col_bright.value(),
                gamma=self.col_gamma.value(),
                saturation=self.col_sat.value(),
            ),
            audio_privacy=AudioPrivacySettings(
                enabled=self.audio_enable.isChecked(),
                mode="auto",
                enf_notch=self.cb_enf_notch.isChecked(),
                micro_pitch=self.cb_micro_pitch.isChecked(),
                pitch_ratio=0.99,
            ),
            quantization=QuantizationSettings(
                forced_gop=self.cb_forced_gop.isChecked(),
                gop_size=48,
                scene_change_threshold=0,
                normalize_timestamps=True,
                epoch_zero=self.cb_epoch_zero.isChecked(),
                bitexact=True,
            ),
            codec=CodecSettings(mode=c_mode, codec=codec),
            quality=QualitySettings(
                mode=q_mode,
                crf=self.spin_crf.value(),
                bitrate_kbps=self.spin_bitrate.value(),
            ),
            privacy=PrivacySettings(
                remove_metadata=self.cb_priv_meta.isChecked(),
                remove_comments=self.cb_priv_comm.isChecked(),
                remove_chapters=self.cb_priv_chap.isChecked(),
                remove_attachments=self.cb_priv_chap.isChecked(),
                scrub_after_encoding=self.cb_priv_scrub.isChecked(),
                verify_output=self.cb_priv_verify.isChecked(),
            ),
            quality_gate=VisualBudgetPolicy(
                enabled=self.cb_qg_enable.isChecked(),
                enforce_strict=self.cb_qg_strict.isChecked(),
                policy_budget=0.05,
                ssim_mean_min=self.spin_qg_ssim.value(),
                psnr_mean_min_db=self.spin_qg_psnr.value(),
            ),
        )

    def set_settings(self, settings: ProcessingSettings):
        self._updating_ui = True
        try:
            # Crop
            self.crop_enable.setChecked(settings.crop.enabled)
            if settings.crop.mode == "auto":
                self.crop_auto.setChecked(True)
            else:
                self.crop_manual.setChecked(True)
            self.crop_left.setValue(settings.crop.left)
            self.crop_right.setValue(settings.crop.right)
            self.crop_top.setValue(settings.crop.top)
            self.crop_bottom.setValue(settings.crop.bottom)

            # Resize
            self.resize_enable.setChecked(settings.resize.enabled)
            if settings.resize.mode == "auto":
                self.resize_auto.setChecked(True)
            else:
                self.resize_manual.setChecked(True)
            if settings.resize.width > 0:
                self.resize_w.setValue(settings.resize.width)
            if settings.resize.height > 0:
                self.resize_h.setValue(settings.resize.height)
            self.resize_aspect.setChecked(settings.resize.maintain_aspect)

            # FPS
            self.fps_enable.setChecked(settings.fps.enabled)
            if settings.fps.mode == "auto":
                self.fps_auto.setChecked(True)
            else:
                self.fps_manual.setChecked(True)
            if settings.fps.fps > 0:
                self.fps_val.setValue(settings.fps.fps)

            # Trim
            self.trim_enable.setChecked(settings.trim.enabled)
            if settings.trim.mode == "auto":
                self.trim_auto.setChecked(True)
            else:
                self.trim_manual.setChecked(True)
            self.trim_start.setValue(settings.trim.start)
            if settings.trim.duration:
                self.trim_dur.setValue(settings.trim.duration)

            # Noise
            self.noise_widget.set_settings(settings.noise)

            # Color
            col = getattr(settings, "color", ColorSettings())
            self.color_enable.setChecked(col.enabled)
            if col.mode == "auto":
                self.color_auto.setChecked(True)
            else:
                self.color_manual.setChecked(True)
            self.col_contrast.setValue(col.contrast)
            self.col_bright.setValue(col.brightness)
            self.col_gamma.setValue(col.gamma)
            self.col_sat.setValue(col.saturation)

            # Audio
            aud = getattr(settings, "audio_privacy", AudioPrivacySettings())
            self.audio_enable.setChecked(aud.enabled)
            self.cb_enf_notch.setChecked(aud.enf_notch)
            self.cb_micro_pitch.setChecked(aud.micro_pitch)

            # Quantization
            qz = getattr(settings, "quantization", QuantizationSettings())
            self.cb_forced_gop.setChecked(qz.forced_gop)
            self.cb_epoch_zero.setChecked(qz.epoch_zero)

            # Codec
            self.combo_codec_mode.setCurrentIndex(1 if settings.codec.mode == "manual" else 0)
            if settings.codec.codec == "hevc":
                self.combo_codec.setCurrentIndex(1)
            elif settings.codec.codec == "av1":
                self.combo_codec.setCurrentIndex(2)
            else:
                self.combo_codec.setCurrentIndex(0)

            # Quality
            if settings.quality.mode == "crf":
                self.combo_q_mode.setCurrentIndex(1)
            elif settings.quality.mode == "bitrate":
                self.combo_q_mode.setCurrentIndex(2)
            else:
                self.combo_q_mode.setCurrentIndex(0)
            self.spin_crf.setValue(settings.quality.crf)
            self.spin_bitrate.setValue(settings.quality.bitrate_kbps)

            # Privacy
            self.cb_priv_meta.setChecked(settings.privacy.remove_metadata)
            self.cb_priv_comm.setChecked(settings.privacy.remove_comments)
            self.cb_priv_chap.setChecked(settings.privacy.remove_chapters)
            self.cb_priv_scrub.setChecked(settings.privacy.scrub_after_encoding)
            self.cb_priv_verify.setChecked(settings.privacy.verify_output)

            # Quality Gate
            qg = getattr(settings, "quality_gate", VisualBudgetPolicy())
            self.cb_qg_enable.setChecked(qg.enabled)
            self.cb_qg_strict.setChecked(qg.enforce_strict)
            self.spin_qg_ssim.setValue(qg.ssim_mean_min)
            self.spin_qg_psnr.setValue(qg.psnr_mean_min_db)

        finally:
            self._updating_ui = False
            self._update_all_states()
