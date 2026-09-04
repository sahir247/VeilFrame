"""
VeilFrame Technical Domain-Specific VMAF Qualification Study (v1.2).

Evaluates whether VMAF becomes a reliable production discriminator when evaluated
within technically homogeneous model domains, rather than across a global scalar:
  - Domain 1: 1080p SDR
  - Domain 2: 1080p HFR (fps >= 50.0)
  - Domain 3: 2160p SDR
  - Domain 4: 2160p HFR (fps >= 50.0)

Qualification Criteria:
  1. Minimum Data Safeguards: >= 3 independent sequence groups per domain.
  2. Development Feasibility: Operating point achieving FAR < 2.0% and FRR < 5.0%.
  3. Held-Out Generalization: Untouched held-out validation achieving FAR < 2.0% and FRR < 5.0%.
  4. Non-Manufacture Invariant: Any domain failing any criterion is marked 'not_qualified'.
"""
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.vmaf_threshold_analysis import (
    load_corpus_samples,
    partition_by_sequence_group,
    evaluate_policy_operating_point,
    CorpusSample,
)
from veilframe.quality.vmaf_models import classify_resolution, is_hfr


def run_domain_qualification(
    corpus_results_path: Path,
    output_report_path: Optional[Path] = None,
    dev_fraction: float = 0.70,
    seed: int = 42,
) -> Dict[str, Any]:
    primary_samples, group_counts, secondary_samples, hdr_samples = load_corpus_samples(corpus_results_path)

    # Re-verify labeling strictly from measured SSIM and PSNR
    for s in primary_samples:
        if s.ssim_mean is None or s.psnr_mean is None:
            s.independent_policy_label = "missing"
        elif s.ssim_mean >= 0.9500 and s.psnr_mean >= 30.00:
            s.independent_policy_label = "acceptable"
        else:
            s.independent_policy_label = "unacceptable"

    # Segregate by technical domain
    domains: Dict[str, List[CorpusSample]] = {
        "1080p_sdr": [],
        "1080p_hfr": [],
        "2160p_sdr": [],
        "2160p_hfr": [],
    }

    for s in primary_samples:
        res_class = classify_resolution(s.width, s.height)
        if res_class not in ("1080p", "2160p"):
            continue
        hfr_flag = is_hfr(s.fps)
        key = f"{res_class}_{'hfr' if hfr_flag else 'sdr'}"
        if key in domains:
            domains[key].append(s)

    report: Dict[str, Any] = {
        "study_id": "VF-CAL-VMAF-DOMAIN-2026-09",
        "dataset_version": "1.2.0",
        "analysis_version": "1.2.0",
        "dev_fraction": dev_fraction,
        "seed": seed,
        "domains": {},
    }

    print("=" * 70)
    print("VeilFrame Technical Domain-Specific VMAF Qualification Study")
    print("=" * 70)

    for domain_key, samples in sorted(domains.items()):
        unique_groups = sorted(set(s.sequence_group for s in samples))
        n_samples = len(samples)
        n_groups = len(unique_groups)

        domain_summary: Dict[str, Any] = {
            "domain": domain_key,
            "total_samples": n_samples,
            "unique_groups": unique_groups,
            "group_count": n_groups,
            "status": "not_qualified",
            "reason": "",
            "dev_results": None,
            "heldout_results": None,
            "recommended_mean_min": None,
            "recommended_p5_min": None,
        }

        print(f"\nEvaluating Domain: {domain_key}")
        print(f"  Samples: {n_samples} | Sequence Groups ({n_groups}): {unique_groups}")

        # 1. Minimum sequence group safeguard
        if n_groups < 3:
            reason = f"Insufficient independent sequence groups ({n_groups} < 3 required for grouped validation)"
            domain_summary["status"] = "not_qualified"
            domain_summary["reason"] = reason
            print(f"  VERDICT: NOT_QUALIFIED — {reason}")
            report["domains"][domain_key] = domain_summary
            continue

        # 2. Deterministic grouped partition
        dev_s, ho_s, dev_g, ho_g = partition_by_sequence_group(samples, dev_fraction=dev_fraction, seed=seed)
        dev_binary = [s for s in dev_s if s.independent_policy_label in ("acceptable", "unacceptable")]
        ho_binary = [s for s in ho_s if s.independent_policy_label in ("acceptable", "unacceptable")]

        dev_acc = sum(1 for s in dev_binary if s.independent_policy_label == "acceptable")
        dev_unacc = sum(1 for s in dev_binary if s.independent_policy_label == "unacceptable")
        ho_acc = sum(1 for s in ho_binary if s.independent_policy_label == "acceptable")
        ho_unacc = sum(1 for s in ho_binary if s.independent_policy_label == "unacceptable")

        print(f"  Development: {len(dev_binary)} samples ({dev_acc} acc, {dev_unacc} unacc) across groups: {dev_g}")
        print(f"  Held-Out:    {len(ho_binary)} samples ({ho_acc} acc, {ho_unacc} unacc) across groups: {ho_g}")

        # 3. Exhaustive search across unique decision boundaries on development set
        scores = sorted(set(min(s.vmaf_mean, s.vmaf_p5) for s in dev_binary))
        feasible_points: List[Tuple[float, Any]] = []

        for sc in scores:
            m = evaluate_policy_operating_point(dev_binary, sc, policy_name="combined")
            if m.false_accept_rate < 0.02 and m.false_reject_rate < 0.05:
                feasible_points.append((sc, m))

        if not feasible_points:
            all_m = [evaluate_policy_operating_point(dev_binary, sc, policy_name="combined") for sc in scores]
            min_far = min(m.false_accept_rate for m in all_m) * 100 if all_m else 0.0
            min_frr = min(m.false_reject_rate for m in all_m) * 100 if all_m else 0.0
            reason = f"No feasible threshold on development set (min FAR={min_far:.2f}%, min FRR={min_frr:.2f}%)"
            domain_summary["status"] = "not_qualified"
            domain_summary["reason"] = reason
            print(f"  VERDICT: NOT_QUALIFIED — {reason}")
            report["domains"][domain_key] = domain_summary
            continue

        # 4. Held-out validation on candidate threshold
        best_threshold, best_dev_m = feasible_points[0]
        ho_m = evaluate_policy_operating_point(ho_binary, best_threshold, policy_name="combined")

        domain_summary["dev_results"] = {
            "threshold": best_threshold,
            "far_pct": round(best_dev_m.false_accept_rate * 100, 2),
            "frr_pct": round(best_dev_m.false_reject_rate * 100, 2),
        }
        domain_summary["heldout_results"] = {
            "threshold": best_threshold,
            "far_pct": round(ho_m.false_accept_rate * 100, 2),
            "frr_pct": round(ho_m.false_reject_rate * 100, 2),
        }

        print(f"  Development Feasible Point: T={best_threshold:.2f} (FAR={best_dev_m.false_accept_rate*100:.2f}%, FRR={best_dev_m.false_reject_rate*100:.2f}%)")
        print(f"  Held-Out Generalization:   T={best_threshold:.2f} (FAR={ho_m.false_accept_rate*100:.2f}%, FRR={ho_m.false_reject_rate*100:.2f}%)")

        if ho_m.false_accept_rate < 0.02 and ho_m.false_reject_rate < 0.05:
            domain_summary["status"] = "validated"
            domain_summary["recommended_mean_min"] = round(best_threshold, 2)
            domain_summary["recommended_p5_min"] = round(best_threshold, 2)
            domain_summary["reason"] = f"Empirically qualified at T={best_threshold:.2f} with verified held-out generalization"
            print(f"  VERDICT: VALIDATED — threshold {best_threshold:.2f}")
        else:
            reason = f"Failed held-out generalization (held-out FRR={ho_m.false_reject_rate*100:.2f}% >= 5.0% or FAR={ho_m.false_accept_rate*100:.2f}% >= 2.0%)"
            domain_summary["status"] = "not_qualified"
            domain_summary["reason"] = reason
            print(f"  VERDICT: NOT_QUALIFIED — {reason}")

        report["domains"][domain_key] = domain_summary

    print("\n" + "=" * 70)
    print("Summary of Model-Domain Qualifications:")
    for k, v in report["domains"].items():
        print(f"  {k:12s} -> Status: {v['status']:14s} | Reason: {v['reason']}")
    print("=" * 70)

    if output_report_path:
        output_report_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nSaved domain qualification report to: {output_report_path}")

    return report


if __name__ == "__main__":
    results_path = Path("vmaf_corpus_results.json")
    out_path = Path("vmaf_domain_qualification.json")
    run_domain_qualification(results_path, out_path)
