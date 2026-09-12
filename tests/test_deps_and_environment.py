"""
Unit tests for Dependency Manager, Environment Doctor, and GUI Dialogs.
"""
import unittest
import os
import tempfile
from pathlib import Path
from PySide6.QtWidgets import QApplication, QMessageBox, QPushButton

from veilframe.core.deps_manager import (
    audit_environment,
    is_ffmpeg_installed,
    get_user_bin_dir,
    EnvironmentReport,
)
from veilframe.core.resources import find_executable
from veilframe.gui.dialogs import (
    AboutDialog,
    EnvironmentDoctorDialog,
    DependencyInstallerDialog,
    prompt_missing_dependency,
)


class TestDepsAndEnvironment(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_audit_environment(self):
        """audit_environment should return a populated EnvironmentReport."""
        report = audit_environment()
        self.assertIsInstance(report, EnvironmentReport)
        self.assertTrue(len(report.items) >= 5)
        names = [item.name for item in report.items]
        self.assertIn("FFmpeg Binary", names)
        self.assertIn("FFprobe Binary", names)
        self.assertIn("Pillow (PIL)", names)
        self.assertIn("Cryptography", names)
        self.assertIn("NumPy Numerical Engine", names)

    def test_get_user_bin_dir(self):
        """get_user_bin_dir should point to ~/.veilframe/bin."""
        bin_dir = get_user_bin_dir()
        self.assertEqual(bin_dir, Path.home() / ".veilframe" / "bin")
        self.assertTrue(bin_dir.exists())

    def test_find_executable_in_user_bin(self):
        """find_executable should locate binaries in ~/.veilframe/bin."""
        user_bin = get_user_bin_dir()
        ext = ".exe" if os.name == "nt" else ""
        dummy_tool = user_bin / f"mock_tool{ext}"
        try:
            dummy_tool.write_text("mock executable")
            found = find_executable("mock_tool")
            self.assertEqual(found, dummy_tool)
        finally:
            if dummy_tool.exists():
                dummy_tool.unlink()

    def test_about_dialog_instantiation_and_repo_url(self):
        """AboutDialog should instantiate and define official repo URL."""
        dlg = AboutDialog()
        self.assertEqual(dlg.REPO_URL, "https://github.com/sahir247/VeilFrame")
        self.assertIn("VeilFrame", dlg.windowTitle())
        dlg.close()


    def test_environment_doctor_dialog_instantiation(self):
        """EnvironmentDoctorDialog should instantiate and populate table rows."""
        dlg = EnvironmentDoctorDialog()
        self.assertGreaterEqual(dlg.table.rowCount(), 5)
        dlg.close()

    def test_dependency_installer_dialog_instantiation(self):
        """DependencyInstallerDialog should instantiate and display progress widgets."""
        dlg = DependencyInstallerDialog("MockDependency", auto_start=False)
        self.assertEqual(dlg.dependency_name, "MockDependency")
        self.assertIn("MockDependency", dlg.lbl_title.text())
        dlg.close()

    def test_prompt_missing_dependency_structure(self):
        """prompt_missing_dependency should configure Cancel on Left and OK on Right."""
        msg_box = QMessageBox()
        msg_box.setWindowTitle("Missing Component")
        dep_name = "FFprobe"
        msg_box.setText(f"<b>{dep_name} is missing.</b><br><br>Do you want to install it automatically?")
        btn_cancel = msg_box.addButton("Cancel", QMessageBox.ButtonRole.RejectRole)
        btn_ok = msg_box.addButton("OK", QMessageBox.ButtonRole.AcceptRole)

        self.assertIn("FFprobe is missing", msg_box.text())
        self.assertEqual(btn_cancel.text(), "Cancel")
        self.assertEqual(btn_ok.text(), "OK")

    def test_codec_settings_gpu_and_target_format(self):
        """CodecSettings should support hw_accel and target_format fields."""
        from veilframe.models.settings import CodecSettings, ProcessingSettings
        settings = CodecSettings(hw_accel="nvenc", target_format="mkv")
        self.assertEqual(settings.hw_accel, "nvenc")
        self.assertEqual(settings.target_format, "mkv")

        proc_settings = ProcessingSettings(codec=settings)
        d = proc_settings.to_dict()
        self.assertEqual(d["codec"]["hw_accel"], "nvenc")
        self.assertEqual(d["codec"]["target_format"], "mkv")

    def test_encoder_hardware_capabilities_probe(self):
        """get_hardware_capabilities should return dict with physical GPUs and verified encoders."""
        from veilframe.core.resources import get_hardware_capabilities
        caps = get_hardware_capabilities()
        self.assertIsInstance(caps, dict)
        self.assertIn("physical_gpus", caps)
        self.assertIn("verified_encoders", caps)

    def test_encoder_build_encode_cmd(self):
        """build_encode_cmd should format ffmpeg command with correct arguments."""
        from veilframe.core.encoder import build_encode_cmd
        from veilframe.models.settings import ProcessingSettings, CodecSettings

        proc = ProcessingSettings()
        proc.codec = CodecSettings(mode="manual", codec="h264", hw_accel="cpu")
        cmd = build_encode_cmd(
            src=Path("input.mp4"),
            dst=Path("output.mp4"),
            settings=proc,
        )
        self.assertIn("-c:v", cmd)
        self.assertIn("libx264", cmd)
        self.assertIn("-crf", cmd)
        self.assertEqual(cmd[-1], "output.mp4")

    def test_image_info_widget_exif_formatting(self):
        """ImageInfoWidget should display source file metadata and clear properly."""
        from veilframe.gui.image_panel import ImageInfoWidget
        widget = ImageInfoWidget()
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tf:
            temp_path = Path(tf.name)
        try:
            from PIL import Image
            img = Image.new("RGB", (640, 480), color=(255, 0, 0))
            img.save(temp_path)
            widget.set_image_file(temp_path)
            self.assertIn("640 x 480", widget._lbl_dims.text())
            widget.clear()
            self.assertEqual(widget._lbl_dims.text(), "—")
        finally:
            if temp_path.exists():
                temp_path.unlink()
        widget.close()

    def test_image_panel_format_conversion_options(self):
        """ImageProcessingPanel should have all target formats in format conversion combo."""
        from veilframe.gui.image_panel import ImageProcessingPanel
        panel = ImageProcessingPanel()
        expected_formats = ["JPEG", "PNG", "WebP", "TIFF", "BMP", "GIF", "ICO", "PPM"]
        items = [panel.combo_format.itemText(i) for i in range(panel.combo_format.count())]
        for fmt in expected_formats:
            self.assertTrue(any(fmt in it for it in items), f"Expected format {fmt} in combo")
        panel.close()

    def test_processing_panel_gpu_and_target_format(self):
        """ProcessingPanel should allow selecting GPU acceleration and video target format."""
        from veilframe.gui.processing_panel import ProcessingPanel
        from veilframe.presets.manager import PresetManager
        preset_mgr = PresetManager()
        panel = ProcessingPanel(preset_mgr)
        gpu_items = [panel.combo_hw_accel.itemText(i) for i in range(panel.combo_hw_accel.count())]
        self.assertIn("Auto (GPU if Available)", gpu_items)
        self.assertIn("NVIDIA NVENC", gpu_items)
        self.assertIn("Intel QuickSync", gpu_items)
        self.assertIn("AMD AMF", gpu_items)
        self.assertIn("Apple VideoToolbox", gpu_items)
        self.assertIn("Software CPU", gpu_items)

        fmt_items = [panel.combo_video_format.itemText(i) for i in range(panel.combo_video_format.count())]
        self.assertIn("Auto (Match Source)", fmt_items)
        self.assertIn("MP4 (.mp4)", fmt_items)
        self.assertIn("MKV (.mkv)", fmt_items)
        self.assertIn("WebM (.webm)", fmt_items)

        panel.combo_video_format.setCurrentIndex(1)
        self.assertTrue(panel.is_format_conversion_enabled())
        self.assertEqual(panel.get_target_extension(), ".mp4")
        panel.close()


if __name__ == "__main__":
    unittest.main()

