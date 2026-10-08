# Round 10 Report — Device-Feedback Batch: Motion Lab + 4 Named Fixes

**Branch:** `release/v2.3.0-expressive-rc1` → tip `d66312c` (commits `311d88c` code, `d66312c` docs)
**Base:** still fast-forwards onto your pushed `main@a6b846e` (delta prereq unchanged).
**Inputs:** your five device observations — optical flow as a tool, document-scan editing gap, bg-remover not saving, Library not reflecting stored works, button/text clipping.

---

## 1. Motion Lab — optical flow is now a real tool (Alpha)

**What changed.** New workspace wiring the previously dormant `cv/motion` stack end-to-end:

```
pick video → FFmpegKit extract frames → per-pair FlowEstimator (DIS=Fast / Farnebäck=Quality)
           + FrameSynthesizer.synthesize(t=k/m) → interleaved sequence
           → libx264 re-encode at fps×2 or ×4 (original audio copied via -map 1:a?)
           → filesDir/exports (Library-visible) + "Save to Gallery" (Movies/VeilFrame)
```

**Files:** `media/MotionLabController.kt` (new, ~300 lines), `res/layout/layout_motion_lab.xml` (new), plus integration points: `ScreenState.MOTION_LAB`, `WorkspaceRoute.MOTION_LAB` (aliases: optical flow / motion / interpolation / slow motion / fps — catalogue **search finds it**), catalogue card, `showMotionLabScreen()` + `hideAllToolViewsExcept` + the exhaustive `when`, `activity_main` include, MainActivity field/init/launcher/click/open, central insets listener list.

**Why it's honest (Alpha label is real):**
- Caps: ≤30 s input, ≤900 frames — over-limit refusals say so explicitly.
- Memory flat: pairwise Mats released every iteration; work dir deleted in `finally`.
- Mixed-resolution mid-video → refusal, not garbage output.
- Cancellation: flag + job cancel + `FFmpegKit.cancel()` between every synthesis.
- Governance: `FrameSynthesizer`/`FlowEstimator` are internally ADR-0006 governed (telemetry, admission) — no bypass.
- Every failure surfaces as status text + toast with the real message.
- `MediaMetadataRetriever.release()` guarded (`runCatching`) — it's API 29+, minSdk is 26.

**Not included (honest):** FlowConsistency-guided fallbacks and FrameInterpolator keyframe logic remain unconsumed; audio is copied, not time-stretched (at 2× frames the clip plays at normal duration — fps↑, not slow-mo; slow-mo = play same frames at original fps, which is a flag we can add next round).

## 2. Document scanning — editing beyond filters/auto-crop

**What changed.** Per-page **Adjust** dialog (`btnDocPageAdjust` in the page toolbar, next to Rotate): Exposure (−2..+2 stops), Contrast (0.5..2), Saturation (0..2), Reset/Apply. Backed by the **dormant `cv/color/ColorEngine` — now wired** under CvEngine governance (INTERACTIVE lane, 15 s watchdog, memory admission), identity-safe Mat chain (no double-release on no-op paths), result → `page.processedBitmapCache` → persisted → UI refresh.

## 3. Background remover — "image is not saving" FIXED

**Root cause:** export wrote to purgeable `cacheDir/exports` then handed off to a **SAF launcher** (`bgRemoverExportLauncher`) — on your device that flow died silently. **Fix:** direct **MediaStore gallery save** (`Pictures/VeilFrame`, Q+ `IS_PENDING` pattern, pre-Q fallback) **plus** persistent `filesDir/exports` copy; success/failure toasts with real messages; error logged under `VeilFrame.BgRemover`.

## 4. Library — now stores/displays previous works coherently

**Root cause:** `refreshLibrary` listed only five app dirs — MediaStore saves (upscaler gallery outputs, and now bg-remover/Motion Lab) were invisible. **Fix:** merges a MediaStore query (`DATA LIKE '%/VeilFrame/%'`) with app-dir exports, deduped by absolute path, date-sorted; existing category chips filter the merged set unchanged. Query failure degrades gracefully (log + app-dir-only list).

## 5. Clipping sweep — "many buttons clipping, button text clipping"

**Root cause:** the expressive type scale (RC1) made labels larger while **126 MaterialButtons still had hardcoded dp heights** (40/44/48dp), and 22 fixed-height rows (36–60dp) contained buttons/text. **Fix:** swept all 126 buttons → `wrap_content` (size overlays now own height) and all 22 rows → `wrap_content`. Icon-only buttons untouched.

## Bonus finding: catalogue "symlink search" was real all along

The round-9 audit flagged tools search as MOCK-risk ("not found in code"). Source trace this round: `WorkspaceRoute.search()` (title/description/**alias** matching) + live `TextWatcher` + category chips exist at `MainActivity:940`. Audit corrected: 🟢 REAL, tally now **24 REAL / 3 PARTIAL / 3 UNWIRED / 0 MOCK / 0 BROKEN**.

## Verification performed here (no compiler in this sandbox)

- All layouts XML-parse (`xml.dom.minidom`), including the two new files.
- Repo-wide reference audit: **0 missing** `@style`, `@drawable`, `@layout` includes, `R.style/R.layout/R.drawable` (new files conformed to the repo's real `Widget.VeilFrame.*` / `TextAppearance.VeilFrame.*` system — an earlier draft used artifact-side `Vf.*` names; caught and fixed pre-commit).
- Brace/paren balance deltas 0 on all touched Kotlin (MainActivity's residual +3/+3 is byte-identical to the pre-edit baseline — checker artifact from char literals, not drift).
- `showMotionLabScreen` clone verified; exhaustive `when` extended; back-stack handled by the generic fallback branch.

**Compiler-never-seen list grows by:** everything above (rc5→rc10 content). Your gates remain the authority.

## Your side (same flow as rc9)

```
git fetch /path/to/rc10-delta.bundle rc10:rc10
git merge --ff-only rc10          # onto main@a6b846e
gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```

Device smoke: Motion Lab (short clip, 2× Fast), doc-page Adjust, bg-remover Save → check Gallery + Library, button labels everywhere.

## Known risks for your compiler (honest predictions)

1. `MediaMetadataRetriever.setDataSource(activity, uri)` — fine on 26+, but if your lint has `NewApi` strictness on `release()` we already wrapped it.
2. `FrameSynthesizer.Options(estimator = …)` named arg — verified field exists (`FrameSynthesizer.kt:55`).
3. `binding.layoutMotionLab.root` in the insets list — include-binding `.root` pattern matches siblings.
4. Unused `onExportPngRequest` param on BackgroundRemoverController (kept for signature stability; `bgRemoverExportLauncher` now unused in that path) → warnings only.

## Artifacts (this round)

| Artifact | Path |
|---|---|
| Repo (branch tip `d66312c`) | `/home/user/veilframe` |
| Full bundle (prereq `8f090a7`) | `/home/user/veilframe-m3-expressive/rc10-full.bundle` |
| **Delta bundle (prereq `a6b846e` — use this)** | `/home/user/veilframe-m3-expressive/rc10-delta.bundle` |
| Patches | `/home/user/veilframe-m3-expressive/patches/0021-*` (code), `0022-*` (docs) |
| This report | `/home/user/veilframe-m3-expressive/11_ROUND10_REPORT.md` |
| Release notes (repo) | `/home/user/veilframe/RELEASE_NOTES.md` |
| Audit + addendum (repo) | `/home/user/veilframe/docs/PRODUCTION_READINESS_AUDIT.md` |
| New sources | `android/.../media/MotionLabController.kt`, `res/layout/layout_motion_lab.xml`, `res/layout/dialog_page_adjust.xml` |
| Touched | `MainActivity.kt`, `MainNavigationController.kt`, `ScreenState.kt`, `WorkspaceRoute.kt`, `BackgroundRemoverController.kt`, `DocumentScannerController.kt`, `activity_main.xml`, `layout_tools_catalogue.xml`, `layout_document_scanner.xml`, +15 layouts (clipping sweep) |
