# ADR 0007: Motion Lab & Native Optical Flow Frame Interpolation

## Status

Status: Accepted  
Implementation Baseline: `v2.3.1` (branch `main`)  
Date: November 2026 (Updated for v2.3.1 zero-bundle on-demand model architecture)  
Related: ADR 0004 (Workspace Catalogue), ADR 0005 (M3 Expressive), ADR 0006 (CV Governance)

---

## 1. Context

VeilFrame's computer vision engine initially contained classical research implementations of dense optical flow and frame synthesis (`com.veilframe.app.cv.motion.FlowEstimator`, `FrameSynthesizer`). In v2.3.0, Motion Lab was upgraded with deep learning-based neural optical flow interpolation using Tencent NCNN with Vulkan GPU compute acceleration (RIFE v4 series).

Mobile users regularly encounter low-framerate video recordings (e.g., 24 fps or 30 fps) that exhibit judder during high-motion playback or when slowed down. Existing cloud-based video interpolation tools violate user privacy by uploading unencrypted media to remote inference servers. A zero-trust, on-device frame interpolation workspace was required that could safely synthesize intermediate video frames (2×, 4×, and dedicated 60 FPS target framerate) while strictly operating within mobile compute, thermal, and memory boundaries.

---

## 2. Decision

Promote Motion Lab to the **14th canonical workspace** (`WorkspaceRoute.MOTION_LAB`) in the VeilFrame catalogue under the `CREATE_EDIT` category with an initial **Production-Ready** status designation.

### 2.1 Multi-Tier Frame Synthesis Algorithm Hierarchy

Frame interpolation follows a strict, verifiable multi-tier execution hierarchy:

1. **Neural Optical Flow Engine (Primary)**:
   - Powered by Tencent NCNN C++ inference (`librife_jni.so`) with Vulkan GPU acceleration.
   - Supports modern RIFE v4 architectures:
     - `rife-v4.26` / `rife-v4.25`: State-of-the-art flow refinement with temporal consistency.
     - `rife-v4.22-lite` / `rife-v4.15-lite`: High-efficiency low-power neural models optimized for mobile chipsets.
     - `rife-v4.0`: Baseline stable neural flow model.
   - Legacy RIFE v2.4 is permanently deprecated and excluded from the active registry.
   - **On-Demand Zero-Bundle Policy**: AI models are not bundled into APK assets to prevent app bloat. Neural models are downloaded dynamically on demand via GitHub / CDN endpoints upon mode selection or via the Model Manager dialog, protected by cryptographic checksum validation and atomic file installation.

2. **Classical Dense Optical Flow Engine (Secondary / Fallback)**:
   - `cv::optflow::createOptFlow_DIS(DISOpticalFlow::PRESET_FAST)`: Dense Inverse Search provides orders-of-magnitude acceleration over classical variational methods, making mobile pairwise motion vector calculation computationally viable.
   - `cv::calcOpticalFlowFarneback` (multiscale polynomial expansion): Utilized if DIS is unavailable or encounters non-standard channel configurations.

3. **Strict Fail-Honest Contract (Zero Silent Duplication)**:
   - Previous implementations silently duplicated the previous frame and marked the frame as `interpolatedSuccessfully = true` if neural inference failed.
   - Under the v2.3.0 governance contract, when RIFE returns null or fails, the engine falls back to OpenCV DIS/Farnebäck synthesis. If fallback synthesis also fails, the frame is recorded as degraded, `interpolatedSuccessfully` is set to `false`, and honest metrics (`degradedFrameCount`) are surfaced to the user. Duplicated frames are never masked as successful optical flow synthesis.

### 2.2 Governed Pairwise Frame Processing & Extended Duration

To guarantee deterministic memory execution on devices with 6–16 GB of RAM:

1. **Extraction**: Source video is split into sequential PNG frames via `FFmpegKit` into a private working directory (`context.cacheDir/motion_lab/<session_id>/`).
2. **Pairwise Streaming**: The engine processes adjacent frames $(F_i, F_{i+1})$ one pair at a time:
   - Neural flow warping via RIFE v4, or bi-directional warping pixel coordinates using OpenCV `remap`.
   - Alpha-blend with motion vector occlusion weights to generate intermediate frames (1 intermediate for 2×, 3 intermediates for 4×, or exact timestamps for 60 FPS target mode).
   - **Immediate Mat & Bitmap Release**: All native OpenCV matrices (`Mat`) and neural tensors are recycled immediately after the interpolated frame is serialized to disk. Heap memory remains completely flat across arbitrarily long frame sequences.
3. **Cancellation Checkpoints**: In compliance with CV Phase B6 (ADR 0006), coroutine `isActive` checks occur before every pair calculation, allowing instantaneous cancellation without orphan processes or leaked native allocations. Discarding work cleans up all staged cache frames atomically.
4. **Extended Video Duration & Soft Thermal Quotas**:
   - The artificial 30-second hard cap has been removed to support longer user videos.
   - To prevent unintended battery drain and thermal throttling, clips exceeding 30 seconds present a soft warning dialog ("Videos longer than 30 seconds may cause device heating and increased processing time") with "Proceed" and "Cancel" actions, plus a persistent "Do not show again" preference toggle.

### 2.3 Dynamic UI & Interactive Lifecycle

1. **Dynamic Action Dock**:
   - The Interpolate button transitions dynamically: displays disabled processing state during inference, then transitions to an active "Save" button upon job completion.
   - Action controls (Trim, Speed, Model Selector) are safely disabled during active synthesis to prevent concurrent pipeline collisions.
2. **Re-Encoding & MediaStore Export**:
   - Interpolated frame sequences are compiled into H.264 video using `FFmpegKit` with `-c:v libx264 -crf 18 -preset fast -pix_fmt yuv420p`, multiplexing the original audio stream without re-compression degradation.
   - The finished artifact is saved directly to `context.filesDir/exports` and published to `MediaStore.Video.Media` under `Movies/VeilFrame`.
   - Processed videos are automatically indexed into VeilFrame's offline `Library` tab.

---

## 3. Consequences

### Positive
- Users can double (60 fps), quadruple (120 fps), or target specific framerates with cutting-edge neural flow synthesis (RIFE v4) or classical OpenCV DIS completely offline.
- Elimination of silent duplicate frame masking ensures full forensic and perceptual integrity.
- Flat memory footprint prevents out-of-memory crashes even during 1080p and UHD optical flow synthesis.
- Extended duration support accommodates real-world mobile video workflows while informing users of thermal considerations.
- Unified with VeilFrame's M3 Expressive design system, action dock, and insets contract.

### Negative / Trade-offs
- GPU-accelerated neural inference generates heat on long video sequences; users are transparently warned via the duration dialog.
- Neural models require one-time download (10–30 MB) if not pre-bundled; mitigated by high-speed CDN routing and instant OpenCV fallback if offline.
