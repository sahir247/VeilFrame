# ADR 0005: Material 3 Expressive Design System ("Quiet Intensity" v3.0)

## Status

Status: Accepted
Implementation Baseline: `v2.3.0-rc1` (branch `release/v2.3.0-expressive-rc1`)
Date: October 2026
Supersedes-in-part: ADR 0004 (UI redesign architecture — remains binding: no Compose rewrite)

---

## 1. Context

ADR 0004 locked the ViewBinding + Material Components architecture and shipped the
13-workspace shell. The shell carried early expressive seeds (28dp dialogs, jelly bounce,
morph dialogs, hand-declared M3 Expressive spring tokens in `motion_tokens.xml`) but the
foundation remained `Theme.Material3.*` on Material Components **1.12.0**:

- No expressive components (buttons/nav/progress/sliders) — all `Widget.Material3.*` pins.
- Physics via `OvershootInterpolator`/`DecelerateInterpolator`, not springs; declared tokens unconsumed.
- ~500 raw `textSize` literals (9–36sp), ALL-CAPS micro-labels, 10 button radii / 10 card radii variants.
- Colors wired directly to `@color/vf_*` in widgets → Dynamic Color & palette overlays partially bypassed.
- Legacy 80dp navigation bar with custom tint selector; 38dp icon buttons; 42dp secondary actions; no circular/wavy progress; light mode lacked the surface-container ladder; no tertiary color role.

Material Components Android **1.14.0** (stable) ships the full M3 Expressive system for
Views: `Theme.Material3Expressive.*`, expressive buttons/icon buttons/button groups
(5 sizes, round/square, press & select shape-morph), 64dp navigation bar with 56dp pill
indicator and horizontal items ≥600dp, collapsed/expanded navigation rail, thick (8dp)
and **wavy** progress indicators, expressive sliders, search bar, expressive lists, and
the emphasized typescale. M3 Expressive is the most-researched update to Material
(46 studies, 18k+ participants): key elements spotted up to 4× faster; age-related
fixation gaps erased; desirability +30–34%.

## 2. Decision

Adopt M3 Expressive as the VeilFrame design system under a **"Quiet Intensity"** profile:
forensic-grade calm with spring-loaded energy. Expressiveness is carried by **shape,
size, motion, and containment**; **chroma is reserved for meaning**.

1. **Theme foundation**: `Theme.VeilFrame` re-parented to `Theme.Material3Expressive.DayNight.NoActionBar`.
   Material 1.12.0 → 1.14.0; `androidx.dynamicanimation` added; core-ktx/appcompat/constraintlayout
   aligned to 1.14.0's dependency floor. AGP/Gradle unchanged (8.8.2/8.10.2 — 1.14.0 is built with
   compileSdk 35 and imposes no higher consumer requirement; fallback documented below).
2. **Monochrome Classic remains the flagship theme** — expressive through the 5-step
   surface-container ladder (now also in light mode), shape tension (pill vs square, 48dp
   hero top-rounding), size tiers, emphasized typography, and spring physics — not hue.
3. **Color**: every palette (Monochrome + Sage/Ocean/Amber/Violet, light/night) gains a
   **tertiary** editorial voice; full container ladder + outline roles tokenized; semantic
   status colors (pass/warn/fail/info) stay palette-invariant and always ship as
   container+on-container+icon pairs. Widgets consume theme roles (`?attr/color*`), never raw hexes.
4. **Components**: all 290 buttons remapped to VeilFrame role styles over
   `Widget.Material3Expressive.*` (Execute=XL pill, CTA=L, Action=tonal M 48dp,
   Utility=outlined S, Quiet=text, icon buttons 40/48dp); explicit radii/backgroundTints/
   textColors stripped so theme roles and Dynamic Color flow; progress indicators default
   to thick 8dp with **wavy** for indeterminate waits; sliders default to expressive Medium
   (library default Xsmall overridden — deliberate for a media-editing product);
   bottom nav/rail use expressive theme defaults (custom tint selector deleted).
5. **Motion**: real spring physics via `VfSprings` (themed slots resolved with
   `MotionUtils.resolveThemeSpringForce`: fast 0.6/800, default 0.8/380, slow 0.8/200 spatial;
   1.0/3800–800 effects). `ExpressiveMotion` keeps its public API and delegates. Jelly
   completion flourish retained (re-implemented as underdamped spring, damping 0.35).
   Hand-copied `motion_tokens.xml` deleted — library tokens are now the source of truth.
   Reduced-motion contract preserved everywhere (springs → instant; haptics retained).
6. **Typography**: baseline + emphasized scales consumed via `?attr/textAppearance*`;
   chrome section headers → `TitleSmallEmphasized` title-case; card titles →
   `BodyLargeEmphasized`; 9/10sp text raised to an 11sp floor. Runtime/telemetry labels
   keep ALL-CAPS terminal identity (deliberate brand dual-voice).
7. **Hero budget = 2**: (1) EXECUTE dock sequence (READY → PROCESSING wavy → COMPLETED
   jelly+haptic), (2) document-scan capture (shutter spring + page fly-in). No sound, ever.

## 3. Consequences

**Positive**
- Token-driven theming: Dynamic Color, 5 palettes × Light/Dark/AMOLED × 3 typefaces all flow through roles.
- Library-maintained physics/components replace ~600 lines of hand-rolled motion constants.
- Usability: 40dp+ icon targets, 48dp secondary actions, 11sp text floor, secondary-colored nav labels, thick/wavy progress for long waits.
- Forward compatibility: expressive themes are the library's default direction; M3 pins would drift.

**Negative / Risks**
- Material 1.14.0 is new-stable; residual visual regressions possible where layouts pinned
  legacy looks → mitigated by role-style remap + screenshot matrix (P5 in migration plan).
- Bottom nav height 80→64dp changes inset math — audited (`MainActivity` pads via insets listener).
- If AAR metadata ever demands newer AGP: fallback = AGP 8.11.1 + Gradle 8.13 + Kotlin 2.1.x (one-line bumps, CI-compatible).

**Neutral**
- `Widget.Material3.*` styles remain available for deliberate legacy holds (TabLayout, chips —
  no expressive variants exist for chips/text fields in 1.14.0; theme defaults apply).

## 4. Verification (release-candidate gate)

Cannot compile in analysis sandbox (OpenCV SDK artifacts generated out-of-tree). RC gate =
1. `./gradlew :app:assembleDebug` on maintainer machine / CI (JDK 17, android-35).
2. Resource audit performed: all layout XML well-formed; every `@style/@color/@drawable/?attr`
   reference resolves (app, Material 1.14.0, or framework); no dangling refs to deleted
   `motion_tokens.xml` / `bottom_nav_color_selector.xml`.
3. Visual matrix: {Light, Dark, AMOLED} × {Dynamic, Monochrome, Sage, Ocean, Amber, Violet}
   on Home, Tools, Library, one studio, one dialog, one sheet; reduced-motion pass
   (`adb shell settings put global animator_duration_scale 0`).
4. `testDebugUnitTest` suite green (no unit tests touch changed resources; Tier-4 oracle untouched).
