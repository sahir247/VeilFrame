# VeilFrame Subjective Validation Protocol
## Psychophysical Experimental Protocol for Disputed Quality Boundaries

**Document Version:** 1.1.0  
**Status:** Pre-Specified Experimental Protocol (Unexecuted; Zero Human Data Collected to Date)  
**Evidence Classification:** Future Experimental Protocol Design — Does Not Constitute Empirical Perceptual Evidence  
**Target Reference:** ITU-R BT.500-14 / ITU-T P.910 / ITU-R BT.1788  

> [!IMPORTANT]
> **Experimental Protocol Status Notice**: This document specifies a formal psychophysical testing procedure designed for future empirical execution. No human observer trials have been performed to date. The presence of this experimental specification does not constitute perceptual proof or empirical evidence of human acceptance.

---

## 1. Executive Summary & Scientific Motivation

VeilFrame's production visual budget policy enforces strict physical fidelity constraints:
$$\text{SSIM} \ge 0.9500 \quad \text{and} \quad \text{PSNR} \ge 30.00\text{ dB}$$

Empirical calibration studies across both photographic and computer-generated imagery (CGI) revealed profound, statistically reproducible non-separability between this authoritative visual budget and libvmaf v1.0.16 prediction:

1. **False Rejection Collapse (Acceptable Content, Collapsed VMAF):**
   - Clip: `sintel_trailer` with mild spatial softening (`JOINT_Q2_FAIL_SSIM_02_BLUR` and `SSIM_BND_PASS_02_BLUR`).
   - Physical Measurements: $\text{SSIM} = 0.9673–0.9768$ (Pass), $\text{PSNR} = 33.41–35.65\text{ dB}$ (Pass).
   - VMAF Prediction: $\text{VMAF Mean} = 20.59–39.87$ (Catastrophic collapse).
   - Hypothesized Mechanism: Detail and additive impairment features (such as `adm2` and `vif_scale0`) are hypothesized to penalize slight edge-softening on sharp textures, though sub-feature ablation has not been causally isolated.

2. **False Acceptance Blindness (Unacceptable Content, Saturated VMAF):**
   - Clip: `sintel_trailer` with brightness/contrast and chroma DC shifts (`REP_Q3_BRIGHT_01` and `REP_Q4_BRIGHT_CRF_01`).
   - Physical Measurements: $\text{SSIM} = 0.9374–0.9466$ (Fail), $\text{PSNR} = 28.29–29.54\text{ dB}$ (Fail).
   - VMAF Prediction: $\text{VMAF Mean} = 89.73–97.55$ (High quality / Saturated pass).
   - Core Mechanism: VMAF is primarily a contrast-loss and detail-loss model; it exhibits blind spots toward uniform luminance offsets and chroma shifts that degrade physical pixel fidelity.

Because objective metrics conflict fundamentally in these regimes, neither metric can serve as ground truth for the other. This protocol specifies a rigorous, double-blind psychophysical human visual quality experiment to determine whether the human visual system (HVS) aligns with the authoritative SSIM/PSNR policy or with VMAF.

---

## 2. Experimental Methodology

### 2.1 Test Paradigm: SAMVIQ (ITU-R BT.1788 / P.910)
To maximize precision and statistical discriminability around near-boundary thresholds, the study utilizes the **SAMVIQ** (Subjective Assessment of Methodology for Video Quality Assessment) protocol:
- **Random Access & Self-Paced:** Observers can view the uncompressed reference master and multiple test sequences in any order, as many times as necessary.
- **Explicit Hidden Reference:** The pristine reference sequence is included as a hidden test item to anchor the top of the scale ($100$) and compute baseline observer error.
- **Explicit Low-Anchor:** A severely degraded version (CRF 48, heavy blockiness, VMAF < 15, PSNR < 22 dB) is included to anchor the bottom of the scale ($0$).

### 2.2 Stimulus Material & Disputed Test Cells
The experimental design evaluates $5$ distinct stimulus conditions per scene across $4$ content classes (Photographic Surveillance, Photographic Indoor, Fast Motion Sport, CGI Animation):

| Condition ID | Description | Policy Status | Expected VMAF | Primary Research Hypothesis Tested |
| :--- | :--- | :--- | :--- | :--- |
| **REF** | Uncompressed reference | Pristine (100) | 100.0 | Anchor validation & observer baseline |
| **CELL_A** | Standard H.264 Quantization (CRF 22) | Pass ($\text{SSIM} \approx 0.958, \text{PSNR} \approx 37$) | $\approx 92–95$ | Mutual agreement (baseline calibration) |
| **CELL_B** | Mild Spatial Blur / Softening | Pass ($\text{SSIM} \approx 0.967, \text{PSNR} \approx 33.4$) | $\approx 20–40$ | **Disputed:** Does human DMOS accept or reject? |
| **CELL_C** | Luminance / Brightness Offset | Fail ($\text{SSIM} \approx 0.946, \text{PSNR} \approx 28.3$) | $\approx 95–98$ | **Disputed:** Is DC shift objectionable to humans? |
| **CELL_D** | Chroma DC Inversion | Fail ($\text{SSIM} \approx 0.994, \text{PSNR} \approx 27.8$) | $\approx 97–98$ | **Disputed:** Does chromatic distortion degrade DMOS? |
| **ANCHOR** | Severe Degradation (CRF 48) | Severe Fail | $\approx 10–15$ | Low anchor validation |

---

## 3. Laboratory Setup & Viewing Conditions

The laboratory environment strictly complies with **ITU-R BT.500-14 Section 2**:

1. **Display Hardware:**
   - Calibrated 31-inch Reference OLED Master Monitor (Sony BVM-HX310 or equivalent).
   - Resolution: Native $1920 \times 1080$ progressive mapping (1:1 pixel grid, zero scaling).
   - Peak Luminance: $100\text{ cd/m}^2$ (standard Rec.709 SDR reference).
   - Black Level: $< 0.001\text{ cd/m}^2$ (true black).
   - Colorimetry: Rec.709 / D65 white point, 10-bit SDI interface.

2. **Viewing Environment:**
   - Viewing Distance: Exactly $3.0 \times H$ ($3$ screen heights, $\approx 1.16\text{ m}$).
   - Ambient Illumination: Darkened mastering environment, $< 5\text{ lux}$.
   - Background Wall: Neutral grey (approx. $15\%$ reflectance), matte finish.

3. **Observer Cohort:**
   - Sample Size: $N = 30$ naïve observers (minimum $24$ required post-screening).
   - Pre-Screening:
     * Visual Acuity: Snellen chart $\ge 20/20$ (normal or corrected-to-normal).
     * Color Vision: 24-plate Ishihara color blindness test with zero critical errors.
   - Demographics: Balanced distribution across age (18–55) and gender; no video coding specialists.

---

## 4. Test Session Execution & Protocol

1. **Training Phase (15 minutes):**
   - Explanation of the continuous rating scale ($[0, 100]$ marked with semantic qualifiers: *Bad, Poor, Fair, Good, Excellent*).
   - Demonstration of common artifacts (blur, banding, noise, color shifts, blockiness).
   - Practice trials on non-test content (`crowd_run`) until observer scoring stabilizes.

2. **Testing Phase (2 x 25-minute blocks):**
   - Total session duration $\le 55$ minutes to prevent visual fatigue.
   - Randomized presentation order across clips and distortion conditions.
   - Self-paced evaluation with compulsory review of both reference and test stream before score submission.

---

## 5. Statistical Processing & Falsification Criteria

### 5.1 Outlier Rejection (ITU-R BT.500 Annex 2)
Raw observer scores $u_{ijk}$ (observer $i$, clip $j$, condition $k$) are evaluated for statistical consistency:
1. Compute mean $\bar{u}_{jk}$ and standard deviation $S_{jk}$ per condition.
2. Check for kurtosis $\beta_{2jk} = \frac{m_4}{S_{jk}^4}$.
3. Discard observers whose scores diverge beyond $2.0 \times S_{jk}$ on more than $5\%$ of presentations if distribution is normal, or beyond $\sqrt{20} \times S_{jk}$ if non-normal.

### 5.2 Differential Mean Opinion Score (DMOS)
$$\text{DMOS}_{jk} = u_{i, \text{REF}, j} - u_{ijk} + 100$$
Normalized to continuous interval $[0, 100]$ where $100$ indicates identical quality to reference.

### 5.3 Formal Decision Hypotheses & Falsification Rules

1. **Hypothesis $H_1$ (Blur / Spatial Softening):**
   - *Policy Prediction:* Acceptable ($\text{DMOS} \ge 75$).
   - *VMAF Prediction:* Unacceptable ($\text{DMOS} \le 40$, reflecting VMAF ~20).
   - **Decision Rule:** If $\text{DMOS}_{\text{CELL\_B}} \ge 70$ with $95\%$ confidence ($p < 0.001$), **VMAF is falsified** for edge-softened content; the physical SSIM/PSNR policy correctly reflects perceived visual quality.

2. **Hypothesis $H_2$ (Luminance & Chroma Shifts):**
   - *Policy Prediction:* Unacceptable ($\text{DMOS} < 65$).
   - *VMAF Prediction:* Pristine ($\text{DMOS} > 90$, reflecting VMAF ~97).
   - **Decision Rule:** If $\text{DMOS}_{\text{CELL\_C}} \le 65$ or $\text{DMOS}_{\text{CELL\_D}} \le 65$ with $95\%$ confidence ($p < 0.001$), **VMAF is falsified** for global DC shifts; the physical SSIM/PSNR policy correctly prevents artifact leakage.

---

## 6. Implementation Readiness & Artifact Retention

All physical stimuli, scripts, and video pairs are committed and hashed in the VeilFrame calibration infrastructure:
- Reference Masters: `calibration/data/raw/`
- Test Clips: `calibration/data/distorted/`
- Checksums: SHA-256 verified in `measurement_control_audit.json`
- Test Software Harness: `tools/vmaf_reproducibility_audit.py`
