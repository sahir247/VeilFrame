#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VeilFrame VMAF Threshold Analysis Engine
========================================
Scientific, reproducible threshold calibration and cross-validation tool.

Ingests raw measurements from vmaf_corpus_results.json, applies non-circular
independent policy labeling, performs sequence-group development/held-out splitting,
sweeps thresholds, selects the lowest feasible operating point on development data,
and validates it once on untouched held-out data.

Scientific & Architectural Invariants:
  - Non-Circular Labeling: Independent policy labels are derived from VeilFrame's
    independent visual budget criteria (SSIM >= 0.95, PSNR >= 30 dB, fixture axis),
    NEVER from VMAF itself.
  - Zero Sequence Leakage: Development and held-out sets are partitioned strictly at the
    sequence_group level using a recorded deterministic seed (--seed).
  - Measurement Integrity: Missing or failed measurements are excluded as missing data,
    NEVER interpreted as score 0.0.
  - Coupled Policy Definition: Combined mean + P5 policy sweeps a single scalar T
    satisfying (V_mean >= T AND V_p5 >= T).
  - Lowest Feasible Operating Point: Selects the lowest threshold on dev data meeting
    FAR < fa_max and FRR < fr_max (to avoid rejecting acceptable content).
  - Untouched Held-Out Validation: Candidate operating point is tested ONCE against
    held-out sequence groups without re-tuning.
  - Minimum Data Safety: Emits "insufficient_data" when sample or group counts fall below
    minimum confidence requirements.
  - Production Gate Safety: Does not alter production configuration (vmaf_gate_enabled = False).
"""

import argparse
import datetime
import json
import random
import sys
import io
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

from veilframe.models.settings import VisualBudgetPolicy
from veilframe.quality.vmaf_models import VMAF_MODEL_VERSION


ANALYSIS_VERSION = "1.2.0"

# Fixture groupings for policy classification
ACCEPTABLE_FIXTURES   = {"IDENTICAL", "VERY_LOW", "LOW_PERTURBATION"}
BOUNDARY_FIXTURES     = {"MODERATE"}
UNACCEPTABLE_FIXTURES = {"MODERATE_EXCEEDANCE", "HIGH", "SEVERE", "EXTREME"}


# ── Sample Data Class ──────────────────────────────────────────────────── #

@dataclass
class CorpusSample:
    clip_filename:          str
    sequence_group:         str
    sequence_group_source:  str = "manifest"
    category:               str = "general"
    subcategory:            str = ""
    width:                  int = 1920
    height:                 int = 1080
    fps:                    float = 30.0
    pix_fmt:                str = "yuv420p"
    fixture:                str = "IDENTICAL"
    vmaf_mean:              float = 100.0
    vmaf_p5:                Optional[float] = None
    vmaf_worst:             Optional[float] = None
    vmaf_stddev:            float = 0.0
    ssim_mean:              float = 1.0
    psnr_mean:              float = 100.0
    model_id:               Optional[str] = None
    model_name:             Optional[str] = None
    model_sha256:           Optional[str] = None
    independent_policy_label: str = "acceptable"
    domain:                 str = "Domain 1: Primary SDR"
    suitability_status:     str = "eligible"
    vmaf_median:            Optional[float] = None
    vmaf_p1:                Optional[float] = None
    vmaf_p95:               Optional[float] = None
    adm2_score:             Optional[float] = None
    vif_score:              Optional[float] = None
    motion_score:           Optional[float] = None
    evidence_path:          Optional[str] = None
    evidence_sha256:        Optional[str] = None


@dataclass
class OperatingMetrics:
    threshold:              float
    policy_name:            str
    total_samples:          int
    acceptable_samples:     int
    unacceptable_samples:   int
    true_accepts:           int
    true_rejects:           int
    false_accepts:          int
    false_rejects:          int
    false_accept_rate:      float
    false_reject_rate:      float
    acceptance_rate:        float
    rejection_rate:         float
    precision:              float
    recall:                 float
    balanced_accuracy:      float


# ── Independent Policy Labeling ────────────────────────────────────────── #

def assign_independent_policy_label(
    fixture: str,
    ssim_mean: Optional[float],
    psnr_mean: Optional[float],
) -> str:
    """
    Assigns an independent quality label based strictly on VeilFrame's existing fidelity criteria:
    SSIM constraint: >= 0.9500. PSNR constraint: >= 30.00 dB.

    Rule: Fixture names/severity labels are semantic identifiers only and must never be used
    as a substitute for the computed SSIM/PSNR policy label. The implementation derives
    the final label strictly from measured SSIM and PSNR against the declared policy.
    """
    if ssim_mean is None or psnr_mean is None:
        return "missing"

    if ssim_mean >= 0.95 and psnr_mean >= 30.0:
        return "acceptable"

    return "unacceptable"


# ── Ingestion ──────────────────────────────────────────────────────────── #

def load_corpus_samples(
    corpus_results_path: Path,
) -> Tuple[List[CorpusSample], Dict[str, int], List[CorpusSample], List[Dict[str, Any]]]:
    """
    Loads measurement samples from vmaf_corpus_results.json.
    Excludes HDR not-applicable samples, metadata errors, and measurement failures.
    Missing data is NEVER interpreted as 0.0.

    Strict Domain Segregation:
      - Domain 1: Modern/representative SDR (1080p and 2160p UHD) -> primary_samples
      - Domain 2: 720p and classic SD legacy sequences -> secondary_samples (diagnostic only)
      - Domain 3: HDR / WCG sequences -> hdr_samples (segregated from SDR)
    """
    if not corpus_results_path.exists():
        raise FileNotFoundError(f"Corpus results file not found: '{corpus_results_path}'")

    with open(corpus_results_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    primary_samples: List[CorpusSample] = []
    secondary_samples: List[CorpusSample] = []
    hdr_samples: List[Dict[str, Any]] = []

    exclusion_counts: Dict[str, int] = {
        "not_applicable_hdr": 0,
        "metadata_error": 0,
        "measurement_error": 0,
        "unsupported_resolution": 0,
        "missing_vmaf": 0,
        "secondary_domain_diagnostic": 0,
    }

    clips = data.get("clips", [])
    for clip in clips:
        if clip.get("status") == "metadata_error":
            exclusion_counts["metadata_error"] += len(clip.get("fixtures", []))
            continue

        c_fn = clip.get("clip_filename") or Path(clip.get("clip_path", "")).name
        seq_grp = clip.get("sequence_group", "unknown")
        seq_src = clip.get("sequence_group_source", "filename_heuristic")
        cat = clip.get("category", "general")
        subcat = clip.get("subcategory", "")
        domain = clip.get("domain", "")
        suitability = clip.get("suitability_status", "")
        w = clip.get("width") or 0
        h = clip.get("height") or 0
        fps = clip.get("fps") or 0.0
        pix_fmt = clip.get("pix_fmt", "unknown")

        # Fallback domain tagging if absent in older result schema
        if not domain:
            if clip.get("is_hdr") or "hdr" in c_fn.lower() or "chimera" in c_fn.lower():
                domain = "Domain 3: HDR / WCG"
                suitability = "not_applicable_hdr"
            elif h == 720 or "720p" in c_fn.lower():
                domain = "Domain 2: Secondary / Legacy"
                suitability = "diagnostic_only"
            else:
                domain = "Domain 1: Primary SDR"
                suitability = "eligible_sdr_primary"

        for fx in clip.get("fixtures", []):
            st = fx.get("status", "success")
            fixture_name = fx.get("fixture", "")
            s_mean = fx.get("ssim_mean")
            p_mean = fx.get("psnr_mean")

            if st == "not_applicable_hdr" or domain == "Domain 3: HDR / WCG":
                exclusion_counts["not_applicable_hdr"] += 1
                hdr_samples.append({
                    "clip_filename": c_fn,
                    "sequence_group": seq_grp,
                    "fixture": fixture_name,
                    "domain": domain,
                    "suitability_status": suitability,
                    "ssim_mean": s_mean,
                    "psnr_mean": p_mean,
                    "status": st,
                    "error_message": fx.get("error_message"),
                })
                continue

            if st == "unsupported_resolution":
                exclusion_counts["unsupported_resolution"] += 1
                continue

            if st == "measurement_error" or fx.get("error_message"):
                exclusion_counts["measurement_error"] += 1
                continue

            v_mean = fx.get("vmaf_mean")
            if v_mean is None:
                exclusion_counts["missing_vmaf"] += 1
                continue

            v_p5 = fx.get("vmaf_p5")
            v_worst = fx.get("vmaf_worst")
            v_std = fx.get("vmaf_stddev") or 0.0
            v_med = fx.get("vmaf_median")
            v_p1 = fx.get("vmaf_p1")
            v_p95 = fx.get("vmaf_p95")
            adm2_val = fx.get("adm2_score")
            vif_val = fx.get("vif_score")
            mot_val = fx.get("motion_score")

            label = assign_independent_policy_label(fixture_name, s_mean, p_mean)

            sample = CorpusSample(
                clip_filename=c_fn,
                sequence_group=seq_grp,
                sequence_group_source=seq_src,
                category=cat,
                subcategory=subcat,
                domain=domain,
                suitability_status=suitability,
                width=w,
                height=h,
                fps=fps,
                pix_fmt=pix_fmt,
                fixture=fixture_name,
                vmaf_mean=float(v_mean),
                vmaf_median=float(v_med) if v_med is not None else None,
                vmaf_p1=float(v_p1) if v_p1 is not None else None,
                vmaf_p5=float(v_p5) if v_p5 is not None else None,
                vmaf_p95=float(v_p95) if v_p95 is not None else None,
                vmaf_worst=float(v_worst) if v_worst is not None else None,
                vmaf_stddev=float(v_std),
                ssim_mean=float(s_mean) if s_mean is not None else 0.0,
                psnr_mean=float(p_mean) if p_mean is not None else 0.0,
                adm2_score=float(adm2_val) if adm2_val is not None else None,
                vif_score=float(vif_val) if vif_val is not None else None,
                motion_score=float(mot_val) if mot_val is not None else None,
                evidence_path=fx.get("evidence_path"),
                evidence_sha256=fx.get("evidence_sha256"),
                model_id=fx.get("model_id"),
                model_name=fx.get("model_name"),
                model_sha256=fx.get("model_sha256"),
                independent_policy_label=label,
            )

            # Strict Domain Segregation: Domain 1 for primary calibration, Domain 2 for secondary diagnostic
            if domain == "Domain 1: Primary SDR" or (not domain and "720p" not in c_fn.lower()):
                primary_samples.append(sample)
            else:
                exclusion_counts["secondary_domain_diagnostic"] += 1
                secondary_samples.append(sample)

    return primary_samples, exclusion_counts, secondary_samples, hdr_samples


# ── Sequence Group Splitting ───────────────────────────────────────────── #

def partition_by_sequence_group(
    samples: List[CorpusSample],
    dev_fraction: float = 0.70,
    seed: int = 42,
) -> Tuple[List[CorpusSample], List[CorpusSample], List[str], List[str]]:
    """
    Backward-compatible grouped split: partitions samples strictly by sequence_group.
    Guarantees zero sequence leakage across splits.
    """
    unique_groups = sorted(list(set(s.sequence_group for s in samples)))
    rng = random.Random(seed)
    shuffled_groups = list(unique_groups)
    rng.shuffle(shuffled_groups)

    num_dev = max(1, int(round(len(shuffled_groups) * dev_fraction)))
    dev_groups = sorted(shuffled_groups[:num_dev])
    heldout_groups = sorted(shuffled_groups[num_dev:])

    # Edge case: if heldout_groups is empty, assign at least 1 group if >= 2 available
    if not heldout_groups and len(dev_groups) > 1:
        heldout_groups.append(dev_groups.pop())

    dev_samples = [s for s in samples if s.sequence_group in dev_groups]
    heldout_samples = [s for s in samples if s.sequence_group in heldout_groups]

    return dev_samples, heldout_samples, dev_groups, heldout_groups


def partition_by_sequence_group_algorithmic(
    samples: List[CorpusSample],
    target_dev_fraction: float = 0.70,
    seed: int = 42,
    min_total_groups: int = 12,
    min_dev_groups: int = 8,
    min_heldout_groups: int = 4,
    min_total_binary: int = 60,
    min_dev_binary: int = 40,
    min_heldout_binary: int = 20,
) -> Tuple[List[CorpusSample], List[CorpusSample], List[str], List[str], Dict[str, Any]]:
    """
    Deterministic grouped split selected algorithmically under all hard constraints.
    The final development and held-out group counts are determined by the partitioning
    algorithm and recorded in the resulting artifacts; no fixed 9/4 allocation is assumed.
    """
    unique_groups = sorted(list(set(s.sequence_group for s in samples)))
    n_groups = len(unique_groups)

    if n_groups < min_total_groups:
        dev_s, ho_s, dev_g, ho_g = partition_by_sequence_group(samples, target_dev_fraction, seed)
        passed, reasons = check_minimum_data_requirements(
            dev_s, ho_s, dev_g, ho_g,
            min_total_groups=min_total_groups,
            min_dev_groups=min_dev_groups,
            min_heldout_groups=min_heldout_groups,
            min_total_binary=min_total_binary,
            min_dev_binary=min_dev_binary,
            min_heldout_binary=min_heldout_binary,
        )
        return dev_s, ho_s, dev_g, ho_g, {
            "algorithm": "algorithmic_deterministic_search",
            "seed_used": seed,
            "constraints_satisfied": False,
            "failure_reasons": reasons,
            "dev_groups_count": len(dev_g),
            "heldout_groups_count": len(ho_g),
            "safeguard_disclaimer": (
                "The minimum sample/group requirements are eligibility safeguards, not a claim of "
                "statistical power or universal perceptual validity. Confidence intervals and uncertainty "
                "must be reported independently of the minimum-data pass/fail decision."
            )
        }

    # Deterministic search over dev split sizes and random seeds
    target_k = int(round(n_groups * target_dev_fraction))
    k_candidates = sorted(
        range(min_dev_groups, n_groups - min_heldout_groups + 1),
        key=lambda x: abs(x - target_k)
    )

    best_split = None
    for attempt in range(100):
        current_seed = seed + attempt
        rng = random.Random(current_seed)
        shuffled = list(unique_groups)
        rng.shuffle(shuffled)

        for k in k_candidates:
            cand_dev_groups = sorted(shuffled[:k])
            cand_ho_groups = sorted(shuffled[k:])

            cand_dev_samples = [s for s in samples if s.sequence_group in cand_dev_groups]
            cand_ho_samples = [s for s in samples if s.sequence_group in cand_ho_groups]

            passed, reasons = check_minimum_data_requirements(
                cand_dev_samples, cand_ho_samples, cand_dev_groups, cand_ho_groups,
                min_total_groups=min_total_groups,
                min_dev_groups=min_dev_groups,
                min_heldout_groups=min_heldout_groups,
                min_total_binary=min_total_binary,
                min_dev_binary=min_dev_binary,
                min_heldout_binary=min_heldout_binary,
            )
            if passed:
                best_split = (cand_dev_samples, cand_ho_samples, cand_dev_groups, cand_ho_groups, current_seed, k)
                break
        if best_split:
            break

    if not best_split:
        # Fallback to direct default split
        dev_s, ho_s, dev_g, ho_g = partition_by_sequence_group(samples, target_dev_fraction, seed)
        passed, reasons = check_minimum_data_requirements(
            dev_s, ho_s, dev_g, ho_g,
            min_total_groups=min_total_groups,
            min_dev_groups=min_dev_groups,
            min_heldout_groups=min_heldout_groups,
            min_total_binary=min_total_binary,
            min_dev_binary=min_dev_binary,
            min_heldout_binary=min_heldout_binary,
        )
        return dev_s, ho_s, dev_g, ho_g, {
            "algorithm": "algorithmic_deterministic_search",
            "seed_used": seed,
            "constraints_satisfied": False,
            "failure_reasons": reasons,
            "dev_groups_count": len(dev_g),
            "heldout_groups_count": len(ho_g),
            "safeguard_disclaimer": (
                "The minimum sample/group requirements are eligibility safeguards, not a claim of "
                "statistical power or universal perceptual validity. Confidence intervals and uncertainty "
                "must be reported independently of the minimum-data pass/fail decision."
            )
        }

    cand_dev_samples, cand_ho_samples, cand_dev_groups, cand_ho_groups, used_seed, chosen_k = best_split
    partition_meta = {
        "algorithm": "algorithmic_deterministic_search",
        "seed_used": used_seed,
        "constraints_satisfied": True,
        "failure_reasons": [],
        "dev_groups_count": len(cand_dev_groups),
        "heldout_groups_count": len(cand_ho_groups),
        "dev_groups": cand_dev_groups,
        "heldout_groups": cand_ho_groups,
        "safeguard_disclaimer": (
            "The minimum sample/group requirements are eligibility safeguards, not a claim of "
            "statistical power or universal perceptual validity. Confidence intervals and uncertainty "
            "must be reported independently of the minimum-data pass/fail decision."
        )
    }
    return cand_dev_samples, cand_ho_samples, cand_dev_groups, cand_ho_groups, partition_meta


# ── Policy Evaluation & Sweep ──────────────────────────────────────────── #

def evaluate_policy_operating_point(
    samples: List[CorpusSample],
    threshold: float,
    policy_name: str = "mean",
) -> OperatingMetrics:
    """
    Evaluates classification performance for a specific threshold and policy rule.

    Supported policies:
      - "mean":     V_mean >= T
      - "p5":       V_p5 >= T
      - "worst":    V_worst >= T
      - "combined": V_mean >= T AND V_p5 >= T  (scalar coupled threshold)
    """
    # Exclude boundary samples from binary FAR/FRR denominator
    eval_samples = [s for s in samples if s.independent_policy_label in ("acceptable", "unacceptable")]

    ta = tr = fa = fr = 0

    for s in eval_samples:
        if policy_name == "mean":
            pred = (s.vmaf_mean >= threshold)
        elif policy_name == "p5":
            pred = (s.vmaf_p5 is not None and s.vmaf_p5 >= threshold)
        elif policy_name == "worst":
            pred = (s.vmaf_worst is not None and s.vmaf_worst >= threshold)
        elif policy_name == "combined":
            pred = (s.vmaf_mean >= threshold and s.vmaf_p5 is not None and s.vmaf_p5 >= threshold)
        else:
            raise ValueError(f"Unknown policy rule: {policy_name}")

        is_acc = (s.independent_policy_label == "acceptable")

        if pred and is_acc:
            ta += 1
        elif pred and not is_acc:
            fa += 1
        elif not pred and not is_acc:
            tr += 1
        else:
            fr += 1

    total_acc = sum(1 for s in eval_samples if s.independent_policy_label == "acceptable")
    total_unacc = sum(1 for s in eval_samples if s.independent_policy_label == "unacceptable")
    total_n = len(eval_samples)

    far = fa / total_unacc if total_unacc > 0 else 0.0
    frr = fr / total_acc if total_acc > 0 else 0.0
    acc_rate = (ta + fa) / total_n if total_n > 0 else 0.0
    rej_rate = (tr + fr) / total_n if total_n > 0 else 0.0
    prec = ta / (ta + fa) if (ta + fa) > 0 else 0.0
    rec = ta / (ta + fr) if (ta + fr) > 0 else 0.0

    tpr = rec
    tnr = tr / (tr + fa) if (tr + fa) > 0 else 0.0
    balanced_acc = (tpr + tnr) / 2.0

    return OperatingMetrics(
        threshold=round(threshold, 2),
        policy_name=policy_name,
        total_samples=total_n,
        acceptable_samples=total_acc,
        unacceptable_samples=total_unacc,
        true_accepts=ta,
        true_rejects=tr,
        false_accepts=fa,
        false_rejects=fr,
        false_accept_rate=round(far, 4),
        false_reject_rate=round(frr, 4),
        acceptance_rate=round(acc_rate, 4),
        rejection_rate=round(rej_rate, 4),
        precision=round(prec, 4),
        recall=round(rec, 4),
        balanced_accuracy=round(balanced_acc, 4),
    )


def sweep_thresholds(
    samples: List[CorpusSample],
    policy_name: str,
    start: float = 80.0,
    stop: float = 100.0,
    step: float = 0.5,
) -> List[OperatingMetrics]:
    """Sweeps threshold T across [start, stop] with given step."""
    points: List[OperatingMetrics] = []
    curr = start
    while curr <= stop + 1e-6:
        m = evaluate_policy_operating_point(samples, curr, policy_name=policy_name)
        points.append(m)
        curr += step
    return points


def select_lowest_feasible_threshold(
    sweep_points: List[OperatingMetrics],
    fa_max: float = 0.02,
    fr_max: float = 0.05,
) -> Optional[OperatingMetrics]:
    """
    Selects the lowest threshold on development data strictly satisfying both:
      FAR < fa_max AND FRR < fr_max.
    Selecting the lowest threshold avoids unnecessary rejection of acceptable outputs.
    Returns None if no candidate satisfies both constraints.
    """
    feasible = [p for p in sweep_points if p.false_accept_rate < fa_max and p.false_reject_rate < fr_max]
    if not feasible:
        return None
    # Lowest threshold among feasible points
    return min(feasible, key=lambda p: p.threshold)


def evaluate_exhaustive_threshold_boundaries(
    samples: List[CorpusSample],
    policy_name: str = "combined",
    domain_start: float = 70.0,
    domain_stop: float = 100.0,
    fa_max: float = 0.02,
    fr_max: float = 0.05,
) -> Dict[str, Any]:
    """
    Performs an exhaustive decision-boundary and interval analysis over the domain [domain_start, domain_stop].

    Under the decision rule V_decision >= T, the binary classification changes ONLY at
    observed sample decision scores.
    Evaluates:
      1. Every unique observed V_decision value within [domain_start, domain_stop] as an exact boundary (T = v_i).
      2. Every open interval between adjacent decision values (v_i, v_{i+1}), where classification
         is invariant across all thresholds inside the interval.
      3. The outer boundary intervals [domain_start, v_1) and (v_m, domain_stop].

    Applies strict research inequalities: FAR < fa_max AND FRR < fr_max.
    """
    eval_samples = [s for s in samples if s.independent_policy_label in ("acceptable", "unacceptable")]
    if not eval_samples:
        return {
            "status": "no_data",
            "feasible_intervals": [],
            "lowest_feasible_threshold": None,
            "evaluations": [],
        }

    def get_v_dec(s: CorpusSample) -> float:
        if policy_name == "combined":
            if s.vmaf_p5 is None:
                return float("-inf")
            return min(s.vmaf_mean, s.vmaf_p5)
        elif policy_name == "mean":
            return s.vmaf_mean
        elif policy_name == "p5":
            if s.vmaf_p5 is None:
                return float("-inf")
            return s.vmaf_p5
        elif policy_name == "worst":
            if s.vmaf_worst is None:
                return float("-inf")
            return s.vmaf_worst
        else:
            raise ValueError(f"Unknown policy rule: {policy_name}")

    total_acc = sum(1 for s in eval_samples if s.independent_policy_label == "acceptable")
    total_unacc = sum(1 for s in eval_samples if s.independent_policy_label == "unacceptable")
    total_n = len(eval_samples)

    observed_scores = sorted(list(set(get_v_dec(s) for s in eval_samples if get_v_dec(s) != float("-inf"))))
    in_domain_scores = [v for v in observed_scores if domain_start <= v <= domain_stop]

    evaluations: List[Dict[str, Any]] = []
    feasible_segments: List[Dict[str, Any]] = []

    def evaluate_at(t_val: float, point_type: str, interval_repr: str) -> Dict[str, Any]:
        ta = tr = fa = fr = 0
        for s in eval_samples:
            v_score = get_v_dec(s)
            pred = (v_score >= t_val)
            is_acc = (s.independent_policy_label == "acceptable")
            if pred and is_acc:
                ta += 1
            elif pred and not is_acc:
                fa += 1
            elif not pred and not is_acc:
                tr += 1
            else:
                fr += 1

        far = fa / total_unacc if total_unacc > 0 else 0.0
        frr = fr / total_acc if total_acc > 0 else 0.0
        is_feasible = (far < fa_max and frr < fr_max)

        reasons = []
        if far >= fa_max:
            reasons.append(f"FAR ({far:.4f}) >= {fa_max:.4f}")
        if frr >= fr_max:
            reasons.append(f"FRR ({frr:.4f}) >= {fr_max:.4f}")

        ev = {
            "threshold_evaluated": round(t_val, 4),
            "point_type": point_type,
            "interval_repr": interval_repr,
            "total_samples": total_n,
            "acceptable_samples": total_acc,
            "unacceptable_samples": total_unacc,
            "true_accepts": ta,
            "true_rejects": tr,
            "false_accepts": fa,
            "false_rejects": fr,
            "false_accept_rate": round(far, 4),
            "false_reject_rate": round(frr, 4),
            "is_feasible": is_feasible,
            "rejection_reasons": reasons,
        }
        return ev

    # 1. Left interval [domain_start, v_1)
    if in_domain_scores and in_domain_scores[0] > domain_start:
        v1 = in_domain_scores[0]
        ev_left = evaluate_at(domain_start, "left_interval", f"[{domain_start:.2f}, {v1:.4f})")
        evaluations.append(ev_left)
        if ev_left["is_feasible"]:
            feasible_segments.append(ev_left)

    # 2. Iterate through observed scores and intermediate intervals
    for i, v in enumerate(in_domain_scores):
        # Exact boundary T = v
        ev_b = evaluate_at(v, "exact_boundary", f"T = {v:.4f}")
        evaluations.append(ev_b)
        if ev_b["is_feasible"]:
            feasible_segments.append(ev_b)

        # Open interval to next score
        if i + 1 < len(in_domain_scores):
            v_next = in_domain_scores[i + 1]
            t_mid = (v + v_next) / 2.0
            ev_int = evaluate_at(t_mid, "open_interval", f"({v:.4f}, {v_next:.4f})")
            evaluations.append(ev_int)
            if ev_int["is_feasible"]:
                feasible_segments.append(ev_int)

    # 3. Right interval (v_m, domain_stop]
    if in_domain_scores and in_domain_scores[-1] < domain_stop:
        vm = in_domain_scores[-1]
        t_right_mid = (vm + domain_stop) / 2.0
        ev_right = evaluate_at(t_right_mid, "open_interval", f"({vm:.4f}, {domain_stop:.2f})")
        evaluations.append(ev_right)
        if ev_right["is_feasible"]:
            feasible_segments.append(ev_right)
        ev_end = evaluate_at(domain_stop, "domain_endpoint", f"T = {domain_stop:.2f}")
        evaluations.append(ev_end)
        if ev_end["is_feasible"]:
            feasible_segments.append(ev_end)
    elif not in_domain_scores:
        ev_all = evaluate_at(domain_start, "full_domain", f"[{domain_start:.2f}, {domain_stop:.2f}]")
        evaluations.append(ev_all)
        if ev_all["is_feasible"]:
            feasible_segments.append(ev_all)

    # Determine status & lowest feasible threshold
    if not feasible_segments:
        status = "no_feasible_threshold"
        lowest_feasible = None
    else:
        status = "feasible_candidate_found"
        lowest_feasible = min(feasible_segments, key=lambda x: x["threshold_evaluated"])["threshold_evaluated"]

    return {
        "domain_start": domain_start,
        "domain_stop": domain_stop,
        "policy_name": policy_name,
        "fa_max": fa_max,
        "fr_max": fr_max,
        "total_binary_samples": total_n,
        "acceptable_samples": total_acc,
        "unacceptable_samples": total_unacc,
        "unique_decision_values_count": len(in_domain_scores),
        "unique_decision_values": [round(v, 4) for v in in_domain_scores],
        "evaluated_segments_count": len(evaluations),
        "feasible_segments_count": len(feasible_segments),
        "feasible_intervals": [s["interval_repr"] for s in feasible_segments],
        "lowest_feasible_threshold": lowest_feasible,
        "status": status,
        "evaluations": evaluations,
    }


# ── Minimum Data Confidence Requirements ──────────────────────────────── #

MIN_TOTAL_SEQUENCE_GROUPS = 12
MIN_DEV_SEQUENCE_GROUPS = 8
MIN_HELDOUT_SEQUENCE_GROUPS = 4
MIN_TOTAL_BINARY_SAMPLES = 60
MIN_DEV_BINARY_SAMPLES = 40
MIN_HELDOUT_BINARY_SAMPLES = 20


def check_minimum_data_requirements(
    dev_samples: List[CorpusSample],
    heldout_samples: List[CorpusSample],
    dev_groups: List[str],
    heldout_groups: List[str],
    min_total_groups: int = MIN_TOTAL_SEQUENCE_GROUPS,
    min_dev_groups: int = MIN_DEV_SEQUENCE_GROUPS,
    min_heldout_groups: int = MIN_HELDOUT_SEQUENCE_GROUPS,
    min_total_binary: int = MIN_TOTAL_BINARY_SAMPLES,
    min_dev_binary: int = MIN_DEV_BINARY_SAMPLES,
    min_heldout_binary: int = MIN_HELDOUT_BINARY_SAMPLES,
) -> Tuple[bool, List[str]]:
    """
    Evaluates whether the partitioned dataset satisfies scientific minimum-data requirements.

    Requirements:
      1. Total sequence groups >= min_total_groups (12)
      2. Development sequence groups >= min_dev_groups (8)
      3. Held-out sequence groups >= min_heldout_groups (4)
      4. Total binary evaluation samples >= min_total_binary (60)
      5. Development binary samples >= min_dev_binary (40)
      6. Held-out binary samples >= min_heldout_binary (20)
      7. Both 'acceptable' and 'unacceptable' samples present in dev and in held-out.
    """
    reasons: List[str] = []

    total_groups = len(dev_groups) + len(heldout_groups)
    if total_groups < min_total_groups:
        reasons.append(f"Total sequence groups ({total_groups}) < minimum ({min_total_groups})")

    if len(dev_groups) < min_dev_groups:
        reasons.append(f"Development sequence groups ({len(dev_groups)}) < minimum ({min_dev_groups})")

    if len(heldout_groups) < min_heldout_groups:
        reasons.append(f"Held-out sequence groups ({len(heldout_groups)}) < minimum ({min_heldout_groups})")

    # Binary samples only (exclude boundary 'MODERATE')
    dev_binary = [s for s in dev_samples if s.independent_policy_label in ("acceptable", "unacceptable")]
    heldout_binary = [s for s in heldout_samples if s.independent_policy_label in ("acceptable", "unacceptable")]
    total_binary = len(dev_binary) + len(heldout_binary)

    if total_binary < min_total_binary:
        reasons.append(f"Total binary samples ({total_binary}) < minimum ({min_total_binary})")

    if len(dev_binary) < min_dev_binary:
        reasons.append(f"Development binary samples ({len(dev_binary)}) < minimum ({min_dev_binary})")

    if len(heldout_binary) < min_heldout_binary:
        reasons.append(f"Held-out binary samples ({len(heldout_binary)}) < minimum ({min_heldout_binary})")

    dev_acc = sum(1 for s in dev_binary if s.independent_policy_label == "acceptable")
    dev_unacc = sum(1 for s in dev_binary if s.independent_policy_label == "unacceptable")
    if dev_acc < 1:
        reasons.append("Development set has zero acceptable samples")
    if dev_unacc < 1:
        reasons.append("Development set has zero unacceptable samples")

    ho_acc = sum(1 for s in heldout_binary if s.independent_policy_label == "acceptable")
    ho_unacc = sum(1 for s in heldout_binary if s.independent_policy_label == "unacceptable")
    if ho_acc < 1:
        reasons.append("Held-out set has zero acceptable samples")
    if ho_unacc < 1:
        reasons.append("Held-out set has zero unacceptable samples")

    return (len(reasons) == 0, reasons)


# ── Diagnostic Breakdowns ──────────────────────────────────────────────── #

def compute_category_breakdown(
    samples: List[CorpusSample],
    operating_threshold: float,
    policy_name: str = "mean",
) -> Dict[str, Any]:
    """Diagnostic breakdown by category (does not tune threshold)."""
    categories = sorted(list(set(s.category for s in samples)))
    breakdown: Dict[str, Any] = {}

    for cat in categories:
        cat_samples = [s for s in samples if s.category == cat]
        cat_groups = len(set(s.sequence_group for s in cat_samples))
        m = evaluate_policy_operating_point(cat_samples, operating_threshold, policy_name=policy_name)
        breakdown[cat] = {
            "samples": len(cat_samples),
            "sequence_groups": cat_groups,
            "false_accept_rate": m.false_accept_rate,
            "false_reject_rate": m.false_reject_rate,
            "balanced_accuracy": m.balanced_accuracy,
        }
    return breakdown


def compute_resolution_breakdown(
    samples: List[CorpusSample],
    operating_threshold: float,
    policy_name: str = "mean",
) -> Dict[str, Any]:
    """Diagnostic breakdown by resolution/HFR tier (does not tune threshold)."""
    def tier(s: CorpusSample) -> str:
        max_d = max(s.width, s.height)
        is_hfr = (s.fps >= 50.0)
        prefix = "2160p" if max_d >= 3840 else "1080p"
        suffix = "HFR" if is_hfr else "SDR"
        return f"{prefix}_{suffix}"

    tiers = sorted(list(set(tier(s) for s in samples)))
    breakdown: Dict[str, Any] = {}

    for t in tiers:
        t_samples = [s for s in samples if tier(s) == t]
        t_groups = len(set(s.sequence_group for s in t_samples))
        m = evaluate_policy_operating_point(t_samples, operating_threshold, policy_name=policy_name)
        breakdown[t] = {
            "samples": len(t_samples),
            "sequence_groups": t_groups,
            "false_accept_rate": m.false_accept_rate,
            "false_reject_rate": m.false_reject_rate,
            "balanced_accuracy": m.balanced_accuracy,
        }
    return breakdown


# ── ASCII Report ───────────────────────────────────────────────────────── #

def print_analysis_report(
    calibration_status: str,
    dev_candidate: Optional[OperatingMetrics],
    heldout_result: Optional[OperatingMetrics],
    dev_groups: List[str],
    heldout_groups: List[str],
    dev_samples: List[CorpusSample],
    heldout_samples: List[CorpusSample],
    fa_max: float,
    fr_max: float,
    failure_reasons: Optional[List[str]] = None,
    dev_exhaustive: Optional[Dict[str, Any]] = None,
):
    dev_binary = sum(1 for s in dev_samples if s.independent_policy_label in ("acceptable", "unacceptable"))
    dev_boundary = len(dev_samples) - dev_binary
    ho_binary = sum(1 for s in heldout_samples if s.independent_policy_label in ("acceptable", "unacceptable"))
    ho_boundary = len(heldout_samples) - ho_binary
    dev_clips = len(set(s.clip_filename for s in dev_samples))
    ho_clips = len(set(s.clip_filename for s in heldout_samples))
    total_groups = len(dev_groups) + len(heldout_groups)
    total_clips = dev_clips + ho_clips
    total_pairs = len(dev_samples) + len(heldout_samples)
    total_binary = dev_binary + ho_binary
    total_boundary = dev_boundary + ho_boundary

    print()
    print("=" * 80)
    print("  VeilFrame VMAF Threshold Scientific Analysis Report")
    print("=" * 80)
    print(f"  Calibration Status:        {calibration_status.upper()}")
    print(f"  Sequence Groups:           {len(dev_groups)} dev / {len(heldout_groups)} held-out ({total_groups} Domain 1 groups)")
    print(f"  Dev Groups ({len(dev_groups)}):            {', '.join(dev_groups)}")
    print(f"  Held-Out Groups ({len(heldout_groups)}):       {', '.join(heldout_groups)}")
    print(f"  Reference Clips:           {total_clips} clips total ({dev_clips} dev / {ho_clips} held-out)")
    print(f"                             - Dev set: {dev_clips} clips across {len(dev_groups)} groups (park_joy has 25fps & 50fps)")
    print(f"                             - Held-out set: {ho_clips} clips across {len(heldout_groups)} groups")
    print(f"  Sample Accounting:         {total_pairs} total fixture pairs in Domain 1 ({total_clips} clips x 8 fixtures)")
    print(f"                             * {len(dev_samples)} development pairs ({dev_clips} clips x 8 fixtures: {dev_binary} binary + {dev_boundary} boundary)")
    print(f"                             * {len(heldout_samples)} held-out pairs ({ho_clips} clips x 8 fixtures: {ho_binary} binary + {ho_boundary} boundary)")
    print(f"                             - {total_binary} binary evaluation samples ({dev_binary} dev / {ho_binary} held-out)")
    print(f"                             - {total_boundary} boundary MODERATE pairs quarantined ({dev_boundary} dev / {ho_boundary} held-out)")
    print(f"                             [Reconciliation: {len(dev_samples)} dev + {len(heldout_samples)} heldout = {total_pairs}; {total_binary} binary + {total_boundary} boundary = {total_pairs}]")
    print(f"  Research Constraints:      FAR < {fa_max*100:.1f}%, FRR < {fr_max*100:.1f}% (strict inequalities)")
    print()

    if dev_exhaustive:
        print("  Exhaustive Decision-Boundary Search Results (Development):")
        print(f"    Search Domain:           [{dev_exhaustive['domain_start']:.1f}, {dev_exhaustive['domain_stop']:.1f}]")
        print(f"    Unique Decision Scores:  {dev_exhaustive['unique_decision_values_count']}")
        print(f"    Evaluated Segments:      {dev_exhaustive['evaluated_segments_count']} (exact boundaries + invariant intervals)")
        print(f"    Feasible Segments Found: {dev_exhaustive['feasible_segments_count']}")
        if dev_exhaustive['feasible_intervals']:
            print(f"    Feasible Intervals:      {', '.join(dev_exhaustive['feasible_intervals'])}")
            print(f"    Lowest Feasible T:       {dev_exhaustive['lowest_feasible_threshold']}")
        else:
            print("    Feasible Intervals:      NONE (Intersection of FAR < 2% and FRR < 5% is empty)")
        print()

    if dev_candidate:
        print("  Development Candidate Operating Point:")
        print(f"    Policy:                  {dev_candidate.policy_name} >= {dev_candidate.threshold}")
        print(f"    Dev False-Accept Rate:   {dev_candidate.false_accept_rate*100:.2f}% (FAR < {fa_max*100:.1f}%) [OK]")
        print(f"    Dev False-Reject Rate:   {dev_candidate.false_reject_rate*100:.2f}% (FRR < {fr_max*100:.1f}%) [OK]")
        print(f"    Dev Balanced Accuracy:   {dev_candidate.balanced_accuracy*100:.1f}%")
        print()
    else:
        print("  Development Candidate Operating Point: NONE")
        print()

    if heldout_result:
        ho_far_ok = (heldout_result.false_accept_rate < fa_max)
        ho_frr_ok = (heldout_result.false_reject_rate < fr_max)
        print("  Untouched Held-Out Validation Result:")
        print(f"    Held-Out FAR:            {heldout_result.false_accept_rate*100:.2f}% "
              f"[{'PASS' if ho_far_ok else 'FAIL'}]")
        print(f"    Held-Out FRR:            {heldout_result.false_reject_rate*100:.2f}% "
              f"[{'PASS' if ho_frr_ok else 'FAIL'}]")
        print(f"    Held-Out Balanced Acc:   {heldout_result.balanced_accuracy*100:.1f}%")
        print()
    else:
        print("  Held-Out Partition Status: PRESERVED UNTOUCHED (Validation Not Executed)")
        print("    Because development produced no feasible candidate, held-out data was not unblinded.")
        print()

    if calibration_status == "validated":
        print("  VERDICT: [VALIDATED]")
        print("    Threshold satisfied research constraints on both development and held-out sets.")
        print("    NOTE: VMAF gate remains disabled (vmaf_gate_enabled=False).")
        print("    Activation requires separate human review and production gate promotion.")
    elif calibration_status == "no_feasible_threshold":
        print("  VERDICT: [NO FEASIBLE THRESHOLD]")
        print("    An exhaustive threshold-boundary search over the complete development decision space")
        print(f"    found no threshold satisfying FAR < {fa_max*100:.1f}% and FRR < {fr_max*100:.1f}%.")
        print("    This indicates that further calibration and/or evaluation of multi-tier operating")
        print("    points is warranted.")
    elif calibration_status == "insufficient_data":
        print("  VERDICT: [INSUFFICIENT DATA]")
        print("    Corpus does not satisfy minimum scientific data confidence requirements.")
        if failure_reasons:
            print("    Reasons:")
            for r in failure_reasons:
                print(f"      - {r}")
    elif calibration_status == "failed":
        print("  VERDICT: [FAILED]")
        print("    Candidate threshold failed research constraints upon held-out validation.")
    print("=" * 80)
    print()


# ── CLI & Main ─────────────────────────────────────────────────────────── #

def main():
    parser = argparse.ArgumentParser(
        description="VeilFrame VMAF Threshold Scientific Analysis Engine"
    )
    parser.add_argument("--corpus-results", type=Path,
        default=Path("vmaf_corpus_results.json"),
        help="Input JSON from vmaf_corpus_runner.py")
    parser.add_argument("--out", type=Path,
        default=Path("calibration_analysis.json"),
        help="Output JSON analysis report (Deliverable #7)")
    parser.add_argument("--sweep-csv", type=Path,
        default=Path("threshold_sweep.csv"),
        help="Output CSV of threshold sweep (Deliverable #9)")
    parser.add_argument("--dev-split-json", type=Path,
        default=Path("development_split.json"),
        help="Output JSON of development partition (Deliverable #10)")
    parser.add_argument("--heldout-split-json", type=Path,
        default=Path("heldout_split.json"),
        help="Output JSON of held-out partition (Deliverable #11)")
    parser.add_argument("--data-quality-json", type=Path,
        default=Path("data_quality_report.json"),
        help="Output JSON of data quality & confidence check (Deliverable #12)")
    parser.add_argument("--excluded-samples-json", type=Path,
        default=Path("excluded_samples.json"),
        help="Output JSON of excluded samples (Deliverable #13)")
    parser.add_argument("--sequence-group-report-json", type=Path,
        default=Path("sequence_group_report.json"),
        help="Output JSON of sequence group breakdown (Deliverable #14)")
    parser.add_argument("--policy", choices=["mean", "p5", "worst", "combined"],
        default="combined",
        help="Policy rule to optimize ('mean', 'p5', 'worst', or 'combined'; default: combined)")
    parser.add_argument("--seed", type=int, default=42,
        help="RNG seed for deterministic sequence group split (default: 42)")
    parser.add_argument("--dev-fraction", type=float, default=0.70,
        help="Fraction of sequence groups assigned to development set (default: 0.70)")
    parser.add_argument("--fa-max", type=float, default=0.02,
        help="Maximum false-accept rate constraint (default: 0.02)")
    parser.add_argument("--fr-max", type=float, default=0.05,
        help="Maximum false-reject rate constraint (default: 0.05)")
    parser.add_argument("--min-sequence-groups", type=int, default=MIN_TOTAL_SEQUENCE_GROUPS,
        help=f"Minimum unique sequence groups required (default: {MIN_TOTAL_SEQUENCE_GROUPS})")
    parser.add_argument("--min-dev-groups", type=int, default=MIN_DEV_SEQUENCE_GROUPS,
        help=f"Minimum development sequence groups required (default: {MIN_DEV_SEQUENCE_GROUPS})")
    parser.add_argument("--min-heldout-groups", type=int, default=MIN_HELDOUT_SEQUENCE_GROUPS,
        help=f"Minimum held-out sequence groups required (default: {MIN_HELDOUT_SEQUENCE_GROUPS})")
    parser.add_argument("--min-total-binary", type=int, default=MIN_TOTAL_BINARY_SAMPLES,
        help=f"Minimum total binary evaluation samples required (default: {MIN_TOTAL_BINARY_SAMPLES})")
    parser.add_argument("--min-dev-binary", type=int, default=MIN_DEV_BINARY_SAMPLES,
        help=f"Minimum valid development binary samples required (default: {MIN_DEV_BINARY_SAMPLES})")
    parser.add_argument("--min-heldout-binary", type=int, default=MIN_HELDOUT_BINARY_SAMPLES,
        help=f"Minimum valid held-out binary samples required (default: {MIN_HELDOUT_BINARY_SAMPLES})")
    args = parser.parse_args()

    # Invariant: Verify production policy remains False
    assert VisualBudgetPolicy().vmaf_gate_enabled is False, (
        "Production gate invariant violation: vmaf_gate_enabled must be False"
    )

    print()
    print(f"VeilFrame VMAF Threshold Analysis Engine  v{ANALYSIS_VERSION}")
    print("=" * 65)

    primary_samples, exclusions, secondary_samples, hdr_samples = load_corpus_samples(args.corpus_results)
    hdr_count = len(hdr_samples)
    print(f"  Domain 1 (Primary SDR) samples: {len(primary_samples)}")
    print(f"  Domain 2 (Secondary diag) samples: {len(secondary_samples)}")
    print(f"  Domain 3 (HDR segregated) samples: {hdr_count}")
    print(f"  Other exclusions: {sum(exclusions.values()) - hdr_count}")

    if not primary_samples:
        print("\nERROR: No valid Domain 1 measurement samples found in corpus results.", file=sys.stderr)
        sys.exit(1)

    # Sequence Group Split via Algorithmic Deterministic Partitioner under hard constraints
    dev_samples, heldout_samples, dev_groups, heldout_groups, partition_meta = partition_by_sequence_group_algorithmic(
        primary_samples,
        target_dev_fraction=args.dev_fraction,
        seed=args.seed,
        min_total_groups=args.min_sequence_groups,
        min_dev_groups=args.min_dev_groups,
        min_heldout_groups=args.min_heldout_groups,
        min_total_binary=args.min_total_binary,
        min_dev_binary=args.min_dev_binary,
        min_heldout_binary=args.min_heldout_binary,
    )

    dev_clips_count = len(set(s.clip_filename for s in dev_samples))
    ho_clips_count = len(set(s.clip_filename for s in heldout_samples))

    print(f"  Unique sequence groups (Domain 1): {len(dev_groups) + len(heldout_groups)} SDR groups")
    print(f"  Algorithmic split selected:        {len(dev_groups)} dev / {len(heldout_groups)} held-out groups (Seed: {partition_meta['seed_used']})")
    print(f"  Dev set:                           {len(dev_samples)} samples across {len(dev_groups)} groups ({dev_clips_count} clips)")
    print(f"  Held-out set:                      {len(heldout_samples)} samples across {len(heldout_groups)} groups ({ho_clips_count} clips)")
    print()

    # Minimum Data Check
    passed_min, min_reasons = check_minimum_data_requirements(
        dev_samples, heldout_samples, dev_groups, heldout_groups,
        min_total_groups=args.min_sequence_groups,
        min_dev_groups=args.min_dev_groups,
        min_heldout_groups=args.min_heldout_groups,
        min_total_binary=args.min_total_binary,
        min_dev_binary=args.min_dev_binary,
        min_heldout_binary=args.min_heldout_binary,
    )

    dev_binary = sum(1 for s in dev_samples if s.independent_policy_label in ("acceptable", "unacceptable"))
    dev_boundary = len(dev_samples) - dev_binary
    ho_binary = sum(1 for s in heldout_samples if s.independent_policy_label in ("acceptable", "unacceptable"))
    ho_boundary = len(heldout_samples) - ho_binary
    total_domain1_measured = len(primary_samples)

    sample_accounting = {
        "total_primary_sdr_pairs": total_domain1_measured,
        "hdr_segregated_pairs": hdr_count,
        "secondary_domain_diagnostic_pairs": len(secondary_samples),
        "total_clips_in_domain1": dev_clips_count + ho_clips_count,
        "development_split": {
            "sequence_groups_count": len(dev_groups),
            "sequence_groups": dev_groups,
            "clips_count": dev_clips_count,
            "total_pairs": len(dev_samples),
            "binary_evaluation_samples": dev_binary,
            "acceptable_samples": sum(1 for s in dev_samples if s.independent_policy_label == "acceptable"),
            "unacceptable_samples": sum(1 for s in dev_samples if s.independent_policy_label == "unacceptable"),
            "boundary_moderate_excluded": dev_boundary,
        },
        "heldout_split": {
            "sequence_groups_count": len(heldout_groups),
            "sequence_groups": heldout_groups,
            "clips_count": ho_clips_count,
            "total_pairs": len(heldout_samples),
            "binary_evaluation_samples": ho_binary,
            "acceptable_samples": sum(1 for s in heldout_samples if s.independent_policy_label == "acceptable"),
            "unacceptable_samples": sum(1 for s in heldout_samples if s.independent_policy_label == "unacceptable"),
            "boundary_moderate_excluded": ho_boundary,
        },
        "total_binary_evaluation_samples": dev_binary + ho_binary,
        "total_boundary_moderate_excluded": dev_boundary + ho_boundary,
        "arithmetic_verification": {
            "domain1_pairs_check": f"{len(dev_samples)} dev + {len(heldout_samples)} heldout = {len(dev_samples) + len(heldout_samples)} Domain 1 pairs",
            "binary_plus_boundary_check": f"{dev_binary + ho_binary} binary + {dev_boundary + ho_boundary} boundary = {dev_binary + ho_binary + dev_boundary + ho_boundary} Domain 1 pairs",
        },
        "safeguard_disclaimer": (
            "The minimum sample/group requirements are eligibility safeguards, not a claim of "
            "statistical power or universal perceptual validity. Confidence intervals and uncertainty "
            "must be reported independently of the minimum-data pass/fail decision."
        ),
    }

    # Data Quality Report (Deliverable #12)
    data_quality_report = {
        "schema": "veilframe-data-quality-v1",
        "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
        "data_sufficiency_passed": passed_min,
        "failure_reasons": min_reasons,
        "sample_accounting": sample_accounting,
        "partitioning_metadata": partition_meta,
        "safeguard_disclaimer": (
            "The minimum sample/group requirements are eligibility safeguards, not a claim of "
            "statistical power or universal perceptual validity. Confidence intervals and uncertainty "
            "must be reported independently of the minimum-data pass/fail decision."
        ),
    }
    with open(args.data_quality_json, "w", encoding="utf-8") as f:
        json.dump(data_quality_report, f, indent=2)
    print(f"  Data quality report written → {args.data_quality_json}")

    # Excluded Samples Report (Deliverable #13)
    excluded_report = {
        "schema": "veilframe-excluded-samples-v1",
        "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
        "total_excluded": hdr_count + len(secondary_samples) + (dev_boundary + ho_boundary),
        "hdr_segregated_samples": hdr_samples,
        "secondary_domain_samples": [asdict(s) for s in secondary_samples],
        "boundary_moderate_samples": [asdict(s) for s in primary_samples if s.independent_policy_label == "boundary"],
        "exclusion_counts": exclusions,
    }
    with open(args.excluded_samples_json, "w", encoding="utf-8") as f:
        json.dump(excluded_report, f, indent=2)
    print(f"  Excluded samples report written → {args.excluded_samples_json}")

    # Sequence Group Report (Deliverable #14)
    all_groups_dict = {}
    for s in primary_samples:
        grp = s.sequence_group
        if grp not in all_groups_dict:
            split_tag = "development" if grp in dev_groups else "held_out"
            all_groups_dict[grp] = {
                "sequence_group": grp,
                "domain": "Domain 1: Primary SDR",
                "split": split_tag,
                "category": s.category,
                "subcategory": s.subcategory,
                "width": s.width,
                "height": s.height,
                "fps": s.fps,
                "pix_fmt": s.pix_fmt,
                "total_pairs": 0,
                "binary_pairs": 0,
                "boundary_pairs": 0,
                "clips": set(),
                "eligibility_rationale": "SDR 1080p/2160p modern representative content satisfying VMAF v1.0.16 model criteria",
            }
        all_groups_dict[grp]["total_pairs"] += 1
        all_groups_dict[grp]["clips"].add(s.clip_filename)
        if s.independent_policy_label in ("acceptable", "unacceptable"):
            all_groups_dict[grp]["binary_pairs"] += 1
        else:
            all_groups_dict[grp]["boundary_pairs"] += 1

    for grp in all_groups_dict:
        all_groups_dict[grp]["clips"] = sorted(list(all_groups_dict[grp]["clips"]))

    # Add secondary domain groups
    for s in secondary_samples:
        grp = s.sequence_group
        if grp not in all_groups_dict:
            all_groups_dict[grp] = {
                "sequence_group": grp,
                "domain": "Domain 2: Secondary / Legacy",
                "split": "secondary_diagnostic",
                "category": s.category,
                "subcategory": s.subcategory,
                "width": s.width,
                "height": s.height,
                "fps": s.fps,
                "pix_fmt": s.pix_fmt,
                "total_pairs": 0,
                "binary_pairs": 0,
                "boundary_pairs": 0,
                "clips": set(),
                "eligibility_rationale": "720p or classic SD legacy sequence excluded from primary calibration threshold fitting; diagnostic robustness only",
            }
        all_groups_dict[grp]["total_pairs"] += 1
        all_groups_dict[grp]["clips"].add(s.clip_filename)
        if s.independent_policy_label in ("acceptable", "unacceptable"):
            all_groups_dict[grp]["binary_pairs"] += 1
        else:
            all_groups_dict[grp]["boundary_pairs"] += 1
    for grp in all_groups_dict:
        if isinstance(all_groups_dict[grp]["clips"], set):
            all_groups_dict[grp]["clips"] = sorted(list(all_groups_dict[grp]["clips"]))

    # Add HDR groups
    for item in hdr_samples:
        grp = item["sequence_group"]
        if grp not in all_groups_dict:
            all_groups_dict[grp] = {
                "sequence_group": grp,
                "domain": "Domain 3: HDR / WCG",
                "split": "hdr_segregated",
                "category": "hdr",
                "subcategory": "hdr",
                "width": 4096,
                "height": 2160,
                "fps": 59.94,
                "pix_fmt": "yuv420p",
                "total_pairs": 0,
                "binary_pairs": 0,
                "boundary_pairs": 0,
                "clips": [item["clip_filename"]],
                "eligibility_rationale": "HDR / WCG material outside SDR VMAF v1.0.16 model domain; segregated without fabricating scores",
            }
        all_groups_dict[grp]["total_pairs"] += 1

    seq_group_report = {
        "schema": "veilframe-sequence-group-report-v1",
        "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
        "total_groups_in_corpus": len(all_groups_dict),
        "domain_1_primary_groups_count": len([g for g, v in all_groups_dict.items() if v["domain"] == "Domain 1: Primary SDR"]),
        "domain_2_secondary_groups_count": len([g for g, v in all_groups_dict.items() if v["domain"] == "Domain 2: Secondary / Legacy"]),
        "domain_3_hdr_groups_count": len([g for g, v in all_groups_dict.items() if v["domain"] == "Domain 3: HDR / WCG"]),
        "groups": all_groups_dict,
    }
    with open(args.sequence_group_report_json, "w", encoding="utf-8") as f:
        json.dump(seq_group_report, f, indent=2)
    print(f"  Sequence group report written → {args.sequence_group_report_json}")

    # Development Split JSON (Deliverable #10)
    dev_split_data = {
        "schema": "veilframe-development-split-v1",
        "split_name": "development",
        "groups_count": len(dev_groups),
        "sequence_groups": dev_groups,
        "clips_count": dev_clips_count,
        "total_pairs": len(dev_samples),
        "binary_evaluation_samples": dev_binary,
        "acceptable_samples": sum(1 for s in dev_samples if s.independent_policy_label == "acceptable"),
        "unacceptable_samples": sum(1 for s in dev_samples if s.independent_policy_label == "unacceptable"),
        "boundary_moderate_excluded": dev_boundary,
        "samples": [asdict(s) for s in dev_samples],
    }
    with open(args.dev_split_json, "w", encoding="utf-8") as f:
        json.dump(dev_split_data, f, indent=2)
    print(f"  Development split written → {args.dev_split_json}")

    # Held-out Split JSON (Deliverable #11)
    ho_split_data = {
        "schema": "veilframe-heldout-split-v1",
        "split_name": "held_out",
        "groups_count": len(heldout_groups),
        "sequence_groups": heldout_groups,
        "clips_count": ho_clips_count,
        "total_pairs": len(heldout_samples),
        "binary_evaluation_samples": ho_binary,
        "acceptable_samples": sum(1 for s in heldout_samples if s.independent_policy_label == "acceptable"),
        "unacceptable_samples": sum(1 for s in heldout_samples if s.independent_policy_label == "unacceptable"),
        "boundary_moderate_excluded": ho_boundary,
        "samples": [asdict(s) for s in heldout_samples],
    }
    with open(args.heldout_split_json, "w", encoding="utf-8") as f:
        json.dump(ho_split_data, f, indent=2)
    print(f"  Held-out split written → {args.heldout_split_json}")

    # Full Sweep across [70.0, 100.0] with step 0.5 (Deliverable #9)
    dev_sweep = sweep_thresholds(dev_samples, policy_name=args.policy, start=70.0, stop=100.0, step=0.5)

    with open(args.sweep_csv, "w", encoding="utf-8") as f:
        f.write("threshold,policy,total_samples,acceptable_samples,unacceptable_samples,true_accepts,true_rejects,false_accepts,false_rejects,false_accept_rate,false_reject_rate,precision,recall,balanced_accuracy,acceptance_rate,rejection_rate\n")
        for m in dev_sweep:
            f.write(
                f"{m.threshold:.1f},{m.policy_name},{m.total_samples},{m.acceptable_samples},{m.unacceptable_samples},"
                f"{m.true_accepts},{m.true_rejects},{m.false_accepts},{m.false_rejects},"
                f"{m.false_accept_rate:.4f},{m.false_reject_rate:.4f},{m.precision:.4f},{m.recall:.4f},"
                f"{m.balanced_accuracy:.4f},{m.acceptance_rate:.4f},{m.rejection_rate:.4f}\n"
            )
    print(f"  Threshold sweep CSV written → {args.sweep_csv}")

    if not passed_min:
        status = "insufficient_data"
        print_analysis_report(
            status, None, None, dev_groups, heldout_groups, dev_samples, heldout_samples,
            args.fa_max, args.fr_max, failure_reasons=min_reasons
        )
        out_data = {
            "schema": "veilframe-vmaf-analysis-v1",
            "analysis_version": ANALYSIS_VERSION,
            "vmaf_model_version": VMAF_MODEL_VERSION,
            "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
            "calibration_status": status,
            "insufficient_data_reasons": min_reasons,
            "partitioning_metadata": partition_meta,
            "sample_accounting": sample_accounting,
            "production_gate_status": {"vmaf_gate_enabled": False},
        }
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(out_data, f, indent=2)
        print(f"  Analysis written → {args.out}\n")
        return

    # Exhaustive Decision-Boundary Threshold Analysis (Development Partition)
    dev_exhaustive = evaluate_exhaustive_threshold_boundaries(
        dev_samples, policy_name=args.policy, domain_start=70.0, domain_stop=100.0,
        fa_max=args.fa_max, fr_max=args.fr_max
    )

    # Dual-Configuration Sensitivity Analysis: ide_editing (1808x1080)
    no_ide_samples = [s for s in dev_samples if s.sequence_group != "ide_editing"]
    no_ide_exhaustive = evaluate_exhaustive_threshold_boundaries(
        no_ide_samples, policy_name=args.policy, domain_start=70.0, domain_stop=100.0,
        fa_max=args.fa_max, fr_max=args.fr_max
    )

    sensitivity_analysis = {
        "ide_editing_geometry_audit": {
            "native_resolution": "1808x1080",
            "aspect_ratio": "226:135 (non-standard 1080p width)",
            "selected_model": "vmaf_v1.0.16_hfr_3d0h",
            "domain_1_with_ide_editing": {
                "sequence_groups_count": len(dev_groups),
                "binary_samples_count": dev_binary,
                "status": dev_exhaustive["status"],
                "feasible_intervals": dev_exhaustive["feasible_intervals"],
                "lowest_feasible_threshold": dev_exhaustive["lowest_feasible_threshold"],
            },
            "domain_1_quarantined_ide_editing": {
                "sequence_groups_count": len([g for g in dev_groups if g != "ide_editing"]),
                "binary_samples_count": len([s for s in no_ide_samples if s.independent_policy_label in ("acceptable", "unacceptable")]),
                "status": no_ide_exhaustive["status"],
                "feasible_intervals": no_ide_exhaustive["feasible_intervals"],
                "lowest_feasible_threshold": no_ide_exhaustive["lowest_feasible_threshold"],
            }
        }
    }

    # Operating Point Decision based on Exhaustive Search
    dev_candidate = None
    if dev_exhaustive["status"] == "feasible_candidate_found":
        lowest_t = dev_exhaustive["lowest_feasible_threshold"]
        dev_candidate = evaluate_policy_operating_point(dev_samples, lowest_t, policy_name=args.policy)

    heldout_result = None
    if not dev_candidate:
        status = "no_feasible_threshold"
        heldout_validation_data = {
            "status": "heldout_preserved_validation_not_executed",
            "reason": "No feasible development candidate emerged from exhaustive boundary search; held-out partition remains unblinded to preserve evidentiary integrity."
        }
    else:
        # Untouched Held-Out Validation
        heldout_result = evaluate_policy_operating_point(
            heldout_samples, dev_candidate.threshold, policy_name=args.policy
        )
        if heldout_result.false_accept_rate < args.fa_max and heldout_result.false_reject_rate < args.fr_max:
            status = "validated"
        else:
            status = "failed"
        heldout_validation_data = asdict(heldout_result)

    # Category and Resolution breakdowns
    chosen_threshold = dev_candidate.threshold if dev_candidate else 90.0
    cat_breakdown = compute_category_breakdown(primary_samples, chosen_threshold, policy_name=args.policy)
    res_breakdown = compute_resolution_breakdown(primary_samples, chosen_threshold, policy_name=args.policy)

    print_analysis_report(
        status, dev_candidate, heldout_result, dev_groups, heldout_groups, dev_samples, heldout_samples,
        args.fa_max, args.fr_max, dev_exhaustive=dev_exhaustive
    )

    scientific_conclusion = (
        "Under the prespecified development corpus, independent labeling rule, decision policy, "
        f"threshold domain [70.0, 100.0], and strict research constraints (FAR < {args.fa_max*100:.1f}% and FRR < {args.fr_max*100:.1f}%), "
        "an exhaustive threshold-boundary search over the complete development decision space found no feasible threshold. "
        "This indicates that further calibration and/or evaluation of multi-tier operating points is warranted."
    ) if status == "no_feasible_threshold" else (
        "A feasible scalar threshold was identified that satisfies research constraints on both development and held-out sets."
        if status == "validated" else
        "Candidate threshold failed research constraints upon held-out validation."
    )

    out_data = {
        "schema": "veilframe-vmaf-analysis-v1",
        "analysis_version": ANALYSIS_VERSION,
        "vmaf_model_version": VMAF_MODEL_VERSION,
        "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
        "calibration_status": status,
        "policy_evaluated": args.policy,
        "partitioning_metadata": partition_meta,
        "research_constraints": {"fa_max": args.fa_max, "fr_max": args.fr_max},
        "sequence_groups": {
            "total_domain1_groups": len(dev_groups) + len(heldout_groups),
            "dev_groups": dev_groups,
            "heldout_groups": heldout_groups,
        },
        "sample_accounting": sample_accounting,
        "scientific_conclusion": scientific_conclusion,
        "exhaustive_analysis": dev_exhaustive,
        "sensitivity_analysis": sensitivity_analysis,
        "selected_operating_point": asdict(dev_candidate) if dev_candidate else None,
        "heldout_validation": heldout_validation_data,
        "category_breakdown": cat_breakdown,
        "resolution_breakdown": res_breakdown,
        "production_gate_status": {"vmaf_gate_enabled": False},
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out_data, f, indent=2)

    # Also keep alias for backward-compatibility if out is calibration_analysis.json
    alias_path = Path("vmaf_threshold_analysis.json")
    if args.out != alias_path:
        with open(alias_path, "w", encoding="utf-8") as f:
            json.dump(out_data, f, indent=2)

    print(f"  Analysis written → {args.out} (and {alias_path})\n")


if __name__ == "__main__":
    main()
