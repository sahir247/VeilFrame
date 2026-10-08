# VeilFrame v2.3.0-rc1 — Final Implementation Report

**Branch:** `release/v2.3.0-expressive-rc1` (7 commits on top of `main@8f090a7`)
**Scope delivered:** ① full M3 Expressive UI/UX migration (release candidate) · ② CV subsystem Phase A **including A2 engine-governance wiring** (implemented) · ③ AI Upscaler P0 memory correctness **and P1 lag program** (implemented) · ④ two evidence-based engineering plans · ⑤ repair of three pre-existing compile breaks on `main`.
**Durable artifacts:** because this analysis workspace has a snapshot size cap that can drop the repo's `.git`, the full commit series is also exported as `veilframe-m3-expressive/patches/*.patch` (7 files) and `veilframe-m3-expressive/rc1-delta.bundle` (apply with `git pull <bundle> release/v2.3.0-expressive-rc1` onto a checkout of `8f090a7`).

| Commit | What | Files |
|---|---|---|
| `2a0e7f8` | feat(ui): M3 Expressive design system v3.0 "Quiet Intensity" | 53 (4 new, 2 deleted, 47 modified) |
| `0d0a000` | docs: upscaler + whole-CV reliability plans | 2 |
| `3f2a6e4` | fix(cv): Phase A + A2 — bootstrap, honest failures, leaks, engine governance | 16 (2 new) |
| `17b650f` | fix(upscaler): P0 memory correctness | 6 (2 new) |
| `e98ecb9` | docs: rc1 implementation report (first cut) | 1 |
| `18e9cd5` | perf(upscaler): P1 lag program — EP ladder, threads, thermal, hot loops | 6 (1 new) |
| *(this)* | docs: final report update | 1 |

---

## Part 1 — UI/UX: M3 Expressive migration (the "alive & expressive" RC)

### 1.1 Theme foundation
**Changed:** Material Components `1.12.0 → 1.14.0`; `Theme.VeilFrame` re-parented to `Theme.Material3Expressive.DayNight.NoActionBar`; added `androidx.dynamicanimation`; aligned core-ktx 1.16.0 / appcompat 1.7.1 / constraintlayout 2.2.1; deleted the hand-copied `values/motion_tokens.xml`.
**Why:** 1.14.0 is the first stable Views release with the full expressive system; the token file duplicated values the library now provides (drift hazard).
**How it fixes it:** every expressive component style, spring slot, and emphasized text attribute resolves from the library; the theme parent now drives the whole app's physics and component language.

### 1.2 Color tokens — monochrome-first, roles everywhere
**Changed:** day+night palettes extended: full 5-step surface-container ladder **in light mode too**, tertiary editorial voice for all 5 palettes, outline roles, `vf_scrim`; all 27 legacy `vf_*` names preserved (25 Kotlin references intact).
**Why:** widgets were pinned to raw `@color/vf_*` — Dynamic Color and palette switching were partially bypassed; light mode had no containment hierarchy; no tertiary role existed.
**How it fixes it:** widgets consume `?attr/color*` roles; the sweep stripped the pins from 177 button blocks, so Material You, 5 palettes, AMOLED, and semantic status colors all flow through one map. Monochrome stays emotionally rich through luminance choreography (page=Low → cards=Container → dock=High → hero=Highest) — the research-backed "calmer expressive" register appropriate for a forensics product.

### 1.3 Component sweep (all 37 layouts)
**Changed:** 278 button remaps to expressive role styles (Execute=XL pill, CTA=L, Action=tonal M 48dp, Utility=outlined S, Quiet=text, icon buttons 40/48dp); all `app:cornerRadius` pins deleted (10 conflicting radii → style-driven shape with library press/select morphs); 116 icon buttons resized via overlays (fixes 38dp targets); 31 `iconSize` pins removed; 10 progress indicators → thick 8dp (determinate) / **wavy** (indeterminate); 12 sliders de-pinned onto expressive Medium (theme default — library default Xsmall deliberately overridden); bottom-nav custom tint selector **deleted** (expressive 64dp bar, 56dp pill indicator, secondary active labels, horizontal items ≥600dp); dock → 32dp `ContainerHigh`; hero → 48dp-top `Highest` card; badges → pills; 9/10sp text eliminated (11sp floor, 27 sites); 24 chrome headers → `TitleSmallEmphasized` title-case (telemetry keeps terminal caps).
**Why:** research: size/shape/color contrast → key elements spotted up to 4× faster; wavy/thick progress makes waits feel shorter; old shell had chaotic radii, sub-target controls, static 4dp bars for multi-minute jobs.
**How it fixes it:** one shape scale + one size ladder; the primary action per screen is physically the largest element; waiting states are alive.

### 1.4 Motion — real springs
**Changed:** new `ui/motion/VfSprings.kt` (themed slots via `MotionUtils.resolveThemeSpringForce`: fast 0.6/800, default 0.8/380, slow 0.8/200 spatial; effects 1.0/3800–800), API-26-safe haptics vocabulary, reduced-motion collapse; `ExpressiveMotion` keeps its public API (~60 call sites untouched) and delegates; jelly re-implemented as an underdamped spring (0.35/380) reproducing the legacy oscillation with real physics.
**Why:** the old system declared expressive tokens but animated with fixed-duration overshoot interpolators.
**How it fixes it:** velocity-continuous, theme-tuned physics; reduced-motion parity preserved.

### 1.5 Release metadata
versionCode 230 / versionName 2.3.0 everywhere (gradle, update.json, badge, About, updater fallbacks), RELEASE_NOTES entry, ADR 0005, runtime button labels title-cased (expressive buttons don't auto-capitalize — caps literals would shout).

---

## Part 2 — CV subsystem: Phase A + A2 (implemented)

### 2.1 Native bootstrap (CV-2, critical)
**Changed:** new `cv/core/CvRuntime` (idempotent `initialize()`, recorded `isNativeAvailable`/`initError`, `requireAvailable()` → typed `CvNativeUnavailableException`, shared daemon camera executor, `engine` singleton, `estimateBytes` helper) + new `OpenCVInitProvider` ContentProvider registered with `initOrder=100`; `VeilFrameApplication` and `WeChatQrEngine.install` defer to it; `CvErrorCode.NATIVE_UNAVAILABLE` + failure-mapper case.
**Why:** the provider the comments claimed existed **did not exist**; native loading hung on one `runCatching` inside QR install — failure meant `UnsatisfiedLinkError` inside silent catch-alls across every non-QR CV feature.
**How it fixes it:** providers run before `Application.onCreate`; CV unavailability becomes a typed, mapped, user-visible state.

### 2.2 Engine governance wired (A2 — CV-1, critical)
**Changed:** `CvRuntime.engine` = app-scoped `CvEngine(CvMemoryManager(MemInfoMemoryProbe()))` with the 3-lane `CvDispatcher`. Background removal, image-quality analysis, document auto-crop, and document enhance/filter now submit **INTERACTIVE-lane jobs with memory estimates** (×6 segmentation / ×3 analysis-warp buffers) instead of raw `Dispatchers.IO`; blocks carry `ctx.ensureActive()` checkpoints and `reportProgress`; admission rejections arrive as typed `CvResult.Err(OUT_OF_MEMORY, "… retry at <tier>")` and surface honestly.
**Why:** the governance core (lanes, admission, reservations, pooling) was excellent dead code — nothing ran through it, so nothing was admitted, budgeted, or lane-isolated.
**How it fixes it:** heavy CV work can no longer start when the system lacks the headroom (typed rejection instead of OOM-kill), and interactive work is lane-protected. QR decode intentionally keeps its frame-token architecture (latency budget); the viewfinder stays on the shared camera executor (per-frame lane churn not worth it — documented).

### 2.3 Honest failures (A3 — CV-3, critical)
**Changed:** BackgroundRemover never returns the source disguised as a cutout (typed failure, reason surfaced, Save disabled, export refuses); its Otsu fallback fixed for 4-channel input (it threw *inside the catch* — the actual root cause of the fake results); ImageQuality's fabricated score-82 fallback and pre-seeded fake metrics deleted (dash score + reason + disabled export + truthful report text); DocumentScanner's silent `catch(_){}` paths log + toast.
**Why:** silent result-fabrication is the worst failure class in a forensics product.
**How it fixes it:** every failure path is now truthful UI + logs; policy: fail-closed for verification, fail-honest for generation.

### 2.4 Leaks, frame path, executors (A4 — CV-4/5/6/7/9/11)
**Changed:** document viewfinder switched from per-frame **NV21→JPEG(85)→decode→Bitmap→Mat** to direct **Y-plane→CV_8UC1 ≤1024px INTER_AREA** (QuadDetector accepts 1-channel natively); all controller `Mat.release()` in `finally`; QrScanner resize leak fixed; per-controller `cameraExecutor` → shared CvRuntime daemon executor (thread leaked on every theme-change recreate); `BitmapBridge.toMat` recycles its intermediate copy + HARDWARE-config typed guard; `SmartAutoCrop` integral/squared released in `finally`; dead-CV frames short-circuit before conversion.
**Why:** these were the concrete mechanisms behind native-heap drift, viewfinder latency/heat, and thread accumulation.
**How it fixes it:** per-frame cost drops from ~8–24 MB JPEG churn to ~1 MB grayscale; native memory is deterministic under exceptions; thread count constant across recreates.

### 2.5 Compile repairs (main was red)
`main@8f090a7` did not compile (its CI badge was failing): `DocumentScanner.findCorners/warpPerspective/process` were called but missing (restored as QuadDetector/PerspectiveCorrector/enhance wrappers — `process` clones, so the caller's dual-release pattern is safe); phantom `page.appliedQuad`/`page.appliedFilter` → real `ScannedPage.corners`/`.mode`.

---

## Part 3 — AI Upscaler: P0 memory correctness + P1 lag program (implemented)

### P0 (commit `17b650f`)
- **F1 streaming source**: new `SourceAccess` (`BitmapSource` in-memory when ≤35% heap · `UriRegionSource` per-tile `BitmapRegionDecoder` — the full image never exists in heap for huge sources); previews always sampled ≤2048px; input-tile PNG round-trip deleted; defensive full-size copy in the single-tile path dropped (−192 MB peak on 48 MP).
- **F2-lite output budget**: pre-flight `(W·s)(H·s)·4` vs `min(45% heap, 220 MB)`; exceeding → honest dialog with exact numbers + "Switch to 2×"; algorithmic models additionally guarded (they need the full source in memory). *Band-streaming compose (lifting the cap instead of refusing) remains the documented follow-up.*
- **F3 device-class tiles**: `UpscaleInferenceParams.forDevice()` via `largeMemoryClass`/`isLowRamDevice` — chunk 160–512, workers 1–2 (was fixed 512 × up-to-4 workers ≈ 400 MB transient spikes).
- **F4 OOM guard + honest degrade**: engine retries once with halved tiles (floor 128px, workers 1) → typed `UpscaleMemoryException` → controller offers "Apply Lanczos 3" (in-memory sources) or "Retry at 2×" (streaming sources).
- **F5 job isolation**: per-job UUID workdirs (the shared dir was `deleteRecursively()`d by whichever job finished first — concurrent jobs corrupted each other); `StatFs` disk pre-flight; typed `TileIOException`/`InsufficientDiskSpaceException`.

### P1 (commit `18e9cd5`)
- **F7 EP ladder**: `OnnxSessionManager` rewritten — NNAPI (API 27+) → XNNPACK (2 threads) → bounded CPU; first backend that builds a session wins; choice cached per model in SharedPreferences; `lastBackend` feeds honest status lines ("Running Real-ESRGAN via NNAPI…") and the diagnostics panel.
- **F8 thread discipline**: CPU sessions intra ≤ min(4, cores·3/4), interOp 1, SEQUENTIAL (was cores·3/4 + interOp 4 per worker × 4 workers = 20–30 saturated threads); tile workers moved to a **dedicated executor with `THREAD_PRIORITY_BACKGROUND`** — the UI thread and compositor keep the fast cores, which is the direct fix for "whole phone laggy"; executor shutdown race fixed (outside `coroutineScope`).
- **F11 thermal governor**: new `runtime/ThermalGovernor` (PowerManager listener API 29+, battery heuristic API 26+), registered by the upscaler workspace. MODERATE+ → efficiency profile (workers 1, chunk halved); CRITICAL+ → cooperative per-tile abort with typed `ThermalShutdownException` and honest "let it cool down" UX; battery <20% unplugged → workers 1. This removes the thermal-soak mechanism that kept the phone laggy *after* jobs finished.
- **F10-lite hot loops**: `renderModelOutput` per-row `ensureActive` (was per-pixel — millions of coroutine checks per tile) + direct y/x alpha indexing (was div+mod per pixel); `drawTile` blends only the overlap strips (was whole-tile iteration with `continue`).

---

## Part 4 — Deliberately NOT in this RC (documented, sequenced)

- **CV Phase B/C**: per-job watchdogs/timeouts, `cv_runs.jsonl` telemetry + live diagnostics, native-heap instrumentation & lint gates, thermal *lane* governor for CV, unified `MediaDecoder.decodeSampled`, cancellation checkpoints inside primitives, dormant-portfolio decisions (`cv/motion` ≈970 LOC, `SmartAutoCrop`, `NoiseReducer`, `ColorEngine`, `TemplateMatcher`, `QrRecoveryEngine` — wire through CvEngine or park out of release builds), ADR 0006 + docs truthfulness pass.
- **Upscaler**: F2 full band-streaming compose (lifts the output cap), F6 foreground-service wrapper (`VeilFrameProcessingService` already declares `dataSync`), benchmark-gated EP selection, fp16/int8 model catalog, resume-after-process-death.
- **UI**: settings radios → connected ButtonGroups, container-transform navigation, hero-moment polish (EXECUTE width-morph, scan fly-in), workspace-internal typography sweep beyond the 11sp floor.

## Part 5 — Verification performed vs. required

**Performed here (no Android SDK/JDK in sandbox — `:opencv-sdk` natives are generated out-of-tree):** all XML well-formed (manifest included); full `@style/@color/@drawable/?attr` reference-resolution audit clean against Material 1.14.0 sources; zero dangling refs to deleted resources; brace/paren balance on all 25+ edited/created Kotlin files; public API surfaces preserved (`ExpressiveMotion`, controller `init/handleImageSelected/release`, engine overloads with default-arg compatibility for the benchmark path); ORT 1.27 API verified against `rel-1.27.0` sources (`addNnapi()`, `addXnnpack(Map)`, `setExecutionMode`, void returns); Material style/attr names verified against 1.14.0 library sources; phantom-symbol sweep; executor-shutdown race caught and fixed during self-review; haptics constants API-26-safe.

**Required before tagging v2.3.0 (maintainer/CI):**
1. `./gradlew :app:assembleDebug` + `testDebugUnitTest` (JDK 17, android-35 — CI config unchanged; Material 1.14.0 is compileSdk-35-built).
2. ADR 0005 §4 visual matrix: {Light, Dark, AMOLED} × {Dynamic, 5 palettes} × reduced-motion.
3. Upscaler acceptance: 48 MP@4× streams without crash; >budget output refuses with dialog; NNAPI/XNNPACK selection visible in diagnostics; Profiler: native heap flat over 5-min doc-scan; `gfxinfo` jank <5% while scrolling during a job; thermal override (`adb shell cmd thermalservice override-status 4`) → honest stop.
4. CV acceptance (plan §4 gates): fault-injection (`CvRuntime` unavailable) → honest UI in all four chains; admission rejection path exercised on a low-memory emulator; no thread growth across 5 theme-change recreates.
