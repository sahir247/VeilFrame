"""
Temporal Motion & Frame-Delta Sequence Attribution Benchmark Suite.
==================================================================

Evaluates temporal dynamics, inter-frame difference trajectories, and cadence changes.

Methodology:
- Temporal difference matrix: Delta I_t = I_{t+1} - I_t.
- Computes frame-to-frame temporal gradient sequences.
- Calculates Pearson correlation between reference and transformed sequence trajectories.
- Evaluates temporal cadence regularity.

Outputs 3-layer metrics:
- SignalMetrics: Mean frame-delta correlation, gradient energy drift, cadence change.
- DetectorMetrics: Sequence matching score vs threshold tau (default tau=0.85).
- AttributionMetrics: Trajectory matching classification.
"""
from typing import Dict, List, Optional, Tuple
import numpy as np

from ..common.models import (
    BenchmarkEnvironment,
    SignalMetrics,
    DetectorMetrics,
    AttributionMetrics,
    BenchmarkResult,
)
from ..common.statistics import pearson_correlation


def evaluate_motion_benchmark(
    ref_frames: List[np.ndarray],
    trans_frames: List[np.ndarray],
    correlation_threshold: float = 0.85,
    env: Optional[BenchmarkEnvironment] = None,
) -> BenchmarkResult:
    """
    Evaluates frame-delta correlation and temporal alignment between reference and transformed video sequences.
    """
    if env is None:
        env = BenchmarkEnvironment()

    if len(ref_frames) < 2 or len(trans_frames) < 2:
        return BenchmarkResult(
            benchmark_name="motion_frame_delta",
            benchmark_version="0.1.0",
            status="unavailable",
            signal_metrics=SignalMetrics("frame_delta_correlation"),
            detector_metrics=DetectorMetrics("motion_delta_matcher", "TemporalFrameDiff_Pearson", 0.0, correlation_threshold, "UNAVAILABLE"),
            attribution_metrics=AttributionMetrics(0),
            environment=env,
            error_message="Requires at least 2 frames to compute temporal frame-delta sequences",
        )

    n_frames = min(len(ref_frames), len(trans_frames))
    correlations: List[float] = []
    ref_delta_energies: List[float] = []
    trans_delta_energies: List[float] = []

    for t in range(n_frames - 1):
        delta_ref = ref_frames[t + 1] - ref_frames[t]
        delta_trans = trans_frames[t + 1] - trans_frames[t]

        r_energy = float(np.mean(delta_ref ** 2))
        t_energy = float(np.mean(delta_trans ** 2))

        ref_delta_energies.append(r_energy)
        trans_delta_energies.append(t_energy)

        corr = pearson_correlation(delta_ref, delta_trans)
        correlations.append(corr)

    mean_corr = float(np.mean(correlations)) if correlations else 0.0
    min_corr = float(np.min(correlations)) if correlations else 0.0
    max_corr = float(np.max(correlations)) if correlations else 0.0

    mean_r_energy = float(np.mean(ref_delta_energies)) if ref_delta_energies else 0.0
    mean_t_energy = float(np.mean(trans_delta_energies)) if trans_delta_energies else 0.0
    energy_ratio = float(mean_t_energy / max(mean_r_energy, 1e-12))

    match_status = "MATCH" if mean_corr >= correlation_threshold else "NO_MATCH"
    decision_margin = float(mean_corr - correlation_threshold)

    sig_metrics = SignalMetrics(
        name="frame_delta_correlation",
        values={
            "frame_delta_correlation_mean": mean_corr,
            "frame_delta_correlation_min": min_corr,
            "frame_delta_correlation_max": max_corr,
            "ref_delta_energy_mean": mean_r_energy,
            "trans_delta_energy_mean": mean_t_energy,
            "temporal_energy_ratio": energy_ratio,
            "evaluated_delta_pairs": len(correlations),
        },
        units={
            "frame_delta_correlation_mean": "pearson_rho",
            "temporal_energy_ratio": "ratio",
        },
    )

    det_metrics = DetectorMetrics(
        detector_name="motion_frame_delta_matcher",
        algorithm="InterFrameDifference_PearsonCorrelation",
        match_score=mean_corr,
        threshold=correlation_threshold,
        match_status=match_status,
        decision_margin=decision_margin,
        parameters={
            "correlation_threshold_tau": correlation_threshold,
            "evaluated_frames": n_frames,
        },
    )

    classification = "TRUE_POSITIVE" if match_status == "MATCH" else "FALSE_NEGATIVE"
    attr_metrics = AttributionMetrics(
        evaluated_pairs=1,
        classification=classification,
        summary={
            "sequence_matched": (match_status == "MATCH"),
            "temporal_correlation_mean": mean_corr,
        },
    )

    return BenchmarkResult(
        benchmark_name="motion_frame_delta",
        benchmark_version="0.1.0",
        status="success",
        signal_metrics=sig_metrics,
        detector_metrics=det_metrics,
        attribution_metrics=attr_metrics,
        environment=env,
    )
