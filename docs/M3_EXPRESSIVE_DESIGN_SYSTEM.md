# VeilFrame Expressive Design System v3.0 — "Quiet Intensity"

> The design-system update emphasized in the migration: **emotional engagement + usability through strategic color, shape, size, motion, and containment.**
> Personality: *forensic-grade calm, spring-loaded energy.* Monochrome-first; chroma is meaning, never decoration.
> Backed by M3 Expressive research: elements spotted 4× faster with size/color contrast; waiting feels shorter with living progress; springs read as "alive"; containment groups carry hierarchy. Calmer variants are preferred by a strong minority — VeilFrame's audience (privacy/forensics) sits squarely in that cohort, so expressiveness comes from **physics, shape and hierarchy**, with disciplined color.

---

## 1. COLOR — monochrome as a first-class expressive theme

### 1.1 Principles
1. **Monochrome Classic is the default brand voice** — full zinc neutral ramp, expressive through *tone contrast* (5-step container ladder) instead of hue.
2. **Chroma = semantics.** The only saturated colors are status: pass `sage`, warn `amber`, fail `terracotta`, info `steel` — always as **container + on-container pairs with an icon** (color-blind safe).
3. **Accent palettes gain a tertiary voice.** Each palette (Sage/Ocean/Amber/Violet) gets primary/secondary/**tertiary** — tertiary is the "editorial accent" used sparingly: hero washes, emphasized chips, active nav detail, wavy progress on long jobs. This is the M3 Expressive contrast lever without breaking the calm.
4. **Dynamic Color (Material You) stays default-ON**, expressed through the same role map — every widget consumes roles (`?attr/colorPrimary`, `?attr/colorTertiaryContainer`…), never raw hexes, so wallpaper-derived palettes "just work".
5. AMOLED keeps 1dp `vf_surface_stroke` borders (containers can't be distinguished by luminance alone on true black).

### 1.2 Monochrome Classic — token map (default)

| Role | Light | Dark | AMOLED |
|---|---|---|---|
| primary | `#18181B` | `#E4E4E7` | `#E4E4E7` |
| onPrimary | `#FFFFFF` | `#121214` | `#000000` |
| primaryContainer | `#E4E4E7` | `#27272A` | `#121216` |
| onPrimaryContainer | `#18181B` | `#F4F4F5` | `#F4F4F5` |
| secondary | `#52525B` | `#A1A1AA` | `#A1A1AA` |
| secondaryContainer | `#E4E4E7` | `#27272A` | `#16161A` |
| onSecondaryContainer | `#18181B` | `#F4F4F5` | `#F4F4F5` |
| **tertiary (new)** | `#3F3F46` | `#D4D4D8` | `#D4D4D8` |
| **tertiaryContainer (new)** | `#D4D4D8` | `#2C2C32` | `#1C1C20` |
| **onTertiaryContainer** | `#18181B` | `#F4F4F5` | `#F4F4F5` |
| background | `#FAFAFB` | `#121214` | `#000000` |
| surfaceContainerLowest | `#FFFFFF` | `#0E0E10` | `#000000` |
| surfaceContainerLow | `#FAFAFB` | `#16161A` | `#000000` |
| surfaceContainer | `#F4F4F6` | `#1C1C20` | `#000000` |
| surfaceContainerHigh | `#EEEEF0` | `#242428` | `#0B0B0E` |
| surfaceContainerHighest | `#E8E8EB` | `#2C2C32` | `#121216` |
| onSurface / Variant | `#09090B` / `#52525B` | `#F4F4F5` / `#A1A1AA` | same as dark |
| outline / outlineVariant | `#A1A1AA` / `#D4D4D8` | `#52525B` / `#3F3F46` | `#52525B` / `#2C2C32` |

*Monochrome expressiveness = luminance choreography: hero card on `Highest`, working cards on `Container`, page on `Low`, insets/dock on `High`. In monochrome the wavy progress + jelly success + spring morphs carry the emotion; status colors are the only hue.*

### 1.3 Accent palettes (extend existing; regenerate final ramps from seeds in Material Theme Builder)

| Palette | Seed | primary L/D | secondary L/D | **tertiary L/D (new)** | tertiaryContainer L/D |
|---|---|---|---|---|---|
| Forest Sage | `#2E7D32` | `#2E7D32` / `#6AA878` | `#5B7261` / `#9DB5A3` | `#7D6B2F` / `#CBB76E` | `#EFE6C8` / `#332B12` |
| Deep Ocean | `#1976D2` | `#1976D2` / `#7EA8D4` | `#51688A` / `#9FB4CF` | `#0E7490` / `#6FC7DC` | `#CFF3F8` / `#0A2E36` |
| Warm Amber | `#D97706` | `#B45309` / `#D4A44C` | `#7A6248` / `#CBB493` | `#2C6E69` / `#79C2BC` | `#CFE9E6` / `#10312F` |
| Cyber Violet | `#7C3AED` | `#7C3AED` / `#A78BFA` | `#68598C` / `#B7A8DC` | `#A33E8C` / `#EF9BDD` | `#F8D7EC` / `#3B1130` |

Secondary = desaturated support voice (labels, nav active detail, tonal buttons); tertiary = editorial pop (hero wash, wavy progress, selected-chip detail). Status colors (`vf_accent_*`) are **palette-invariant**.

### 1.4 Contrast budget
AA minimum everywhere; hero-on-tertiaryContainer text uses onTertiaryContainer (≥4.5:1); badges/pills verify in all 3 modes; AMOLED strokes `#2C2C32`–`#3F3F46`.

---

## 2. SHAPE — one scale, deliberate tension

Expressive scale adopted wholesale: **none 0 · XS 4 · S 8 · M 12 · L 16 · L+ 20 · XL 28 · XL+ 32 · XXL 48 · full**.

| Element | Token | Rationale |
|---|---|---|
| Dialogs | XL 28 (existing ✓) | expressive default |
| Bottom sheets (top corners) | XL+ 32 | modal prominence |
| **Hero card (Home)** | **XXL 48 top-only rounding** ("extra large top rounding") | Hero Moment framing; breaks the 16dp card rhythm = tension that draws the eye |
| Action dock | XL+ 32 | floating-object feel |
| Workspace cards / list items | L 16 / M 12 | calm rhythm |
| Section containers (segmented lists, catalogue groups) | XL 28 wrapping M 12 items | containment contrast |
| Buttons | **full pill (default round style)**; `.Square` overlay (M 12–L 16) *only* for tension pairs (e.g., square "Reset" beside pill "Execute") | library morphs pill↔square on press/select — built-in delight |
| Icon buttons | round (circle) default; square 12 in dense toolbars | |
| Chips, badges, version pill | full | |
| Media thumbnails | M 12; QR art previews L 16 | content never outranks containers |
| Snackbar/tooltip | library defaults (XS/S) | |

Rule: **at most two shape families visible in one viewport** (pill + one rectangular radius), except hero moments which intentionally break the pattern.

## 3. SIZE — hierarchy you can feel in the thumb

| Tier | Size | Where |
|---|---|---|
| XL action | **56–64dp, full-width pill** (`SizeOverlay…Button.Xlarge`) | EXECUTE dock button; doc-export confirm |
| L CTA | 56dp | hero "Launch Upscaler", scanner "Capture page" empty-state CTA |
| M action (default) | **48dp** (library min `m3expressive_btn_min_height`) | tonal secondaries (Save/Share — fixes 42dp), form submits, dialog primary |
| S control | 40dp | icon buttons (fixes 38dp), dense dialog utilities, chips |
| Nav | 64dp bar / 56dp active pill indicator (library expressive defaults) | bottom nav; rail collapsed 80dp; ≥600dp horizontal items |
| Progress | **thick 8dp** determinate (radius 4); **wavy** (≈10–16dp container) for long jobs; circular wavy 48dp inline | all waits >2s get wavy |
| Sliders | Medium default; **Large** for hero controls (trim range, before/after threshold); Small in dense dialogs | expressive slider styles |
| Type minimums | body ≥12sp (BodySmall), labels ≥11sp (LabelSmall) — **9/10sp banned** | fixes 47 sub-11sp instances |

Size contrast rule: the single most important action per screen is ≥1 tier larger than everything else on it (research: 4× faster spotting; email Send-button case study).

## 4. TYPOGRAPHY — emphasized where it matters

Adopt library scales: `TextAppearance.Material3.*` + **`*.Emphasized`**. VeilFrame semantic styles:

| VeilFrame style | Maps to | Replaces |
|---|---|---|
| `Vf.Display.Hero` | DisplaySmallEmphasized 36 | hero title (was 22sp bold) |
| `Vf.Title.Workspace` | TitleLargeEmphasized 22 | toolbar tool title (was 15sp bold caps) |
| `Vf.Title.Section` | TitleSmallEmphasized 14 | ALL-CAPS 11sp section headers → **title case** ("Media studios") |
| `Vf.Card.Title` | BodyLargeEmphasized 16 | 13–14sp bold caps card titles → title case |
| `Vf.Body` / `Vf.Body.Sub` | BodyMedium 14 / BodySmall 12 | 13sp×100 / 11-12sp×281 body text |
| `Vf.Label` / `Vf.Label.Small` | LabelLarge 14 / LabelSmall 11 | badges, meta, 9-10sp |
| `Vf.Mono.Telemetry` | BodySmall + monospace | console/forensic readouts (identity preserved; user typeface styles kept) |

Rules: emphasis via **weight & size, not letterspacing-caps**; one emphasized element per card; user font-scale respected (sp only); the 3 user typefaces (Sans/Forensic-Terminal/Editorial-Serif) continue to work — semantic styles reference `?android:textAppearance` so the recursive typeface swap still applies.

## 5. MOTION — springs as the signature

All motion resolves from theme (`MotionUtils.resolveThemeSpringForce`), so switching expressive themes re-tunes the whole app's physics for free.

| Interaction | Physics | Tokens |
|---|---|---|
| Button/chip press → release | **fast spatial** (0.6/800) scale 0.94→1.0 + **fast effects** (1.0/3800) color; library shape-morph pill↔square rides along | replaces fixed 75/180ms |
| Card press | fast spatial, scale 0.98 | existing, re-sprung |
| Dialog open/close | origin-morph (keep `MorphDialogController`) on **default spatial** (0.8/380), overshoot ≤1.1; scrim fade on default effects | replaces 360ms morph w/ OvershootInterpolator |
| Sheet open/settle | default spatial spring; drag-follow 1:1, fling-to-settle velocity-aware | library + spring |
| Workspace transition (home→tool) | container-transform: card bounds expand to workspace surface, icon/title shared elements, **slow spatial** (0.8/200) + slow effects fade; reduced-motion → instant | replaces 28dp slide |
| Nav destination switch | library indicator spring (built-in) + 40ms staggered content rise (fast spatial, 28dp, first frame only) | |
| Dock state morph (READY→PROCESSING→COMPLETED) | button width morph (default spatial) → thick **wavy** progress → **jelly 320ms success** (keep signature keyframes: 1.00→0.95→1.045→0.982→1.012→1.0) + icon check-morph | Hero Moment #1 |
| Scan capture | shutter pulse (fast spatial) + page fly-in to strip (slow spatial, emphasized-decel) + thumbnail jelly + status pill shape-morph Searching→Detected→Ready | Hero Moment #2 |
| Progress appearance | determinate: spring-driven progress value smoothing; indeterminate waits >2s: wavy | |
| Micro | theme-toggle icon 180° rotate-morph (fast spatial 300ms emphasized); toggles/switches library springs; slider detent haptics at 0/50/100% | |
| **Haptics** | tick=selection, KEYBOARD_TAP=confirm, LONG_PRESS=warn, double-tick=error, CONFIRM=success+jelly; **no sound ever** (privacy brand) | |
| **Reduced motion** (`ANIMATOR_DURATION_SCALE=0`) | springs → ≤100ms fades/instant; jelly → single 8% pulse; wavy → standard indeterminate; stagger off; container-transform → crossfade; haptics retained | contract, tested in CI |

Duration pairing when springs don't apply: micro=short4 200ms · component=medium1/2 250–300ms · transformational=medium4/long1 400–450ms · full-screen=long2 500ms; easing: enter=emphasizedDecelerate, exit=emphasizedAccelerate, on-screen=emphasized.

## 6. CONTAINMENT — grouping is the hierarchy

1. **Ladder semantics** (both light & dark): page = `Low`; grouped content cards = `Container`; interactive/elevated (dock, hero, dialogs) = `High`/`Highest`; AMOLED differentiates via stroke, not luminance.
2. **Brightest surface = most important task** (research): hero upscaler card sits on `Highest` with tertiary wash; EXECUTE dock floats on `High` with 10dp elevation → 3dp+shadow only under floating objects.
3. **Segmented containment** for Library (Recents / Sessions / Exports as XL-rounded segments of M-rounded rows) and Tools catalogue category groups.
4. **Whitespace rhythm**: 16dp screen margin; 12dp intra-group; 20–24dp between sections; hero card gets 24dp internal padding (ample space = prominence).
5. **One hero per screen**: the emphasized container is unique; everything else stays on the calm ladder.

## 7. COMPONENT SPECIFICATIONS (VeilFrame styles → library expressive)

| VeilFrame style (new, `code/vf_expressive_components.xml`) | Parent / config |
|---|---|
| `Widget.VeilFrame.Button.Execute` | filled default + `SizeOverlay…Button.Xlarge`, full-width, LabelLargeEmphasized |
| `Widget.VeilFrame.Button.CTA` | filled + `SizeOverlay…Button.Large` |
| `Widget.VeilFrame.Button.Action` | tonal (inherit `materialButtonTonalStyle`), Medium 48dp |
| `Widget.VeilFrame.Button.Utility` | outlined/text, Small–Medium; `.Square` variant for tension pairs |
| `Widget.VeilFrame.IconButton.Toolbar` | `Widget.Material3Expressive.Button.IconButton.Standard` + `SizeOverlay…IconButton.Small` (40dp); `.Filled` for emphasized |
| `Widget.VeilFrame.IconButton.Workspace` | IconButton.Medium (48dp) |
| `Widget.VeilFrame.Progress.Thick` | `Widget.Material3Expressive.LinearProgressIndicator` trackThickness 8dp, radius 4dp |
| `Widget.VeilFrame.Progress.Wavy` | `Widget.Material3Expressive.LinearProgressIndicator.Wavy` (+Circular.Wavy 48dp inline) |
| `Widget.VeilFrame.Slider.Hero` | `Widget.Material3Expressive.Slider.Large` |
| `Widget.VeilFrame.Card.Hero` | 48dp top-rounded, `colorSurfaceContainerHighest` + tertiaryContainer gradient wash, stroke none (AMOLED: outlineVariant) |
| `Widget.VeilFrame.Card.Workspace` | L 16, `colorSurfaceContainer`, ripple bounded, spring press |
| `Widget.VeilFrame.Dock` | XL+ 32 card, `colorSurfaceContainerHigh`, elevation 10 |
| `Widget.VeilFrame.Badge.*` | pill chips: version (`Highest`/onSurfaceVariant), status pass/warn/fail/info (semantic container pairs + icon) |
| `Widget.VeilFrame.ButtonGroup.Connected` | `Widget.Material3Expressive.MaterialButtonGroup.Connected` — theme mode, export mode, aspect, edit scope |
| `Widget.VeilFrame.SearchBar` | `Widget.Material3Expressive.SearchBar` w/ synonym search (Tools) |
| Nav bar / rail | theme defaults (`bottomNavigationStyle`, `navigationRailStyle` from expressive theme); **custom tint selectors deleted**; Monochrome overlay sets secondary strong enough for active labels |
| Dialogs | existing 28dp overlay re-parented to `ThemeOverlay.Material3Expressive.MaterialAlertDialog` + Vf button styles |

## 8. EMOTIONAL-DESIGN RULES (the "alive" checklist)

1. **Hero budget = 2** (EXECUTE sequence, scan capture). Candidates must pass: emotionally impactful? key interaction? If not → calm treatment.
2. Every wait >2s is **living** (wavy/progress + stage labels in emphasized type + haptic on completion).
3. Every success is **felt** (jelly + haptic + check-morph ≤500ms total).
4. Every press is **answered** (<100ms spring response, no dead touches).
5. Chroma appears only for meaning (status/tertiary accents ≤10% of viewport).
6. Patterns stay familiar (lists are lists; tabs are tabs) — expressiveness never restructures IA (research guardrail).
7. Reduced-motion users get the same information hierarchy, minus physics.
8. Monochrome must never feel "disabled" — it is the flagship theme: full container ladder, springs, shape tension, emphasized type all active.
