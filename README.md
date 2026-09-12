<p align="center">
  <img src="docs/images/veilframe_logo.svg" width="160" height="160" alt="VeilFrame Logo" />
  <br />
  <h1 align="center">VeilFrame</h1>
  <p align="center"><b>Auditable Multimedia Privacy Compiler, Bounded Forensic Disruption & Cryptographic Provenance</b></p>
</p>

<p align="center">
  <a href="https://github.com/sahir247/VeilFrame"><img src="https://img.shields.io/badge/version-2.0.0-blue.svg" alt="Version" /></a>
  <a href="https://github.com/"><img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-blue.svg" alt="Platform" /></a>
  <a href="https://python.org/"><img src="https://img.shields.io/badge/Python-3.10%2B-green.svg" alt="Python" /></a>
  <a href="https://github.com/"><img src="https://img.shields.io/badge/CLI-veilframe-informational.svg" alt="CLI" /></a>
  <a href="https://pyside.org/"><img src="https://img.shields.io/badge/GUI-PySide6%20%2F%20Qt-brightgreen.svg" alt="GUI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-orange.svg" alt="License" /></a>
  <a href="https://ed25519.cr.yp.to/"><img src="https://img.shields.io/badge/Audit%20Signatures-Ed25519-purple.svg" alt="Audit Signatures" /></a>
  <a href="https://datatracker.ietf.org/doc/html/rfc8785"><img src="https://img.shields.io/badge/RFC%208785-JSON%20Canonicalization-blueviolet.svg" alt="RFC 8785" /></a>
</p>

---

**VeilFrame** is an advanced local multimedia sanitization, bounded forensic signal transformation, and privacy compiler system with independent visual-fidelity verification and cryptographic provenance. Unlike standard metadata strippers that only modify container headers or paint superficial blur filters, **VeilFrame** operates across both video and still-image domains:

1. **Video Domain:** Applies bounded, orthogonal signal perturbations across spatial geometry, temporal cadence, physical sensor noise (Bayer CFA PRNU), transform-domain perceptual hashes (2D DCT), ISP chrominance drift, and acoustic Electrical Network Frequency (ENF) hums within strict **5% or 10% transformation policy budgets**, guarded by an **independent read-only three-tier visual fidelity gate**.
2. **Image Domain:** Compiles images through a deterministic multi-layer privacy compiler (Layer A Container Stripping, Layer B Representation Normalization, Layer C Isolated Solid Redaction), audited against **7 Level-3 Fingerprint-Distinct independent red-team probes** and enforced by a normative **5-Contract QualityGate** (Privacy, Geometry, Fidelity, Integrity, Completeness).
3. **Cryptographic Provenance:** Every output is sealed with **RFC 8785 canonical JSON manifests** and **Ed25519 asymmetric digital signatures**.

---

## <img src="docs/images/icons/architecture.svg" width="22" height="22" alt="" /> System Architecture

![VeilFrame System Architecture](docs/images/veilframe_architecture.svg)

VeilFrame is built upon the permanent architectural invariant:
> **"Providers measure. VeilFrame decides."**  
> No transformation engine, red-team detector, or metric provider ever determines whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## <img src="docs/images/icons/gui.svg" width="22" height="22" alt="" /> Desktop Graphical Interface (GUI)

VeilFrame includes a modern desktop application built on PySide6 / Qt supporting both Video and Image workflows with real-time feedback:

![VeilFrame GUI Overview](docs/images/veilframe_gui_overview.svg)

### GUI Key Capabilities:
- **Dual Pipeline Switcher:** Seamlessly toggle between `Video Sanitizer` and `Image Compiler` modes.
- **Intelligent Drag-and-Drop:** Drops automatically detect media type and configure the appropriate pipeline.
- **Granular Semantic Detectors:** Toggle Face, License Plate, Text OCR, and QR/Barcode detectors with configurable safety margins.
- **5-Contract Visual Checklist:** Real-time verdict badges for Privacy, Geometry, Fidelity, Integrity, and Completeness.
- **Independent Red-Team Results Table:** Tabular inspection of individual probe verdicts and confidence metrics.
- **Cryptographic Manifest Inspector:** View, inspect, and copy signed RFC 8785 canonical JSON audit manifests directly from the UI.

To launch the GUI:
```bash
veilframe gui
# or directly:
veilframe-gui
```

---

## <img src="docs/images/icons/cli.svg" width="22" height="22" alt="" /> Terminal Command-Line Interface (CLI)

VeilFrame provides a unified developer terminal interface styled with structured cards, ANSI tables, and progress indicators:

![VeilFrame CLI Overview](docs/images/veilframe_cli_overview.svg)

### CLI Commands Summary:
| Command | Description |
|---|---|
| `veilframe sanitize <video> -o <out>` | Multi-pass video sanitization with 3-tier QualityGate audit |
| `veilframe image sanitize <image> -o <out>` | Deterministic image compilation with 5-Contract QualityGate |
| `veilframe image verify <image> <manifest>` | Cryptographic image provenance and bitstream verification |
| `veilframe image inspect <image>` | Deep inspection of image EXIF, XMP, IPTC, and embedded thumbnails |
| `veilframe image doctor` | Diagnostics for neural detectors, OCR backends, and image libraries |
| `veilframe inspect <video>` | Deep inspection of container atoms, elementary streams, and GPS tags |
| `veilframe audit <ref> <trans>` | Independent visual-fidelity audit of reference vs transformed media |
| `veilframe verify <manifest>` | Standalone Ed25519 signature and SHA-256 bitstream verification |
| `veilframe doctor` | System diagnostic check for OS, Python, FFmpeg, and GPU encoders |
| `veilframe presets` | Interactive inspection of transformation presets and policy budgets |

---

## <img src="docs/images/icons/pipelines.svg" width="22" height="22" alt="" /> Core Pipelines

### 1. Video Pipeline: Bounded Forensic Disruption

```
[Input Video]
     │
     ▼
[Pass 1: Pre-Sanitize] ──► Demux elementary streams, drop container atoms & SEI NALs
     │
     ▼
[Pass 2: Transform]    ──► Bayer CFA PRNU dither + 2D DCT median shift + ENF mains notch
     │
     ▼
[Pass 3: Post-Sanitize]──► Bit-exact packaging, timestamp zeroing (Epoch 0)
     │
     ▼
[Pass 4: QualityGate]  ──► 3-Tier Gate (Policy Budget + SSIM/PSNR Fidelity + Monotonic PTS)
     │
     ▼
[Pass 5: Audit Engine] ──► RFC 8785 Canonical JCS JSON + Ed25519 signature manifest
```

#### Mathematical Signal Transformations:
1. **Physical Bayer CFA PRNU Sensor Dither:**
   $$I_{\text{injected}} = \text{clip}\left(I_{\text{bayer}} + \beta \cdot I_{\text{bayer}} \cdot K \cdot \sin\left(\pi \cdot \frac{I}{255}\right)^\gamma, 0, 255\right)$$
2. **2D DCT Transform-Domain Perceptual Hash Perturbation:**
   $$X'(u_i, v_i) = \mu_{1/2} \pm \left(|X(u_i, v_i) - \mu_{1/2}| + \delta_{\text{shift}}\right)$$
   $$\|f_{\text{perturbed}}(x, y) - f_{\text{original}}(x, y)\|_\infty \le \epsilon \quad (\text{SSIM} \ge 0.95)$$
3. **Decoded YUV Total Variation Histogram Distance ($D_{TV}$):**
   $$D_{TV}(P_{\text{ref}}, P_{\text{trans}}) = \frac{1}{2} \sum_{i=0}^{255} |P_{\text{ref}}(i) - P_{\text{trans}}(i)| \in [0, 1]$$
4. **Electrical Network Frequency (ENF) Acoustic Notch:**
   Attenuates 50Hz, 60Hz, 100Hz, and 120Hz power-grid hums using high-Q multi-order IIR filters.

---

### 2. Image Pipeline: Deterministic Privacy Compiler

```
[Input Image]
     │
     ▼
[Layer A: Container]   ──► Strip EXIF, XMP, IPTC, vendor APP tags; purge preview thumbnails
     │
     ▼
[Layer B: Normalizer]  ──► Bit-depth to 8-bit, color mode to sRGB, coordinate geometry lock
     │
     ▼
[Layer C: Redaction]   ──► Isolated ConstantFill solid rectangles, safety margin expansion
     │
     ▼
[7 Red-Team Probes]    ──► Level-3 Fingerprint-Distinct audits (Face, Plate, OCR, QR, Fringe)
     │
     ▼
[5-Contract Gate]      ──► Privacy, Geometry, Fidelity, Integrity, Completeness
     │
     ▼
[Signed Manifest]      ──► Canonical RFC 8785 JCS + Ed25519 digital signature
```

#### Five Normative QualityGate Contracts:
- **Privacy Contract:** All Level-3 independent red-team probes return zero residual detections.
- **Geometry Contract:** Exact coordinate canvas preservation ($\|Observed - Expected\|_\infty = 0$).
- **Fidelity Contract:** Non-redacted pixels remain unaltered within mathematical budget limits ($D_{TV} \le \text{budget}$, $\text{SSIM} \ge \text{target}$).
- **Integrity Contract:** Every redacted pixel is strictly opaque with binary alpha ($\alpha \in \{0, 1\}$); partial alpha and blurring are forbidden.
- **Completeness Contract:** Redaction mask coverage strictly spans all requested privacy graph regions.

---

## <img src="docs/images/icons/presets.svg" width="22" height="22" alt="" /> Built-in Presets Comparison

| Feature / Policy Dimension | 5% Bounded Forensic Disruption | 10% Bounded Forensic Disruption | Privacy Clean |
|---|:---:|:---:|:---:|
| **Aggregate Policy Ceiling ($S_{\text{policy}}$)** | $\le 5.0\%$ | $\le 10.0\%$ | $0.0\%$ |
| **Spatial Geometry Ceiling ($\Delta_{\text{spatial}}$)** | $\le 2.0\%$ (99.8% Lanczos) | $\le 4.0\%$ (99.5% Lanczos, 2-4px crop) | $0.0\%$ (No crop/scale) |
| **Temporal Dynamics Ceiling ($\Delta_{\text{temporal}}$)** | $\le 1.0\%$ ($\pm 0.2\%$ speed) | $\le 2.0\%$ ($\pm 0.5\%$ speed, fractional FPS) | $0.0\%$ (Preserved) |
| **Luminance Drift Ceiling ($\Delta_{\text{luma}}$)** | $\le 1.0\%$ (0.5% luma, 1.5% gamma) | $\le 2.0\%$ (0.8% luma, 2.5% gamma) | $0.0\%$ (Original) |
| **Chrominance Drift Ceiling ($\Delta_{\text{chroma}}$)** | $\le 1.0\%$ (2.0% sat) | $\le 2.0\%$ (3.0% sat) | $0.0\%$ (Original) |
| **Frequency Noise Ceiling ($\Delta_{\text{freq}}$)** | $\le 1.0\%$ (Gaussian Noise Strength 8) | $\le 2.0\%$ (Bayer CFA PRNU Strength 16) | $0.0\%$ (Disabled) |
| **DCT Hash Perturbation** | Off | Enabled ($\text{SSIM} \ge 0.95$) | Off |
| **Audio ENF Notch Filtration** | 50/60/100/120 Hz, 0.99x pitch | 50/60/100/120 Hz, 0.985x pitch | Off |
| **Quality Gate: Mean SSIM Constraint** | $\ge 0.9500$ | $\ge 0.9000$ | $\ge 0.9500$ |
| **Quality Gate: Tail P5 SSIM Constraint** | $\ge 0.9000$ | $\ge 0.8500$ | $\ge 0.9000$ |
| **Quality Gate: Worst-Case SSIM** | $\ge 0.8500$ | $\ge 0.8000$ | $\ge 0.8500$ |
| **Quality Gate: Mean PSNR Constraint** | $\ge 30.0\text{ dB}$ | $\ge 28.0\text{ dB}$ | $\ge 30.0\text{ dB}$ |
| **Quality Gate: Worst-Frame PSNR** | $\ge 25.0\text{ dB}$ | $\ge 22.0\text{ dB}$ | $\ge 25.0\text{ dB}$ |
| **Metadata & SEI NAL Sanitization** | Full scrub | Full scrub | Full scrub |

---

## <img src="docs/images/icons/benchmarks.svg" width="22" height="22" alt="" /> Empirical Forensic Attribution Benchmarks (Research Suite)

The decoupled research benchmark layer evaluates empirical forensic decorrelation:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   RESEARCH ATTRIBUTION BENCHMARK LAYER                 │
├────────────────────────────────────────────────────────────────────────┤
│ • Layer 1 (Physical/Signal): PRNU PCE / NCC, ENF Welch PSD Attenuation │
│ • Layer 2 (Detector Decisions): pHash / dHash Hamming Distance Margins │
│ • Layer 3 (Multi-Camera ROC): True Positive Rate, FPR, and ROC AUC     │
└────────────────────────────────────────────────────────────────────────┘
```

- **PRNU Peak-to-Correlation Energy (PCE):**
  $$\text{PCE} = \frac{\text{NCC}(r_{\text{peak}}, c_{\text{peak}})^2}{\frac{1}{|U|} \sum_{(r, c) \in U} \text{NCC}(r, c)^2}$$
- **Welch Power Spectral Density Attenuation:**
  $$P_{xx}(f) = \frac{1}{K L U} \sum_{k=1}^K \left| \sum_{n=0}^{L-1} x_k[n] w[n] e^{-j 2\pi f n / f_s} \right|^2$$

---

## <img src="docs/images/icons/quickstart.svg" width="22" height="22" alt="" /> Quickstart & Installation

### 1. Prerequisites
- **Python:** 3.10 or newer.
- **FFmpeg:** System FFmpeg with `ffprobe` on `PATH`.

### 2. Installation
```bash
# Clone the repository
git clone https://github.com/sahir247/VeilFrame.git
cd VeilFrame

# Install core package with image and video support
pip install -e .

# Install GUI components
pip install -e ".[gui]"

# Install testing dependencies
pip install -e ".[test]"
```

### 3. Usage Examples

#### Video Sanitization
```bash
# Sanitize a video with standard 5% forensic disruption
veilframe sanitize input.mp4 -o sanitized.mp4

# Run with custom preset
veilframe sanitize input.mp4 -o sanitized.mp4 --preset "10% Bounded Forensic Disruption"

# Inspect raw container atoms
veilframe inspect input.mp4

# Verify signed audit manifest
veilframe verify sanitized_audit/manifest.json
```

#### Image Compilation
```bash
# Sanitize an image with automatic PII detection
veilframe image sanitize photo.jpg -o photo_clean.png

# Custom detectors and safety margins
veilframe image sanitize document.png -o doc_clean.png \
  --detectors face,plate,text,qr \
  --margin 12 \
  --fill "#000000"

# Verify cryptographic image provenance
veilframe image verify doc_clean.png doc_clean.manifest.json

# Deep inspection of container tags and thumbnails
veilframe image inspect photo.jpg
```

#### Desktop GUI
```bash
veilframe gui
```

---

## <img src="docs/images/icons/manifest.svg" width="22" height="22" alt="" /> Cryptographic Evidence Manifest

Every sanitized output generates an RFC 8785 canonical JSON manifest:

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

## <img src="docs/images/icons/limitations.svg" width="22" height="22" alt="" /> Limitations & Non-Guarantees

1. **Semantic Context:** VeilFrame neutralizes physical, acoustic, container, and pixel-level identifiers. It cannot obscure semantic text that the user opts not to redact (e.g., spoken dialogue).
2. **Extreme Adversaries:** Against determined manual human analysts with out-of-band context, manual inspection may still identify unredacted surroundings.
3. **Lossless Video Bit-for-Bit Identity:** Bounded forensic signal perturbation intentionally modifies pixel values within strict perceptual budgets; for 100% bitstream identity preservation, select the **Privacy Clean** preset.

---

## <img src="docs/images/icons/license.svg" width="22" height="22" alt="" /> License & Security

- **License:** MIT License. See [LICENSE](LICENSE) for details.
- **Security Policy:** See [SECURITY.md](SECURITY.md) for vulnerability reporting procedures and threat model documentation.
