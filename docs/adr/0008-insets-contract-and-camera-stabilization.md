# ADR 0008: Real-Time Edge-to-Edge System Insets & Viewport Stabilization Architecture

## Status

Status: Accepted  
Implementation Baseline: `v2.2.9` (branch `main`)  
Date: October 2026  
Related: ADR 0004 (UI Redesign), ADR 0005 (M3 Expressive), ADR 0006 (CV Governance)

---

## 1. Context

Across complex multi-workspace Android applications with rich edge-to-edge layouts, two recurring structural failure modes emerge:
1. **System Insets Incoherence**: Ad-hoc `android:fitsSystemWindows="true"` declarations on individual nested layouts fail when dynamic view switching occurs in a single-activity architecture (`MainActivity`). Symptoms included export sheets sliding beneath the status bar, bottom action docks colliding with the 3-button navigation bar or IME (keyboard), and truncated buttons on compact devices.
2. **CameraX Lifecycle & Viewfinder Instability**:
   - Simultaneous frame analysis and full-resolution shutter capture competed for memory and CPU threads, risking out-of-memory (OOM) faults during 48 MP+ sensor captures.
   - Independent frame-by-frame quad corner detection produced noticeable visual jitter and strobing overlays in document scanning modes.

---

## 2. Decision

### 2.1 Centralized `WorkspaceInsets` Contract
Implement a single authoritative window insets controller: `com.veilframe.app.ui.insets.WorkspaceInsets`.
- **Single Listener**: `MainActivity` registers one `ViewCompat.setOnApplyWindowInsetsListener` at the root decor container.
- **Unified Metric**: Evaluates system bars and IME simultaneously:
  $$\text{top} = \text{insets.getInsets}(\text{WindowInsetsCompat.Type.statusBars()}).\text{top}$$
  $$\text{bottom} = \max(\text{insets.getInsets}(\text{navigationBars()}).\text{bottom},\; \text{insets.getInsets}(\text{ime()}).\text{bottom})$$
- **Idempotent Application**: Absolute top and bottom paddings are dispatched to the active workspace container, ensuring toolbars clear the camera cutout and the floating action dock floats cleanly above gesture pills or 3-button navigation bars.
- **Elimination of Hacks**: Removed conflicting `fitsSystemWindows` and hardcoded status bar offsets across all 37 layouts.

### 2.2 Single-Flight Camera Capture Ownership
In `DocumentScannerController`:
- **Atomic Concurrency Gate**: An `isCapturing` flag guarantees frame analysis and capture never overlap. Real-time preview analysis is temporarily suspended while the CameraX `ImageCapture` pipeline takes ownership of the execution thread.
- **OOM Defense in JPEG Conversion**: Added structured try-catch memory guards around both the initial JPEG decode and the subsequent rotation matrix copy, failing gracefully with an honest user message rather than crashing the VM.

### 2.3 Temporal Quad Stabilization (`QuadStabilizer`)
To eliminate corner strobing during document detection, implement `com.veilframe.app.cv.document.QuadStabilizer`:
- **Exponential Moving Average (EMA)**:
  $$\mathbf{P}_{\text{filtered}}^{(t)} = \alpha \mathbf{P}_{\text{detected}}^{(t)} + (1 - \alpha) \mathbf{P}_{\text{filtered}}^{(t-1)} \quad (\alpha = 0.35)$$
- **Corner Distance Matching**: A distance threshold (48 px) matches corresponding corners across frames regardless of contour vertex ordering.
- **Hysteresis & Liveness**:
  - Requires 4 consecutive detections before transitioning from `SEARCHING` to `TRACKING` and `STABLE` (`READY`).
  - Tolerate single-frame dropouts without resetting the smoothed contour.
- **Haptic & Guidance Synchronization**: The transition to `READY` triggers an M3 Expressive haptic tick and updates viewfinder instructions from "Align document" $\to$ "Hold still" $\to$ "Ready — tap shutter".

---

## 3. Consequences

### Positive
- Edge-to-edge layout renders consistently across phones, tablets (600dp/840dp), foldables, gesture navigation, and 3-button navigation.
- Floating action docks and bottom sheets never collide with the system IME or navigation pill.
- Viewfinder quad overlay is temporally stable and jitter-free, increasing user capture confidence.
- Camera captures are memory-safe and isolated from analysis thread churn.

### Negative / Trade-offs
- EMA introduces a slight smoothing latency (~2 frames), which is imperceptible to users and beneficial for scan stability.
