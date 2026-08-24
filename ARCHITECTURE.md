# System Architecture: VeilFrame

## 📖 Executive Overview

**VeilFrame** is a privacy-focused media sanitization and forensic anti-fingerprinting system with independent visual-fidelity verification and cryptographically signed audit manifests.

### What It Does
1. **Eliminates Identifiers:** Scrubs container metadata, GPS coordinates, camera hardware serial numbers, creation timestamps, and bitstream encoder headers via the **VeilFrame Sanitizer**.
2. **Disrupts Forensic Fingerprints:** Introduces bounded, visually imperceptible perturbations across spatial, temporal, frequency, color, and acoustic domains to break sensor PRNU noise profiles, perceptual hash trees, motion vector trajectories, and electric grid clock hums.
3. **Guarantees Visual Fidelity:** Audits the processed output through an independent, read-only **VeilFrame Quality Gate** requiring SSIM $\ge 0.95$ and PSNR $\ge 30.0\text{ dB}$.
4. **Produces Cryptographic Proof:** Generates an independently verifiable **Ed25519 digitally signed audit manifest** binding the input and output cryptographic hashes, native stream dimensions, and statistical fidelity metrics via the **VeilFrame Audit Engine**.
5. **Enables External Verification:** Supports third-party verification via the zero-dependency **VeilFrame Manifest Verifier**.

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
- **Risk:** Directly reveals who recorded the video, exact GPS coordinates, and the exact date and time.

### 2. Bitstream & Encoder Leaks
- **Vector:** Supplementary Enhancement Information (SEI) NAL units, x264/x265 configuration strings, encoder build strings, and vendor-specific bitstream markers.
- **Risk:** Identifies the exact capture app, OS version, or editing software used.

### 3. Sensor Photo-Response Non-Uniformity (PRNU)
- **Vector:** Microscopic physical variations in individual CMOS/CCD pixels create a unique, deterministic noise pattern across every frame recorded by that specific camera sensor.
- **Risk:** Acts as a physical "ballistics fingerprint" linking separate videos to the exact physical camera or smartphone hardware.

### 4. Perceptual Hashes & Motion Trajectories
- **Vector:** Spatial DCT grids (pHash, aHash, dHash) and temporal motion vector trees calculated across adjacent frames.
- **Risk:** Enables automated indexing, content-matching algorithms, and automated cross-platform tracking.

### 5. Electric Network Frequency (ENF) Acoustic Grid Signatures
- **Vector:** Microphones capture subtle 50Hz (Europe/Asia) or 60Hz (Americas) electromagnetic and acoustic hums emitted by nearby electrical infrastructure and power lines. The subtle frequency variations of the power grid over time form a unique clock.
- **Risk:** Forensically determines the geographic region and exact minute a recording occurred.

---

## ⚙️ How It Works: Multi-Pass Architecture

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
                        │ • SHA-256 Input Hash            │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 2: Bounded Transformation │
                        │ • 99.8% Asymmetric Spatial Crop │
                        │ • Decimal FPS Micro-Time Warp   │
                        │ • High-Frequency PRNU Dither    │
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
                        │ • SHA-256 Output Hash           │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 4: VeilFrame Quality Gate │
                        │ • Native-domain stream audit    │
                        │ • Decoded YUV energy metrics    │
                        │ • Pre-resampling temporal check │
                        │ • Canonical SSIM & PSNR metrics │
                        │ • Three-Tier Verdict            │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Pass 5: VeilFrame Audit Engine │
                        │ • Canonical JSON payload        │
                        │ • SHA-256 manifest digest       │
                        │ • Ed25519 digital signature     │
                        │ • Pinned public key fingerprint │
                        └─────────────────────────────────┘
```

---

## 📐 The 5% Bounded Transformation Policy Budget

The transformation engine operates under a strict **5% engineering policy budget ceiling** ($S_{\text{policy}} \le 5.0\%$). This score represents the combined perturbation allocated across orthogonal signal domains:

$$S_{\text{policy}} = \Delta_{\text{spatial}} + \Delta_{\text{temporal}} + \Delta_{\text{luma}} + \Delta_{\text{chroma}} + \Delta_{\text{freq}} \le 5.0\%$$

| Sub-Policy | Formula | Ceiling | Purpose |
|---|---|:---:|---|
| **Spatial Geometry** | $\max\left(\frac{\|W_t - W_r\|}{W_r}, \frac{\|H_t - H_r\|}{H_r}\right) \times 100$ | **$\le 2.0\%$** | Breaks fixed pixel grids and spatial pHash matches. |
| **Temporal Dynamics** | $\max\left(\frac{\|T_t - T_r\|}{T_r}, \frac{\|\text{FPS}_t - \text{FPS}_r\|}{\text{FPS}_r}\right) \times 100$ | **$\le 1.0\%$** | Breaks frame delta trees and motion trajectories. |
| **Luminance Drift** | $\Delta \bar{Y} \times 100 = \frac{\|\bar{Y}_t - \bar{Y}_r\|}{255.0} \times 100$ | **$\le 1.0\%$** | Shifts global luminance curves. |
| **Chrominance Drift** | $\Delta_{\text{chroma}} \times 100 = \sqrt{(\Delta \bar{U})^2 + (\Delta \bar{V})^2} \times 100$ | **$\le 1.0\%$** | Neutralizes camera ISP sensor-tuning profiles. |
| **Frequency Dither** | $\min(1.0, \Delta E_{\text{HF, rel}} \times 2.0)$ | **$\le 1.0\%$** | Injects dynamic Gaussian dither to decorrelate PRNU. |

> **Note on Policy Score:** The policy score is an engineering budget ceiling across applied transformation dimensions. It is not a literal percentage of changed pixels. Visual fidelity ($\text{SSIM}, \text{PSNR}$) and temporal integrity are evaluated independently.

---

## 🔬 Independent Read-Only Three-Tier Quality Gate

To prevent the transformation engine from declaring false success, the **VeilFrame Quality Gate** performs a three-tier audit:

```
                            THREE-TIER QUALITY AUDIT
                                       │
         ┌─────────────────────────────┼─────────────────────────────┐
         ▼                             ▼                             ▼
  [ TIER 1: POLICY ]          [ TIER 2: FIDELITY ]         [ TIER 3: TEMPORAL ]
  • Spatial <= 2.0%           • SSIM Mean >= 0.95          • Missing Frames = 0
  • Temporal <= 1.0%          • SSIM P5 >= 0.90            • Duplicate Frames = 0
  • Luma Delta <= 1.0%        • SSIM Worst >= 0.85         • Reordered Frames = 0
  • Chroma Delta <= 1.0%      • PSNR Mean >= 30.0 dB       • Max Drift <= 0.10s
  • Frequency <= 1.0%         • PSNR Worst >= 25.0 dB      • Cadence Dev <= 1.0%
  • Aggregate <= 5.0%         • D_TV Luma Distribution     • Duration Delta <= 1.0%
         │                             │                             │
         └─────────────────────────────┼─────────────────────────────┘
                                       ▼
                              [ FINAL VERDICT ]
                               PASS or REJECT
```

### 1. Two-Level Validation Domain Split
- **Native-Domain Metrics:** Inspects raw stream dimensions, duration, FPS, and pixel formats directly from elementary bitstreams without downscaling or normalizing away geometric modifications.
- **Canonical-Domain Metrics:** Applies controlled spatial normalization (`scale={w}:{h}:flags=lanczos,setsar=1,format=yuv420p,split`) purely for evaluating structural similarity (SSIM) and pixel fidelity (PSNR).

### 2. Decoded YUV Plane Energy Metrics
- **Luma Distribution Drift ($D_{TV}$):** Total Variation normalized histogram distance ($0 \le D_{TV} \le 1$):
  $$D_{TV} = \frac{1}{2}\sum_{i=0}^{255} |p_{r, i} - p_{t, i}|$$
- **Luma RMS Error:** $\text{RMS}_Y = \frac{\sqrt{\frac{1}{N}\sum (Y_t - Y_r)^2}}{255.0}$
- **Spectral High-Frequency Laplacian Energy:**
  $$\text{abs\_delta\_hf} = |E_t - E_r|, \quad \text{rel\_delta\_hf} = \frac{|E_t - E_r|}{E_r + 1.0}$$

---

## 🔏 Cryptographic Audit Provenance & Verification

```
     ┌──────────────────┐
     │  manifest.json   │  (Canonical JSON payload)
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

Every sanitization run creates an independently verifiable audit package:
1. `manifest.json`: Canonical RFC 8785 JSON recording input/output SHA-256 hashes, exact stream dimensions, decoded energy metrics, statistical distribution percentiles, and environment metadata.
2. `manifest.sha256`: SHA-256 hash of the canonical JSON bytes.
3. `manifest.sig`: Ed25519 digital signature of the canonical JSON bytes.
4. `public_key.pem`: Public key distributed alongside the export.
5. **Pinned Public Key Fingerprint:** The raw 32-byte Ed25519 public key hash (`SHA256:...`) is pinned in the manifest and report to prevent public key replacement attacks.

---

## 🌍 Real-World Privacy Impact

| Target User Group | Threat Scenario | VeilFrame Mitigations |
|---|---|---|
| **Journalists & Whistleblowers** | Submitting video evidence without revealing the physical smartphone hardware ID, location, or source identity. | Mitigates PRNU sensor noise correlation, zeroes EXIF/GPS, attenuates ENF mains electrical hums, and normalizes bitstream timestamps. |
| **Human Rights Activists** | Uploading protest or documentation footage subject to automated platform cross-indexing and device fingerprinting. | Perturbs spatial pHash and temporal motion vectors within a 5% budget to reduce exposure to automated dragnet matching. |
| **Everyday Consumers** | Sharing family or personal videos on social platforms without leaking camera serial numbers, home GPS, or capture tools. | Completely zeroes container atoms, standardizes bitstream headers, and validates visual fidelity with SSIM/PSNR. |
| **Digital Forensics Auditors** | Verifying that a video was legitimately sanitized according to strict privacy constraints. | Inspects the tamper-evident Ed25519 signed audit manifest and independent three-tier quality report. |

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
