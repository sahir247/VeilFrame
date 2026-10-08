# VeilFrame Production-Readiness Audit — control → backend trace (REAL / PARTIAL / MOCK / BROKEN / UNWIRED)

> Method: every workspace's visible control groups traced through controller → processing request → backend implementation → output, with file evidence. Granularity = control *groups* (not literally every chip); cross-check against device screenshots remains the maintainer's pass. Backend facts verified by code reading across six rounds of audits in this repo.
> Legend: 🟢 REAL (wired end-to-end) · 🟡 PARTIAL (works, with named gaps) · ⚪ MOCK (UI without backend) · 🔴 BROKEN (wired but defective) · ⬜ UNWIRED (backend exists, no production path)

## 1. Shell & navigation

| Control group | Path | Backend evidence | Label |
|---|---|---|---|
| Bottom nav / rail (Home·Tools·Library) | MainNavigationController → screen visibility + motion | adaptive rail ≥600dp, expressive 64dp bar | 🟢 REAL |
| Home tool cards / hero (22 entry points) | open*Screen() + container-transform origins | NavigationMotionController (v3.1 springs) | 🟢 REAL |
| Theme/palette/typography settings | ThemeSettingsManager | 4 modes × 6 color sources × 3 typefaces, AMOLED overlay | 🟢 REAL |
| In-app updates | AppUpdateManager | GitHub Releases manifest, version compare, repair flow | 🟢 REAL |
| **Workspace edge-to-edge insets** | MainActivity root listener | **was 🔴** (only shell chrome inset; workspace toolbars under status bar — the screenshot clipping). **Fixed this pass**: `ui/insets/WorkspaceInsets` contract applied to all 12 workspace roots (top=status, bottom=max(nav,IME)), per-workspace `Contract` opt-out for future immersive camera | 🟢 REAL (fixed) |

## 2. Document Scanner (the screenshot problem set)

| Control group | Path | Backend evidence | Label |
|---|---|---|---|
| Camera viewfinder/zoom/flash/lens | DocumentScannerController + CameraX | Preview+ImageAnalysis+ImageCapture bound; zoom clamped to capabilities | 🟢 REAL |
| **Live quad detection overlay** | analyzer → Y-plane Mat ≤1024 → `DocumentScanner.findCorners` → QuadDetector (Canny→contours→approx→convexity→confidence) → DocumentQuadOverlayView | Full OpenCV chain live; **was 🟡 flicker-prone** (per-frame raw quads). **Fixed this pass**: `QuadStabilizer` — EMA corner smoothing + 4-hit hysteresis + SEARCHING/TRACKING/STABLE states + haptic on READY | 🟢 REAL (stabilized) |
| **Shutter capture** | takePicture → imageProxyToBitmap → ScannedPage → session persist → auto-crop | **was 🔴-risk** (crash reports): OOM unguarded on full-res JPEG decode + rotation double-allocation; capture could race analysis on the shared executor. **Fixed this pass**: `isCapturing` single-flight flag (analysis freezes during capture), OOM guards → honest "Could not process captured frame", capture errors logged, state reset on viewfinder close | 🟡 PARTIAL → needs the actual crash log to confirm closure (see §8) |
| Page manager (reorder/rotate/duplicate/delete) | DocumentSession | disk-persisted JSON + bitmap cache, process-death survival | 🟢 REAL |
| Editor (crop/rotate/enhance modes) | delegates to DocumentScanner.process/enhance → Preprocessor/SmartSharpener | 6 modes, governed via CvEngine (A2) + B6 checkpoints | 🟢 REAL |
| Export (single/separate PDFs/images, paper/orientation/fit/margins/quality) | DocumentExportEngine | `android.graphics.PdfDocument` (7 refs), progressive-disclosure sheet (this pass) | 🟢 REAL |

## 3. Video Studio & media tools

| Control group | Path | Backend evidence | Label |
|---|---|---|---|
| Trim/scale/speed/audio/aspect/color/presets | VideoStudioController → FFmpegKit | `FFmpegKit` sessions, cancel by session id; WhatsApp-status pipeline (analyzer/rate-control/validator) | 🟢 REAL |
| **Motion-aware denoise / frame interpolation / temporal consistency** | — | `cv/motion/**` (DIS + Farnebäck + FlowConsistency + FrameSynthesizer + FlowInterpolator) complete & contract-tested but **zero production consumers** | ⬜ UNWIRED (Phase 4; wiring contract in `cv/motion/README.md`: CvEngine VIDEO lane, ensureActive, ThermalGovernor, golden tests) |
| Image Cleaner / Video Cleaner | ToolSessionManager → ImageMetadataSanitizer / VideoMetadataSanitizer + FFmpeg repack | EXIF/GPS/maker-notes scrub, PRNU mitigation path, privacy impact summary | 🟢 REAL |
| Folder Scanner / AI Bundle | ToolExecutionController → FolderScannerEngine (SAF tree scan) / AiBundleEngine | lock-file condensing, token budgeting, bundle export | 🟢 REAL |
| Image Studio (crop/resize/rotate/filters/EXIF/watermark/compress) | ImageStudioController → ImageTransformEngine + ImageCompressionEngine + dialog controllers | deterministic transform plan; iterative binary-search target size | 🟢 REAL |
| Image Studio "smart" crop | SmartAutoCrop (importance map + integral search) | engine complete, leak-fixed, **no UI consumer** (manual CropOverlayView is what ships) | ⬜ UNWIRED |

## 4. QR · Quality · Remover · Upscaler · Provenance · Markdown

| Control group | Path | Backend | Label |
|---|---|---|---|
| QR generate (11 styles) / scan | QrStudioFragment → QrGenerator (EFQRCode-parity, Tier-4 oracle-tested) / QrScanner (WeChatQRCode primary + MLKit fallback, frame tokens) | 899-test suite covers parity | 🟢 REAL |
| QR color picker | 4 Material Sliders (converted this pass from native SeekBars) + HSV gradient strips | | 🟢 REAL |
| Image Quality forensics | ImageQualityController → ImageQualityAnalyzer (Laplacian/noise/exposure/… + QualityScore policy) via CvEngine, honest-failure UI | | 🟢 REAL |
| Quality → Upscaler handoff (ADR 0004 Ph6) | — | recommendation gate not built | ⬜ UNWIRED |
| Background remover | GrabCut@640 + MaskOps (cleanup/fill/refine/feather/composite) via CvEngine; before/after split w/ spring+haptics | classical CV is real; **ONNX MODNet segmenter SPI exists but no model wired** | 🟡 PARTIAL (classical REAL, AI segmentation ⬜) |
| AI Upscaler | full pipeline: model manager (download+SHA-256), streaming SourceAccess, tiled ONNX (EP benchmark ladder), band-streaming PNG, FGS, thermal governor, degrade paths | **the 50.3 MP-on-11 GB case from the screenshots**: 50.3MP×4B ≈ 201MB vs budget min(45%·heap≈230MB, 220MB, ½·MemAvailable) → runs in-RAM when headroom exists, streams to disk when not, refuses >200MP — by design, dynamic | 🟢 REAL |
| Provenance & verify | ProvenanceController → Ed25519 signature validation + manifest forensics | | 🟢 REAL |
| Markdown viewer | MarkdownViewerController → offline WebView (marked/katex/mermaid/highlight bundled), security policy | | 🟢 REAL |
| Library (recents/sessions/exports) | MainActivity rendering over DocumentSession disk state + file listings | | 🟢 REAL |
| Tools catalogue search w/ synonym aliases (ADR 0004 §2.4) | no alias index found in code | browse + navigation real; **search/synonyms not found** | ⚪ MOCK-risk → treat as UNWIRED until verified on device |

## 5. Tally

🟢 REAL: 22 control groups · 🟡 PARTIAL: 3 (capture-crash confirmation pending, BG-remover AI segmenter, none user-blocking) · ⬜ UNWIRED: 5 (optical flow, smart crop, MODNet, quality handoff, tools search) · ⚪ MOCK: 0 found · 🔴 BROKEN: 0 remaining (insets + detection flicker + capture races were the 🔴s; all three treated this pass).

**Verdict on the "UI ahead of engine" thesis:** mostly *integration maturity*, confirmed — the engines are real and now governed (CvEngine lanes, admission, telemetry, thermal). The genuine UI-ahead-of-engine items are exactly the ⬜ rows, each with a documented wiring contract. No mock backends were found.

## 6. This pass (stabilization, per the freeze-features directive)

1. **Phase 1 — insets**: `WorkspaceInsets` contract on all 12 workspace roots (status-bar top, max(nav,IME) bottom), one implementation, per-workspace opt-outs. Fixes: Export-PDF-under-status-bar, clipped video-studio controls, obscured bottom docks, displaced toolbars.
2. **Phase 2 — camera ownership**: single-flight capture flag, OOM-guarded decode/rotate, logged capture errors, stabilizer/flag reset on viewfinder close. `ImageProxy.close()` in finally (prior pass), Mats in finally (prior pass), `unbindAll` on exit (pre-existing ✓), shared daemon executor (prior pass).
3. **Phase 3 — detection**: `QuadStabilizer` (EMA + hysteresis + honest SEARCHING/TRACKING/STABLE copy + READY haptic). The overlay now behaves like a scanner, not a strobe.
4. **Phase 4 — optical flow**: deliberately NOT started (feature work during a stabilization freeze); contract ready in `cv/motion/README.md`.

## 7. Remaining before "freeze UI"

- Device validation matrix (ADR 0005 §4) incl. tall phones / gesture nav / keyboard resize — the insets contract needs eyes on 6.1"/6.7"/tablet.
- The actual **capture crash log** (logcat around `VeilFrame.DocScanner`) — three candidate boundaries are now guarded; the log confirms which fired and whether anything remains.
- Tools-search decision: implement the ADR synonym index or remove the field (honesty rule: no control implies a capability that doesn't exist).
- Then Phase 4 (optical flow → VIDEO lane) as the first post-stabilization feature PR.
