# Phase B — Real-Content Calibration Corpus

This directory holds the real-world video clips used to validate the
candidate VMAF threshold produced by Phase A synthetic fixtures.

## Purpose

Synthetic fixtures (Phase A) establish an initial candidate threshold.
This corpus validates whether that threshold behaves **consistently across
content types** — which synthetic fixtures alone cannot prove.

## Required Clips (minimum 20, target 40)

Each sub-category should contain 3–6 representative clips.

| Category | Sub-category | Description |
|---|---|---|
| `natural/low_motion` | Interviews, talking heads, static scenes | Low inter-frame change |
| `natural/high_motion` | Sports, action, handheld | High inter-frame change |
| `natural/texture` | Foliage, fabric, crowds | High spatial detail |
| `natural/dark` | Night footage, low-key lighting | Low luma, noise-sensitive |
| `animation` | Screen-captured animation, cartoons | Flat colour, sharp edges |
| `screen_content` | Screen recordings, UI demos | Mixed text + graphics |
| `high_detail` | Macro, documentary, 4K downscaled | Maximum spatial frequency |

## Clip Requirements

- **Format:** Any container FFmpeg can decode (`.mp4`, `.mov`, `.mkv`, `.webm`, `.y4m`, `.avi`, `.m4v`, `.ts`)
- **Duration:** 5–30 seconds (longer clips improve statistical stability)
- **Resolution:** Native resolution (1080p and 4K domains supported with orientation-safety; no forced downscaling)
- **Dynamic Range:** SDR clips are evaluated against official VMAF v1.0.16 models; HDR clips (BT.2020/PQ/HLG) are automatically detected and segregated (`not_applicable_hdr`) while measuring SSIM/PSNR
- **Licensing:** Must be freely distributable or self-recorded
- **Privacy:** No identifiable persons without consent

## Environment Configuration

Configure the official VMAF model root if models are outside `%USERPROFILE%\vmaf\model`:
```powershell
$env:VMAF_MODEL_ROOT = "C:\path\to\vmaf\model"
```

## Historical Corpus Evaluation Pipeline

During Phase B empirical calibration, the evaluation pipeline operated in two decoupled stages:
1. **Measurement Runner (`tools/vmaf_corpus_runner.py`):** Applied fixture distortions and measured metric outputs.
2. **Threshold Analysis Engine (`tools/vmaf_threshold_analysis.py`):** Evaluated candidate operating points against FAR (<2.0%) and FRR (<5.0%) constraints.

The resulting data proved that no feasible global scalar threshold exists, leading to formal VMAF excision (ADR-003). The full reports and provenance are archived in `research/vmaf/`.

## Corpus Structure and Sample Accounting

- **Reference Clips:** 16 clips total (15 SDR across 14 sequence groups; 1 HDR clip `chimera` segregated from SDR calibration).
- **Sequence Groups:** 14 usable SDR sequence groups; 1 additional HDR group segregated from calibration (15 named sequence groups in `manifest.json`).
- **Fixture Pairs:** 128 total fixture pairs (16 clips × 8 fixtures).
  - 8 HDR pairs segregated.
  - 120 usable SDR pairs (88 development pairs across 10 groups + 32 held-out pairs across 4 groups).
  - *Note on development sample count:* The `park_joy` sequence group contains both 25fps and 50fps variants (2 clips × 8 = 16 pairs), so 10 development groups comprise 11 clips (88 fixture pairs).
  - *Binary evaluation samples:* 115 binary samples (85 dev + 30 held-out) evaluated; 5 boundary `MODERATE` pairs with acceptable metrics are excluded from binary threshold tuning.

## Production Gate Invariant

In VeilFrame v1.1+, the production quality gate is strictly independent and requires standard FFmpeg filters only:
- **Tier 1:** Transformation Policy Budget
- **Tier 2:** SSIM ($\ge 0.9500$) and PSNR ($\ge 30.0\text{ dB}$) Canonical Fidelity
- **Tier 3:** Pre-Resampling Temporal Stream Integrity

VMAF has been permanently excised from production dependencies, runtime configurations, and gating logic.

## Sourcing Clips

Recommended royalty-free sources:
- [Pexels Video](https://www.pexels.com/videos/) (CC0)
- [Coverr](https://coverr.co/) (CC0)
- [Pixabay Video](https://pixabay.com/videos/) (CC0)
- [Big Buck Bunny](https://peach.blender.org/) (CC-BY)
- [Tears of Steel](https://mango.blender.org/) (CC-BY)
- Self-recorded clips (preferred — known provenance)
