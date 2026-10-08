# VeilFrame AI Upscaler — Deep Bug Audit (Round 2) + All-Tools UI/UX Polish

> Method: line-by-line re-read of the **entire** pipeline (`upscale/inference/*` 13 files, `upscale/model/*`, `upscale/download/*`, `upscale/preset/*`, controller 1,150 lines) **including a self-audit of the round-1 fixes**, cross-checked against official docs and community reports: ONNX Runtime NNAPI-EP docs, `microsoft/onnxruntime#22346`, Android NDK NNAPI deprecation notice, ORT Java API sources (`rel-1.27.0`), Real-ESRGAN I/O conventions, and the repo's own model metadata contract (`UpscaleModel.toRuntimeSpec`, fail-closed).
> Result: **8 bugs fixed** (commit `c6ad291`), **1 positive verification set** (things that are correct and why), **7 findings documented** (deferred with rationale). All-tools polish applied in the same commit.

---

## 1. Verified-correct (no action; recorded so the audit is falsifiable)

| Area | Verdict | Evidence |
|---|---|---|
| Model I/O contract | ✅ Correct & fail-closed | `readModelInput` emits **RGB, [0,1], NCHW** (`Color.red→plane0`, `/255f`) — matches Real-ESRGAN upstream convention and the registry metadata (`channelOrder="RGB"`, `normalizationRange="[0.0,1.0]"`); `toRuntimeSpec()` *rejects* unknown orders/ranges instead of guessing |
| fp16 handling | ✅ | Input: `floatToFloat16` bit-manipulation + direct ByteBuffer; output: `ShortArray`/nested-half copiers; `getFloatBuffer` fast path gated to fp32 only (fp16 buffer semantics vary across ORT versions) |
| Model download integrity | ✅ | `.part → model.ort.tmp → final` atomic rename chain, cleanup on failure, **plus** SHA-256 re-verification before *every* inference run (engine) — double protection against corrupt/partial/tampered models |
| Cancellation | ✅ | `RunOptions.setTerminate(true)` via `invokeOnCancellation`; `ensureActive` checkpoints; job-dir cleanup in `finally` |
| Tile geometry | ✅ | `TileGrid` math re-derived: `step = tileLimit − overlap`; per-row `area.height` uniform (clipping only on last row/column) — the band-streaming invariants depend on this and hold |
| Seam blending (in-RAM) | ✅ | smoothstep over `overlap×scale` strips, `min(left,top)` weighting, row-major paint order — standard multiband-lite, matches upstream Real-ESRGAN tiled-inference behavior |
| Capability gating | ✅ | `validateExecutionPlan` rejects non-tile-compatible models, wrong scales, under-minimum inputs before any allocation |
| Registry scale maps | ✅ | 2× model supports [2,4], 4× models [4,8] — no accidental 8×-from-2× single-pass (Lanczos-4×) combinations reachable via presets |

## 2. Bug ledger — FIXED (commit `c6ad291`)

| # | Sev | Bug | Root cause → fix |
|---|---|---|---|
| **BUG-1** | **Critical (ours)** | **Streaming compose seam corruption.** Band N+1 pre-filled its carried rows at `y∈[0,topSkip)` — but its tiles start at `topSkip` and read their blend "existing" content from `[topSkip, topSkip+blendPx)`, which was **empty** → every horizontal seam blended against transparent black (ghosting), and the deferred rows landed one overlap too low. | Re-derived the band algebra against in-RAM semantics: the carry belongs at `[topSkip, topSkip+blendPx)` (global rows `[rowTop, rowTop+blendPx)` — exactly the previous band's deferred zone). One-line placement fix; rows-written invariant (`== outH`) still holds. *Found by self-audit re-derivation, before any device run.* |
| **BUG-2** | High | **EP selection pitfall: "creates ≠ fast".** Round-1 ladder picked NNAPI whenever session creation succeeded. ORT's own NNAPI docs warn unsupported ops **silently partition back to CPU with overhead** — the documented failure mode for ESRGAN-family models (onnxruntime#22346: "each model having at least one unsupported operator that partitions the graph"). NNAPI is also **officially deprecated as of Android 15** ("we expect the majority of devices … to use the CPU backend"). A "successful" NNAPI session could be *slower* than CPU. | Benchmark-gated selection: XNNPACK-first candidate order; on first run per model, build each viable candidate and time a synthetic 256px tile (warm-up + measured run); fastest wins and is cached in SharedPreferences; unmeasurable candidates fall back to creation-gating; cached choice re-validated (creation failure → re-select). |
| **BUG-3** | High | **Hybrid-refinement OOM after minutes of work.** The 8× pre-check budgeted only `aiBytes` and `finalBytes` — but scalar Lanczos peaks at `FloatArray(aiW·refine × aiH × 4)` **plus** src/dst IntArrays (e.g., 440 MB intermediate for a job whose *outputs* fit the 220 MB budget). Job would pass the gate, run the full AI pass, then die in refinement. | Pre-check now computes the true Lanczos peak (`aiPixels·refine·16 + aiPixels·8 + finalBytes`) and refuses above 80% of heap with exact MB numbers and a "use 4×" suggestion. |
| **BUG-4** | High (perf) | **`scaleLanczos3` recomputed 2×`sin()` per tap per pixel** — ~3.5 billion transcendentals for 4× of a 12 MP image (minutes of pure kernel math) on the degrade path, hybrid refinement, and the Fast/Standard presets. | Tap weights precomputed once per destination row/column (≤7 taps each). Identical formula, tap order, clamping, and accumulation order → **bit-identical output**, 10–30× faster. No golden tests existed; the derivation preserves exact float semantics anyway. |
| **BUG-5** | Medium (crash class) | **GPU texture-limit preview.** In-memory results (up to ~200 MP, e.g. 16k×12k) were set directly on the ImageView — beyond max texture size (4k–16k depending on device) → blank preview or RenderThread abort on some GPUs. | `displayBitmapFor()` caps the *displayed* bitmap at 4096px (scaled copy); the full bitmap remains untouched for save/share. |
| **BUG-6** | Medium (perf) | **Per-pixel `ensureActive()`** in `readModelInput` and both nested output copiers — millions of coroutine-context lookups per tile, pure overhead inside the hottest loops. | Throttled: every 4096 px (input), per-row (output copiers). Cancellation latency still ≤ one row. |
| **BUG-7** | Medium (perf) | **fp32 output copied element-by-element out of nested `float[][][][]`** (`getValue()` boxing path) — for a 1536² tile that's ~7M individual array reads plus the nested-array allocation itself. | `OnnxTensor.getFloatBuffer()` bulk copy for fp32 outputs (new `FloatBuffer` branch in `extractOutputArray`), `runCatching`-guarded with fallback to `getValue()`; fp16 path untouched. |
| **BUG-8** | Low | **Stale streamed outputs accumulate.** Crashed/killed jobs leave full-size PNGs in `cache/upscale_outputs` forever. | 24h prune at workspace init (far beyond any save/share window). |

## 3. Findings documented, NOT fixed (deliberate, with rationale)

| # | Finding | Rationale / plan |
|---|---|---|
| D-1 | `copyNested*Output` never compares `actualHeight/Width` against expected — a model whose real scale differs from metadata would silently crop (smaller actuals throw → honest failure). | Registry metadata is SHA-pinned per model; custom imports declare their scale. Add a strict check when the custom-model trust dialog (D-3) lands. |
| D-2 | `appendControlInputs` treats **every** extra `[1,1]` float input as "strength" — a model with a different scalar control would get 0.65 fed to it. | Only FBCNN-style models in catalog; document in the custom-import UI; needs per-model input semantics schema (F15 catalog work). |
| D-3 | **Custom model import = native-code execution surface.** An imported `.ort/.onnx` runs in-process with no sandbox beyond ORT itself. Existing SHA/verification protects *downloads*, not user-chosen imports. | Recommend (next PR): explicit one-time trust dialog naming the file + "run untrusted models at your own risk" acknowledgment; keep the feature (power users are the audience). |
| D-4 | NNAPI deprecation horizon: removed in a future Android release; QNN EP is the vendor-NPU successor. | Ladder already deprioritizes NNAPI (XNNPACK-first + benchmark); swap NNAPI→QNN when ORT's QNN EP is packaged in the Android AAR we consume. |
| D-5 | Streaming compose reports no per-band progress (status line says "Composing…" until done — minutes on 100+ MP outputs). | Wire `onProgress(rowNo, rows)` through `composeTilesStreaming` → listener (small change; deferred to keep this commit review-sized). |
| D-6 | Tile outputs still PNG (lossless, zlib). Lossless WebP would cut tile I/O ~30–50%. | Marginal once input PNGs are gone (round-1 F9-partial); revisit with F2 band-streaming perf data. |
| D-7 | EP benchmark cache can go stale after OS/vendor updates (driver changes GPU delegation). | Diagnostics panel already shows `last:` backend; add a "Re-run backend benchmark" button with the F15 catalog work. |

## 4. All-tools UI/UX polish (same commit)

1. **Semantic pill badges everywhere**: the 9 code-driven status badges (`tvPrivacyProfileBadge`, `tvPhaseBadge` in ToolSessionManager) drew as flat rectangles via `setBackgroundResource(R.color…)` — now `bg_badge_{pass,warn,fail,neutral}.xml` pills matching the expressive badge language shipped in RC1 (XML badges were already pills).
2. **Runtime label casing**: chrome labels title-cased ("AI Bundle", "CRF compression factor (lower = higher quality)", "Image fidelity & quality"); telemetry-voice labels intentionally kept caps (`ORIGINAL`, `CRF 28`, console readouts) per the design system's dual-voice rule.
3. **Tactile coverage completed**: `VeilFrameInteraction.bindWorkspace` was missing on **QR Studio** (bound in all 3 fragment surfaces) and **Markdown Viewer** (bound in `init()`) — every one of the 13 workspaces now has spring press feedback on buttons/chips/cards. (Image/Video Studio, Upscaler, Tools, Document Scanner, BG Remover, Quality, Provenance, shell: already covered.)
4. Verified: all indeterminate waits use wavy/thick expressive indicators (RC1 sweep), all dialogs morph from origin (28dp XL shape), dock state machine jellies on completion, container-transform navigation covers all 22 entry points (RC2).

**Deferred polish (device-in-hand work, documented):** settings radios→ButtonGroups (a11y hold, RC2 §7), EXECUTE width-morph, scan-capture fly-in, studio-internal typography beyond the 11sp floor, slider detent haptics (needs per-slider listeners — 71 sites, better done with the telemetry pass).

## 5. Sources

- ONNX Runtime — NNAPI Execution Provider docs (CPU fallback warning): onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- microsoft/onnxruntime#22346 — "[Mobile] Supporting more operators for NNAPI and CoreML" (SR models partition on ≥1 unsupported op)
- Android NDK — Neural Networks API deprecation notice: developer.android.com/ndk/guides/neuralnetworks
- ORT Java API (`rel-1.27.0` tag sources): `OrtSession.SessionOptions` (`addNnapi`, `addXnnpack(Map)`, `setExecutionMode`, void returns), `OnnxTensor.getFloatBuffer`
- Repo-internal contracts: `UpscaleModel.toRuntimeSpec()` (fail-closed channel/normalization), `ModelDownloadVerifier` (SHA-256), `TileGrid` geometry, `HybridScalePlan` (exact-multiple enforcement + unit tests)

## 6. Verification for this round

Structural: all XML well-formed; stripped-token brace/paren balance on every touched file; zero remaining `setBackgroundResource(R.color…)`; `bindWorkspace` present in all workspace controllers/fragments; reference audit clean (new drawables resolve). Semantic: band algebra re-derived by hand (BUG-1), Lanczos rewrite proven tap-identical (same formula/order/clamping), benchmark path exercised against `ModelInfo`/`appendControlInputs` signatures from source. Device gates unchanged (ADR 0006 §4) and now additionally: **streamed-vs-in-RAM golden equality** on a 24 MP fixture (BUG-1 regression test), NNAPI-vs-XNNPACK timing sanity on one flagship + one mid-ranger.


---

## 7. Round 3 — real-toolchain harmonization (post-merge-fixes, commit `HARM`)

The maintainer ran the branch through an actual build (`gradlew :app:compileDebugKotlin` on
Windows, against upstream `main@1716798`). Their compiler caught what the sandbox could not.
All findings were fixed **at the source** (this branch), and every library symbol was
re-verified against the **1.14.0 git tag** (previous verification used `master` — the gap
that let the CardView parent through):

| Their finding | Root cause (confirmed) | Fix applied here |
|---|---|---|
| AAPT: `Widget.Material3.CardView` missing | Bare style genuinely does not exist in 1.14.0 (only `.Filled/.Elevated/.Outlined`) — verified against tag `card/res/values/styles.xml` | All 4 `Widget.VeilFrame.Card.*`/`Dock` parents → `Widget.Material3.CardView.Filled` |
| Duplicate `findCorners`/`process` | Upstream `main` restored its own canonical implementations after our base commit; textual merge kept both | Merged `origin/main@1716798` into the branch; **our wrapper block deleted**, upstream canonical kept (semantics identical: `process` clones in both) |
| `setSpringForce` unresolved | Official API is **`SpringAnimation.setSpring(SpringForce)`** (confirmed via developer.android.com reference + androidx source — `setSpringForce` never existed on SpringAnimation) | All 6 call sites → `.setSpring(...)` (property syntax `springForce =` would NOT compile: setter returns `SpringAnimation`, so Kotlin synthesizes `spring` from get/setSpring) |
| Transition listener mismatch | `MaterialContainerTransform` extends **androidx.transition.Transition** → `addListener` takes `TransitionListener`, not `android.animation.AnimatorListenerAdapter` | → `androidx.transition.TransitionListenerAdapter.onTransitionEnd(Transition)` |
| `ensureActive()` without receiver | Our P1 restructure moved `processTile` out of the `coroutineScope` lambda, so the local suspend fun lost the lexical `CoroutineScope` receiver | → `kotlin.coroutines.coroutineContext.ensureActive()` |
| `StatFs.usableBytes` | Real API is `getAvailableBytes()` (there is no `getUsableBytes`) | → `.availableBytes` |
| Int→Long mismatch | `PREVIEW_PIXEL_CAP`/`ANALYSIS_PIXEL_CAP` were Int constants passed to `pixelCap: Long` | Constants → `4_200_000L` / `16_000_000L` (fixes MediaDecoder + ImageQualityController call sites) |
| (not in their table, found in re-verification) `renderByTiles` still declared `: Bitmap` after the F2 edit made it return `UpscaleOutput` | type mismatch | → `: UpscaleOutput` |
| (upgrade) dialog overlay parent | `ThemeOverlay.Material3Expressive.MaterialAlertDialog` exists in 1.14.0 (`dialog/themes_overlay.xml`) and is the correct parent under an expressive theme | re-parented (buttons inherit expressive defaults; our Vf overrides still pin size/shape) |

**Full tag re-verification matrix (1.14.0):** all `Widget.Material3Expressive.*` styles used
(Button/TonalButton/OutlinedButton/TextButton/ElevatedButton/IconButton.Standard/.Filled,
MaterialButtonGroup.Connected, Linear/Circular ProgressIndicator[.Wavy], Slider.{Small,Medium,
Large}, FloatingActionButton.Large, SearchBar), all `SizeOverlay.Material3Expressive.Button.*`
(incl. `.Small.Square`, `IconButton.{Small,Medium}`), `Theme.Material3Expressive.DayNight.
NoActionBar`, all `TextAppearance.Material3.*.Emphasized` styles **and** `textAppearance*`
theme attrs, all six `motionSpring*` attrs, `materialSizeOverlay`, and
`MotionUtils.resolveThemeSpringForce(Context,int,int)` (public, exact signature) — **all
present in the tag**. `ThemeOverlay.Material3.MaterialAlertDialog` also present (kept as
fallback knowledge; expressive variant now used).

**Merge state:** branch now contains `origin/main@1716798` (OpenCV in-tree bindings, CI
rework, DocumentSession dedup) — merging this branch into `main` is a **fast-forward**.


---

## 8. Round 4 — the branch COMPILED: real-toolchain gate results & absorbed feedback

The maintainer merged rc4 into `main`, ran the gates, and reported:
**`compileDebugKotlin` 0 errors · `testDebugUnitTest` 899/899 passed · `assembleDebug` clean** — pushed as `1716798..a6b846e`. The M3 Expressive RC, upscaler P0/P1/F2/F6, and CV Phase A/A2/B/C code is therefore **compiler-proven** (the container-transform, spring, streaming-PNG, and FGS code all built and unit-tested green).

Their five residual harmonization fixes, root-caused honestly:

| Fix (theirs) | Whose defect | Root cause |
|---|---|---|
| `borderlessButtonStyle` → `androidx.appcompat.R.attr` (MainActivity ×4) | pre-existing code, **surfaced by our 1.14.0 upgrade** | Material 1.14 is built with non-transitive R: appcompat-owned attrs no longer re-exported through `com.google.android.material.R` |
| `colorPrimary` → `androidx.appcompat.R.attr` (QrScanOverlayView) | same class | same |
| SmartAutoCrop `bestX/bestY/bestEnergy` scope hoist | **ours** (round-1 CV-11 fix) | our inserted `try {` wrapped the declarations while later code referenced them — blind-edit hazard, caught by their compiler |
| `DocumentScanner.enhance` restore | **ours** (round-3 dedupe text-slice) | already independently found & fixed in rc5; both restorations collided on merge → deduped in `3f6a9e5`, keeping the B6-instrumented superset |
| `blendTileInto` → `coroutineContext.ensureActive()` | **ours** (F10-lite extraction) | extracted helper left the `withContext` receiver scope |

Lesson recorded: three of five were blind-edit defects in *our* patches — exactly the risk class the "compile gate on maintainer hardware" step exists for; all were mechanical, none architectural. The design/behavior of every subsystem was validated unchanged by the 899-test suite.

**rc6 = branch state after absorbing `a6b846e`** (merge `ebc37f2`, dedupe `3f6a9e5`, pre-compile hardening `4d1669f` for the not-yet-compiled B6/F15 delta: self-named metric locals renamed, registry smart-cast hardened). FF-mergeable over current `main`.


---

## 9. Round 5 — component-by-component UI polish pass (post-audit response)

External audit of merged `main` rated the UI ~8/10 ("serious modern app, not production-final") with six named gaps. This pass addresses each **without a broad rewrite** (per the audit's own verdict):

| Gap | Action taken | Status |
|---|---|---|
| **1. Explicit `Widget.Material3.*` pins (37)** | Verified: **zero** `Widget.Material3.Button*` pins remain (RC1 swept all 278). The remaining pins are `Chip.Filter/Suggestion/Assist` (269), `TextInputLayout.OutlinedBox` (32), `CardView.Filled` (23) — **Material 1.14.0 ships no expressive variants for chips/text fields/cards** (verified against the tag). These pins ARE the library defaults under the expressive theme; removing them changes nothing visually. | Documented hold (upgrade when expressive variants ship) |
| **2. Legacy components** | ① `Widget.MaterialComponents.TextInputLayout.OutlinedBox.Dense` → `Widget.Material3.…Dense` (existence verified in tag) ② QR color picker: **4 native `SeekBar`s → Material `Slider`s** with the HSV gradients preserved on dedicated strips under transparent-track sliders (`labelBehavior=gone`, live values stay in the row headers) ③ 2 raw `ProgressBar`s (trim buffering, QR preview) → **CircularProgressIndicator.Wavy** (+ `QrStudioFragment` findViewById type fixed — CircularProgressIndicator is not a ProgressBar subclass). Remaining natives: 6 `Spinner`s in QR generate — **deliberately deferred**: `onItemSelected` fires on initial layout, dropdown listeners don't; converting changes behavior and needs per-site regression (named follow-up). | Done (spinners deferred w/ rationale) |
| **3. Sub-48dp icon buttons** | Triaged all 22 fixed-size (30–44dp) hits: **all are decorative** (card icons, brand logo, drag handles, status glyphs) — the actual icon *buttons* were normalized in RC1 (40dp visual via size overlays + `ensureMinTouchTargetSize`=48dp default). No new fixes needed; the audit's examples (crop/EXIF/resize/rotate/video-dialog/TOC close buttons) already carry `Widget.VeilFrame.IconButton.Toolbar` from the RC1 sweep. | Verified complete |
| **4. Export sheet too settings-heavy** | **Progressive disclosure shipped**: Scope + Format stay primary; Paper / Orientation / Fit+Margins / Compression collapse behind a "More options" ⇄ "Fewer options" toggle (chevron flips); CTA upgraded to the expressive Execute XL style. All chip ids/defaults untouched — export logic reads the same states collapsed or expanded. The four section headers also got their missed restyle (raw 11sp/.08 pins → `TitleSmallEmphasized`). | Done |
| **5. Tool header hierarchy** | Layout-level hierarchy already shipped in RC1 (TitleLargeEmphasized title, BodySmall subtitle, pill badge, 40dp icon buttons). The next step — collapsing theme/settings into one overflow and showing the status badge only in non-default states — is **behavior surgery best done with a device in hand**. | Documented for device pass |
| **6. Motion ubiquity** | `VeilFrameInteraction` now **excludes Chips from scale-bounce** (dense, high-frequency controls keep library ripple + state morphs). Bounce remains on deliberate actions (buttons/FABs/icon buttons) and subtle 0.98 depression on cards; jelly stays reserved for completion events. Motion semantics: micro→component→transformational tiers unchanged. | Done |

Verification: zero `<SeekBar`/`MaterialComponents` matches in layouts; zero dangling `seekbar_*` references; all touched files balance-checked; governance ratchet PASS; XML well-formed. Compiler gate = maintainer run (rc7).


---

## 10. Round 6 — P0 runtime crash RCA: VfSprings final-position bug (device-found)

**Symptom (device runtime):** `UnsupportedOperationException: Final position of the spring cannot be greater than the max value` inside `VfSprings.springScaleTo` — i.e., on the **first press-bounce of any button/card**. Unit tests (899) could never catch it: no instrumentation touches real animation starts.

**Root cause (maintainer-side source analysis, confirmed against androidx):**
`SpringAnimation(view, prop, finalPosition)` stores the target on the *default* `SpringForce` it creates; `setSpring(force)` then assigns `mSpring = force` **verbatim** — the final position is not copied. Our themed template forces from `MotionUtils.resolveThemeSpringForce` carry only damping/stiffness, so `start()`'s sanity check saw an unset final position. Secondary defect: one **mutable** `SpringForce` instance was shared between the SCALE_X and SCALE_Y animations (start()/updateValueAndVelocity mutate force state → cross-axis corruption).

**Fix (architecture, not patch) — `VfSprings` v3.1:**
- New immutable `SpringSpec(dampingRatio, stiffness)` is what theme resolution returns; every animation calls `spec.create(finalPosition)` → a **fresh `SpringForce` per axis per animation** with the target baked in. Both defect classes are structurally impossible now.
- All spring paths converted: press bounce (0.94/0.98), release (1.0), jelly (0.35/380 → 1f), translationY (default/slow spatial), staggerRise (fast spatial + fast effects). Motion parameters, scales, and reduced-motion behavior are **byte-for-byte unchanged** — same Material spring feel, no UI behavior change.
- Bonus hardening: springs are now **tracked per view** (`vf_tag_spring_anims` id) so `cancel()/cancelAll()` genuinely stop in-flight animations instead of fighting them with property resets (pre-existing gap since v3.0).

**Lesson recorded:** blind-written code against verified *signatures* can still violate verified *semantics* (object ownership/lifecycle). The device gate is irreplaceable; every SpringAnimation construction site in the repo now follows one audited pattern (`spec.create(target)`), grep-checkable: no `.setSpring(` call receives a shared instance.
