# VeilFrame VMAF Scientific Calibration & Remediation Report

**Study Identifier:** `VF-CAL-VMAF-REMEDIATION-2026-09`  
**Target Repository:** `sahir247/VeilFrame`  
**Execution Timestamp:** September 6, 2026  
**Calibration Engine Version:** `1.2.0`  
**libvmaf Specification:** `vmaf_v1.0.16_hfr_3d0h` / `vmaf_v1.0.16_4k_3d0h` (Model SHA-256 Verified)  
**Production Gate Status:** **LOCKED DISABLED (`VisualBudgetPolicy.vmaf_gate_enabled = False`, `vmaf_gate_mode = "audit"`)**  
**Final Decision Tree Classification:** **`NO_FEASIBLE_THRESHOLD_UNDER_EVALUATED_PROTOCOL`**  

---

## 1. Executive Summary

This report documents the scientific remediation of the VeilFrame VMAF calibration study. The primary objective was to investigate whether the observed non-separability between Netflix VMAF v1.0.16 scores and VeilFrame's authoritative visual budget policy ($\text{SSIM} \ge 0.9500, \text{PSNR} \ge 30.00\text{ dB}$) stemmed from methodological or implementation artifacts (such as temporal misalignment, decode precision truncation, pixel format handling, or calculation bugs) or represented reproducible properties of the metric and video corpus.

### Key Remediation Findings:

1. **Measurement Controls & Pipeline Consistency:**
   > The implemented measurement controls found no detected frame-count, duration, geometry, or tested precision anomalies across the audited pairs; targeted remeasurement reproduced the stored rounded VMAF statistics. Some lower-level timestamp/color-domain equivalence properties remain implementation-dependent and should not be described as mathematically proven.
   All $81$ pairs ($73$ representative Domain 1 pairs and $8$ adversarial stress pairs) passed stream-level frame-count equality ($1253$ frames for Sintel, $600$ frames for Pedestrian and Dinner), framerate matching within $0.001$ fps, and duration tolerance ($|\Delta t| \le 0.05$s).
2. **Decode-Stage Precision Sensitivity:**
   > The collapse is reproducible under the specified libvmaf v1.0.16 measurement pipeline and was not explained by the tested decode-precision conversion.
   An A/B sensitivity comparison between an 8-bit decode intermediate (`yuv420p`) and a 10-bit promoted intermediate path (`yuv420p10le`) across the $5$ most anomalous targets proved that representation promotion differences prior to VMAF calculation are negligible (median $|\Delta| = 0.70$, maximum $|\Delta| = 0.83 \le 1.50$ tolerance). **Methodological Scope:** This experiment confirms decode-stage representation stability; it does *not* evaluate whether initial 8-bit source encoding/quantization from high-precision masters caused the score shift, as high-bit-depth source encodes were not evaluated.
3. **Independent Rounded-Score Reproducibility ($\Delta = 0.00$):**
   All $6$ anomalous Sintel cluster observations (including acceptable mild blur scoring $\text{VMAF} = 20.59$, and unacceptable brightness offsets scoring $\text{VMAF} = 97.55$) were independently re-executed from source media and verified to reproduce the stored rounded VMAF statistics exactly to two decimal places ($\Delta_{\text{mean}} = 0.00, \Delta_{\text{P5}} = 0.00$). Input physical media files were verified bit-identical via SHA-256 digests.
4. **Exhaustive Domain Boundary Evaluation ($[0.0, 100.0]$):**
   Expanding the threshold search domain across the entire possible range $[0.0, 100.0]$ with integer sample accounting ($\text{max allowable } \text{FA} = \lfloor 0.02 \times N_{\text{unacc}} \rfloor = 0, \text{max allowable } \text{FR} = \lfloor 0.05 \times N_{\text{acc}} \rfloor = 0$) confirmed that **zero feasible operating points exist** in the development partition or full corpus.
5. **LOGO Cross-Validation Inexecutable:**
   Leave-One-Group-Out (LOGO) cross-validation could not be executed to evaluate held-out generalization because every training fold produced an empty feasible threshold set on its training data. Furthermore, content type (CGI vs natural video) is heavily confounded with sequence identity and distortion parameters across the 3 available groups.
6. **Operational Population Relevance:**
   The empirical findings apply strictly to full-frame controlled benchmark distortions. The operational behavior of VMAF on actual localized VeilFrame privacy transformation masks (face redaction, license plate blur) has not yet been directly characterized; `production_population_evidence` is formally declared **`insufficient`**.
7. **Subjective Validation Status:**
   A double-blind psychophysical testing protocol has been pre-specified (SAMVIQ / ITU-R BT.500), but zero human observer trials have been conducted to date. The protocol provides a framework for future empirical research, not present perceptual proof.
8. **Final Scientific Verdict:**
   In accordance with strict research ethics, no threshold was forced or relaxed. The decision tree terminates at:
   $$\mathbf{NO\_FEASIBLE\_THRESHOLD\_UNDER\_EVALUATED\_PROTOCOL}$$

---

## 2. Non-Negotiable Invariants

Throughout this remediation, the following scientific invariants remained strictly enforced:

- **Authoritative Production Policy:** VeilFrame's production visual budget remains strictly:
  $$\text{SSIM}_{\text{mean}} \ge 0.9500 \quad \land \quad \text{PSNR}_{\text{mean}} \ge 30.00\text{ dB}$$
- **Non-Circular Labeling:** Independent ground-truth labels derive exclusively from measured physical SSIM and PSNR values against the authoritative budget. VMAF values were never used to determine acceptability.
- **Production Gate Isolation:** Production gate invariants remain active:
  ```python
  VisualBudgetPolicy().vmaf_gate_enabled is False
  VisualBudgetPolicy().vmaf_gate_mode == "audit"
  ```
- **Immutable Baseline:** Historical directory `calibration/v1.0/` remained completely untouched.
- **Evidentiary Integrity:** No empirical observations were deleted, hidden, or relabeled.
- **Anti-Overclaim Contract:** A negative result is not claimed as proof of universal impossibility. The scope is strictly bounded to the evaluated protocol, content, and libvmaf v1.0.16 model.

---

## 3. Methodological Controls & Verification Audits

### 3.1 Stream-Header Frame Count & Duration Consistency Audit (Deliverable #1: `measurement_control_audit.json`)
The VMAF measurement pipeline was audited to ensure stream-level temporal and format consistency between reference and distorted streams:

- **Audit Scope:** All $81$ physical pairs ($73$ representative Domain 1 pairs, $8$ adversarial stress pairs).
- **Checks Performed:** Frame count equivalence, stream duration delta ($|\Delta t| < 0.05\text{s}$), framerate matching within $0.001$ fps ($24.0, 25.0, 30.0\text{ fps}$), pixel format (`yuv420p`), color range (`tv`), and matrix coefficients (`bt709`).
- **Audit Outcome:**
  * **Consistent Pairs:** $81 / 81$ ($100.0\%$).
  * **Frame Count Match:** Exactly $1253$ frames for Sintel, $600$ frames for Pedestrian Area and Dinner.
  * **Net Dropped / Duplicated Frames:** $0$ based on stream frame count equality.
- **Methodological Qualifications & Limitations:**
  1. *Per-Frame PTS Tracing:* The audit verifies stream-level frame count, framerate, duration tolerance, and container metadata. It does not perform packet-by-packet PTS timestamp extraction or decoded visual hash alignment for every frame.
  2. *Color-Domain Enforcement:* Color domain differences are audited under standard SDR limited-range rules. Mismatches are recorded as informational warnings and implicit conversions rather than disqualifying errors when within standard YUV420p SDR reproduction.
  3. *Bit-Depth Provenance:* Bit depth is reported from container `bits_per_raw_sample` when available, or transparently inferred from standard pixel format definitions (e.g. `yuv420p` = 8-bit). Unknown formats are reported as unknown rather than assumed 8-bit.

### 3.2 Decode-Stage Intermediate Precision Sensitivity Audit (Deliverable #3: `vmaf_pipeline_sensitivity_report.json`)
To evaluate whether converting decoded 8-bit YUV samples to a higher bit-depth representation before feeding libvmaf alters metric scores, an A/B pipeline sensitivity test was executed across the $5$ primary anomalous Sintel targets:
- **Path A (8-bit):** Standard decoded 8-bit intermediate path (`yuv420p`).
- **Path B (10-bit):** High-precision 10-bit intermediate path (`yuv420p10le`), promoting decoded 8-bit samples.
- **Pre-Declared Tolerance:** $|\Delta \text{VMAF}| \le 1.50$ points.

| Target ID | Distortion Type | Policy Status | 8-bit VMAF | 10-bit VMAF | $|\Delta\text{VMAF}|$ | Within Tol? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `JOINT_Q2_FAIL_SSIM_02_BLUR` | Spatial Blur ($\sigma=1.2$) | Acceptable ($\text{SSIM}=0.967, \text{PSNR}=33.41\text{ dB}$) | $20.59$ | $19.81$ | $0.78$ | **Yes** ($\le 1.50$) |
| `SSIM_BND_PASS_02_BLUR` | Spatial Blur ($\sigma=0.8$) | Acceptable ($\text{SSIM}=0.977, \text{PSNR}=35.65\text{ dB}$) | $39.87$ | $39.04$ | $0.83$ | **Yes** ($\le 1.50$) |
| `SSIM_BND_PASS_01` | Quantization (CRF 23) | Acceptable ($\text{SSIM}=0.960, \text{PSNR}=36.82\text{ dB}$) | $66.24$ | $65.93$ | $0.31$ | **Yes** ($\le 1.50$) |
| `REP_Q3_BRIGHT_01` | Brightness ($+0.065$) | Unacceptable ($\text{SSIM}=0.947, \text{PSNR}=28.29\text{ dB}$) | $97.55$ | $96.89$ | $0.66$ | **Yes** ($\le 1.50$) |
| `REP_Q4_GAMMA_CRF_01` | Gamma + CRF | Unacceptable ($\text{SSIM}=0.937, \text{PSNR}=29.54\text{ dB}$) | $89.73$ | $89.03$ | $0.70$ | **Yes** ($\le 1.50$) |

**Conclusion & Methodological Boundaries:**
- Differences are negligible (median $|\Delta| = 0.70$, max $|\Delta| = 0.83 \le 1.50$), establishing decode-stage representation stability.
- **What this proves:** Converting decoded 8-bit samples to a 10-bit representation prior to VMAF calculation does not materially change the score.
- **What this does NOT prove:** This experiment does not evaluate whether original 8-bit video encoding/quantization from high-precision source masters contributed to the anomaly, as uncompressed high-bit-depth source encodes were not generated.
- **Sub-feature causality:** While the collapse on blur is consistent with the design of detail and edge-loss features (`adm2`, `vif_scale0`), specific sub-feature mathematical causality remains a hypothesis that has not been isolated via causal ablation experiments.

### 3.3 Independent Rounded-Score Reproducibility Audit (Deliverable #2: `vmaf_measurement_reproducibility_report.json`)
All $6$ anomalous targets from the Sintel empirical study were independently re-measured from the source `.y4m` master and distorted `.mp4` files, comparing fresh physical runs against committed evidence files:

| Cluster | Fixture ID | Policy Status | Fresh SSIM | Fresh PSNR | Fresh VMAF | Stored VMAF | Delta | Rounded Match | Physical SHA-256 Match |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Acceptable VMAF ~20.59** | `JOINT_Q2_FAIL_SSIM_02_BLUR` | Acceptable | $0.9673$ | $33.41\text{ dB}$ | $20.59$ (P5: $0.00$) | $20.59$ (P5: $0.00$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |
| **Acceptable VMAF ~35–40** | `SSIM_BND_PASS_02_BLUR` | Acceptable | $0.9768$ | $35.65\text{ dB}$ | $39.87$ (P5: $1.62$) | $39.87$ (P5: $1.62$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |
| **Acceptable VMAF ~57–66** | `SSIM_BND_FAIL_01` | Acceptable | $0.9506$ | $34.65\text{ dB}$ | $57.07$ (P5: $22.09$) | $57.07$ (P5: $22.09$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |
| **Acceptable VMAF ~57–66** | `SSIM_BND_PASS_01` | Acceptable | $0.9603$ | $36.82\text{ dB}$ | $66.24$ (P5: $34.61$) | $66.24$ (P5: $34.61$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |
| **Unacceptable VMAF ~90–98** | `REP_Q3_BRIGHT_01` | Unacceptable | $0.9466$ | $28.29\text{ dB}$ | $97.55$ (P5: $89.91$) | $97.55$ (P5: $89.91$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |
| **Unacceptable VMAF ~90–98** | `REP_Q4_BRIGHT_CRF_01` | Unacceptable | $0.9123$ | $25.26\text{ dB}$ | $93.83$ (P5: $82.22$) | $93.83$ (P5: $82.22$) | $0.00$ | **EXACT (2 decimals)** | **MATCHED** |

**Conclusion:** 100% reproduction of the stored rounded VMAF statistics (exact to two decimal places, delta = 0.00 across all 6 targets) is confirmed. Input files were verified bit-identical via SHA-256. This confirms rounded score reproducibility on disk; full floating-point bitstream equality was not evaluated.

---

## 4. Exhaustive Decision-Boundary Threshold Analysis (Deliverable #6: `exhaustive_threshold_analysis.json`)

To eliminate the possibility that a valid operating point was missed due to search window restrictions, the threshold evaluation was expanded across the full domain $[0.0, 100.0]$.

### 4.1 Evaluation Formulation & Constraints
- Decision Rule: $\mathcal{P}(T): \min(V_{\text{mean}}, V_{\text{P5}}) \ge T$
- Predefined Research Constraints: $\text{FAR} < 0.0200$ and $\text{FRR} < 0.0500$
- Development Partition: $N = 44$ binary evaluation samples ($N_{\text{acc}} = 19, N_{\text{unacc}} = 25$)
- Maximum Allowable Violations:
  $$\text{Max Allowable False Accepts} = \lfloor 0.02 \times 25 \rfloor = 0$$
  $$\text{Max Allowable False Rejects} = \lfloor 0.05 \times 19 \rfloor = 0$$

### 4.2 Exhaustive Search Results
The classifier status changes only at unique observed sample scores. The search evaluated:
- **Unique Observed Scores:** $44$ distinct values in $[29.15, 98.71]$
- **Evaluated Segments:** $88$ total segments (exact boundaries, mid-interval points, outer bounds $[0.0, 29.15)$ and $(98.71, 100.0]$)
- **Feasible Operating Points Found:** **$0$**
- **Feasible Intervals:** **NONE ($\emptyset$)**

```text
Threshold T    Point Type            FP (FA)    FN (FR)    FAR       FRR       Status
───────────────────────────────────────────────────────────────────────────────────────
T = 0.00       Left Bound [0, 29.15) 25         0          100.0%    0.0%      REJECTED (FAR >= 2%)
T = 29.15      Exact Boundary        25         0          100.0%    0.0%      REJECTED (FAR >= 2%)
T = 55.35      Mid-Interval Point    22         0          88.0%     0.0%      REJECTED (FAR >= 2%)
T = 74.98      Mid-Interval Point    19         0          76.0%     0.0%      REJECTED (FAR >= 2%)
T = 85.11      Exact Boundary        15         1          60.0%     5.26%     REJECTED (Both violated)
T = 88.76      Mid-Interval Point    10         2          40.0%     10.53%    REJECTED (Both violated)
T = 91.60      Exact Boundary        4          4          16.0%     21.05%    REJECTED (Both violated)
T = 95.04      Mid-Interval Point    2          8          8.0%      42.11%    REJECTED (Both violated)
T = 98.71      Exact Boundary        0          19         0.0%      100.0%    REJECTED (FRR >= 5%)
T = 100.00     Domain Endpoint       0          19         0.0%      100.0%    REJECTED (FRR >= 5%)
```

At low thresholds ($T \le 75$), FAR is catastrophically high ($\ge 76\%$), admitting severe noise, extreme quantization, and uncalibrated shifts. At high thresholds ($T \ge 85$), FRR explodes ($\ge 5.26\% \to 100\%$), rejecting acceptable video. The intersection of $\text{FAR} < 2\%$ and $\text{FRR} < 5\%$ is strictly empty.

---

## 5. Cross-Group Generalization & Population Alignment

### 5.1 Leave-One-Group-Out Cross-Validation (Deliverable #7: `vmaf_generalization_report.json`)
Cross-validation evaluated whether a model trained on a subset of groups could produce a viable operating threshold for held-out evaluation:

| Fold (Held-Out Group) | Training Groups | Training Feasible Candidates | Held-Out Execution Status |
| :--- | :--- | :--- | :--- |
| **Fold 1: `dinner`** | `pedestrian_area`, `sintel_trailer` | $0$ (None) | **Not Executed** (Training produced empty feasible set) |
| **Fold 2: `pedestrian_area`** | `dinner`, `sintel_trailer` | $0$ (None) | **Not Executed** (Training produced empty feasible set) |
| **Fold 3: `sintel_trailer`** | `dinner`, `pedestrian_area` | $0$ (None) | **Not Executed** (Training produced empty feasible set) |

**Statistical Interpretation & Confounding:**
- LOGO qualification was not executable because every training fold failed to produce a qualifying threshold under the mandated FAR < 2% and FRR < 5% constraints. This represents an in-fold threshold non-discoverability result, rather than a failure of generalization from a trained model.
- Because the corpus contains only 3 sequence groups (2 natural photographic, 1 CGI animation), content type is heavily confounded with sequence identity, spatial texture complexity, motion dynamics, and specific distortion implementations across the 3 available groups. The observed differences cannot be definitively isolated as a CGI-vs-natural domain gap.

### 5.2 Population Relevance Audit (Deliverable #5: `production_population_report.json`)
The audit formally compared calibration test conditions with actual production video cleaner workloads:
- **Production Reality:** Localized bounding-box blurring (faces, license plates), spatial tracking feathering, variable CCTV/sensor noise, edge-preserving redaction.
- **Corpus Reality:** Full-frame global parametric filters applied uniformly to pristine 1080p benchmark masters.
- **Scope of Findings:** Our empirical findings apply strictly to controlled benchmark distortions on full frames. The operational behavior of VMAF on actual localized VeilFrame privacy transformation masks has not yet been directly characterized; `production_population_evidence` is formally declared **`insufficient`**.

### 5.3 Sampling-Design Limitations (Decision-Boundary Stress Testing)
The 73 representative samples in Deliverable #4 (`representative_corpus_report.json`) were purposefully synthesized with distortion parameters clustered near the $\text{SSIM} \ge 0.9500$ and $\text{PSNR} \ge 30.00\text{ dB}$ policy thresholds to evaluate classifier separability under difficult edge cases. While labels are determined independently by formula, the sampling distribution reflects boundary stress testing rather than the natural distribution of real-world video cleaner traffic.

---

## 6. Subjective Validation Protocol Summary (Deliverable #8)

To resolve metric conflicts without circular reasoning, a formal psychophysical subjective testing protocol has been designed:
- **Protocol Status:** **PRE-SPECIFIED EXPERIMENTAL PROTOCOL (Unexecuted; Zero Human Data Collected to Date)**.
- **Evidence Classification:** Future experimental protocol design — does not constitute empirical perceptual proof.
- **Standard:** SAMVIQ (ITU-R BT.1788 / ITU-T P.910).
- **Environment:** Calibrated Rec.709 D65 OLED master monitor, 3H viewing distance, controlled darkroom ($< 5\text{ lux}$).
- **Cohort:** $N = 30$ naïve human observers screened for 20/20 acuity and Ishihara color vision.
- **Core Hypothesis Testing:**
  * $H_1$: Does human visual quality accept mild CGI blur ($\text{VMAF} \approx 20$) as $\text{DMOS} \ge 75$? (If yes, VMAF is falsified for edge-softening).
  * $H_2$: Does human visual quality penalize brightness/chroma shifts ($\text{VMAF} \approx 97$) as $\text{DMOS} \le 65$? (If yes, VMAF is falsified for DC offsets).

---

## 7. Final Scientific Classification & Action Plan

### 7.1 Final Decision Tree Verdict
In strict compliance with the scientific integrity contract:
$$\mathbf{NO\_FEASIBLE\_THRESHOLD\_UNDER\_EVALUATED\_PROTOCOL}$$

*Scope Statement:* This finding applies specifically to libvmaf v1.0.16 standard models evaluated on the primary SDR calibration corpus against VeilFrame's visual budget policy under strict $\text{FAR} < 2.0\%$ and $\text{FRR} < 5.0\%$ research constraints.

### 7.2 Production Action Plan
1. **Gate Invariant Maintained:** `vmaf_gate_enabled = False` and `vmaf_gate_mode = "audit"` remain unchanged.
2. **Authoritative Policy Retained:** SSIM ($\ge 0.9500$) and PSNR ($\ge 30.00\text{ dB}$) remain the sole authoritative gatekeepers in VeilFrame production pipelines.
3. **No Relaxation:** Research constraints are NOT weakened to force an artificial pass.
4. **Audit Logging:** VMAF calculations continue running asynchronously in audit mode for diagnostic observability without blocking video cleaner operations.
