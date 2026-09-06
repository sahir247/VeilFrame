"""
Unit Tests for VMAF Percentile (P5) and Worst-Frame Formulation.
================================================================
Verifies:
  - Precise mathematical definition of P5 (linear interpolation)
  - Clear separation between minimum (worst frame) and P5 tail
  - P5 is not controlled by a single isolated outlier frame
  - Frame count distribution behavior
"""
import numpy as np
import pytest


def compute_vmaf_percentile(scores, p=5.0, method="linear"):
    """
    Computes the p-th percentile across frame-level VMAF scores
    using deterministic linear interpolation.
    """
    if not scores:
        return None
    return float(np.percentile(scores, p, method=method))


def test_p5_uniform_distribution():
    # 100 frames from 1.0 to 100.0
    scores = [float(i) for i in range(1, 101)]
    p5 = compute_vmaf_percentile(scores, p=5.0)
    worst = min(scores)

    # In numpy linear method: 5th percentile of 1..100 is 1 + 0.05 * 99 = 5.95
    assert abs(p5 - 5.95) < 1e-4
    assert worst == 1.0
    assert p5 > worst, "P5 must be distinct from worst frame"


def test_p5_not_controlled_by_single_isolated_outlier():
    """
    Proves that a single severe outlier frame craters the worst-frame metric,
    but does NOT control the P5 tail when the sequence length is substantial.
    """
    # 1253 frames (Sintel length): 1252 frames at 95.0, and exactly 1 frame dropped to 10.0
    scores = [95.0] * 1252 + [10.0]
    worst = min(scores)
    p5 = compute_vmaf_percentile(scores, p=5.0)

    # 1 frame out of 1253 is 0.08% of the frames, well below the 5% threshold!
    assert worst == 10.0
    assert p5 == 95.0, f"P5 should remain 95.0 on a single outlier, but got {p5}"
    assert p5 - worst == 85.0


def test_p5_tail_drop_requires_systemic_degradation():
    """
    P5 responds when degradation affects 5% or more of the frames.
    """
    # 100 frames: 6 frames at 20.0, 94 frames at 90.0
    scores = [20.0] * 6 + [90.0] * 94
    p5 = compute_vmaf_percentile(scores, p=5.0)
    # 6% of frames are at 20.0, so the 5th percentile falls within the degraded cluster
    assert p5 == 20.0


def test_p5_empty_returns_none():
    assert compute_vmaf_percentile([]) is None
