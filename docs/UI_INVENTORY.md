# VeilFrame Android — Current UI Inventory (Baseline Audit)

> Source: `github.com/sahir247/VeilFrame` @ `main` (v2.2.9, commit `8f090a7`, Oct 7 2026)
> Stack: **Native Views + XML + ViewBinding** (ADR 0004 forbids Compose rewrite) · `com.google.android.material:material:1.12.0` · minSdk 26 · compileSdk/targetSdk 35 · AGP 8.8.2 / Gradle 8.10.2 · Kotlin 2.0.21

---

## 1. App Shell & Layout Architecture

`activity_main.xml` is a single `CoordinatorLayout` hosting everything (37 layouts total, ~20k lines of XML across the res folder):

| Layer | Element | Current state |
|---|---|---|
| Top | `AppBarLayout` + **dual `MaterialToolbar`** (`toolbarHome`, `toolbarTool`) | 56dp, flat `colorSurface`, elevation 2dp. Home bar: brand logo + "VeilFrame" 16sp bold + version badge (square, unstyled bg) + theme-toggle & settings icon buttons (38dp). Tool bar: back (44dp) + title/subtitle + status badge + icons |
| Body | `NestedScrollView` home dashboard | Hero card (`cardHeroUpscaler`, AI Upscaler promo) + section headers ("MEDIA STUDIOS", "PRIVACY CLEANERS", …) + full-width tool cards (icon + ALL-CAPS title + 1-line subtitle + chevron) |
| Workspaces | 13 `<include>`d workspace layouts | image/video studio, QR fragment host, markdown viewer, upscaler, tools catalogue, library, document scanner, background remover, image quality, provenance |
| Action dock | `cardCleanerActionDock` — sticky 2-tier `MaterialCardView` (28dp radius, 10dp elevation, 1dp stroke) | Tier 1: `btnExecute` full-width filled 48dp, `cornerRadius=24dp`, ALL-CAPS "EXECUTE". Tier 2: `btnExportResult` + `btnShareResult` tonal 42dp, radius 21dp |
| Nav (compact <600dp) | `BottomNavigationView` | 3 items (Home / Tools / Library), legacy 80dp height, custom `bottom_nav_color_selector` (checked → `vf_primary`), surface bg, elevation 8dp |
| Nav (≥600dp) | `NavigationRailView` | Same menu/tint, toggled in `MainNavigationController` |
| Settings | Slide-over overlay (`scrimSettings` + `containerSettings` incl. `layout_settings_panel`) | Custom right-edge slide (280ms) + swipe-to-dismiss in `NavigationMotionController`; scrim `#99000000` hardcoded |

Insets: manual `setOnApplyWindowInsetsListener` padding on root/bottomNav/rail (`MainActivity.kt:372–384`). Edge-to-edge is partially hand-rolled (status/nav bar colors set in theme).

## 2. Theming System (`ThemeSettingsManager` + `themes.xml` + `colors.xml`)

- **Theme**: `Theme.VeilFrame` → parent `Theme.Material3.DayNight.NoActionBar` (⚠️ non-expressive).
- **Theme modes**: SYSTEM / LIGHT / DARK / **AMOLED** (true-black overlay). Dark overlay defines full `colorSurfaceContainer*` ladder (#0E0E10 → #2C2C32); **light mode has no container ladder** (only `vf_surface` #FFFFFF / `vf_surface_variant` #EAEAEA).
- **Accent palettes** (ThemeOverlay styles, applied when Dynamic Color off): **MONOCHROME (default brand — zinc neutrals #18181B / #E4E4E7)**, SAGE (#2E7D32), OCEAN (#1976D2), AMBER (#D97706), VIOLET (#7C3AED), each with night variants. Only primary/secondary roles are overridden — **no tertiary role anywhere**.
- **Material You**: `DynamicColors.applyToActivityIfAvailable()` per-Activity, pref default ON, takes precedence over palettes.
- **Status/semantic colors** (fixed, not themed): pass `#2E7D32` sage-green, warn `#B45309` amber, fail `#C53030` terracotta, info `#2B6CB0` steel — with tinted backgrounds (`vf_status_*_bg`). Console/telemetry keeps a terminal-dark aesthetic.
- **Typography**: no type scale. 3 user-selectable typefaces (sans / "Forensic Terminal" mono / serif) applied by recursive typeface swap post-inflate.
- **Existing expressive seeds**: `Widget.VeilFrame.Button.Pill` (50% corner), dialog overlay with **28dp XL shape**, `Widget.VeilFrame.LinearProgressIndicator` (trackCornerRadius 8dp), `values/motion_tokens.xml` — a **complete copy of M3 Expressive spring tokens** (expressive fast spatial 0.6/800, default 0.8/380, slow 0.8/200; effects 1.0/3800–800) + emphasized easing paths + 16 duration tokens. These are declared but only partially consumed.

## 3. Component Census (all layouts)

| Component | Count | Notes |
|---|---|---|
| `MaterialButton` | **290** in 34 files | 74× Tonal, 64× Outlined, 59× IconButton, 51× Text, 28× Filled, 1× Elevated, 1× IconButton.Filled. All reference `Widget.Material3.*` styles explicitly. Common overrides: `app:backgroundTint="@color/vf_primary"`, `textColor=@color/vf_*` (bypasses theme roles → breaks Dynamic Color) |
| `MaterialCardView` | **192** in 17 files | radii: 16dp ×40, 12dp ×20, 24dp ×16, 8dp ×6, 18dp ×4, 28dp ×3, 20/19/10dp ×1 each, 0dp ×4. Frequent 1dp `vf_surface_stroke` |
| Chips | heavy use (653 raw hits incl. ChipGroup) | `Widget.Material3.Chip.Filter` (palette picker, tool filters, QR styles) |
| `Slider` | **71** in 12 files | legacy `Widget.Material3.Slider`; `trackColorActive=vf_primary`, `trackColorInactive=vf_surface_variant`; RangeSlider for video trim |
| `LinearProgressIndicator` | **10** in 7 files | 4dp default track, `indicatorColor=vf_primary`, `trackColor=vf_surface_variant`; used for AI model download, video processing, image ops, markdown load |
| `CircularProgressIndicator` | **0** | none — spinners absent; processing states use text + linear bars |
| `FloatingActionButton` | 2 | doc-scanner shutter / camera flows |
| `MaterialToolbar` | 12 in 6 files | per-workspace headers |
| `MaterialSwitch` | 14 | settings, tool options |
| `TextInputLayout` | 99 in 8 files | mixed filled/outlined |
| `MaterialButtonToggleGroup` | 4 | export modes, scanner options |
| `TabLayout` | 2 (1 file) | QR studio modes |
| Radio/Checkbox | 21 / 2 | theme mode, typography, edit scope |
| Dialogs | 20 `dialog_*.xml`, **43× `MaterialAlertDialogBuilder`** in Kotlin | custom 28dp shape overlay; `bg_popup_dialog` drawable |
| Bottom sheets | `sheet_shared_source_picker`, `sheet_document_export` | `BottomSheetDialogFragment` ×2, `BottomSheetDialog` ×4 |
| Snackbar | 5× `Snackbar.make` | |
| Custom views | `CropOverlayView`, `BeforeAfterSplitView`, `DocumentQuadOverlayView`, `QrScanOverlayView` | canvas-drawn; split view has spring settle + haptic ticks per ADR 0004 |

## 4. Motion & Interaction Subsystem (`app/ui/motion`, `app/ui/dock`)

| File | Role |
|---|---|
| `MotionSpec.kt` | Central constants: PRESS_SCALE 0.94 / 75ms; RELEASE 180ms; jelly 0.95→1.045→0.982→1.012→1.0 over 320ms keyframes; tiers FAST 120 / NORMAL 220 / EXPRESSIVE 320 / MORPH 360; interpolators: `OvershootInterpolator(1.2)`, `DecelerateInterpolator(1.5)`, M3 emphasized `PathInterpolator`s (correct control points) |
| `ExpressiveMotion.kt` | `applyTouchBounce` (scale 0.96 on down via OnTouch, spring-back on up), `applyCardSpringMotion` (0.98), confirmation haptics, `playJellyBounce` (Keyframe oscillation, reduced-motion aware), dock merge progress (alpha/scale/translationY), recursive `cancelAll`. Reduced-motion = `ANIMATOR_DURATION_SCALE == 0` |
| `VeilFrameInteraction.kt` | Auto-binds bounce to every Button/FAB/Chip/Card recursively per workspace |
| `NavigationMotionController.kt` | Home↔Tool: 28dp directional slide + crossfade (220ms emphasized); settings slide-over: 280ms + swipe-to-dismiss w/ VelocityTracker |
| `MorphDialogController.kt` | Origin-aware dialog morph: measures trigger view, translates/scales dialog card from origin with `OvershootInterpolator(1.12)`, reverse-collapse on dismiss, `DialogMotionState` machine, jelly on completion |
| `ProcessingMotionController.kt` | Crossfades processing sub-states (Preparing → Inference), drives `LinearProgressIndicator`, completion anims |
| `FloatingDockStateController.kt` | Action-dock state machine EMPTY → READY → PROCESSING → COMPLETED; primary button label/icon swaps; "Saved" auto-revert (2.5s) |

⚠️ All physics is `ViewPropertyAnimator`/`ObjectAnimator` with overshoot interpolators — **no real spring physics** (`DynamicAnimation` not used), and the declared `motion_tokens.xml` springs are not resolved via `MotionUtils`.

## 5. Screen Inventory (13 workspaces + shell)

HOME (intent dashboard) · TOOLS catalogue (2-col card grid + synonym search) · LIBRARY (recents/sessions/exports) · IMAGE STUDIO · VIDEO STUDIO · QR STUDIO (Fragment; 11 art styles) · DOCUMENT SCANNER (entry/camera/pages/editor/export) · BACKGROUND REMOVER (before/after split) · IMAGE QUALITY · IMAGE UPSCALER (model manager w/ download progress) · AI BUNDLE · MARKDOWN VIEWER · PROVENANCE · IMAGE/VIDEO CLEANER + 20 dialogs + settings panel + source-picker/export sheets.

## 6. Debt & Gap List (drives the migration plan)

1. **Library**: Material 1.12.0 → no `Theme.Material3Expressive` / `Widget.Material3Expressive.*` (available since **1.14.0 stable**).
2. **Typography**: ~500 raw `textSize` literals (11sp ×178, 12sp ×103, 13sp ×100, 9–10sp ×47 — below readable minimums); ALL-CAPS micro-labels with letterspacing instead of emphasized type styles.
3. **Color plumbing**: direct `@color/vf_*` references in widgets/layouts bypass theme roles → Dynamic Color & palette switching partially broken; 17 hardcoded hexes in layouts; light-mode surface-container ladder missing; no tertiary role.
4. **Shape chaos**: 10 button radii variants (8–26dp), 10 card radii (0–28dp) — no shape scale mapping.
5. **Touch targets**: 38dp icon buttons, 42dp secondary dock buttons (expressive minimums: 40/48dp); version/status badges unstyled squares.
6. **Progress**: no circular indicators; determinate bars are flat 4dp; no stop-indicator/wavy variants; waiting states are static.
7. **Nav**: legacy 80dp bottom bar with custom tint selector overriding M3 indicator colors; rail unused potential (no expanded rail).
8. **Motion**: springs declared but unused; overshoot interpolators ≠ physics springs; no shared-element transitions between home cards and workspaces (slide+fade only); jelly keyframes hand-tuned.
9. **Component gaps**: no ButtonGroups, no SearchBar/SearchView (tools search is a custom field), FABs unstyled legacy, lists not segmented.
10. **AGP/Gradle** (8.8.2/8.10.2) behind what Material 1.14.0 is built with (AGP 8.11.1 / Gradle 8.13); non-transitive R classes from 1.13.0-alpha12 may surface `com.google.android.material.R` compile fixes.
