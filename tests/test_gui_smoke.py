"""
VeilFrame GUI & Qt Platform Smoke Test.
Verifies Qt platform plugin loading, PySide6 widgets, panels, and mode switching in offscreen mode.
"""
import os
import sys
import unittest

os.environ["QT_QPA_PLATFORM"] = "offscreen"


class TestGuiSmoke(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            from tests.conftest import get_or_create_test_qapp
            cls.app = get_or_create_test_qapp()
        except Exception as e:
            cls.app = None
            cls.init_error = e

    def setUp(self):
        if not getattr(self, "app", None):
            try:
                from tests.conftest import get_or_create_test_qapp
                self.app = get_or_create_test_qapp()
            except Exception as e:
                self.app = None
                self.init_error = e

    def test_gui_and_panels_smoke(self):
        if not self.app:
            self.skipTest(f"PySide6 QApplication unavailable: {getattr(self, 'init_error', 'unknown')}")

        from veilframe.gui.main_window import MainWindow
        window = MainWindow()
        window.show()
        self.assertIsNotNone(window)
        self.assertIsNotNone(window.folder_panel)
        self.assertIsNotNone(window.processing_panel)
        self.assertIsNotNone(window.image_processing_panel)

        # Verify 4-mode switcher state machine
        window._set_mode("ai")
        self.assertEqual(window.current_mode, "ai")
        self.assertFalse(window.folder_panel.isHidden())
        self.assertTrue(window.processing_panel.isHidden())
        self.assertTrue(window.image_processing_panel.isHidden())
        self.assertTrue(window.folder_panel._is_ai_mode)
        self.assertEqual(window.folder_panel.tabs.currentIndex(), 0)
        self.assertEqual(window.folder_panel.tabs.tabText(0), "AI Program Lister")

        window._set_mode("folder")
        self.assertEqual(window.current_mode, "folder")
        self.assertFalse(window.folder_panel.isHidden())
        self.assertTrue(window.processing_panel.isHidden())
        self.assertTrue(window.image_processing_panel.isHidden())
        self.assertFalse(window.folder_panel._is_ai_mode)
        self.assertEqual(window.folder_panel.tabs.tabText(0), "Directory Explorer")

        window._set_mode("image")
        self.assertEqual(window.current_mode, "image")
        self.assertTrue(window.folder_panel.isHidden())
        self.assertTrue(window.processing_panel.isHidden())
        self.assertFalse(window.image_processing_panel.isHidden())

        window._set_mode("video")
        self.assertEqual(window.current_mode, "video")
        self.assertTrue(window.folder_panel.isHidden())
        self.assertFalse(window.processing_panel.isHidden())
        self.assertTrue(window.image_processing_panel.isHidden())

        window.close()


if __name__ == "__main__":
    unittest.main()
