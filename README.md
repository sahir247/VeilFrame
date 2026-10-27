<p align="center">
  <img src="docs/images/veilframe_logo.svg" width="160" height="160" alt="VeilFrame Logo" />
  <br />
  <h1 align="center">VeilFrame</h1>
  <p align="center"><b>Offline-First Multimedia Engineering, Forensic Signal Transformation, AI Super-Resolution & Privacy Compilation Platform</b></p>
</p>

<p align="center">
  <a href="https://github.com/sahir247/VeilFrame"><img src="https://img.shields.io/badge/version-2.3.0-blue.svg" alt="Version 2.3.0" /></a>
  <a href="https://github.com/"><img src="https://img.shields.io/badge/platform-Android%20%7C%20Windows%20%7C%20Linux%20%7C%20macOS-blue.svg" alt="Platform Support" /></a>
  <a href="https://python.org/"><img src="https://img.shields.io/badge/python-3.10%2B-green.svg" alt="Python 3.10+" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/kotlin-Android%20SDK%2035-purple.svg" alt="Kotlin Android SDK 35" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange.svg" alt="MIT License" /></a>
  <a href="https://ed25519.cr.yp.to/"><img src="https://img.shields.io/badge/provenance-Ed25519-purple.svg" alt="Ed25519 Signatures" /></a>
  <a href="https://datatracker.ietf.org/doc/html/rfc8785"><img src="https://img.shields.io/badge/spec-RFC%208785%20JCS-blueviolet.svg" alt="RFC 8785" /></a>
  <a href="#8-ai-project-intelligence--context-bundler-aibundle"><img src="https://img.shields.io/badge/ai%20context-.aibundle%20v1-informational.svg" alt="AI Context .aibundle v1" /></a>
</p>

---

## Executive Overview

**VeilFrame** is an offline-first, local-native multimedia engineering, signal transformation, and privacy compilation platform. Originally architected as a specialized bitstream privacy sanitizer, VeilFrame has evolved into a complete, standalone workstation spanning **14 specialized creative, computer vision, and forensic workspaces** across Mobile (Android) and Desktop (Windows, Linux, macOS).

VeilFrame requires zero cloud dependencies, guarantees 100% on-device data sovereignty, and enforces strict mathematical boundaries across all operations.

---

## Dedicated Workspaces & Tools

```
                             [ VeilFrame Core Engine ]
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼                               ▼                               ▼
 [ Creative & Vision Studios ]   [ Privacy & Forensics ]       [ Developer & Docs ]
 • AI Image Upscaler (Streaming) • Video Privacy Sanitizer     • Folder Forensic Scanner
 • QR Code Studio (EF Parity)    • Image Privacy Compiler      • AI Context Bundler (.aibundle)
 • Document Scanner (Stabilized) • Image Quality Forensics     • Offline Markdown Studio
 • Motion Lab (Optical Flow FPS) • Background Remover (Gallery)• Cryptographic Provenance
 • Video Studio & Compressor
 • Image Studio & Compressor
```

| Workspace | Domain | Key Technology | Core Capabilities |
|---|---|---|---|
| **1. AI Image Upscaler** | Creative / AI | NCNN C++, Vulkan, RealSR | Native Real-ESRGAN & RealSR NCNN super-resolution (2×, 3×, 4×), Vulkan GPU compute, streaming disk sinks, dynamic memory governor, zero ONNX. |
| **2. QR Code Studio** | Creative / Utility | OpenCV WeChat, CameraX, Canvas | 11 artistic rendering modes, EFQRCode 7.0.3 parity, Vector & Animated SVG export, live camera scanning, clean Scanability validation. |
| **3. Document Scanner** | Vision / Docs | QuadStabilizer, OpenCV 4.14 | Real-time EMA corner stabilization, per-page exposure/contrast/saturation Adjust dialog, perspective correction, progressive PDF export. |
| **4. Motion Lab** | Video / Vision | RIFE v4 AI, DIS & Farnebäck | Pairwise neural frame synthesis (RIFE v4) with dedicated 60 FPS target mode, optical flow fallback (DIS/Farnebäck), flat pairwise Mat management, MediaStore gallery output. |
| **5. Background Remover** | Vision / Utility | NCNN C++, U^2-Net-P, Guided Alpha | Native NCNN matting (U^2-Net-P), hybrid trimap refinement with guided alpha filter, interactive touch refine brush/eraser, direct gallery export (`Pictures/VeilFrame`). |
| **6. Video Studio** | Creative / Video | FFmpeg 8.1.7, Media3 ExoPlayer | Sub-100ms instant preview via direct URI, universal YUV420p output encoding, timeline range trimming, target size ceilings, rotation, crop. |
| **7. Image Studio** | Creative / Image | AndroidX ExifInterface, Lanczos | Continuous quality compression (1-100%), aspect cropping, lossless rotation, color grading filters, EXIF scrubbing, passport 600×600 preset. |
| **8. Video Privacy Sanitizer** | Privacy / Forensics | Orthogonal Signal Engine | Bitstream SEI NAL scrub, Bayer CFA PRNU dither, 2D DCT median shift, acoustic ENF mains notch, 3-tier QualityGate audit. |
| **9. Image Privacy Compiler** | Privacy / Forensics | Deterministic Compiler | ConstantFill solid redaction, 8-bit sRGB quantization normalization, 7 Level-3 adversarial red-team probes, 5-contract QualityGate. |
| **10. Image Quality Forensics**| Analyze / Vision | OpenCV Laplacian, Histograms | Component-wise sharpness, sensor noise, dynamic range, exposure, blur analysis, and honest diagnostic score cards. |
| **11. Folder Scanner** | Forensics / Storage | SQLite3, Parallel Hasher | Selective directory traversal, staged 3-tier duplicate file detector, wasted space calculation, full 64-char SHA-256 integrity tables. |
| **12. AI Context Bundler** | Developer / Docs | PathSpec, Knapsack Budgeter | Token-budgeted `.aibundle` v1, explicit max depth limits (`--max-depth`), modular secret masking, code outlining, multi-format export. |
| **13. Offline Markdown Studio**| Developer / Docs | Marked.js, KaTeX, Mermaid | Offline GFM editor, live dual-pane preview, formula/diagram rendering, single "Save As" menu (MD/PDF/DOCX), Light/Dark PDF print theming. |
| **14. Cryptographic Provenance**| Developer / Verify | Ed25519, RFC 8785 JCS | Neutral initial audit dashboard, Ed25519-SHA512 verification card, canonical JSON evidence manifests, and SHA-256 bitstream binding. |


---

## <img src="docs/images/icons/architecture.svg" width="22" height="22" alt="" /> System Architecture

![VeilFrame System Architecture](docs/images/veilframe_architecture.svg)

VeilFrame adheres to a permanent architectural invariant:
> **"Providers measure. VeilFrame decides."**  
> No transformation engine, red-team detector, or metric provider ever determines whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Workspace Deep-Dives

### 1. <img src="docs/images/icons/upscale.svg" width="20" height="20" alt="" /> AI Image Super-Resolution & Restoration (Native NCNN / RealSR)

VeilFrame delivers 100% offline, on-device neural super-resolution powered exclusively by **native Tencent NCNN C++ binaries (`realsr_jni.cpp`)** accelerated via Vulkan GPU compute with multi-threaded CPU fallbacks, completely free of ONNX Runtime dependencies:

- **Verified NCNN Neural Models (Pinned SHA-256 & Exact Byte Sizes):**
  - `Real-ESRGAN x4plus (NCNN)`: 4× high-fidelity universal restoration for blind real-world degradation (33.5 MB, BSD-3-Clause).
  - `Real-ESRGAN AnimeVideoV3 (NCNN)`: Compact 2×, 3×, and 4× super-resolution models specifically optimized for anime, manga, and animation frames (1.25 MB each, BSD-3-Clause).
  - `RealSR DF2K 4× (NCNN)`: High-fidelity photo restoration model trained on DIV2K and Flickr2K datasets (33.5 MB, BSD-3-Clause).
  - `RealSR DF2K JPEG 4× (NCNN)`: Specialized RealSR model with deep JPEG compression artifact suppression and edge recovery (33.5 MB, BSD-3-Clause).
  - `Real-ESRGAN HFA2k 4× (NCNN)`: High-frequency-aware portrait and texture synthesis model for facial details and fine fabrics (33.5 MB, BSD-3-Clause).
- **Model-Aware Honest Output Scales (2×, 3×, 4×):**
  - Dynamic scale toggle UI (`2×`, `3×`, `4×`) that enables only the native scales supported by the active model without artificial multi-pass Lanczos upscaling.
- **Fail-Closed Download Architecture:**
  - Strict SHA-256 digest verification, Content-Length checks, redirect domain allowlisting (`.github.com`, `.githubusercontent.com`, `.huggingface.co`, `.hf.co`), and atomic backup/rollback on failure.
- **Tiled Streaming & Memory Governor:**
  - Configurable tile dimensions with smoothstep Hermite blending to eliminate tile boundary seams.
  - Disk-backed memory streaming for ultra-high-resolution images exceeding physical RAM.
- **Mathematical Fallbacks:** Native multi-lobe Lanczos-3, Bicubic spline, and Nearest Neighbor resampling for zero-hallucination mathematical fidelity.

---

### 2. <img src="docs/images/icons/qr.svg" width="20" height="20" alt="" /> QR Code Studio (EFQRCode Parity, 11 Modes & Scanner)

VeilFrame includes a comprehensive QR Code Studio supporting 11 artistic visual rendering modes powered by the VeilFrame Art Engine, complete with EFQRCode 7.0.3 cleanroom parity:

- **11 Visual Rendering Modes:**
  1. `Basic`: Square modules with customizable corner shapes (rectangles, rounded, planets).
  2. `Bubble`: Organic cluster bubbles with distinct outline and inner core coloration.
  3. `2.5D`: Isometric 3D projection rendering top, left, and right illuminated cube faces.
  4. `DSJ`: Radial concentric position markers inspired by turntable geometry.
  5. `Image Fill`: Module-level image fill rendered via hardware `BitmapShader`.
  6. `Image Overlay`: Full background image compositing with contrast-preserving module masks.
  7. `Image Resample`: Pixelated image sampling into the QR grid with luminance weighting.
  8. `Line`: Interconnected horizontal and vertical stripe geometry.
  9. `Random Rectangle`: Seeded pseudo-random rectangular jitter for artistic layouts.
  10. `Function`: Procedural mathematical shape generation based on module coordinates.
  11. `Style Function`: Dynamic functional style compositing with secondary modulation.
- **EFQRCode 7.0.3 Parity Normalization:**
  - Authoritative Canonical Geometry IR (`effectiveDesignForMode()`) enforcing deterministic shape, quiet zone, and node order parity.
  - Full vector XML `<path>` and `<rect>` SVG export without raster blurring, plus animated GIF/SVG generation.
- **Live CameraX Viewfinder & Decode-First Validation:**
  - Real-time frame analysis using WeChatQRCode CNN primary detector with fallback gating.
  - Targeting reticle overlay with corner bracket animations (`QrScanOverlayView`) and gallery decoding.
- **Comprehensive Payload Parser (11 Formats):**
  - Instant parsing and safe intent dispatch for Wi-Fi (`WIFI:T:...;S:...;P:...;;`), UPI payments (`upi://pay`), URLs (with IDN homograph phishing defense), Phone (`tel:`), SMS (`smsto:`), Email (`mailto:`), Geo coordinates (`geo:`), Contacts (vCard/MeCard), Calendar events, and OtpAuth tokens.
  - Mandatory user confirmation prior to firing external action intents; responsive 2×2 button layout.

---

### 3. <img src="docs/images/icons/camera.svg" width="20" height="20" alt="" /> Document Scanner & Progressive PDF Exporter

Offline document capture and perspective correction powered by OpenCV 4.14.0:

- **Real-Time QuadStabilizer:** Exponential Moving Average (EMA, $\alpha=0.4$) corner filtering with 48px tolerance and 4-hit hysteresis, eliminating viewfinder quad jitter and strobing.
- **Per-Page Image Adjust Dialog:** Interactive per-page adjustment dialog for Exposure (−2..+2 stops), Contrast (0.5..2×), and Saturation (0..2×) backed by `cv/color/ColorEngine`.
- **CameraX Capture Discipline:** Single-flight capture lock (`isCapturing`) and OOM-guarded bitmap decoding preventing camera executor race conditions.
- **Progressive Export Sheet:** Multi-page PDF generation with configurable page sizes (A4, Letter, Legal), orientation, margins, and progressive disclosure options.

---

### 4. <img src="docs/images/icons/speed.svg" width="20" height="20" alt="" /> Motion Lab (Optical Flow Frame Synthesis)

Zero-trust, on-device video frame interpolation powered by native OpenCV optical flow algorithms:

- **Dual Estimation Algorithms:** DIS Optical Flow for fast mobile processing and Farnebäck Optical Flow for dense, high-accuracy motion vectors.
- **2× and 4× Frame Synthesis:** Pairwise intermediate frame synthesis (`FrameSynthesizer`) yielding smooth 60fps/120fps motion without cloud uploads.
- **Flat Memory Footprint:** Pairwise Mat memory lifecycle releasing native allocations after disk serialization; temporary frame scratch cleanup; direct export to MediaStore (`Movies/VeilFrame`).

---

### 5. <img src="docs/images/icons/crop.svg" width="20" height="20" alt="" /> Background Remover & Segmentation

Foreground extraction and portrait matting with transparent alpha output:

- **Neural & Classical Segmentation Modes:**
  - **Neural Models (ONNX Runtime):** State-of-the-art salient object detection using `BiRefNet-general` (tiny epoch 232) and `ISNet-general` with floating-point mask alpha compositing.
  - **GrabCut Classical Segmentation:** Fast bounding-box guided matting with morphological refinement, hole filling, and edge feathering.
- **Direct Gallery & Library Sync:** Direct export to device gallery (`Pictures/VeilFrame`) with Android 10+ `IS_PENDING` compliance, and persistent mirror to app storage for immediate Library visibility.

---

### 6. <img src="docs/images/icons/video_studio.svg" width="20" height="20" alt="" /> Video Studio & Visual Compressor

Dedicated visual video compressor and editor powered by FFmpeg 8.1.7 and Media3 ExoPlayer:

- **Sub-100ms Instant Preview:** Direct URI handoff to Media3 ExoPlayer eliminating intermediate disk copies and cache stalls.
- **Guaranteed YUV420p Compatibility:** Universal `-pix_fmt yuv420p` encoding across all export paths, guaranteeing playback compatibility across older hardware decoders and messaging apps.
- **Visual Timeline Range Trimmer:** Dual-thumb scrubber allowing arbitrary millisecond-accurate start/end range extraction with looping preview.
- **Platform Delivery Presets:**
  - `WhatsApp Target (16 MB)`: Multi-pass two-tier bitrate budget calculation with AAC audio compression.
  - `Discord Standard (25 MB)`: Byte-bounded safety margin compression for free-tier uploads.
  - `Discord Nitro (50 MB)`: High-bitrate 1080p/720p optimization.
  - `Email Attachment (8 MB / 25 MB)`: Compact resolution downsampling with speech mono audio.
  - `Web Optimized (10 MB)`: Fast-start MOOV atom relocation (`+faststart`) with CRF 28.
  - `Social Reel / Shorts`: 9:16 vertical crop with synchronized speed control.
- **Codec & Container Support:** Multi-codec encoding across H.264 (libx264), H.265 (libx265), VP9, and AV1 in MP4, MKV, WebM, MOV, and AVI containers.
- **Spatial & Temporal Controls:** 90°/180°/270° rotation, horizontal/vertical flip, custom margin crop slider (0-40%), and playback speed adjustment (0.5x to 2.0x with synchronized audio pitch).

---

### 7. <img src="docs/images/icons/image_studio.svg" width="20" height="20" alt="" /> Image Studio & Creative Compressor

Deep image transformation and compression studio:

- **Continuous Quality Compression:** Precision quality scaling (1% to 100%) with real-time empirical output size estimation.
- **Dimension Scaling & Cropping:** Multi-lobe Lanczos resampling, arbitrary scaling, and aspect-ratio cropping (Free, 1:1, 4:3, 16:9, 9:16).
- **Official Passport Preset:** Dedicated 600×600 px document export preset with strict 1:1 aspect constraint.
- **Color Grading Presets:** Color matrix transforms for Grayscale, Sepia, Vintage, Cool, and Warm tone grading.
- **Lossless Transform & EXIF Management:** 90-degree step rotations, arbitrary free rotation, alpha background fills, and zero-leakage metadata scrubbing.

---

### 8. <img src="docs/images/icons/security.svg" width="20" height="20" alt="" /> Video Privacy & Bounded Forensic Disruption

VeilFrame applies bounded, orthogonal signal perturbations across physical and transform domains to disrupt forensic re-identification while preserving perceptual visual quality:

```
[Input Video] ──► [Pass 1: Demux & Strip] ──► [Pass 2: Transform Signal] ──► [Pass 3: QualityGate] ──► [Signed Output]
```

- **Physical Bayer CFA PRNU Sensor Dither:**
  $$I_{\text{injected}} = \text{clip}\left(I_{\text{bayer}} + \beta \cdot I_{\text{bayer}} \cdot K \cdot \sin\left(\pi \cdot \frac{I}{255}\right)^\gamma, 0, 255\right)$$
- **2D DCT Transform-Domain Perceptual Hash Perturbation:**
  $$X'(u_i, v_i) = \mu_{1/2} \pm \left(|X(u_i, v_i) - \mu_{1/2}| + \delta_{\text{shift}}\right)$$
- **Acoustic Electrical Network Frequency (ENF) Mains Filter:**
  High-Q multi-order IIR notch filters targeting 50 Hz, 60 Hz, 100 Hz, and 120 Hz power-grid hums.
- **Independent Three-Tier QualityGate:**
  Enforces Mean SSIM $\ge 0.95$, Tail P5 SSIM $\ge 0.90$, Worst-Case SSIM $\ge 0.85$, Mean PSNR $\ge 30.0\text{ dB}$, and strictly monotonic presentation timestamps ($PTS_{i+1} > PTS_i$).

---

### 9. <img src="docs/images/icons/pipelines.svg" width="20" height="20" alt="" /> Image Privacy Compiler

A deterministic multi-layer privacy compiler audited against independent adversarial red-team probes:

- **Layer A (Container Stripping):** Bitstream scrubbing of EXIF, XMP, IPTC, maker notes, and preview thumbnails.
- **Layer B (Representation Normalization):** Quantization normalization to standard 8-bit depth and sRGB color space.
- **Layer C (Isolated Solid Redaction):** ConstantFill solid rectangular masks with configurable safety margins.
- **Adversarial Red-Team Probes:** Audited by 7 independent Level-3 detectors (Face, License Plate, Text OCR, QR/Barcode, Boundary Fringe).
- **Five Normative QualityGate Contracts:**
  - *Privacy Contract:* Zero residual detections from red-team probes.
  - *Geometry Contract:* Exact coordinate canvas preservation ($\|Observed - Expected\|_\infty = 0$).
  - *Fidelity Contract:* Non-redacted pixels remain unaltered within mathematical budget limits ($D_{TV} \le \text{budget}$, $\text{SSIM} \ge \text{target}$).
  - *Integrity Contract:* Redacted pixels are strictly opaque with binary alpha ($\alpha \in \{0, 1\}$); partial alpha and blurring are forbidden.
  - *Completeness Contract:* Redaction coverage strictly spans all requested privacy graph regions.

---

### 10. <img src="docs/images/icons/visibility.svg" width="20" height="20" alt="" /> Image Quality Forensics & Visual Audit

Independent computer vision analysis evaluating image integrity and defects:

- **Component-Wise Metric Scoring:** Evaluates Laplacian variance sharpness, Gaussian noise variance, dynamic range entropy, and exposure clipping.
- **Honest Diagnostics:** Structured scoring cards with categorized verdicts (Excellent, Good, Fair, Poor) without fabricated score fallbacks.

---

### 11. <img src="docs/images/icons/folder.svg" width="20" height="20" alt="" /> Folder Forensics & Staged Duplicate Finder

High-performance selective directory analyzer and cryptographic duplicate candidate detector:

- **Optimized Scanning:** Fast `os.scandir()` traversal extracting only requested attributes with zero unneeded file I/O.
- **SQLite3 Persistent Indexing:** Immediate search, extension distribution aggregation, and directory depth rollups.
- **Staged 3-Tier Duplicate Engine:**
  - *Stage 1:* Exact byte-size partitioning ($O(1)$ elimination of unique files).
  - *Stage 2:* 16 KiB head/tail staged fingerprinting.
  - *Stage 3:* Streaming full SHA-256 calculation for confirmed candidates.
- **Comprehensive Reporting:** Export full uncut 64-character SHA-256 reports to interactive HTML dashboards, Markdown inventory tables, CSV, JSON, and structured text.

---

### 12. <img src="docs/images/icons/ai.svg" width="20" height="20" alt="" /> AI Project Intelligence & Context Bundler (`.aibundle`)

Deterministic codebase packaging compiler designed for Large Language Models (LLMs) and autonomous AI coding agents:

- **Zero-Truncation Guarantee:** Selected source files are delivered in their entirety; no arbitrary middle-slicing or truncated code fragments.
- **Priority Knapsack Budgeting:** Operates under configurable token ceilings (`32k`, `64k`, `128k`, `200k`, `1M`, or `Unlimited`). Entry points and core architecture are prioritized over tests and secondary docs.
- **Explicit Max Depth Limits:** Interactive UI depth controls and `--max-depth` CLI argument bounding recursive traversal depth.
- **Modular Syntax-Preserving Secret Masking:** Language-specific regexes and Shannon entropy scanners mask credentials (AWS keys, GitHub tokens, private keys) with `[REDACTED]` while avoiding false positives on URLs or paths.
- **Structural Outlining & Summaries:** AST signature extraction for Python, brace-language outlining (TypeScript, Go, Rust, Java, C++), lockfile condensation (`uv.lock`, `package-lock.json`), and SQLite schema extraction.
- **Multi-Format Export:** Generates `.aibundle` v1, Markdown, interactive single-page HTML, structured JSON, and curated ZIP archives.

---

### 13. <img src="docs/images/icons/markdown.svg" width="20" height="20" alt="" /> Offline Markdown Studio & Multi-Format Exporter

Integrated offline GitHub Flavored Markdown (GFM) workspace:

- **Dual-Pane Live Preview:** Instant split-view or full-screen rendering of GFM documentation.
- **Offline Diagram & Formula Rendering:** Embedded Mermaid diagram synthesis and KaTeX mathematical typesetting without internet access.
- **Unified "Save As" Menu:** Streamlined single-action menu for exporting to Markdown (`.md`), PDF (`.pdf`), and Microsoft Word (`.docx`).
- **Print & PDF Theming:** Specialized `@media print` CSS rules formatting documents with clean Light and Dark print styles, pagination, and math scaling.
- **Productivity Features:** Table of contents generator, document structure outlining, syntax-highlighted code blocks with 1-click clipboard copy, in-page search, and New Markdown Maker.

---

### 14. <img src="docs/images/icons/manifest.svg" width="20" height="20" alt="" /> Cryptographic Provenance & Manifest Verification

Zero-trust cryptographic evidence validation and bitstream verification:

- **Neutral Initial Audit State:** Clean initial verification dashboard without pre-populated failure markers.
- **RFC 8785 Canonical JSON Manifests:** Deterministic JCS canonicalization bound with Ed25519 digital signatures and SHA-256 bitstream digests.
- **Ed25519-SHA512 Verification Card:** Instant validation of signed manifests, public key fingerprint verification, and input/output integrity checking.


---

## Desktop Graphical Interface (GUI)

VeilFrame includes a local desktop application built on PySide6 / Qt:

![VeilFrame GUI Overview](docs/images/veilframe_gui_overview.svg)

```bash
# Launch the desktop GUI
veilframe gui
# or directly:
veilframe-gui
```

### Standalone Pre-Built Packages:

| OS / Platform | Artifact | Architecture |
|---|---|---|
| **Windows** | `VeilFrame-windows-x86_64.exe` | x86_64 (Standalone GUI & CLI) |
| **Linux (Debian/Ubuntu)** | `VeilFrame-linux-x86_64.deb` | x86_64 (Desktop app + CLI) |
| **Linux (Portable)** | `VeilFrame-linux-x86_64.tar.gz` | x86_64 (Standalone portable executable) |
| **macOS (Installer)** | `VeilFrame-macos-arm64.dmg` | Apple Silicon (ARM64) |
| **macOS (Portable)** | `VeilFrame-macos-arm64.tar.gz` | Apple Silicon (ARM64) |
| **Android (APK)** | `app-debug.apk` / `VeilFrame-android-arm64.apk` | ARM64 / Universal (API 26+) |
| **Python Package** | `veilframe-2.3.0-py3-none-any.whl` | Universal (`pip install`) |

---

## <img src="docs/images/icons/cli.svg" width="22" height="22" alt="" /> Terminal Command-Line Interface (CLI)

![VeilFrame CLI Overview](docs/images/veilframe_cli_overview.svg)

### CLI Commands Reference:

| Command | Action |
|---|---|
| `veilframe sanitize <video> -o <out>` | Multi-pass video privacy sanitization with 3-tier QualityGate audit |
| `veilframe video compress <video> [opts]` | Video compression, visual range trimming, resolution scaling & platform presets |
| `veilframe image sanitize <image> -o <out>` | Deterministic image compilation with 5-Contract QualityGate |
| `veilframe image compress <image> [opts]` | Image compression, Lanczos resize, EXIF scrub, rotation & color grading |
| `veilframe image verify <image> <manifest>` | Cryptographic image provenance and bitstream verification |
| `veilframe image inspect <image>` | Deep inspection of image EXIF, XMP, IPTC, and embedded thumbnails |
| `veilframe folder scan <dir>` | High-performance selective directory analysis with configurable presets |
| `veilframe folder dupes <dir>` | 3-stage duplicate file detection with wasted space calculations |
| `veilframe folder stats <dir>` | Statistical size rollups, file type distributions, and directory depth |
| `veilframe folder export <dir> -o <out>` | Export comprehensive reports to interactive HTML, MD, JSON, CSV, TXT |
| `veilframe folder ai <dir> -o <out>` | Compile codebase into `.aibundle` v1, Markdown, HTML, JSON, or ZIP |
| `veilframe inspect <video>` | Deep inspection of container atoms, elementary streams, and GPS tags |
| `veilframe audit <ref> <trans>` | Independent visual-fidelity audit of reference vs transformed media |
| `veilframe verify <manifest>` | Standalone Ed25519 signature and SHA-256 bitstream verification |
| `veilframe doctor` | System diagnostic check for OS, Python, FFmpeg, and GPU encoders |

---

## <img src="docs/images/icons/presets.svg" width="22" height="22" alt="" /> Policy Presets Comparison

| Feature / Policy Dimension | 5% Forensic Disruption | 10% Forensic Disruption | Privacy Clean |
|---|:---:|:---:|:---:|
| **Aggregate Policy Ceiling ($S_{\text{policy}}$)** | $\le 5.0\%$ | $\le 10.0\%$ | $0.0\%$ |
| **Spatial Geometry Ceiling ($\Delta_{\text{spatial}}$)** | $\le 2.0\%$ (99.8% Lanczos) | $\le 4.0\%$ (99.5% Lanczos, 2-4px crop) | $0.0\%$ (No crop/scale) |
| **Temporal Dynamics Ceiling ($\Delta_{\text{temporal}}$)** | $\le 1.0\%$ ($\pm 0.2\%$ speed) | $\le 2.0\%$ ($\pm 0.5\%$ speed) | $0.0\%$ (Preserved) |
| **Luminance Drift Ceiling ($\Delta_{\text{luma}}$)** | $\le 1.0\%$ (0.5% luma, 1.5% gamma) | $\le 2.0\%$ (0.8% luma, 2.5% gamma) | $0.0\%$ (Original) |
| **Chrominance Drift Ceiling ($\Delta_{\text{chroma}}$)** | $\le 1.0\%$ (2.0% sat) | $\le 2.0\%$ (3.0% sat) | $0.0\%$ (Original) |
| **Frequency Noise Ceiling ($\Delta_{\text{freq}}$)** | $\le 1.0\%$ (Gaussian Noise) | $\le 2.0\%$ (Bayer CFA PRNU) | $0.0\%$ (Disabled) |
| **DCT Hash Perturbation** | Off | Enabled ($\text{SSIM} \ge 0.95$) | Off |
| **Audio ENF Notch Filtration** | 50/60/100/120 Hz | 50/60/100/120 Hz | Off |
| **Quality Gate: Mean SSIM** | $\ge 0.9500$ | $\ge 0.9000$ | $\ge 0.9500$ |
| **Quality Gate: Worst-Case SSIM** | $\ge 0.8500$ | $\ge 0.8000$ | $\ge 0.8500$ |
| **Quality Gate: Mean PSNR** | $\ge 30.0\text{ dB}$ | $\ge 28.0\text{ dB}$ | $\ge 30.0\text{ dB}$ |
| **Metadata & SEI NAL Scrub** | Full scrub | Full scrub | Full scrub |

---

## <img src="docs/images/icons/manifest.svg" width="22" height="22" alt="" /> Cryptographic Evidence Manifest

Every sanitized output generates an RFC 8785 canonical JSON manifest signed with Ed25519:

```json
{
  "schema_version": "2.0.0",
  "media_type": "image",
  "input_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "output_sha256": "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
  "contracts": {
    "privacy": "PASS",
    "geometry": "PASS",
    "fidelity": "PASS",
    "integrity": "PASS",
    "completeness": "PASS"
  },
  "signature": {
    "algorithm": "Ed25519",
    "public_key_fingerprint": "SHA256:...",
    "signature_bytes_base64": "..."
  }
}
```

---

## <img src="docs/images/icons/quickstart.svg" width="22" height="22" alt="" /> Quickstart & Installation

```bash
# Clone the repository
git clone https://github.com/sahir247/VeilFrame.git
cd VeilFrame

# Install core package
pip install -e .

# Install testing dependencies
pip install -e ".[test]"

# Run test suite
pytest
```

---

## <img src="docs/images/icons/limitations.svg" width="22" height="22" alt="" /> Limitations & Non-Guarantees

1. **Semantic Context:** VeilFrame neutralizes physical, acoustic, container, and pixel-level identifiers. It cannot obscure semantic text that the user opts not to redact (e.g., spoken dialogue).
2. **Extreme Adversaries:** Against determined manual human analysts with out-of-band context, manual inspection may still identify unredacted surroundings.
3. **Lossless Video Bit-for-Bit Identity:** Bounded forensic signal perturbation intentionally modifies pixel values within strict perceptual budgets; for 100% bitstream identity preservation, select the **Privacy Clean** preset.

---

## <img src="docs/images/icons/license.svg" width="22" height="22" alt="" /> License & Security

- **License:** MIT License. See [LICENSE](LICENSE) for details.
- **Security Policy:** See [SECURITY.md](SECURITY.md) for vulnerability reporting procedures and threat model documentation.
