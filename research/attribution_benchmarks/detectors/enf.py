"""
Electrical Network Frequency (ENF) Attribution Benchmark Suite.
==============================================================

Measures acoustic and electromagnetic power-grid hum (50Hz / 60Hz fundamental
and 100Hz / 120Hz harmonics) in audio bitstreams before and after notch filtration.

Methodology:
- Sample rate: 1000 Hz downsampled audio.
- Window function: 4-term Blackman-Harris window.
- FFT size: N = 4096 bins (frequency resolution df = 0.244 Hz).
- Spectral estimation: Welch periodogram with 50% overlap.

Outputs 3-layer metrics:
- SignalMetrics: Peak power (dB), spectral attenuation Delta_dB, noise floor, SNR drop.
- DetectorMetrics: Peak detection score, threshold, match status (HUM_DETECTED / HUM_SUPPRESSED).
- AttributionMetrics: ENF presence classification and grid attribution feasibility.
"""
from typing import Any, Dict, List, Optional, Tuple
import numpy as np

from ..common.models import (
    BenchmarkEnvironment,
    SignalMetrics,
    DetectorMetrics,
    AttributionMetrics,
    BenchmarkResult,
)
from ..common.statistics import welch_psd


def _find_peak_in_band(
    freqs: np.ndarray,
    psd: np.ndarray,
    target_freq: float,
    bandwidth: float = 2.0,
) -> Tuple[float, float, float]:
    """
    Finds peak frequency, peak power (dB), and estimated local noise floor (dB)
    within [target_freq - bandwidth, target_freq + bandwidth].
    """
    mask = (freqs >= target_freq - bandwidth) & (freqs <= target_freq + bandwidth)
    if not np.any(mask):
        return target_freq, -120.0, -120.0

    band_freqs = freqs[mask]
    band_psd = psd[mask]

    peak_idx = np.argmax(band_psd)
    peak_freq = float(band_freqs[peak_idx])
    peak_power = float(band_psd[peak_idx])

    # Surrounding noise floor excluding 3 bins around peak
    surround_mask = (freqs >= target_freq - bandwidth * 2.5) & (freqs <= target_freq + bandwidth * 2.5)
    excl_mask = surround_mask & (np.abs(freqs - peak_freq) > 0.5)
    noise_floor = float(np.median(psd[excl_mask])) if np.any(excl_mask) else 1e-12

    peak_db = 10.0 * np.log10(max(peak_power, 1e-12))
    noise_db = 10.0 * np.log10(max(noise_floor, 1e-12))

    return peak_freq, peak_db, noise_db


def evaluate_enf_benchmark(
    ref_audio: np.ndarray,
    trans_audio: np.ndarray,
    sample_rate: int = 1000,
    target_harmonics: Optional[List[float]] = None,
    detection_snr_threshold_db: float = 10.0,
    env: Optional[BenchmarkEnvironment] = None,
) -> BenchmarkResult:
    """
    Evaluates ENF spectral power attenuation across nominal grid frequencies (50/60/100/120 Hz).
    """
    if env is None:
        env = BenchmarkEnvironment()

    if target_harmonics is None:
        target_harmonics = [50.0, 60.0, 100.0, 120.0]

    if len(ref_audio) < 100 or len(trans_audio) < 100:
        return BenchmarkResult(
            benchmark_name="enf_spectral_analysis",
            benchmark_version="0.1.0",
            status="unavailable",
            signal_metrics=SignalMetrics("enf_spectral_attenuation"),
            detector_metrics=DetectorMetrics("enf_detector", "Welch_PSD_4096", 0.0, detection_snr_threshold_db, "UNAVAILABLE"),
            attribution_metrics=AttributionMetrics(0),
            environment=env,
            error_message="Audio PCM stream missing or too short for spectral analysis",
        )

    nperseg = min(4096, len(ref_audio))
    freq_res = float(sample_rate) / float(nperseg)

    f_ref, psd_ref = welch_psd(ref_audio, fs=sample_rate, nperseg=nperseg)
    f_trans, psd_trans = welch_psd(trans_audio, fs=sample_rate, nperseg=nperseg)

    harmonic_results: Dict[str, Any] = {}
    max_attenuation_db = 0.0
    ref_detected_harmonics = 0
    trans_detected_harmonics = 0

    for freq in target_harmonics:
        p_freq_ref, p_db_ref, n_db_ref = _find_peak_in_band(f_ref, psd_ref, freq)
        p_freq_trans, p_db_trans, n_db_trans = _find_peak_in_band(f_trans, psd_trans, freq)

        snr_ref = p_db_ref - n_db_ref
        snr_trans = p_db_trans - n_db_trans
        attenuation_db = p_db_ref - p_db_trans

        if snr_ref >= detection_snr_threshold_db:
            ref_detected_harmonics += 1
        if snr_trans >= detection_snr_threshold_db:
            trans_detected_harmonics += 1

        if attenuation_db > max_attenuation_db:
            max_attenuation_db = attenuation_db

        tag = f"{int(freq)}Hz"
        harmonic_results[tag] = {
            "nominal_freq_hz": freq,
            "ref_peak_freq_hz": p_freq_ref,
            "ref_peak_power_db": p_db_ref,
            "ref_snr_db": snr_ref,
            "trans_peak_power_db": p_db_trans,
            "trans_snr_db": snr_trans,
            "attenuation_delta_db": attenuation_db,
        }

    # Evaluate residual hum on harmonics that were actually present in the reference stream
    detected_in_ref = [h for h in harmonic_results.values() if h["ref_snr_db"] >= detection_snr_threshold_db]
    if detected_in_ref:
        max_residual_snr = max((h["trans_snr_db"] for h in detected_in_ref), default=0.0)
        match_status = "HUM_DETECTED" if any(h["trans_snr_db"] >= detection_snr_threshold_db for h in detected_in_ref) else "HUM_SUPPRESSED"
    else:
        max_residual_snr = max((h["trans_snr_db"] for h in harmonic_results.values()), default=0.0)
        match_status = "HUM_NOT_PRESENT"

    sig_metrics = SignalMetrics(
        name="enf_spectral_attenuation",
        values={
            "max_attenuation_db": float(max_attenuation_db),
            "fft_size_n": nperseg,
            "frequency_resolution_hz": freq_res,
            "window_function": "blackmanharris",
            "sample_rate_hz": sample_rate,
            "harmonics": harmonic_results,
        },
        units={
            "max_attenuation_db": "dB",
            "frequency_resolution_hz": "Hz",
            "sample_rate_hz": "Hz",
        },
    )

    det_metrics = DetectorMetrics(
        detector_name="enf_grid_hum_detector",
        algorithm="Welch_PSD_BlackmanHarris_PeakSearch",
        match_score=float(max_residual_snr),
        threshold=detection_snr_threshold_db,
        match_status=match_status,
        decision_margin=float(detection_snr_threshold_db - max_residual_snr),
        parameters={
            "target_frequencies_hz": target_harmonics,
            "detection_snr_threshold_db": detection_snr_threshold_db,
            "fft_size": nperseg,
        },
    )

    classification = "TRUE_POSITIVE" if match_status == "HUM_DETECTED" else "ATTRIBUTED_SUPPRESSED"
    attr_metrics = AttributionMetrics(
        evaluated_pairs=1,
        classification=classification,
        summary={
            "ref_harmonics_detected": ref_detected_harmonics,
            "trans_harmonics_detected": trans_detected_harmonics,
            "attribution_feasible": (trans_detected_harmonics > 0),
        },
    )

    return BenchmarkResult(
        benchmark_name="enf_spectral_analysis",
        benchmark_version="0.1.0",
        status="success",
        signal_metrics=sig_metrics,
        detector_metrics=det_metrics,
        attribution_metrics=attr_metrics,
        environment=env,
    )
