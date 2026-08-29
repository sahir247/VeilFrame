# VeilFrame — Privacy-Focused Media Sanitization with Independent Visual-Fidelity Verification & Cryptographically Signed Audit Manifests

[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-blue.svg)](https://github.com/)
[![Python](https://img.shields.io/badge/Python-3.10%2B-green.svg)](https://python.org/)
[![CLI](https://img.shields.io/badge/CLI-veilframe-informational.svg)](https://github.com/)
[![GUI](https://img.shields.io/badge/GUI-PySide6%20%2F%20Qt-brightgreen.svg)](https://pyside.org/)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![Audit](https://img.shields.io/badge/Audit%20Signatures-Ed25519-purple.svg)](https://ed25519.cr.yp.to/)

**VeilFrame** is an advanced local media sanitization and bounded signal transformation system with independent visual-fidelity verification and cryptographic provenance. Unlike generic "metadata strippers" that only edit container tags, **VeilFrame** applies bounded signal perturbations across multiple domains (spatial trimming, micro-temporal shifts, sensor PRNU dither, chrominance drift, and acoustic ENF notch filtration) within a **5% transformation policy budget**, guarded by an **independent read-only visual fidelity gate** and sealed with **Ed25519 asymmetric cryptographic audit manifests**.

---

## 🏛️ Core Architecture Components

VeilFrame is built around four decoupled, defensible engineering components:

1. **VeilFrame Sanitizer:** Multi-pass elementary stream extractor, container atom zeroer, SEI NAL stripper, and bounded signal perturbation engine (spatial, temporal, frequency PRNU dither, color, and acoustic ENF notch filtration).
2. **VeilFrame Quality Gate:** An independent, read-only auditor that executes after export. Evaluates native-domain stream geometry, decoded YUV plane energies, and canonical visual fidelity ($\text{SSIM} \ge 0.95$, $\text{PSNR} \ge 30.0\text{ dB}$).
3. **VeilFrame Audit Engine:** Ephemeral Ed25519 cryptographic signing engine that binds input/output SHA-256 hashes, exact stream deltas, and statistical percentiles into a canonical RFC 8785 JSON manifest.
4. **VeilFrame Manifest Verifier:** A standalone, zero-dependency external verification tool allowing recipients to independently verify manifest integrity, public key fingerprints, and video bitstream hashes without running the GUI or trusting the transformation engine.

---

## 🆚 Why Traditional "EXIF Erasers" Fail on Video

Most commercial "EXIF removers" (e.g. ExifTool, generic metadata cleaners) only delete top-level container tags. Modern forensic platforms and platform algorithms do not rely solely on EXIF tags to track and identify video files—they analyze deep structural and physical sensor signatures.

| Forensic Vector / Threat | Traditional "EXIF Erasers" | VeilFrame Engine |
|---|:---:|:---:|
| **Top-Level Metadata (GPS, Camera Model, Creation Time)** | ✅ Stripped | ✅ Completely zeroed & sanitized |
| **Bitstream Encoder Leaks (SEI NALs, FFmpeg / x264 banners)** | ❌ Preserved | ✅ Re-muxed & scrubbed with bitexact headers |
| **Sensor PRNU Fingerprints (Photo-Response Non-Uniformity)** | ❌ Intact (Unique device hardware ID) | ⚠️ Experimental mitigation via bounded temporal Gaussian dither ($\sigma \approx 2.5$) |
| **Spatial Perceptual Hashes (pHash / DCT Grid Signatures)** | ❌ Identical match | ⚠️ Bounded perturbation via asymmetric edge cropping & 99.8% Lanczos rescale |
| **Temporal Motion Hashes & Frame Delta Trees** | ❌ Identical match | ⚠️ Bounded perturbation via micro-speed modulation & decimal FPS time-warp |
| **Electrical Network Frequency (ENF) Power-Grid Acoustic Hum** | ❌ Leaks geographic location & recording date | ⚠️ Attenuated via 50Hz, 60Hz, 100Hz, and 120Hz IIR notch filters |
| **ISP Color & Sensor Profile Fingerprints** | ❌ Identical ISP curve match | ⚠️ Bounded drift via subtle non-linear gamma & chrominance drift |
| **Bitstream GOP / Timestamp Cadence Fingerprints** | ❌ Matches recording software cadence | ✅ Standardized to deterministic IDR/GOP cadence & Epoch 0 timestamps |
| **Quality Verification** | ❌ None (Blind export) | ✅ **VeilFrame Independent Read-Only Three-Tier Quality Gate** |
| **Audit Provenance & Tamper Evidence** | ❌ None | ✅ **VeilFrame Asymmetric Ed25519 Signed Audit Manifests** |

---

## 🏗️ System Flow

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
                        │ Tier 1: Policy Score ≤ 5.0% │
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

---

## 🛡️ The 5% Bounded Transformation Policy Budget

VeilFrame operates under an engineering budget ceiling ($S_{\text{policy}} \le 5.0\%$). It distributes perturbations across orthogonal domains to disrupt tracking signals without degrading human viewer experience:

1. **Spatial Geometry ($\Delta_{\text{spatial}} \le 2.0\%$):** Asymmetric 1–2px edge trimming + projective 99.8% Lanczos scaling to eliminate rigid pixel coordinate matches and spatial pHash grids.
2. **Temporal Dynamics ($\Delta_{\text{temporal}} \le 1.0\%$):** Micro-speed shift ($\pm 0.2\%$) and decimal FPS modulation (e.g. $60.0 \rightarrow 59.88\text{ fps}$) to alter temporal motion vectors and frame-delta trees.
3. **Sensor PRNU Dither ($\Delta_{\text{frequency}} \le 1.0\%$):** High-frequency temporal Gaussian dither injected at imperceptible amplitudes to decorrelate physical silicon sensor noise profiles.
4. **Color & Luminance Drift ($\Delta_{\text{lum/chroma}} \le 1.0\%$):** Subtle non-linear gamma ($0.985$) and contrast perturbation to decouple ISP sensor-tuning curves.
5. **Acoustic ENF Notch Filtration:** 50Hz, 60Hz, 100Hz, and 120Hz infinite impulse response (IIR) notch filters to eliminate electrical grid background hums that leak geographic location and recording timestamps.

---

## 🔍 Independent Three-Tier Quality Gate

The transformation engine **cannot declare itself successful**. The independent read-only validator inspects the rendered output:

### 1. Tier 1: Transformation Policy Score ($S_{\text{policy}} \le 5.0\%$)
- Evaluates native stream dimensions, decoded frame luminance ($\Delta \bar{Y}, \text{RMS}_Y$), chroma vector shifts ($\Delta \bar{U}, \Delta \bar{V}$), and 2D Laplacian high-frequency energy.
- Measures **Luma distribution drift** ($D_{TV} = \frac{1}{2}\sum |p_r - p_t| \in [0, 1]$) as a diagnostic distribution metric.

### 2. Tier 2: Rendered Visual Fidelity (Independent Mathematical Constraints)
- **SSIM (Structural Similarity):** $\text{Mean} \ge 0.95$, $\text{P5 (5th Percentile)} \ge 0.90$, $\text{Worst-Case Min} \ge 0.85$.
- **PSNR (Signal-to-Noise Ratio):** $\text{Mean} \ge 30.0\text{ dB}$, $\text{Worst-Case Min} \ge 25.0\text{ dB}$.

### 3. Tier 3: Temporal Integrity
- Evaluates raw streams **before** resampling. Detects missing frames ($0$), duplicate frames ($0$), reordered frames ($0$), cadence deviations ($\le 1.0\%$), and duration deltas ($\le 1.0\%$).

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

## 🚀 Quickstart & Installation

### Requirements
- **OS:** Windows 10 / 11, Linux, or macOS (64-bit)
- **Python:** 3.10 or higher
- **FFmpeg & FFprobe:** Available on system `PATH` or bundled in `veilframe/resources/ffmpeg/`.

### Setup
```powershell
# 1. Clone or navigate to the repository
cd VeilFrame

# 2. Create and activate a virtual environment
python -m venv .venv
.\.venv\Scripts\Activate.ps1

# 3. Install dependencies
pip install -e .
```

### Command Line Interface (CLI)
```powershell
# Sanitize a video using the 5% bounded forensic disruption preset
veilframe sanitize input.mp4 -o output.mp4 --strict

# Run an independent read-only quality gate audit comparing reference vs transformed
veilframe audit reference.mp4 transformed.mp4

# Launch the PySide6 desktop GUI
veilframe gui
```

### Standalone Manifest Verification (Independent Auditor)
Anyone can verify an exported video and its Ed25519 signed manifest using the standalone application-independent script (requiring only standard Python and `cryptography`) without running the GUI or importing engine components:

```powershell
python examples/verify_manifest.py <manifest.json> <manifest.sig> <public_key.pem> --expected-fingerprint SHA256:... --expected-key-id veilframe-signer-01 --video-file <output.mp4>
```

### 🔬 Empirical Forensic Attribution Benchmarks (Research Suite)
Evaluate how VeilFrame transformations impact established forensic detectors (Perceptual Hashers, ENF power-grid hum analyzers, temporal motion gradient trackers, and PRNU sensor noise residue correlation):

```powershell
# Evaluate empirical attribution degradation on a reference/transformed video pair
python tools/run_attribution_benchmarks.py --ref original.mp4 --trans sanitized.mp4 --output-json benchmark_results.json

# Run reproducible multi-camera synthetic corpus benchmark
python tools/run_attribution_benchmarks.py --synthetic --output-json synthetic_benchmark.json
```
For detailed scientific methodology, detector math, and 3-layer metric schemas, see the [Research Suite Documentation](research/README.md).

### Run Test Suite
```powershell
.\.venv\Scripts\python.exe -m unittest discover tests
```

---

## 🎛️ Built-in Presets

| Preset Name | Description |
|---|---|
| **5% Bounded Forensic Disruption** | Flagship subtle preset. Full multi-pass metadata zeroing, 99.8% spatial scaling, 0.2% time-warp, PRNU Gaussian dither, 1% color drift, ENF audio notch filtration, and strict quality gate enforcement ($\text{SSIM} \ge 0.95$, $\text{PSNR} \ge 30\text{ dB}$, $\text{Budget} \le 5.0\%$). |
| **10% Bounded Forensic Disruption** | Deep forensic perturbation preset. Allocates a 10% bounded modification budget across Spatial Geometry (4%), Temporal Cadence (2%), High Frequency Noise / Bayer CFA Mosaic PRNU Dither (2%), Color/Gamma Drift (2%), DCT Hash Perturbation, and deep multi-harmonic Audio ENF notch filtration ($\text{SSIM} \ge 0.90$, $\text{PSNR} \ge 28\text{ dB}$, $\text{Budget} \le 10.0\%$). |
| **Privacy Clean** | Pure metadata and container header sanitization without altering video frames or audio streams. |
| **Privacy Clean (Subtle Perturbation)** | Metadata zeroing with subtle micro-perturbations on auto mode. |
| **Custom** | Complete manual control over every filter, slider, coordinate, and quality gate constraint. |
| **Export** | Standard high-fidelity transcoding pipeline with bitexact header normalization. |

---

## ⚠️ Limitations & Non-Guarantees

This system provides measurable media sanitization, threat-model mitigations, and independent visual fidelity verification. It **does not guarantee absolute removal of every possible provenance, forensic, or attribution signal**.

In particular:
- **No Absolute Anonymity Guarantee:** Codec and container transformations reduce exposure to known forensic vectors, but do not guarantee complete provenance erasure against sophisticated state-level or novel multi-modal forensic methodologies.
- **Perceptual Metrics vs. Semantics:** Mathematical fidelity metrics (SSIM $\ge 0.95$, PSNR $\ge 30\text{ dB}$) evaluate visual and structural consistency under controlled canonical representation; they do not prove semantic equivalence.
- **Sensor Noise Variability:** PRNU mitigation efficacy depends heavily on the specific camera sensor architecture, resolution, ISO lighting conditions, and scene texture.
- **ENF Acoustic Variations:** Electrical Network Frequency notch filtration effectiveness depends on ambient acoustic signal-to-noise ratios, microphone sensitivity, and regional grid stability.
- **Perceptual Hashes:** Spatial perceptual hashes (pHash/dHash) are statistical heuristic matchers, not cryptographic identifiers.
- **Application Policy Scope:** The 5% policy score ($S_{\text{policy}}$) is an application-defined engineering budget ceiling, not a universal measurement of percentage visual modification.
- **Cryptographic Scope:** Ed25519 digital signatures prove cryptographic integrity and provenance relative to the trusted public key; they do not prove that the underlying measurement suite is scientifically exhaustive.

---

## 📄 License & Security

- Licensed under the [MIT License](LICENSE).
- Security policy & vulnerability reporting: See [SECURITY.md](SECURITY.md).
