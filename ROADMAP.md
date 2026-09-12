# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether media passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v2.0 CURRENT (Production Release)
- **Dual Multimedia Pipeline**:
  - **Video Sanitization Pipeline**: Multi-pass container atom stripping, SEI NAL removal, Bayer CFA PRNU dither, 2D DCT block perturbation, and acoustic ENF mains notch filtration.
  - **Image Privacy Compiler**: Multi-layer deterministic compilation pipeline (Layer A Container Sanitization, Layer B Representation Normalization, Layer C Isolated Semantic Redaction).
- **Independent 5-Contract QualityGate**:
  - Privacy Contract: Zero residual facial, plate, text, or QR/barcode detections across independent probes.
  - Geometry Contract: Exact preservation of spatial canvas dimensions ($\|Observed - Expected\|_\infty = 0$).
  - Fidelity Contract: Strict pixel preservation in non-redacted areas ($D_{TV} \le \text{budget}$, $\text{SSIM} \ge \text{target}$).
  - Integrity Contract: Strict alpha binary quantization ($\alpha \in \{0, 1\}$) with zero anti-aliasing edge leaks.
  - Completeness Contract: Complete structural bounding-box coverage across all requested regions.
- **Independent Red-Team Probe Suite**:
  - 7 Level-3 Fingerprint-Distinct probes (Face, License Plate, OCR Text, QR/Barcode, Container Residuals, Alpha Fringe, Palette Indexing).
- **Dual-Mode PySide6 GUI**:
  - Modern desktop interface with real-time video/image mode switcher, drag-and-drop auto-detection, detector toggles, visual 5-contract checklist, probe results table, and signed manifest inspector.
- **Cryptographic Provenance**:
  - RFC 8785 Canonical JCS JSON manifests bound with Ed25519 digital signatures and SHA-256 bitstream digests.
  - Ephemeral and persistent signing modes with pinned public key fingerprints.
- **Unified CLI Suite**:
  - Full CLI support (`veilframe sanitize`, `veilframe image sanitize`, `veilframe image verify`, `veilframe image inspect`, `veilframe image doctor`, `veilframe doctor`, `veilframe presets`).

---

### v2.1 UPCOMING: Hardware Acceleration & High-Throughput Batch Processing
- **Zero-Copy GPU Paths**: Direct GPU texture sharing for real-time video and image batch redaction.
- **Async Batch Dispatcher**: Multi-threaded worker queue for large-scale directory and cloud bucket batch sanitization.
- **Advanced Audio Neutralization**: Expanded harmonic notch filtering and acoustic watermark neutralization.

---

### v2.2 UPCOMING: Advanced Forensic Consensus & Distributed Provenance
- **Multi-Parser Consensus**: Cross-validation of container syntax using both `ffprobe`, `MediaInfo`, and native Rust parsers.
- **ExifTool Deep Forensic Audit**: Optional deep-inspection pass for proprietary vendor metadata blocks.
- **Hardware Security Module (HSM) Integration**: Direct PKCS#11 HSM support for enterprise cryptographic audit signing.
- **Audit Reproducibility CLI**: `veilframe audit-reproduce <audit_bundle_dir>` for 1-click deterministic re-verification.
