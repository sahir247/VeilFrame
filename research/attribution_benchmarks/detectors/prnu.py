"""
Photo-Response Non-Uniformity (PRNU) Camera Fingerprint Attribution Benchmark Suite.
===================================================================================

Measures sensor noise residue extraction, same-camera vs. cross-camera 2D cross-correlation,
Peak-to-Correlation Energy (PCE), and statistical attribution classification (TPR, FPR, AUC).

Methodology:
- Noise residual extraction: W = I - F(I) using spatial adaptive 2D filtering.
- Fingerprint estimation: K_hat = (1/M) sum(W_i).
- Matching statistic: 2D circular cross-correlation, Normalized Cross-Correlation (NCC),
  and Peak-to-Correlation Energy (PCE = Peak^2 / Mean_Square_Outside_Peak).
- Multi-camera attribution: Evaluates same-camera matching distribution vs cross-camera distribution
  to compute empirical ROC curves and Area Under Curve (AUC).
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
from ..common.statistics import compute_pce_and_ncc, compute_roc_and_auc


def extract_noise_residual(img: np.ndarray, filter_radius: int = 2) -> np.ndarray:
    """
    Extracts high-frequency sensor noise residue W = I - F(I) via 2D spatial filtering.
    """
    im = np.asarray(img, dtype=np.float64)
    h, w = im.shape

    # Fast separable box smoothing as baseline F(I)
    padded = np.pad(im, filter_radius, mode="reflect")
    smoothed = np.zeros_like(im)

    win_len = 2 * filter_radius + 1
    # Horizontal pass
    h_smooth = np.apply_along_axis(lambda m: np.convolve(m, np.ones(win_len)/win_len, mode="valid"), axis=1, arr=padded)
    # Vertical pass
    smoothed = np.apply_along_axis(lambda m: np.convolve(m, np.ones(win_len)/win_len, mode="valid"), axis=0, arr=h_smooth)

    residual = im - smoothed
    # Zero-mean the residual
    residual -= np.mean(residual)
    return residual


def estimate_camera_fingerprint(frames: List[np.ndarray]) -> np.ndarray:
    """
    Estimates composite sensor fingerprint by maximum-likelihood averaging of residuals.
    """
    if not frames:
        return np.zeros((1, 1), dtype=np.float64)

    residuals = [extract_noise_residual(f) for f in frames]
    fingerprint = np.mean(residuals, axis=0)
    std = np.std(fingerprint)
    if std > 1e-12:
        fingerprint /= std
    return fingerprint


def evaluate_prnu_pair_benchmark(
    ref_frames: List[np.ndarray],
    trans_frames: List[np.ndarray],
    pce_threshold: float = 60.0,
    env: Optional[BenchmarkEnvironment] = None,
) -> BenchmarkResult:
    """
    Evaluates PRNU 2D cross-correlation and PCE between reference and transformed video frames.
    """
    if env is None:
        env = BenchmarkEnvironment()

    if not ref_frames or not trans_frames:
        return BenchmarkResult(
            benchmark_name="prnu_sensor_fingerprint",
            benchmark_version="0.1.0",
            status="unavailable",
            signal_metrics=SignalMetrics("prnu_noise_residual_correlation"),
            detector_metrics=DetectorMetrics("prnu_pce_matcher", "2D_PCE_NCC", 0.0, pce_threshold, "UNAVAILABLE"),
            attribution_metrics=AttributionMetrics(0),
            environment=env,
            error_message="Insufficient frames to extract PRNU noise residuals",
        )

    fp_ref = estimate_camera_fingerprint(ref_frames)
    fp_trans = estimate_camera_fingerprint(trans_frames)

    # 1. Self-reference baseline PCE (identifying maximum possible peak on clean reference)
    pce_ref_self, ncc_ref_self, _ = compute_pce_and_ncc(fp_ref, fp_ref)

    # 2. Transformed vs Reference PCE
    pce_match, ncc_match, peak_coord = compute_pce_and_ncc(fp_ref, fp_trans)

    pce_attenuation_ratio = float(pce_match / max(pce_ref_self, 1e-12))
    match_status = "MATCH" if pce_match >= pce_threshold else "NO_MATCH"
    decision_margin = float(pce_match - pce_threshold)

    sig_metrics = SignalMetrics(
        name="prnu_noise_residual_correlation",
        values={
            "ref_self_pce": float(pce_ref_self),
            "ref_self_ncc": float(ncc_ref_self),
            "transformed_pce": float(pce_match),
            "transformed_ncc": float(ncc_match),
            "pce_attenuation_ratio": pce_attenuation_ratio,
            "peak_coordinate": list(peak_coord),
            "evaluated_frames_ref": len(ref_frames),
            "evaluated_frames_trans": len(trans_frames),
        },
        units={
            "ref_self_pce": "energy_ratio",
            "transformed_pce": "energy_ratio",
            "transformed_ncc": "correlation_coefficient",
        },
    )

    det_metrics = DetectorMetrics(
        detector_name="prnu_pce_detector",
        algorithm="Normalized_2D_CrossCorr_PCE",
        match_score=float(pce_match),
        threshold=pce_threshold,
        match_status=match_status,
        decision_margin=decision_margin,
        parameters={
            "pce_decision_threshold": pce_threshold,
            "peak_exclusion_radius": 5,
        },
    )

    classification = "TRUE_POSITIVE" if match_status == "MATCH" else "FALSE_NEGATIVE"
    attr_metrics = AttributionMetrics(
        evaluated_pairs=1,
        classification=classification,
        summary={
            "same_camera_attributed": (match_status == "MATCH"),
            "attribution_confidence": float(pce_match),
        },
    )

    return BenchmarkResult(
        benchmark_name="prnu_sensor_fingerprint",
        benchmark_version="0.1.0",
        status="success",
        signal_metrics=sig_metrics,
        detector_metrics=det_metrics,
        attribution_metrics=attr_metrics,
        environment=env,
    )


def evaluate_prnu_corpus_benchmark(
    camera_corpora: Dict[str, Dict[str, List[np.ndarray]]],
    pce_threshold: float = 60.0,
    env: Optional[BenchmarkEnvironment] = None,
) -> BenchmarkResult:
    """
    Evaluates PRNU attribution performance across a multi-camera dataset before and after transformation.

    Structure of camera_corpora:
    {
        "cam_A": {"ref": [frames...], "trans": [frames...]},
        "cam_B": {"ref": [frames...], "trans": [frames...]},
        ...
    }
    """
    if env is None:
        env = BenchmarkEnvironment()

    cam_ids = list(camera_corpora.keys())
    if len(cam_ids) < 2:
        return BenchmarkResult(
            benchmark_name="prnu_corpus_attribution",
            benchmark_version="0.1.0",
            status="unavailable",
            signal_metrics=SignalMetrics("prnu_corpus_metrics"),
            detector_metrics=DetectorMetrics("prnu_corpus_matcher", "MultiCamera_ROC", 0.0, pce_threshold, "UNAVAILABLE"),
            attribution_metrics=AttributionMetrics(0),
            environment=env,
            error_message="Requires at least 2 distinct camera corpora to compute ROC/AUC attribution curves",
        )

    # Compute fingerprints
    ref_fps: Dict[str, np.ndarray] = {}
    trans_fps: Dict[str, np.ndarray] = {}
    for cid, clips in camera_corpora.items():
        ref_fps[cid] = estimate_camera_fingerprint(clips.get("ref", []))
        trans_fps[cid] = estimate_camera_fingerprint(clips.get("trans", []))

    same_cam_before: List[float] = []
    same_cam_after: List[float] = []
    diff_cam_before: List[float] = []
    diff_cam_after: List[float] = []

    for i, c1 in enumerate(cam_ids):
        # Same camera match
        pce_self_b, _, _ = compute_pce_and_ncc(ref_fps[c1], ref_fps[c1])
        pce_self_a, _, _ = compute_pce_and_ncc(ref_fps[c1], trans_fps[c1])
        same_cam_before.append(pce_self_b)
        same_cam_after.append(pce_self_a)

        # Cross camera matches
        for j, c2 in enumerate(cam_ids):
            if i != j:
                pce_cross_b, _, _ = compute_pce_and_ncc(ref_fps[c1], ref_fps[c2])
                pce_cross_a, _, _ = compute_pce_and_ncc(ref_fps[c1], trans_fps[c2])
                diff_cam_before.append(pce_cross_b)
                diff_cam_after.append(pce_cross_a)

    auc_before, roc_b = compute_roc_and_auc(same_cam_before, diff_cam_before, higher_is_match=True)
    auc_after, roc_a = compute_roc_and_auc(same_cam_after, diff_cam_after, higher_is_match=True)

    tp_b = sum(1 for s in same_cam_before if s >= pce_threshold)
    fp_b = sum(1 for s in diff_cam_before if s >= pce_threshold)
    tpr_b = float(tp_b) / len(same_cam_before) if same_cam_before else 0.0
    fpr_b = float(fp_b) / len(diff_cam_before) if diff_cam_before else 0.0

    tp_a = sum(1 for s in same_cam_after if s >= pce_threshold)
    fp_a = sum(1 for s in diff_cam_after if s >= pce_threshold)
    tpr_a = float(tp_a) / len(same_cam_after) if same_cam_after else 0.0
    fpr_a = float(fp_a) / len(diff_cam_after) if diff_cam_after else 0.0

    sig_metrics = SignalMetrics(
        name="prnu_corpus_metrics",
        values={
            "mean_same_pce_before": float(np.mean(same_cam_before)),
            "mean_same_pce_after": float(np.mean(same_cam_after)),
            "mean_diff_pce_before": float(np.mean(diff_cam_before)),
            "mean_diff_pce_after": float(np.mean(diff_cam_after)),
            "camera_count": len(cam_ids),
        },
        units={"mean_same_pce_before": "PCE", "mean_same_pce_after": "PCE"},
    )

    det_metrics = DetectorMetrics(
        detector_name="prnu_corpus_pce_classifier",
        algorithm="MultiCamera_PCE_Thresholding",
        match_score=float(np.mean(same_cam_after)),
        threshold=pce_threshold,
        match_status="EVALUATED",
        parameters={"camera_count": len(cam_ids), "threshold": pce_threshold},
    )

    attr_metrics = AttributionMetrics(
        evaluated_pairs=len(same_cam_after) + len(diff_cam_after),
        true_positive_rate=tpr_a,
        false_positive_rate=fpr_a,
        area_under_curve=auc_after,
        summary={
            "before_transformation": {"tpr": tpr_b, "fpr": fpr_b, "auc": auc_before},
            "after_transformation": {"tpr": tpr_a, "fpr": fpr_a, "auc": auc_after},
            "auc_delta": float(auc_after - auc_before),
            "tpr_delta": float(tpr_a - tpr_b),
        },
    )

    return BenchmarkResult(
        benchmark_name="prnu_corpus_attribution",
        benchmark_version="0.1.0",
        status="success",
        signal_metrics=sig_metrics,
        detector_metrics=det_metrics,
        attribution_metrics=attr_metrics,
        environment=env,
    )
