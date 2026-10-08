# ADR 0007: Motion Lab & Native Optical Flow Frame Interpolation

## Status

Status: Accepted  
Implementation Baseline: `v2.2.9` (branch `main`)  
Date: October 2026  
Related: ADR 0004 (Workspace Catalogue), ADR 0005 (M3 Expressive), ADR 0006 (CV Governance)

---

## 1. Context

VeilFrame's computer vision engine contained research implementations of dense optical flow and frame synthesis (`com.veilframe.app.cv.motion.FlowEstimator`, `FrameSynthesizer`). However, these capabilities lacked a user-facing entry point, remaining dormant in production builds.

Mobile users regularly encounter low-framerate video recordings (e.g., 24 fps or 30 fps) that exhibit judder during high-motion playback or when slowed down. Existing cloud-based video interpolation tools violate user privacy by uploading unencrypted media to remote inference servers. A zero-trust, on-device frame interpolation workspace was required that could safely synthesize intermediate video frames (2× and 4× framerates) while strictly operating within mobile compute, thermal, and memory boundaries.

---

## 2. Decision

Promote Motion Lab to the **14th canonical workspace** (`WorkspaceRoute.MOTION_LAB`) in the VeilFrame catalogue under the `CREATE_EDIT` category with an initial **Alpha** status designation.

### 2.1 Native Optical Flow Algorithm Hierarchy
Optical flow estimation is executed entirely on-device via native OpenCV primitives:
1. **Primary Engine**: `cv::optflow::createOptFlow_DIS(DISOpticalFlow::PRESET_FAST)`. Dense Inverse Search provides orders-of-magnitude acceleration over classical variational methods, making mobile pairwise motion vector calculation computationally viable.
2. **Fallback Engine**: `cv::calcOpticalFlowFarneback` (multiscale polynomial expansion), utilized if DIS is unavailable or encounters non-standard channel configurations.

### 2.2 Governed Pairwise Frame Processing
To guarantee deterministic memory execution on devices with 6–16 GB of RAM:
1. **Extraction**: Source video is split into sequential PNG frames via `FFmpegKit` into a private working directory (`context.cacheDir/motion_lab/<session_id>/`).
2. **Pairwise Streaming**: The engine processes adjacent frames $(F_i, F_{i+1})$ one pair at a time:
   - Compute forward flow $\mathbf{u}_{i \to i+1}$ and backward flow $\mathbf{u}_{i+1 \to i}$.
   - Bi-directionally warp pixel coordinates using OpenCV `remap`.
   - Alpha-blend with motion vector occlusion weights to generate intermediate frames (1 intermediate for 2×, 3 intermediates for 4×).
   - **Immediate Mat Release**: All native OpenCV matrices (`Mat`) are released immediately after the interpolated frame is serialized to disk. Heap memory remains completely flat across arbitrarily long frame sequences.
3. **Cancellation Checkpoints**: In compliance with CV Phase B6 (ADR 0006), coroutine `isActive` checks occur before every pair calculation, allowing instantaneous cancellation without orphan processes or leaked native allocations.

### 2.3 Re-Encoding & MediaStore Export
1. Interpolated frame sequences are compiled into H.264 video using `FFmpegKit` with `-c:v libx264 -crf 18 -preset fast -pix_fmt yuv420p`, multiplexing the original audio stream without re-compression degradation.
2. The finished artifact is saved directly to `context.filesDir/exports` and published to `MediaStore.Video.Media` under `Movies/VeilFrame`.
3. Processed videos are automatically indexed into VeilFrame's offline `Library` tab.

### 2.4 Safety Quotas & Honest Failures
Because dense optical flow is compute-intensive:
- **Duration Cap**: Maximum 30 seconds (or 900 frames) per job on mobile.
- **Validation**: Enforce resolution matching; reject corrupt or variable-geometry streams with transparent error messages.
- **Fail-Honest**: Any native allocation error or FFmpeg failure triggers an immediate error toast and telemetry logging; never output fake duplicated frames disguised as optical flow synthesis.

---

## 3. Consequences

### Positive
- Users can double (60 fps) or quadruple (120 fps) video framerates with high perceptual smoothness completely offline.
- Flat memory footprint prevents out-of-memory crashes even during 1080p optical flow synthesis.
- Unified with VeilFrame's M3 Expressive design system, action dock, and insets contract.

### Negative / Trade-offs
- CPU/GPU intensive: Long clips generate noticeable heat; strictly guarded by the 30-second cap.
- Alpha maturity: Fast-moving non-rigid deformations or complex transparent occlusions may exhibit localized warping artifacts.
