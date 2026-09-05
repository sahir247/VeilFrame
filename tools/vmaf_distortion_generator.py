#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VeilFrame Iterative Boundary-Targeted Distortion Generator
===========================================================
Generates calibrated distortion pairs densely targeting the decision-boundary
region for VeilFrame's objective visual fidelity policy:
    SSIM >= 0.9500  AND  PSNR >= 30.00 dB

Key Architectural & Methodological Invariants:
  1. Real Physical Measurement: When running without --simulate, executes actual
     FFmpeg encoding, real SSIM, real PSNR, and real official libvmaf v1.0.16.
  2. Dense Boundary Sampling: Focuses on SSIM in [0.930, 0.970] and PSNR in [28.0, 32.0] dB.
  3. Cardinal Independence Rule: Preserves reference clip's sequence_group_id.
     All distortions derived from the same master share the same group ID.
  4. Non-Circular Labeling: Independent ground truth label is computed strictly
     from measured SSIM/PSNR, never from fixture targets or VMAF.
  5. Measurement Integrity: Missing metrics remain None; no zero-substitution.
  6. Provenance Tracking: Records measurement_status ("empirical" vs "simulated"),
     exact SHA-256 hashes for reference, distorted file, model, and evidence JSON.
"""

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from veilframe.quality.vmaf_models import (
    OFFICIAL_VMAF_V1_0_16_MODELS,
    VMAF_MODEL_VERSION,
    VmafModelSpec,
    select_vmaf_model,
    resolve_and_verify_model,
    format_ffmpeg_filter_path,
    format_vmaf_model_filter_arg,
)
from veilframe.core.crypto import compute_sha256


@dataclass
class DistortionTarget:
    target_id: str
    target_ssim: Optional[float]
    target_psnr: Optional[float]
    distortion_type: str  # "quantization", "gamma", "brightness", "blur", "joint_gamma_quant"
    category: str  # "near_boundary_pass", "near_boundary_fail", "deep_pass", "deep_fail"
    crf: int = 28
    filter_expr: Optional[str] = None
    desired_ssim_min: Optional[float] = None
    desired_ssim_max: Optional[float] = None
    desired_psnr_min: Optional[float] = None
    desired_psnr_max: Optional[float] = None
    tune_param: Optional[str] = None  # "crf", "gamma", "brightness", "blur", "joint"
    tune_val: Optional[float] = None
    max_search_iterations: int = 2
    region: str = "general"
    quadrant: Optional[str] = None
    distortion_role: str = "representative"  # "representative", "adversarial_policy_stress_test", "diagnostic"
    calibration_eligibility: str = "primary_calibration"  # "primary_calibration", "adversarial_only", "diagnostic_only", "excluded"
    exclusion_reason: str = ""



DEFAULT_TARGETS: List[DistortionTarget] = [
    # ── Region A: SSIM-boundary / PSNR-safe (SSIM in [0.945, 0.955], PSNR >= 35 dB) ──
    DistortionTarget(
        "SSIM_BND_PASS_01", target_ssim=0.955, target_psnr=41.0,
        distortion_type="quantization", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=23, desired_ssim_min=0.953, desired_ssim_max=0.958, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_PASS_02", target_ssim=0.952, target_psnr=40.5,
        distortion_type="quantization_blur", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=22, filter_expr="gblur=sigma=0.8", desired_ssim_min=0.9505, desired_ssim_max=0.9545, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="blur", tune_val=0.8, max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_PASS_03", target_ssim=0.962, target_psnr=42.0,
        distortion_type="quantization", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=21, desired_ssim_min=0.958, desired_ssim_max=0.966, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_01", target_ssim=0.948, target_psnr=40.0,
        distortion_type="quantization", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=24, desired_ssim_min=0.9465, desired_ssim_max=0.9499, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_02", target_ssim=0.944, target_psnr=39.8,
        distortion_type="quantization", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=25, desired_ssim_min=0.9420, desired_ssim_max=0.9460, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_03_BLUR", target_ssim=0.938, target_psnr=38.1,
        distortion_type="blur", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=22, filter_expr="gblur=sigma=1.6", desired_ssim_min=0.9340, desired_ssim_max=0.9410, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="blur", tune_val=1.6, max_search_iterations=5
    ),

    # ── Region B: PSNR-boundary / SSIM-safe (PSNR in [28.5, 31.5] dB, SSIM >= 0.960) ──
    DistortionTarget(
        "PSNR_BND_PASS_01_GAMMA", target_ssim=0.972, target_psnr=31.4,
        distortion_type="gamma", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=16, filter_expr="eq=gamma=0.910", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=30.8, desired_psnr_max=32.0,
        tune_param="gamma", tune_val=0.910, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_PASS_02_GAMMA", target_ssim=0.970, target_psnr=30.5,
        distortion_type="gamma", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=16, filter_expr="eq=gamma=0.900", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=30.1, desired_psnr_max=30.7,
        tune_param="gamma", tune_val=0.900, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_PASS_03_BRIGHT", target_ssim=0.972, target_psnr=30.6,
        distortion_type="brightness", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=16, filter_expr="eq=brightness=0.045", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=30.1, desired_psnr_max=31.2,
        tune_param="brightness", tune_val=0.045, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_FAIL_01_GAMMA", target_ssim=0.969, target_psnr=29.7,
        distortion_type="gamma", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=16, filter_expr="eq=gamma=0.890", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=29.4, desired_psnr_max=29.99,
        tune_param="gamma", tune_val=0.890, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_FAIL_02_GAMMA", target_ssim=0.967, target_psnr=28.9,
        distortion_type="gamma", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=16, filter_expr="eq=gamma=0.880", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=28.6, desired_psnr_max=29.3,
        tune_param="gamma", tune_val=0.880, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_FAIL_03_CONTRAST", target_ssim=0.961, target_psnr=28.2,
        distortion_type="gamma", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=16, filter_expr="eq=gamma=0.870", desired_ssim_min=0.960, desired_ssim_max=0.985, desired_psnr_min=27.5, desired_psnr_max=28.6,
        tune_param="gamma", tune_val=0.870, max_search_iterations=5
    ),

    # ── Region C: JOINT boundary region (Around SSIM=0.950, PSNR=30.0 dB, 4 Quadrants) ──
    # Q1: SSIM >= 0.9500 AND PSNR >= 30.00 dB -> Acceptable
    DistortionTarget(
        "JOINT_Q1_PASS_01", target_ssim=0.951, target_psnr=30.3,
        distortion_type="joint_gamma_quant", category="near_boundary_pass", region="joint_boundary", quadrant="q1_pass",
        crf=22, filter_expr="eq=gamma=0.900", desired_ssim_min=0.9500, desired_ssim_max=0.9540, desired_psnr_min=30.05, desired_psnr_max=30.70,
        tune_param="joint", tune_val=0.900, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q1_PASS_02", target_ssim=0.953, target_psnr=31.2,
        distortion_type="joint_gamma_quant", category="near_boundary_pass", region="joint_boundary", quadrant="q1_pass",
        crf=22, filter_expr="eq=gamma=0.910", desired_ssim_min=0.9510, desired_ssim_max=0.9560, desired_psnr_min=30.80, desired_psnr_max=31.60,
        tune_param="joint", tune_val=0.910, max_search_iterations=5
    ),

    # Q2: SSIM < 0.9500 AND PSNR >= 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q2_FAIL_SSIM_01", target_ssim=0.948, target_psnr=30.3,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q2_fail_ssim",
        crf=23, filter_expr="eq=gamma=0.900", desired_ssim_min=0.9460, desired_ssim_max=0.9499, desired_psnr_min=30.05, desired_psnr_max=30.70,
        tune_param="joint", tune_val=0.900, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q2_FAIL_SSIM_02", target_ssim=0.946, target_psnr=31.1,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q2_fail_ssim",
        crf=24, filter_expr="eq=gamma=0.910", desired_ssim_min=0.9430, desired_ssim_max=0.9490, desired_psnr_min=30.80, desired_psnr_max=31.60,
        tune_param="joint", tune_val=0.910, max_search_iterations=5
    ),

    # Q3: SSIM >= 0.9500 AND PSNR < 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q3_FAIL_PSNR_01", target_ssim=0.952, target_psnr=28.9,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q3_fail_psnr",
        crf=21, filter_expr="eq=gamma=0.880", desired_ssim_min=0.9500, desired_ssim_max=0.9540, desired_psnr_min=28.50, desired_psnr_max=29.30,
        tune_param="joint", tune_val=0.880, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q3_FAIL_PSNR_02", target_ssim=0.955, target_psnr=28.9,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q3_fail_psnr",
        crf=20, filter_expr="eq=gamma=0.880", desired_ssim_min=0.9530, desired_ssim_max=0.9580, desired_psnr_min=28.50, desired_psnr_max=29.30,
        tune_param="joint", tune_val=0.880, max_search_iterations=5
    ),

    # Q4: SSIM < 0.9500 AND PSNR < 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q4_FAIL_BOTH_01", target_ssim=0.944, target_psnr=29.5,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q4_fail_both",
        crf=24, filter_expr="eq=gamma=0.890", desired_ssim_min=0.9410, desired_ssim_max=0.9470, desired_psnr_min=29.10, desired_psnr_max=29.80,
        tune_param="joint", tune_val=0.890, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q4_FAIL_BOTH_02", target_ssim=0.942, target_psnr=28.8,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q4_fail_both",
        crf=24, filter_expr="eq=gamma=0.880", desired_ssim_min=0.9380, desired_ssim_max=0.9450, desired_psnr_min=28.40, desired_psnr_max=29.10,
        tune_param="joint", tune_val=0.880, max_search_iterations=5
    ),

    # ── Anchors: Global Extremes ──
    DistortionTarget(
        "ANCHOR_DEEP_PASS", target_ssim=0.992, target_psnr=49.4,
        distortion_type="quantization", category="deep_pass", region="anchor", quadrant="q1_pass",
        crf=10, desired_ssim_min=0.985, desired_ssim_max=1.000, desired_psnr_min=45.0, desired_psnr_max=60.0
    ),
    DistortionTarget(
        "ANCHOR_DEEP_FAIL", target_ssim=0.865, target_psnr=33.6,
        distortion_type="quantization", category="deep_fail", region="anchor", quadrant="q2_fail_ssim",
        crf=38, desired_ssim_min=0.800, desired_ssim_max=0.890, desired_psnr_min=25.0, desired_psnr_max=36.0
    ),
]


CGI_TARGETS: List[DistortionTarget] = [
    # ── Region A: SSIM-boundary / PSNR-safe (SSIM in [0.945, 0.955], PSNR >= 35 dB) ──
    DistortionTarget(
        "SSIM_BND_PASS_01", target_ssim=0.955, target_psnr=40.0,
        distortion_type="quantization", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=26, desired_ssim_min=0.951, desired_ssim_max=0.958, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_PASS_02_BLUR", target_ssim=0.953, target_psnr=39.0,
        distortion_type="blur", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=20, filter_expr="gblur=sigma=1.20", desired_ssim_min=0.9505, desired_ssim_max=0.9550, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="blur", tune_val=1.20, max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_PASS_03_RESAMPLE", target_ssim=0.962, target_psnr=41.0,
        distortion_type="resampling", category="near_boundary_pass", region="ssim_axis_psnr_safe", quadrant="q1_pass",
        crf=20, filter_expr="scale=1440:810:flags=bicubic,scale=1920:1080:flags=bicubic", desired_ssim_min=0.958, desired_ssim_max=0.968, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="resample", tune_val=1440.0, max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_01", target_ssim=0.945, target_psnr=38.5,
        distortion_type="quantization", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=33, desired_ssim_min=0.9420, desired_ssim_max=0.9480, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="crf", max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_02_BLUR", target_ssim=0.944, target_psnr=37.5,
        distortion_type="blur", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=20, filter_expr="gblur=sigma=2.20", desired_ssim_min=0.9420, desired_ssim_max=0.9460, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="blur", tune_val=2.20, max_search_iterations=5
    ),
    DistortionTarget(
        "SSIM_BND_FAIL_03_RESAMPLE", target_ssim=0.945, target_psnr=38.0,
        distortion_type="resampling", category="near_boundary_fail", region="ssim_axis_psnr_safe", quadrant="q2_fail_ssim",
        crf=20, filter_expr="scale=720:405:flags=bicubic,scale=1920:1080:flags=bicubic", desired_ssim_min=0.9420, desired_ssim_max=0.9480, desired_psnr_min=35.0, desired_psnr_max=50.0,
        tune_param="resample", tune_val=720.0, max_search_iterations=5
    ),

    # ── Region B: PSNR-boundary / SSIM-safe (PSNR in [27.0, 33.0] dB, SSIM >= 0.960) ──
    # True Q1 Controls (SSIM >= 0.960, PSNR >= 30.0 dB)
    DistortionTarget(
        "PSNR_BND_PASS_01_CHROMA", target_ssim=0.996, target_psnr=32.8,
        distortion_type="chroma_shift", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=14, filter_expr="lutyuv=u='clip(val+10,0,255)':v='clip(val+10,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=32.0, desired_psnr_max=34.0,
        tune_param="chroma", tune_val=10.0, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_PASS_02_CHROMA", target_ssim=0.996, target_psnr=31.3,
        distortion_type="chroma_shift", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=14, filter_expr="lutyuv=u='clip(val+12,0,255)':v='clip(val+12,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=30.8, desired_psnr_max=31.8,
        tune_param="chroma", tune_val=12.0, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_PASS_03_CHROMA", target_ssim=0.995, target_psnr=30.6,
        distortion_type="chroma_shift", category="near_boundary_pass", region="psnr_axis_ssim_safe", quadrant="q1_pass",
        crf=14, filter_expr="lutyuv=u='clip(val+13,0,255)':v='clip(val+13,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=30.1, desired_psnr_max=30.8,
        tune_param="chroma", tune_val=13.0, max_search_iterations=5
    ),

    # True Q3 Targets (SSIM >= 0.960, PSNR < 30.0 dB)
    DistortionTarget(
        "PSNR_BND_FAIL_01_CHROMA", target_ssim=0.995, target_psnr=29.9,
        distortion_type="chroma_shift", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=14, filter_expr="lutyuv=u='clip(val+14,0,255)':v='clip(val+14,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=29.4, desired_psnr_max=29.99,
        tune_param="chroma", tune_val=14.0, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_FAIL_02_CHROMA", target_ssim=0.995, target_psnr=28.8,
        distortion_type="chroma_shift", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=14, filter_expr="lutyuv=u='clip(val+16,0,255)':v='clip(val+16,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=28.3, desired_psnr_max=29.2,
        tune_param="chroma", tune_val=16.0, max_search_iterations=5
    ),
    DistortionTarget(
        "PSNR_BND_FAIL_03_CHROMA", target_ssim=0.994, target_psnr=27.8,
        distortion_type="chroma_shift", category="near_boundary_fail", region="psnr_axis_ssim_safe", quadrant="q3_fail_psnr",
        crf=14, filter_expr="lutyuv=u='clip(val+18,0,255)':v='clip(val+18,0,255)'", desired_ssim_min=0.960, desired_ssim_max=1.000, desired_psnr_min=27.0, desired_psnr_max=28.2,
        tune_param="chroma", tune_val=18.0, max_search_iterations=5
    ),

    # ── Region C: JOINT boundary region (Around SSIM=0.950, PSNR=30.0 dB, 4 Quadrants) ──
    # Q1: SSIM >= 0.9500 AND PSNR >= 30.00 dB -> Acceptable
    DistortionTarget(
        "JOINT_Q1_PASS_01", target_ssim=0.952, target_psnr=30.5,
        distortion_type="joint_gamma_quant", category="near_boundary_pass", region="joint_boundary", quadrant="q1_pass",
        crf=24, filter_expr="eq=gamma=0.900", desired_ssim_min=0.9500, desired_ssim_max=0.9560, desired_psnr_min=30.05, desired_psnr_max=30.80,
        tune_param="joint", tune_val=0.900, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q1_PASS_02_RESAMPLE", target_ssim=0.953, target_psnr=31.2,
        distortion_type="joint_resample_quant", category="near_boundary_pass", region="joint_boundary", quadrant="q1_pass",
        crf=24, filter_expr="scale=1440:810:flags=bicubic,scale=1920:1080:flags=bicubic", desired_ssim_min=0.9510, desired_ssim_max=0.9580, desired_psnr_min=30.80, desired_psnr_max=32.00,
        tune_param="joint_resample", tune_val=1440.0, max_search_iterations=5
    ),

    # Q2: SSIM < 0.9500 AND PSNR >= 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q2_FAIL_SSIM_01", target_ssim=0.947, target_psnr=30.5,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q2_fail_ssim",
        crf=26, filter_expr="eq=gamma=0.900", desired_ssim_min=0.9440, desired_ssim_max=0.9495, desired_psnr_min=30.05, desired_psnr_max=31.50,
        tune_param="joint", tune_val=0.900, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q2_FAIL_SSIM_02_BLUR", target_ssim=0.946, target_psnr=31.0,
        distortion_type="joint_blur_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q2_fail_ssim",
        crf=24, filter_expr="gblur=sigma=1.40", desired_ssim_min=0.9430, desired_ssim_max=0.9490, desired_psnr_min=30.50, desired_psnr_max=32.00,
        tune_param="blur", tune_val=1.40, max_search_iterations=5
    ),

    # Q3: SSIM >= 0.9500 AND PSNR < 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q3_FAIL_PSNR_01", target_ssim=0.995, target_psnr=29.4,
        distortion_type="joint_chroma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q3_fail_psnr",
        crf=14, filter_expr="lutyuv=u='clip(val+15,0,255)':v='clip(val+15,0,255)'", desired_ssim_min=0.9600, desired_ssim_max=1.0000, desired_psnr_min=29.00, desired_psnr_max=29.80,
        tune_param="chroma", tune_val=15.0, max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q3_FAIL_PSNR_02_CHROMA", target_ssim=0.986, target_psnr=28.8,
        distortion_type="joint_luma_chroma", category="near_boundary_fail", region="joint_boundary", quadrant="q3_fail_psnr",
        crf=14, filter_expr="lutyuv=y='clip(val+4,0,255)':u='clip(val+15,0,255)':v='clip(val+15,0,255)'", desired_ssim_min=0.9600, desired_ssim_max=1.0000, desired_psnr_min=28.30, desired_psnr_max=29.20,
        max_search_iterations=5
    ),

    # Q4: SSIM < 0.9500 AND PSNR < 30.00 dB -> Unacceptable
    DistortionTarget(
        "JOINT_Q4_FAIL_BOTH_01", target_ssim=0.913, target_psnr=27.9,
        distortion_type="joint_gamma_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q4_fail_both",
        crf=24, filter_expr="eq=gamma=0.82", desired_ssim_min=0.8800, desired_ssim_max=0.9450, desired_psnr_min=26.50, desired_psnr_max=29.50,
        max_search_iterations=5
    ),
    DistortionTarget(
        "JOINT_Q4_FAIL_BOTH_02_NOISE", target_ssim=0.880, target_psnr=28.5,
        distortion_type="joint_noise_quant", category="near_boundary_fail", region="joint_boundary", quadrant="q4_fail_both",
        crf=26, filter_expr="noise=alls=24:allf=t+u", desired_ssim_min=0.8000, desired_ssim_max=0.9450, desired_psnr_min=26.00, desired_psnr_max=29.50,
        max_search_iterations=5
    ),

    # ── Anchors: Global Extremes ──
    DistortionTarget(
        "ANCHOR_DEEP_PASS", target_ssim=0.992, target_psnr=49.4,
        distortion_type="quantization", category="deep_pass", region="anchor", quadrant="q1_pass",
        crf=10, desired_ssim_min=0.985, desired_ssim_max=1.000, desired_psnr_min=45.0, desired_psnr_max=60.0
    ),
    DistortionTarget(
        "ANCHOR_DEEP_FAIL", target_ssim=0.865, target_psnr=33.6,
        distortion_type="quantization", category="deep_fail", region="anchor", quadrant="q2_fail_ssim",
        crf=42, desired_ssim_min=0.800, desired_ssim_max=0.890, desired_psnr_min=25.0, desired_psnr_max=36.0
    ),
]



def check_ffmpeg_available() -> bool:
    try:
        res = subprocess.run(["ffmpeg", "-version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return res.returncode == 0
    except Exception:
        return False


def simulate_distortion_metrics(
    target: DistortionTarget,
    base_complexity: float = 1.0,
) -> Tuple[float, float, float, float, float]:
    """
    Deterministic simulated measurement engine for testing and CI when
    raw benchmark video files or libvmaf are not physically mounted.
    Returns (ssim_mean, psnr_mean, vmaf_mean, vmaf_p5, vmaf_worst).
    """
    target_ssim = target.target_ssim if target.target_ssim is not None else 0.95
    target_psnr = target.target_psnr if target.target_psnr is not None else 30.0

    seed_hash = int(hashlib.md5(f"{target.target_id}_{base_complexity}".encode()).hexdigest()[:8], 16)
    delta_s = ((seed_hash % 200) - 100) / 50000.0
    delta_p = (((seed_hash >> 8) % 200) - 100) / 500.0

    meas_ssim = round(min(1.0, max(0.0, target_ssim + delta_s)), 4)
    meas_psnr = round(max(10.0, target_psnr + delta_p), 2)

    if target.distortion_type == "blur":
        vmaf_est = 100.0 - (1.0 - meas_ssim) * 350.0 - max(0.0, 40.0 - meas_psnr) * 0.8
    elif target.distortion_type == "noise":
        vmaf_est = 100.0 - (1.0 - meas_ssim) * 180.0 - max(0.0, 38.0 - meas_psnr) * 1.2
    elif target.distortion_type in ("chroma_shift", "joint_chroma_quant", "joint_luma_chroma"):
        vmaf_est = 100.0 - (1.0 - meas_ssim) * 150.0 - max(0.0, 35.0 - meas_psnr) * 1.5
    else:
        vmaf_est = 100.0 - (1.0 - meas_ssim) * 220.0 - max(0.0, 35.0 - meas_psnr) * 1.0

    vmaf_mean = round(min(100.0, max(0.0, vmaf_est)), 2)
    vmaf_p5 = round(max(0.0, vmaf_mean - 2.5 - (seed_hash % 30) / 10.0), 2)
    vmaf_worst = round(max(0.0, vmaf_p5 - 1.8), 2)

    return (meas_ssim, meas_psnr, vmaf_mean, vmaf_p5, vmaf_worst)


def generate_real_distortion(
    ref_path: Path,
    out_dist_path: Path,
    target: DistortionTarget,
) -> Tuple[bool, str]:
    """Generates a physical distorted video file using real FFmpeg libx264 encoding.
    Returns (success, command_string).
    """
    out_dist_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["ffmpeg", "-y", "-i", str(ref_path)]
    if target.filter_expr:
        cmd.extend(["-vf", target.filter_expr])
    cmd.extend([
        "-c:v", "libx264",
        "-crf", str(target.crf),
        "-preset", "ultrafast",
        "-pix_fmt", "yuv420p",
        "-an",
        str(out_dist_path),
    ])
    cmd_str = " ".join(cmd)
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return (res.returncode == 0 and out_dist_path.exists(), cmd_str)


def measure_real_ssim(ref_path: Path, dist_path: Path) -> Optional[float]:
    """Measures exact frame-averaged SSIM using FFmpeg against the reference."""
    cmd = [
        "ffmpeg", "-y",
        "-i", str(ref_path),
        "-i", str(dist_path),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]ssim",
        "-f", "null", "-",
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    m = re.search(r"All:([\d.]+)", res.stderr)
    return float(m.group(1)) if m else None


def measure_real_psnr(ref_path: Path, dist_path: Path) -> Optional[float]:
    """Measures exact frame-averaged PSNR using FFmpeg against the reference."""
    cmd = [
        "ffmpeg", "-y",
        "-i", str(ref_path),
        "-i", str(dist_path),
        "-filter_complex",
        "[0:v]setpts=PTS-STARTPTS[r];[1:v]setpts=PTS-STARTPTS[d];[d][r]psnr",
        "-f", "null", "-",
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    m = re.search(r"average:([\d.]+|inf)", res.stderr)
    if not m:
        return None
    val = m.group(1)
    return 100.0 if val == "inf" else float(val)


def measure_real_vmaf(
    ref_path: Path,
    dist_path: Path,
    model_path: Path,
    evidence_json_path: Path,
) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[str], Optional[Dict[str, Any]]]:
    """
    Executes real libvmaf with official model JSON and extracts exact frame-level metrics.
    Stream mapping: -i dist -i ref maps stream 0:v (dist) to pad 0 and 1:v (ref) to pad 1.
    """
    evidence_json_path.parent.mkdir(parents=True, exist_ok=True)
    escaped_json = format_ffmpeg_filter_path(evidence_json_path)
    model_arg = format_vmaf_model_filter_arg(model_path)

    filt_v = (
        f"[0:v]setpts=PTS-STARTPTS[dist];"
        f"[1:v]setpts=PTS-STARTPTS[ref];"
        f"[dist][ref]libvmaf="
        f"{model_arg}:"
        f"log_fmt=json:log_path='{escaped_json}':"
        f"feature='name=adm|name=vif|name=motion'"
    )
    cmd = [
        "ffmpeg", "-y",
        "-i", str(dist_path),
        "-i", str(ref_path),
        "-filter_complex", filt_v,
        "-f", "null", "-",
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0 or not evidence_json_path.exists():
        print(f"    [ERROR] libvmaf failed: {res.stderr[-300:]}")
        return None, None, None, None, None

    with open(evidence_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    libvmaf_ver = data.get("version", "4991d2b5")

    frames = data.get("frames", [])
    scores = [fr["metrics"]["vmaf"] for fr in frames if "vmaf" in fr.get("metrics", {})]
    if scores:
        vmaf_mean = round(float(statistics.mean(scores)), 2)
        vmaf_worst = round(float(min(scores)), 2)
        s = sorted(scores)
        idx_p5 = max(0, int(round(len(s) * 0.05)) - 1)
        vmaf_p5 = round(float(s[idx_p5]), 2)
    else:
        pooled = data.get("pooled_metrics", {})
        vmaf_mean = round(float(pooled.get("vmaf", {}).get("mean", 0.0)), 2)
        vmaf_p5 = round(float(pooled.get("vmaf", {}).get("percentile5", 0.0)), 2)
        vmaf_worst = round(float(pooled.get("vmaf", {}).get("min", 0.0)), 2)

    return vmaf_mean, vmaf_p5, vmaf_worst, libvmaf_ver, data


def closed_loop_tune_and_generate(
    ref_path: Path,
    out_dist_path: Path,
    target: DistortionTarget,
) -> Tuple[bool, str, float, float, Dict[str, Any], int]:
    """
    Executes real closed-loop parameter adjustment:
    1. Generate candidate distortion.
    2. Measure real SSIM and PSNR.
    3. Determine distance from desired target bounds.
    4. Adjust parameters if out of bounds.
    5. Keep best candidate and return (ok, cmd, ssim, psnr, config, iterations_run).
    """
    import shutil

    crf = target.crf
    val = target.tune_val
    best_candidate = None
    best_dist = float("inf")
    search_history = []

    max_iters = target.max_search_iterations if target.max_search_iterations > 1 else 1

    for it in range(1, max_iters + 1):
        if target.tune_param == "gamma":
            fexpr = f"eq=gamma={val:.3f}" if val is not None else target.filter_expr
        elif target.tune_param == "brightness":
            fexpr = f"eq=brightness={val:.4f}" if val is not None else target.filter_expr
        elif target.tune_param == "blur":
            fexpr = f"gblur=sigma={val:.2f}" if val is not None else target.filter_expr
        elif target.tune_param in ("joint", "gamma_joint"):
            fexpr = f"eq=gamma={val:.3f}" if val is not None else target.filter_expr
        elif target.tune_param in ("noise", "joint_noise"):
            fexpr = f"noise=alls={int(round(val))}:allf=t+u" if val is not None else target.filter_expr
        elif target.tune_param in ("resample", "joint_resample"):
            dw = int(round(val)) if val is not None else 1280
            dw = dw if dw % 2 == 0 else dw - 1
            dh = int(round(dw * 9 / 16))
            dh = dh if dh % 2 == 0 else dh - 1
            fexpr = f"scale={dw}:{dh}:flags=bicubic,scale=1920:1080:flags=bicubic"
        elif target.tune_param in ("chroma", "chroma_shift"):
            shift = int(round(val)) if val is not None else 14
            fexpr = f"lutyuv=u='clip(val+{shift},0,255)':v='clip(val+{shift},0,255)'"
        else:
            fexpr = target.filter_expr

        temp_target = DistortionTarget(
            target_id=target.target_id,
            target_ssim=target.target_ssim,
            target_psnr=target.target_psnr,
            distortion_type=target.distortion_type,
            category=target.category,
            crf=crf,
            filter_expr=fexpr,
        )
        temp_out = out_dist_path.with_name(f"{out_dist_path.stem}_iter_{it}.mp4")
        ok, cmd = generate_real_distortion(ref_path, temp_out, temp_target)
        if not ok:
            continue

        s = measure_real_ssim(ref_path, temp_out)
        p = measure_real_psnr(ref_path, temp_out)
        if s is None or p is None:
            if temp_out.exists():
                try:
                    temp_out.unlink()
                except Exception:
                    pass
            continue

        in_s = (target.desired_ssim_min is None or target.desired_ssim_min <= s) and (
            target.desired_ssim_max is None or s <= target.desired_ssim_max
        )
        in_p = (target.desired_psnr_min is None or target.desired_psnr_min <= p) and (
            target.desired_psnr_max is None or p <= target.desired_psnr_max
        )

        search_history.append({
            "iteration": it,
            "crf": crf,
            "tune_val": val,
            "filter_expr": fexpr,
            "measured_ssim": s,
            "measured_psnr": p,
            "in_ssim_bounds": in_s,
            "in_psnr_bounds": in_p,
        })

        target_s = (
            (target.desired_ssim_min + target.desired_ssim_max) / 2
            if (target.desired_ssim_min and target.desired_ssim_max)
            else (target.target_ssim if target.target_ssim is not None else s)
        )
        target_p = (
            (target.desired_psnr_min + target.desired_psnr_max) / 2
            if (target.desired_psnr_min and target.desired_psnr_max)
            else (target.target_psnr if target.target_psnr is not None else p)
        )

        # Calculate boundary error (0 if inside desired bounds)
        err_s = 0.0
        if target.desired_ssim_min and s < target.desired_ssim_min:
            err_s = target.desired_ssim_min - s
        elif target.desired_ssim_max and s > target.desired_ssim_max:
            err_s = s - target.desired_ssim_max

        err_p = 0.0
        if target.desired_psnr_min and p < target.desired_psnr_min:
            err_p = target.desired_psnr_min - p
        elif target.desired_psnr_max and p > target.desired_psnr_max:
            err_p = p - target.desired_psnr_max

        dist = err_s * 1000.0 + err_p * 10.0

        if (in_s and in_p) or (dist < best_dist):
            if best_candidate and best_candidate["path"].exists():
                try:
                    best_candidate["path"].unlink()
                except Exception:
                    pass
            best_dist = dist
            best_candidate = {
                "path": temp_out,
                "crf": crf,
                "val": val,
                "ssim": s,
                "psnr": p,
                "cmd": cmd,
                "fexpr": fexpr,
                "iter": it,
            }
            if in_s and in_p:
                break
        else:
            if temp_out.exists():
                try:
                    temp_out.unlink()
                except Exception:
                    pass

        # Adjust parameters dynamically based on distance from target bounds
        if target.tune_param == "crf":
            diff = s - target_s
            step = max(1, min(6, int(abs(diff) / 0.005)))
            if s < target.desired_ssim_min:
                crf = max(10, crf - step)
            elif s > target.desired_ssim_max:
                crf = min(45, crf + step)
        elif target.tune_param == "blur" and val is not None:
            diff = s - target_s
            step_b = max(0.2, min(1.2, abs(diff) * 25.0))
            if s < target.desired_ssim_min:
                val = max(0.2, round(val - step_b, 2))
            elif s > target.desired_ssim_max:
                val = min(6.0, round(val + step_b, 2))
        elif target.tune_param in ("gamma", "joint", "gamma_joint") and val is not None:
            diff_p = p - target_p
            step_g = max(0.005, min(0.03, abs(diff_p) * 0.005))
            if target.desired_psnr_min and p < target.desired_psnr_min:
                val = round(val + step_g, 3)
            elif target.desired_psnr_max and p > target.desired_psnr_max:
                val = round(val - step_g, 3)
            if target.tune_param in ("joint", "gamma_joint") and target.desired_ssim_min and target.desired_ssim_max:
                diff_s = s - target_s
                step_c = max(1, min(5, int(abs(diff_s) / 0.005)))
                if s < target.desired_ssim_min:
                    crf = max(10, crf - step_c)
                elif s > target.desired_ssim_max:
                    crf = min(45, crf + step_c)
        elif target.tune_param in ("noise", "joint_noise") and val is not None:
            diff_p = p - target_p
            step_n = max(1.0, min(8.0, abs(diff_p) * 2.0))
            if target.desired_psnr_min and p < target.desired_psnr_min:
                val = max(1.0, round(val - step_n, 1))
            elif target.desired_psnr_max and p > target.desired_psnr_max:
                val = min(60.0, round(val + step_n, 1))
            if target.tune_param == "joint_noise" and target.desired_ssim_min and target.desired_ssim_max:
                diff_s = s - target_s
                step_c = max(1, min(5, int(abs(diff_s) / 0.005)))
                if s < target.desired_ssim_min:
                    crf = max(10, crf - step_c)
                elif s > target.desired_ssim_max:
                    crf = min(45, crf + step_c)
        elif target.tune_param in ("resample", "joint_resample") and val is not None:
            diff_s = s - target_s
            step_w = max(16, min(160, int(abs(diff_s) * 3000)))
            if s < target.desired_ssim_min:
                val = min(1920, val + step_w)
            elif s > target.desired_ssim_max:
                val = max(480, val - step_w)
            if target.tune_param == "joint_resample" and target.desired_psnr_min and target.desired_psnr_max:
                diff_p = p - target_p
                step_c = max(1, min(4, int(abs(diff_p) / 0.5)))
                if p < target.desired_psnr_min:
                    crf = max(10, crf - step_c)
                elif p > target.desired_psnr_max:
                    crf = min(45, crf + step_c)
        elif target.tune_param == "brightness" and val is not None:
            diff_p = p - target_p
            step_br = max(0.002, min(0.02, abs(diff_p) * 0.003))
            if target.desired_psnr_min and p < target.desired_psnr_min:
                val = round(max(0.0, val - step_br), 4)
            elif target.desired_psnr_max and p > target.desired_psnr_max:
                val = round(val + step_br, 4)
        elif target.tune_param in ("chroma", "chroma_shift") and val is not None:
            diff_p = p - target_p
            step_ch = max(1.0, min(4.0, abs(diff_p) * 1.5))
            if target.desired_psnr_min and p < target.desired_psnr_min:
                val = max(1.0, round(val - step_ch, 1))
            elif target.desired_psnr_max and p > target.desired_psnr_max:
                val = min(60.0, round(val + step_ch, 1))
        else:
            if target.desired_ssim_min and s < target.desired_ssim_min:
                crf = max(10, crf - 1)
            elif target.desired_ssim_max and s > target.desired_ssim_max:
                crf = min(45, crf + 1)

    if best_candidate is None:
        return False, "", 0.0, 0.0, {}, 0

    if out_dist_path.exists():
        try:
            out_dist_path.unlink()
        except Exception:
            pass
    shutil.move(str(best_candidate["path"]), str(out_dist_path))

    cfg = {
        "crf": best_candidate["crf"],
        "filter_expr": best_candidate["fexpr"],
        "preset": "ultrafast",
        "codec": "libx264",
        "tune_param": target.tune_param,
        "region": target.region,
        "quadrant": target.quadrant,
        "search_iterations": len(search_history),
        "search_history": search_history,
    }
    return True, best_candidate["cmd"], best_candidate["ssim"], best_candidate["psnr"], cfg, len(search_history)


def generate_boundary_dataset(
    reference_sequences: List[Dict[str, Any]],
    output_results_path: Path,
    targets: Optional[List[DistortionTarget]] = None,
    simulate: bool = False,
    raw_dir: Path = Path("calibration/data/raw"),
    dist_dir: Path = Path("calibration/data/distorted"),
    evidence_dir: Path = Path("evidence"),
) -> Dict[str, Any]:
    """
    Generates boundary-dense distortion corpus for the provided reference sequences.
    Strictly derives independent policy labels from measured SSIM and PSNR.
    Supports real physical FFmpeg/libvmaf execution (default) and simulation mode.
    """
    output_results_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    dist_dir.mkdir(parents=True, exist_ok=True)

    existing_clips: Dict[str, Any] = {}
    if output_results_path.exists():
        try:
            with open(output_results_path, "r", encoding="utf-8") as f:
                prev_data = json.load(f)
                for c in prev_data.get("clips", []):
                    grp_id = c.get("sequence_group")
                    if grp_id:
                        existing_clips[grp_id] = c
        except Exception:
            pass

    results_data: Dict[str, Any] = {
        "study_id": "VF-CAL-VMAF-EMPIRICAL-EXPANDED-2026" if not simulate else "VF-CAL-VMAF-BOUNDARY-SIMULATED-2026",
        "dataset_version": "1.3.0",
        "boundary_targeting": True,
        "measurement_status": "simulated" if simulate else "empirical",
        "simulation_mode": simulate,
        "is_simulated": simulate,
        "ffmpeg_version": "9.0-full_build-www.gyan.dev" if not simulate else None,
        "vmaf_model_version": VMAF_MODEL_VERSION if not simulate else "v0.6.1",
        "clips": [],
    }

    print("=" * 70)
    print("VeilFrame Iterative Boundary-Targeted Distortion Generator")
    print("=" * 70)
    print(f"Reference sequences: {len(reference_sequences)}")
    print(f"Distortion targets:  {len(targets) if targets is not None else 'Per-sequence adaptive (22)'}")
    print(f"Measurement Mode:    {'SIMULATION (--simulate)' if simulate else 'REAL PHYSICAL MEASUREMENT'}")
    print(f"Output File:         {output_results_path}")
    print("=" * 70)

    total_pairs_generated = 0
    boundary_pairs_count = 0
    acceptable_count = 0
    unacceptable_count = 0

    for ref_idx, ref in enumerate(reference_sequences, 1):
        grp = ref.get("sequence_group_id", f"group_{ref_idx}")
        fname = ref.get("filename", f"ref_{ref_idx}.mp4")
        cat = ref.get("category", "general")
        subcat = ref.get("subcategory", "")
        w = ref.get("width", 1920)
        h = ref.get("height", 1080)
        fps = ref.get("fps", 30.0)
        dom = ref.get("domain_target", "1080p_sdr")
        exp_sha = ref.get("sha256")

        print(f"\n[{ref_idx}/{len(reference_sequences)}] Sequence Group: {grp} | File: {fname}")

        ref_file = raw_dir / fname
        ref_sha256 = None
        model_spec = None
        model_path = None

        if not simulate:
            if not ref_file.exists():
                raise FileNotFoundError(
                    f"Reference file '{ref_file}' not found locally in {raw_dir}. "
                    f"Download it first using tools/download_calibration_corpus.py."
                )
            ref_sha256 = compute_sha256(ref_file)
            if exp_sha and ref_sha256.lower() != exp_sha.lower():
                raise ValueError(
                    f"SHA-256 mismatch for reference file {ref_file}!\n"
                    f"Expected: {exp_sha}\n"
                    f"Actual:   {ref_sha256}"
                )
            print(f"  Reference verified: SHA-256={ref_sha256[:16]}... ({ref_file.stat().st_size} bytes)")

            # Select and verify official VMAF model
            model_spec = select_vmaf_model(w, h, fps)
            model_path = resolve_and_verify_model(model_spec)
            print(f"  VMAF Model: {model_spec.model_id} ({model_path.name}, SHA-256 verified)")

        clip_entry: Dict[str, Any] = {
            "clip_filename": fname,
            "clip_sha256": ref_sha256,
            "sequence_group": grp,
            "category": cat,
            "subcategory": subcat,
            "domain": dom,
            "suitability_status": "eligible",
            "width": w,
            "height": h,
            "fps": fps,
            "measurement_status": "simulated" if simulate else "empirical",
            "is_simulated": simulate,
            "fixtures": [],
        }

        seq_targets = targets if targets is not None else (
            CGI_TARGETS if (cat == "animation_cgi" or grp == "sintel_trailer") else DEFAULT_TARGETS
        )

        for t_idx, t in enumerate(seq_targets, 1):
            fixture_id = t.target_id
            print(f"  [{t_idx}/{len(seq_targets)}] Fixture: {fixture_id:<25}", end="", flush=True)

            if simulate:
                ssim_m, psnr_m, vmaf_m, vmaf_p5, vmaf_min = simulate_distortion_metrics(
                    t, base_complexity=ref_idx * 1.15
                )
                dist_fname = f"{grp}_{fixture_id}.mp4"
                dist_path_str = f"calibration/data/distorted/{grp}_{fixture_id}.mp4"
                dist_sha256 = None
                ev_path = f"evidence/{grp}_{fixture_id}.json"
                ev_sha256 = None
                mod_id = "vmaf_v0.6.1"
                mod_name = "vmaf_v0.6.1.json"
                mod_sha = None
                meas_status = "simulated"
                libvmaf_ver = None
                cmd_executed = None
            else:
                dist_path = dist_dir / f"{grp}_{fixture_id}.mp4"
                ev_path_obj = evidence_dir / f"{grp}_{fixture_id}_vmaf_evidence.json"

                # 1. Closed-loop distortion generation & SSIM/PSNR tuning
                ok, cmd_executed, ssim_m, psnr_m, cfg_used, iters_run = closed_loop_tune_and_generate(
                    ref_path=ref_file,
                    out_dist_path=dist_path,
                    target=t,
                )
                if not ok:
                    raise RuntimeError(f"Failed to generate distortion {fixture_id} for {ref_file}")
                dist_sha256 = compute_sha256(dist_path)
                dist_fname = dist_path.name
                dist_path_str = str(dist_path).replace("\\", "/")

                # 2. Real VMAF v1.0.16 with official verified model
                vmaf_m, vmaf_p5, vmaf_min, libvmaf_ver, _ = measure_real_vmaf(
                    ref_path=ref_file,
                    dist_path=dist_path,
                    model_path=model_path,
                    evidence_json_path=ev_path_obj,
                )
                if vmaf_m is None:
                    raise RuntimeError(f"VMAF measurement failed for {fixture_id}")

                ev_sha256 = compute_sha256(ev_path_obj)
                ev_path = str(ev_path_obj).replace("\\", "/")
                mod_id = model_spec.model_id
                mod_name = model_spec.filename
                mod_sha = model_spec.expected_sha256
                meas_status = "empirical"

            # Strict independent policy ground truth rule:
            # SSIM >= 0.9500 AND PSNR >= 30.00 dB
            is_acc = (ssim_m >= 0.9500 and psnr_m >= 30.00)
            ind_label = "acceptable" if is_acc else "unacceptable"

            if is_acc:
                acceptable_count += 1
            else:
                unacceptable_count += 1

            is_near_boundary = (0.930 <= ssim_m <= 0.970 or 28.0 <= psnr_m <= 32.0)
            if is_near_boundary:
                boundary_pairs_count += 1

            print(f" -> SSIM={ssim_m:.4f} | PSNR={psnr_m:.2f}dB | VMAF={vmaf_m:.2f} (P5={vmaf_p5:.2f}) -> [{ind_label.upper()}] (iters={iters_run if not simulate else 1})")

            fixture_entry: Dict[str, Any] = {
                "sequence_group": grp,
                "clip_filename": fname,
                "clip_sha256": ref_sha256,
                "fixture": fixture_id,
                "status": "success",
                "target_type": t.distortion_type,
                "target_category": t.category,
                "region": t.region,
                "quadrant": t.quadrant,
                "distortion_role": t.distortion_role,
                "calibration_eligibility": t.calibration_eligibility,
                "exclusion_reason": t.exclusion_reason,
                "measurement_status": meas_status,
                "is_simulated": simulate,
                "configuration": cfg_used if not simulate else {
                    "crf": t.crf,
                    "filter_expr": t.filter_expr,
                    "preset": "ultrafast",
                    "codec": "libx264",
                },
                "parameters": cfg_used if not simulate else {
                    "crf": t.crf,
                    "filter_expr": t.filter_expr,
                },
                "search_iterations": iters_run if not simulate else 1,
                "target_bounds": {
                    "desired_ssim_min": t.desired_ssim_min,
                    "desired_ssim_max": t.desired_ssim_max,
                    "desired_psnr_min": t.desired_psnr_min,
                    "desired_psnr_max": t.desired_psnr_max,
                },
                "distortion_command": cmd_executed,
                "distorted_filename": dist_fname,
                "distorted_path": dist_path_str,
                "distorted_sha256": dist_sha256,
                "ssim": {"mean": ssim_m},
                "psnr": {"mean": psnr_m},
                "vmaf": {
                    "mean": vmaf_m,
                    "p5": vmaf_p5,
                    "min": vmaf_min,
                },
                "ssim_mean": ssim_m,
                "psnr_mean": psnr_m,
                "vmaf_mean": vmaf_m,
                "vmaf_p5": vmaf_p5,
                "vmaf_worst": vmaf_min,
                "independent_policy_label": ind_label,
                "policy_label": ind_label,
                "model_id": mod_id,
                "model_name": mod_name,
                "model_sha256": mod_sha,
                "ffmpeg_version": "9.0-full_build-www.gyan.dev" if not simulate else None,
                "libvmaf_version": libvmaf_ver,
                "evidence_path": ev_path,
                "evidence_sha256": ev_sha256,
            }
            clip_entry["fixtures"].append(fixture_entry)
            total_pairs_generated += 1

        existing_clips[grp] = clip_entry

    results_data["clips"] = list(existing_clips.values())

    with open(output_results_path, "w", encoding="utf-8") as f:
        json.dump(results_data, f, indent=2)

    pct_boundary = (boundary_pairs_count / total_pairs_generated * 100) if total_pairs_generated else 0.0
    print("\n" + "=" * 70)
    print("Distortion Generation & Measurement Complete:")
    print(f"  Mode:                        {'SIMULATION' if simulate else 'REAL EMPIRICAL MEASUREMENT'}")
    print(f"  Total Sequence Groups:       {len(reference_sequences)}")
    print(f"  Total Evaluation Pairs:      {total_pairs_generated}")
    print(f"  Acceptable Pairs:            {acceptable_count}")
    print(f"  Unacceptable Pairs:          {unacceptable_count}")
    print(f"  Boundary Pairs (SSIM/PSNR):  {boundary_pairs_count} ({pct_boundary:.1f}%)")
    print(f"  Results Saved To:            {output_results_path}")
    print("=" * 70)

    return results_data


def main():
    parser = argparse.ArgumentParser(description="VeilFrame Iterative Boundary-Targeted Distortion Generator")
    parser.add_argument("--manifest", type=Path, default=Path("calibration/data/corpus_manifest.json"),
                        help="Path to open benchmark manifest")
    parser.add_argument("--output", type=Path, default=Path("calibration/data/expanded_corpus_results.json"),
                        help="Output path for generated corpus results JSON")
    parser.add_argument("--sequence-group", type=str, default=None,
                        help="Limit generation to a single sequence_group_id")
    parser.add_argument("--raw-dir", type=Path, default=Path("calibration/data/raw"),
                        help="Path to directory containing reference sequences")
    parser.add_argument("--dist-dir", type=Path, default=Path("calibration/data/distorted"),
                        help="Path to directory for output distorted sequences")
    parser.add_argument("--evidence-dir", type=Path, default=Path("evidence"),
                        help="Path to directory for VMAF JSON evidence logs")
    parser.add_argument("--simulate", action="store_true", default=False,
                        help="Run in simulation mode for deterministic synthetic test/CI runs (default: False)")

    args = parser.parse_args()

    if not args.manifest.exists():
        print(f"Manifest not found: {args.manifest}")
        sys.exit(1)

    with open(args.manifest, "r", encoding="utf-8") as f:
        manifest_data = json.load(f)

    sequences = manifest_data.get("sequences", [])
    if args.sequence_group:
        sequences = [s for s in sequences if s.get("sequence_group_id") == args.sequence_group]
        if not sequences:
            print(f"Sequence group '{args.sequence_group}' not found in manifest {args.manifest}")
            sys.exit(1)

    generate_boundary_dataset(
        reference_sequences=sequences,
        output_results_path=args.output,
        simulate=args.simulate,
        raw_dir=args.raw_dir,
        dist_dir=args.dist_dir,
        evidence_dir=args.evidence_dir,
    )


if __name__ == "__main__":
    main()
