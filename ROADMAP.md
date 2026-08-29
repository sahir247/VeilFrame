# VeilFrame Public Roadmap

## Architectural Invariant (Permanent)

> **Providers measure. VeilFrame decides.**
>
> No transformation engine or metric measurement provider ever decides whether a video passes. Pass/fail verdicts are owned strictly and exclusively by the independent, read-only `QualityGate`.

---

## Release Milestones & Architecture Status

### v1.1 CURRENT (Production Release Candidate)
- **Multi-Pass Sanitization Pipeline**: Container atom stripping, SEI removal, Bayer CFA PRNU dither, 2D DCT perturbation, ENF mains filtering.
- **Provider / Gate Separation**: `QualityProvider` protocol with `FFmpegNativeProvider` (SSIM, PSNR) and `LibvmafFFmpegProvider` (VMAF, ADM2, VIF).
- **Independent 3-Tier QualityGate**:
  - Tier 1: Multi-dimensional mathematical budget ceilings (Spatial, Temporal, Luma, Chroma, Frequency, Aggregate).
  - Tier 2a: Structural & pixel fidelity (`SSIM >= 0.95`, `PSNR >= 30 dB`, $D_{TV}$ luma distribution drift).
  - Tier 3: Temporal integrity & pre-resampling presentation timestamp (PTS) monotonicity audit.
- **Production Audit Bundle**: Dedicated `<video>_audit/` bundle co-locating `manifest.json` (RFC 8785 canonical JCS), `manifest.sig` (Ed25519), `manifest.sha256`, `public_key.pem`, and `vmaf.json` evidence.
- **Cryptographic Provenance**: Dual-mode Ed25519 signing (ephemeral & persistent) with pinned public key fingerprints and standalone zero-dependency verifier (`examples/verify_manifest.py`).
- **Interactive Developer TUI / CLI**: Keyboard-arrow traversable navigation, `#CE9178` brand styling, physical GPU detection, and hardware encoder diagnostics.
- **Test Matrix & CI**: 106+ comprehensive unit and integration tests passing across Ubuntu and Windows matrices.

---

### v1.2 IN-PROGRESS: Calibrated VMAF Perceptual Gate Promotion

The Tier 2b VMAF gate logic is implemented behind `VisualBudgetPolicy.vmaf_gate_enabled = False`. To promote it to active production status:

#### Phase A: Calibration Laboratory Execution (`tools/vmaf_calibration.py`)
- Evaluates 8-level perturbation severity ladder: `IDENTICAL`, `VERY_LOW`, `LOW_PERTURBATION`, `MODERATE`, `MODERATE_EXCEEDANCE`, `HIGH`, `SEVERE`, `EXTREME`.
- Records VMAF mean, median, P1, P5, P95, worst, stddev, ADM2, and VIF across fixture matrices.

#### Phase B: Real-Content Calibration Corpus (`tools/vmaf_corpus_runner.py`)
- Runs multi-clip evaluation across natural, high-motion, textured, low-light, screen-content, and high-detail video categories.

#### Phase C: Threshold Freeze & Gate Promotion
- Replace initial development placeholders (`vmaf_mean_min = 75.0`, `vmaf_p5_min = 60.0`) with frozen empirical bounds (false-accept rate $< 2\%$, false-reject rate $< 5\%$).
- Promote `vmaf_gate_enabled = True` in default production profiles.

---

### v1.3 UPCOMING: Advanced Forensic Consensus Layer
- **Multi-Parser Consensus**: Cross-validation of container syntax using both `ffprobe` and `MediaInfo`.
- **ExifTool Deep Forensic Audit**: Optional deep-inspection pass for non-standard vendor atoms.
- **Adversarial Regression Lab**: Automated test fixtures designed to stress-test adversarial bitstream tampering and clock-skew vectors.
- **Audit Reproducibility CLI**: `veilframe audit-reproduce <audit_bundle_dir>` for 1-click deterministic re-verification.
