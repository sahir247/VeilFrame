# cv/motion — DORMANT (experimental)

Status per ADR 0006 / CV_RELIABILITY_UPGRADE_PLAN Phase C.

`FlowEstimator`, `FlowConsistency`, `FlowInterpolator`, `FrameSynthesizer`,
`MotionDetector` (~970 LOC) are **compiled into release builds but have zero
production consumers**. They were built ahead of the Video Cleaner
"flow-guided temporal denoise / frame interpolation" roadmap item.

Contract before any wiring (non-negotiable):
1. Submit through `CvRuntime.engine` on the **VIDEO lane** with memory
   estimates (`CvRuntime.estimateBytes`) and a watchdog timeout.
2. Per-frame callers must use `ctx.ensureActive()` checkpoints and honor
   `ThermalGovernor` (skip frames when throttled, stop when critical).
3. Instrumented golden tests on arm64 + x86_64 (extend `CvNativeSubsystemTest`)
   and telemetry visible in `cv_runs.jsonl`.
4. Frame budgets: flow estimation ≤ 40 ms/frame at 480p working resolution on
   a mid-tier device; synthesis runs offline (export), never in preview.

Until then, treat this package as experimental: do not call it from
controllers, and do not "fix" its absence from production paths.
