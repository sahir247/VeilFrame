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

- **Format:** Any container FFmpeg can decode (MP4, MOV, MKV, WebM)
- **Duration:** 5–30 seconds (longer clips improve statistical stability)
- **Resolution:** Any (script normalises to 640×480 for comparison)
- **Licensing:** Must be freely distributable or self-recorded
- **Privacy:** No identifiable persons without consent

## File Naming Convention

```
<category>_<subcategory>_<id>.mp4
```

Examples:
```
natural_low_motion_01.mp4
natural_high_motion_02.mp4
animation_01.mp4
screen_content_03.mp4
```

## Running the Corpus Validation

Once clips are placed in the correct sub-directories, run:

```bash
python tools/vmaf_corpus_runner.py \
    --corpus calibration_corpus/ \
    --out vmaf_corpus_results.json
```

The corpus runner:
1. Applies all 8 fixture distortions to each clip
2. Measures VMAF + SSIM + PSNR for each fixture × clip combination
3. Checks whether the Phase A candidate threshold holds across all content types
4. Reports false-accept / false-reject rates per content category
5. Recommends a final threshold with confidence rating

## Decision Criterion

The candidate threshold from Phase A is **accepted** when:

- `LOW_PERTURBATION` passes on ≥ 95% of corpus clips
- `MODERATE_EXCEEDANCE` fails on ≥ 90% of corpus clips
- False-accept rate (unacceptable clips passing gate) < 2%
- False-reject rate (acceptable clips failing gate) < 5%

If criterion is not met, the threshold must be adjusted or the fixture
definitions must be revised before promoting VMAF to a gate predicate.

## Sourcing Clips

Recommended royalty-free sources:
- [Pexels Video](https://www.pexels.com/videos/) (CC0)
- [Coverr](https://coverr.co/) (CC0)
- [Pixabay Video](https://pixabay.com/videos/) (CC0)
- [Big Buck Bunny](https://peach.blender.org/) (CC-BY)
- [Tears of Steel](https://mango.blender.org/) (CC-BY)
- Self-recorded clips (preferred — known provenance)
