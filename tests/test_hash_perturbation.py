"""
Unit tests for Transform-Domain (DCT) Perceptual Hash Perturbation Engine.
"""
import unittest
import numpy as np

from veilframe.core.hash_perturbation import (
    perturb_luminance_dct,
    perturb_rgb_frame_dct,
    _dct_2d,
    _idct_2d,
)


class TestHashPerturbationEngine(unittest.TestCase):
    def setUp(self):
        # 64x64 test gradient
        x = np.linspace(0, 1.0, 64, dtype=np.float32)
        y = np.linspace(0, 1.0, 64, dtype=np.float32)
        xx, yy = np.meshgrid(x, y)
        self.sample_luma = (xx + yy) / 2.0
        self.sample_rgb = np.stack([self.sample_luma * 255.0] * 3, axis=-1)

    def test_dct_idct_invertibility(self):
        block = self.sample_luma[:32, :32].astype(np.float64)
        dct = _dct_2d(block)
        recon = _idct_2d(dct)
        np.testing.assert_allclose(recon, block, atol=1e-5)

    def test_luminance_perturbation_boundedness(self):
        perturbed = perturb_luminance_dct(self.sample_luma, target_flips=8, max_epsilon=0.02, block_size=32, seed=99)
        self.assertEqual(perturbed.shape, self.sample_luma.shape)
        # Delta should be strictly bounded by max_epsilon
        delta = np.abs(perturbed - self.sample_luma)
        self.assertLessEqual(float(np.max(delta)), 0.05)
        # Mean delta should be subtle but non-zero
        self.assertGreater(float(np.mean(delta)), 0.0)

    def test_rgb_frame_perturbation(self):
        perturbed_rgb = perturb_rgb_frame_dct(self.sample_rgb, target_flips=10, max_epsilon=0.02, seed=100)
        self.assertEqual(perturbed_rgb.shape, self.sample_rgb.shape)
        # Range should remain strictly in [0, 255]
        self.assertGreaterEqual(float(np.min(perturbed_rgb)), 0.0)
        self.assertLessEqual(float(np.max(perturbed_rgb)), 255.0)


if __name__ == "__main__":
    unittest.main()
