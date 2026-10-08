# M3 Expressive — Research Digest for VeilFrame

Sources: the two provided links (fully read), m3.material.io style/component docs, Material Components Android **1.14.0** docs & library sources (fetched from GitHub), community discussions.

---

## 1. What M3 Expressive is (blog: "Start building with Material 3 Expressive", May 13 2025)

- An **evolution of M3, not "M4"** — nothing deprecated wholesale; new features, updated components, and design tactics for *emotionally impactful UX*.
- Most-researched update since 2014: **46 studies, 18,000+ participants**.
- Key findings: expressive preferred across all ages; scores higher on playfulness/energy/creativity/friendliness; users more likely to switch to products using it; **key UI elements spotted up to 4× faster**.
- **14 new/updated components**: App bars, **Button groups (new)**, Common buttons, Extended FAB, **FAB menu (new)**, FABs, Icon buttons, **Loading indicator (new)**, Navigation bar, Navigation rail, Progress indicators, Sliders, **Split button (new)**, **Toolbars (new)**.
- **Style systems**: motion-physics springs (spatial vs effects), emphasized typography (variable/static), expanded shape library (35 decorative shapes + **shape-morph animation**), vibrant color schemes.
- **7 expressive tactics**: ① variety of shapes (mix round/square for tension; break shape style to draw attention; caution: small shapes demote important actions) ② rich, nuanced color (mix primary/secondary/tertiary roles + surface tones for hierarchy; contrast emphasizes the takeaway) ③ typography guides attention (emphasized styles for headlines/actions; editorial moments) ④ **containment** (group content in containers; most important content gets the brightest surface mapping + ample space) ⑤ fluid natural motion (shape morph, surface effects, springs, micro-animations) ⑥ component flexibility (adapt controls to context/window size; canonical layouts for large screens) ⑦ **hero moments** — combine tactics for 1–2 signature interactions; brief, delightful, surprising; too many overwhelms.

## 2. Research findings (design.google: "Better, Easier, Emotional UX")

- Expressive design's fundamentals = **color, shape, size, motion, containment** — the same axes that drive usability (attention steering + grouping).
- Methods: eye-tracking, surveys/focus groups, experiments, usability tests.
- Preference: net-positive all ages, **up to 87% among 18–24s**. Desirability: **+32% subculture, +34% modernity, +30% rebelliousness** (switch-driving attributes, per Warren et al. 2019 framework).
- Usability: elements spotted **4× faster**; email case study — big secondary-colored Send button above keyboard vs small toolbar button; tap time dropped by seconds. **Age effects erased**: 45+ users fixate as fast as younger users. Better for users with varying movement/visual abilities (larger buttons, high-contrast containment).
- Component-level studies: progress indicators optimized so **waiting feels faster** and premium; button size pushed for faster taps without overwhelming; floating **toolbar** optimized for modern/clean/energetic + noticeable; accessibility standards met or exceeded.
- **Guardrails**: context matters — don't break established patterns (scattered album-art playlist concept looked modern but usability collapsed; removing text labels hurt). A strong minority prefers calmer versions — start from user need. Prioritize functionality; follow a11y; iterate.
- VeilFrame read-through: a privacy-forensics tool should land on the **"calmer, intentional" end of expressive** — motion/shape/size/containment carry the emotion; color stays disciplined (monochrome-first works *with* the research, not against it).

## 3. Motion system (m3.material.io/styles/motion + MDC-Android `theming/Motion.md`)

- **Springs** (physics system, library ≥1.13.0; used with `androidx.dynamicanimation`): six themed slots — fast/default/slow × spatial/effects:
  | Slot | Expressive values (damping/stiffness) | Use |
  |---|---|---|
  | `motionSpringFastSpatial` | 0.6 / 800 (std: 0.9/1400) | small components: buttons, switches |
  | `motionSpringFastEffects` | 1.0 / 3800 | color/opacity on small components |
  | `motionSpringDefaultSpatial` | 0.8 / 380 (std: 0.9/700) | partial-screen: bottom sheets, nav drawer |
  | `motionSpringDefaultEffects` | 1.0 / 1600 | effects for partial-screen |
  | `motionSpringSlowSpatial` | 0.8 / 200 (std: 0.9/300) | full-screen transitions |
  | `motionSpringSlowEffects` | 1.0 / 800 | full-screen effects |
  - Resolve via `MotionUtils.resolveThemeSpringForce(context, R.attr.motionSpringFastSpatial)` → configure `SpringForce` of a `SpringAnimation`. Expressive themes automatically re-point these slots to the springier expressive values (lower stiffness, underdamped → lively settle).
  - Pair spatial+effects springs per element (e.g., button press: fast spatial for shape/size, fast effects for color).
- **Easing** (7 attrs): standard `cubic-bezier(0.2,0,0,1)`, standard-decel `(0,0,0,1)`, standard-accel `(0.3,0,1,1)`, emphasized path `M0,0 C0.05,0 0.133333,0.06 0.166666,0.4 C0.208333,0.82 0.25,1 1,1`, emphasized-decel `(0.05,0.7,0.1,1)`, emphasized-accel `(0.3,0,0.8,0.15)`, linear.
- **Durations**: 16 tokens 50–1000ms (short1-4 50/100/150/200, medium1-4 250–400, long1-4 450–600, extraLong1-4 700–1000); duration grows with traversal distance.
- ✅ VeilFrame's `motion_tokens.xml` already mirrors these values exactly — migration = *consume* them (or drop them and use library attrs).

## 4. Shape (m3.material.io/styles/shape)

- Expressive corner scale adds tokens: **none 0 · XS 4 · S 8 · M 12 · L 16 · L-increased 20 · XL 28 · XL-increased 32 · XXL 48 · full**.
- 35-shape decorative library (abstract silhouettes for images/avatars) + asymmetric variants (top-only, start/end-only rounding) + built-in **shape morph** (e.g., square↔circle).
- Library exposes `shapeCornerSize{ExtraSmall…ExtraExtraLarge}` theme attrs; buttons morph shape pressed/checked via `ShapeAppearance.M3.Comp.Button.*` state lists.

## 5. Typography

- Baseline 15-style scale unchanged (Display/Headline/Title/Body/Label ×L/M/S).
- **New emphasized scale** (15 more styles): Display→Label, weights bumped (e.g., TitleMedium **Bold** 16sp, TitleSmall Bold 14sp, Body* Medium). Theme attrs `textAppearance*Emphasized`; library styles `TextAppearance.Material3.*.Emphasized`. Use for selection, actions, headlines, editorial moments.

## 6. Color

- Same M3 role system, **used more boldly**: mix primary/secondary/**tertiary** roles; surface-container ladder for containment hierarchy (lowest→highest); vibrant palettes; Dynamic Color fully supported (expressive dynamic variants exist: `Theme.Material3Expressive.DynamicColors.*`).
- Contrast between roles prioritizes actions; containers group and elevate the key task ("brightest surface mapping" for most important content).

## 7. Component specs relevant to VeilFrame (MDC-Android 1.14.0 stable, May 13 2026 release)

1.14.0 ships **Expressive Themes + expressive styles for Views**: Lists, Buttons, Icon Buttons, Button Groups, FABs, Top App Bars, BottomNavigationView, NavigationRail, Search, Progress Indicators, Sliders, Emphasized Typescale. Requirements: theme parent `Theme.Material3Expressive.*` for `Widget.Material3Expressive.*` styles; minSdk ≥23 (VeilFrame: 26 ✓); library built with AGP 8.11.1/Gradle 8.13; non-transitive R classes (reference library R fully-qualified).

| Component | Expressive changes (Views) | Key styles |
|---|---|---|
| **Buttons** | 5 sizes via `SizeOverlay.Material3Expressive.Button.{Xsmall·Small(default)·Medium·Large·Xlarge}`; **round & square shapes**; **shape morphs on press and on selection** (state-list shapeAppearance); toggle/checkable variants; small-button padding 16dp (24dp deprecated); default min height 48dp (`m3expressive_btn_min_height`); color styles are configurations (filled/tonal/outlined/elevated/text) resolved through theme attrs `materialButtonStyle`, `materialButtonTonalStyle`, etc. | `Widget.Material3Expressive.Button[.TonalButton/.OutlinedButton/.ElevatedButton/.TextButton/.Icon]` |
| **Icon buttons** | 5 sizes × 3 widths (narrow/default/wide) × round/square; morph on press/selected | `SizeOverlay.Material3Expressive.Button.IconButton.*` |
| **Button groups** | New connected groups (split-button equivalent in Views) | `Widget.Material3Expressive.MaterialButtonGroup[.Connected]` |
| **Navigation bar** | Height **80→64dp**; active label color on-surface-variant→**secondary**; top/bottom item padding 12/16→6dp; labels no longer bold when selected; active indicator 64→**56dp**; **horizontal item layout at ≥600dp windows** (icon at start, content-width items); container `?attr/colorSurfaceContainer`, elevation 3dp | `Widget.Material3Expressive.BottomNavigationView` (default via `bottomNavigationStyle` in expressive themes) |
| **Navigation rail** | Collapsed (existing) + **expanded rail replaces nav drawer** (`expandedWidth/expandedHeight/expandedItemMinHeight/expanded*` attrs); active label → secondary | `Widget.Material3Expressive.NavigationRailView` |
| **Progress indicators** | Rounded colorful default; **thick track 8dp** (`trackThickness=8dp` + `trackCornerRadius=4dp`); **wavy** variants (determinate+indeterminate) that make long waits feel alive; wavy linear increases container height | `Widget.Material3Expressive.LinearProgressIndicator[.Wavy]`, `…CircularProgressIndicator[.Wavy]` |
| **Sliders** | 5 sizes (Xsmall→Xlarge): track height, thumb height, track corner, optional track icons | `Widget.Material3Expressive.Slider.{Xsmall…Xlarge}` |
| **App bars** | Renamed "app bar"; small app bar gains subtitle/center-aligned/flexible content; **medium/large flexible** replace deprecated medium/large; **search app bar** opens SearchView | `Widget.Material3Expressive.SearchBar[.CenteredText/.AppBarWithSearch]`, `…SearchView.Toolbar` |
| **FAB** | New medium size; small deprecated; tone color styles primary/secondary/tertiary (container styles renamed); surface FABs deprecated | `Widget.Material3Expressive.FloatingActionButton.*` |
| **Lists** | Standard + **segmented** variants (colors/shapes updated) | see `docs/components/List.md` |
| Compose-only (no Views equivalent yet) | **Loading indicator** (4-arc orbital), **FAB menu**, floating **Toolbar**, **Split button** (use connected ButtonGroup instead) | — |

## 8. Community/forum notes

- Expressive bottom-nav horizontal layout applies automatically at ≥600dp; custom `itemIconTint`/`itemTextColor` selectors (like VeilFrame's `bottom_nav_color_selector`) **override and break** the new secondary-label behavior → remove custom selectors, use theme defaults.
- Views migration stories echo Compose migrations: the theme switch is easy; the long tail is (a) explicit `Widget.Material3.*` style references that pin old looks, (b) hardcoded colors/sizes that ignore tokens, (c) inset math when nav bar height changes 80→64dp.
- Known caution: mixing `Theme.Material3.*` parents with `Widget.Material3Expressive.*` styles is unsupported — the expressive theme must be the parent.
- `android.nonTransitiveRClass=true` since 1.13.0-alpha12: fully-qualify library R references (`com.google.android.material.R.attr.motionSpringFastSpatial`).

## 9. Synthesis — what "alive & expressive" means for VeilFrame

1. **Monochrome-first expressive** is viable and on-brand: emotion via **shape contrast** (pill vs square vs 48dp XXL containers), **size contrast** (XL execute button, emphasized type), **surface-container containment** (5-step ladder in both light & dark), and **spring motion** — with the existing semantic accents (sage/amber/terracotta/steel) as the only chroma pops, and tertiary role added per palette for a second accent voice.
2. **Hero moments** (pick 2): the **EXECUTE→PROCESSING→COMPLETED dock sequence** (button morph + wavy progress + jelly success + haptic) and **document-scan capture** (shutter spring + page fly-in). Everything else stays disciplined — matching the research's "calmer minority" and VeilFrame's forensic-serious positioning.
3. **Waiting = alive**: wavy/thick progress for AI upscale, video transcode, model downloads (research: waiting feels faster).
4. **Springs replace overshoot interpolators**: adopt `DynamicAnimation` + themed spring slots; keep jelly as the signature completion flourish; keep reduced-motion collapse.
5. **Size/contrast for usability**: 4× faster element spotting justifies bigger primary actions (≥56dp XL execute), 40dp min icon buttons, secondary-colored active nav labels, emphasized titles instead of ALL-CAPS 10-11sp micro-labels.
