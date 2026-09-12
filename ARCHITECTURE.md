# System Architecture: VeilFrame

## Executive Overview

**VeilFrame** is an auditable, privacy-preserving multimedia sanitization and forensic anti-fingerprinting system engineered with independent visual-fidelity verification and cryptographically signed audit manifests.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       VEILFRAME SYSTEM CORE                                      │
├─────────────────────────┬─────────────────────────┬──────────────────────────────────────────────┤
│    SANITIZATION &       │   INDEPENDENT MULTI-    │             CRYPTOGRAPHIC AUDIT              │
│    TRANSFORMATION       │     CONTRACT GATES      │              & PROVENANCE                    │
│                         │                         │                                              │
│ • Video: Container Scrub│ • Video: 3-Tier Gate    │ • RFC 8785 JSON Canonicalization (JCS)       │
│ • Video: SEI Stripper   │   - Policy Budget       │ • Ed25519 Digital Signature & Keypair        │
│ • Video: CFA PRNU       │   - Visual Fidelity     │ • Pinned Public Key Fingerprints             │
│ • Video: 2D DCT Dither  │   - Temporal Integrity  │ • Standalone Zero-Dependency Verifier        │
│ • Video: ENF Acoustic   │ • Image: 5 Contracts    │ • Immutable SHA-256 Bitstream Binding        │
│ • Image: Layer A Meta   │   - Privacy, Geometry,  │ • Full Reproducibility Proofs                │
│ • Image: Layer B Repr   │     Fidelity, Integrity,│                                              │
│ • Image: Layer C Pixels │     Completeness        │                                              │
└─────────────────────────┴─────────────────────────┴──────────────────────────────────────────────┘
```

### Core Invariant
> **"Providers measure. VeilFrame decides."**  
> Transformation engines, red-team detectors, and metric measurement providers never control gate thresholds or declare their own success. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Threat Model & Forensic Attack Vectors

```
                               ┌────────────────────────────────┐
                               │   RAW MULTIMEDIA DATA LEAKS    │
                               └───────────────┬────────────────┘
                                               │
             ┌───────────────────┬─────────────┴──────┬───────────────────┐
             ▼                   ▼                    ▼                   ▼
    ┌─────────────────┐ ┌─────────────────┐  ┌─────────────────┐ ┌─────────────────┐
    │ Metadata & EXIF │ │ Sensor & Silicon│  │ Semantic PII &  │ │ Mains ENF Hum   │
    │ GPS, Device ID, │ │ PRNU noise, CFA │  │ Faces, Plates,  │ │ 50Hz / 60Hz     │
    │ Creation Times  │ │ color mosaics   │  │ Text OCR, pHash │ │ Acoustic Grid   │
    └────────┬────────┘ └────────┬────────┘  └────────┬────────┘ └────────┬────────┘
             │                   │                    │                   │
             └───────────────────┼────────────────────┴───────────────────┘
                                 ▼
                     [ AUTOMATED SURVEILLANCE & ]
                     [ PLATFORM IDENTIFICATION  ]
```

### 1. Metadata & Container Tracking
- **Vector:** MP4/MOV atoms (`udta`, `moov`, `meta`, `QuickTime Keys`), JPEG/PNG EXIF/XMP/IPTC tags, GPS coordinates, camera serial numbers, creation timestamps, and software tags.
- **Threat Mitigation:** Full container sanitization strips all user-data atoms and metadata blocks, zeroes creation/modification timestamps to Unix Epoch 0 (`1970-01-01T00:00:00Z`), and purges embedded preview thumbnails.

### 2. Bitstream & Encoder Leaks
- **Vector:** Supplementary Enhancement Information (SEI) NAL units, x264/x265 configuration strings, encoder build strings, and vendor-specific bitstream markers.
- **Threat Mitigation:** Bitstream filtering removes unreferenced SEI user-data packets and normalizes SPS/PPS headers without corrupting decode syntax.

### 3. Sensor Photo-Response Non-Uniformity (PRNU)
- **Vector:** Microscopic physical variations in individual CMOS/CCD pixels create a unique, deterministic noise pattern acting as a silicon ballistics fingerprint.
- **Threat Mitigation:** Physical Bayer Color Filter Array (CFA) noise modeling injects sub-pixel synthetic sensor perturbations through non-linear saturation clamping and reconstructive demosaicing.

### 4. Semantic PII Leaks (Images & Stills)
- **Vector:** Human biometric facial features, vehicle license plates, printed documents/credentials, and scannable QR/barcode payloads.
- **Threat Mitigation:** Isolated `ConstantFill` solid bounding box redaction with safety margin expansion, linear sRGB pixel replacement, and complete prohibition of anti-aliasing ($\alpha \in \{0, 1\}$).

### 5. Perceptual Hashes & Motion Trajectories
- **Vector:** Spatial DCT grids (pHash, aHash, dHash) and temporal motion vector trees calculated across adjacent frames for automated indexing and dragnet cross-matching.
- **Threat Mitigation:** 2D DCT transform-domain perturbation subtly shifts AC coefficient medians across $8 \times 8$ blocks, altering spatial hashes while strictly preserving structural similarity.

### 6. Electric Network Frequency (ENF) Acoustic Grid Signatures
- **Vector:** Microphones capture subtle 50Hz (Europe/Asia) or 60Hz (Americas) electromagnetic and acoustic hums emitted by power lines. The subtle frequency drift of the power grid over time forms a unique temporal/geographic clock.
- **Threat Mitigation:** Multi-order IIR notch filters with high quality factors ($Q = 30$) attenuate primary grid frequencies (50Hz / 60Hz) and secondary harmonics (100Hz / 120Hz).

---

## Video Multi-Pass Pipeline Architecture

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
                        │ • Temporal Integrity Audit      │
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

### Video Transformation Engines

1. **Physical Bayer CFA Mosaic PRNU Sensor Engine (`veilframe/core/cfa_prnu.py`):**
   - Converts RGB frames into single-channel Bayer mosaic planes (`RGGB`, `BGGR`, `GRBG`, `GBRG`).
   - Injects channel-specific photon quantum noise ($\sigma_R, \sigma_G, \sigma_B$).
   - Applies non-linear saturation clamping $M(I) = \sin(\pi \cdot I / 255)^\gamma$.
   - Reconstructs frames via bilinear demosaicing to naturally disperse synthetic noise across channels.

2. **2D DCT Transform-Domain Hash Perturbation (`veilframe/core/hash_perturbation.py`):**
   - Decomposes luminance plane into non-overlapping $8 \times 8$ blocks.
   - Micro-shifts AC coefficients near the median boundary ($\pm \delta$) to decorrelate pHash/dHash signatures.
   - Enforces strict $\|I_{\text{new}} - I_{\text{orig}}\|_\infty \le 0.02$ spatial bounds ($\text{SSIM} \ge 0.95$).

---

## Image Privacy Compiler Architecture (Phase 2)

The Image Privacy Compiler (`veilframe/image/`) treats privacy redaction not as an ad-hoc brush tool, but as a deterministic, formal compilation pass over an input image $I \in \mathcal{I}$.

```
                 IMAGE PRIVACY COMPILATION PIPELINE
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │      INPUT IMAGE      │
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ LAYER A: CONTAINER    │
                     │ • Strip EXIF/XMP/IPTC │
                     │ • Purge Thumbnails    │
                     │ • Zero Timestamps     │
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ LAYER B: REPR NORM    │
                     │ • Convert to 8-bit sRGB│
                     │ • Linear Color Space  │
                     │ • Geometry Invariance │
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ LAYER C: REDACTION    │
                     │ • ConstantFill (RGB)  │
                     │ • Box Safety Margins  │
                     │ • Binary Alpha {0, 1} │
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ 7 RED-TEAM PROBES     │
                     │ (Level-3 Independent) │
                     │ • Face / Plate / OCR  │
                     │ • QR / Alpha / Palette│
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ 5-CONTRACT QUALITYGATE│
                     │ Privacy, Geometry,    │
                     │ Fidelity, Integrity,  │
                     │ Completeness          │
                     └───────────┬───────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │ CRYPTOGRAPHIC MANIFEST│
                     │ RFC 8785 + Ed25519    │
                     └───────────────────────┘
```

### Three-Layer Image Pipeline

1. **Layer A: Container Sanitization (`veilframe/image/sanitizers/container.py`):**
   - Drops all proprietary EXIF, XMP, IPTC, and vendor application tags (`APP1`-`APP15`).
   - Completely removes embedded thumbnail streams (which often preserve unredacted views).
   - Enforces epoch timestamp normalization.

2. **Layer B: Representation Normalization (`veilframe/image/sanitizers/representation.py`):**
   - Normalizes non-standard bit depths ($\ge 16$-bit, CMYK, LAB, YCbCr) into standardized 8-bit sRGB arrays.
   - Guarantees exact coordinate geometry invariance.

3. **Layer C: Semantic Pixel Redaction (`veilframe/image/sanitizers/semantic.py`):**
   - Renders opaque `ConstantFill` solid color blocks over target regions.
   - Enforces binary alpha masks ($\alpha \in \{0, 1\}$); partial alpha, Gaussian blurring, and pixelation are strictly prohibited because they leak reversible mathematical boundary information.

### 7 Independent Red-Team Probes (`veilframe/image/redteam/`)
All probes implement Level-3 Fingerprint-Distinct Independence (distinct algorithm, distinct feature representation, distinct training family):
- `FaceProbe`: Evaluates residual facial biometrics.
- `PlateProbe`: Evaluates vehicle license plate character patterns.
- `TextOCRProbe`: Evaluates residual optical character text.
- `QRBarcodeProbe`: Evaluates 2D QR matrix codes and 1D bar codes.
- `ContainerResidualProbe`: Scans output file structure for leaked metadata tags.
- `AlphaFringeProbe`: Scans redaction perimeters for semi-transparent alpha leakages.
- `PaletteIndexingProbe`: Verifies palette/indexed image representations do not retain hidden original color indices.

### 5 Normative QualityGate Contracts (`veilframe/image/verification/`)
- **Privacy Contract:** All red-team probes must return `PASS` (zero residual detections).
- **Geometry Contract:** Output dimensions must match expected dimensions exactly ($\|Observed - Expected\|_\infty = 0$).
- **Fidelity Contract:** Unredacted pixels must preserve source fidelity within configured budget ($D_{TV} \le \text{budget}$, $\text{SSIM} \ge \text{target}$).
- **Integrity Contract:** Every redacted pixel must be fully opaque ($\alpha \in \{0, 1\}$) with zero anti-aliasing edge fringe.
- **Completeness Contract:** Redaction mask coverage must be complete for all target nodes in the privacy graph.

---

## Cryptographic Audit Provenance

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

1. **RFC 8785 JSON Canonicalization Scheme (JCS):** Produces byte-for-byte deterministic JSON hashing across all platforms, operating systems, and architectures.
2. **Dual Signing Modes:**
   - **Ephemeral Mode (`mode: "ephemeral"`):** Fresh Ed25519 keypair generated in memory per export audit.
   - **Persistent Signer Mode (`mode: "persistent"`):** Signs using a persistent organizational key identity (`key_id: "veilframe-production-01"`).
3. **Standalone Verifier (`examples/verify_manifest.py` & `veilframe image verify`):** Self-contained, zero-dependency auditor requiring only standard Python and `cryptography`.

---

## Developer Interfaces: Dual-Mode GUI & CLI

VeilFrame offers both a modern terminal CLI and a dual-mode desktop GUI built on PySide6 / Qt:

### Desktop GUI (`veilframe-gui` / `veilframe gui`)
- **Dual Mode Switcher:** Instant toggling between `Video Sanitizer` and `Image Compiler`.
- **Drag-and-Drop Auto-Detection:** Automatically switches pipelines based on file extension (`.mp4`, `.mov`, `.mkv` vs `.png`, `.jpg`, `.webp`).
- **Semantic Detector Toggles:** Individual switches for Face, Plate, Text, and QR/Barcode detectors with safety margin controls.
- **Live 5-Contract Checklist:** Visual indicator badges for Privacy, Geometry, Fidelity, Integrity, and Completeness contracts.
- **Red-Team Results Table:** Tabular inspection of all 7 independent probe verdicts.
- **Signed Manifest Inspector:** Built-in viewer and clipboard exporter for canonical RFC 8785 JSON audit manifests.

### Command-Line Interface (`veilframe`)
- `veilframe sanitize <video> -o <output>`: Multi-pass video sanitization with quality gate audit.
- `veilframe image sanitize <image> -o <output>`: Deterministic image privacy compilation.
- `veilframe image verify <image> <manifest.json>`: Cryptographic image provenance verification.
- `veilframe image inspect <image>`: Deep inspection of container tags, thumbnails, and bit depths.
- `veilframe image doctor`: System diagnostics for image backends, neural detectors, and OCR engines.
- `veilframe inspect <video>`: Elementary stream and container atom inspection.
- `veilframe audit <ref> <trans>`: Independent 3-tier visual fidelity audit.
- `veilframe verify <manifest.json>`: Standalone Ed25519 signature and SHA-256 bitstream verification.
- `veilframe doctor`: Video environment and hardware encoder diagnostics.

---

## Decoupled Research Attribution Benchmark Layer

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

## Summary of System Invariants

1. **Zero Silent Modification:** Every byte modification is constrained by an explicit, self-describing mathematical policy budget or privacy contract.
2. **Quality Separation:** The transformation pipeline never evaluates its own visual fidelity.
3. **Cryptographic Binding:** Bitstreams and images are bound to their evaluation metrics via SHA-256 digests and Ed25519 digital signatures.
4. **Platform Independence:** Deterministic RFC 8785 canonicalization ensures identical verification results across Linux, macOS, and Windows.
