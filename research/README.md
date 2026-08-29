# VeilFrame Attribution Benchmark Suite (Research Layer)

The **VeilFrame Research Layer** is a neutral scientific evaluation framework designed to empirically measure how media transformations affect forensic signal properties and detector matching behavior.

---

## 🔬 Core Design Philosophy

VeilFrame strictly decouples **production sanitization & quality verification** from **forensic attribution research**:

1. **Production Engine (`veilframe/`):**
   - Strips container metadata, SEI NAL units, and bitstream markers.
   - Applies bounded signal perturbations within a strict $\le 5\%$ visual policy budget.
   - Enforces read-only independent quality gating ($\text{SSIM} \ge 0.95$, $\text{PSNR} \ge 30.0\text{ dB}$, VMAF).
   - Generates cryptographically signed Ed25519 audit manifests.

2. **Research Benchmark Layer (`research/attribution_benchmarks/`):**
   - Evaluates empirical signal degradation against established forensic extractors.
   - Answers: *Does the transformation measurably alter physical signal properties, and does that alter detector matching performance under a defined corpus?*

---

## 🏛️ 3-Layer Metrics Architecture

Every benchmark detector implements a 3-layer output schema:

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Signal Metrics                                     │
│ Physical & mathematical properties (Hamming distance, BER, │
│ Spectral Attenuation ΔdB, Frame-Delta Pearson ρ, PCE / NCC) │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Detector Metrics                                   │
│ Detector algorithm score, threshold τ, match decision status│
│ (MATCH / NO_MATCH, HUM_DETECTED / HUM_SUPPRESSED)           │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Attribution Metrics                                │
│ Multi-sample classification & statistical performance       │
│ (Pair Classification, Multi-Camera TPR / FPR, ROC / AUC)    │
└─────────────────────────────────────────────────────────────┘
```

---

## 📦 Benchmark Detectors

### 1. Perceptual Hashing (`detectors/perceptual_hash.py`)
- **Algorithms:**
  - **pHash:** $32 \times 32$ 2D Discrete Cosine Transform (DCT) low-frequency $8 \times 8$ median hash.
  - **dHash:** $9 \times 8$ horizontal luminance gradient comparison hash.
  - **aHash:** $8 \times 8$ average luminance threshold hash.
  - **wHash:** 2D Haar wavelet LL subband approximation hash.
- **Layer 1 Metrics:** Mean/min/max Hamming distance (bits), Bit Error Rate ($\text{BER} = \frac{D_H}{64}$).
- **Layer 2 Metrics:** Match score vs decision threshold $\tau$ (default $\tau = 10$).
- **Layer 3 Metrics:** Pair match classification (`TRUE_POSITIVE` / `FALSE_NEGATIVE`).

### 2. Electrical Network Frequency (ENF) (`detectors/enf.py`)
- **Methodology:**
  - Audio downsampling: $f_s = 1000\text{ Hz}$.
  - Spectral estimator: Welch periodogram with 4-term Blackman-Harris windowing ($N = 4096$, $\Delta f = 0.244\text{ Hz}$, 50% overlap).
  - Target grid harmonics: $50\text{ Hz}$, $60\text{ Hz}$, $100\text{ Hz}$, $120\text{ Hz}$.
- **Layer 1 Metrics:** Peak spectral power (dB), attenuation $\Delta\text{dB} = P_{\text{trans}} - P_{\text{ref}}$, SNR reduction.
- **Layer 2 Metrics:** Peak detection score vs threshold ($\text{SNR} \ge 10.0\text{ dB}$).
- **Layer 3 Metrics:** Residual hum classification (`HUM_DETECTED` / `HUM_SUPPRESSED`).

### 3. Temporal Motion & Frame-Delta Sequence (`detectors/motion.py`)
- **Methodology:**
  - Frame-to-frame difference: $\Delta I_t = I_{t+1} - I_t$.
  - Analyzes temporal gradient sequence trajectory correlation.
- **Layer 1 Metrics:** Mean Pearson correlation $\rho(\Delta I_{\text{ref}}, \Delta I_{\text{trans}})$, temporal energy ratio.
- **Layer 2 Metrics:** Trajectory matching score vs threshold $\tau$ (default $\tau = 0.85$).
- **Layer 3 Metrics:** Sequence trajectory match status.

### 4. Sensor PRNU Fingerprint (`detectors/prnu.py`)
- **Methodology:**
  - High-pass noise residual extraction: $W = I - F(I)$ using 2D adaptive spatial filtering.
  - Camera sensor fingerprint estimation: $\hat{K} = \frac{1}{M} \sum W_i$.
  - 2D Circular Cross-Correlation, Normalized Cross-Correlation (NCC), and Peak-to-Correlation Energy ($\text{PCE} = \frac{\text{Peak}^2}{\text{MSE}_{\text{outside}}}$).
- **Layer 1 Metrics:** Baseline self-PCE, Transformed vs Reference PCE, PCE attenuation ratio.
- **Layer 2 Metrics:** Matching score vs decision threshold $\tau$ (default $\tau = 60.0$).
- **Layer 3 Metrics:** Multi-camera attribution ROC curve, True Positive Rate (TPR), False Positive Rate (FPR), and Area Under the ROC Curve (AUC).

---

## 🚀 Running the Benchmarks

### Benchmark a Single Video Pair
```bash
uv run python tools/run_attribution_benchmarks.py \
  --ref original.mp4 \
  --trans sanitized.mp4 \
  --output-json results.json
```

### Run Self-Contained Multi-Camera Synthetic Corpus Evaluation
```bash
uv run python tools/run_attribution_benchmarks.py \
  --synthetic \
  --output-json synthetic_results.json
```

---

## 🔒 Reproducibility & Environment Invariants

Every benchmark report records:
- Implementation version and git commit.
- Pinned SHA-256 digests of input, output, and dataset manifests.
- Host platform, Python, NumPy, and FFmpeg compiler build details.
- Exact sampling configuration and pseudo-random seed.
