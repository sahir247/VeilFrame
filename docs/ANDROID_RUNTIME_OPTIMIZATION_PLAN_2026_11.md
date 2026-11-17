# VeilFrame Android runtime optimization plan (17 November 2026)

**Basis:** code inspection of main at `d067944a5a1e833097834021eaee0db4eb0257a2` and the changes proposed in PR #45. This is a prioritized performance plan, **not** a claim of measured speedups. Memory budgets and timings must be verified on real arm64 devices.

## Scope and invariants

Optimize app *runtime*, not build speed alone. Maintain pixel fidelity, metadata/privacy guarantees, output file format, existing offline processing, and release minification. Do not silently downgrade native model quality, image export dimensions, PDF page continuity, video timestamps, or crypto correctness. No arbitrary dependency upgrades, minSdk increases, or library swaps without compatibility testing.

## Earlier completed implementation in PR #45 (historical CI passed; latest CI/device pending)

- PDF Extract Pages: single PDF, single image, and multi-image ZIP generation write to cache files directly. No list of rendered pages or whole ZIP/PDF loaded into heap in the app's SAF path.
- PDF Quick Tool: PDF-to-image ZIP and all three split modes (all pages, selected pages, custom ranges) write to files and transfer ownership to the system document picker. Checks bitmap compression results.
- PDF Watermark and Imposition: avoid `readBytes()` for normal app export; snapshot controls on Main before starting background work. Preserve legacy byte-array callbacks for compatible callers.
- SAF exports: copy with a fixed-size buffer; delete cache files on success, cancelled picker and failed destination; unit coverage for the streaming utility's error paths.
- PDF signature inspection: don't proceed after an encryption inspection failure.
- Watermark preview: reuse the proven PDF flattening 1280px preview DPI policy, debounce slider changes, and dispose of temporary preview bitmaps on cancellation/replacement.
- Video Studio: use Android API 27+ scaled frame decoding for preview thumbnails, keeping source metadata and full-resolution rendering unchanged.
- Background removal: read EXIF orientation once per decoded image.

## Earlier FFmpeg follow-up (17 November 2026)

- **FFmpeg worker interruption:** `VideoProcessor.runFFmpegSession` now cancels only its active FFmpegKit session when the blocking worker is interrupted, restores the interrupt flag, and propagates the interruption. This is **not yet CI/device verified**.
- **Still outstanding:** test coroutine-to-FFmpeg cancellation races and cleanup, complete cross-controller typed file-backed PDF sink consistency, full-resolution image export fidelity, native model/thermal profiling, startup benchmarking and real-device acceptance checks. Disk-backed unlocking and printing have now been added for picked PDFs; legacy byte callers remain.

## Earlier PDF/video implementation on PR #45 (17 November 2026)

- **PDF Quick Tool:** canvas-signing, metadata sanitization, and form-filling exports now write to buffered temporary PDF files. The existing disk-save callback avoids materializing full PDF byte arrays; the legacy callback remains supported when no disk callback is configured. Temporary files are deleted when not handed to the save picker. Form flatten preference is captured before background work.
- **Video:** both standalone stabilization and redaction duration probes release `MediaMetadataRetriever` in `finally`, including when metadata extraction fails; durations are clamped to positive values to avoid zero-duration progress division.
- **Validation:** these follow-up commits have **not yet been confirmed by CI or real-device tests**. In particular, exercise form/sign/metadata save, picker cancellation, navigation, and exception paths.

## Latest implementation pass on PR #45 (17 November 2026)

This pass changes real code and adds host regression tests; **the latest commits still require CI and device acceptance**. Earlier green checks do not validate newer commits.

| Subsystem | Code change | Status |
| --- | --- | --- |
| Document Scanner exports | Offload to IO; stream per-page PNG and PDF entries directly into ZIP instead of per-page `ByteArrayOutputStream`; check `Bitmap.compress` results; reject undecodable pages; delete partial PDF/ZIP/individual files on failure; check cancellation at each page | Implemented; CI/device pending |
| PDF Unlock picker/export | Picked PDF stays a SAF URI rather than a full input byte array; decrypt using the existing stream engine into a private temp file; bounded 1280px first-page preview; save/share/print from disk. SAF owns a hardlink (or IO-thread copy fallback) until picker result; stale orphaned files aged over 24h are swept asynchronously; older `loadPdfBytes` integrations remain supported | Implemented; CI/device pending |
| PDF printing | Java print adapter accepts file-backed input, uses PDFBox scratch files and always recycles rendered page bitmaps even when drawing fails | Implemented; CI/device pending |
| PDF merge thumbnails | Bounded page-count LRU reusing `PdfRenderer` counts rather than repeatedly loading a PDF with PDFBox during RecyclerView binds; skip invalid image-as-PDF attempts | Implemented; CI/device pending |
| PDF reorder thumbnails | Cache reordered-state flag across binds; recompute only when page order changes | Implemented; CI/device pending |
| PDF OCR | Check cancellation between pages; always recycle the rendered page in `finally`; propagate coroutine cancellation instead of showing a failure toast | Implemented; CI/device pending |
| Cutout Gallery / sticker / share / clipboard | Capture presentation settings on Main before IO export, fail on false encoder result, delete failed MediaStore insertions, use fixed-size disk copy | Implemented; CI/device pending |
| Multi-subject matting | Alpha-fuse ROI regions using 64-row strips; connected-component tiny-blob filtering is linear rather than pixel × label; release OpenCV Mats and PII detector on all paths | Implemented; CI/device pending; verify model output parity |
| FFmpeg sessions | Reject a missing async session rather than waiting forever; cancel owned native session on callback/interruption error; cancel sessions reported after coroutine cancellation; clean motion-analysis temporary files on setup failures | Implemented; CI/device pending; stress cancellation races |
| Startup and NCNN | Added Perfetto trace sections for PDFBox, crypto, layout, controller, listener, haptics and native model-load/inference; no speculative changes to native allocator/mutexes or model precision | Instrumented; actual bottleneck selection requires traces |
| Profiling | `scripts/profile_android_runtime.py` now captures PSS distributions, battery temperature/level and a device-validated startup-baseline comparison with `--baseline` | Harness implemented; no device measurements supplied |

**Tests added:** image ZIP decoding and exact entry counts; cleanup on a missing scanned page; parity of max-alpha ROI fusion for odd dimensions, offset placement, strip boundaries and invalid rectangles; six-page file-backed PDF unlock stream result and corrupt PDF rejection.

**Unverified or dependent on device data (do not mark complete):**
- True end-to-end abort of synchronous FFmpeg/native/C++ work across all service callers; concurrency and session-owner races under slow callback delivery.
- File-backed PDF unlock is newly implemented but has not yet been stress-tested with password-protected 300-page inputs, scanner free-space failures, process death while the picker is open or asynchronous print cancellation. Verify it preserves cryptographic permission-removal semantics, page counts, and all buttons.
- Full-resolution 48/108MP matting export beyond the present 4096px working cap. This requires separately tiled full-resolution alpha application, EXIF/ICC parity, and peak-memory tests; blindly raising the cap could introduce OOM.
- Vulkan/CPU numerical parity, RIFE/RealSR model hot-switching, GPU fallback, model-load benchmarks, 10-minute thermal/battery, 4K rotated-video PII coordinates, PDF free-space/picker process-death scenarios, R8 signed release startup, and real-device p50/p95.
- These test/device/release gates cannot be declared passed from source inspection.

**Suggested measurements on the same ARM64 phone and build mode:**

```bash
python scripts/profile_android_runtime.py --runs 7 --out baseline.json
python scripts/profile_android_runtime.py --runs 7 --baseline baseline.json --out after.json
python scripts/profile_android_runtime.py --runs 7 --workflow "300 page PDF to PNG ZIP" --out pdf-workflow.json
```

Use Perfetto slices with prefix `VF.` to determine whether PDFBox initialization, crypto provider, hierarchy inflation, haptic recursion, NCNN model load, or inference actually dominates runtime. Compare identical input files and thermal conditions. The manual `--workflow` snapshot is *not* an automated operation-duration benchmark.

## Priority 0 — finish and verify first

1. **End-to-end export continuity:** review remaining PDF conversion and export paths still using in-memory buffers; signing, metadata, and form filling now use disk-backed exports. Route large outputs through file callbacks, then migrate controllers to a shared typed output contract; preserve simple small outputs where appropriate. Test picker cancel, navigation during export, >300-page PDF, 150/300 DPI image ZIP, free-space exhaustion, invalid PDFs, repeated exports, and ZIP integrity.
2. **Release correctness:** run `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and minified release assembly. Launch real signed release on an arm64 device to validate R8/WorkManager startup. No merge while these checks are failing.
3. **Measure baselines:** use `python scripts/profile_android_runtime.py --runs 7 --out baseline.json` and capture representative `--workflow` scenarios. Compare exactly the same device, source media, thermal conditions, and APK configuration before/after. Observe PSS, native heap, frame jank, cold startup latency, battery/temperature where available.
4. **Cancellation and storage:** ensure output generators respect coroutine cancellation *during* long C++/FFmpeg/PDF loops, not only at UI callbacks. Verify files are not leaked if navigation or Android process death occurs while a CreateDocument picker is open.

## Priority 1 — broad functional performance passes

### PDF suite
- Profile PDFBox parsing/rendering and thumbnail scheduling. Cap preview raster size to actual view bounds; introduce an eviction-aware bitmap cache only after observing decode churn. Do not cache document objects past workspace exit.
- Review duplicate byte arrays and stream buffering in PDF-to-text, office conversion, signing, form filling, and metadata tools.
- Confirm that finished image/PDF output is written only once and never serialized on the main thread; validate page counts and formats.

### Image/CV suite
- Audit import-wide `readBytes()` and full-sized intermediate ARGB_8888 bitmaps. Maintain separate working-resolution inference and final-resolution export policies. The current 4096-pixel matting cap reduces original image resolution on larger photos; restoring export fidelity needs tile/mask mapping and seam tests, not raising the cap blindly.
- Profile mask post-processing and native/Java bitmap transfers. Measure 12MP, 48MP, 108MP, transparent PNG, landscape/portrait, odd dimensions, and low-memory devices. Explicitly recycle only bitmaps no longer displayed or shared.
- Use memory budgets rather than universal crop/downscale settings; ensure orientation, aspect ratio, ICC/color handling are preserved.

### Video suite
- Profile FFmpeg startup, full video ingest/copy, MediaMetadataRetriever calls, transcoding and frame extraction. Avoid decoding full video frames solely for previews; use scaled retrieval wherever coordinate mapping is proven correct.
- Thread interruption now cancels the active FFmpegKit session at `CountDownLatch.await()`. Verify coroutine cancellation reaches the waiting worker, callback races, and native cleanup; do not impose a short timeout on legitimate long video exports.
- For video PII scanning, measure the full-frame decode → analysis downscale cost. Any scaled-frame change must retain accurate source coordinates and redaction locations, verified against video rotation and aspect ratio.

### NCNN, RIFE, RealSR, matting
- Existing native engines already use lifecycle mutexes, NCNN allocators, persistent RIFE buffers and shared Vulkan initialization. First collect per-engine cold/warm load time, inference p50/p95, native high-water memory and CPU/Vulkan results across supported GPUs.
- Test model hot switching, cancellation, repeated open/close, two simultaneous callers and Vulkan unavailable fallback before altering mutex or allocation boundaries. Never parallelize a global non-reentrant NCNN net without a clear ownership strategy.
- Revalidate non-square matting masks and scaled padding/unpadding against original geometry. Avoid changing Vulkan model options without image output parity tests.

### Startup and UI
- Measure `MainActivity.onCreate` (PDFBox initialization, BouncyCastle provider, theme/typography binding, `initSubControllers`, and recursive haptic attachment). Move expensive non-essential initialization off the critical first-frame path only if timing shows a benefit and dependencies permit it.
- Use an Android Macrobenchmark to measure cold/warm startup and frame timing. Generate Baseline Profiles from representative critical user journeys rather than inventing static profile lines.

## Priority 2 — release engineering

- Compare native symbols/ABI packaging and unused asset inclusion. Confirm models aren't loaded at launch unless a feature uses them. Keep R8 enabled and apply targeted keep rules only.
- Inspect battery use and thermal throttling during 10-minute repeated video and NCNN workloads.
- Track crash-free startup, OOM, cancellation leaks, permission failures, file-format fidelity, and document-picker behaviors as release acceptance gates.

## Measurement and acceptance

Compare before/after with medians and p95 (not a single run). Require unchanged PDF page counts/ZIP entry counts, render checksums within documented codec tolerances, unchanged original video size and timestamps, absence of new ANRs/OOMs, no UI view access on IO worker threads, and successful minified release startup.

Suggested workloads: 300-page PDF extracted as ZIP, 300-page PDF to WebP at 300 DPI, 48/108MP camera photo for matting, 4K/10-minute video export, video PII scan, PDF watermark sliders, RIFE frame interpolation and RealSR image upscaling. Record peak PSS, native heap, duration, output size, battery/thermal state and failures.

**Current limitations:** No connected real Android device or full Gradle runtime exists in this GitHub connector session. CI runs can validate code/build; device-side performance gains remain unverified until benchmark logs are collected. Keep PR #45 in draft until verification.

## References
- https://developer.android.com/topic/performance/graphics/load-bitmap.html
- https://developer.android.com/reference/android/media/MediaMetadataRetriever
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices
