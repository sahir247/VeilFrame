# VeilFrame VMAF Research Archive

## Overview

This directory preserves the historical research, calibration studies, measurement control audits, and dataset provenance generated during the evaluation of **VMAF v1.0.16** as a candidate quality metric for VeilFrame.

## Archival Status & Core Decision

> **CORE DECISION:** VMAF is **not** qualified for production quality enforcement in VeilFrame. It has been permanently excised from all production runtime code, configuration surfaces, API endpoints, GUI panels, and build/CI dependencies.

Production VeilFrame quality evaluation relies strictly and authoritatively on native FFmpeg filters:
$$\text{SSIM} \ge 0.9500 \quad \land \quad \text{PSNR} \ge 30.0\text{ dB}$$

### Rationale for Non-Qualification
VMAF v1.0.16 was not qualified to reproduce VeilFrame's authoritative SSIM/PSNR acceptance policy under the evaluated conditions and constraints:
1. **Decision Incompatibility:** Across evaluated sequences and distortion domains, VMAF decision scores could not reliably separate passing from failing items under the independent policy without unacceptable false-accept or false-reject rates.
2. **Sensitivity to Format & Pipeline Controls:** Metric scores exhibited variance across chroma formats, decode bit-depth promotion, and duration slices that complicated threshold generalization.
3. **Operational & Dependency Burden:** Requiring `libvmaf` imposed external compiled dependencies, model asset files, and CPU overhead without providing superior discrimination over the deterministic native FFmpeg SSIM/PSNR gate.

## Directory Structure

```text
research/vmaf/
    ├── reports/        # Static analysis reports, threshold sweeps, and calibration summaries (Markdown, JSON, CSV)
    ├── provenance/     # Corpus inventory, licensing ledgers, and model provenance manifests
    ├── evidence/       # Raw JSON per-frame and per-fixture VMAF evidence logs from calibration sweeps
    └── README.md       # This document
```

### Archived Contents

- `evidence/`:
  - 92 raw per-frame and boundary fixture metric evidence manifests (`*_vmaf_evidence.json`).

- `reports/`:
  - `calibration_report.md` & `calibration_summary.md`: Initial Phase A calibration laboratory findings.
  - `duration_sensitivity_report.md` & `duration_sensitivity.json`: Analysis of metric variance across clip duration slices.
  - `exhaustive_threshold_analysis.json`: Multi-threshold empirical confusion matrix over development and heldout splits.
  - `measurement_control_audit.json`: Stream alignment, container timing, and filtergraph control audit.
  - `vmaf_domain_qualification.json`: Technical domain qualification study across SDR, HDR, and WCG content.
  - `vmaf_pipeline_sensitivity_report.json`: Decode-stage bit-depth and pipeline sensitivity experiments.
  - `subjective_validation_protocol.md`: Protocol design for subjective double-blind comparison.

- `provenance/`:
  - `corpus_inventory.json` & `corpus_inventory.csv`: Complete metadata inventory of benchmark video sequences.
  - `model_provenance_report.json`: Cryptographic hashes and provenance for VMAF model files.
  - `provenance_license_ledger.json`: Rights, attribution, and license terms for test corpus media.
  - `chimera_episode_index.json`: Temporal scene segmentation index for the Chimera reference sequence.

## Engineering Policy

1. **No Secondary Executable Code:** This directory is strictly an evidence repository. It contains no executable Python tools or test runners.
2. **Immutable Record:** The reports and data files herein document the empirical justification for the decision not to adopt VMAF into VeilFrame.
