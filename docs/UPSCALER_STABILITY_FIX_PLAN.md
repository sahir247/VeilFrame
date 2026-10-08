# VeilFrame AI Upscaler — Stability & Performance Fix Plan

> Scope: `app/upscale/**` (inference, controller), `app/cv/core/**` (memory policy), runtime-wide optimizations.
> Symptoms addressed: **(1)** whole-phone lag during upscaling even on flagships, **(2)** app crashes (OOM/native kills), **(3)** general runtime sluggishness, **(4)** CV dependability for the intended offline super-resolution use.
> Status: Plan (RC gate item). Every finding cites code evidence from `main@8f090a7`.
> Sister plan: `CV_RELIABILITY_UPGRADE_PLAN.md` covers the **entire** `cv/**` subsystem (all 34 files + consumers); shared infrastructure (CvRuntime, sampled decode, admission, telemetry, thermal) is specified there and referenced by F1/F3/F11/F13/F14 below.

---

## 1. Root-cause diagnosis (evidence-based)

### A. Why the whole phone lags

| # | Cause | Evidence |
|---|---|---|
| A1 | **CPU-only inference.** No NNAPI/XNNPACK/QNN execution provider is ever attempted; the diagnostics panel literally reports "Active Backend: Multi-Threaded CPU" | `OnnxSessionManager.createSession` — only `setIntraOpNumThreads/setInterOpNumThreads/setOptimizationLevel`; `ImageUpscalerController.setupDiagnostics` |
| A2 | **Thread oversubscription.** intra = `cores*3/4` (≈6 on an 8-core flagship) **per session run**, interOp = 4, times up to **4 parallel tile workers**, plus `Dispatchers.Default` pool — 20–30 compute threads saturate every core including the ones the system compositor needs | `OnnxSessionManager` + `UpscaleInferenceParams(parallelWorkers = cores.coerceIn(1,4))` + `AiProcessor.resolveParallelWorkers` (4 workers on ≥8 cores) |
| A3 | **No thread-priority discipline.** Inference runs at default priority on Default dispatcher; nothing yields CPU to SurfaceFlinger/system → UI stutters device-wide | `UpscaleInferenceEngine.upscale = withContext(Dispatchers.Default)` |
| A4 | **PNG round-trip churn.** Every tile is PNG-encoded to cacheDir, decoded back, re-encoded after inference, decoded again at compose — zlib on both sides burns cores that should run the model | `AiProcessor.writeSourceTiles / transformStoredTile / composeTiles` (`savePng`, `loadBitmap`) |
| A5 | **Scalar Kotlin hot loops.** Per-pixel color packing with a coroutine `ensureActive()` **per pixel** (millions of coroutine checks per tile); scalar Lanczos with a `FloatArray(targetW*srcH*4)` intermediate | `AiProcessor.renderModelOutput`, `drawTile` (per-pixel `ensureActive`), `AlgorithmicUpscaler.scaleLanczos3` |
| A6 | **Thermal runaway, no governor.** Sustained all-core load soaks the SoC; Android throttles *everything* (including system processes) → "whole phone laggy" for minutes after. No `PowerManager` thermal listener anywhere | absence in `app/upscale/**` |

### B. Why it crashes

| # | Cause | Evidence / math |
|---|---|---|
| B1 | **Full-resolution source decode.** Selected image decoded with no `inSampleSize` → a 48 MP photo = 8000×6000×4 B = **192 MB** heap before work starts | `ImageUpscalerController.handleImageSelected` (`BitmapFactory.decodeStream(stream)` raw) |
| B2 | **Full-size output materialized in RAM.** `composeTiles` allocates `(W·s)×(H·s)` ARGB_8888: 12 MP @4× = 192 MB; 48 MP @4× = **768 MB** → guaranteed OOM (heap caps 256–512 MB) | `AiProcessor.composeTiles` |
| B3 | **Per-tile transient spikes × workers.** Output `FloatArray` (3ch·(chunk·s)²·4 B) + `IntArray` pixels + rendered Bitmap + two blend `IntArray`s per tile ≈ 80–120 MB at chunk 512/scale 4 — multiplied by up to 4 concurrent workers | `runModelOnBitmap`, `renderModelOutput`, `drawTile` |
| B4 | **No memory admission control.** `CvMemoryManager` (tiers, budgets, working resolutions) exists but the upscaler never consults it; chunk size fixed 512 regardless of device/model scale | `cv/core/CvMemoryManager.kt` vs `UpscaleInferenceParams` defaults |
| B5 | **No OOM handling / degrade path.** A single allocation failure kills the job (or the process); no chunk-halving retry, no honest fallback | `AiProcessor` (no `OutOfMemoryError` catch) |
| B6 | **Shared, racing work directory.** All jobs use one fixed `cacheDir/processing_chunks`; `finally { chunksDir.deleteRecursively() }` in two places — concurrent jobs delete each other's tiles; decode returns null → `error()` crash | `AiProcessor.chunksDir`, `processImage.finally`, `renderByTiles.finally`, `loadBitmap` |
| B7 | **Process-kill exposure.** Minutes of heavy CPU with no foreground service → OEM phantom-process killers / LMK terminate the app mid-job ("crash" without stack) | absence of FGS around inference (`VeilFrameProcessingService` exists for batch tools but is not used here) |
| B8 | **Refinement OOM.** Hybrid 2×AI+2×Lanczos refinement allocates another full-size output plus the Lanczos intermediate array | `UpscaleInferenceEngine` refinement branch + A5 |

### C. Reliability gaps (beyond crash/lag)

- Cancellation terminates the ORT run (`RunOptions.setTerminate` ✓) but partial files/bitmaps aren't guaranteed cleaned; dock state can stick in PROCESSING.
- No disk-space check before writing tens of MB of PNG tiles.
- No per-run telemetry → field failures are invisible; diagnostics panel is static text.
- No heartbeat/watchdog: a hung tile (driver wedge on NNAPI, if added later) hangs the job forever.

---

## 2. Fix plan

Priority: **P0 = crash-stoppers (ship blockers) · P1 = lag-stoppers · P2 = dependability program.**
Each item: change → where → acceptance criterion.

### P0 — Memory correctness (kills the crashes)

**F1. Sampled, streamed source (B1).**
`handleImageSelected`: keep `inJustDecodeBounds` pass, then decode a **working preview** (`inSampleSize` so long edge ≤ 2048, `Config.ARGB_8888`, or `HARDWARE` for display-only) for UI. Inference reads the **full source on demand** via `BitmapRegionDecoder` (API 31+: `ImageDecoder.createSource` region decoding; fallback: re-open stream per tile band with `inSampleSize=1` + region crop). Controller holds preview only; `sourceBitmap` full-res field is deleted.
*Accept: 108 MP test image opens with < 80 MB delta heap (Profiler), UI shows preview instantly.*

**F2. Output memory budget + band-streaming compose (B2, B8).**
Before execution compute `outputBytes = W·s × H·s × 4`. Budget = `min(Runtime.maxMemory()×0.45, 220 MB)`.
- If `outputBytes ≤ budget` → current in-RAM compose (fine up to ~24 MP @4× on 8 GB flagships).
- Else → **band-streaming mode**: compose row-band by row-band (band = one tile row × scale), immediately `compress()`/encode each band to the destination file via a sequential PNG/WebP writer (or per-band files + final stitch with OpenCV `cv::vconcat` on 8UC4 mats — OpenCV is already linked), keeping only one band + a sampled full preview (`long edge ≤ 2048`) in heap. Result screen shows preview; Save/Share use the file.
Hard refusal above ~150 MP output with honest dialog ("Output exceeds 150 MP — choose 2× or crop"), never a silent crash.
*Accept: 48 MP @4× (768 MB output) completes on a 6 GB device via streaming with peak heap < 250 MB; 108 MP @4× refuses cleanly.*

**F3. Device-class tile sizing (B3, B4).**
Wire `CvMemoryManager.tier()` into `UpscaleInferenceParams`:
| Tier | chunk (scale≤2) | chunk (scale 4) | workers |
|---|---|---|---|
| CONSERVATIVE (<7 GB or <1 GB avail) | 256 | 160 | 1 |
| NORMAL (7–11 GB) | 384 | 256 | 1 |
| HIGH (>11 GB, heap ≥512 MB) | 512 | 384 | 2 |
Constraint invariant: `workers × perTilePeak(chunk·s) ≤ 0.4 × maxMemory()`; `perTilePeak ≈ (c·s)²×(12 float + 4 int + 4 bmp)B`. Enforce in `UpscaleInferenceParams.init` (fail fast, log chosen profile to diagnostics panel — the panel becomes truthful).
*Accept: unit tests over tier×scale matrix assert invariant; diagnostics panel shows tier/chunk/workers/EP.*

**F4. OOM guard + honest degrade (B5).**
Wrap per-tile and compose allocations: on `OutOfMemoryError` → cancel siblings, halve `chunkSize`, retry job once; second failure → offer `AlgorithmicUpscaler` (Lanczos) fallback with explicit UI copy: "Neural path exceeded this device's memory budget — applied Lanczos 3 instead." Never propagate OOM to the process.
*Accept: instrumented test with artificially tiny heap budget degrades instead of crashing.*

**F5. Per-job workdir + disk guard (B6).**
`processing_chunks/<jobId-uuid>/`; single cleanup owner (job finalizer, idempotent); pre-flight `StatFs` check (need ≈ 2× estimated tile bytes + 100 MB slack) else stream-only mode (F9 makes this near-zero anyway). Replace `error("Could not read …")` with typed `TileIOException` → F4 path.
*Accept: two concurrent jobs (debug trigger) don't interfere; low-disk device gets clean refusal.*

**F6. Foreground service for inference (B7).**
Run jobs under the existing `VeilFrameProcessingService` (type `dataSync`, notification = wavy progress + stage + Cancel action). Kills phantom-kill exposure, gives the job a proper scheduling group, and survives navigation away; controller binds and observes.
*Accept: leaving the app mid-job keeps it running with notification; cancel from notification works; no `ForegroundServiceStartNotAllowedException` paths (start only from user gesture).*

### P1 — CPU/thermal correctness (kills the lag)

**F7. Execution-provider ladder (A1).**
`OnnxSessionManager.createSession(model, prefs)`: try in order — ① NNAPI EP (`addNnapi()`, flags: `NNAPI_FLAG_USE_FP16` on capable SoCs; GPU delegation is driver-dependent → benchmark-gated), ② XNNPACK EP (`addXnnpack` with 2 threads) for fp32/fp16 CNN-style SR models, ③ CPU (current path, tuned per F8). First successful *benchmark* (one 128² warm-up tile, timed) wins; choice cached per (device, model) in prefs and shown in diagnostics ("Backend: NNAPI-GPU · 2.1× vs CPU"). Any EP init exception → next rung (never crash).
*Accept: on a Snapdragon 8-gen flagship, 12 MP @2× wall-time drops ≥40% vs CPU baseline; on EP-unsupported emulator, silently uses CPU.*

**F8. Thread budget + priority (A2, A3).**
Single shared session per job; `intraOpNumThreads = min(4, bigCoreCount)`, `interOpNumThreads = 1`, `setExecutionMode(SEQUENTIAL)`; workers default 1 (2 only on HIGH tier + XNNPACK/CPU EP, never with NNAPI). Inference coroutine context = `Dispatchers.Default.limitedParallelism(workers)` and every worker thread calls `Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND + 2)` (via a custom dispatcher/`ThreadFactory` for the ORT session — `OrtSession` runs on caller threads for run(); set priority around `run` calls). UI thread and compositor keep the fast cores → phone stays responsive *during* upscaling.
*Accept: during a 12 MP @4× job, `adb shell dumpsys gfxinfo com.veilframe.app` jank < 5% while scrolling another app section; job wall-time regression ≤ 15% vs oversubscribed baseline (throughput traded for responsiveness — deliberate).*

**F9. Zero-disk tile pipeline (A4).**
Tiles flow in memory: region-decode (F1) → tensor → inference → draw into band canvas (F2). Disk only as overflow when band > budget (raw `.webp` lossless, not PNG — ~8× faster encode). Deletes `writeSourceTiles`/`loadBitmap` entirely.
*Accept: cacheDir writes ≈ 0 B for ≤48 MP jobs; CPU time in `zlib` disappears from traces.*

**F10. Vectorized hot loops (A5).**
- `renderModelOutput`: drop per-pixel `ensureActive()` (check per row); read the ORT `FloatBuffer` directly (`OnnxTensor.getFloatBuffer()` where available) instead of array copies; pack ARGB with Int shifts in a tight loop; consider `Bitmap.wrapBytes`-style direct writes via `android.graphics.Bitmap.setPixels` per row band. Optional later: move CHW→HWC+quantize into OpenCV (`cv::Mat` + `cv::dnn::blobFromImage` inverse) — SIMD for free since OpenCV is linked.
- `drawTile` blending: restrict pixel loop to the blend region only (left strip + top strip), not the whole tile (current code iterates all pixels and `continue`s).
- `AlgorithmicUpscaler.scaleLanczos3`: chunk the `FloatArray(targetW·srcH·4)` intermediate into row bands (it alone can OOM at 4× of a 4000-px-wide source), or delegate to OpenCV `cv::resize(INTER_LANCZOS4)` (SIMD, battle-tested, already available) keeping the Kotlin path as no-OpenCV fallback.
*Accept: 512-tile post-processing (non-ORT) time drops ≥50% in traces; Lanczos 4× of 8000-px source completes without OOM.*

**F11. Thermal & battery governor (A6).**
`PowerManager.addThermalStatusListener` (API 29+) during jobs:
| Thermal status | Action |
|---|---|
| NONE/LIGHT | full profile |
| MODERATE | workers→1, intra→2, chunk→ next-lower preset |
| SEVERE | pause job, persist band checkpoint, notify "Paused — device temperature" |
| CRITICAL+ | checkpoint + graceful stop, offer resume later |
Battery < 20% & unplugged → one-time confirm dialog before starting; low-power mode → default to 2× with notice. Pre-API-29: estimate via `BatteryManager.EXTRA_TEMPERATURE` polling (30 s).
*Accept: forced thermal escalation (adb `cmd thermalservice override-status`) pauses/resumes correctly; no ANRs; job resumes from last band.*

### P2 — Dependability program (CV works upgrade)

**F12. Unified memory policy.** (Joint with CV plan A2/B5 — `CvRuntime` + `MediaDecoder.decodeSampled` are shared.) All heavy CV entry points (upscaler, document scanner multi-page, background remover, video frames) route through `CvMemoryManager.admit(op, estimatedBytes)` — single source of truth for tiers; OpenCV paths lease from `MatPool` (`use {}` enforced by lint rule on `Mat(` outside pool). Debug builds: pool leak detector logs unreturned leases after 30 s.
**F13. Watchdog + heartbeat.** Each tile emits heartbeat; 120 s stall → `RunOptions.setTerminate`, typed `InferenceTimeoutException`, F4 degrade dialog. Session `close()` in all paths (already ✓ via `finally` — add timeout path).
**F14. Local telemetry (private by design).** Append-only `cache/upscale_runs.jsonl`: {ts, tier, model, scale, chunk, workers, EP, tiles, ms/tile p50/p95, peakJavaHeap, peakNativeHeap (`Debug.getNativeHeapAllocatedSize`), thermal transitions, outcome}. Surfaces in the existing Diagnostics panel ("Copy report") — feeds regression tests and support without any network calls (privacy brand promise).
**F15. Model catalog upgrade.** *(STATUS: tooling shipped — `tools/model_pipeline/quantize_upscale_models.py` produces PSNR-gated fp16/int8 artifacts + paste-ready registry snippet; `UpscaleModel.precision/variantOf/memoryClassBytes/recommendedBackend` schema live; `registerQuantizedVariant` is fail-closed against placeholder URLs/SHAs. Artifact publication awaits one maintainer run + GitHub Release upload.)* Publish **fp16 + int8 quantized** Real-ESRGAN variants (int8 ≈ ¼ size, 2–3× faster on CPU/XNNPACK, PSNR delta < 0.5 dB for SR — validate with the existing quality-gate tooling in `calibration/`); extend `ModelRuntimeSpec` with `memoryClass`, `minTile/maxTile`, `recommendedEP`; `validateExecutionPlan` gains memory validation (currently only scale/dims/capability).
**F16. Cancellation & process-death correctness.** Cancel = terminate + delete job dir + recycle partials + dock → READY + haptic; persist job descriptor (uri, model, scale, bandsDone) so Library ▸ Sessions can resume after process death (aligns with ADR 0004 §2.5 session pattern).
**F17. Tests.** Instrumented (CI x86_64): 108 MP refusal, 48 MP @4× streaming success, tiny-heap degrade, concurrent-job isolation, cancel-mid-job cleanliness, seam-blend golden diff (existing `DeterministicSvgRasterizer`-style golden infra). Manual matrix: 6 GB mid-ranger + 12 GB flagship × {2×,4×} × {12,48 MP} × {charging, hot}.

---

## 3. Runtime optimization plan (app-wide)

| # | Item | Expected effect |
|---|---|---|
| R1 | **Baseline Profiles** (`androidx.baselineprofile` + macrobenchmark-generated profile for shell, Home→studio nav, execute flow) | 20–40% faster cold start & first-use jank reduction (AOT the hot paths) |
| R2 | R8 full mode + resource shrink verification for release; keep rules audit for ORT/FFmpegKit reflection | smaller APK, faster steady-state |
| R3 | **Bitmap config discipline**: previews `Config.HARDWARE` (no CPU copies; re-decode on edit), no-alpha content `RGB_565` where safe, centralize in `BitmapBridge` | −30–50% preview memory |
| R4 | `limitedParallelism` dispatchers per subsystem (inference=1–2, IO pipelines=2, UI-prep=Default) instead of shared Default pool | predictable scheduling, no cross-starvation |
| R5 | WebView (Markdown viewer) lazy-warm only on workspace entry; CameraX analysis 480p + `STRATEGY_KEEP_ONLY_LATEST` (verify current) | background CPU/RAM savings |
| R6 | FFmpegKit: confirm only needed native libs are packaged per ABI split (full-GPL is huge); consider variant swap if size regressions appear | APK size / install time |
| R7 | Jank budget in CI docs: FrameMetrics median < 16 ms during nav transitions & dock morphs on mid-tier device (manual gate + macrobenchmark module when adopted) | protects the expressive motion investment |
| R8 | Startup: defer `UpscaleModelRepository` scan, ONNX env init, and OpenCV `loadLocally` until first workspace entry (verify current init order in `VeilFrameApplication`) | faster cold start |

---

## 4. Sequencing & effort

| PR | Contents | Est. | Blocks release? |
|---|---|---|---|
| `fix(upscaler): memory correctness` | F1–F5 | 3–4 d | **Yes** (crash class) |
| `fix(upscaler): fg-service + EP ladder` | F6–F7 | 2–3 d | **Yes** (kill class + flagship lag) |
| `perf(upscaler): threads, zero-disk, vectorize, thermal` | F8–F11 | 3–4 d | Recommended |
| `feat(cv): dependability program` | F12–F17 | 4–5 d | No (follow-up release) |
| `perf(app): runtime program` | R1–R8 | 3–4 d | No (incremental) |

Verification gate per PR: instrumented tests above + Profiler captures (Java heap, native heap, CPU, thermal) attached to PR; diagnostics panel truthfulness checked on 2 physical devices (mid-range 6 GB, flagship 12 GB).

## 5. Summary of the two headline fixes

1. **Crashes** are an unbounded-memory design (full-res source + full-size output + 4×512-tile spikes in RAM, no admission control). Fixed by *stream everywhere*: region-decoded input, budgeted tiles, band-streamed output, tier-aware parameters, OOM degrade, foreground service.
2. **Whole-phone lag** is CPU saturation + thermal soak (CPU-only ORT, 20–30 threads at default priority, PNG churn, scalar loops). Fixed by *yield everywhere*: NNAPI/XNNPACK EP ladder, 1–2 background-priority workers, zero-disk pipeline, SIMD/OpenCV hot paths, thermal governor.
