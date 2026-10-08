# VeilFrame → M3 Expressive: Complete UI Migration Plan

**Goal**: make VeilFrame feel *alive and expressive* — Better, Easier, Emotional UX — via strategic **color, shape, size, motion, containment**, with **Monochrome Classic as a first-class expressive theme** (not a fallback).
**Constraints (ADR 0004, honored)**: no Compose rewrite; studio engines untouched; OpenCV/ONNX decoupled from UI; ViewBinding + Material Components only.
**Target baseline**: Material Components **1.14.0** (expressive themes stable), AGP 8.11.1+ / Gradle 8.13, minSdk 26 unchanged.

Companion docs: `01_UI_INVENTORY.md` (audit) · `02_RESEARCH_M3_EXPRESSIVE.md` (research) · `04_DESIGN_SYSTEM_V3.md` (tokens & specs) · `code/` (drop-in starters).

---

## Phase 0 — Toolchain & Theme Foundation  *(est. 2–3 days)*

| # | Task | Files |
|---|---|---|
| 0.1 | Bump `com.google.android.material` 1.12.0 → **1.14.0**; add `androidx.dynamicanimation:dynamicanimation:1.0.0` | `android/app/build.gradle.kts` |
| 0.2 | Upgrade AGP → 8.11.1, Gradle wrapper → 8.13; verify `:opencv-sdk` module still builds (run CI matrix) | `android/build.gradle.kts`, `gradle/wrapper/*` |
| 0.3 | Re-parent theme: `Theme.VeilFrame` → **`Theme.Material3Expressive.DayNight.NoActionBar`** (keep manual `DynamicColors.applyToActivityIfAvailable` — it layers over any theme; alternatively switch parent to `Theme.Material3Expressive.DynamicColors.DayNight.NoActionBar` and drop the manual call — decide in 1.4) | `res/values/themes.xml` |
| 0.4 | Fix non-transitive R fallout: fully-qualify `com.google.android.material.R.*` references (motion attrs, etc.) | Kotlin sources (compiler-driven list) |
| 0.5 | Delete duplicated tokens: `values/motion_tokens.xml` re-declares library tokens (same values) — remove file, consume library attrs (`?attr/motionSpringFastSpatial`, `?attr/motionDurationMedium2`, `?attr/motionEasingEmphasizedInterpolator`) | `res/values/motion_tokens.xml` |
| 0.6 | Inset audit: bottom nav **80→64dp** and dock padding math; re-verify `MainActivity.kt:372–384` + `1601–1603` insets on phone/tablet/3-button/gesture nav | `MainActivity.kt` |

**Exit criteria**: app compiles & runs on expressive parent with zero visual regressions beyond intended defaults; CI green.

## Phase 1 — Design Tokens (the design-system update)  *(est. 3–4 days)*

Deliverable = `04_DESIGN_SYSTEM_V3.md` + `code/vf_theme_expressive.xml` + `code/vf_expressive_components.xml`.

| # | Task |
|---|---|
| 1.1 | **Color**: add missing roles — full `colorSurfaceContainer{Lowest…Highest}` ladder in *light* (dark already exists); add **tertiary/tertiaryContainer/onTertiaryContainer** per palette; regenerate Sage/Ocean/Amber/Violet ramps as expressive HCT palettes (Material Theme Builder) with secondary *and* tertiary voices; keep Monochrome as zinc-neutral ramp with status accents as the only chroma. Keep AMOLED overlay. |
| 1.2 | **Token plumbing**: sweep layouts replacing `@color/vf_*` widget references with theme attrs (`?attr/colorPrimary`, `?attr/colorOnSurfaceVariant`, `?attr/colorSurfaceContainer*`); eliminate the 17 hardcoded hexes (scrim → `?attr/scrimColor`-based drawable, etc.). `vf_*` colors remain as the palette source of truth inside theme overlays only. |
| 1.3 | **Shape scale**: adopt expressive scale (0/4/8/12/16/20/28/32/48/full) via `shapeCornerSize*` overrides; publish `ShapeAppearance.VeilFrame.{XS…XXL,Pill,Sheet,Dialog}`; map the 10 rogue button radii + 10 card radii onto the scale (see 04 §3). |
| 1.4 | **Type**: adopt baseline + **emphasized** scales; define VeilFrame text styles (`TextAppearance.VeilFrame.SectionHeader` = TitleSmallEmphasized etc.); plan mechanical replacement of raw `textSize` literals (178×11sp, 103×12sp, 100×13sp, 47×9–10sp) per §Phase 3 sweep rules; ALL-CAPS micro-labels → title-case emphasized small. |
| 1.5 | **Motion**: rewire `MotionSpec` to resolve library springs (`MotionUtils.resolveThemeSpringForce`) + duration/easing attrs; keep named tiers (FAST/NORMAL/EXPRESSIVE/MORPH) mapped to tokens (`motionDurationShort4/Medium1/Medium3/Medium4`). |
| 1.6 | **Dark/AMOLED re-check**: container ladder + tertiary contrast audit in night overlays (WCAG AA on every text/icon pairing). |

**Exit criteria**: theme switching (Light/Dark/AMOLED × 6 color sources × 3 typefaces) renders fully token-driven; screenshot diff reviewed.

## Phase 2 — App Shell  *(est. 4–5 days)*

| # | Element | Migration |
|---|---|---|
| 2.1 | **BottomNavigationView** | Adopt `Widget.Material3Expressive.BottomNavigationView` (theme default). **Delete `bottom_nav_color_selector` + `itemIconTint/itemTextColor` overrides** so secondary-colored active labels/56dp pill indicator apply. Height 64dp → fix dock/inset math (0.6). |
| 2.2 | **NavigationRailView** | Expressive collapsed rail styling; ≥840dp: evaluate **expanded rail** (replaces nothing today — settings stays a sheet) for TOOLS/LIBRARY destinations; horizontal-item bottom nav covers 600–840dp automatically. |
| 2.3 | **Toolbars** | Small app bar expressive styling; `toolbarHome`: version badge → pill chip (`ShapeAppearance…Full`, `colorSurfaceContainerHighest`, LabelSmall); icon buttons → expressive sizes (40dp, round); `toolbarTool`: title → `TitleLarge` (not 15sp bold), subtitle → `BodySmall`, status badge → tonal pill chip w/ semantic color + icon. |
| 2.4 | **Home dashboard** | Containment pass: section headers → `TitleMediumEmphasized` + 20dp top spacing; tool cards → `colorSurfaceContainer`/`Low` ladder by importance, radius XL 28dp (hero) / L 16dp (list cards), no stroke where container contrast suffices; hero card = **Hero Moment #1 candidate** (XXL 48dp asymmetric top-rounding, tertiaryContainer wash, DisplaySmallEmphasized title, Large filled CTA). Staggered spring entrance (28dp rise, 40ms stagger, fast spatial spring). |
| 2.5 | **Action dock** | `btnExecute` → XL expressive filled pill (56dp+, morph-on-press built-in); secondaries → Medium tonal 48dp (fixes 42dp target); dock card → XL-increased 32dp, `colorSurfaceContainerHigh`; keep `FloatingDockStateController` state machine, re-timed on default-spatial spring. |
| 2.6 | **Settings slide-over** | Convert to `BottomSheetDialogFragment` on compact / keep side-sheet on expanded; expressive drag handle; default-spatial spring settle; scrim from token. Content: theme-mode radios → **connected ButtonGroup** (checkable morph); palette chips → expressive filter chips w/ shape-morph-on-select; typography radios → ButtonGroup. |
| 2.7 | **Tools catalogue** | Search field → `Widget.Material3Expressive.SearchBar`/`SearchView.Toolbar` (keeps synonym index); grid cards → segmented expressive list styling on LIBRARY, card grid retained for TOOLS (pattern continuity per research guardrails). |

## Phase 3 — Component Sweep (all 37 layouts)  *(est. 5–7 days, parallelizable per workspace)*

Mechanical mapping rules (see `04_DESIGN_SYSTEM_V3.md` §6 for the full table):

| Current | → Expressive target |
|---|---|
| `Widget.Material3.Button` (28 filled) | drop style → inherit `materialButtonStyle` default; role-based size overlays: primary action = Large/Xlarge; form submit = Medium |
| `…Button.TonalButton` (74) | inherit `materialButtonTonalStyle`; secondary actions Medium (48dp) |
| `…Button.OutlinedButton` (64) | outlined expressive; utility/danger actions Small–Medium |
| `…Button.TextButton` (51) | text expressive; dialog buttons keep Pill overlay |
| `…Button.IconButton` (59, many 38dp) | `Widget.Material3Expressive.Button.Icon` Small (40dp) min; toolbar = Small, workspace = Medium |
| cornerRadius 8–26dp chaos | remove all `app:cornerRadius` — shape comes from style (round default; `.Square` overlays only for deliberate tension: e.g., EXECUTE pill vs square secondary pair) |
| 10× `LinearProgressIndicator` | **thick 8dp** expressive default for determinate; **`.Wavy` indeterminate** for AI/video/model-download waits; add stop indicator on determinate; `CircularProgressIndicator.Wavy` for inline busy states (replace text-only "Processing…") |
| 71× `Slider` | `Widget.Material3Expressive.Slider.Medium` default; `Large` for hero controls (video trim RangeSlider, before/after threshold, upscale scale); `Small` inside dense dialogs |
| Chips (filter/suggest) | inherit expressive chip defaults — free shape-morph-on-select; palette chips get color dot |
| 4× ToggleGroup | `Widget.Material3Expressive.MaterialButtonGroup.Connected` (export modes, aspect ratios, edit-scope radios) |
| 2× TabLayout (QR studio) | keep (pattern continuity), restyle tokens; primary tabs get emphasized label |
| 2× FAB | expressive primary/secondary tone; small→medium; doc-scanner shutter = Large primary (Hero Moment #2) |
| 99× TextInputLayout | filled expressive default (container color), outlined for dialogs; fix labels to BodyLarge |
| Dialogs (43 call sites) | keep 28dp XL shape overlay (already expressive-aligned); buttons inherit expressive dialog styles; `MorphDialogController` origin-morph retained, spring-refactored (fast spatial + overshoot ≤1.1) |
| Sheets (source picker, export) | expressive drag handle + container surface; export options as segmented list + connected button groups; spring settle |
| Library lists | **segmented expressive lists** (Recents / Sessions / Exports segments) |
| Raw `textSize` ×~500 | sweep to `textAppearance` refs per mapping (9–10sp→LabelSmall 11sp min; 11–12sp→LabelMedium/BodySmall; 13sp→BodyMedium; 14–15sp→BodyLarge/TitleSmall; 16sp→TitleMedium; 18–22sp→TitleLarge/HeadlineSmall; 36sp→DisplaySmallEmphasized) — scripted codemod + manual review |
| `@color/vf_*` in widgets (hundreds) | `?attr/` roles (script + review); semantic status colors stay as `vf_status_*` but get **container+on-container pairs** and an icon (color-blind safe) |

## Phase 4 — Motion & "Alive" Layer  *(est. 4–5 days)*

| # | Item | Spec |
|---|---|---|
| 4.1 | **Spring refactor** of `ExpressiveMotion`: `SpringAnimation` + `MotionUtils.resolveThemeSpringForce`; press = fast-spatial (scale 0.94) + fast-effects (color); release springs back (no fixed 180ms) | `code/VfExpressiveMotion.kt` |
| 4.2 | **Navigation transitions**: home→workspace becomes container-transform-style (card bounds → workspace surface, shared-element on icon/title; slow-spatial spring); back reverses. Replaces 28dp slide+fade. Fallback slide retained for reduced-motion/off-screen origins | `NavigationMotionController` |
| 4.3 | **Dock/execute sequence = Hero Moment #1**: READY (XL pill) → PROCESSING (button width morphs into thick wavy progress inside dock card + stage labels crossfade w/ emphasized type) → COMPLETED (jelly 320ms + check-morph icon + `CLOCK_TICK`→`CONFIRM` haptic + tonal Save/Share rise-in). All spring-driven; reduced-motion = instant states + haptics | `FloatingDockStateController`, `ProcessingMotionController` |
| 4.4 | **Doc scanner = Hero Moment #2**: shutter FAB Large; capture = fast-spring scale pulse + page card flies to thumbnail strip (slow-spatial, emphasized decel) + thumbnail jelly; quad-detected overlay corners spring-settle; status pill morphs Searching→Detected→Ready (shape morph + effects spring) | document scanner layout/controllers |
| 4.5 | **Micro-interactions**: theme-toggle icon 180° rotate+fade morph (fast spatial); nav-item selection = indicator spring (library built-in); chip select morph (built-in); card hover/press 0.98 spring (existing, re-sprung); staggered card entrance on Home/Tools/Library first-frame only | various |
| 4.6 | **Haptics vocabulary**: selection tick (chips/radios/sliders detents), confirm (execute/save), success (export/scan), warning (quality-gate fail = `LONG_PRESS`), error (double-tick). No sound (privacy product) | central `Haptics` object |
| 4.7 | **Reduced-motion contract** (keep & extend): all springs collapse to ≤100ms fades or instant; jelly → single 8% scale pulse; wavy progress → standard indeterminate; stagger off; haptics retained | `ExpressiveMotion.isReducedMotion` gate everywhere |

## Phase 5 — Accessibility & Contrast Hardening  *(est. 2–3 days)*

- Touch targets ≥48dp primary / ≥40dp icon; audit all 59 icon buttons & dock.
- Contrast: WCAG AA sweep across **6 color sources × 3 modes** (Light/Dark/AMOLED) incl. Monochrome tertiary + status pairs; text ≥12sp body / ≥11sp label (kills 9–10sp).
- Color-independent status: every pass/warn/fail/info state keeps icon + text (never hue alone).
- TalkBack: labels for search bar, button groups, wavy progress (announce % + stage), split view (existing), expanded rail; live-region for processing stage changes.
- Emphasized type respects user font-scale (sp everywhere; no fixed dp text heights).

## Phase 6 — Rollout, Verification, ADR  *(continuous)*

- **PR sequencing** (each independently shippable): P0 toolchain → P1 tokens → P2 shell → P3 per-workspace sweeps (13 PRs max, ordered: Home/Tools/Library → Image Studio → Video Studio → Doc Scanner → BG Remover → Quality → Upscaler → QR → Cleaner×2 → AI Bundle → Markdown → Provenance) → P4 motion heroes → P5 a11y.
- **Verification**: screenshot tests (Paparazzi/Roborazzi) per palette×mode matrix; FrameMetrics jank budget (<5ms median frame on mid-tier during dock morph & scan fly-in); `adb shell settings put global animator_duration_scale 0` reduced-motion pass; tablet (600/840dp) + foldable pass; 3-button vs gesture nav.
- **ADR 0005** "M3 Expressive Design System" recording: expressive-parent decision, monochrome-first stance, hero-moment budget (2), spring adoption, non-goals (no Compose, no sound, no decorative 35-shape library usage beyond hero containers).
- **Metrics post-release**: crash-free sessions, jank rate, task time on EXECUTE flow, theme-usage distribution (monochrome vs palettes vs dynamic), Play-store UX sentiment.

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Material 1.14.0 needs newer AGP/Gradle; `:opencv-sdk` module churn | Build breaks | P0.2 isolated PR; CI matrix first; library AAR itself only needs minSdk 23 ✓ |
| Non-transitive R breaks `material.R` refs | Compile errors | mechanical fully-qualify sweep (P0.4) |
| Removing custom nav tint selector changes brand feel in Monochrome (secondary label = zinc, not primary black) | Brand dilution | Monochrome overlay sets `colorSecondary` = zinc-600 → active labels stay strong; per-palette secondary tuned in P1.1 |
| Bottom nav 80→64dp + dock overlap | Layout clipping | P0.6 inset audit + visual pass on 5 device configs |
| Expressive default styles silently restyle pinned `Widget.Material3.*` references inconsistently | Mixed looks mid-migration | P3 sweeps remove explicit styles workspace-by-workspace; expressive theme keeps M3 styles available during transition |
| Spring physics perf on low-end during wavy progress + fly-ins | Jank | wavy indicator is library-rendered (GPU); cap concurrent springs; FrameMetrics budget; fall back to interpolators below API 26? (minSdk is 26 → non-issue) |
| "Too playful" for a forensics app | Brand mismatch | Research-backed: calmer variant chosen; hero budget = 2; chroma only in semantic states; monochrome default retained |
| User familiarity dip (research noted) | Short-term usability perception | Keep patterns (lists stay lists, tabs stay tabs); change skin/physics, not IA; ship in one coherent release, not drip-fed A/B |

## Definition of Done (whole migration)

1. Theme parent expressive; zero `Widget.Material3.` explicit style pins except deliberate legacy holds (documented).
2. Zero hardcoded hex in layouts; zero raw `textSize` on new/edited screens (codemod ≥90% of存量).
3. All springs themed; reduced-motion contract 100% coverage; haptic vocabulary centralized.
4. 6 color sources × 3 modes screenshot matrix green; AA contrast report attached to ADR 0005.
5. Two hero moments shipped (EXECUTE dock, doc-scan capture); wavy progress on all waits >2s.
6. Nav bar/rail expressive defaults; tablet horizontal items verified.
