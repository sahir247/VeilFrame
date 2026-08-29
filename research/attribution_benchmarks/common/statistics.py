"""
Mathematical and statistical routines for signal, detector, and attribution analysis.
"""
from typing import List, Tuple, Union
import numpy as np


def hamming_distance(h1: Union[int, str, bytes, np.ndarray], h2: Union[int, str, bytes, np.ndarray]) -> int:
    """Computes exact bitwise Hamming distance between two binary hashes."""
    if isinstance(h1, np.ndarray) and isinstance(h2, np.ndarray):
        return int(np.count_nonzero(h1.astype(bool) != h2.astype(bool)))

    if isinstance(h1, str) and isinstance(h2, str):
        # Hex string conversion
        v1 = int(h1, 16)
        v2 = int(h2, 16)
        return bin(v1 ^ v2).count("1")

    if isinstance(h1, bytes) and isinstance(h2, bytes):
        return sum(bin(b1 ^ b2).count("1") for b1, b2 in zip(h1, h2))

    if isinstance(h1, int) and isinstance(h2, int):
        return bin(h1 ^ h2).count("1")

    raise TypeError(f"Unsupported hash types: {type(h1)}, {type(h2)}")


def bit_error_rate(h1: Union[int, str, bytes, np.ndarray], h2: Union[int, str, bytes, np.ndarray], bit_length: int = 64) -> float:
    """Computes normalized Bit Error Rate (BER = HammingDistance / bit_length)."""
    dist = hamming_distance(h1, h2)
    return float(dist) / float(bit_length)


def pearson_correlation(x: np.ndarray, y: np.ndarray) -> float:
    """Computes Pearson product-moment correlation coefficient between two arrays."""
    x_flat = np.asarray(x, dtype=np.float64).ravel()
    y_flat = np.asarray(y, dtype=np.float64).ravel()
    if len(x_flat) == 0 or len(y_flat) == 0 or len(x_flat) != len(y_flat):
        return 0.0

    x_mean = np.mean(x_flat)
    y_mean = np.mean(y_flat)
    x_dev = x_flat - x_mean
    y_dev = y_flat - y_mean

    norm_x = np.sqrt(np.sum(x_dev ** 2))
    norm_y = np.sqrt(np.sum(y_dev ** 2))

    if norm_x < 1e-12 or norm_y < 1e-12:
        return 0.0

    return float(np.sum(x_dev * y_dev) / (norm_x * norm_y))


def compute_blackman_harris_window(N: int) -> np.ndarray:
    """Computes exact 4-term Blackman-Harris window for high-dynamic-range spectral analysis."""
    n = np.arange(N)
    a0 = 0.35875
    a1 = 0.48829
    a2 = 0.14128
    a3 = 0.01168
    return a0 - a1 * np.cos(2 * np.pi * n / (N - 1)) + a2 * np.cos(4 * np.pi * n / (N - 1)) - a3 * np.cos(6 * np.pi * n / (N - 1))


def welch_psd(
    signal: np.ndarray,
    fs: float,
    nperseg: int = 4096,
    overlap: float = 0.5,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Computes Power Spectral Density (PSD) using Welch's periodogram method
    with deterministic Blackman-Harris windowing.
    """
    sig = np.asarray(signal, dtype=np.float64).ravel()
    n_samples = len(sig)
    if n_samples < nperseg:
        # Zero pad signal to nperseg if shorter
        padded = np.zeros(nperseg, dtype=np.float64)
        padded[:n_samples] = sig
        sig = padded
        n_samples = nperseg

    step = int(nperseg * (1.0 - overlap))
    win = compute_blackman_harris_window(nperseg)
    win_power = np.sum(win ** 2)

    psd_accum = np.zeros(nperseg // 2 + 1, dtype=np.float64)
    n_segments = 0

    for start in range(0, n_samples - nperseg + 1, step):
        segment = sig[start : start + nperseg] * win
        fft_vals = np.fft.rfft(segment)
        psd_accum += (np.abs(fft_vals) ** 2) / (fs * win_power)
        n_segments += 1

    if n_segments > 0:
        psd_accum /= n_segments

    # Single-sided scaling (multiply bins 1 to N-1 by 2)
    if len(psd_accum) > 2:
        psd_accum[1:-1] *= 2.0

    freqs = np.fft.rfftfreq(nperseg, d=1.0 / fs)
    return freqs, psd_accum


def compute_pce_and_ncc(
    res1: np.ndarray,
    res2: np.ndarray,
    peak_exclusion_radius: int = 5,
) -> Tuple[float, float, Tuple[int, int]]:
    """
    Computes 2D Normalized Cross-Correlation (NCC) and Peak-to-Correlation Energy (PCE)
    between two high-pass PRNU noise residual matrices.

    PCE formula:
        PCE = Peak^2 / Mean_Square_Energy(Outside_Peak_Area)
    """
    r1 = np.asarray(res1, dtype=np.float64)
    r2 = np.asarray(res2, dtype=np.float64)

    if r1.shape != r2.shape or r1.size == 0:
        return 0.0, 0.0, (0, 0)

    # Zero-mean normalization
    r1_zm = r1 - np.mean(r1)
    r2_zm = r2 - np.mean(r2)

    std1 = np.std(r1_zm)
    std2 = np.std(r2_zm)
    if std1 < 1e-12 or std2 < 1e-12:
        return 0.0, 0.0, (0, 0)

    r1_norm = r1_zm / std1
    r2_norm = r2_zm / std2

    # 2D FFT Cross-correlation
    f1 = np.fft.fft2(r1_norm)
    f2 = np.fft.fft2(r2_norm)
    cross_corr = np.fft.ifft2(f1 * np.conj(f2)).real / float(r1.size)

    # Find peak location
    peak_idx = np.unravel_index(np.argmax(cross_corr), cross_corr.shape)
    peak_val = float(cross_corr[peak_idx])

    # Compute energy outside peak radius
    h, w = cross_corr.shape
    y_grid, x_grid = np.ogrid[:h, :w]
    dy = np.minimum(np.abs(y_grid - peak_idx[0]), h - np.abs(y_grid - peak_idx[0]))
    dx = np.minimum(np.abs(x_grid - peak_idx[1]), w - np.abs(x_grid - peak_idx[1]))
    dist_sq = dy ** 2 + dx ** 2
    outside_mask = dist_sq > (peak_exclusion_radius ** 2)

    outside_energy = np.mean(cross_corr[outside_mask] ** 2) if np.any(outside_mask) else 1e-12
    pce = float((peak_val ** 2) / max(outside_energy, 1e-12))
    ncc = float(peak_val)

    return pce, ncc, (int(peak_idx[0]), int(peak_idx[1]))


def compute_roc_and_auc(
    same_camera_scores: List[float],
    diff_camera_scores: List[float],
    higher_is_match: bool = True,
) -> Tuple[float, List[Tuple[float, float]]]:
    """
    Computes exact empirical Receiver Operating Characteristic (ROC) curve and Area Under Curve (AUC)
    from ground-truth matched (positive) and non-matched (negative) score distributions.
    """
    if not same_camera_scores or not diff_camera_scores:
        return 0.5, [(0.0, 0.0), (1.0, 1.0)]

    n_pos = len(same_camera_scores)
    n_neg = len(diff_camera_scores)

    y_true = np.array([1] * n_pos + [0] * n_neg)
    y_score = np.array(same_camera_scores + diff_camera_scores, dtype=np.float64)

    if not higher_is_match:
        y_score = -y_score

    order = np.argsort(y_score)[::-1]
    sorted_y = y_true[order]

    tpr = np.cumsum(sorted_y) / float(n_pos)
    fpr = np.cumsum(1 - sorted_y) / float(n_neg)

    tpr = np.insert(tpr, 0, 0.0)
    fpr = np.insert(fpr, 0, 0.0)

    auc = float(np.trapezoid(tpr, fpr))
    roc_points = [(float(f), float(t)) for f, t in zip(fpr, tpr)]

    return max(0.0, min(1.0, auc)), roc_points

