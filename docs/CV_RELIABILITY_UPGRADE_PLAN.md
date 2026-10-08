# VeilFrame CV Subsystem — Reliability & Dependability Upgrade Plan (ALL cv/*)

> Scope: **every file in `com.veilframe.app.cv.**`** (34 files, ~6,200 LOC, 15 packages) **plus all CV consumers**: `document/DocumentScannerController`, `document/DocumentSession`, `media/BackgroundRemoverController`, `media/ImageQualityController`, `qr/scanner/QrScanner`, `qr/decoder/WeChatQrDecoder`, `VeilFrameApplication`, `ui/views/DocumentQuadOverlayView`.
> Method: full audit at `main@8f090a7` — every file inventoried, dependency graph resolved (wired vs dormant), leak/threading/init/lifecycle patterns grepped and read. Evidence cited per finding.
> Companion: `UPSCALER_STABILITY_FIX_PLAN.md` (F-series) — shared infrastructure items are cross-referenced, not duplicated.

---

## 1. Subsystem map — what is actually wired

| Status | Files | Meaning |
|---|---|---|
| **LIVE (4 chains)** | `cv/document/DocumentScanner` ← DocumentScannerController/Session/QuadOverlay · `cv/segmentation/BackgroundRemover` + `MaskOps` ← BackgroundRemoverController · `cv/analysis/ImageQualityAnalyzer` + `BlurAnalyzer` + `EdgeDetector` + `QualityScore` ← ImageQualityController · `cv/qr/WeChatQrEngine` + `Geometry` ← QrScanner/WeChatQrDecoder/Application (MLKit fallback) | Production paths users hit today |
| **LIVE-SUPPORT** | `cv/geometry/QuadDetector`, `PerspectiveCorrector` (via DocumentScanner) · `cv/preprocess/Preprocessor`, `cv/sharpen/SmartSharpener` (via DocumentScanner.enhance) · `cv/core/BitmapBridge`, `Types`, `CvContracts` (conversion/error types) | Reachable from live chains |
| **GOVERNANCE — BUILT, NEVER WIRED** | `cv/core/CvEngine`, `CvDispatcher`, `CvMemoryManager`, `MatPool`, `SizeClass` | **Zero production consumers** (only self-references + JVM tests). Lanes, admission control, reservations, pooling all dormant |
| **DORMANT FEATURES** | `cv/crop/SmartAutoCrop` · `cv/denoise/NoiseReducer` · `cv/color/ColorEngine` · `cv/template/TemplateMatcher` · `cv/qr/QrRecoveryEngine` · **all of `cv/motion`** (`FlowEstimator`, `FlowConsistency`, `FlowInterpolator`, `FrameSynthesizer`, `MotionDetector` ≈ 970 LOC) | Compiled into every APK, exercised only by tests |
| **QA TOOLING (dormant by design)** | `cv/qr/QrReliabilityGate`, `QrStressMatrix`, `QrCodeDetectorDiagnostic` | Validation instruments — keep, but gate out of release dex if unused (R8 will strip; verify) |

**The headline**: the subsystem's *quality core* (laned dispatcher, memory admission, Mat pooling, structured `CvResult` failures, fatal-error rethrow policy) is excellent — and **none of it runs in production**. Every live chain bypasses it with raw `Dispatchers.IO`, unadmitted allocations, and controller-level `catch (Throwable)` that hides failures. Dependability work is therefore mostly *wiring and honesty*, not rewriting.

## 2. Findings register (evidence-based)

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| **CV-1** | **Critical** | Governance core unwired: no CV job passes through `CvEngine.submit`/`CvDispatcher` lanes/`CvMemoryManager.reserve`/`MatPool` | `grep -rl CvEngine\|CvDispatcher` → only `cv/core/*` themselves; all consumers use `scope.launch(Dispatchers.IO)` (BackgroundRemoverController:93/130/264, ImageQualityController:65/90, DocumentScannerController:431–620) |
| **CV-2** | **Critical** | **`OpenCVInitProvider` does not exist.** Comments claim "the OpenCVInitProvider loads the native library at app start"; no such class in tree, nothing in `AndroidManifest.xml`. Native load depends solely on `runCatching { OpenCVLoader.initLocal() }` inside `WeChatQrEngine.install()`; if it fails, OpenCV is never loaded and every non-QR CV path hits `UnsatisfiedLinkError` | WeChatQrEngine.kt:34,166–173; `find java -name '*InitProvider*'` → empty; manifest grep → empty |
| **CV-3** | **Critical** | **Dishonest silent fallbacks** (contradict the repo's own fail-closed quality-gate doctrine): BackgroundRemover `catch (e: Throwable) → cutoutBmp = srcBmp` returns the **original, unremoved image as the result**; ImageQuality `catch (Throwable)` **fabricates score 82 "Good Quality"** and pre-seeded fake metrics ("142.5", "1.8 dB") can reach the UI | BackgroundRemoverController.executeRemoval catch block; ImageQualityController.runDiagnostics catch block |
| **CV-4** | High | **Native Mat leaks on error paths** (release not in `finally`): BackgroundRemoverController (`srcMat`, plus `removalResult.output/mask` if `toBitmap` throws) · DocumentScannerController.analyzeFrameForDocument (`srcMat` when `findCorners` throws — *per camera frame*) · capture-path re-crop (`srcMat`, ~line 500) · QrScanner.yPlaneToMat (`full` if `Imgproc.resize` throws) | code reads at cited lines; primitives themselves are clean (try/finally counts match in all 10 heavy files) |
| **CV-5** | High | **Per-frame JPEG round-trip in document viewfinder**: NV21 copy → `YuvImage.compressToJpeg(85)` → decode → Bitmap → `BitmapBridge.toMat` — at full camera resolution, every frame, single-thread executor. Latency (quad overlay lags), heat, GC churn. An efficient Y-plane→Mat path already exists in the same codebase (QrScanner:229) and `QuadDetector` works on grayscale anyway | DocumentScannerController.imageProxyToBitmap:785–803 + analyzeFrameForDocument:338 |
| **CV-6** | High | **Silent total-failure loops**: `catch (_: Throwable) {}` with no logging in capture path (user taps re-crop → nothing happens, nothing logged); analyzer catch clears overlay silently — with CV-2 active, the app burns battery running JPEG conversions per frame to produce nothing | DocumentScannerController:~503, 364–368 |
| **CV-7** | Medium | **Executor leak**: `cameraExecutor = Executors.newSingleThreadExecutor()` per controller instance; no `shutdown()` anywhere. Theme change → `activity.recreate()` (ThemeSettingsManager.pendingRecreate) → new controller → orphan non-daemon thread accumulates per recreate | DocumentScannerController:79; grep shutdown → none |
| **CV-8** | Medium | **Unsampled full-res decodes** at every CV entry (same class as upscaler B1): `BitmapFactory.decodeStream(stream)` raw in BackgroundRemover/ImageQuality `handleImageSelected`; 48 MP photo = 192 MB heap before analysis starts; no `CvMemoryManager.workingResolution` consulted | BackgroundRemoverController:93–100; ImageQualityController:65–72 |
| **CV-9** | Medium | `BitmapBridge.toMat` leaks the intermediate ARGB copy when source config is unsupported (`bitmap.copy(...)` result never recycled); `createMatFor` fallback assumes 4 channels for unknown configs (e.g. HARDWARE bitmaps throw in `bitmapToMat` — HARDWARE previews from the expressive R3 plan would crash here) | BitmapBridge.kt:33–45 |
| **CV-10** | Medium | **No watchdog/heartbeat/timeout** on any live chain: a hung GrabCut/warp/native wedge blocks the single camera executor or an IO coroutine forever; no job telemetry; diagnostics panels are static strings | absence across consumers; `CvContext.timed()` exists but unused |
| **CV-11** | Low | `SmartAutoCrop` has one `try` without `finally` (3:2 ratio — only file in the set); dormant, but must be fixed before any wiring (ADR 0004 lists Smart Crop as an Image Studio feature) | grep table §1 |
| **CV-12** | Low | **Docs drift**: `docs/cv/VEILFRAME_CV_ENGINE.md` and class KDoc describe CvDispatcher as "the only place CV work runs" — factually false today. Contract tests (14 JVM files) test the governance core in isolation, giving false confidence that production is governed | CvDispatcher.kt header vs §1 evidence |

## 3. Upgrade program

### Phase A — Ship blockers: make the live chains dependable *(est. 4–5 days)*

**A1. Real native bootstrap (CV-2).** Add the missing `OpenCVInitProvider` (androidx-startup `Initializer<Unit>` or a plain ContentProvider registered in the manifest) that calls `OpenCVLoader.initLocal()` **before any workspace can run**, records the outcome in a new `CvRuntime` object (`isAvailable`, `initError`, `loadedAtMs`). Every CV entry point checks `CvRuntime.requireAvailable()` → typed `CvResult.Err(NATIVE_UNAVAILABLE)` instead of `UnsatisfiedLinkError`. `WeChatQrEngine.install` keeps its belt-and-braces init but defers to `CvRuntime`.
*Accept: manifest-registered provider; killing initLocal (debug flag) → all four chains show honest "CV engine unavailable" UI, zero crashes, QR falls back to MLKit as designed.*

**A2. Wire the governance core (CV-1, CV-8, CV-10).** Introduce `CvRuntime.engine` (app-scoped singleton `CvEngine` with real `MemoryProbe` reading `ActivityManager.MemoryInfo` + `/proc/meminfo`). Migrate the four live chains:
- BackgroundRemoverController.executeRemoval → `engine.submit("bg-remove", INTERACTIVE, memoryEstimate = srcBytes×6) { ctx → … }`
- ImageQualityController.runDiagnostics → `engine.submit("quality-analysis", INTERACTIVE, …)`
- DocumentScanner capture/re-crop/enhance → `engine.submit(…, INTERACTIVE)`; viewfinder analysis → dedicated path (A4)
- QrScanner decode → keep its frame-token architecture but execute `WeChatQrEngine.detect` via `engine.submit("qr-detect", VIDEO)` lane when queue depth allows (fallback: current executor if lane saturated — scanner latency budget 100 ms)
Estimates come from `CvMemoryManager.costEstimate`; rejections surface as `CvErrorCode.OUT_OF_MEMORY` → honest dialog with suggested tier (the message already exists in `CvEngine.submit`). Long ops sample `ctx.ensureActive()`; `CvJob.cancel()` bound to workspace exit + user Cancel.
*Accept: no `Dispatchers.IO` remains around CV primitives in the 4 consumers (grep gate in CI); admission rejection produces UI message, not crash; cancel mid-GrabCut returns within 200 ms.*

**A3. Honest failures everywhere (CV-3, CV-6).** Ban result-fabricating catch blocks:
- BackgroundRemover: on `CvResult.Err` → keep source visible, badge "Removal failed — <reason>", retry + "Save original" as *explicit user choices*. Never present the source as a cutout.
- ImageQuality: on Err → score field shows "—", label "Analysis unavailable", export disabled; delete pre-seeded fake metric strings.
- DocumentScanner: every `catch (_: Throwable)` gains `Log.w(TAG, …, e)` + one-shot user-visible status ("Detection unavailable"); capture failures re-enable the shutter with a toast.
- Policy note in ADR: fail-closed for *verification* (existing quality-gate doctrine) and fail-*honest* for *generation* (never silently substitute output).
*Accept: fault-injection debug switch (`vf_cv_force_failure`) exercises all four chains → every path shows truthful UI; unit tests assert no fabricated constants.*

**A4. Leak & frame-path fixes (CV-4, CV-5, CV-7, CV-9).**
- All controller Mat lifecycles → `try { … } finally { mat.release() }` (or a tiny `Mat.use {}` extension in `cv/core`); QrScanner.yPlaneToMat resize path wraps `full.release()` in finally.
- Document viewfinder: replace the JPEG round-trip with direct **Y-plane → CV_8UC1 Mat** (port QrScanner's `yPlaneToMat`, incl. 1024 cap) and feed grayscale straight to `QuadDetector` (it grayscales internally today — pass-through saves a cvtColor); add frame-token gating (single-flight) like QrScanner; drop per-frame Bitmap allocation entirely.
- `cameraExecutor`: single app-scoped executor in `CvRuntime` (or `shutdown()` in a controller `release()` hooked to workspace exit + activity destroy).
- `BitmapBridge.toMat`: recycle the intermediate copy; explicit `HARDWARE`-config guard with typed error (protects the expressive preview strategy R3).
*Accept: Profiler native-heap flat across 5 min viewfinder + 20 forced-failure captures; viewfinder CPU/frame −50% vs JPEG path (systrace); no thread growth across 5 theme-change recreates.*

### Phase B — Hardening the whole subsystem *(est. 4–6 days)*

**B1. Watchdog & timeouts (CV-10).** `CvDispatcher` gains per-job `timeoutMs` (default: INTERACTIVE 15 s, VIDEO 3 s/frame, BACKGROUND 120 s; GrabCut/warp get op-specific budgets). Timeout → `CvErrorCode.TIMEOUT`, lane permit released, heartbeat log. Camera lanes never block > 1 frame budget: analyzer drops frames when a job is in flight (existing KEEP_ONLY_LATEST + token gate).
**B2. Telemetry (private, on-device).** Every `CvJob` completion appends to `cache/cv_runs.jsonl`: {name, lane, tier, estimateBytes, ms, poolHits/Misses, thermalStatus, outcome, errorCode}. Diagnostics panels (upscaler's exists; add to scanner/quality/bg-remover debug sheets) render `engine.stats()` + last-50 runs + "Copy report". Zero network — privacy brand promise.
**B3. Native-memory instrumentation.** Debug builds: `Debug.getNativeHeapAllocatedSize()` sampled around jobs; `MatPool` leak canary (finalizer-registered wrapper logging unreturned leases > 30 s); strict-mode penaltyDeathForLeakedClosables off (Mats aren't Closeable) but add lint rule **"no `Mat(` constructor outside `cv/` package"** and **"every `cv/` Mat either pooled or released in finally"** (custom lint or detekt rule; SmartAutoCrop CV-11 fixed first).
**B4. Thermal & battery lane governor (shared with upscaler F11).** One `ThermalGovernor` in `CvRuntime`: MODERATE → VIDEO lane 1 slot + INTERACTIVE estimates ×0.7; SEVERE → pause BACKGROUND lane, video analysis to 2 fps; CRITICAL → cancel non-user-visible jobs. Battery < 15% unplugged → background CV deferred.
**B5. Sampled-decode policy at every image entry (shared with upscaler F1).** Single `MediaDecoder.decodeSampled(uri, purpose)` using `CvMemoryManager.workingResolution(purpose)` — ANALYSIS ≤1600 px edge, STANDARD tier-based, FULL only with admission. Adopted by BackgroundRemover, ImageQuality, DocumentScanner file-import, Upscaler (F1), Image/Video Cleaner previews. Kills the CV-8 class systemically.
**B6. Cancellation coverage inside primitives.** Audit long loops in live-reachable primitives (`MaskOps` morphology passes, `Preprocessor.denoise`, `QuadDetector` contour scan, `ImageQualityAnalyzer` stages) for `CvContext.ensureActive()` checkpoints at row/tile granularity; add where missing (they already accept no context today — thread an optional `CvContext?` param, defaulting null for test compatibility).
**B7. Contract-test expansion.** The 14 JVM contract tests stay; add: fault-injection tests for A3 paths (Robolectric), `CvRuntime` bootstrap failure test, dispatcher timeout/lane-starvation tests, and instrumented arm64+x86_64 runs of the 4 live chains end-to-end on synthetic fixtures (extend `CvNativeSubsystemTest`).

### Phase C — Portfolio decisions: dormant code must be *dependable or gone* *(est. 2–3 days + product calls)*

**C1. `cv/motion` (≈970 LOC, 0 consumers).** Decide per ADR roadmap: either wire `FlowEstimator/FrameSynthesizer` into Video Cleaner as an opt-in "flow-guided temporal denoise / interpolation" feature **through CvEngine's VIDEO lane with full B-phase hardening**, or move the package to an `experimental` source set excluded from release builds until needed. Shipping untested-in-production native-heavy code in every APK is a liability (APK size, attack surface, maintenance).
**C2. Feature primitives awaiting their ADR 0004 homes.** `SmartAutoCrop` → Image Studio "Smart Crop" (fix CV-11 first; add instrumented golden tests). `NoiseReducer`/`ColorEngine` → Image Studio enhance stack (currently Skia-only). `TemplateMatcher` → Provenance/QR QA. `QrRecoveryEngine` → QR Studio "repair" feature. Each wiring PR must go through `CvEngine.submit` with admission + telemetry from day one (no more direct-dispatcher regressions — CI grep gate from A2).
**C3. Docs truthfulness (CV-12).** Update `docs/cv/VEILFRAME_CV_ENGINE.md` + KDocs to describe the *wired* architecture; add an ARCHITECTURE.md CV section diagram: consumers → CvRuntime(engine, thermal, telemetry) → lanes → primitives → MatPool. Record this program as **ADR 0006 "CV subsystem governance: wire, harden, honest-fail"**.
**C4. QA tooling.** Keep `QrReliabilityGate/StressMatrix/QrCodeDetectorDiagnostic` as validation instruments; confirm R8 strips them from release dex (they're only reachable from tests today) or move to `androidTest` source set.

## 4. Per-chain acceptance criteria (release gate)

| Chain | Gate |
|---|---|
| **Document Scanner** | Viewfinder: no JPEG path, ≤35 ms/frame analysis CPU, quad overlay latency < 120 ms, 5-min session native-heap flat; capture → warp → enhance via CvEngine with admission; every failure visible + logged; executor thread count constant across 5 recreates |
| **Background Remover** | 12 MP image on 6 GB device: admitted, < 4 s GrabCut pass or honest timeout; failure never yields a disguised source image; split-view spring/haptics unaffected |
| **Image Quality** | No fabricated metrics under any fault injection; ≥24 MP image analyzed via tier-sampled working resolution; report export matches on-screen values exactly |
| **QR Studio scan** | WeChat primary + MLKit fallback unchanged in behavior; decode lane never starves preview (frame drop, not queue growth); OpenCV-unavailable device → instant clean fallback, zero toasts spam |
| **Cross-cutting** | `grep -rn "Dispatchers.IO" <consumers>` shows no CV-adjacent hits; `cv_runs.jsonl` records every job; reduced-motion & thermal governors tested per upscaler plan §2 acceptance |

## 5. Sequencing

| PR | Contents | Est. | Blocks release? |
|---|---|---|---|
| `fix(cv): native bootstrap + CvRuntime` | A1 | 1 d | **Yes** |
| `fix(cv): honest failures + leak finally-discipline` | A3, A4 (leaks, executor, BitmapBridge) | 1–1.5 d | **Yes** |
| `feat(cv): wire CvEngine governance into 4 live chains` | A2 | 1.5–2 d | **Yes** |
| `perf(cv): direct Y-plane document analyzer` | A4 frame path | 0.5–1 d | Recommended |
| `feat(cv): watchdog, telemetry, thermal, sampled decode` | B1–B5 | 2–3 d | Recommended |
| `test(cv): cancellation, contracts, instrumented matrix` | B6–B7 | 1–2 d | Follow-up |
| `chore(cv): dormant portfolio decisions + ADR 0006 + docs` | C1–C4 | 1 d + product calls | Follow-up |

Verification per PR: JVM contract suite (`testDebugUnitTest`) + instrumented `CvNativeSubsystemTest` on arm64 device/emulator + Profiler captures attached (Java heap, **native heap**, threads, thermal) + fault-injection screen recording.

## 6. Relationship to the upscaler plan

Shared infrastructure (build once, use everywhere): `CvRuntime` (bootstrap + thermal + telemetry host) ≙ upscaler F11/F14 · `MediaDecoder.decodeSampled` ≙ F1 · `CvEngine` admission ≙ F3/F4 · watchdog ≙ F13 · foreground-service pattern ≙ F6 (upscaler jobs additionally get FGS; interactive CV jobs do not). The upscaler's ONNX stack (`app/upscale/inference`) stays separate from OpenCV governance but adopts the same telemetry schema and thermal governor.
