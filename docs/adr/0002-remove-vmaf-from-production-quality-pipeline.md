# ADR 0002: Permanent Removal of VMAF from Production Quality Pipeline

## Status
**Accepted** (2026-09-06)

## Context
VeilFrame is a local, privacy-preserving video sanitization engine. To ensure that metadata erasure, spatial/temporal perturbations, PRNU sensor noise dithering, and audio ENF filtering do not degrade viewer experience, VeilFrame enforces an objective visual quality gate before certifying transformed videos.

In VeilFrame v1.0, quality was evaluated using native FFmpeg libavfilter metrics:
- **SSIM** (Structural Similarity Index): Mean $\ge 0.9500$
- **PSNR** (Peak Signal-to-Noise Ratio): Mean $\ge 30.0\text{ dB}$

An extensive research investigation was conducted to determine whether **VMAF v1.0.16** (Video Multi-Method Assessment Fusion) could serve as a production quality gate. The investigation encompassed:
1. Synthetic perturbation calibration across 5 distortion domains (spatial, temporal, luminance, chroma, frequency).
2. Evaluation over an open benchmark corpus (BVI-DVC, UVG, Chimera, CrowdRun).
3. Technical domain qualification across SDR, HDR, and WCG.
4. Decode bit-depth sensitivity and duration slice variance analyses.

### Empirical Findings
The research concluded that VMAF v1.0.16 **was not qualified to reproduce VeilFrame's authoritative SSIM/PSNR acceptance policy under the evaluated conditions and constraints**:
- **Decision Incompatibility:** Across evaluated sequences and distortion domains, VMAF decision scores could not reliably separate passing from failing items under the independent policy without unacceptable false-accept or false-reject rates.
- **Sensitivity to Format and Pipeline Controls:** Metric scores exhibited variance across chroma subsampling, decode bit-depth promotion, and temporal duration slices that complicated threshold generalization.
- **Operational & Packaging Complexity:** Enforcing VMAF required custom FFmpeg binaries compiled with `libvmaf`, external JSON model files, and substantial CPU overhead, conflicting with zero-dependency local deployment goals.

## Decision
1. **Excise VMAF from Production:** Permanently remove `LibvmafFFmpegProvider`, `vmaf_models.py`, `vmaf_policy.py`, and all VMAF-specific settings (`vmaf_gate_mode`, `vmaf_mean_min`, etc.) from the VeilFrame runtime.
2. **Authoritative Quality Policy:** The production quality gate strictly enforces:
   $$\text{SSIM} \ge 0.9500 \quad \land \quad \text{PSNR} \ge 30.0\text{ dB}$$
   measured via native FFmpeg filtergraphs.
3. **Preserve Fail-Closed Semantics:** `QualityGate` preserves its strict fail-closed contract. If SSIM or PSNR measurements are missing or invalid, the gate rejects the video with explicit policy violations.
4. **No Secondary Executable Code:** Deprecated VMAF experiment runners in `tools/` and test suites are deleted. No executable VMAF code is maintained.
5. **Static Research Archive:** All empirical research reports, measurement control audits, and dataset provenance ledgers are archived statically under `research/vmaf/`.
6. **Decouple CI and Dependencies:** CI workflows, release gates, and developer environments require only standard FFmpeg (`ffmpeg` and `ffprobe`), with no `libvmaf` requirement.

## Consequences
- **Positive:**
  - Dramatically simplified quality architecture: "Providers measure. VeilFrame decides."
  - Deterministic, reproducible, fast quality decisions using native FFmpeg filters.
  - Zero external model asset files or custom FFmpeg compilation requirements.
  - Fully decoupled CI pipeline capable of running on standard distribution packages.
- **Neutral:**
  - Historical evidence and calibration data remain intact under `research/vmaf/` for scientific provenance and audit trail.
