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



if __name__ == "__main__":
    unittest.main()
