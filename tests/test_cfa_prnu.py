"""
Unit tests for Bayer CFA Mosaic-Aware PRNU Engine.
"""
import unittest
import numpy as np

from veilframe.core.cfa_prnu import (
    bayer_mosaic,
    generate_synthetic_prnu,
    inject_cfa_prnu,
    demosaic_reconstruction,
    apply_cfa_prnu_pipeline,
)


class TestCfaPrnuEngine(unittest.TestCase):
    def setUp(self):
        self.h, self.w = 64, 64
        # Synthetic RGB gradient
        x = np.linspace(0, 255, self.w, dtype=np.float32)
        y = np.linspace(0, 255, self.h, dtype=np.float32)
        xx, yy = np.meshgrid(x, y)
        self.sample_rgb = np.stack([xx, yy, (xx + yy) / 2.0], axis=-1)

    def test_bayer_mosaic_dimensions_and_mask(self):
        raw, mask = bayer_mosaic(self.sample_rgb, pattern="RGGB")
        self.assertEqual(raw.shape, (self.h, self.w))
        self.assertEqual(mask.shape, (self.h, self.w, 3))
        # RGGB layout check: (0,0) is Red, (0,1) is Green
        self.assertEqual(mask[0, 0, 0], 1.0)
        self.assertEqual(mask[0, 1, 1], 1.0)
        self.assertEqual(mask[1, 0, 1], 1.0)
        self.assertEqual(mask[1, 1, 2], 1.0)

    def test_synthetic_prnu_generation(self):
        prnu = generate_synthetic_prnu(self.h, self.w, sigma_r=0.015, sigma_g=0.008, sigma_b=0.012, pattern="RGGB", seed=42)
        self.assertEqual(prnu.shape, (self.h, self.w))
        # Zero-mean check
        self.assertAlmostEqual(float(np.mean(prnu)), 0.0, places=4)
        # Red channel variance should be higher than green channel
        var_r = float(np.var(prnu[0::2, 0::2]))
        var_g = float(np.var(prnu[0::2, 1::2]))
        self.assertGreater(var_r, var_g)

    def test_saturation_clamping(self):
        # Pure black (0) and pure white (255)
        flat_black = np.zeros((32, 32), dtype=np.float32)
        flat_white = np.full((32, 32), 255.0, dtype=np.float32)
        prnu = np.ones((32, 32), dtype=np.float32) * 0.05

        inj_black = inject_cfa_prnu(flat_black, prnu, beta=1.0, gamma=0.6)
        inj_white = inject_cfa_prnu(flat_white, prnu, beta=1.0, gamma=0.6)

        # In black and highlight regions, noise injection is suppressed
        np.testing.assert_allclose(inj_black, 0.0, atol=1e-5)
        np.testing.assert_allclose(inj_white, 255.0, atol=1e-5)

    def test_demosaic_reconstruction_fidelity(self):
        raw, _ = bayer_mosaic(self.sample_rgb, pattern="RGGB")
        recon = demosaic_reconstruction(raw, pattern="RGGB")
        self.assertEqual(recon.shape, (self.h, self.w, 3))
        # Demosaicing should recover original smooth gradient closely
        mse = float(np.mean((recon - self.sample_rgb) ** 2))
        self.assertLess(mse, 50.0)

    def test_apply_cfa_prnu_pipeline(self):
        perturbed = apply_cfa_prnu_pipeline(self.sample_rgb, pattern="RGGB", beta=1.0, seed=123)
        self.assertEqual(perturbed.shape, self.sample_rgb.shape)
        # Difference should be bounded and non-zero
        diff = np.abs(perturbed - self.sample_rgb)
        self.assertGreater(float(np.mean(diff)), 0.0)
        self.assertLess(float(np.max(diff)), 30.0)


if __name__ == "__main__":
    unittest.main()
