# System Architecture: VeilFrame

## 📖 Executive Overview

**VeilFrame** is a privacy-preserving media sanitization and forensic anti-fingerprinting system engineered with independent visual-fidelity verification and cryptographically signed audit manifests.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       VEILFRAME SYSTEM CORE                                      │
├─────────────────────────┬─────────────────────────┬──────────────────────────────────────────────┤
│    SANITIZATION &       │   INDEPENDENT 3-TIER    │             CRYPTOGRAPHIC AUDIT              │
│    TRANSFORMATION       │      QUALITY GATE       │              & PROVENANCE                    │
│                         │                         │                                              │
│ • Container Atom Scrub  │ • Native-Domain Check   │ • RFC 8785 JSON Canonicalization (JCS)       │
│ • SEI Header Stripping  │ • Decoded YUV Energy    │ • Ed25519 Digital Signature & Keypair        │
│ • Bayer CFA PRNU Engine │ • SSIM (>=0.95) & PSNR  │ • Pinned Public Key Fingerprints             │
│ • 2D DCT Block Dither   │ • PTS Monotonicity Audit│ • Standalone Zero-Dependency Verifier        │
│ • ENF Acoustic Notch    │ • VMAF Evidence Logging │ • Immutable SHA-256 Bitstream Binding        │
└─────────────────────────┴─────────────────────────┴──────────────────────────────────────────────┘
```

### Core Invariant
> **"Providers measure. VeilFrame decides."**  
> Transformation engines and metric measurement providers never control gate thresholds or declare their own success. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## 🎯 Threat Model & Forensic Attack Vectors

```
                               ┌────────────────────────────────┐
                               │   RAW VIDEO RECORDING LEAKS    │
                               └───────────────┬────────────────┘
                                               │
             ┌───────────────────┬─────────────┴──────┬───────────────────┐
             ▼                   ▼                    ▼                   ▼
    ┌─────────────────┐ ┌─────────────────┐  ┌─────────────────┐ ┌─────────────────┐
    │ Container EXIF  │ │ Sensor PRNU     │  │ Perceptual      │ │ Mains ENF Hum   │
    │ GPS, Device ID, │ │ Silicon photo-  │  │ Spatial pHash,  │ │ 50Hz / 60Hz     │
    │ Creation Times  │ │ response noise  │  │ Motion Vectors  │ │ Acoustic Grid   │
    └────────┬────────┘ └────────┬────────┘  └────────┬────────┘ └────────┬────────┘
             │                   │                    │                   │
             └───────────────────┼────────────────────┴───────────────────┘
                                 ▼
                     [ AUTOMATED SURVEILLANCE & ]
                     [ PLATFORM IDENTIFICATION  ]
```

### 1. Metadata & Container Tracking
- **Vector:** MP4/MOV container atoms (`udta`, `moov`, `meta`, `QuickTime Keys`), GPS coordinates, camera serial numbers, creation timestamps, and software tags.
- **Threat Mitigation:** Full container sanitization strips all non-essential user-data atoms, zeroes creation/modification timestamps to Unix Epoch 0 (1970-01-01T00:00:00Z), and clears container handler identifiers.

### 2. Bitstream & Encoder Leaks
- **Vector:** Supplementary Enhancement Information (SEI) NAL units, x264/x265 configuration strings, encoder build strings, and vendor-specific bitstream markers.
- **Threat Mitigation:** Bitstream filtering removes unreferenced SEI user-data packets and normalizes SPS/PPS headers without corrupting decode syntax.

### 3. Sensor Photo-Response Non-Uniformity (PRNU)
- **Vector:** Microscopic physical variations in individual CMOS/CCD pixels create a unique, deterministic noise pattern acting as a silicon ballistics fingerprint.
- **Threat Mitigation:** Physical Bayer Color Filter Array (CFA) noise modeling injects sub-pixel synthetic sensor perturbations through non-linear saturation clamping and reconstructive demosaicing.

### 4. Perceptual Hashes & Motion Trajectories
- **Vector:** Spatial DCT grids (pHash, aHash, dHash) and temporal motion vector trees calculated across adjacent frames for automated indexing and dragnet cross-matching.
- **Threat Mitigation:** 2D DCT transform-domain perturbation subtly shifts AC coefficient medians across $8 \times 8$ blocks, altering spatial hashes while strictly preserving structural similarity.

### 5. Electric Network Frequency (ENF) Acoustic Grid Signatures
- **Vector:** Microphones capture subtle 50Hz (Europe/Asia) or 60Hz (Americas) electromagnetic and acoustic hums emitted by power lines. The subtle frequency drift of the power grid over time forms a unique temporal/geographic clock.
- **Threat Mitigation:** Multi-order IIR notch filters with high quality factors ($Q = 30$) attenuate primary grid frequencies (50Hz / 60Hz) and secondary harmonics (100Hz / 120Hz).

---

## ⚙️ Multi-Pass Pipeline Architecture

```
                             ┌──────────────────────────┐
                             │       INPUT VIDEO        │
                             └────────────┬─────────────┘
                                          │
                                          ▼
                        ┌─────────────────────────────────┐
                        │  Pass 1: Pre-Sanitization       │
                        │ • Demux elementary streams      │
                        │ • Drop container atoms & SEI    │
                        │ • Record SHA-256 Input Digest   │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 2: Bounded Transformation │
                        │ • Bayer CFA PRNU Sensor Engine  │
                        │ • 2D DCT Block Perturbation     │
                        │ • Decimal FPS Micro-Time Warp   │
                        │ • Non-Linear Gamma Color Drift  │
                        │ • 50/60/100/120Hz ENF Filtering │
                        │ • Deterministic IDR/GOP Cadence │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 3: Post-Sanitization      │
                        │ • Bitexact container packaging  │
                        │ • Epoch 0 timestamp zeroing     │
                        │ • Record SHA-256 Output Digest  │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 4: VeilFrame Quality Gate │
                        │ • Native-domain stream audit    │
                        │ • Decoded YUV energy metrics    │
                        │ • Pre-resampling PTS audit      │
                        │ • Canonical SSIM & PSNR metrics │
                        │ • VMAF evidence capture         │
                        │ • Three-Tier Verdict            │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 5: Cryptographic Audit    │
                        │ • RFC 8785 Canonical JCS JSON   │
                        │ • Ed25519 digital signature     │
                        │ • Pinned public key fingerprint │
                        │ • Export audit manifest bundle  │
                        └─────────────────────────────────┘
```

---

## 🔬 Deep Transformation Engines

### 1. Physical Bayer CFA Mosaic PRNU Sensor Engine (`veilframe/core/cfa_prnu.py`)

Physical digital cameras place a Color Filter Array (CFA) over the silicon photodiode grid. To disrupt camera identification without creating artificial compression artifacts:

1. **Bayer Sensor Grid Simulation:**  
   Converts RGB frames into a single-channel Bayer mosaic plane $I_{\text{bayer}}(x, y)$ for any standard layout (`RGGB`, `BGGR`, `GRBG`, `GBRG`):
   $$I_{\text{bayer}}(x, y) = \begin{cases} R(x, y) & \text{if } (x, y) \in \text{Red pixels} \\ G(x, y) & \text{if } (x, y) \in \text{Green pixels} \\ B(x, y) & \text{if } (x, y) \in \text{Blue pixels} \end{cases}$$

2. **Channel-Specific Sensor Noise:**  
   Generates independent Gaussian noise fields matching physical photon quantum efficiency differences ($\sigma_R = 0.015, \sigma_G = 0.008, \sigma_B = 0.012$).

3. **Non-Linear Saturation Clamping:**  
   Suppresses noise in deep shadows ($I \to 0$) and saturated highlights ($I \to 255$):
   $$M(I) = \sin\left(\pi \cdot \frac{I}{255}\right)^\gamma$$
   $$I_{\text{injected}} = \operatorname{clip}\left(I_{\text{bayer}} + \beta \cdot I_{\text{bayer}} \cdot K \cdot M(I_{\text{bayer}}), 0, 255\right)$$

4. **Reconstructive Demosaicing:**  
   Demosaicing the modified Bayer plane naturally interpolates the synthetic PRNU across adjacent color channels, mimicking in-camera ISP processing:
   - **Default:** High-performance vectorized pure NumPy bilinear demosaicing.
   - **Acceleration (Optional):** Compiled C++ OpenCV color space demosaicing fast path when `opencv-python` is installed.

---

### 2. 2D DCT Transform-Domain Hash Perturbation (`veilframe/core/hash_perturbation.py`)

Perceptual hashing algorithms (pHash, dHash) compute 2D Discrete Cosine Transforms (DCT) over downsampled luminance blocks and construct binary hash keys by comparing AC coefficients against their median:

1. **$8 \times 8$ Block DCT Decomposition:**  
   Decomposes the image luminance plane into non-overlapping $8 \times 8$ frequency blocks.
2. **Median Decision-Boundary Shifting:**  
   Identifies AC transform coefficients near the median boundary and applies controlled micro-shifts ($\pm \delta$) to flip specific hash bits while maintaining perceptual invariance.
3. **Strict $L_\infty$ Bound & SSIM Guard:**  
   Clamps all spatial modifications to $\|I_{\text{new}} - I_{\text{orig}}\|_\infty \le 0.02$, ensuring imperceptible pixel changes.

---

## 📐 Transformation Policy Budgets

VeilFrame offers three mathematically calibrated transformation presets:

| Profile | Aggregate Policy Ceiling ($S_{\text{policy}}$) | Spatial Ceiling ($\Delta_{\text{spatial}}$) | Temporal Ceiling ($\Delta_{\text{temporal}}$) | SSIM Mean Constraint | PSNR Mean Constraint | Primary Use Case |
|---|:---:|:---:|:---:|:---:|:---:|---|
| **Privacy Clean (Lossless)** | $0.0\%$ | $0.0\%$ | $0.0\%$ | $\ge 0.99$ | $\ge 45\text{ dB}$ | Metadata/EXIF stripping with lossless stream copy. |
| **5% Bounded Forensic Disruption** | $\le 5.0\%$ | $\le 2.0\%$ | $\le 1.0\%$ | $\ge 0.95$ | $\ge 30\text{ dB}$ | Standard balance of visual fidelity and forensic decorrelation. |
| **10% Bounded Forensic Disruption** | $\le 10.0\%$ | $\le 4.0\%$ | $\le 2.0\%$ | $\ge 0.90$ | $\ge 26\text{ dB}$ | Deep forensic disruption with Bayer CFA PRNU and 2D DCT perturbation. |

---

## 🔬 Independent Read-Only Three-Tier Quality Gate

Pass/fail decisions are governed by a three-tier audit architecture:

```
                            THREE-TIER QUALITY AUDIT
                                       │
         ┌─────────────────────────────┼─────────────────────────────┐
         ▼                             ▼                             ▼
  [ TIER 1: POLICY ]          [ TIER 2: FIDELITY ]         [ TIER 3: TEMPORAL ]
  • Spatial <= Budget         • SSIM Mean >= Target        • Missing Frames = 0
  • Temporal <= Budget        • SSIM P5 >= Target          • Duplicate Frames = 0
  • Luma Delta <= Budget      • SSIM Worst >= Target       • Reordered Frames = 0
  • Chroma Delta <= Budget    • PSNR Mean >= Target        • Max Drift <= 0.10s
  • Frequency <= Budget       • PSNR Worst >= Target       • Cadence Dev <= 1.0%
  • Aggregate <= Policy Max   • D_TV Luma Distribution     • Duration Delta <= Budget
         │                             │                             │
         └─────────────────────────────┼─────────────────────────────┘
                                       ▼
                              [ FINAL VERDICT ]
                               PASS or REJECT
```

### 1. Two-Level Validation Domain Split
- **Native-Domain Metrics:** Measures stream dimensions, duration, FPS, and pixel format directly from raw elementary bitstreams.
- **Canonical-Domain Metrics:** Evaluates structural similarity (SSIM) and peak signal-to-noise ratio (PSNR) under controlled spatial normalization (`scale={w}:{h}:flags=lanczos,setsar=1,format=yuv420p`).

### 2. Decoded YUV Plane Energy Metrics
- **Luma Distribution Drift ($D_{TV}$):** Total Variation normalized histogram distance ($0 \le D_{TV} \le 1$):
  $$D_{TV} = \frac{1}{2}\sum_{i=0}^{255} |p_{r, i} - p_{t, i}|$$
- **High-Frequency Spectral Energy (2D Laplacian Variance):**
  $$E = \operatorname{Var}\left(\nabla^2 I\right) = \frac{1}{N}\sum_{x,y} \left(\nabla^2 I(x, y) - \mu_{\nabla^2 I}\right)^2$$
  $$\Delta E_{\text{abs}} = |E_t - E_r|, \quad \Delta E_{\text{rel}} = \frac{|E_t - E_r|}{E_r + 1.0}$$

### 3. Pre-Resampling Packet Presentation Timestamp (PTS) Monotonicity
- Verifies packet presentation timestamps for strict monotonicity ($t_{i+1} \ge t_i$).
- Detects dropped frames, packet cadence stutter, and reordered frame sequences in streams with non-trivial decode-vs-presentation order (B-frames).

### 4. VMAF Evaluation Status & Provider Contract
- **Adapter Integration:** Integrated Netflix VMAF v1.0.16 via `veilframe/quality/adapters/vmaf_adapter.py`.
- **Empirical Calibration Verdict (`VF-CAL-VMAF-2026-09`):** `NO_FEASIBLE_THRESHOLD`. An exhaustive decision-boundary search across Domain-1 calibration assets proved that no single global scalar threshold $\min(\text{VMAF}_{\text{mean}}, \text{VMAF}_{p5}) \in [70, 100]$ satisfies both $\text{FAR} < 2.0\%$ and $\text{FRR} < 5.0\%$.
- **Operational Status:** `VisualBudgetPolicy.vmaf_gate_enabled = False` strictly preserved. VMAF serves as measurement/audit evidence only; primary fidelity enforcement is governed by SSIM, PSNR, and plane energy metrics.

---

## 🔏 Cryptographic Audit Provenance & Dual-Mode Trust Architecture

```
     ┌──────────────────┐
     │  manifest.json   │  (RFC 8785 Canonical JCS JSON)
     └────────┬─────────┘
              │
              ├──────────────────────────────────┐
              ▼                                  ▼
     ┌──────────────────┐               ┌──────────────────┐
     │     SHA-256      │               │ Ed25519 Sign     │
     │  manifest.sha256 │               │ manifest.sig     │
     └──────────────────┘               └────────┬─────────┘
                                                 │
                                                 ▼
                                        ┌──────────────────┐
                                        │  public_key.pem  │
                                        │  + Pinned Hash   │
                                        └──────────────────┘
```

1. **RFC 8785 JSON Canonicalization Scheme (JCS):** Produces byte-for-byte deterministic JSON hashing across all platforms, architectures, and runtimes.
2. **Dual Signing Modes:**
   - **Ephemeral Mode (`mode: "ephemeral"`):** Fresh Ed25519 keypair generated per export.
   - **Persistent Signer Mode (`mode: "persistent"`):** Signs using a persistent hardware or organizational key identity (`key_id: "veilframe-production-01"`).
3. **Standalone Verifier (`examples/verify_manifest.py`):** Self-contained, zero-dependency auditor requiring only standard Python and `cryptography`.

---

## 💻 Developer CLI & TUI Architecture

VeilFrame provides a unified developer terminal interface styled after modern agentic CLIs:

```
veilframe/
├── cli.py               # Main CLI dispatcher & 8 subcommands (sanitize, inspect, audit, verify, presets, doctor, benchmark, gui)
└── cli_ui.py            # Zero-dependency terminal formatting engine (cards, ANSI colors, spinners, tables, trees, interactive TUI)
```

### CLI Subcommands Overview:
- **`veilframe sanitize <input> -o <output>`**: Multi-pass sanitization with media preview card, real-time progress bar, and audit tree.
- **`veilframe inspect <video>`**: Deep inspection of container atoms, elementary video/audio streams, tracking tags, and GPS coordinates.
- **`veilframe audit <ref> <trans>`**: Independent 3-tier QualityGate visual fidelity audit.
- **`veilframe verify <manifest.json>`**: Standalone Ed25519 signature and SHA-256 bitstream verification.
- **`veilframe presets`**: Interactive inspection of transformation presets and budget allocations.
- **`veilframe doctor`**: System diagnostics for OS, Python, FFmpeg, FFprobe, libvmaf, Cryptography, NumPy, and hardware encoders.
- **`veilframe interactive`**: Interactive developer dashboard with menu navigation.
- **`veilframe benchmark`**: Research attribution benchmark detector suite runner.

---

## 📊 Decoupled Research Attribution Benchmark Layer

The research attribution benchmark suite (`research/attribution_benchmarks/`) provides an empirical evaluation framework:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   RESEARCH ATTRIBUTION BENCHMARK LAYER                 │
├────────────────────────────────────────────────────────────────────────┤
│ • Layer 1 (Physical/Signal): PRNU PCE / NCC, ENF Welch PSD Attenuation │
│ • Layer 2 (Detector Decisions): pHash / dHash Hamming Distance Margins │
│ • Layer 3 (Multi-Camera ROC): True Positive Rate, FPR, and ROC AUC     │
└────────────────────────────────────────────────────────────────────────┘
```

1. **PRNU Cross-Correlation Detector:** Measures Peak-to-Correlation Energy (PCE) and Normalized Cross-Correlation (NCC).
2. **Perceptual Hash Detector:** Computes spatial DCT Hamming distances across frame sequences.
3. **ENF Acoustic Detector:** Evaluates Welch Power Spectral Density attenuation across electrical grid fundamental and harmonic bands.
4. **Synthetic Corpus Generator:** Synthesizes multi-camera video streams with parameterized PRNU noise, color calibration, and lens distortion for offline evaluation.

---

## 📄 Summary of System Invariants

1. **Zero Silent Modification:** Every byte modification is constrained by an explicit, self-describing mathematical policy budget.
2. **Quality Separation:** The transformation pipeline never evaluates its own visual fidelity.
3. **Cryptographic Binding:** Bitstreams are bound to their evaluation metrics via SHA-256 digests and Ed25519 digital signatures.
4. **Platform Independence:** Deterministic RFC 8785 canonicalization ensures identical verification results across Linux, macOS, and Windows.
