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

---

## Folder Analyzer & Staged Duplicate Detection Architecture

```
                               ┌──────────────────────────┐
                               │     TARGET DIRECTORY     │
                               └────────────┬─────────────┘
                                            │
                                            ▼
                           ┌─────────────────────────────────┐
                           │   Selective os.scandir() Walk   │
                           │ • Zero unselected field I/O     │
                           │ • Configurable scan profiles    │
                           │ • Non-blocking streaming events │
                           └────────┬───────────────┬────────┘
                                    │               │
                                    ▼               ▼
                 ┌───────────────────────┐   ┌─────────────────────────────┐
                 │   SQLite Index DB     │   │   Staged Duplicate Engine   │
                 │ • Persistent / Memory │   │ • Stage 1: Size grouping    │
                 │ • Instant text filter │   │ • Stage 2: Head/tail 16KiB  │
                 │ • Size & depth rollup │   │ • Stage 3: Streaming SHA-256│
                 └───────────┬───────────┘   └──────────────┬──────────────┘
                             │                              │
                             └──────────────┬───────────────┘
                                            │
                                            ▼
                           ┌─────────────────────────────────┐
                           │   Multi-Format Report Engine    │
                           │ • Interactive HTML (Live Search)│
                           │ • Full 64-char SHA-256 in MD    │
                           │ • JSON, CSV, Structured TXT     │
                           │ • Standardized Folder Naming    │
                           └─────────────────────────────────┘
```

### 1. Selective Field-Driven Scanning
- Traversal strictly avoids unnecessary syscalls (e.g. `stat()`, `created`, `accessed`, `permissions`) when those fields are not explicitly selected by the user.
- Emits non-blocking progressive events (`folderDiscovered`, `fileDiscovered`, `progress`) to keep the user interface fully interactive and animated without GUI freezing.

### 2. Staged 3-Tier Duplicate Filtering
- **Stage 1 (Exact Size Matching)**: Instant $O(1)$ grouping. Files with unique sizes are filtered out immediately without disk reads.
- **Stage 2 (Head/Tail Staged Fingerprint)**: Hashes `[file_size] + [head 8KiB] + [tail 8KiB]`. Reads at most 16 KiB even for massive multi-gigabyte media files.
- **Stage 3 (Parallel Streaming Hash Verification)**: Parallel `ThreadPoolExecutor` streams 1 MiB chunks through cryptographic hashers (SHA-256) only for candidate collisions.

### 3. Full Cryptographic Integrity & Report Generation
- Uncut 64-character SHA-256 digests are stored internally and preserved across all export formats.
- Generates interactive, self-contained HTML dashboards with one-click clipboard copying, searchable inventory tables, and collapsible directory trees.

---

## AI Project Intelligence & LLM Context Subsystem

```
                    PROJECT DIRECTORY
                           │
                           ▼
                    FAST INVENTORY
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
           PROJECT CONTEXT    FILE CLASSIFIER
                 │                   │
                 ▼                   ▼
           ECOSYSTEM ENGINE    RULE REGISTRY (YAML)
                 │                   │
                 └─────────┬─────────┘
                           ▼
                    SECURITY ENGINE
                           │
                           ▼
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
      LOGICAL TREE   DEPENDENCIES   RELATIONSHIPS
           │               │               │
           └───────────────┼───────────────┘
                           ▼
                    PRIORITY ENGINE
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
           CONTENT SELECTOR    TOKEN BUDGET
                 │                   │
                 └─────────┬─────────┘
                           ▼
                    CONTENT READER
                 (Zero-Truncation Guarantee)
                           │
                           ▼
                   CONTEXT ASSEMBLER
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
              INDEX                FILES
           (IDs F001..)     (Complete Source)
                 │                   │
                 └─────────┬─────────┘
                           ▼
                     AI BUNDLE
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
          .aibundle       .md         .html
```

### 1. Two-Layer Context Package Architecture
Designed to maximize **information density per token** while eliminating AI hallucination:
- **Layer A (Project Index)**: Highly structured metadata layer containing:
  - Project Manifest (primary language, ecosystems, frameworks, libraries, package manager, build system)
  - Logical Tree (with collapsed exclusions accurately labeled: `node_modules/ [EXCLUDED: dependency]`, `veilframe/ [EXCLUDED: Context budget exhausted]`)
  - Dependency manifests (runtime, development, standard library)
  - Module import relationships graph with `[INTERNAL]` vs `[EXTERNAL]` classification
  - Master file index mapping IDs (`F001`, `F002`) to paths, roles, and token counts
- **Layer B (File Contents)**: Complete, untruncated source, test, config, and doc files wrapped in unambiguous boundaries:
  ```text
  @FILE id="F001" path="veilframe/core/pipeline.py" type="source" language="python"
  <<<
  ...
  >>>
  ```

### 2. Language-Family Modular Secret Detectors
Rather than relying on a fragile, universal assignment regex that introduces false positives or corrupts syntax, VeilFrame utilizes a modular hierarchy of language-aware detectors:
- **Generic Token Formats (`generic.py`)**: Identifies high-entropy signatures (AWS access keys, GitHub PATs, Slack tokens, Stripe keys, OpenAI/Anthropic keys, private key blocks, JWT tokens).
- **Language-Family Assignment Detectors**:
  - `python.py`: Evaluates Python variable assignments and type annotations (`api_key: str = "..."`).
  - `javascript.py`: TypeScript / JavaScript declarations (`const apiKey: string = "..."`, `let secret = "..."`).
  - `go.py`: Go declarations (`var stripeKey string = "..."`, `apiKey := "..."`).
  - `rust.py`: Rust constants and let bindings (`const API_KEY: &str = "..."`).
  - `jvm.py`: Java and Kotlin declarations (`val secretToken: String = "..."`, `private static final String API_KEY = "..."`).
  - `c_family.py`: C, C++, C#, Dart, and Swift (`let signingKey: String = "..."`, `final String token = "..."`).
  - `php.py`: PHP variable assignments (`$apiPassword = "..."`).
  - `shell.py`: Bash / Shell environment exports (`export PRIVATE_KEY="..."`).
  - `config.py`: Key-value configuration formats (YAML, JSON, TOML, `.env`).
- **Syntax-Preserving Redaction Guarantee**: Inline redaction replaces only the secret payload while preserving 100% of surrounding declarations, type annotations, sigils, and semicolons.

### 3. Resilient Multi-Encoding Reader (`TextReadResult`)
- Evaluates text files through a strict decoding hierarchy:
  1. Byte Order Mark (BOM) sniffing (`UTF-8-SIG`, `UTF-16-LE`, `UTF-16-BE`).
  2. Strict UTF-8 decoding.
  3. Validated UTF-16 with null-rate heuristics (differentiating valid PowerShell/Windows UTF-16 text from binary streams).
  4. CP1252 / Latin-1 fallback with strict non-silent binary discrimination: files with high rates of unprintable control characters are identified as `is_binary=True`, preventing binary blobs from being decoded into corrupt pseudo-source.

### 4. The 10 Invariants of `.aibundle` v1
Every generated `.aibundle` adheres to a strict formal contract:
1. **Invariant 1 (Credential Shield)**: Dedicated credential files (`.env`, `id_rsa`, `*.pem`) are never included in context.
2. **Invariant 2 (Zero Secret Leaks)**: Detected inline secret tokens are 100% masked/redacted.
3. **Invariant 3 (Token Budget Ceiling)**: Total estimated tokens never exceed the configured budget.
4. **Invariant 4 (Deterministic Paths)**: Every `@FILE` entry has a normalized relative path.
5. **Invariant 5 (One-to-One File Mapping)**: Every included file has exactly one corresponding `@FILE` block.
6. **Invariant 6 (Mandatory File Survival)**: Core architecture files (`README.md`, `ARCHITECTURE.md`, `pyproject.toml`, `main.py`) survive under extreme budget pressure.
7. **Invariant 7 (No Binary Serialization)**: Binary executables, images, and compiled data are never serialized into code blocks.
8. **Invariant 8 (Security Segregation)**: `@SECURITY` contains security findings only.
9. **Invariant 9 (Context Segregation)**: `@EXCLUDED` contains context-selection explanations only.
10. **Invariant 10 (UTF-8 Integrity)**: The bundle output is 100% valid, decodable UTF-8 text.

---

## Multi-Platform & Android Architecture

VeilFrame decouples the core media processing and intelligence engines from desktop GUI assumptions, enabling native execution across Desktop (Windows, Linux, macOS) and Mobile (Android):

```
                   VEILFRAME SYSTEM
                          │
          ┌───────────────┴───────────────┐
          │                               │
     CORE ENGINE                  PLATFORM ADAPTERS
          │                               │
   ┌──────┼──────┐                 ┌──────┴──────┐
   │      │      │                 │             │
 Video  Image  Folder           Desktop       Android
Engine Engine Intelligence       (Qt)        (Chaquopy)
   │      │      │                 │             │
Media  Raster  Security         PySide6       Native UI
Backend Graph  Detectors         Desktop     Activity/SAF
```

### 1. `MediaBackend` & `MediaSource` Abstraction
- **`MediaSource`**: Exposes a uniform interface across raw filesystem paths, Android `content://` URIs, file descriptors, and materialized scratch files via `open_read()`, `open_write()`, and `as_path()`.
- **`MediaBackend`**: Isolates FFmpeg and platform codec invocation:
  - `DesktopFFmpegBackend`: Orchestrates native CLI FFmpeg executables with GPU hardware encoders (NVENC, QSV, AMF, VideoToolbox).
  - `AndroidMediaBackend`: Bridges to FFmpegKit JNI, bundled `libffmpeg.so`, or Android MediaCodec APIs.
  - `get_media_backend()`: Dynamically selects the runtime backend based on platform detection.

### 2. Android Scoped Storage & Least-Privilege Permissions
- Operates under modern Android Scoped Storage (API 26 to 34+):
  - Uses the Android Storage Access Framework (SAF) and system file pickers to access media.
  - Eliminates deprecated, overbroad permissions (`WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`).
  - Restricts media reads to granular `READ_MEDIA_VIDEO` and `READ_MEDIA_IMAGES` (API 33+).

### 3. Chaquopy Native Android Bridge
- The Android host application (`android/app/src/main/java/com/veilframe/app/MainActivity.kt`) runs the core Python engine directly on-device via Chaquopy.
- Native background processing routes video sanitization, image scrubbing, and AI bundle compilation through local Android services without external cloud dependencies.

---

## CI/CD & Automated Release Pipeline Topology

VeilFrame utilizes a comprehensive multi-stage GitHub Actions release pipeline (`.github/workflows/ci.yml`):

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            GITHUB ACTIONS WORKFLOW                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. TEST MATRIX                                                              │
│    • Ubuntu (Python 3.10, 3.11, 3.12)                                       │
│    • Windows (Python 3.10, 3.11, 3.12)                                      │
│    • macOS (Python 3.10, 3.11, 3.12)                                        │
│    • 3-Layer Blackbox & 10-Invariant Test Gate                              │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. RELEASE GATE                                                             │
│    • Full regression gate on Linux, Windows, macOS                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. MULTI-PLATFORM PACKAGING                                                 │
│    • Windows: PyInstaller Standalone (VeilFrame-windows-x86_64.exe)         │
│    • Linux: Debian Package (.deb) & Portable Tarball (.tar.gz)              │
│    • macOS: Apple Silicon DMG (.dmg) & Portable Tarball (.tar.gz)           │
│    • Android: Release APK (arm64-v8a) & Release AAB (Universal)             │
│    • Python: Universal Wheel (.whl) & Source Dist (.tar.gz)                 │
├─────────────────────────────────────────────────────────────────────────────┤
│ 4. AGGREGATE & PUBLISH                                                      │
│    • Compute SHA256SUMS.txt across all 7 platform distribution packages     │
│    • Create GitHub Release with signed artifacts and release notes          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Developer Interfaces: 4-Mode GUI & CLI

VeilFrame offers both a modern terminal CLI and a 4-mode desktop GUI built on PySide6 / Qt:

### Desktop GUI (`veilframe-gui` / `veilframe gui`)
- **4-Mode Segmented Switcher:** Instant toggling between `Video Sanitizer`, `Image Privacy`, `Folder Analyzer`, and the dedicated `AI Project Lister`.
- **Dedicated AI Project Lister:** Features upfront tab priority, real-time token gauge, file inclusion/exclusion policies, and one-click export to native `.aibundle` v1, Markdown, HTML, JSON, and ZIP.
- **Drag-and-Drop Auto-Detection:** Automatically switches pipelines based on file extension (`.mp4`, `.mov`, `.mkv` vs `.png`, `.jpg`, `.webp`) or directory drop.
- **Live Progressive Tree:** Animated directory tree rendering during active folder scans.
- **Pulsing Telemetry:** Animated gradient progress bar with items/sec throughput and millisecond timers.
- **Semantic Detector Toggles:** Individual switches for Face, Plate, Text, and QR/Barcode detectors with safety margin controls.
- **Live 5-Contract Checklist:** Visual indicator badges for Privacy, Geometry, Fidelity, Integrity, and Completeness contracts.
- **Red-Team Results Table:** Tabular inspection of all 7 independent probe verdicts.
- **Signed Manifest Inspector:** Built-in viewer and clipboard exporter for canonical RFC 8785 JSON audit manifests.
- **Clipboard & Context Menu Actions:** One-click full SHA-256 copying and direct OS file manager integration.

### Command-Line Interface (`veilframe`)
- `veilframe sanitize <video> -o <output>`: Multi-pass video sanitization with quality gate audit.
- `veilframe video compress <video> [opts]`: Precision video compression, visual range timeline trimmer, and platform size presets.
- `veilframe image sanitize <image> -o <output>`: Deterministic image privacy compilation.
- `veilframe image compress <image> [opts]`: Smart image quality compression, Lanczos resize, EXIF scrub, rotation, and color filters.
- `veilframe image verify <image> <manifest.json>`: Cryptographic image provenance verification.
- `veilframe image inspect <image>`: Deep inspection of container tags, thumbnails, and bit depths.
- `veilframe image doctor`: System diagnostics for image backends, neural detectors, and OCR engines.
- `veilframe folder scan <dir>`: Selective directory analysis with configurable presets and reports.
- `veilframe folder dupes <dir>`: 3-stage duplicate file detection with wasted space calculations.
- `veilframe folder stats <dir>`: Summary size rollups, file type distributions, and directory depth.
- `veilframe folder export <dir> -o <out>`: Export comprehensive reports to interactive HTML, MD, JSON, CSV, TXT.
- `veilframe folder ai <dir> -o <out>`: Compile codebases into native `.aibundle` or Markdown AI context packages.
- `veilframe inspect <video>`: Elementary stream and container atom inspection.
- `veilframe audit <ref> <trans>`: Independent 3-tier visual fidelity audit.
- `veilframe verify <manifest.json>`: Standalone Ed25519 signature and SHA-256 bitstream verification.
- `veilframe doctor`: Video environment and hardware encoder diagnostics.
- `veilframe presets`: Inspection of transformation presets and policy budgets.

---

## Media Compression & Studio Subsystems (v2.2.5 Architecture)

The media compression subsystem (`veilframe.core.media_compressor`) exposes high-performance compression and editing pipelines with 100% unified parity across Desktop GUI, CLI, and Android Chaquopy/native runtime:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                    MEDIA COMPRESSION & STUDIO SUBSYSTEM                    │
├──────────────────────────────────────┬─────────────────────────────────────┤
│        VIDEO COMPRESSOR STUDIO       │        IMAGE COMPRESSOR STUDIO      │
│                                      │                                     │
│ • Precision Timeline Range Trimmer   │ • Target File Size Solver (KB/MB)   │
│   Fast-seek arbitrary start/end      │   Iterative binary search (5-95)    │
│ • Multi-Container Output Engine      │ • Quality Percentage Slider (1-100%)│
│   (MP4, MOV, MKV, WebM, AVI, GIF)    │ • Dimension Lanczos Grid Resampling │
│ • Multi-Codec Video Encoding         │ • Aspect Ratio Center Cropping      │
│   (H.264, H.265, VP9, AV1, Copy)     │ • Text Watermark / Overlay Studio   │
│ • Audio Studio Processing            │   9-point anchor, size, color & drop│
│   Volume (0–200%), Channels          │ • Horizontal & Vertical Flips       │
│   (Stereo/Mono/Keep), Codecs (AAC,   │ • Alpha Background Fill Compositing │
│   MP3, Opus, FLAC, Mute)             │   (White, Black, Transparent)       │
│ • Platform Target Bitrate Allocators │ • Vectorized NumPy Color Grading    │
│   (WhatsApp 16MB, Discord 25MB/50MB, │ • Zero-Leakage EXIF/GPS Scrubbing   │
│    Email 8MB, Web 10MB)              │ • Lossless 90°/180°/270° Rotation   │
│ • Lanczos Resolution Resampling      │ • Multi-Format Output Encoding      │
│ • 0.25x–4.0x Speed/Pitch Chaining    │   (JPEG, PNG, WebP)                 │
└──────────────────────────────────────┴─────────────────────────────────────┘
```

1. **Target File Size Solver:** Employs an iterative binary search optimizer (5–95% quality range, $\le 7$ iterations) to compress images directly to a bounded maximum file size budget without manual trial and error.
2. **Watermark & Alpha Fill Engine:** Non-destructive overlay rendering featuring 9-point spatial anchoring, font scaling, drop-shadow contrast protection, and alpha background replacement for transparent graphics.
3. **Multi-Container & Codec Pipeline:** Transmuxes and re-encodes video streams across MP4, MOV, MKV, WebM, AVI, and animated GIF formats utilizing H.264 (`libx264`), H.265 (`libx265`), VP9 (`libvpx-vp9`), and AV1 (`libsvtav1`).
4. **Audio Matrix Processor:** Dynamic audio gain adjustment (0% to 200%), downmixing/upmixing (mono, stereo, channel pass-through), and resampling across AAC, MP3, Opus, and FLAC codecs.
5. **Precision Range Trimming:** Employs two-phase fast keyframe seeking (`-ss` before `-i`) combined with exact output clamping (`-to`), eliminating decode lag and drift.
6. **Target Bitrate Allocation:** Calculates video bitrates using explicit audio overhead budgets and byte safety margins:
   $$\text{Bitrate}_{\text{target}} = \frac{\text{TargetBytes} \times 8 \times \text{SafetyMargin}}{\text{Duration}} - \text{AudioBitrate}$$
7. **Android APK Signature Scheme V2/V3:** Release packaging enforces pure block-level APK Signature Scheme v2/v3, eliminating obsolete V1 JAR signing and verifying clean against `minSdkVersion 26`.

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
