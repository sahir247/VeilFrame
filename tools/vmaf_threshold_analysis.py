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
    sequence_group_source:  str
    category:               str
    subcategory:            str
    width:                  int
    height:                 int
    fps:                    float
    pix_fmt:                str
    fixture:                str
    vmaf_mean:              float
    vmaf_p5:                float
    vmaf_worst:             float
    vmaf_stddev:            float
    ssim_mean:              float
    psnr_mean:              float
    model_id:               Optional[str]
    model_name:             Optional[str]
    model_sha256:           Optional[str]
    independent_policy_label: str  # "acceptable", "unacceptable", "boundary"


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
    Assigns an independent quality label based on VeilFrame's existing fidelity criteria.
    SSIM constraint: >= 0.95. PSNR constraint: >= 30.0 dB.

    Research Question:
      Can VMAF v1.0.16 reproduce VeilFrame's independently defined visual quality policy?
    """
    ssim_ok = (ssim_mean is not None and ssim_mean >= 0.95)
    psnr_ok = (psnr_mean is not None and psnr_mean >= 30.0)

    if fixture in ACCEPTABLE_FIXTURES and ssim_ok and psnr_ok:
        return "acceptable"

    if fixture in UNACCEPTABLE_FIXTURES or not ssim_ok or not psnr_ok:
        return "unacceptable"

    if fixture in BOUNDARY_FIXTURES:
        return "boundary"

    return "unacceptable"


# ── Ingestion ──────────────────────────────────────────────────────────── #

def load_corpus_samples(
    corpus_results_path: Path,
) -> Tuple[List[CorpusSample], Dict[str, int]]:
    """
    Loads successful measurement samples from vmaf_corpus_results.json.
    Excludes HDR not-applicable samples, metadata errors, and measurement failures.
    Missing data is NEVER interpreted as 0.0.
    """
    if not corpus_results_path.exists():
        raise FileNotFoundError(f"Corpus results file not found: '{corpus_results_path}'")

    with open(corpus_results_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    samples: List[CorpusSample] = []
    exclusion_counts: Dict[str, int] = {
        "not_applicable_hdr": 0,
        "metadata_error": 0,
        "measurement_error": 0,
        "unsupported_resolution": 0,
        "missing_vmaf": 0,
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
        w = clip.get("width") or 0
        h = clip.get("height") or 0
        fps = clip.get("fps") or 0.0
        pix_fmt = clip.get("pix_fmt", "unknown")

        for fx in clip.get("fixtures", []):
            st = fx.get("status", "success")
            if st == "not_applicable_hdr":
                exclusion_counts["not_applicable_hdr"] += 1
                continue
            if st == "unsupported_resolution":
                exclusion_counts["unsupported_resolution"] += 1
                continue
            if st == "measurement_error" or fx.get("error_message"):
                exclusion_counts["measurement_error"] += 1
                continue

            v_mean = fx.get("vmaf_mean")
            if v_mean is None or v_mean <= 0.0:
                exclusion_counts["missing_vmaf"] += 1
                continue

            v_p5 = fx.get("vmaf_p5") if fx.get("vmaf_p5") is not None else v_mean
            v_worst = fx.get("vmaf_worst") if fx.get("vmaf_worst") is not None else v_mean
            v_std = fx.get("vmaf_stddev") or 0.0
            s_mean = fx.get("ssim_mean")
            p_mean = fx.get("psnr_mean")
            fixture_name = fx.get("fixture", "")

            label = assign_independent_policy_label(fixture_name, s_mean, p_mean)

            sample = CorpusSample(
                clip_filename=c_fn,
                sequence_group=seq_grp,
                sequence_group_source=seq_src,
                category=cat,
                subcategory=subcat,
                width=w,
                height=h,
                fps=fps,
                pix_fmt=pix_fmt,
                fixture=fixture_name,
                vmaf_mean=float(v_mean),
                vmaf_p5=float(v_p5),
                vmaf_worst=float(v_worst),
                vmaf_stddev=float(v_std),
                ssim_mean=float(s_mean) if s_mean is not None else 0.0,
                psnr_mean=float(p_mean) if p_mean is not None else 0.0,
                model_id=fx.get("model_id"),
                model_name=fx.get("model_name"),
                model_sha256=fx.get("model_sha256"),
                independent_policy_label=label,
            )
            samples.append(sample)

    return samples, exclusion_counts


# ── Sequence Group Splitting ───────────────────────────────────────────── #

def partition_by_sequence_group(
    samples: List[CorpusSample],
    dev_fraction: float = 0.70,
    seed: int = 42,
) -> Tuple[List[CorpusSample], List[CorpusSample], List[str], List[str]]:
    """
    Partitions samples into development and held-out sets strictly by sequence_group.
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
            pred = (s.vmaf_p5 >= threshold)
        elif policy_name == "worst":
            pred = (s.vmaf_worst >= threshold)
        elif policy_name == "combined":
            pred = (s.vmaf_mean >= threshold and s.vmaf_p5 >= threshold)
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
    fa_max: float,
    fr_max: float,
):
    print()
    print("=" * 80)
    print("  VeilFrame VMAF Threshold Scientific Analysis Report")
    print("=" * 80)
    print(f"  Calibration Status:        {calibration_status.upper()}")
    print(f"  Sequence Groups:           {len(dev_groups)} dev / {len(heldout_groups)} held-out")
    print(f"  Dev Groups:                {', '.join(dev_groups)}")
    print(f"  Held-Out Groups:           {', '.join(heldout_groups)}")
    print(f"  Research Constraints:      FAR < {fa_max*100:.1f}%, FRR < {fr_max*100:.1f}%")
    print()

    if dev_candidate:
        print("  Development Candidate Operating Point:")
        print(f"    Policy:                  {dev_candidate.policy_name} >= {dev_candidate.threshold}")
        print(f"    Dev False-Accept Rate:   {dev_candidate.false_accept_rate*100:.2f}% (FAR < {fa_max*100:.1f}%) [OK]")
        print(f"    Dev False-Reject Rate:   {dev_candidate.false_reject_rate*100:.2f}% (FRR < {fr_max*100:.1f}%) [OK]")
        print(f"    Dev Balanced Accuracy:   {dev_candidate.balanced_accuracy*100:.1f}%")
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

    if calibration_status == "validated":
        print("  VERDICT: [VALIDATED]")
        print("    Threshold satisfied research constraints on both development and held-out sets.")
        print("    NOTE: VMAF gate remains disabled (vmaf_gate_enabled=False).")
        print("    Activation requires separate human review and production gate promotion.")
    elif calibration_status == "no_feasible_threshold":
        print("  VERDICT: [NO FEASIBLE THRESHOLD]")
        print("    No candidate threshold satisfied both FAR and FRR constraints on development data.")
    elif calibration_status == "insufficient_data":
        print("  VERDICT: [INSUFFICIENT DATA]")
        print("    Corpus does not satisfy minimum independent sequence groups or sample requirements.")
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
        default=Path("vmaf_threshold_analysis.json"),
        help="Output JSON analysis report")
    parser.add_argument("--policy", choices=["mean", "p5", "worst", "combined"],
        default="mean",
        help="Policy rule to optimize ('mean', 'p5', 'worst', or 'combined')")
    parser.add_argument("--seed", type=int, default=42,
        help="RNG seed for deterministic sequence group split (default: 42)")
    parser.add_argument("--dev-fraction", type=float, default=0.70,
        help="Fraction of sequence groups assigned to development set (default: 0.70)")
    parser.add_argument("--fa-max", type=float, default=0.02,
        help="Maximum false-accept rate constraint (default: 0.02)")
    parser.add_argument("--fr-max", type=float, default=0.05,
        help="Maximum false-reject rate constraint (default: 0.05)")
    parser.add_argument("--min-sequence-groups", type=int, default=4,
        help="Minimum unique sequence groups required (default: 4)")
    parser.add_argument("--min-dev-samples", type=int, default=8,
        help="Minimum valid development samples required (default: 8)")
    parser.add_argument("--min-heldout-samples", type=int, default=4,
        help="Minimum valid held-out samples required (default: 4)")
    args = parser.parse_args()

    # Invariant: Verify production policy remains False
    assert VisualBudgetPolicy().vmaf_gate_enabled is False, (
        "Production gate invariant violation: vmaf_gate_enabled must be False"
    )

    print()
    print(f"VeilFrame VMAF Threshold Analysis Engine  v{ANALYSIS_VERSION}")
    print("=" * 65)

    samples, exclusions = load_corpus_samples(args.corpus_results)
    print(f"  Valid samples loaded:    {len(samples)}")
    print(f"  Excluded samples:        {sum(exclusions.values())} "
          f"(HDR: {exclusions['not_applicable_hdr']}, errors: {exclusions['measurement_error'] + exclusions['metadata_error']})")

    if not samples:
        print("\nERROR: No valid measurement samples found in corpus results.", file=sys.stderr)
        sys.exit(1)

    # Sequence Group Split
    dev_samples, heldout_samples, dev_groups, heldout_groups = partition_by_sequence_group(
        samples, dev_fraction=args.dev_fraction, seed=args.seed
    )

    print(f"  Unique sequence groups:  {len(dev_groups) + len(heldout_groups)}")
    print(f"  Dev set:                 {len(dev_samples)} samples across {len(dev_groups)} groups")
    print(f"  Held-out set:            {len(heldout_samples)} samples across {len(heldout_groups)} groups")
    print()

    # Minimum Data Check
    total_groups = len(dev_groups) + len(heldout_groups)
    if (total_groups < args.min_sequence_groups or
        len(dev_samples) < args.min_dev_samples or
        len(heldout_samples) < args.min_heldout_samples):

        status = "insufficient_data"
        print_analysis_report(status, None, None, dev_groups, heldout_groups, args.fa_max, args.fr_max)

        out_data = {
            "schema": "veilframe-vmaf-analysis-v1",
            "analysis_version": ANALYSIS_VERSION,
            "vmaf_model_version": VMAF_MODEL_VERSION,
            "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
            "calibration_status": status,
            "reason": (
                f"Insufficient data: {total_groups} groups (min {args.min_sequence_groups}), "
                f"{len(dev_samples)} dev samples (min {args.min_dev_samples}), "
                f"{len(heldout_samples)} held-out samples (min {args.min_heldout_samples})"
            ),
            "sequence_groups": {"dev": dev_groups, "heldout": heldout_groups},
            "production_gate_status": {"vmaf_gate_enabled": False},
        }
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(out_data, f, indent=2)
        print(f"  Analysis written → {args.out}\n")
        return

    # Sweep development set
    dev_sweep = sweep_thresholds(dev_samples, policy_name=args.policy, start=80.0, stop=100.0, step=0.5)
    dev_candidate = select_lowest_feasible_threshold(dev_sweep, fa_max=args.fa_max, fr_max=args.fr_max)

    heldout_result = None
    if not dev_candidate:
        status = "no_feasible_threshold"
    else:
        # Untouched Held-Out Validation
        heldout_result = evaluate_policy_operating_point(
            heldout_samples, dev_candidate.threshold, policy_name=args.policy
        )
        if heldout_result.false_accept_rate < args.fa_max and heldout_result.false_reject_rate < args.fr_max:
            status = "validated"
        else:
            status = "failed"

    # Category and Resolution breakdowns
    chosen_threshold = dev_candidate.threshold if dev_candidate else 90.0
    cat_breakdown = compute_category_breakdown(samples, chosen_threshold, policy_name=args.policy)
    res_breakdown = compute_resolution_breakdown(samples, chosen_threshold, policy_name=args.policy)

    print_analysis_report(
        status, dev_candidate, heldout_result, dev_groups, heldout_groups, args.fa_max, args.fr_max
    )

    out_data = {
        "schema": "veilframe-vmaf-analysis-v1",
        "analysis_version": ANALYSIS_VERSION,
        "vmaf_model_version": VMAF_MODEL_VERSION,
        "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
        "calibration_status": status,
        "policy_evaluated": args.policy,
        "random_seed": args.seed,
        "research_constraints": {"fa_max": args.fa_max, "fr_max": args.fr_max},
        "sequence_groups": {
            "total_groups": total_groups,
            "dev_groups": dev_groups,
            "heldout_groups": heldout_groups,
        },
        "sample_counts": {
            "total_valid": len(samples),
            "dev_samples": len(dev_samples),
            "heldout_samples": len(heldout_samples),
            "excluded_samples": exclusions,
        },
        "selected_operating_point": asdict(dev_candidate) if dev_candidate else None,
        "heldout_validation": asdict(heldout_result) if heldout_result else None,
        "category_breakdown": cat_breakdown,
        "resolution_breakdown": res_breakdown,
        "production_gate_status": {"vmaf_gate_enabled": False},
    }

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out_data, f, indent=2)

    print(f"  Analysis written → {args.out}\n")


if __name__ == "__main__":
    main()
