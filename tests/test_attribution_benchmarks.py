"""
Unit and invariant tests for the scientific attribution benchmark suite.
========================================================================
"""
import unittest
import numpy as np

from research.attribution_benchmarks.common.models import BenchmarkEnvironment
from research.attribution_benchmarks.common.statistics import (
    hamming_distance,
    bit_error_rate,
    pearson_correlation,
    welch_psd,
    compute_pce_and_ncc,
    compute_roc_and_auc,
)
from research.attribution_benchmarks.detectors.perceptual_hash import (
    compute_phash,
    compute_dhash,
    compute_ahash,
    compute_whash,
    evaluate_perceptual_hash_benchmark,
)
from research.attribution_benchmarks.detectors.enf import evaluate_enf_benchmark
from research.attribution_benchmarks.detectors.motion import evaluate_motion_benchmark
from research.attribution_benchmarks.detectors.prnu import (
    extract_noise_residual,
    estimate_camera_fingerprint,
    evaluate_prnu_pair_benchmark,
    evaluate_prnu_corpus_benchmark,
)
from research.attribution_benchmarks.datasets.corpus import (
    generate_synthetic_evaluation_corpus,
    generate_synthetic_audio_pair,
)


class TestAttributionBenchmarks(unittest.TestCase):
    def setUp(self):
        self.rng = np.random.default_rng(42)
        # Generate 10 identical grayscale frames
        self.identical_frames = [
            self.rng.uniform(0.1, 0.9, size=(60, 80)).astype(np.float32) for _ in range(10)
        ]

    def test_invariant_identical_perceptual_hashes(self):
        """Invariant: Identical frames must have exactly 0 Hamming distance and BER 0.0."""
        res = evaluate_perceptual_hash_benchmark(self.identical_frames, self.identical_frames, threshold=10)
        self.assertEqual(res.status, "success")

        # Layer 1: Signal Metrics
        self.assertEqual(res.signal_metrics.values["phash_hamming_mean"], 0.0)
        self.assertEqual(res.signal_metrics.values["phash_ber"], 0.0)
        self.assertEqual(res.signal_metrics.values["dhash_hamming_mean"], 0.0)
        self.assertEqual(res.signal_metrics.values["ahash_hamming_mean"], 0.0)
        self.assertEqual(res.signal_metrics.values["whash_hamming_mean"], 0.0)

        # Layer 2: Detector Metrics
        self.assertEqual(res.detector_metrics.match_status, "MATCH")
        self.assertEqual(res.detector_metrics.match_score, 0.0)

        # Layer 3: Attribution Metrics
        self.assertEqual(res.attribution_metrics.classification, "TRUE_POSITIVE")

    def test_invariant_identical_motion_correlation(self):
        """Invariant: Identical video sequences must have exact Pearson rho = 1.0."""
        res = evaluate_motion_benchmark(self.identical_frames, self.identical_frames, correlation_threshold=0.85)
        self.assertEqual(res.status, "success")

        # Layer 1: Signal Metrics
        self.assertAlmostEqual(res.signal_metrics.values["frame_delta_correlation_mean"], 1.0, places=5)
        self.assertAlmostEqual(res.signal_metrics.values["temporal_energy_ratio"], 1.0, places=5)

        # Layer 2: Detector Metrics
        self.assertEqual(res.detector_metrics.match_status, "MATCH")
        self.assertAlmostEqual(res.detector_metrics.match_score, 1.0, places=5)

    def test_invariant_identical_prnu_correlation(self):
        """Invariant: Identical noise residuals must achieve maximum PCE and NCC = 1.0."""
        res = evaluate_prnu_pair_benchmark(self.identical_frames, self.identical_frames, pce_threshold=60.0)
        self.assertEqual(res.status, "success")

        # Layer 1: Signal Metrics
        self.assertAlmostEqual(res.signal_metrics.values["transformed_ncc"], 1.0, places=4)
        self.assertAlmostEqual(res.signal_metrics.values["pce_attenuation_ratio"], 1.0, places=4)

        # Layer 2: Detector Metrics
        self.assertEqual(res.detector_metrics.match_status, "MATCH")

    def test_invariant_synthetic_enf_tone_detection_and_attenuation(self):
        """Invariant: Injected 50Hz tone is detected at 50Hz and attenuated by simulated notch filter."""
        ref_audio, trans_audio = generate_synthetic_audio_pair(
            duration_sec=3.0,
            sample_rate=1000,
            enf_freq=50.0,
            snr_db=6.0,
            apply_notch=True,
            notch_depth_db=30.0,
            seed=101,
        )

        res = evaluate_enf_benchmark(ref_audio, trans_audio, sample_rate=1000, target_harmonics=[50.0, 60.0])
        self.assertEqual(res.status, "success")

        h50 = res.signal_metrics.values["harmonics"]["50Hz"]
        # Injected frequency peak should match nominal 50Hz within resolution
        self.assertAlmostEqual(h50["ref_peak_freq_hz"], 50.0, delta=0.5)

        # Attenuation should be >= 20 dB
        self.assertGreaterEqual(h50["attenuation_delta_db"], 20.0)
        self.assertGreater(h50["ref_snr_db"], h50["trans_snr_db"])

        # Transformed hum should be suppressed
        self.assertEqual(res.detector_metrics.match_status, "HUM_SUPPRESSED")

    def test_invariant_prnu_multi_camera_corpus_roc(self):
        """Invariant: Multi-camera corpus correctly separates same-camera from cross-camera with high AUC."""
        corpus = generate_synthetic_evaluation_corpus(num_cameras=3, num_frames=15, seed=42)
        res = evaluate_prnu_corpus_benchmark(corpus, pce_threshold=60.0)

        self.assertEqual(res.status, "success")
        self.assertIn("before_transformation", res.attribution_metrics.summary)
        self.assertIn("after_transformation", res.attribution_metrics.summary)

        # Same-camera PCE before perturbation should exceed cross-camera PCE
        sig = res.signal_metrics.values
        self.assertGreater(sig["mean_same_pce_before"], sig["mean_diff_pce_before"])

        # AUC before perturbation should be high (>= 0.85) on synthetic distinct sensors
        summary = res.attribution_metrics.summary
        self.assertGreaterEqual(summary["before_transformation"]["auc"], 0.85)

    def test_invariant_unavailable_inputs_return_proper_status(self):
        """Invariant: Missing/empty inputs return 'unavailable' status rather than artificial zeros."""
        res_phash = evaluate_perceptual_hash_benchmark([], [])
        self.assertEqual(res_phash.status, "unavailable")
        self.assertEqual(res_phash.detector_metrics.match_status, "UNAVAILABLE")

        res_enf = evaluate_enf_benchmark(np.array([]), np.array([]))
        self.assertEqual(res_enf.status, "unavailable")

        res_motion = evaluate_motion_benchmark([self.identical_frames[0]], [self.identical_frames[0]])
        self.assertEqual(res_motion.status, "unavailable")

        res_prnu = evaluate_prnu_pair_benchmark([], [])
        self.assertEqual(res_prnu.status, "unavailable")

    def test_schema_serialization_integrity(self):
        """Ensure 3-layer output schema serializes cleanly to dict/JSON without exceptions."""
        res = evaluate_perceptual_hash_benchmark(self.identical_frames, self.identical_frames)
        d = res.to_dict()

        self.assertIn("benchmark", d)
        self.assertIn("signal_metrics", d)
        self.assertIn("detector_metrics", d)
        self.assertIn("attribution_metrics", d)
        self.assertIn("environment", d)
        self.assertEqual(d["signal_metrics"]["name"], "perceptual_hash_distances")


if __name__ == "__main__":
    unittest.main()
