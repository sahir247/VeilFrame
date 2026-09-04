# VeilFrame Subjective Visual Quality Validation Protocol (Experiment B)
**Document ID:** `VF-RES-SUBJ-PROTO-2026-v1.0`  
**Author:** VeilFrame Core Engineering & Video Quality Research  
**Status:** Approved Research Protocol  
**Date:** September 2026  

---

## 1. Executive Summary & Purpose

This protocol specifies **Experiment B: Human Subjective Visual Quality Evaluation (MOS / ACR-HR)** for VeilFrame. 

### Decoupling Invariant
VeilFrame strictly decouples:
- **Experiment A (Objective Policy Calibration):** Evaluates mathematical compliance with VeilFrame's production visual budget policy ($\text{SSIM} \ge 0.9500 \land \text{PSNR} \ge 30.00\text{ dB}$).
- **Experiment B (Human Subjective Validation):** Evaluates human perceptual acceptability and correlates Mean Opinion Scores (MOS) with VMAF, SSIM, and PSNR.

Under no circumstances may objective policy labels be termed "human perceptual ground truth" without empirical validation under this Experiment B protocol.

---

## 2. Experimental Methodology

The study follows the recommendations of **ITU-R BT.500-14** (*Methodologies for the subjective assessment of the quality of television pictures*) and **ITU-T P.910** (*Subjective video quality assessment methods for multimedia applications*).

### 2.1 Assessment Method
- **Method:** Absolute Category Rating with Hidden Reference (**ACR-HR**).
- **Presentation:** Sequences are presented individually in randomized order without explicit identification of processed versus reference condition.
- **Duration:** 10.0 seconds per stimulus, followed by a 5.0-second scoring interval.
- **Training Session:** Observers participate in a 5-minute pre-trial calibration session displaying representative quality anchors (unimpaired master, borderline processing, severe artifacts) to stabilize scoring criteria.

### 2.2 Rating Scale
Observers rate overall visual quality on the standard 5-point ITU continuous/discrete quality scale:

| Score | Category Descriptor | Perceptual Criteria |
| :---: | :--- | :--- |
| **5** | **Excellent** | Imperceptible difference from reference; pristine fidelity. |
| **4** | **Good** | Perceptible difference, but not annoying; natural video appearance. |
| **3** | **Fair** | Slightly annoying impairment; subtle blurring or edge softening. |
| **2** | **Poor** | Annoying impairment; visible compression artifacts, ringing, or blockiness. |
| **1** | **Bad** | Very annoying impairment; severe degradation, flickering, or face destruction. |

---

## 3. Test Material & Corpus Sampling

The evaluation set comprises **60 video stimuli** derived from VeilFrame's open benchmark corpus:

| Stimulus Type | Count | Composition & Purpose |
| :--- | :---: | :--- |
| **Hidden References (HR)** | 12 | Pristine, uncompressed 1080p/2160p master sequences. Used for normalization and rater bias correction. |
| **Near-Boundary VeilFrame Outputs** | 24 | Outputs with objective metrics near policy boundaries ($0.940 \le \text{SSIM} \le 0.960$, $29.0 \le \text{PSNR} \le 31.0\text{ dB}$). |
| **High-Fidelity Transparent Outputs** | 12 | Mild privacy filtering with near-lossless fidelity ($\text{SSIM} \ge 0.985$, $\text{PSNR} \ge 40.0\text{ dB}$). |
| **Severe Impairment Controls** | 12 | Heavy blur, extreme compression, temporal flicker, and severe downscaling ($\text{SSIM} < 0.850$, $\text{PSNR} < 25.0\text{ dB}$). |

### Domain Coverage
- **1080p SDR:** 24 stimuli (including human faces `crowd_run`, `pedestrian_area`, `dinner`, and CGI `sintel`).
- **1080p HFR ($\ge 50\text{ fps}$):** 16 stimuli (`crowd_run`, `park_joy`).
- **2160p UHD SDR & HFR:** 20 stimuli (`beauty`, `bosphorus`, `honeybee`, `jockey`).

---

## 4. Observer Cohort & Environmental Controls

### 4.1 Observer Demographics
- **Target Panel Size:** $N = 20$ non-expert observers (minimum $N = 15$ retained after post-screening).
- **Vision Screening:** All participants undergo pre-trial Snellen visual acuity screening ($\ge 20/20$ corrected) and Ishihara color blindness testing.
- **Rater Anonymity:** Observers are assigned cryptographically pseudorandom identifiers (`RATER_01` to `RATER_20`). No personal biometric data is stored.

### 4.2 Environmental & Display Specifications
- **Display Device:** 32-inch professional IPS reference monitor (calibrated D65 white point, 100% sRGB / Rec.709 gamut coverage, $\ge 350\text{ cd/m}^2$ peak brightness).
- **Viewing Distance:**
  - $3.0 \times H$ (screen height) for 1080p sequences.
  - $1.5 \times H$ for 2160p (4K UHD) sequences.
- **Ambient Illumination:** Controlled dim room lighting ($\approx 20\text{ lux}$), D65 ambient backlight.

---

## 5. Statistical Analysis & Rater Screening

### 5.1 Outlier Screening (ITU-R BT.500 Annex 2)
An observer's ratings are rejected if either of the following conditions is met:
1. **Linear Correlation:** The Pearson correlation coefficient $r$ between the observer's ratings and the cohort mean falls below $0.75$.
2. **Shift and Kurtosis:** Rater scores fall outside $2 \times \sigma$ of the cohort mean for more than $5\%$ of the presentations, with kurtosis $\beta_2 \in [2, 4]$.

### 5.2 Differential Mean Opinion Score (DMOS)
For each observer $i$ and processed stimulus $j$, the raw score $R_{i,j}$ is normalized against their hidden reference score $R_{i, \text{ref}(j)}$:
$$\text{DMOS}_{i,j} = R_{i,j} - R_{i, \text{ref}(j)} + 5.0$$

The sample Mean Opinion Score ($\text{MOS}_j$) and standard deviation ($s_j$) are computed across all $M$ screened observers:
$$\text{MOS}_j = \frac{1}{M} \sum_{i=1}^M \text{DMOS}_{i,j}$$
$$s_j = \sqrt{\frac{1}{M - 1} \sum_{i=1}^M (\text{DMOS}_{i,j} - \text{MOS}_j)^2}$$

### 5.3 Confidence Intervals
The $95\%$ two-sided confidence interval is computed using Student's $t$-distribution with $M - 1$ degrees of freedom:
$$\text{CI}_{95, j} = \pm t_{0.025, M-1} \cdot \frac{s_j}{\sqrt{M}}$$

### 5.4 Metric Correlation & Prediction Quality
The relationship between objective metrics (VMAF, SSIM, PSNR) and human MOS is evaluated using:
1. **Pearson Linear Correlation Coefficient (PLCC):** Linear association after non-linear 4-parameter logistic mapping.
2. **Spearman Rank Order Correlation Coefficient (SROCC):** Monotonic prediction ranking.
3. **Root Mean Square Error (RMSE):** Prediction residual magnitude.

---

## 6. Acceptance Criteria for Perceptual Claims

Any marketing or technical documentation claiming that VeilFrame's output is **"perceptually indistinguishable"** or **"transparent"** must satisfy:
1. **Perceptual Parity:** $\text{MOS}_{\text{processed}} \ge 4.50$ across all tested sequences.
2. **Statistical Indistinguishability:** Two-sample equivalence testing (TOST) against the hidden reference shows no significant difference within an equivalence margin $\Delta = 0.20$ ($\alpha = 0.05$).
3. **VMAF Agreement:** For domain-qualified VMAF thresholds, the correlation with human MOS must achieve $\text{SROCC} \ge 0.85$ and $\text{PLCC} \ge 0.85$.
