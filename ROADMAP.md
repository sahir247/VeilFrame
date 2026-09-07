# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether a video passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v1.1 CURRENT (Production Release Candidate)
- **Multi-Pass Sanitization Pipeline**: Container atom stripping, SEI removal, Bayer CFA PRNU dither, 2D DCT perturbation, ENF mains filtering.
- **Provider / Gate Separation**: `QualityProvider` protocol with `FFmpegNativeProvider` (SSIM, PSNR via native libavfilter).
- **Independent 3-Tier QualityGate**:
  - Tier 1: Multi-dimensional mathematical budget ceilings (Spatial, Temporal, Luma, Chroma, Frequency, Aggregate).
  - Tier 2: Structural & pixel fidelity (`SSIM >= 0.9500`, `PSNR >= 30.0 dB`, $D_{TV}$ luma distribution drift).
  - Tier 3: Temporal integrity & pre-resampling presentation timestamp (PTS) monotonicity audit.
- **Production Audit Bundle**: Dedicated `<video>_audit/` bundle co-locating `manifest.json` (RFC 8785 canonical JCS), `manifest.sig` (Ed25519), `manifest.sha256`, and `public_key.pem`.
- **Cryptographic Provenance**: Dual-mode Ed25519 signing (ephemeral & persistent) with pinned public key fingerprints and standalone zero-dependency verifier (`examples/verify_manifest.py`).
- **Interactive Developer TUI / CLI**: Keyboard-arrow traversable navigation, `#CE9178` brand styling, physical GPU detection, and hardware encoder diagnostics.
- **Test Matrix & CI**: Comprehensive unit and integration test suite passing across Ubuntu and Windows matrices.

---

### v1.2 UPCOMING: Objective Perceptual Refinement & Performance Optimization
- **VMAF Architecture Resolution**: Formal non-qualification and permanent excision of VMAF from production pipeline (see [ADR 0002](docs/adr/0002-remove-vmaf-from-production-quality-pipeline.md)); static historical research archive maintained under `research/vmaf/`.
- **High-Throughput Acceleration**: Zero-copy hardware accelerated pipeline paths for high-speed batch sanitization.
- **Advanced Audio Sanitization**: Enhanced multi-band ENF mains notch filtering and acoustic watermark neutralization.

---

### v1.3 UPCOMING: Advanced Forensic Consensus Layer
- **Multi-Parser Consensus**: Cross-validation of container syntax using both `ffprobe` and `MediaInfo`.
- **ExifTool Deep Forensic Audit**: Optional deep-inspection pass for non-standard vendor atoms.
- **Adversarial Regression Lab**: Automated test fixtures designed to stress-test adversarial bitstream tampering and clock-skew vectors.
- **Audit Reproducibility CLI**: `veilframe audit-reproduce <audit_bundle_dir>` for 1-click deterministic re-verification.
