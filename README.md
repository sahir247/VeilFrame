# VeilFrame — Privacy-Preserving Media Sanitization, Bounded Forensic Disruption & Cryptographically Signed Audit Manifests

[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-blue.svg)](https://github.com/)
[![Python](https://img.shields.io/badge/Python-3.10%2B-green.svg)](https://python.org/)
[![CLI](https://img.shields.io/badge/CLI-veilframe-informational.svg)](https://github.com/)
[![GUI](https://img.shields.io/badge/GUI-PySide6%20%2F%20Qt-brightgreen.svg)](https://pyside.org/)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![Audit Signatures](https://img.shields.io/badge/Audit%20Signatures-Ed25519-purple.svg)](https://ed25519.cr.yp.to/)
[![RFC Compliance](https://img.shields.io/badge/RFC%208785-JSON%20Canonicalization-blueviolet.svg)](https://datatracker.ietf.org/doc/html/rfc8785)

**VeilFrame** is an advanced local media sanitization and bounded forensic signal transformation system with independent visual-fidelity verification and cryptographic provenance. Unlike standard metadata strippers that only modify container headers, **VeilFrame** applies bounded, orthogonal signal perturbations across spatial geometry, temporal cadence, physical sensor noise (Bayer CFA PRNU), transform-domain perceptual hashes (2D DCT), ISP chrominance drift, and acoustic Electrical Network Frequency (ENF) hums within strict **5% or 10% transformation policy budgets**, guarded by an **independent read-only three-tier visual fidelity gate** and sealed with **Ed25519 asymmetric cryptographic audit manifests**.

---

## 🏛️ Core Architecture Components

```
                             ┌──────────────────────────┐
                             │       INPUT VIDEO        │
                             └────────────┬─────────────┘
                                          │
                                          ▼
                        ┌─────────────────────────────────┐
                        │   1. VEILFRAME SANITIZER        │
                        │ • ffprobe stream extraction     │
                        │ • container atom zeroing        │
                        │ • SHA-256 Input Checksum        │
                        └────────────────┬────────────────┘
                                         │
                      ┌──────────────────┴──────────────────┐
                      │                                     │
                      ▼                                     ▼
            ┌──────────────────┐                  ┌──────────────────┐
            │ REFERENCE PATH   │                  │ TRANSFORM PATH   │
            │ (Ground-Truth)   │                  │ Multi-Pass       │
            │                  │                  │ Transformation   │
            └────────┬─────────┘                  └────────┬─────────┘
                     │                                     │
                     │                                     ▼
                     │                         ┌──────────────────────┐
                     │                         │ Re-mux / re-encode   │
                     │                         │ (Pass 2 + Pass 3)    │
                     │                         └──────────┬───────────┘
                     │                                    │
                     └────────────────┬───────────────────┘
                                      ▼
                        ┌─────────────────────────────┐
                        │  2. VEILFRAME QUALITY GATE  │
                        │      (Strict Read-Only)     │
                        └─────────────┬───────────────┘
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            │                         │                         │
            ▼                         ▼                         ▼
┌─────────────────────────┐ ┌───────────────────┐ ┌─────────────────────────┐
│ 1. NATIVE-DOMAIN STREAM │ │ 2. DECODED-FRAME  │ │ 3. CANONICAL FIDELITY   │
│    & FORMAT METRICS     │ │    ENERGY METRICS │ │    & TEMPORAL METRICS   │
│                         │ │ (Decoded Y, U, V) │ │                         │
│ • Exact Resolution Δ    │ │ • Luminance Drift │ │ • Spatial Normalization │
│ • Aspect Ratio (DAR/SAR)│ │   (Mean, RMS ΔY,  │ │ • Per-Frame SSIM & PSNR │
│ • FPS & Timebase Δ      │ │    Luma D_TV Dist)│ │ • Statistical Tails:    │
│ • Pixel Format & Range  │ │ • Chroma Drift    │ │   Mean, Median, P1, P5, │
│ • Duration Delta        │ │   (Mean ΔU, ΔV)   │ │   P95, Worst-Case Min   │
│                         │ │ • High-Frequency  │ │ • Temporal Integrity:   │
│                         │ │   Band Energy (E) │ │   Missing, Duplicate,   │
│                         │ │   via 2D Lap/FFT  │ │   Reordered, Drift Max  │
└───────────┬─────────────┘ └─────────┬─────────┘ └────────────┬────────────┘
            │                         │                        │
            └─────────────────────────┼────────────────────────┘
                                      ▼
                        ┌─────────────────────────────┐
                        │    THREE-TIER EVALUATION    │
                        │                             │
                        │ Tier 1: Policy Score Budget │
                        │ Tier 2: Rendered Fidelity   │
                        │ Tier 3: Temporal Integrity  │
                        └──────────────┬──────────────┘
                                       │
                                ┌──────┴──────┐
                                ▼             ▼
                             [ PASS ]      [ FAIL ]
                                │             │
                                ▼             ▼
                        ┌──────────────┐ ┌──────────────┐
                        │ OUTPUT VIDEO │ │ QUARANTINE / │
                        │      +       │ │ DELETE       │
                        │  3. AUDIT    │ │      +       │
                        │    ENGINE    │ │ AUDIT REPORT │
                        │ (Ed25519)    │ └──────────────┘
                        └──────────────┘
```

VeilFrame is partitioned into four decoupled engineering layers:

1. **VeilFrame Sanitizer:** Multi-pass elementary stream extractor, ISO-BMFF box zeroer, SEI NAL stripper, and bounded multi-domain transformation engine.
2. **VeilFrame Quality Gate:** An independent, read-only post-export auditor. Evaluates native-domain stream geometry, decoded frame plane energy distributions ($D_{TV}$), and canonical visual fidelity ($\text{SSIM}$, $\text{PSNR}$, optional $\text{VMAF}$).
3. **VeilFrame Audit Engine:** Cryptographic signing engine generating deterministic RFC 8785 JSON manifests bound with Ed25519 digital signatures and SHA-256 bitstream checksums.
4. **VeilFrame Manifest Verifier:** A zero-dependency external verification utility allowing downstream recipients to verify manifest authenticity, signature correctness, and video bitstream hashes without trusting the transformation pipeline.

---

## 🔬 Mathematical Foundations of Signal Transformations

VeilFrame models video tracking and device fingerprinting as physical, statistical, and transform-domain signals. Bounded perturbations are applied to disrupt attribution while strictly preserving viewer visual fidelity.

```
                   ┌────────────────────────────────────────────────────────┐
                   │               PHYSICAL SENSOR PLANE                    │
                   │  Bayer CFA Mosaicing: I_raw = M_CFA(I_RGB)             │
                   │  Channel Variance: σ_R ≈ 0.015, σ_G ≈ 0.008, σ_B ≈ 0.012│
                   │  Saturation Clamping: M(I) = sin(π · I / 255)^γ        │
                   │  Reconstruction: I_injected = Demosaic(I_raw + Δ_PRNU) │
                   └──────────────────────────┬─────────────────────────────┘
                                              │
                                              ▼
                   ┌────────────────────────────────────────────────────────┐
                   │             TRANSFORM DOMAIN (2D DCT)                  │
                   │  Block 2D DCT: X(u,v) = DCT2D(Y_block)                 │
                   │  Decision Flipping: X_AC ≈ median ± Δ_shift            │
                   │  Bounded IDCT: Y_pert = IDCT2D(X_pert), ||Δ||_∞ ≤ ε    │
                   └──────────────────────────┬─────────────────────────────┘
                                              │
                                              ▼
                   ┌────────────────────────────────────────────────────────┐
                   │               ACOUSTIC FREQUENCY DOMAIN                │
                   │  Multi-Harmonic IIR Notch: H(z) at 50/60/100/120 Hz    │
                   │  Micro-Pitch Shift: 0.985x - 0.990x Phase Vocoder      │
                   └────────────────────────────────────────────────────────┘
```

### 1. Photo-Response Non-Uniformity (PRNU) & Bayer CFA Mosaic Injection

Physical camera sensors possess unique microscopic silicon silicon variations known as Photo-Response Non-Uniformity ($K$). The classical sensor output model is:

$$I_k = I_k^{(0)} \cdot (1 + K) + \Theta_k$$

where $I_k^{(0)}$ is the noise-free optical scene, $K$ is the zero-mean multiplicative PRNU fingerprint matrix, and $\Theta_k$ is additive sensor read noise.

#### A. Bayer CFA Mosaicing
Standard camera hardware places a Color Filter Array (CFA) over the sensor in recurring sub-pixel layouts (e.g. $\text{RGGB}$, $\text{BGGR}$, $\text{GRBG}$, $\text{GBRG}$). VeilFrame converts RGB frames to single-channel Bayer mosaics before noise injection:

$$\text{RGGB: } I_{\text{raw}}(x, y) = \begin{cases} R(x, y) & \text{if } x \equiv 0 \pmod 2,\, y \equiv 0 \pmod 2 \\ G(x, y) & \text{if } x \not\equiv y \pmod 2 \\ B(x, y) & \text{if } x \equiv 1 \pmod 2,\, y \equiv 1 \pmod 2 \end{cases}$$

#### B. Sub-Pixel Channel Variance Matching
Synthetic PRNU noise maps are generated with per-channel standard deviations calibrated to physical photon responsivity:

$$K(x, y) \sim \begin{cases} \mathcal{N}(0, \sigma_R^2) & \text{on Red pixels } (\sigma_R \approx 0.015) \\ \mathcal{N}(0, \sigma_G^2) & \text{on Green pixels } (\sigma_G \approx 0.008) \\ \mathcal{N}(0, \sigma_B^2) & \text{on Blue pixels } (\sigma_B \approx 0.012) \end{cases}$$

#### C. Non-Linear Saturation & Shadow Clamping
Physical sensor saturation prevents PRNU variance in clipped deep shadows ($I=0$) and overexposed highlights ($I=255$). VeilFrame applies a non-linear modulation envelope:

$$M(I) = \left[\max\left(0, \sin\left(\pi \cdot \frac{I}{255}\right)\right)\right]^\gamma \quad (\gamma \approx 0.6)$$

$$I_{\text{injected}} = \text{clip}\left(I_{\text{raw}} + \beta \cdot I_{\text{raw}} \cdot K \cdot M(I_{\text{raw}}),\, 0,\, 255\right)$$

#### D. Demosaicing Reconstruction
The perturbed mosaic plane is demosaiced via bilinear and edge-directed gradient interpolation, naturally propagating PRNU noise cross-covariance through color channels without tripping forensic sub-grid variance detectors.

---

### 2. Transform-Domain (2D DCT) Perceptual Hash Micro-Perturbation

Perceptual hashing algorithms (pHash, dHash) compress $32 \times 32$ spatial blocks into 64-bit fingerprints using the 2D Discrete Cosine Transform (DCT):

$$X(u, v) = \alpha(u)\alpha(v) \sum_{x=0}^{N-1} \sum_{y=0}^{N-1} f(x, y) \cos\left[\frac{\pi (2x+1)u}{2N}\right] \cos\left[\frac{\pi (2y+1)v}{2N}\right]$$

where $\alpha(0) = \sqrt{1/N}$ and $\alpha(u) = \sqrt{2/N}$ for $u > 0$.

```
Spatial Block (32x32) ──► 2D DCT ──► 8x8 Low-Freq AC Matrix ──► Find Median Threshold
                                                                         │
Reconstructed Spatial ◄── 2D IDCT ◄── Perturb Boundary Coefficients ◄──┘
```

1. **Median Decision Boundary Identification:** Low-frequency AC coefficients $\{X(u,v) \mid 1 \le u, v < 8\}$ are sorted relative to their block median $\mu_{1/2}$.
2. **Boundary Flipping:** Coefficients closest to $\mu_{1/2}$ govern binary hash bits ($b_i = 1 \text{ if } X_i > \mu_{1/2} \text{ else } 0$). VeilFrame perturbs target boundary coefficients across the median:

$$X'(u_i, v_i) = \mu_{1/2} \pm \left(|X(u_i, v_i) - \mu_{1/2}| + \delta_{\text{shift}}\right)$$

3. **Strict $L_\infty$ Bounded Reconstruction:** The Inverse 2D DCT (IDCT) is computed and bounded:

$$\|f_{\text{perturbed}}(x, y) - f_{\text{original}}(x, y)\|_\infty \le \epsilon \quad (\epsilon \approx 0.02, \text{ SSIM} \ge 0.95)$$

---

### 3. Electrical Network Frequency (ENF) Acoustic Notch Filtration

Audio recorded near electrical infrastructure captures mains power hum ($50\text{ Hz}$ Europe/Asia, $60\text{ Hz}$ Americas) and its harmonics ($100\text{ Hz}, 120\text{ Hz}$), which forensic analysts use to determine geographic recording regions and exact recording timestamps.

VeilFrame applies second-order Infinite Impulse Response (IIR) digital notch filters centered at all mains frequencies:

$$H(z) = \frac{1 - 2\cos(\omega_0) z^{-1} + z^{-2}}{1 - 2 r \cos(\omega_0) z^{-1} + r^2 z^{-2}} \quad \text{where } \omega_0 = 2\pi \frac{f_0}{f_s},\, r = 1 - \frac{\text{BW}\cdot\pi}{f_s}$$

Combined with micro-pitch time-stretch phase modulation ($\text{ratio} \in [0.985, 0.990]$), the ENF signal-to-noise ratio is attenuated below physical forensic detection thresholds ($\Delta\text{dB} \ge 15\text{ dB}$).

---

## 🔍 Independent Three-Tier Quality Gate

The transformation engine **cannot declare itself successful**. The independent read-only validator inspects the rendered output across three distinct tiers:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                           THREE-TIER QUALITY GATE                         │
├───────────────────────────────────────────────────────────────────────────┤
│ Tier 1: Transformation Policy Score Budget                                │
│   • S_policy = Δ_spatial + Δ_temporal + w_luma·ΔY + w_chroma·ΔC + Δ_freq  │
│   • Evaluated against 5.0% or 10.0% Aggregate Ceiling                     │
│   • Luma Total Variation Distance: D_TV(P_ref, P_trans)                   │
├───────────────────────────────────────────────────────────────────────────┤
│ Tier 2: Rendered Visual Fidelity & Perceptual Quality                     │
│   • SSIM: Mean ≥ 0.95 (5%) / 0.90 (10%), P5 Tail ≥ 0.90 / 0.85            │
│   • PSNR: Mean ≥ 30.0 dB (5%) / 28.0 dB (10%), Worst-Case ≥ 25 / 22 dB   │
│   • Optional Tier 2b: VMAF Quality Gate                                   │
├───────────────────────────────────────────────────────────────────────────┤
│ Tier 3: Pre-Resampling Temporal Stream Integrity                          │
│   • Packet PTS Monotonicity: ΔPTS_i = PTS_{i+1} - PTS_i > 0               │
│   • 0 Missing Frames, 0 Duplicate Timestamps, 0 Reordered Packets         │
└───────────────────────────────────────────────────────────────────────────┘
```

### 1. Tier 1: Transformation Policy Score ($S_{\text{policy}}$)

The aggregate transformation score combines weighted physical deltas:

$$S_{\text{policy}} = \Delta_{\text{spatial}} + \Delta_{\text{temporal}} + w_{\text{luma}} \cdot \Delta \bar{Y} + w_{\text{chroma}} \cdot \Delta \bar{C} + \min\left(C_{\text{freq}},\, w_{\text{freq}} \cdot \frac{|\sigma_{\text{Lap, trans}}^2 - \sigma_{\text{Lap, ref}}^2|}{\sigma_{\text{Lap, ref}}^2}\right)$$

- **Total Variation Normalized Histogram Divergence ($D_{TV}$):**

$$D_{TV}(P_{\text{ref}}, P_{\text{trans}}) = \frac{1}{2} \sum_{i=0}^{255} |P_{\text{ref}}(i) - P_{\text{trans}}(i)| \in [0, 1]$$

- **High-Frequency Spectral Energy (2D Laplacian Variance):**

$$\sigma_{\text{Lap}}^2 = \text{Var}\left( \nabla^2 I(x, y) \right) = \text{Var}\left( I(x+1,y) + I(x-1,y) + I(x,y+1) + I(x,y-1) - 4I(x,y) \right)$$

---

### 2. Tier 2: Rendered Visual Fidelity & Statistical Tail Analysis

Rendered fidelity is computed over canonical representations ($1280 \times 720$ YUV 4:2:0):

#### Structural Similarity Index Metric (SSIM)

$$\text{SSIM}(x, y) = \frac{(2\mu_x\mu_y + C_1)(2\sigma_{xy} + C_2)}{(\mu_x^2 + \mu_y^2 + C_1)(\sigma_x^2 + \sigma_y^2 + C_2)}$$

where $\mu$ is local mean, $\sigma^2$ is local variance, $\sigma_{xy}$ is cross-covariance, $C_1 = (0.01 \cdot 255)^2$, and $C_2 = (0.03 \cdot 255)^2$.

#### Peak Signal-to-Noise Ratio (PSNR)

$$\text{PSNR} = 10 \cdot \log_{10}\left(\frac{255^2}{\text{MSE}}\right) = 20 \cdot \log_{10}\left(\frac{255}{\sqrt{\frac{1}{M N} \sum_{i=0}^{M-1} \sum_{j=0}^{N-1} [I_{\text{ref}}(i, j) - I_{\text{trans}}(i, j)]^2}}\right)$$

#### Tail Distribution Metrics
To prevent severe single-frame anomalies from being masked by aggregate means, the Quality Gate evaluates complete percentile distributions:

$$\text{Metrics: } \{\text{Mean}, \text{Median}, \text{P1 (1st Percentile)}, \text{P5 (5th Percentile)}, \text{P95}, \text{Worst-Case Minimum}, \sigma\}$$

---

### 3. Tier 3: Pre-Resampling Temporal Stream Integrity

Evaluates raw container packet presentation timestamps ($\text{PTS}$) before frame decoding:

1. **PTS Monotonicity:** $\Delta \text{PTS}_i = \text{PTS}_{i+1} - \text{PTS}_i > 0$ for all chronological packets.
2. **Missing & Duplicate Frames:** Verification that packet count matches continuous presentation timestamps ($\text{Missing} = 0$, $\text{Duplicates} = 0$).
3. **Cadence Jitter:** Standard deviation of inter-packet duration delta ($\Delta \text{cadence} \le 1.0\%$).

### 4. Perceptual Fidelity Metric Integration & Calibration Status (VMAF v1.0.16)

VeilFrame includes native support for **Netflix VMAF v1.0.16** (`veilframe/quality/adapters/vmaf_adapter.py`):
- **Provider Architecture:** Adheres strictly to the architectural invariant: *"Providers measure; QualityGate decides."*
- **Empirical Calibration Study (`VF-CAL-VMAF-2026-09`):** A rigorous empirical evaluation across 144 multimedia items and 112 Domain-1 fixture pairs using an exact decision-boundary search proved that no single global scalar operating point $\min(\text{VMAF}_{\text{mean}}, \text{VMAF}_{p5}) \in [70, 100]$ satisfies both $\text{FAR} < 2.0\%$ and $\text{FRR} < 5.0\%$.
- **Operational Gate Policy:** `VisualBudgetPolicy.vmaf_gate_enabled = False` is strictly maintained. VMAF serves strictly as informational evidence recorded in audit manifests, while primary release gating is governed by SSIM, PSNR, and decoded plane energy metrics.

---

## 🔐 Cryptographically Sealed Ed25519 Audit Manifest

Every processed export generates a tamper-evident audit manifest bundle:

- `manifest.json`: Canonical RFC 8785 JSON payload containing input/output cryptographic hashes, exact stream dimensions, uniform timeline sampling indices, and statistical percentiles.
- `manifest.sha256`: SHA-256 digest of the canonical JSON bytes.
- `manifest.sig`: Ed25519 digital signature of the canonical JSON payload.
- `public_key.pem`: Ed25519 public key.
- **Dual-Mode Trust Architecture:**
  - **Ephemeral Mode:** Generates a fresh keypair per audit for individual export verification.
  - **Persistent Signer Mode:** Uses long-term signer identity keys with persistent key IDs and fingerprint pinning.
- **Root-of-Trust Pinning:** Includes the public key's raw 32-byte SHA-256 fingerprint (`public_key_fingerprint_raw`) and SubjectPublicKeyInfo hash (`public_key_fingerprint_pem`).

---

## 🎛️ Built-in Presets Comparison

| Feature / Policy Dimension | 5% Bounded Forensic Disruption | 10% Bounded Forensic Disruption | Privacy Clean |
|---|:---:|:---:|:---:|
| **Aggregate Policy Ceiling ($S_{\text{policy}}$)** | $\le 5.0\%$ | $\le 10.0\%$ | $0.0\%$ |
| **Spatial Geometry Ceiling ($\Delta_{\text{spatial}}$)** | $\le 2.0\%$ (99.8% Lanczos) | $\le 4.0\%$ (99.5% Lanczos, 2-4px crop) | $0.0\%$ (No crop/scale) |
| **Temporal Dynamics Ceiling ($\Delta_{\text{temporal}}$)** | $\le 1.0\%$ ($\pm 0.2\%$ speed) | $\le 2.0\%$ ($\pm 0.5\%$ speed, fractional FPS) | $0.0\%$ (Preserved) |
| **Luminance Drift Ceiling ($\Delta_{\text{luma}}$)** | $\le 1.0\%$ (0.5% luma, 1.5% gamma) | $\le 2.0\%$ (0.8% luma, 2.5% gamma) | $0.0\%$ (Original) |
| **Chrominance Drift Ceiling ($\Delta_{\text{chroma}}$)** | $\le 1.0\%$ (2.0% sat) | $\le 2.0\%$ (3.0% sat) | $0.0\%$ (Original) |
| **Frequency Noise Ceiling ($\Delta_{\text{freq}}$)** | $\le 1.0\%$ (Gaussian Noise Strength 8) | $\le 2.0\%$ (Bayer CFA PRNU Strength 16) | $0.0\%$ (Disabled) |
| **DCT Hash Perturbation** | Off | ✅ Enabled ($\text{SSIM} \ge 0.95$) | Off |
| **Audio ENF Notch Filtration** | 50/60/100/120 Hz, 0.99x pitch | 50/60/100/120 Hz, 0.985x pitch | Off |
| **Quality Gate: Mean SSIM Constraint** | $\ge 0.9500$ | $\ge 0.9000$ | $\ge 0.9500$ |
| **Quality Gate: Tail P5 SSIM Constraint** | $\ge 0.9000$ | $\ge 0.8500$ | $\ge 0.9000$ |
| **Quality Gate: Worst-Case SSIM** | $\ge 0.8500$ | $\ge 0.8000$ | $\ge 0.8500$ |
| **Quality Gate: Mean PSNR Constraint** | $\ge 30.0\text{ dB}$ | $\ge 28.0\text{ dB}$ | $\ge 30.0\text{ dB}$ |
| **Quality Gate: Worst-Frame PSNR** | $\ge 25.0\text{ dB}$ | $\ge 22.0\text{ dB}$ | $\ge 25.0\text{ dB}$ |
| **Metadata & SEI NAL Sanitization** | ✅ Full scrub | ✅ Full scrub | ✅ Full scrub |

---

## 🔬 Empirical Forensic Attribution Benchmarks (Research Suite)

The decoupled `research/attribution_benchmarks/` framework evaluates transformation impact against established forensic detectors under a neutral 3-layer architecture:

```
┌───────────────────────────────────────────────────────────────────────────┐
│ Layer 1: Physical / Signal Metrics (Hamming distance, ΔdB, PCE, NCC, ρ)   │
├───────────────────────────────────────────────────────────────────────────┤
│ Layer 2: Detector Decision Metrics (Detector score s, Threshold τ, Match) │
├───────────────────────────────────────────────────────────────────────────┤
│ Layer 3: Attribution Metrics (True Positive Rate, False Positive Rate, AUC)│
└───────────────────────────────────────────────────────────────────────────┘
```

### Mathematical Formulations in Research Benchmarks:

#### 1. Peak-to-Correlation Energy (PCE) & 2D Normalized Cross-Correlation (NCC)

$$\text{NCC}(r, c) = \frac{\sum_{x, y} (W_1(x, y) - \bar{W}_1)(W_2(x+r, y+c) - \bar{W}_2)}{\sqrt{\sum_{x, y} (W_1(x, y) - \bar{W}_1)^2 \sum_{x, y} (W_2(x+r, y+c) - \bar{W}_2)^2}}$$

$$\text{PCE} = \frac{\text{NCC}(r_{\text{peak}}, c_{\text{peak}})^2}{\frac{1}{|U|} \sum_{(r, c) \in U} \text{NCC}(r, c)^2}$$

where $U$ represents cross-correlation plane coordinates excluding an $11 \times 11$ window around the peak.

#### 2. Exact Monotonic ROC/AUC Integration

Receiver Operating Characteristic (ROC) curves are evaluated over sorted candidate scores:

$$\text{AUC} = \int_0^1 \text{TPR}(\text{FPR}) \, d(\text{FPR}) = \sum_{i=1}^N \frac{\text{TPR}_i + \text{TPR}_{i-1}}{2} \cdot (\text{FPR}_i - \text{FPR}_{i-1})$$

#### 3. Welch Power Spectral Density (PSD) with Blackman-Harris Windowing

$$P_{xx}(f) = \frac{1}{K L U} \sum_{k=1}^K \left| \sum_{n=0}^{L-1} x_k[n] w[n] e^{-j 2\pi f n / f_s} \right|^2$$

where $w[n]$ is a 4-term Blackman-Harris window ($a_0=0.35875, a_1=0.48829, a_2=0.14128, a_3=0.01168$) and $U = \frac{1}{L}\sum_{n=0}^{L-1} w[n]^2$.

---

## 🚀 Universal Quickstart & Installation

### 1. System Requirements & Prerequisites
- **Python:** 3.10, 3.11, or 3.12 (64-bit)
- **FFmpeg & FFprobe:** Installed and accessible on system `PATH` (or placed in `veilframe/resources/ffmpeg/`).

#### Installing FFmpeg by Operating System:

```bash
# Linux (Ubuntu / Debian)
sudo apt-get update && sudo apt-get install -y ffmpeg libegl1 libgl1

# Linux (Fedora / RHEL)
sudo dnf install ffmpeg

# Linux (Arch Linux)
sudo pacman -S ffmpeg

# macOS (Homebrew)
brew install ffmpeg

# Windows (Winget)
winget install Gyan.FFmpeg

# Windows (Chocolatey)
choco install ffmpeg
```

---

### 2. Environment Setup

```bash
# 1. Clone the repository
git clone https://github.com/sahir247/VeilFrame.git
cd VeilFrame

# 2. Create and activate a virtual environment
# Linux / macOS:
python3 -m venv .venv
source .venv/bin/activate

# Windows (PowerShell):
python -m venv .venv
.\.venv\Scripts\Activate.ps1

# Windows (Command Prompt):
python -m venv .venv
.\.venv\Scripts\activate.bat

# 3. Install VeilFrame in editable mode
pip install --upgrade pip
pip install -e .
```

---

### 3. Command Line Interface (CLI)

VeilFrame includes a comprehensive developer CLI with ANSI cards, live progress spinners, forensic inspection, diagnostics, and independent cryptographic verification.

```
╭──────────────────────────────────────────────────────────────────────────────╮
  ◈ VEILFRAME v1.1.0 │ 3-Tier QualityGate │ Ed25519 Signed
  Privacy-Preserving Media Sanitization & Cryptographic Audit
╰──────────────────────────────────────────────────────────────────────────────╯
```

#### Common Commands:

```bash
# 1. Sanitize video with the standard 5% bounded disruption preset & strict quality gate
veilframe sanitize input.mp4 -o output.mp4 --preset "5% Bounded Forensic Disruption" --strict

# 2. Sanitize video with deep 10% forensic disruption (Bayer CFA PRNU + 2D DCT perturbation)
veilframe sanitize input.mp4 -o output.mp4 --preset "10% Bounded Forensic Disruption" --strict

# 3. Inspect container atoms, elementary video/audio streams, tracking tags, and GPS
veilframe inspect video.mp4

# 4. Run independent read-only 3-tier QualityGate visual fidelity audit
veilframe audit reference.mp4 sanitized.mp4

# 5. Cryptographically verify an Ed25519 signed audit manifest & video bitstream hash
veilframe verify path/to/manifest.json

# 6. Explore built-in transformation presets and mathematical budget ceilings
veilframe presets

# 7. Run system environment diagnostic & hardware acceleration health check
veilframe doctor

# 8. Launch full-screen interactive CLI GUI / TUI dashboard
veilframe tui
# or simply run: veilframe

# 9. Launch PySide6 desktop GUI
veilframe gui
```

#### JSON Pipeline Mode:
All CLI commands support `--json` for direct integration into automated CI/CD and forensic evaluation workflows:
```bash
veilframe inspect video.mp4 --json
veilframe sanitize input.mp4 -o output.mp4 --json
veilframe audit ref.mp4 trans.mp4 --json
veilframe doctor --json
```

---

### 4. Programmatic Python SDK Usage

VeilFrame can be embedded directly into Python applications and automated pipelines:

```python
from pathlib import Path
from veilframe.core.pipeline import run_pipeline
from veilframe.presets.manager import PresetManager

# 1. Load preset profile (e.g. 5% or 10% Bounded Forensic Disruption)
pm = PresetManager()
preset = pm.get_preset("5% Bounded Forensic Disruption")
settings = pm.to_processing_settings(preset)
settings.quality_gate.enforce_strict = True

# 2. Execute multi-pass sanitization with independent fidelity audit
report = run_pipeline(
    src_path=Path("input.mp4"),
    dst_path=Path("output.mp4"),
    settings=settings,
    progress_callback=lambda pct, msg: print(f"[{pct:3.0f}%] {msg}"),
)

print(f"Sanitization Passed: {report.all_passed}")
if report.quality_report:
    print(f"QualityGate Verdict: {report.quality_report.three_tier_verdict.overall_verdict}")
    print(f"Ed25519 Fingerprint: {report.quality_report.public_key_fingerprint}")
```

---

### 5. Standalone Manifest Verification (Zero-Dependency Auditor)

Recipients and third-party auditors can independently verify an exported video and its Ed25519 signed manifest using the standalone script (requiring only Python standard library and `cryptography`) without running the GUI or importing internal transformation components:

```bash
python examples/verify_manifest.py <manifest.json> <manifest.sig> <public_key.pem> \
  --expected-fingerprint SHA256:... \
  --video-file <output.mp4>
```

---

### 6. Research Attribution Benchmark Suite

Evaluate PRNU cross-correlation (PCE/NCC), perceptual hash Hamming distances (pHash/dHash), and ENF spectral attenuation:

```bash
# Run reference vs. transformed empirical benchmark
python tools/run_attribution_benchmarks.py --ref original.mp4 --trans sanitized.mp4 --output-json benchmark_results.json

# Run reproducible multi-camera synthetic corpus benchmark
python tools/run_attribution_benchmarks.py --synthetic --output-json synthetic_corpus.json
```

---

### 7. Run Test Suite

```bash
python -m unittest discover tests -v
```

---

## ⚠️ Limitations & Non-Guarantees

This system provides measurable media sanitization, threat-model mitigations, and independent visual fidelity verification. It **does not guarantee absolute removal of every possible provenance, forensic, or attribution signal**.

In particular:
- **No Absolute Anonymity Guarantee:** Codec and container transformations reduce exposure to known forensic vectors, but do not guarantee complete provenance erasure against sophisticated state-level or novel multi-modal forensic methodologies.
- **Perceptual Metrics vs. Semantics:** Mathematical fidelity metrics (SSIM $\ge 0.95$, PSNR $\ge 30\text{ dB}$) evaluate visual and structural consistency under controlled canonical representation; they do not prove semantic equivalence.
- **Sensor Noise Variability:** PRNU mitigation efficacy depends heavily on the specific camera sensor architecture, resolution, ISO lighting conditions, and scene texture.
- **ENF Acoustic Variations:** Electrical Network Frequency notch filtration effectiveness depends on ambient acoustic signal-to-noise ratios, microphone sensitivity, and regional grid stability.
- **Perceptual Hashes:** Spatial perceptual hashes (pHash/dHash) are statistical heuristic matchers, not cryptographic identifiers.
- **Application Policy Scope:** The 5% or 10% policy score ($S_{\text{policy}}$) is an application-defined engineering budget ceiling, not a universal measurement of percentage visual modification.
- **Cryptographic Scope:** Ed25519 digital signatures prove cryptographic integrity and provenance relative to the trusted public key; they do not prove that the underlying measurement suite is scientifically exhaustive.

---

## 📄 License & Security

- Licensed under the [MIT License](LICENSE).
- Security policy & vulnerability reporting: See [SECURITY.md](SECURITY.md).
