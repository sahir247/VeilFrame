# VeilFrame v2.3.0-rc2 — Final Implementation Report (Round 2: "All Remaining Work")

**Branch:** `release/v2.3.0-expressive-rc1` (10 commits on `main@8f090a7`, clean tree)
**This round delivered:** ① CV Phase B (watchdogs, telemetry, thermal gating, unified decode policy, CI ratchet) ② CV Phase C (ADR 0006, dormant-portfolio contract, docs truthfulness) ③ Upscaler **F2 band-streaming** + **F6 foreground service** + the **dynamic memory policy** for the 6–16 GB fleet ④ UI hero-polish: container-transform navigation for all 22 workspace entry points.
**Binding product constraint honored (user directive):** target devices have **6–16 GB TOTAL RAM with fluctuating availability** → every budget is computed **per job from live `MemAvailable`**, with static tiers as ceilings only — *neither conservative nor aggressive, dynamic*. Recorded as ADR 0006 §2.4.
**Durable artifacts:** `patches/0001…0010-*.patch` + `rc2-delta.bundle` (this workspace's snapshot cap dropped `.git` once already; these survive it — apply with `git am` onto `8f090a7` or `git pull rc2-delta.bundle release/v2.3.0-expressive-rc1`).

| Commit (this round) | What |
|---|---|
| `8edafce` | feat(cv): Phase B+C — watchdogs, telemetry, thermal gating, honest docs, CI ratchet |
| `33cb772` | feat(upscaler): F2 band-streaming, F6 foreground service, dynamic memory policy |
| `9693c98` | feat(ui): container-transform navigation with slide fallback |
| `0d1aed5` | docs: rc1 report (round 1, covers UI RC + CV A/A2 + upscaler P0/P1) |

---

## Part 1 — The dynamic memory policy (the user directive, made concrete)

**Root problem:** a fixed profile either wastes a 16 GB flagship (too conservative) or OOM-kills a busy 6 GB device (too aggressive). Availability — not total RAM — is the real constraint, and it changes minute to minute.

**Changed (mechanism → where):**
1. **In-RAM output budget** = `min(45% app heap, 220 MB, ½ × live MemAvailable)`, floor 48 MB — `AiProcessor.inRamOutputBudget()` + controller gate, evaluated **per job, on an IO thread** (`/proc/meminfo` via the existing `MemInfoMemoryProbe`). A 16 GB device with 8 GB free gets the full budget; a 6 GB device down to ~1.2 GB free gets ~600 MB → 220 MB → streams earlier automatically.
2. **Tile profile** = static heap tier (`largeMemoryClass`/`isLowRamDevice`) as *ceiling*, then live adjustment: `MemAvailable < 1.5 GB` → chunk −25% (32px-snapped, floor 128) & workers→1; `≥ 4 GB` free with 512 MB+ heap → keeps the 2-worker profile — `UpscaleInferenceParams.forDevice()`.
3. **Everything above the budget streams** (Part 2) instead of refusing — refusal only above the hard 200 MP cap or when even one band can't fit.
4. **Thermal/battery overlay** (round-1 `ThermalGovernor`, now ref-counted): MODERATE+ → efficiency profile; CRITICAL+ → typed cooperative abort; battery <20% unplugged → workers 1; document viewfinder drops alternate frames when throttled, stops when critical.
5. **CV admission stays live by construction**: `CvMemoryManager.reserve` budgets against `MemAvailable × 0.25` at submit time — now actually exercised because A2 wired the chains through it (round 1).

**How it fixes the underlying problem:** memory decisions are a *function of the device's state at job start*, not of a device class guessed once. The same 48 MP job runs 2-worker/512px on a cool idle flagship, 1-worker/288px on a warm busy 8 GB phone, and streams-to-disk on a loaded 6 GB one — no crashes, no wasted headroom.

## Part 2 — Upscaler F2: band-streaming compose (output OOM class eliminated)

**Changed:**
- **NEW `StreamingPngWriter`** — minimal, spec-correct PNG writer (8-bit RGBA, filter-0 scanlines, CRC-32 chunks, zlib IDAT via `DeflaterOutputStream`): scanlines are deflated **straight to disk** as they're produced. `abort()` guarantees no half-valid files survive failures.
- **NEW `UpscaleOutput`** sealed result: `InMemory(bitmap)` when within the dynamic budget · `Streamed(file, preview, width, height)` beyond it — the full-size output bitmap **never exists** in the streaming path; peak RAM = one band (`outW × (tile+overlap)×scale × 4 B`, e.g. ~106 MB for a 16k-px-wide output, vs 768 MB for the old 48 MP@4× compose).
- **`composeTilesStreaming`**: tiles composed one tile-row band at a time; a `prevTail` carry of the previous band's bottom overlap rows keeps the smoothstep seam blending **pixel-identical** to the in-RAM path; per-band memory guard; row-count invariant (`rowsWritten == outH`) or typed failure.
- **Controller integration**: preview = sampled re-decode of the written file (≤2048px); **Save = byte-copy** of the already-encoded PNG into MediaStore (no re-encode — faster than the old path); Share = FileProvider copy; stale streamed files deleted on re-run/release.
- **Hybrid-refinement pre-check** (engine): 8× (AI 4× + Lanczos 2×) requires both passes in RAM — refused up front with exact MB numbers and a "use 4×" suggestion instead of dying mid-refinement.

**Why:** `composeTiles` allocating `(W·s)×(H·s)×4` was the single guaranteed-OOM allocation (768 MB for 48 MP@4× on any phone).
**How it fixes it:** output size is now bounded by *disk*, not heap; the 200 MP cap and band guard are the only refusals, both honest and numeric.

## Part 3 — Upscaler F6: foreground service

**Changed:** NEW `UpscaleForegroundService` (dataSync type — manifest + existing `FOREGROUND_SERVICE_DATA_SYNC` permission), silent IMPORTANCE_LOW channel, cooperative **Cancel** notification action wired to the inference job via `cancelHook`, `START_NOT_STICKY`. Controller starts it per job (guarded — a denied FGS start degrades to in-app execution with a log, never a crash), stops it on completion/failure/cancel/release.
**Why:** minutes of heavy CPU with no FGS made the process a prime target for OEM phantom-process killers and LMK — "the app crashed mid-upscale" with no stack trace.
**How it fixes it:** the job gets a visible, cancellable, properly-scheduled process lifetime; users can navigate away; kills become cancellations.

## Part 4 — CV Phase B: hardening (commit `8edafce`)

| Item | Changed | Root problem → fix |
|---|---|---|
| **B1 Watchdogs** | `CvDispatcher.submit(timeoutMs)` → monitor coroutine cancels via `CvCancellation` at the job's next `ensureActive()` checkpoint; permit freed; event recorded. Wired: bg-removal 30s, quality 20s, doc crop/filter 15s | A wedged GrabCut/warp blocked a lane forever → bounded job lifetime (cooperative — a blocked *inside* one native call still waits for it; documented honestly) |
| **B2 Telemetry** | NEW `CvTelemetry`: append-only `cache/cv_runs.jsonl` (rotated 512 KB, pure-JVM, **no network**), fed by every governed CV job (lane, estimate, ms, outcome, error) + every upscale run (model, backend, chunk, workers, ms, streamed) + watchdog events; `recent(n)` for diagnostics panels | Field failures were invisible; static diagnostics text lied → every job now leaves a private, copyable forensic trail (on-brand) |
| **B4 Thermal lanes (CV side)** | `ThermalGovernor` ref-counted (upscaler + doc scanner coexist); viewfinder: skip every other frame when throttled, stop when critical | Sustained per-frame CV soaked the SoC → analysis load now tracks thermal headroom |
| **B5 Unified decode** | NEW `media/MediaDecoder` (bounds, power-of-two sampling, PREVIEW 4.2MP / ANALYSIS 16MP caps, Uri+File variants); ImageQuality + upscaler previews adopted | Every entry point invented its own decode policy (usually none) → one audited policy |
| **CI ratchet** | NEW `android/scripts/check_cv_governance.sh` + step in the tier-4 CI job: consumers of `cv.*` must reference `CvRuntime`; `BitmapBridge.toMat` requires `engine.submit`; upscaler ratcheted on typed-OOM + thermal wiring; documented exemptions | Governance regresses silently in fast-moving code → regressions now fail the build |

## Part 5 — CV Phase C: portfolio & truthfulness

- **ADR 0006** (`docs/adr/0006-cv-governance-dynamic-memory.md`): records every decision above, the dynamic-memory policy as a binding product constraint, risks (NNAPI vendor variance, cooperative watchdogs), and release-blocking verification gates.
- **Dormant contract**: `cv/motion/README.md` declares the ~970-LOC motion stack DORMANT with a non-negotiable wiring contract (VIDEO lane, ensureActive, ThermalGovernor, golden tests, telemetry) — no controller may call it until then. Same policy covers `SmartAutoCrop` (leak fixed in round 1), `NoiseReducer`, `ColorEngine`, `TemplateMatcher`, `QrRecoveryEngine`.
- **Docs truthfulness (CV-12)**: `CvDispatcher` KDoc and `docs/cv/VEILFRAME_CV_ENGINE.md` now describe the *wired* reality (which chains are governed, which run on dedicated executors by design, what's dormant) instead of aspirational claims.

## Part 6 — UI hero polish: container-transform navigation (commit `9693c98`)

**Changed:** `NavigationMotionController` gains a `nextOriginView` holder (consumed on every `showTool`, cleared on `hideTool` — stale origins can never fire) and a `MaterialContainerTransform` path: the clicked card **morphs into the workspace surface** (theme-driven easing/duration, FADE_MODE_THROUGH, container colors resolved via `MaterialColors`, scrim). Any failure → `runCatching`-guarded fallback to the classic directional slide with state reset; reduced-motion keeps instant transitions. All **22 entry points** wired (10 home cards incl. hero, 12 catalogue tiles) with zero changes to `MainNavigationController`'s 10 showTool call sites.
**Why:** the ADR-0005 hero budget called for the workspace-open moment to feel physical; slide+fade reads generic.
**How it fixes it:** the expressive "components flex to context" tactic now anchors navigation — the card you tapped literally becomes the screen, on springs-era Material motion, with a guaranteed-safe fallback.

## Part 7 — Cumulative branch state (all rounds)

`2a0e7f8` UI expressive RC · `0d0a000` plans · `3f2a6e4` CV Phase A+A2 · `17b650f` upscaler P0 · `e98ecb9` report r1 · `18e9cd5` upscaler P1 · `0d1aed5` report r1 final · `8edafce` CV Phase B+C · `33cb772` upscaler F2/F6/dynamic · `9693c98` container transform.

**Everything from both fix plans is now implemented except** (each explicitly documented with rationale, none blocking):
- CV B6 (cancellation checkpoints *inside* primitives — needs optional `CvContext` params across ~8 primitives + contract-test updates; primitives are short-running, watchdogs contain them meanwhile).
- CV B3 native-heap instrumentation beyond telemetry fields (needs debug-build finalizer canary; MatPool accounting already tracked).
- Upscaler F7 benchmark-gated EP *timing* selection (creation-gated ladder + per-model cache shipped; warm-up-run benchmarking deferred — NNAPI small-input timing is a poor predictor, needs a real tile benchmark harness).
- Upscaler F15 fp16/int8 model artifacts (schema-ready; requires the model pipeline to produce + publish quantized files with SHA-256s — cannot be fabricated in-repo).
- Upscaler F20 resume-after-process-death (FGS greatly reduces the exposure; session persistence pattern exists in DocumentSession to copy).
- UI: settings radios → connected ButtonGroups (deliberate hold: RadioGroups carry better accessibility semantics for exclusive settings; conversion adds risk without user-visible gain — recorded here as a product decision, not an omission), workspace-internal typography beyond the 11sp floor, EXECUTE width-morph (dock already state-machines text/icon/jelly; width morph needs layout-container surgery best done with a device in hand).

## Part 8 — Verification performed vs. required

**Performed here (sandbox has no JDK/Android SDK; `:opencv-sdk` natives are generated out-of-tree):** brace/paren balance on all 19 touched Kotlin files; manifest + all XML well-formed; governance ratchet executed locally → **PASS**; leftover-symbol sweeps (`resultBitmap`/`showOutputTooLargeDialog`/`WORKING_PIXEL_CAP`/`chunksDir`/invalid `return@withContext` — all zero in scope); PNG writer stream-ordering bug caught & fixed during self-review (IEND before deflater close); executor-shutdown race caught & fixed (round 1); ORT 1.27 and Material 1.14 APIs verified against upstream sources.
**Required before tagging v2.3.0 (ADR 0006 §4):** `./gradlew :app:assembleDebug testDebugUnitTest` + ratchet; memory matrix 12/24/48/108 MP × {2×,4×,8×} on 6 GB and 12 GB devices (zero OOM; streamed vs in-RAM golden-image equality); thermal override tests (`cmd thermalservice override-status 2/4`); FGS backgrounding + notification-cancel ≤200 ms; fault-injection honesty pass; expressive visual matrix (ADR 0005 §4).
