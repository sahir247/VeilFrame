"""
Unit tests for VeilFrame GUI Custom Controls, Wheel UX Behaviors, and Section Reset Actions.
"""
import unittest
from PySide6.QtWidgets import QApplication, QWidget
from PySide6.QtCore import Qt, QPoint, QPointF
from PySide6.QtGui import QWheelEvent

from veilframe.gui.controls import (
    NoWheelComboBox,
    FocusWheelSpinBox,
    FocusWheelDoubleSpinBox,
    FocusWheelSlider,
    UXWheelEventFilter,
    create_section_reset_button,
)
from veilframe.gui.processing_panel import ProcessingPanel
from veilframe.gui.noise_control import NoiseControlWidget
from veilframe.presets.manager import PresetManager
from veilframe.models.video_info import VideoInfo, VideoStreamInfo


class TestGUIControlsAndUX(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def _create_wheel_event(self, delta: int = 120) -> QWheelEvent:
        return QWheelEvent(
            QPointF(10, 10),
            QPointF(10, 10),
            QPoint(0, delta),
            QPoint(0, delta),
            Qt.NoButton,
            Qt.NoModifier,
            Qt.ScrollUpdate,
            False,
        )

    def test_no_wheel_combobox_ignores_wheel(self):
        """Dropdown should NOT change selection when mouse wheel is scrolled."""
        combo = NoWheelComboBox()
        combo.addItems(["Option A", "Option B", "Option C"])
        combo.setCurrentIndex(0)

        event = self._create_wheel_event(-120)
        combo.wheelEvent(event)

        # Selection should remain Option A and event ignored
        self.assertEqual(combo.currentIndex(), 0)
        self.assertFalse(event.isAccepted())

    def test_focus_wheel_spinbox_requires_focus(self):
        """Numerical spinbox should ignore wheel when unfocused and accept when focused."""
        window = QWidget()
        spin = FocusWheelSpinBox(window)
        spin.setRange(0, 100)
        spin.setValue(50)
        window.show()
        QApplication.processEvents()

        # 1. Unfocused -> Ignore wheel
        spin.clearFocus()
        event_unfocused = self._create_wheel_event(120)
        spin.wheelEvent(event_unfocused)
        self.assertEqual(spin.value(), 50)
        self.assertFalse(event_unfocused.isAccepted())

        # 2. Focused -> Accept wheel and change value
        spin.setFocus()
        QApplication.processEvents()
        self.assertTrue(spin.hasFocus())
        event_focused = self._create_wheel_event(120)
        spin.wheelEvent(event_focused)
        self.assertEqual(spin.value(), 51)
        self.assertTrue(event_focused.isAccepted())
        window.close()

    def test_focus_wheel_double_spinbox_requires_focus(self):
        """Numerical double spinbox should ignore wheel when unfocused and accept when focused."""
        window = QWidget()
        dspin = FocusWheelDoubleSpinBox(window)
        dspin.setRange(0.0, 100.0)
        dspin.setValue(30.0)
        window.show()
        QApplication.processEvents()

        # 1. Unfocused -> Ignore wheel
        dspin.clearFocus()
        event_unfocused = self._create_wheel_event(120)
        dspin.wheelEvent(event_unfocused)
        self.assertEqual(dspin.value(), 30.0)
        self.assertFalse(event_unfocused.isAccepted())

        # 2. Focused -> Accept wheel and change value
        dspin.setFocus()
        QApplication.processEvents()
        self.assertTrue(dspin.hasFocus())
        event_focused = self._create_wheel_event(120)
        dspin.wheelEvent(event_focused)
        self.assertGreater(dspin.value(), 30.0)
        self.assertTrue(event_focused.isAccepted())
        window.close()

    def test_focus_wheel_slider_requires_focus(self):
        """Slider should ignore wheel when unfocused and accept when focused."""
        window = QWidget()
        slider = FocusWheelSlider(Qt.Horizontal, window)
        slider.setRange(0, 100)
        slider.setValue(10)
        window.show()
        QApplication.processEvents()

        # 1. Unfocused -> Ignore wheel
        slider.clearFocus()
        event_unfocused = self._create_wheel_event(120)
        slider.wheelEvent(event_unfocused)
        self.assertEqual(slider.value(), 10)
        self.assertFalse(event_unfocused.isAccepted())

        # 2. Focused -> Accept wheel
        slider.setFocus()
        QApplication.processEvents()
        self.assertTrue(slider.hasFocus())
        event_focused = self._create_wheel_event(120)
        slider.wheelEvent(event_focused)
        self.assertGreater(slider.value(), 10)
        self.assertTrue(event_focused.isAccepted())
        window.close()

    def test_ux_wheel_event_filter(self):
        """Event filter should intercept and prevent unfocused changes across standard Qt controls."""
        filter_obj = UXWheelEventFilter()
        spin = FocusWheelSpinBox()
        spin.setValue(20)
        spin.clearFocus()

        event = self._create_wheel_event(120)
        handled = filter_obj.eventFilter(spin, event)
        self.assertTrue(handled)
        self.assertFalse(event.isAccepted())

    def test_processing_panel_section_resets(self):
        """Every section in ProcessingPanel should have an independent working reset button."""
        pm = PresetManager()
        panel = ProcessingPanel(pm)

        # Set fake video info
        info = VideoInfo(
            file_path="sample.mp4",
            size_bytes=1000,
            duration=45.5,
            video=VideoStreamInfo(
                codec="h264",
                width=1280,
                height=720,
                fps=24.0,
                duration=45.5,
            )
        )
        panel.set_video_info(info)

        # 1. Test Crop Reset
        panel.crop_enable.setChecked(False)
        panel.crop_manual.setChecked(True)
        panel.crop_left.setValue(150)
        panel._reset_crop_section()
        self.assertTrue(panel.crop_enable.isChecked())
        self.assertTrue(panel.crop_auto.isChecked())
        self.assertEqual(panel.crop_left.value(), 0)

        # 2. Test Resize Reset
        panel.resize_w.setValue(3840)
        panel.resize_h.setValue(2160)
        panel._reset_resize_section()
        self.assertEqual(panel.resize_w.value(), 1280)
        self.assertEqual(panel.resize_h.value(), 720)

        # 3. Test FPS Reset
        panel.fps_val.setValue(60.0)
        panel._reset_fps_section()
        self.assertEqual(panel.fps_val.value(), 24.0)

        # 4. Test Trim Reset
        panel.trim_enable.setChecked(True)
        panel.trim_start.setValue(10.0)
        panel.trim_dur.setValue(5.0)
        panel._reset_trim_section()
        self.assertFalse(panel.trim_enable.isChecked())
        self.assertEqual(panel.trim_start.value(), 0.0)
        self.assertEqual(panel.trim_dur.value(), 45.5)

        # 5. Test Color Reset
        panel.col_contrast.setValue(1.8)
        panel.col_bright.setValue(0.4)
        panel._reset_color_section()
        self.assertEqual(panel.col_contrast.value(), 1.015)
        self.assertEqual(panel.col_bright.value(), 0.005)

        # 6. Test Audio Reset
        panel.audio_enable.setChecked(False)
        panel.cb_enf_notch.setChecked(False)
        panel._reset_audio_section()
        self.assertTrue(panel.audio_enable.isChecked())
        self.assertTrue(panel.cb_enf_notch.isChecked())

        # 7. Test Quantization & Codec Reset
        panel.combo_codec_mode.setCurrentIndex(1)
        panel.combo_codec.setCurrentIndex(2)
        panel.spin_crf.setValue(35)
        panel._reset_quant_section()
        self.assertEqual(panel.combo_codec_mode.currentIndex(), 0)
        self.assertEqual(panel.combo_codec.currentIndex(), 0)
        self.assertEqual(panel.spin_crf.value(), 21)

        # 8. Test Privacy Reset
        panel.cb_priv_meta.setChecked(False)
        panel.cb_priv_chap.setChecked(False)
        panel._reset_privacy_section()
        self.assertTrue(panel.cb_priv_meta.isChecked())
        self.assertTrue(panel.cb_priv_chap.isChecked())

        # 9. Test Quality Gate Reset
        panel.cb_qg_enable.setChecked(False)
        panel.cb_qg_strict.setChecked(True)
        panel.spin_qg_ssim.setValue(0.70)
        panel.spin_qg_psnr.setValue(15.0)
        panel._reset_quality_gate_section()
        self.assertTrue(panel.cb_qg_enable.isChecked())
        self.assertFalse(panel.cb_qg_strict.isChecked())
        self.assertEqual(panel.spin_qg_ssim.value(), 0.95)
        self.assertEqual(panel.spin_qg_psnr.value(), 30.0)

    def test_noise_widget_section_reset(self):
        """NoiseControlWidget reset button should restore subtle default state."""
        widget = NoiseControlWidget()
        widget.cb_enable.setChecked(True)
        widget.rb_manual.setChecked(True)
        widget.slider.setValue(45)

        widget._reset_section()
        self.assertFalse(widget.cb_enable.isChecked())
        self.assertTrue(widget.rb_auto.isChecked())
        self.assertEqual(widget.slider.value(), 1)


if __name__ == "__main__":
    unittest.main()
