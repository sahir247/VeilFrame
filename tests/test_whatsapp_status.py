"""
Unit tests for VeilFrame WhatsApp Status Media Pipeline and Rate Control.
"""
import unittest
from veilframe.presets.manager import PresetManager
from veilframe.core.media_compressor import calculate_whatsapp_status_bufsize


class TestWhatsappStatusPipeline(unittest.TestCase):
    def setUp(self):
        self.pm = PresetManager()

    def test_whatsapp_status_preset_loaded(self):
        names = self.pm.get_preset_names()
        self.assertIn("WhatsApp Status", names)
        settings = self.pm.apply_preset("WhatsApp Status")
        self.assertEqual(settings.preset_name, "WhatsApp Status")
        self.assertEqual(settings.fps.fps, 29.97)
        self.assertEqual(settings.quality.crf, 23)

    def test_calculate_whatsapp_status_bufsize_values(self):
        # HD 720p base maxrate = 1900k
        self.assertEqual(calculate_whatsapp_status_bufsize(1900, 4.0), 950)
        self.assertEqual(calculate_whatsapp_status_bufsize(1900, 8.0), 1266)
        self.assertEqual(calculate_whatsapp_status_bufsize(1900, 14.0), 1900)
        self.assertEqual(calculate_whatsapp_status_bufsize(1900, 20.0), 2850)

        # FHD 1080p base maxrate = 3800k
        self.assertEqual(calculate_whatsapp_status_bufsize(3800, 4.0), 1900)
        self.assertEqual(calculate_whatsapp_status_bufsize(3800, 8.0), 2533)
        self.assertEqual(calculate_whatsapp_status_bufsize(3800, 14.0), 3800)
        self.assertEqual(calculate_whatsapp_status_bufsize(3800, 20.0), 5700)

    def test_calculate_whatsapp_status_bufsize_boundaries(self):
        base = 1900
        # 5.999 vs 6.000
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 5.999), 950)
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 6.000), 1266)

        # 10.999 vs 11.000
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 10.999), 1266)
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 11.000), 1900)

        # 15.999 vs 16.000
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 15.999), 1900)
        self.assertEqual(calculate_whatsapp_status_bufsize(base, 16.000), 2850)


if __name__ == "__main__":
    unittest.main()
