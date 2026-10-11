# cv/motion — Production Wiring & Dormant Features

Status per ADR 0006, ADR 0007, and CV_RELIABILITY_UPGRADE_PLAN Phase C.

## Production Status (v2.2.9)

- **Wired in Production (Alpha Workspace)**:
  `FlowEstimator` and `FrameSynthesizer` are actively wired into `media/MotionLabController`
  for the **Motion Lab (14th Workspace)**, supporting 2× and 4× pairwise optical flow
  frame synthesis under native OpenCV execution (DIS and Farnebäck algorithms).
  Execution is governed via `CvEngine` on the `VIDEO` lane with pairwise flat memory
  management, disk scratch cleanup, and direct export to MediaStore (`Movies/VeilFrame`).

- **Dormant (Experimental / Unconsumed)**:
  `FlowConsistency`, `FlowInterpolator`, and `MotionDetector` remain compiled into release
  builds but currently have zero production consumers. They are preserved for future
  flow-guided temporal denoise and advanced keyframe interpolation pipelines.

## Contract Before Additional Wiring (Non-Negotiable)

1. Submit through `CvRuntime.engine` on the **VIDEO lane** with memory
   estimates (`CvRuntime.estimateBytes`) and a watchdog timeout.
2. Per-frame callers must use `ctx.ensureActive()` checkpoints and honor
   `ThermalGovernor` (skip frames when throttled, stop when critical).
3. Instrumented golden tests on arm64 + x86_64 (extend `CvNativeSubsystemTest`)
   and telemetry visible in `cv_runs.jsonl`.
4. Frame budgets: flow estimation ≤ 40 ms/frame at 480p working resolution on
   a mid-tier device; synthesis runs offline (export), never in preview.
