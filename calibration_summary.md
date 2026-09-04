# Executive Calibration Briefing: VMAF v1.0.16 Empirical Study

**Study Identifier**: `VF-CAL-VMAF-2026-09`  
**Target Repository**: `sahir247/VeilFrame`  
**Execution Timestamp**: 2026-09-04  
**Engine Version**: `1.2.0`  
**Empirical Verdict**: `NO_FEASIBLE_THRESHOLD`  
**Production Gate Status**: **DISABLED (`VisualBudgetPolicy.vmaf_gate_enabled = False`)**  

---

## 1. Executive Summary

This executive briefing presents the empirical findings of the controlled calibration study evaluating **Netflix VMAF v1.0.16** against VeilFrame's production visual budget policy:
$$\text{Fidelity Policy: } \text{SSIM}_{\text{mean}} \ge 0.9500 \;\land\; \text{PSNR}_{\text{mean}} \ge 30.00\text{ dB}$$

The study evaluated 144 multimedia items cataloged across four operational domains, conducting decoupled, independent measurements across 128 fixture pairs.

### Primary Calibration Finding
$$\mathbf{VERDICT: \;\; NO\_FEASIBLE\_THRESHOLD}$$

Within the primary SDR calibration domain (Domain 1: 13 sequence groups, 14 reference clips, 112 measured fixture pairs, 98 binary evaluation samples), **no global scalar operating point** $T$ for the coupled decision policy:
$$\mathcal{P}(T): \quad V_{\text{mean}} \ge T \;\land\; V_{p5} \ge T$$
satisfies the predefined research constraints of:
$$\text{FAR} < 2.0\% \quad\text{and}\quad \text{FRR} < 5.0\%$$

Under VeilFrame's scientific integrity contract, **no threshold has been manufactured, forced, or relaxed**. The system refuses to activate a flawed gate, adhering strictly to the principle that a credible "no" is an engineering success.

---

## 2. Formal Hypothesis Outcome

| Hypothesis | Proposition | Empirical Result | Outcome |
| :--- | :--- | :--- | :--- |
| **$H_0$ (Null)** | There exists no threshold $T \in [70.0, 100.0]$ simultaneously satisfying $\text{FAR}(T) < 0.020 \land \text{FRR}(T) < 0.050$. | $\min(\text{FAR}) = 1.92\% \implies \text{FRR} = 5.56\% > 5.0\%$ | **RETAINED** |
| **$H_1$ (Alternative)** | There exists at least one feasible candidate $T^*$ satisfying both constraints on development and surviving held-out validation. | Feasible candidate set on development partition is empty ($\emptyset$). | **REJECTED** |

---

## 3. Four-Domain Corpus Architecture & Accounting

The local video resources were inventoried and segregated into four distinct operational domains:

```text
                           RESOURCE_VIDEOS (144 Items)
                                       │
           ┌───────────────────────────┼───────────────────────────┬───────────────────────────┐
           ▼                           ▼                           ▼                           ▼
       Domain 1                    Domain 2                    Domain 3                    Domain 4
     Primary SDR             Secondary / Legacy           HDR / Wide Gamut             Neuromorphic
  (13 Groups / 14 Clips)       (720p & Classic SD)         (Chimera, SPARKS,            (HUE_Controlled
                                                             SolLevante)               17 Conditions)
           │                           │                           │                           │
  Strict Model Valid          Diagnostic Generalization       Segregated as PQ/P3         Audit Only
  112 Fixture Pairs           Not for Primary Calibration    "not_applicable_hdr"         Event Sensor
```

### Complete Accounting Summary

| Domain | Category | Sequences / Conditions | Fixture Pairs | Measurement Treatment | Operational Role |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **Domain 1** | Primary SDR | 13 groups (14 clips) | 112 pairs | Full VMAF + SSIM/PSNR (120 evidence JSONs) | Primary Threshold Calibration |
| **Domain 2** | Secondary / Legacy | 6 sequences (720p & SD) | Diagnostic | Measured diagnostically with model check | Robustness & Diagnostic Generalization |
| **Domain 3** | HDR / Wide Gamut | 25 files (Chimera, SPARKS, SolLevante) | 8 pairs | Cryptographically hashed; status `not_applicable_hdr` | Segregated; Prevents SDR Metric Corruption |
| **Domain 4** | Sensor / Illumination | 17 conditions (HUE_Controlled) | Audited | Audited for temporal event noise characteristics | Illumination Sensitivity Baseline |

---

## 4. Algorithmic Grouped Partitioning (Domain 1)

In compliance with the methodological rules:
- Partitioning was executed **algorithmically under hard constraints** ($\ge 12$ total groups, $\ge 8$ dev groups, $\ge 4$ held-out groups, $\ge 60$ total binary samples, $\ge 40$ dev binary samples, $\ge 20$ held-out binary samples, both classes present in dev & held-out, zero sequence leakage).
- The resulting allocation is **9 development groups / 4 held-out groups** (Seed 42), derived algorithmically rather than assumed statically:
  - **Development Partition (9 groups, 10 clips, 80 pairs, 70 binary, 10 boundary)**:
    `ducks_take_off`, `ide_editing`, `old_town_cross`, `park_joy` (2160p25 & 2160p50), `pdf_reading`, `red_kayak`, `rush_field_cuts`, `speed_bag`, `tractor`.
    - 18 acceptable / 52 unacceptable samples.
  - **Held-Out Partition (4 groups, 4 clips, 32 pairs, 28 binary, 4 boundary)**:
    `aspen`, `browsing`, `night_drive`, `snow_mnt`.
    - 8 acceptable / 20 unacceptable samples.

> [!NOTE]
> **Statistical Safeguard Disclaimer**: The minimum sample/group requirements are engineering eligibility safeguards, not a claim of statistical power or universal perceptual validity. Confidence intervals and uncertainty are reported independently of the minimum-data pass/fail decision.

---

## 5. Development Partition Operating Curves & Exhaustive Boundary Proof

### Discrete Grid Sweep ([70.0, 100.0], Step 0.5)
| Threshold Range $T$ | False Accept Rate ($\text{FAR}$) | Target $\text{FAR} < 2.0\%$ | False Reject Rate ($\text{FRR}$) | Target $\text{FRR} < 5.0\%$ | Feasibility Status |
| :---: | :---: | :---: | :---: | :---: | :---: |
| **$70.0 \le T \le 89.5$** | **$3.85\%$** | ❌ FAILS ($> 2.0\%$) | **$0.00\%$** | ✅ PASSES | **INFEASIBLE** |
| **$90.0 \le T \le 92.5$** | **$1.92\%$** | ✅ PASSES ($< 2.0\%$) | **$5.56\%$** | ❌ FAILS ($> 5.0\%$) | **INFEASIBLE** |
| **$93.0 \le T \le 95.0$** | **$0.00\% - 1.92\%$** | ✅ PASSES | **$11.11\%$** | ❌ FAILS ($> 5.0\%$) | **INFEASIBLE** |
| **$T \ge 95.5$** | **$0.00\%$** | ✅ PASSES | **$22.22\% - 44.44\%$** | ❌ FAILS (Catastrophic) | **INFEASIBLE** |

### Exhaustive Decision-Boundary & Interval Analysis
To eliminate discrete grid artifacts and avoid arbitrary floating-point epsilons, the calibration engine evaluated every unique observed decision value $V_{\text{dec}} = \min(V_{\text{mean}}, V_{p5})$ and every constant open interval $(v_i, v_{i+1})$:
- **Unique Observed Decision Scores in $[70.0, 100.0]$**: 11 values:
  `71.46, 89.72, 90.33, 92.58, 93.09, 93.97, 94.40, 95.30, 95.53, 98.40, 100.00`
- **Total Segments Evaluated**: 22 exact boundaries and invariant open intervals.
- **Interval Breakdown**:
  - $T \le 89.72$: $\text{FAR} = 3.85\% \ge 2.0\%$ (FAILS FAR, 2 FA / 52 unacc)
  - $(89.72, 90.33)$: $\text{FAR} = 3.85\% \ge 2.0\%$ (FAILS FAR) **and** $\text{FRR} = 5.56\% \ge 5.0\%$ (FAILS FRR, 1 FR / 18 acc)
  - $[90.33, 92.58]$: $\text{FAR} = 1.92\% < 2.0\%$ (PASSES FAR), but $\text{FRR} = 5.56\% \ge 5.0\%$ (FAILS FRR)
  - $(92.58, 93.09)$: $\text{FAR} = 1.92\% < 2.0\%$ (PASSES FAR), but $\text{FRR} = 11.11\% \ge 5.0\%$ (FAILS FRR, 2 FR / 18 acc)
  - $T \ge 93.09$: $\text{FRR} \ge 11.11\% \ge 5.0\%$ (FAILS FRR)

### Exact Mathematical Proof of Empty Intersection
$$\{T \in [70.0, 100.0] \mid \text{FAR}(T) < 0.020\} = [90.33, 100.0]$$
$$\{T \in [70.0, 100.0] \mid \text{FRR}(T) < 0.050\} = [70.0, 89.72]$$
$$\mathbf{\{T \mid \text{FAR}(T) < 0.020\} \cap \{T \mid \text{FRR}(T) < 0.050\} = \emptyset}$$

### Geometry Sensitivity Analysis (`ide_editing` 1808x1080)
Both configurations were evaluated independently with the exhaustive boundary search:
- **Domain 1 Full (with `ide_editing`, 9 dev groups, 70 binary samples)**: `no_feasible_threshold`
- **Domain 1 Quarantined (without `ide_editing`, 8 dev groups, 63 binary samples)**: `no_feasible_threshold`
  - In the quarantined configuration, `speed_bag` VERY_LOW ($V_{\text{dec}} = 92.58$) causes $\text{FRR} = 6.25\% \ge 5.0\%$ at $T > 92.58$, while $T \le 90.33$ yields $\text{FAR} = 4.26\% \ge 2.0\%$. Feasible intervals count = 0.
- **Verdict**: The absence of a feasible threshold is robust to the inclusion or exclusion of the 1808x1080 geometry.

Because no threshold emerged from development, the held-out partition was **PRESERVED UNTOUCHED (Validation Not Executed)**, preserving its cryptographic purity.

---

## 6. Root Causes of Failure Modes & Empirical Deep Dive

1. **High-Frequency Texture & Water Turbulence Masking (Drives FAR Failure)**:
   - **`old_town_cross` (Architectural Masonry)**: Fixture `VERY_LOW` scored $VMAF_{\text{mean}} = 94.34, V_{p5} = 93.09 \implies V_{\text{dec}} = 93.09$ despite failing VeilFrame's independent policy ($SSIM = 0.9338 < 0.9500, PSNR = 38.76\text{ dB}$). VMAF's ADM2 detail feature over-predicted visual quality on high-frequency masonry textures. Falsely accepted at all $T \le 93.09$.
   - **`ducks_take_off` (Water Surface / Turbulence)**: Fixture `VERY_LOW` scored $VMAF_{\text{mean}} = 93.48, V_{p5} = 90.33 \implies V_{\text{dec}} = 90.33$ despite failing independent policy ($SSIM = 0.9208 < 0.9500, PSNR = 34.72\text{ dB}$). Temporal water motion masked structural divergence. Falsely accepted at all $T \le 90.33$.
   - **`tractor` (Agricultural Scene)**: Fixture `LOW_PERTURBATION` scored $V_{\text{dec}} = 71.46$ despite failing policy ($SSIM = 0.8590, PSNR = 29.17\text{ dB}$). Falsely accepted at $T \le 71.46$.

2. **Screen Content & Fine Detail Rejections (Drives FRR Failure)**:
   - **`ide_editing` (Code Editor UI)**: Fixture `VERY_LOW` achieved outstanding fidelity ($SSIM = 0.9965 \ge 0.9500, PSNR = 47.43\text{ dB} \ge 30.00\text{ dB}$), qualifying as independently `acceptable`. However, subtle sub-pixel edge divergence caused $VMAF_{\text{mean}} = 93.51$ and $V_{p5} = 89.72 \implies V_{\text{dec}} = 89.72$. At any threshold $T > 89.72$ (including the $T \in [90.0, 92.5]$ window required to suppress false accepts), this acceptable sample is **falsely rejected**, causing $FRR = 1 / 18 = 5.56\% > 5.0\%$.
   - **`speed_bag` (High-Speed Motion)**: Fixture `VERY_LOW` achieved $SSIM = 0.9822, PSNR = 45.65\text{ dB}$ (independently `acceptable`), but $V_{p5} = 92.58 \implies V_{\text{dec}} = 92.58$. At $T > 92.58$ (e.g. $T = 93.0$), it is **falsely rejected**, escalating $FRR$ to $2 / 18 = 11.11\%$.
   - **Ground-Truth Clarification**: Note that `ide_editing` under `LOW_PERTURBATION` scored $SSIM = 0.9026 < 0.9500$ and is classified as `unacceptable`. Its rejection at $T \ge 85.0$ is a **True Reject**, not a False Reject. The actual false reject driving the $FRR > 5.0\%$ failure is fixture `VERY_LOW`.

---

## 7. Duration Sensitivity Study (Study B) & Comparative Synthesis

To evaluate whether the empty feasible region was an artifact of short temporal clip length (~2–5s baseline), a controlled duration sensitivity study was executed using nested prefixes anchored at $t=0.0\text{s}$:
- **Study Scope**: 54 evaluations across three canonical sequence groups (`ducks_take_off`, `old_town_cross`, `speed_bag`) spanning durations from 2s to 10s, with supplementary screen-content observations on `ide_editing` from 10s to 30s.
- **Empirical Boundary Comparison**:
  - **Study A (~2–5s, 13 groups)**: $\text{FAR} < 2.0\%$ requires **$T > 90.33$**; $\text{FRR} < 5.0\%$ requires **$T \le 89.72$** $\implies \mathcal{F} = \emptyset$.
  - **Study B (10–30s, evaluated groups)**: $\text{FAR} < 2.0\%$ requires **$T > 93.25$**; $\text{FRR} < 5.0\%$ requires **$T \le 90.12$** $\implies \mathcal{F} = \emptyset$.
- **Key Empirical Observations**:
  - For `ducks_take_off`, extending duration from 5s to 10s raises VMAF mean to **97.44 and P5 to 94.48 while SSIM remains failing at 0.9215** ($PSNR = 36.15\text{ dB}$), actually exacerbating the false-accept problem under longer temporal windows.
  - For `ide_editing`, the acceptable transformation remains anchored around a VMAF decision score of **~90** ($V_{\text{dec}} = 90.12$ at 10s, $90.05$ at 20s, $89.98$ at 30s).
- **Supporting Evidence Framing**: For the evaluated sequence groups and controlled nested durations, the observed incompatibility between the VMAF decision score and the independent policy persists with longer temporal windows. The duration study serves as strong supporting evidence, while the exhaustive decision-boundary search on the full 13-group development partition remains the primary mathematical proof for `NO_FEASIBLE_THRESHOLD`.

---

## 8. Independent Labeling Safeguard Confirmation

Fixture nominal labels (`VERY_LOW`, `HIGH`, etc.) were treated strictly as semantic identifiers. Labels were computed from actual measured SSIM and PSNR:
- On `old_town_cross` and `ducks_take_off`, nominal `VERY_LOW` produced $SSIM < 0.9500$ and was classified strictly as **`unacceptable`**.
- Naive nominal labeling would have erroneously counted these as "acceptable" and declared a false pass. The independent policy rule prevented circular reasoning.

---

## 9. Baseline Hardcoded Threshold Comparison

VeilFrame's existing placeholder defaults in `VisualBudgetPolicy`:
$$\text{vmaf\_mean\_min} = 85.0, \quad \text{vmaf\_p5\_min} = 75.0, \quad \text{vmaf\_worst\_min} = 70.0$$
- Empirical evaluation on development data yields: $\text{FAR} = 3.85\%$, $\text{FRR} = 0.00\%$.
- FAR exceeds the maximum allowable production safety threshold ($\text{FAR} < 2.0\%$).
- This confirms that the baseline numbers are **uncalibrated engineering placeholders** and must not be armed in production.

---

## 10. Deliverables & Verification Checklist

All 19 deliverables have been generated and cryptographically verified:

| # | Deliverable Artifact | Description | Status / SHA-256 |
| :-: | :--- | :--- | :--- |
| 1 | [`corpus_inventory.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/corpus_inventory.json) | Complete 144-item inventory | `062e15e3a9a40bf9b05f230063b5ccc0678a54e783dfdb19f4a1424ef269c2c6` |
| 2 | [`corpus_inventory.csv`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/corpus_inventory.csv) | Tabular inventory spreadsheet | `5c4e39b3391ceacdfbb9882e465f3a1222f0bf3145ee04a92a960c373d6964be` |
| 3 | [`provenance_license_ledger.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/provenance_license_ledger.json) | License & origin ledger | `7646d62e96988cf16044e79588e04ce275f985a0ea6928ebaa62a84d4d26bb73` |
| 4 | [`calibration_corpus/manifest.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/calibration_corpus/manifest.json) | Frozen manifest v2 | `06cea3c5520d969cff85360c7283932b55135dc82ab831d22f79ebbe673528e9` |
| 5 | [`vmaf_corpus_results.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/vmaf_corpus_results.json) | Consolidated 128-pair results | `90f12b27e2a3045799b80387c4d878145764601081ed0ff0966a6433d35dd797` |
| 6 | [`calibration_corpus/evidence/`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/calibration_corpus/evidence/) | 120 raw VMAF evidence JSONs | Complete directory |
| 7 | [`calibration_analysis.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/calibration_analysis.json) | Sweep and partition analysis | `b8e4647b53c0be10e04aaaae1bd32b56d245d621f1908c326c1005359d5000dc` |
| 8 | [`calibration_report.md`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/calibration_report.md) | Comprehensive 26-section report | Verified Complete |
| 9 | [`threshold_sweep.csv`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/threshold_sweep.csv) | Operating curves [70.0, 100.0] | `7c9afee4dbce408a883a470e46788dfc5dbe659bc5f7cb0221e334162f865418` |
| 10 | [`development_split.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/development_split.json) | Dev partition (9 groups, 70 bin) | `38f8ae7c4d5e8d6d7217baed24b57f36e71d8d5a69a76a508ad4c740903cc6a7` |
| 11 | [`heldout_split.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/heldout_split.json) | Held-out partition (4 groups, 28 bin) | `5021e1d4e97bd476cef58f05694c8891266dbe4c016452889b6988c11da95a7a` |
| 12 | [`data_quality_report.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/data_quality_report.json) | Quality and confidence audit | `88e91e5760259e42fbfef4f94e8f7b440cfe38b94580730c32eb2d6c55e727dc` |
| 13 | [`excluded_samples.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/excluded_samples.json) | Quarantine log (HDR & 720p) | `a14a56f3001b262bd922ac55e165da6ea4d74ec1ece429b88556c090034b8652` |
| 14 | [`sequence_group_report.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/sequence_group_report.json) | Sequence group accounting | `b3e110ad1d483ff775ac5b5b639f1c05daf4bafd2dfb5ed7287d842b249f699b` |
| 15 | [`model_provenance_report.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/model_provenance_report.json) | Model SHA-256 verification | `4310849d634e001e99b63b2405ada78a829d3ac734c1e96e5f79f5b3dba39d7a` |
| 16 | [`calibration_summary.md`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/calibration_summary.md) | Executive brief | Current Document |
| 17 | [`duration_sensitivity.csv`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/duration_sensitivity.csv) | Duration experiment raw dataset | `3f2ff59f839067ed839d44302a2b4de888de72ac4adc6dee6f57df7688f18995` |
| 18 | [`duration_sensitivity.json`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/duration_sensitivity.json) | Duration study records & metrics | `4d3eb5cbc9f7ab99b4590cea15b5c838557c2f338ac8811c57e574dff31b3bc2` |
| 19 | [`duration_sensitivity_report.md`](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/duration_sensitivity_report.md) | Duration scientific comparison report | `752de1e6ec8ef1152ca50f0781cf348b99185461e5b366ec30073dd6089870ca` |

---

## 11. Final Governance & Invariant Sign-Off

1. **Scientific Conclusion**: For the evaluated Domain-1 corpus, no global scalar $\min(\text{VMAF}_{\text{mean}}, \text{VMAF}_{p5})$ threshold in $[70, 100]$ satisfies both $\text{FAR} < 2\%$ and $\text{FRR} < 5\%$. Longer-duration testing on the evaluated challenging sequences shows that this incompatibility persists, but does not establish duration irrelevance across the entire corpus.
2. **Operational Posture**: VMAF remains measurement and diagnostic evidence only; no production threshold should be promoted.
3. **Gate Activation Policy**: **REJECTED.** No empirical operating point exists for a single global VMAF scalar threshold under VeilFrame's safety constraints.
4. **Production Code State**:
   $$\mathbf{VisualBudgetPolicy.vmaf\_gate\_enabled = False}$$
5. **Operational Protection**: VeilFrame's production video pipeline continues to enforce SSIM, PSNR, temporal flicker, and bounding-box privacy protections without disruption.
6. **VMAF Provider Contract**: *Providers measure; QualityGate decides.*

**Production VMAF gate remains disabled.**
