# VeilFrame v2.3.0-rc1 — Final Implementation Report

**Branch:** `release/v2.3.0-expressive-rc1` (5 commits on top of `main@8f090a7`)
**Scope delivered:** ① full M3 Expressive UI/UX migration (release candidate) · ② CV subsystem Phase A reliability fixes (implemented) · ③ AI Upscaler P0 memory-correctness fixes (implemented) · ④ two evidence-based engineering plans · ⑤ repair of three pre-existing compile breaks on `main`.

| Commit | What | Files |
|---|---|---|
| `e1dc808` | feat(ui): M3 Expressive design system v3.0 "Quiet Intensity" | 53 (4 new, 2 deleted, 47 modified) |
| `a2bc27c` | docs: upscaler stability fix plan + release notes | 1 |
| `5ec2bab` | docs: whole-CV reliability upgrade plan | 2 |
| `296b31e` | fix(cv): Phase A — bootstrap, honest failures, leaks | 14 (2 new) |
| `4b9ada9` | fix(upscaler): P0 memory correctness | 6 (2 new) |

---

## Part 1 — UI/UX: M3 Expressive migration (the "alive & expressive" RC)

### 1.1 Theme foundation
**Changed:** Material Components `1.12.0 → 1.14.0`; `Theme.VeilFrame` re-parented `Theme.Material3.DayNight.NoActionBar → Theme.Material3Expressive.DayNight.NoActionBar`; added `androidx.dynamicanimation`; aligned core-ktx 1.16.0 / appcompat 1.7.1 / constraintlayout 2.2.1 (1.14.0's dependency floor); deleted the hand-copied `values/motion_tokens.xml`.
**Why:** 1.14.0 is the first stable Views release with the full expressive system (themes, buttons, nav, progress, sliders, emphasized typescale). The token file duplicated values the library now provides — a drift hazard.
**How it fixes the problem:** every expressive component style, spring slot, and emphasized text attribute now resolves from the library itself; switching the parent theme re-tunes the whole app's physics and component looks with zero per-widget code.

### 1.2 Color tokens — monochrome-first, roles everywhere
**Changed:** `colors.xml` (day+night) extended: full 5-step surface-container ladder **in light mode too** (previously dark-only), new tertiary/on-tertiary/container roles, outline roles, `vf_scrim`; all 27 legacy `vf_*` names preserved. All five palette overlays (Monochrome flagship + Sage/Ocean/Amber/Violet, day+night) extended with a tertiary "editorial" voice and proper `on*Container` pairs. AMOLED overlay re-pointed at the ladder.
**Why:** the audit found widgets wired to raw `@color/vf_*` (hundreds of pins) — Dynamic Color and palette switching were partially bypassed, light mode had no containment hierarchy, and no tertiary role existed anywhere.
**How it fixes it:** widgets now consume `?attr/color*` roles; the sweep stripped `backgroundTint`/`iconTint`/`textColor` pins from all 177 affected button blocks, so Material You, the 5 palettes, and the semantic status colors all flow through one map. Monochrome stays emotionally rich via luminance choreography (page=Low → cards=Container → dock=High → hero=Highest) instead of hue — exactly the "calmer expressive variant" the Google research says a strong minority prefers, and the right register for a forensics product.

### 1.3 Component sweep (all 37 layouts)
**Changed:** 278 button style remaps to VeilFrame role styles over `Widget.Material3Expressive.*` (Execute=XL pill 64dp-class, CTA=L, Action=tonal M 48dp, Utility=outlined S, Quiet=text, icon buttons 40/48dp); all `app:cornerRadius` pins deleted (10 conflicting radii → shape from style, with library press/select morphs); 116 icon buttons moved to size overlays (fixes 38dp targets), 31 `iconSize` pins removed; 10 progress indicators restyled (determinate → **thick 8dp**, indeterminate → **wavy**); 12 sliders de-pinned onto expressive Medium (theme default; library default Xsmall deliberately overridden for a media editor); nav bar/rail custom tint selector **deleted** so expressive defaults apply (64dp bar, 56dp pill indicator, secondary-colored active labels, horizontal items ≥600dp); dock → 32dp `colorSurfaceContainerHigh` card; hero → 48dp-top-rounded `Highest` card; badges → pill drawables on container tokens; 9/10sp text eliminated (11sp floor, 27 sites); 24 chrome section headers → `TitleSmallEmphasized` title-case (telemetry keeps its terminal caps identity).
**Why:** research shows size/shape/color contrast makes key elements spotted up to 4× faster; the old shell had chaotic radii, sub-touch-target controls, and static 4dp progress bars for multi-minute AI jobs.
**How it fixes it:** one shape scale, one size ladder, and living progress (wavy = "waiting feels faster" per the M3 progress studies); the primary action per screen is now physically the largest element on it.

### 1.4 Motion — real springs
**Changed:** new `ui/motion/VfSprings.kt` (themed spring slots via `MotionUtils.resolveThemeSpringForce`: fast 0.6/800, default 0.8/380, slow 0.8/200 spatial; effects 1.0/3800–1600–800), API-26-safe haptics vocabulary, reduced-motion collapse. `ExpressiveMotion` keeps its public API (all ~60 call sites untouched) and delegates; the jelly completion flourish is re-implemented as an underdamped spring (0.35/380) that naturally reproduces the legacy 1.00→0.95→1.045 oscillation.
**Why:** the old system *declared* expressive spring tokens but animated with `OvershootInterpolator` — fixed-duration approximations of physics.
**How it fixes it:** motion now inherits velocity continuity and theme-tuned physics; switching to a future expressive variant retunes the whole app for free; reduced-motion users keep the hierarchy minus physics (contract preserved and centralized).

### 1.5 Release metadata
versionCode 230 / versionName 2.3.0 (`build.gradle.kts`, `update.json`, toolbar badge, About literal, AppUpdateManager fallbacks), RELEASE_NOTES entry, **ADR 0005** recording every decision + the AGP fallback path. Runtime button labels de-capsed (`"CANCEL PROCESSING"` → `"Cancel processing"`, etc.) because expressive button styles don't auto-capitalize — caps literals would otherwise shout.

---

## Part 2 — CV subsystem Phase A (implemented; plan: `docs/CV_RELIABILITY_UPGRADE_PLAN.md`)

### 2.1 Native bootstrap — CV-2 (critical)
**Changed:** new `cv/core/CvRuntime` (idempotent `initialize()`, recorded `isNativeAvailable`/`initError`, `requireAvailable()` throwing typed `CvNativeUnavailableException`, shared daemon camera executor) + new `OpenCVInitProvider` ContentProvider registered in the manifest with `initOrder=100`; `VeilFrameApplication` and `WeChatQrEngine.install` both defer to it; `CvErrorCode.NATIVE_UNAVAILABLE` + failure-mapper case added.
**Why:** the provider the code comments claimed existed **did not exist**; native loading hung on one `runCatching` inside QR install, and every non-QR CV path died with `UnsatisfiedLinkError` inside silent catch-alls when it failed.
**How it fixes it:** providers run before `Application.onCreate` → OpenCV is loaded (or its failure recorded) before any workspace can request CV work; failures become typed, mapped, user-visible states instead of crash-or-silence.

### 2.2 Honest failures — CV-3 (critical)
**Changed:** BackgroundRemover no longer returns the **original image as the "cutout"** on failure (typed failure state, reason surfaced, Save disabled, export refuses); its Otsu fallback fixed for 4-channel input (it used to throw *inside the catch*, cascading into the fake result); ImageQuality's fabricated "score 82 / Good Quality" and pre-seeded fake metrics deleted — failure shows "—", the reason, disabled export, and the report states VeilFrame never fabricates scores; DocumentScanner's `catch (_: Throwable) {}` paths now log and toast.
**Why:** silent result-fabrication is the worst failure class in a *forensics* product — it destroys trust exactly where the app promises verification.
**How it fixes it:** every failure path now produces truthful UI + logs; policy recorded: fail-closed for verification, fail-honest for generation.

### 2.3 Leaks, frame path, executors — CV-4/5/6/7/9/11
**Changed:** document viewfinder switched from per-frame **NV21→JPEG(85)→decode→Bitmap→Mat** to direct **Y-plane→CV_8UC1 (≤1024px, INTER_AREA)** — the pattern QrScanner already proved; `Mat.release()` moved into `finally` on the analyzer, capture re-crop, and filter paths; `QrScanner.yPlaneToMat` resize leak fixed; per-controller `cameraExecutor` replaced by the shared CvRuntime daemon executor (thread leaked on every theme-change recreate); `BitmapBridge.toMat` recycles its intermediate copy and rejects HARDWARE bitmaps with a typed error; `SmartAutoCrop` integral/squared released in `finally`; dead-CV frames short-circuit before any conversion (no more battery burn producing nothing).
**Why:** these were the concrete mechanisms behind drifting native heap, viewfinder latency/heat, and thread accumulation.
**How it fixes it:** bounded per-frame cost (grayscale 1024px ≈ 1 MB vs ~8–24 MB JPEG round-trip), deterministic native-memory release under exceptions, constant thread count across recreates.

### 2.4 Compile repairs (main was red)
`main@8f090a7` **did not compile** (its CI badge was failing): `DocumentScanner.findCorners/warpPerspective/process` were called but missing (restored as thin QuadDetector/PerspectiveCorrector/enhance wrappers, `process` returns a *copy* so the caller's dual-release pattern is safe), and `page.appliedQuad`/`page.appliedFilter` phantom fields → real `ScannedPage.corners`/`.mode`.

---

## Part 3 — Upscaler P0 memory correctness (implemented; plan: `docs/UPSCALER_STABILITY_FIX_PLAN.md`)

### F1 — streaming source (kills the crash class)
**Changed:** new `SourceAccess` abstraction: `BitmapSource` (in-memory when ≤35% of heap) and `UriRegionSource` (per-tile `BitmapRegionDecoder` — the full image never exists in the heap). Preview always sampled ≤2048px. AiProcessor region-decodes tile inputs; the input-tile PNG round-trip is deleted; the defensive full-size copy in the single-tile path is dropped.
**Why:** the old path decoded the full 48 MP photo (192 MB) just to display it, then copied tiles through PNG encode/decode.
**How it fixes it:** heap peak becomes independent of source resolution for AI models; a 108 MP photo opens as a ~16 MB preview and streams tiles.

### F2-lite — output budget with honest refusal
**Changed:** pre-flight `outputBytes = (W·s)(H·s)·4` vs `min(45% heap, 220 MB)`; exceeding → dialog with exact numbers + "Switch to 2×" action; algorithmic models additionally guarded (they need the full source in memory).
**Why:** `composeTiles` allocates the whole output (768 MB for 48 MP@4×) — a guaranteed OOM.
**How it fixes it:** the app can no longer *start* a job it cannot finish in RAM. (Band-streaming compose — lifting the cap instead of refusing — is the documented F2 follow-up.)

### F3 — device-class tile sizing
**Changed:** `UpscaleInferenceParams.forDevice(context, targetScale)` from `largeMemoryClass`/`isLowRamDevice`: chunk 160–512, workers 1–2 (was fixed 512 + up to 4 workers ≈ 400 MB transient spikes and 20–30 saturated threads).
**How it fixes it:** the per-tile × workers invariant is now bounded by heap class — the flagship "whole phone lags" thermal soak loses its main fuel source (worker/thread oversubscription), pending the P1 EP-ladder and priority work.

### F4 — OOM guard + honest degrade
**Changed:** engine catches `OutOfMemoryError`, retries once with halved tiles (floor 128px, workers 1), then fails with typed `UpscaleMemoryException`; controller offers "Apply Lanczos 3" (in-memory sources) or "Retry at 2×" (streaming sources) — the same honesty policy as Part 2.
**Why:** a single allocation failure used to kill the process.
**How it fixes it:** worst case is now a slower-but-real result the user explicitly chose.

### F5 — job isolation & disk guard
**Changed:** per-job UUID working dirs (the shared `processing_chunks` dir was `deleteRecursively()`d by whichever job finished first — concurrent jobs corrupted each other); `StatFs` pre-flight with typed `InsufficientDiskSpaceException`; tile read failures are typed `TileIOException` instead of `error()`.

---

## Part 4 — What is deliberately NOT in this RC (documented, sequenced)

- **CV A2**: wiring the four live chains through `CvEngine` lanes/admission (next PR per plan — the governance core stays dormant-but-ready; `CvRuntime` is its bootstrap foundation).
- **CV Phase B/C**: watchdogs, telemetry jsonl, thermal lane governor, sampled-decode unification, dormant-portfolio decisions (`cv/motion` etc.).
- **Upscaler F2 full band-streaming, F6 foreground service, P1 lag program** (NNAPI/XNNPACK EP ladder, thread priorities, thermal governor, vectorized hot loops) and **P2** dependability program.
- **UI**: settings radios → connected ButtonGroups, container-transform navigation, hero-moment polish passes, workspace-internal typography sweep (studios keep their current text sizes except the 11sp floor).

## Part 5 — Verification performed vs. required

**Performed here (no Android SDK in sandbox — `:opencv-sdk` natives are generated out-of-tree):** every XML well-formed (manifest included); full `@style/@color/@drawable/?attr` reference-resolution audit clean against Material 1.14.0 sources; zero dangling refs to deleted resources; brace/paren balance on all 19 edited/created Kotlin files; public API surfaces preserved (`ExpressiveMotion`, controllers' `init/handleImageSelected/release`, engine overloads); phantom-symbol sweep (`appliedQuad/appliedFilter/findCorners` resolved); haptics constants API-26-safe; `MotionUtils.resolveThemeSpringForce(Context,int,int)` and every `Widget.Material3Expressive.*`/`SizeOverlay.*` style name verified against library sources.

**Required before tagging v2.3.0 (maintainer/CI):**
1. `./gradlew :app:assembleDebug` + `testDebugUnitTest` (JDK 17, android-35 — unchanged CI config works; 1.14.0 is compileSdk-35-built).
2. ADR 0005 §4 visual matrix: {Light, Dark, AMOLED} × {Dynamic, 5 palettes} × reduced-motion pass.
3. Upscaler acceptance (plan §2): 48 MP@4× streams without crash; >budget output refuses with dialog; Profiler native-heap flat over a 5-min doc-scan session; two concurrent jobs isolated.
4. CV acceptance (plan §4 gates): fault-injection (`requireAvailable` false) → honest UI in all four chains; no thread growth across 5 theme-change recreates.
