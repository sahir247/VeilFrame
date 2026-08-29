"""
Unit tests for VeilFrame Presets (5% and 10% Bounded Forensic Disruption).
"""
import unittest
from veilframe.presets.manager import PresetManager
from veilframe.models.settings import ProcessingSettings


class TestPresetManager(unittest.TestCase):
    def setUp(self):
        self.pm = PresetManager()

    def test_preset_names_include_5_and_10_percent(self):
        names = self.pm.get_preset_names()
        self.assertIn("5% Bounded Forensic Disruption", names)
        self.assertIn("10% Bounded Forensic Disruption", names)
        self.assertIn("Privacy Clean", names)
        self.assertIn("Custom", names)

    def test_apply_5_percent_preset(self):
        settings = self.pm.apply_preset("5% Bounded Forensic Disruption")
        self.assertEqual(settings.preset_name, "5% Bounded Forensic Disruption")
        self.assertTrue(settings.crop.enabled)
        self.assertEqual(settings.noise.strength, 8)
        self.assertEqual(settings.quality_gate.policy_budget, 0.05)
        self.assertEqual(settings.quality_gate.aggregate_ceiling_pct, 5.0)
        self.assertEqual(settings.quality_gate.spatial_ceiling_pct, 2.0)
        self.assertEqual(settings.quality_gate.temporal_ceiling_pct, 1.0)
        self.assertEqual(settings.quality_gate.ssim_mean_min, 0.95)
        self.assertEqual(settings.quality_gate.psnr_mean_min_db, 30.0)

    def test_apply_10_percent_preset(self):
        settings = self.pm.apply_preset("10% Bounded Forensic Disruption")
        self.assertEqual(settings.preset_name, "10% Bounded Forensic Disruption")
        self.assertTrue(settings.crop.enabled)
        self.assertEqual(settings.noise.strength, 16)
        self.assertEqual(settings.noise.prnu_mode, "cfa_mosaic")
        self.assertEqual(settings.noise.cfa_pattern, "RGGB")
        self.assertTrue(settings.noise.hash_perturbation_enabled)
        self.assertEqual(settings.quality_gate.policy_budget, 0.10)
        self.assertEqual(settings.quality_gate.aggregate_ceiling_pct, 10.0)
        self.assertEqual(settings.quality_gate.spatial_ceiling_pct, 4.0)
        self.assertEqual(settings.quality_gate.temporal_ceiling_pct, 2.0)
        self.assertEqual(settings.quality_gate.luma_ceiling_pct, 2.0)
        self.assertEqual(settings.quality_gate.chroma_ceiling_pct, 2.0)
        self.assertEqual(settings.quality_gate.frequency_ceiling_pct, 2.0)
        self.assertEqual(settings.quality_gate.ssim_mean_min, 0.90)
        self.assertEqual(settings.quality_gate.psnr_mean_min_db, 28.0)

    def test_preset_fuzzy_aliases(self):
        s_5 = self.pm.apply_preset("5%")
        self.assertEqual(s_5.preset_name, "5% Bounded Forensic Disruption")

        s_10 = self.pm.apply_preset("10%")
        self.assertEqual(s_10.preset_name, "10% Bounded Forensic Disruption")


if __name__ == "__main__":
    unittest.main()
