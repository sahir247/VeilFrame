"""
Perceptual Hash Attribution Benchmark Suite.
============================================

Evaluates perceptual hash stability and collision degradation across:
1. pHash: 2D Discrete Cosine Transform (DCT) low-frequency median hash.
2. dHash: Horizontal pixel gradient comparison hash.
3. aHash: Average luminance threshold hash.
4. wHash: 2D Haar Wavelet decomposition hash.

Outputs 3-layer metrics:
- SignalMetrics: Exact Hamming distance distribution and mean Bit Error Rate (BER).
- DetectorMetrics: Match score and decision status at threshold tau (default tau=10).
- AttributionMetrics: Pair-level matching accuracy and collision status.
"""
from typing import Dict, List, Optional, Tuple, Union
import numpy as np

from ..common.models import (
    BenchmarkEnvironment,
    SignalMetrics,
    DetectorMetrics,
    AttributionMetrics,
    BenchmarkResult,
)
from ..common.statistics import hamming_distance, bit_error_rate


def _resize_bilinear(img: np.ndarray, target_w: int, target_h: int) -> np.ndarray:
    """Fast, deterministic 2D bilinear interpolation."""
    src_h, src_w = img.shape
    if src_h == target_h and src_w == target_w:
        return img.copy()

    y_coords = (np.arange(target_h) + 0.5) * (src_h / target_h) - 0.5
    x_coords = (np.arange(target_w) + 0.5) * (src_w / target_w) - 0.5

    y_coords = np.clip(y_coords, 0, src_h - 1)
    x_coords = np.clip(x_coords, 0, src_w - 1)

    y0 = np.floor(y_coords).astype(int)
    y1 = np.clip(y0 + 1, 0, src_h - 1)
    x0 = np.floor(x_coords).astype(int)
    x1 = np.clip(x0 + 1, 0, src_w - 1)

    wy1 = y_coords - y0
    wy0 = 1.0 - wy1
    wx1 = x_coords - x0
    wx0 = 1.0 - wx1

    out = np.zeros((target_h, target_w), dtype=np.float64)
    for i in range(target_h):
        for j in range(target_w):
            v00 = img[y0[i], x0[j]]
            v01 = img[y0[i], x1[j]]
            v10 = img[y1[i], x0[j]]
            v11 = img[y1[i], x1[j]]
            val = (v00 * wx0[j] + v01 * wx1[j]) * wy0[i] + (v10 * wx0[j] + v11 * wx1[j]) * wy1[i]
            out[i, j] = val
    return out


def _dct_1d(x: np.ndarray) -> np.ndarray:
    """Type-II 1D DCT with orthogonal normalization."""
    N = len(x)
    n = np.arange(N)
    k = n.reshape((N, 1))
    weights = np.ones(N)
    weights[0] = 1.0 / np.sqrt(2.0)
    basis = np.cos((np.pi * (2 * n + 1) * k) / (2.0 * N))
    return np.sqrt(2.0 / N) * weights * np.dot(basis, x)


def _dct_2d(img: np.ndarray) -> np.ndarray:
    """2D Discrete Cosine Transform."""
    h, w = img.shape
    row_dct = np.zeros((h, w), dtype=np.float64)
    for i in range(h):
        row_dct[i, :] = _dct_1d(img[i, :])
    col_dct = np.zeros((h, w), dtype=np.float64)
    for j in range(w):
        col_dct[:, j] = _dct_1d(row_dct[:, j])
    return col_dct


def compute_phash(img: np.ndarray, hash_size: int = 8, highfreq_factor: int = 4) -> str:
    """Computes standard 64-bit DCT perceptual hash."""
    img_32 = _resize_bilinear(img, hash_size * highfreq_factor, hash_size * highfreq_factor)
    dct = _dct_2d(img_32)
    # Extract low frequency coefficients excluding DC (0,0)
    low_freq = dct[:hash_size, :hash_size]
    med = np.median(low_freq[1:, 1:]) if hash_size > 1 else np.median(low_freq)
    bits = (low_freq > med).flatten()
    int_val = 0
    for b in bits:
        int_val = (int_val << 1) | int(b)
    return f"{int_val:016x}"


def compute_dhash(img: np.ndarray, hash_size: int = 8) -> str:
    """Computes standard 64-bit gradient difference hash."""
    img_resized = _resize_bilinear(img, hash_size + 1, hash_size)
    diff = img_resized[:, 1:] > img_resized[:, :-1]
    int_val = 0
    for b in diff.flatten():
        int_val = (int_val << 1) | int(b)
    return f"{int_val:016x}"


def compute_ahash(img: np.ndarray, hash_size: int = 8) -> str:
    """Computes standard 64-bit average luminance hash."""
    img_resized = _resize_bilinear(img, hash_size, hash_size)
    avg = np.mean(img_resized)
    bits = (img_resized > avg).flatten()
    int_val = 0
    for b in bits:
        int_val = (int_val << 1) | int(b)
    return f"{int_val:016x}"


def compute_whash(img: np.ndarray, hash_size: int = 8) -> str:
    """Computes standard 64-bit 2D Haar wavelet approximation hash."""
    img_16 = _resize_bilinear(img, hash_size * 2, hash_size * 2)
    # 1-level 2D Haar wavelet approximation (LL subband)
    ll = (img_16[0::2, 0::2] + img_16[0::2, 1::2] + img_16[1::2, 0::2] + img_16[1::2, 1::2]) / 4.0
    med = np.median(ll)
    bits = (ll > med).flatten()
    int_val = 0
    for b in bits:
        int_val = (int_val << 1) | int(b)
    return f"{int_val:016x}"


def evaluate_perceptual_hash_benchmark(
    ref_frames: List[np.ndarray],
    trans_frames: List[np.ndarray],
    threshold: int = 10,
    env: Optional[BenchmarkEnvironment] = None,
) -> BenchmarkResult:
    """
    Evaluates perceptual hash distance distributions across frame pairs.
    """
    if env is None:
        env = BenchmarkEnvironment()

    if not ref_frames or not trans_frames:
        return BenchmarkResult(
            benchmark_name="perceptual_hash",
            benchmark_version="0.1.0",
            status="unavailable",
            signal_metrics=SignalMetrics("perceptual_hash_distances"),
            detector_metrics=DetectorMetrics("phash_detector", "DCT_64bit", 0.0, float(threshold), "UNAVAILABLE"),
            attribution_metrics=AttributionMetrics(0),
            environment=env,
            error_message="No frames available for hash evaluation",
        )

    n_frames = min(len(ref_frames), len(trans_frames))
    phash_dists: List[int] = []
    dhash_dists: List[int] = []
    ahash_dists: List[int] = []
    whash_dists: List[int] = []

    for i in range(n_frames):
        r_f = ref_frames[i]
        t_f = trans_frames[i]

        # pHash
        hp_r = compute_phash(r_f)
        hp_t = compute_phash(t_f)
        phash_dists.append(hamming_distance(hp_r, hp_t))

        # dHash
        hd_r = compute_dhash(r_f)
        hd_t = compute_dhash(t_f)
        dhash_dists.append(hamming_distance(hd_r, hd_t))

        # aHash
        ha_r = compute_ahash(r_f)
        ha_t = compute_ahash(t_f)
        ahash_dists.append(hamming_distance(ha_r, ha_t))

        # wHash
        hw_r = compute_whash(r_f)
        hw_t = compute_whash(t_f)
        whash_dists.append(hamming_distance(hw_r, hw_t))

    mean_phash_dist = float(np.mean(phash_dists))
    mean_dhash_dist = float(np.mean(dhash_dists))
    mean_ahash_dist = float(np.mean(ahash_dists))
    mean_whash_dist = float(np.mean(whash_dists))

    mean_phash_ber = float(mean_phash_dist / 64.0)
    match_status = "MATCH" if mean_phash_dist <= threshold else "NO_MATCH"
    decision_margin = float(threshold - mean_phash_dist)

    sig_metrics = SignalMetrics(
        name="perceptual_hash_distances",
        values={
            "phash_hamming_mean": mean_phash_dist,
            "phash_hamming_min": int(np.min(phash_dists)),
            "phash_hamming_max": int(np.max(phash_dists)),
            "phash_ber": mean_phash_ber,
            "dhash_hamming_mean": mean_dhash_dist,
            "ahash_hamming_mean": mean_ahash_dist,
            "whash_hamming_mean": mean_whash_dist,
            "evaluated_frame_count": n_frames,
        },
        units={
            "phash_hamming_mean": "bits",
            "phash_ber": "ratio",
            "dhash_hamming_mean": "bits",
            "ahash_hamming_mean": "bits",
            "whash_hamming_mean": "bits",
        },
    )

    det_metrics = DetectorMetrics(
        detector_name="perceptual_phash_matcher",
        algorithm="DCT_64bit_median_threshold",
        match_score=mean_phash_dist,
        threshold=float(threshold),
        match_status=match_status,
        decision_margin=decision_margin,
        parameters={"hash_length_bits": 64, "threshold_tau": threshold},
    )

    # Attribution classification for single pair
    classification = "TRUE_POSITIVE" if match_status == "MATCH" else "FALSE_NEGATIVE"
    attr_metrics = AttributionMetrics(
        evaluated_pairs=1,
        classification=classification,
        summary={
            "phash_matched_frames": int(sum(1 for d in phash_dists if d <= threshold)),
            "total_frames": n_frames,
            "match_ratio": float(sum(1 for d in phash_dists if d <= threshold)) / float(n_frames),
        },
    )

    return BenchmarkResult(
        benchmark_name="perceptual_hash",
        benchmark_version="0.1.0",
        status="success",
        signal_metrics=sig_metrics,
        detector_metrics=det_metrics,
        attribution_metrics=attr_metrics,
        environment=env,
    )
