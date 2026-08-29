# VeilFrame Public Roadmap

## Architectural Invariant (permanent)

> **Providers measure. VeilFrame decides.**

No provider ever decides whether a video passes. The gate predicate is owned
exclusively by `QualityGate`. This invariant must not be violated at any phase.

---

## Release Baseline

### v1.0 COMPLETE
- Privacy sanitization (metadata, SEI NALs, PRNU, ENF, motion vectors)
- Independent quality gate (SSIM >= 0.95, PSNR >= 30 dB, 3-tier)
- Temporal audit (frame-level fingerprinting, decoded luma)
- Ed25519 signed audit manifest

### v1.1 CURRENT
- QualityProvider protocol + QualityGate separation
- FFmpegNativeProvider (SSIM + PSNR via lavfi)
- LibvmafFFmpegProvider (VMAF measurement-only, evidence only)
- VMAF evidence file + SHA-256 in signed manifest
- Calibration laboratory (tools/vmaf_calibration.py)
- UI 2.0 (provider status bar, sparkbars, 3-tab report, pill badges)
- 35 tests passing

---

## v1.2 -- Calibrated Perceptual Quality Gating

### Phase A -- VMAF Calibration Laboratory

8-level severity axis: IDENTICAL / VERY_LOW / LOW_PERTURBATION / MODERATE /
MODERATE_EXCEEDANCE / HIGH / SEVERE / EXTREME

Per fixture: VMAF mean/median/P1/P5/P95/worst/stddev + ADM2 + VIF(0-3) + SSIM + PSNR
Also record: FFmpeg ver, libvmaf ver, VMAF model, model SHA-256, fixture generator version

### Phase B -- Real-Content Calibration Corpus

20-40 representative clips across: natural/low_motion, high_motion, texture,
dark, animation, screen_content, high_detail. Validate threshold consistency
across content types.

### Phase C -- Candidate Threshold Generation

perceptual_pass = (vmaf_mean >= MEAN_MIN and vmaf_p5 >= P5_MIN and vmaf_worst >= WORST_MIN)
Values from calibration -- not pre-filled.

### Phase D -- VMAF Gate Promotion (after Phase C passes)

v1.1: policy AND temporal AND (SSIM + PSNR)
v1.2: policy AND temporal AND (SSIM + PSNR) AND vmaf_perceptual

QualityGate still owns. Provider still only measures.

### Phase E -- Manifest Schema v1.2
manifest_version 1.2.0 / quality-gate-v5.0
Adds: quality.vmaf block, policy.vmaf thresholds (auditable via signature)
verdict: { policy, temporal, structural, perceptual, overall }

### Phase F -- VMAF Release Gate + Calibration Regression

### Phase G -- Cross-Provider Validation (ffmpeg-quality-metrics)

---

## v1.3 -- Forensic Audit Layer

### Phase H -- Provider Consensus (MATCH / REVIEW / DISAGREE)
### Phase I -- Media Parser Consensus (ffprobe + MediaInfo)
### Phase J -- ExifTool Forensic Metadata (opt-in)
### Phase K -- Adversarial Laboratory (tests/adversarial/)
### Phase L -- Reproducibility Framework (veilframe audit-reproduce)

---

## v2.0 -- Audit Ecosystem

### Phase M -- Audit Bundle Standard (output.mp4 + manifest + sig + vmaf.json)
### Phase N -- UI 3.0 (provider consensus panel)
### Phase O -- Release Engineering (Windows / Linux / APK)

---

## Release Timeline

v1.0  COMPLETE  Privacy sanitization, quality gate, Ed25519 manifest
v1.1  CURRENT   QualityProvider, VMAF evidence, calibration lab, UI 2.0
                 |
         VMAF CALIBRATION (Phases A-C)
         Synthetic fixtures + real corpus + candidate threshold
                 |
v1.2             Calibrated VMAF gate, provider consensus, MediaInfo
                 |
v1.3             ExifTool, reproducibility, audit bundle, adversarial suite
                 |
v2.0             Multi-provider architecture, cross-platform, audit ecosystem
