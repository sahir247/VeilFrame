# ADR 0006: CV Subsystem Governance & Dynamic Memory Policy

## Status

Status: Accepted
Implementation Baseline: `v2.2.9` (branch `main`)
Date: October 2026
Implements: `docs/CV_RELIABILITY_UPGRADE_PLAN.md` (Phases A–C) and
`docs/UPSCALER_STABILITY_FIX_PLAN.md` (F1–F11)
Related: ADR 0004 (workspace architecture), ADR 0005 (expressive design system)

---

## 1. Context

The audit found a paradox: VeilFrame's CV core (`CvEngine`, `CvDispatcher`
lanes, `CvMemoryManager` admission, `MatPool`) was excellent, contract-tested
code with **zero production consumers**, while the four live CV chains ran on
raw `Dispatchers.IO` with unadmitted allocations, silent catch-alls, native-Mat
leaks on error paths, a per-frame JPEG round-trip in the document viewfinder,
and a native-library bootstrap that depended on a ContentProvider which **did
not exist**. The AI upscaler crashed on large sources (unbounded heap: full-res
decode + full-size output compose) and lagged the entire device (CPU-only ONNX,
20–30 saturated threads, PNG churn, no thermal governance).

**Target fleet (binding product constraint):** devices with **6–16 GB TOTAL
RAM, where AVAILABLE memory fluctuates** with the user's other apps. Static
conservative profiles would waste flagship headroom; static aggressive profiles
would OOM a busy 6 GB device. Therefore every memory/compute decision must be
**dynamic**: re-evaluated per job against *live* headroom.

## 2. Decisions

### 2.1 Single native bootstrap — `CvRuntime` + `OpenCVInitProvider`
OpenCV loads via a manifest-registered ContentProvider (`initOrder=100`)
before `Application.onCreate`; `CvRuntime` records the outcome
(`isNativeAvailable` / `initError`) and every CV entry point calls
`requireAvailable()`, which throws a typed `CvNativeUnavailableException`
(mapped to `CvErrorCode.NATIVE_UNAVAILABLE`). UnsatisfiedLinkError can no
longer escape into crashes or silent catch-alls.

### 2.2 Governed execution for heavy one-shot CV work
Background removal, image-quality analysis, document auto-crop, and document
enhance/filter submit through `CvRuntime.engine` (`CvEngine` → INTERACTIVE
lane, memory reservation against `MemAvailable × 0.25`, watchdog timeouts
15–30 s, structured `CvResult`). Camera frame analysis stays on a shared
daemon executor (per-frame lane churn is not worth it) and QR keeps its
frame-token pipeline — both gated by `CvRuntime`. A CI ratchet
(`scripts/check_cv_governance.sh`) fails the build if a new consumer touches
`cv.*` without governance.

### 2.3 Honest-failure policy
Verification surfaces fail **closed** (existing quality-gate doctrine);
generation surfaces fail **honest**: no fabricated scores, no source image
disguised as a cutout, no silent no-ops. Every failure carries a typed code,
a user-visible reason, and a telemetry record (`cv_runs.jsonl`, on-device only).

### 2.4 Dynamic memory & compute policy (the 6–16 GB rule)
All budgets are computed **per job, at job start**, from live signals — never
hardcoded tiers alone:
- **In-RAM output budget** = `min(45% app heap, 220 MB, ½ × MemAvailable)`
  (floor 48 MB). A 16 GB flagship with 8 GB available gets the full 220 MB;
  a 6 GB device busy with other apps (≈1.2 GB available) automatically gets a
  ~600 MB→220 MB→smaller effective budget and streams earlier.
- **Tile profile** = static heap tier (largeMemoryClass / isLowRamDevice)
  × live adjustment: `MemAvailable < 1.5 GB` → chunk steps down 25% (snapped
  to 32 px, floor 128) and workers → 1; `MemAvailable ≥ 4 GB` with a 512 MB+
  heap → workers 2 (never more: ORT intra-ops already parallelize).
- **Beyond the in-RAM budget**: outputs stream — tiles region-decode from the
  source Uri (F1) and compose band-by-band into a PNG file via
  `StreamingPngWriter` (F2); the full-size bitmap never exists. Hard cap:
  200 MP output (honest refusal above it).
- **Thermal/battery overlay** (`ThermalGovernor`, ref-counted across
  workspaces): MODERATE+ → efficiency profile; CRITICAL+ → cooperative abort
  with typed `ThermalShutdownException`; battery <20% unplugged → workers 1;
  document viewfinder drops every other frame when throttled and stops when
  critical.
- **Escalation ladder on OOM**: halve tiles (floor 128 px) → retry once →
  typed failure → user-chosen degrade (Lanczos / 2× / smaller source). The
  app never crashes and never fakes a result.

### 2.5 Accelerator ladder (upscaler)
ONNX sessions: NNAPI (API 27+, vendor-chosen GPU/DSP delegation) → XNNPACK
(2 threads) → bounded CPU (intra ≤ min(4, cores·3/4), interOp 1, SEQUENTIAL).
Choice cached per model in SharedPreferences; `lastBackend` surfaces in status
lines and diagnostics. Tile workers run on a dedicated executor with
`THREAD_PRIORITY_BACKGROUND` so the compositor keeps the fast cores — the
direct fix for "the whole phone lags".

### 2.6 Foreground service for long jobs
AI upscale jobs run under `UpscaleForegroundService` (dataSync, silent
low-priority notification with cooperative Cancel): immune to OEM
phantom-process kills, correctly scheduled, and honest about progress when
the user navigates away. `START_NOT_STICKY` — a killed job is surfaced, never
blindly re-run against a possibly-dead Uri.

### 2.7 Dormant-portfolio policy
Code compiled into release builds but wired to nothing is a liability.
`cv/motion/**`, `SmartAutoCrop`, `NoiseReducer`, `ColorEngine`,
`TemplateMatcher`, and `QrRecoveryEngine` are declared **DORMANT** with an
explicit wiring contract (`cv/motion/README.md`): VIDEO-lane submission,
ensureActive checkpoints, ThermalGovernor compliance, instrumented golden
tests, telemetry — or they stay out of controllers. QA instruments
(`QrReliabilityGate`, `QrStressMatrix`, `QrCodeDetectorDiagnostic`) remain
test-only.

## 3. Consequences

**Positive**
- No crash class from unbounded memory: source, tiles, output, and refinement
  all have dynamic budgets with typed refusals/degrades.
- Device-wide lag removed at the root: bounded threads at background priority,
  accelerator offload, thermal governor, no per-frame JPEG churn.
- Failures are truthful and diagnosable (typed codes + on-device telemetry).
- Regressions are gated in CI (governance ratchet).

**Negative / Risks**
- NNAPI behavior is vendor-dependent; a cached bad choice persists until app
  cache is cleared (benchmark-gated selection is the documented follow-up).
- Band-streaming writes outputs to cache before save/share (extra I/O;
  mitigated by byte-copy on export — no re-encode).
- Watchdogs are cooperative: a wedged native call inside a single OpenCV/ORT
  function still blocks its lane until the call returns (lane isolation
  contains the blast radius).

**Neutral**
- `MemInfoMemoryProbe` reads `/proc/meminfo` per job (≈1 ms, off main thread
  by policy — all budget computations run on IO dispatchers).

## 4. Verification gates (release-blocking)

1. `./gradlew :app:assembleDebug testDebugUnitTest` + `bash android/scripts/check_cv_governance.sh`.
2. Memory matrix (Profiler attached): 12/24/48/108 MP × {2×,4×,8×} on a 6 GB
   and a 12 GB device — zero OOM, streamed outputs byte-identical to in-RAM
   compose for the same fixture (golden test), refusals only above 200 MP.
3. Thermals: `adb shell cmd thermalservice override-status {2,4}` → efficiency
   profile / honest stop; viewfinder frame-skip observable in logs.
4. FGS: job survives app-backgrounding; notification Cancel stops inference
   within 200 ms; no `ForegroundServiceStartNotAllowedException` in logs.
5. Fault injection: `CvRuntime` unavailable → all four chains show honest
   states; `cv_runs.jsonl` records every job and watchdog event.
