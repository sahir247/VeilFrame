# VeilFrame Calibration Baseline v1.0 (Frozen Scientific Baseline)

**Status:** IMMUTABLE SCIENTIFIC BASELINE  
**Study ID:** `VF-CAL-VMAF-2026-09`  
**Dataset Version:** `1.2.0`  
**Analysis Version:** `1.2.0`  
**Policy Version:** `vmaf-policy-0` (Audit baseline)  
**Final Verdict:** `NO_FEASIBLE_THRESHOLD`

---

## Provenance & Integrity Statement

This archive contains the frozen, immutable artifacts of the VeilFrame VMAF v1.0.16 calibration study.
These artifacts document the exhaustive search across the 13 independent sequence groups (112 fixture pairs, 98 binary evaluation samples, 70 development / 28 held-out split with random seed 42) which established that **no single global scalar threshold satisfies the research operating constraints**:
- $\text{FAR} < 2.0\%$
- $\text{FRR} < 5.0\%$

### Frozen Files Inventory

1. `manifest.json`: Corpus manifest with reference SHA-256s, fixture definitions, and tool versions.
2. `corpus_results.json`: Complete 112-fixture measurement results with per-pair VMAF, SSIM, PSNR, ADM2, VIF, motion scores, model IDs, and evidence hashes.
3. `threshold_analysis.json`: Exhaustive decision boundary evaluation across development and held-out partitions, proving empty intersection.
4. `threshold_sweep.csv`: Tabular sweep of all unique decision boundaries on development and held-out sets.
5. `duration_sensitivity_report.md`: Duration sensitivity study demonstrating temporal consistency across prefixes from $t=0$.
6. `duration_sensitivity.json` & `duration_sensitivity.csv`: Data backing the duration sensitivity study.
7. `calibration_report.md`: Full scientific calibration report.
8. `calibration_summary.md`: Concise executive summary and operational reference.
9. `development_split.json` & `heldout_split.json`: Exact grouped partition files.
10. `corpus_inventory.json` & `corpus_inventory.csv`: Full provenance and license ledger.
11. `data_quality_report.json`: Integrity verification report.
12. `sequence_group_report.json`: Group distribution and scene characteristic accounting.
13. `model_provenance_report.json`: Verified official VMAF v1.0.16 model checksum verification.
14. `excluded_samples.json`: Quarantined boundary and excluded samples accounting.
15. `calibration_analysis.json`: Detailed metric distribution summary.

### Invariance Rule
These artifacts MUST NEVER be modified or overwritten by subsequent production gating logic, domain qualification sweeps, or policy enhancements. They serve as the permanent, reproducible empirical record.
