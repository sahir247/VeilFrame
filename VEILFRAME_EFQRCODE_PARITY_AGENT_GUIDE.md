# VeilFrame ↔ EFQRCode 7.0.3 Parity Agent Guide

> **Purpose:** This document is an implementation-grade operating manual for an AI/code agent working on VeilFrame's QR generation pipeline while using **EFQRCode 7.0.3** as the behavioral reference.
>
> It is intentionally adversarial. The agent must optimize for discovering real behavioral differences, not for producing reassuring patches quickly.
>
> **Reference:** EFQRCode 7.0.3, pinned/tagged release and its source-level behavior.
> **Target:** VeilFrame QR implementation in the current working tree.
>
> This guide complements the detailed parity audit. The audit describes the observed contracts and matrix; this guide describes **how an agent must work** so that it does not accidentally invalidate, simplify, or hand-wave those contracts.

---

## 0. PRIME DIRECTIVE

Your job is **not** to make VeilFrame look like EFQRCode.

Your job is to make the relevant VeilFrame behavior agree with the EFQRCode 7.0.3 behavioral contract wherever parity is intended, while preserving explicitly documented VeilFrame extensions.

A patch that produces a visually similar QR is not sufficient.

A patch that passes ordinary unit tests is not sufficient.

A patch that has the same algorithm at a high level is not sufficient.

A patch that is smaller or cleaner is not automatically better.

A patch is successful only when the observable behavior matches the reference at the appropriate verification layer.

### Hard rule

> **Never substitute intent for behavior.**

If EFQRCode does something unusual, reproduce the unusual behavior when parity requires it. Do not “fix” an EF quirk merely because another implementation seems cleaner.

---

# 1. AUTHORITY ORDER

When sources disagree, use this order unless the task explicitly says otherwise:

1. **Actual EFQRCode 7.0.3 source behavior**
2. **Actual EFQRCode 7.0.3 generated oracle artifacts**
3. **VeilFrame source in the current working tree**
4. **Executable VeilFrame tests**
5. **The parity audit / this guide**
6. Comments, README text, commit messages, model recollection, assumptions

The audit and this guide are specifications and maps. They are not substitutes for the source.

### Never do this

- “The docs say this should happen, so I won't inspect the implementation.”
- “The Kotlin implementation is equivalent mathematically, so it is fine.”
- “The image looks the same.”
- “Tests pass, therefore parity is complete.”
- “This is probably how CoreGraphics works.”
- “This is a more correct implementation, so EF's behavior can be ignored.”

### Do this instead

Trace the actual path:

```text
public parameter
  ↓
model / DTO
  ↓
factory / conversion
  ↓
renderer
  ↓
helper / geometry / raster operation
  ↓
SVG IR / bitmap / mask
  ↓
serialization
  ↓
final observable output
```

For every parity-sensitive property, identify **where its meaning changes**.

---

# 2. REPOSITORY / SOURCE MAP

The exact file layout may evolve. Treat these paths as the current architectural map from the parity audit and re-resolve them against the working tree before editing.

## 2.1 VeilFrame QR model / entry points

Typical important locations:

```text
android/app/src/main/java/com/veilframe/app/qr/
```

Core files referenced by the parity work include:

```text
QrStyleParams.kt
model/QrDesign.kt
renderer/ImageRenderer.kt
renderer/ImageFillRenderer.kt
renderer/ResampleImageRenderer.kt
renderer/ResampleGeometryBuilder.kt
renderer/ResampleSubpixelEngine.kt
renderer/ImageScaleResolver.kt
renderer/VeilPositionPatternGeometry.kt
geometry/QrGeometryIr.kt
geometry/IrSvgRenderer.kt
exporter/SvgExporter.kt
renderer/AnimatedQrGenerator.kt
```

Testing is primarily under:

```text
android/app/src/test/java/com/veilframe/app/qr/
```

Do not assume every file still exists at exactly this path. Search the working tree if a path moved.

## 2.2 EFQRCode reference surface

The parity reference is EFQRCode 7.0.3, especially:

```text
Source/Styles/EFQRCodeStyle.swift
Source/Styles/EFQRCodeStyleImage.swift
Source/Styles/EFQRCodeStyleImageFill.swift
Source/Styles/EFQRCodeStyleResampleImage.swift
Source/Styles/EFQRCodeStyleBasic.swift
Source/Type/EFImageMode.swift
Source/Extension/CGImage+EFQRCode.swift
```

The public repository is:

```text
https://github.com/EFPrefix/EFQRCode
```

EFQRCode itself describes its generator as a Swift implementation built around CoreGraphics/CoreImage/ImageIO, and its public README documents both static and animated image generation. Use the **pinned 7.0.3 reference**, not whichever behavior current `main` happens to have.

---

# 3. VERSION PINNING

## Reference version

```text
EFQRCode 7.0.3
```

Do not silently compare against:

- latest EFQRCode `main`
- another major/minor release
- old pre-7 code
- documentation generated from a different version

If you inspect current upstream source and it differs from 7.0.3:

```text
STOP
↓
identify the version difference
↓
return to the pinned 7.0.3 source
↓
only use current upstream as contextual information
```

---

# 4. LOCAL WORKTREE VS COMMITTED STATE

Before modifying code, establish:

```text
1. current git branch
2. HEAD commit
3. git status
4. uncommitted diff
5. staged diff
6. relevant local-only changes
7. most recent pushed commit
```

Never merge these concepts mentally:

```text
pushed code
local code
planned code
```

A parity audit may describe local changes that have not yet been committed. Treat them as **current working-tree behavior**, not as proof that GitHub already contains them.

When reporting a finding, explicitly identify which state it refers to.

Example:

```text
Pushed-state divergence:
    commit X still uses preserveAspectRatio.

Working-tree status:
    local preprocessing changes partially address this.

Oracle status:
    not yet reference-verified.
```

---

# 5. THE FOUR VERIFICATION LAYERS

Every change must be evaluated at the correct layer.

## Layer A — Semantic SVG / Geometry

Verify:

- element topology
- node order
- group nesting
- masks
- clip paths
- transforms
- viewBox
- coordinates
- widths/heights
- path data
- finder geometry
- timing/alignment geometry
- animation attributes
- normalized identifiers

Do not compare random generated IDs directly.

## Layer B — Intermediate Image / Preprocessed Raster

This is the dangerous image-scaling layer.

Compare:

- exact output dimensions
- crop region
- letterbox region
- alpha values
- channel values
- pixel ordering
- intermediate raster bytes

EF's image mode processing happens before image embedding. Do not implement parity merely by asking the final SVG consumer to scale a raw image.

## Layer C — Algorithmic 3N / Subpixel

For RESAMPLE compare:

- active subpixel coordinates
- excluded coordinates
- center anchors
- finder exclusion boxes
- timing/alignment exclusions
- grayscale
- effective contrast
- exposure
- threshold decisions
- deterministic RNG sequence when using instrumented reference mode

## Layer D — Final Raster

Do not confuse two cases:

### Same rendering engine

Exact pixel equality may be demanded.

### Different rendering engines

CoreGraphics vs Skia cannot automatically be treated as byte-identical renderers.

Use perceptual diagnostics and geometric correctness, with exact equality only where the rasterization environment is genuinely identical.

---

# 6. STATUS TAXONOMY

Use exactly these states in parity tracking:

```text
REFERENCE-VERIFIED
IMPLEMENTATION-VERIFIED
UNVERIFIED
DIVERGENT
```

## REFERENCE-VERIFIED

Use only when:

- EF source behavior was inspected;
- VeilFrame source behavior was inspected;
- a test actually ran;
- the expected result came from the real EF oracle or a source-proven invariant;
- the relevant reference artifact was generated where needed.

## IMPLEMENTATION-VERIFIED

Use when the VeilFrame behavior was executed and agrees with an independently source-proven invariant, but a direct EF artifact comparison is not necessary for that specific claim.

## UNVERIFIED

Use when the behavior is plausible or source-derived but executable evidence has not established it.

## DIVERGENT

Use only for a demonstrable behavioral difference, not merely a stylistic code difference.

### Important distinction

Different implementation:

```text
EF: 0.01 expansion
VF: 0.01 * moduleSize expansion
```

is not automatically divergent.

Ask:

> Does the resulting observable geometry differ after all coordinate transforms?

If unknown → `UNVERIFIED`.

---

# 7. PARITY CLAIM INTEGRITY

Never write:

```text
fully equivalent
same behavior
100% parity
production parity
```

unless all applicable parity rows are verified and the oracle gate has passed.

The final claim is blocked if any applicable row remains:

```text
DIVERGENT
UNVERIFIED
```

A successful local test suite is not equivalent to reference parity.

---

# 8. EF IMAGE MODE IS A RASTER CONTRACT, NOT A FITTING LABEL

EF exposes three core image modes relevant to the parity work:

```text
scaleToFill
scaleAspectFit
scaleAspectFill
```

Do not reduce them to UI-level meanings such as “stretch / fit / crop” and stop there.

The important contract includes:

- aspect-ratio comparison
- intermediate size calculation
- integer truncation
- transparent canvas behavior
- crop geometry
- pixel-center behavior
- CoreGraphics bitmap context configuration
- premultiplication
- final embedded raster

## scaleToFill

EF computes a target-ratio intermediate size rather than simply telling the final SVG renderer to stretch the original image.

Important questions:

```text
What is newSize?
What happens before Int conversion?
What gets truncated?
What is the intermediate raster size?
Which filtering operation creates it?
```

Do not replace this with a one-line `Canvas.drawBitmap` call and assume equivalence.

## scaleAspectFit

Parity-sensitive properties:

- target-ratio rectangle
- centered placement
- integer canvas dimensions
- transparent letterboxing / pillarboxing
- alpha values in empty margins

For transparent margins, gamma/luminance processing in RESAMPLE must account for the resulting pixels exactly.

## scaleAspectFill

This mode has a special audit requirement:

> Verify the crop using numbered pixels.

Create a synthetic source in which every pixel identifies its coordinates. Then compare EF's crop with the candidate.

Do not infer crop parity just from a matching bounding rectangle.

---

# 9. RASTERIZATION: DO NOT TRUST GENERIC ANDROID HELPERS

`Bitmap.createScaledBitmap`, `Matrix`, browser SVG scaling, and consumer-side `preserveAspectRatio` are not automatically substitutes for EF's CoreGraphics pipeline.

The parity implementation needs an explicit reference-compatible path.

The current audit treats the following as a **provisional implementation model until empirically validated**:

```text
bilinear interpolation
half-pixel center mapping
premultiplied-alpha interpolation
explicit integer truncation
explicit transparent-border behavior
```

Do not promote a provisional model to a reference fact until the macOS EF oracle has been compared.

## Rasterization questions the agent must answer

For any image discrepancy, investigate:

```text
1. What are the source dimensions?
2. What are the target dimensions?
3. Was an intermediate resize performed?
4. Was there a crop first?
5. Was the crop integer or fractional?
6. What coordinate space is being sampled?
7. Where are pixel centers?
8. What interpolation kernel is used?
9. Is alpha premultiplied during interpolation?
10. What happens outside source bounds?
11. What color space is in use?
12. When are integers truncated/rounded?
13. Are transparent RGB channels retained or cleared?
14. Does SVG perform a second scaling operation?
```

---

# 10. PRESERVE-ASPECT-RATIO RULE

For an EF-parity image path:

> **Do not rely on downstream SVG `preserveAspectRatio` to reproduce EF image preprocessing.**

The intended parity path is:

```text
raw source image
    ↓
EF-compatible preprocessing
    ↓
preprocessed raster
    ↓
PNG/base64 embedding
    ↓
explicit SVG dimensions
```

Not:

```text
raw source image
    ↓
SVG image element
    ↓
preserveAspectRatio
    ↓
whatever renderer consumes the SVG
```

If a non-parity vector mode intentionally uses consumer-side scaling, keep that explicit and separate.

---

# 11. IMAGE STYLE PLAYBOOK

The IMAGE renderer should be reasoned about as these layers:

```text
backdrop
↓
optional transparent pre-pass
↓
preprocessed continuous image
↓
finder hole mask
↓
finder/background geometry
↓
timing geometry
↓
alignment geometry
↓
data geometry
↓
icon
```

## 11.1 allowTransparent

The transparent pre-pass is a specific EF behavior.

It does NOT simply mean “make background transparent.”

Audit:

- which module classes are skipped
- which module classes are pre-rendered
- dark/light colors
- alpha
- exact shape
- full module size
- ordering before the image
- interaction with `data.scale`

Crucial invariant from the parity audit:

```text
allowTransparent pre-pass module size = 1.0 module
```

Do not accidentally reuse data-module scale.

## 11.2 Finder hole mask

Do not hand-wave finder holes.

Verify:

- `markArr`
- all three finder positions
- exact 8×8 box size
- exact x/y coordinates
- mask fill polarity
- mask placement relative to the image

A single coordinate error can produce an apparently plausible QR while still violating EF behavior.

## 11.3 Finder geometry

For roundedRectangle / SQ25:

> Generic `rx/ry` rounded rectangles are not the EF SQ25 path.

Use the exact shared position geometry implementation when parity is required.

---

# 12. IMAGE_FILL PLAYBOOK

Treat IMAGE_FILL as a topology contract.

Expected conceptual order:

```text
backdrop
↓
#hole mask
↓
group masked by #hole
    ↓
    backgroundColor
    ↓
    image
    ↓
    maskColor/tint
↓
center icon
```

The stencil uses:

```text
black full canvas

for each dark module:
    white rect
    x = moduleX - 0.01
    y = moduleY - 0.01
    width = 1.02
    height = 1.02
```

Important:

- all dark functional modules participate;
- light modules remain masked out;
- finder shapes are not separately “fixed” by a second geometric pass in this style;
- animation belongs to the image source, not to the entire QR structure.

### Representation versus observable behavior

If VeilFrame stores alpha inside an ARGB integer while EF stores alpha separately, that alone is not a parity failure.

Compare:

```text
final SVG opacity / color
final compositing behavior
final pixels
```

Only mark divergence when the observable result differs.

---

# 13. RESAMPLE PLAYBOOK

RESAMPLE is the highest-risk parity area.

Treat it as a separate algorithm, not as a decorative IMAGE style.

## 13.1 3N coordinate space

If QR module count is `N`, the resample grid is approximately:

```text
3N × 3N
```

Every module expands into a 3×3 subpixel neighborhood.

## 13.2 Exclusion rules

Audit and test:

- finder boxes
- timing structures
- alignment structures
- light timing/alignment structural regions
- center anchors `(x % 3 == 1 && y % 3 == 1)`

Do not emit center-anchor dots in the same way as the eight neighboring subpixels when the EF contract excludes them.

## 13.3 Contrast / exposure

Trace:

```text
gamma
→ exposure
→ contrast
→ threshold
→ random comparison
```

Do not accidentally apply contrast before exposure if EF applies the inverse order.

Do not clamp negative contrast/exposure just because the UI normally supplies friendly values.

The test corpus intentionally contains negative values.

## 13.4 Luminance / alpha

The audited EF formula is conceptually:

```text
gray = 0.2126*R + 0.7152*G + 0.0722*B
gamma = gray*alpha + (1-alpha)*255
```

But the meaning of `R/G/B` depends on the premultiplication semantics of the reference bitmap buffer.

Therefore:

> **Never “fix” alpha math from intuition. Validate it against the empirical CoreGraphics fixture.**

Use the four canonical test pixels:

```text
(255,   0,   0, 128)
(  0, 255,   0,  64)
( 20,  40,  60, 128)
(  0,   0,   0,   0)
```

## 13.5 RNG

There are two different test modes.

### Real-reference mode

Use actual EFQRCode's unseeded `Double.random` behavior.

Do not claim seed control over this production RNG.

Compare statistical behavior across independent executions.

### Deterministic regression mode

Instrument both reference and candidate with the same seeded pseudo-random generator.

Use this for exact coordinate/raster regression.

Do not confuse deterministic instrumentation with proof that the production random implementation is byte-identical.

---

# 14. POSITION PATTERN GEOMETRY PLAYBOOK

All five EF position styles matter:

```text
Rectangle
Round
RoundedRectangle / SQ25
Planets
DSJ
```

Do not stop after fixing SQ25.

For each style check:

```text
inner geometry
outer geometry
stroke width
size multiplier
center coordinate
3N scaling in RESAMPLE
satellite/orbit geometry where applicable
```

## SQ25 rule

Use one authoritative geometry implementation for all consumers where possible.

Avoid having:

```text
Resample → exact SQ25
Image → rounded Rect
SVG exporter → another rounded Rect
Icon → another rounded Rect
```

That architecture guarantees future divergence.

---

# 15. PARAMETER SEMANTICS

The agent must preserve EF runtime semantics, not impose arbitrary UI-friendly clamps.

Audit these separately:

```text
data.scale
position.size
align.size
 timing.size
image.mode
image.alpha
image.allowTransparent
ImageFill backgroundColor/backgroundAlpha
ImageFill maskColor/maskAlpha
resample.contrast
resample.exposure
icon.percentage
icon.alpha
icon.mode
backdrop.corner
backdrop.color
backdrop.image
backdrop.imageAlpha
backdrop.imageMode
quietzone
```

## Synthetic clamps are suspect

If current VeilFrame contains something like:

```text
coerceIn(0.1, 1.0)
```

ask:

> Does EF actually clamp this value?

If not, do not keep the clamp simply because it prevents ugly input.

Parity has priority over convenience in the EF-compatible path.

---

# 16. ICON / LOGO PLAYBOOK

EF icon behavior is not equivalent to “put a logo in the center.”

Audit:

```text
percentage clamp at 0.33
iconSize
iconXY
2.4% offset expansion
border SQ25 geometry
clip mask
icon alpha
icon image mode
animated icon source
```

Important formula from the audit:

```text
iconOffset = iconXY * 0.024
```

Do not replace it with a constant padding value.

If VeilFrame's LogoStyle has additional UX features such as a card, auto contrast, or other presentation logic, isolate those from the EF-compatible mode so they do not silently contaminate parity.

---

# 17. BACKDROP PLAYBOOK

EF has a shared backdrop concept.

Core properties:

```text
cornerRadius
color
image
imageAlpha
imageMode
quietzone
```

The target architecture should avoid having these scattered across only the RESAMPLE path.

Prefer one shared domain model such as `BackdropStyle` where that is compatible with the existing architecture.

### Extensions

VeilFrame may have richer extensions such as blend modes or tinting.

Those are acceptable only if:

```text
EF-compatible mode defaults to neutral values
```

and they do not alter an EF configuration's output.

---

# 18. QUIET-ZONE PLAYBOOK

Do not assume quiet zones are always integer modules.

Standard EF behavior uses fractional multipliers of module count.

For a standard style, conceptually:

```text
x      = -N * left
 y      = -N * top
width  = N * (left + 1 + right)
height = N * (top + 1 + bottom)
```

Resample uses the 3N coordinate system and therefore has a correspondingly scaled default margin.

Verify:

- all four sides independently
- fractional values
- default values
- viewBox
- content transform
- backdrop clip interaction
- SVG/image placement

---

# 19. ANIMATION PLAYBOOK

The fundamental EF architecture is:

```text
static QR structure
+
animated image source
```

Not:

```text
frame 0 = complete QR SVG
frame 1 = complete QR SVG
frame 2 = complete QR SVG
```

When animating image sources, keep these static outside the animation:

```text
finders
timing
alignment
data
backdrop rectangle
icon border
structural masks where applicable
```

The image frames should be what changes.

## SMIL semantics

Inspect:

```text
frame ordering
delays
total duration
keyTimes
calcMode = discrete
xlink:href / use structure
repeatCount
```

Do not “modernize” the output to a different SVG animation model and call it equivalent without proof.

## Edge conditions

Do not assume behavior for:

- empty frame array
- one frame
- zero total duration
- zero delay
- mismatched frame/delay counts

Generate the EF oracle and observe its actual behavior.

---

# 20. THREE LEVELS OF IMAGE PARITY

When you find an image discrepancy, identify which layer is responsible:

### A. Geometry mismatch

Example:

```text
wrong crop box
wrong finder geometry
wrong mask
```

### B. Intermediate raster mismatch

Example:

```text
same geometry
wrong preprocessed bitmap
```

### C. Final consumer raster mismatch

Example:

```text
same SVG
same embedded PNG
slightly different final AA due to renderer
```

Do not patch level C to compensate for a level B failure.

Likewise, do not rewrite geometry when the real problem is a raster preprocessing difference.

---

# 21. MASTER DEBUGGING METHOD

When output differs from EF, do this in order.

## Step 1 — Freeze inputs

Record:

```text
payload
QR version
error correction level
all style parameters
source image hash
source image dimensions
backdrop image hash
icon image hash
animation frames + delays
```

## Step 2 — Compare source QR matrix

Confirm the QR matrix itself is identical.

If the matrix differs, stop image-style debugging.

## Step 3 — Compare normalized SVG AST

Strip generated identifiers and compare structure.

## Step 4 — Compare intermediate image

Decode both embedded/preprocessed images and compare dimensions/pixels.

## Step 5 — Compare 3N intermediate state

Dump:

```text
coordinate
raw sampled color
alpha
gamma
effective luminance
contrast/exposure output
threshold
random value if instrumented
emission decision
```

## Step 6 — Compare final raster

Only after Layers A–C agree should you investigate final rendering differences.

---

# 22. OBSERVABILITY: BUILD DEBUG DUMPS BEFORE GUESSING

For hard parity bugs, add temporary diagnostics rather than reasoning from screenshots.

Useful debug outputs:

```text
normalized.svg
preprocessed.png
preprocessed.raw
3n-grid.json
3n-emitted-points.json
finder-geometry.json
masks.svg
animation.svg
final.png
pixel-diff.png
```

For each artifact include:

```text
reference or candidate
commit SHA / working-tree marker
parameters hash
source image hash
```

Temporary diagnostics must not become undocumented production APIs.

---

# 23. ADVERSARIAL TEST CORPUS

The existing parity plan defines TC-01 through TC-26. Treat these as required adversarial classes, not optional examples.

```text
TC-01  Basic position styles
TC-02  IMAGE static: square/landscape/portrait + all image modes
TC-03  IMAGE allowTransparent
TC-04  IMAGE animation
TC-05  IMAGE_FILL static + animation + stencil
TC-06  RESAMPLE contrast/exposure
TC-07  RESAMPLE animation
TC-08  ICON percentage + SQ25 + mask
TC-09  BACKDROP + corner radius + quiet zone
TC-10  premultiplied-alpha fixture
TC-11  extreme aspect ratios
TC-11b invalid dimensions / reference rejection behavior
TC-12  odd dimensions
TC-13  fractional target ratio
TC-14  fully transparent source
TC-15  partially transparent colored source
TC-16  alpha boundaries
TC-17  negative contrast
TC-18  negative exposure
TC-19  dataScale = 0
TC-20  marker sizes = 0
TC-21  icon percentage > 0.33
TC-22  animation boundary conditions
TC-23  unequal delays
TC-24  asymmetric quiet zone
TC-25  QR Version 1
TC-26  QR Version 40
```

Any newly discovered bug should add at least one regression case to this corpus or to a clearly named extended corpus.

---

# 24. TEST MATRIX DESIGN RULES

A good parity test should answer one concrete question.

Bad:

```text
assertGeneratedQrLooksCorrect()
```

Good:

```text
assertScaleAspectFillCropMatchesReferencePixels()
assertImageFinderMaskUsesEfMarkArr()
assertImageFillStencilUses102PercentModuleRectangles()
assertResampleSkipsCenterAnchor()
assertQuietZoneUsesFractionalModuleInsets()
```

Avoid giant tests that pass while hiding which contract failed.

Prefer small tests with one contract per test name.

---

# 25. ORACLE HARNESS REQUIREMENTS

The authoritative reference environment is:

```text
macOS
Swift
EFQRCode 7.0.3
CoreGraphics
```

Linux may be used only if it has independently demonstrated byte-equivalent reference artifacts for the relevant operation.

Do not assume a Linux mock or alternative raster engine is identical to macOS CoreGraphics.

## Corpus artifacts

For each case, aim to retain:

```text
<id>_reference.svg
<id>_preprocessed.png
<id>_3n_raster.json
<id>_final_raster.png
<id>_metadata.json
```

`metadata.json` should record at minimum:

```json
{
  "reference": "EFQRCode 7.0.3",
  "source_commit": "...",
  "payload": "...",
  "qr_version": 1,
  "error_correction": "...",
  "style": "IMAGE",
  "parameters_hash": "...",
  "source_image_sha256": "..."
}
```

---

# 26. NORMALIZATION RULES FOR SVG COMPARISON

Before AST comparison:

- normalize generated IDs
- normalize equivalent whitespace
- normalize attribute ordering if semantically irrelevant
- preserve numeric values
- preserve node order
- preserve transforms
- preserve mask topology
- preserve path data

Do NOT normalize away actual behavior.

Examples of dangerous “normalization”:

```text
rounding coordinates to fewer decimals
removing opacity attributes because they are "visually similar"
rewriting paths as rounded rectangles
collapsing separate groups
removing masks that look redundant
```

The purpose of normalization is to remove serialization noise, not behavioral differences.

---

# 27. COMMON FAILURE MODES TO ACTIVELY HUNT

Every agent pass should explicitly search for these.

## Failure mode 1 — High-level equivalence

```text
"Both use aspect fill, so they match."
```

Attack by comparing intermediate pixels.

## Failure mode 2 — Single-path bias

The agent fixes the static renderer but forgets:

```text
animated path
SVG exporter
Canvas renderer
icon path
backdrop path
```

## Failure mode 3 — Wrong source of truth

The agent copies behavior from current EFQRCode `main` instead of pinned 7.0.3.

## Failure mode 4 — Over-clamping

The agent adds `coerceIn()` for safety and silently changes EF semantics.

## Failure mode 5 — Renderer-consumer dependency

The agent leaves scaling/cropping to a browser/WebView and assumes all consumers rasterize the same way.

## Failure mode 6 — Internal-vs-observable confusion

Different field types are incorrectly marked divergent even when generated output is equivalent.

## Failure mode 7 — Test theater

A test asserts the current implementation's output rather than the reference contract.

## Failure mode 8 — Reference corruption

The agent changes the EF harness or modifies the reference implementation, then uses the modified reference to “prove” parity.

## Failure mode 9 — False confidence from visual inspection

A screenshot can miss a one-pixel shift, an alpha difference, incorrect masking, or a wrong animation structure.

## Failure mode 10 — Fixing only the first mismatch

Finding one root cause does not imply the style is now parity-complete.

---

# 28. AGGRESSIVE SELF-REVIEW QUESTIONS

Before declaring a task complete, answer all of these.

```text
1. What exact EF source code proves the behavior?
2. What exact VeilFrame code implements it?
3. What parameters reach that code?
4. Is there a second path for SVG / Canvas / animation?
5. Are defaults identical?
6. Are clamps identical?
7. Are coordinate systems identical?
8. Are integer conversions identical?
9. Are alpha semantics identical?
10. Are transparent pixels identical?
11. Is the operation performed at the same pipeline stage?
12. Is a downstream renderer doing hidden work in VeilFrame?
13. Is layer ordering identical?
14. Is mask polarity identical?
15. Does the animation affect only the image source?
16. Does the icon follow the same contract?
17. Does the backdrop follow the same contract?
18. Are quiet-zone values fractional where required?
19. Can an edge-case input expose a difference?
20. Did I compare actual generated artifacts?
21. Did I run the tests rather than merely inspect them?
22. Could the tests pass while the implementation is still wrong?
23. What evidence would disprove my conclusion?
24. Did I try to find that evidence?
```

If these questions cannot be answered, the task is not finished.

---

# 29. IMPLEMENTATION DISCIPLINE

## Before editing

```text
git status
inspect current diff
locate all consumers
locate existing tests
inspect reference source
write down the contract
```

## During editing

Keep the patch narrow enough that a failure can be attributed.

Prefer:

```text
shared primitive
↓
all consumers switched to primitive
↓
focused test
↓
full test suite
```

over:

```text
copy-paste slightly different fixes into four renderers
```

## After editing

Run:

```text
targeted parity tests
full QR unit suite
build / static checks
```

Then inspect:

```text
git diff --stat
git diff
```

Make sure no unrelated changes slipped in.

---

# 30. NO UNVERIFIED TEST CLAIMS

Never report:

```text
All tests pass
```

unless the tests were actually executed in this run/context.

Do not infer execution from:

- a prior log
- a code path
- an earlier agent
- a CI badge without checking it
- an old uploaded report

When using prior evidence, say:

```text
The supplied worklog reports 333/333 tests passing.
```

When you actually run them, say:

```text
Executed: <command>
Result: <count>
```

Keep those distinct.

---

# 31. CHANGE REVIEW FORMAT

When reporting an implementation change, use this structure:

```text
CHANGE
-----
What changed?

REFERENCE CONTRACT
------------------
What exactly does EF 7.0.3 do?

VEILFRAME BEFORE
----------------
What did the current code do?

VEILFRAME AFTER
---------------
What does the patch now do?

WHY THIS IS PARITY-CORRECT
--------------------------
Exact behavioral reasoning.

EVIDENCE
--------
Files/functions/tests/oracle artifacts.

STATUS
------
REFERENCE-VERIFIED | IMPLEMENTATION-VERIFIED | UNVERIFIED | DIVERGENT

REGRESSION RISK
---------------
What could this change affect elsewhere?
```

---

# 32. WHEN A FIX TOUCHES MULTIPLE STYLES

If a shared primitive is changed, automatically inspect:

```text
BASIC
IMAGE
IMAGE_FILL
RESAMPLE
ICON
BACKDROP
SVG exporter
Canvas renderer
animation path
```

Do not assume a shared class is only used by the renderer you are currently editing.

Search all call sites before changing semantics.

---

# 33. AGENT TASK EXECUTION LOOP

Use this loop for every parity task:

```text
┌──────────────────────────────┐
│ 1. Identify requested scope │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 2. Freeze reference version  │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 3. Inspect EF source         │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 4. Trace VeilFrame path      │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 5. Build difference table    │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 6. Attack assumptions        │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 7. Implement smallest robust │
│    architectural correction  │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 8. Add/repair focused tests  │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│ 9. Execute tests              │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│10. Inspect generated output  │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│11. Re-audit the patch        │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│12. Update parity matrix      │
└──────────────────────────────┘
```

Never skip steps 3, 6, or 11 merely because the bug appears obvious.

---

# 34. PARITY MATRIX ENTRY TEMPLATE

Every discovered issue should be recorded like this:

```text
ID:

EF SOURCE:

EF CONTRACT:

VEILFRAME LOCATION:

CURRENT BEHAVIOR:

REFERENCE BEHAVIOR:

OBSERVABLE DIFFERENCE:

PIPELINE LAYER:
  A / B / C / D

SEVERITY:
  Critical / High / Medium / Low

REPRODUCTION:

TEST:

REFERENCE ARTIFACT:

STATUS:

FIX:

REGRESSION TEST:
```

---

# 35. SEVERITY GUIDANCE

### Critical

- breaks QR readability
- changes core QR matrix
- incorrect finder mask
- wrong data/stencil topology
- severe animation corruption
- wrong image sampling in core parity path

### High

- consistent source/reference image mismatch
- wrong quiet zone
- wrong finder geometry
- wrong alpha behavior
- missing animated image support
- wrong backdrop behavior

### Medium

- specific non-default parameter mismatch
- uncommon style geometry difference
- serialization difference with observable consequences

### Low

- non-semantic serialization noise
- internal representation differences with no observable output difference

Severity must describe **behavioral impact**, not code ugliness.

---

# 36. ARCHITECTURAL TARGET

Do not build a pile of one-off parity patches.

Preferred direction:

```text
               ┌────────────────────────┐
               │   QrStyleParams        │
               └────────────┬───────────┘
                            ↓
               ┌────────────────────────┐
               │      QrDesign          │
               │ canonical semantics    │
               └────────────┬───────────┘
                            ↓
        ┌───────────────────┼───────────────────┐
        ↓                   ↓                   ↓
   Image Source        Geometry IR        Shared Backdrop
        ↓                   ↓                   ↓
  EF preprocessing    position/timing      color/image/clip
        ↓                   ↓                   ↓
   static/animated    static overlays      shared canvas rules
        └───────────────────┼───────────────────┘
                            ↓
                  SVG / Canvas renderers
```

The critical architectural principle is:

> **One semantic contract, multiple output backends.**

Do not let the SVG exporter and Canvas renderer each invent slightly different QR geometry semantics.

---

# 37. WHAT NOT TO OVER-ENGINEER

Parity work can itself become over-engineered.

Do not introduce a giant abstraction merely because the reference has many types.

Before adding a class, ask:

```text
Does this remove duplicated parity logic?
Does this make the reference contract easier to test?
Does this prevent SVG/Canvas divergence?
Does this reduce the chance of another future parity bug?
```

If the answer is no, prefer a smaller change.

The objective is **accurate behavior with maintainable architecture**, not an architectural museum.

---

# 38. WHAT NOT TO “FIX” IN EF PARITY MODE

Do not silently change EF behavior because it is:

- visually odd
- mathematically inefficient
- unusual
- an old implementation detail
- less elegant than the VeilFrame equivalent
- inconsistent with current best practices

Examples:

```text
EF clamping behavior
EF fractional coordinates
EF mask expansion values
EF SQ25 path
EF random-ID behavior
EF animation serialization
EF exact layer order
```

If the behavior is undesirable, create a separate **VeilFrame extension mode** rather than contaminating the compatibility path.

---

# 39. COMPLETION GATES

A milestone is complete only when its exit gate passes.

## M1 — Image preprocessing

Required:

```text
scaleToFill verified
scaleAspectFit verified
scaleAspectFill verified
odd dimensions verified
extreme ratios verified
alpha fixture verified
numbered crop verified
```

## M2 — Static image embedding

Required:

```text
no unintended preserveAspectRatio
correct preprocessed image
correct image dimensions
correct alpha
correct mask
correct layer order
```

## M3 — Animation

Required:

```text
IMAGE animation
IMAGE_FILL animation
ICON animation
keyTimes
calcMode
frame delays
boundary behavior
structural QR static
```

## M4 — Geometry

Required:

```text
Rectangle
Round
SQ25
Planets
DSJ
all relevant render consumers
zero/extreme size semantics
```

## M5 — Backdrop

Required:

```text
corner radius
color
image
image alpha
image mode
fractional quiet zone
viewBox
translation
all supported styles
```

## M6 — Oracle

Required:

```text
reference corpus generated
Layer A passes
Layer B passes
Layer C passes
Layer D diagnostics acceptable
no applicable DIVERGENT
no applicable UNVERIFIED
```

---

# 40. FINAL SELF-ATTACK BEFORE DECLARING PARITY

Before saying “done,” deliberately assume the patch is wrong.

Try to break it with:

```text
portrait source
landscape source
transparent PNG
semi-transparent colored PNG
1xN image
Nx1 image
odd dimensions
zero sizes
negative parameters
maximum parameters
version 1 QR
version 40 QR
animated source
unequal frame delays
fractional quiet zones
SQ25
Planets
DSJ
alpha < 1
contrast < 0
exposure < 0
dataScale = 0
```

Then ask:

> What difference would an expert find that I have not tested?

Do not finish until that question has a concrete answer.

---

# 41. RESPONSE TEMPLATE FOR AN AI AGENT

When asked to continue parity work, respond internally/structurally in this order:

```text
SCOPE
-----
Exact requested component.

REFERENCE CONTRACT
------------------
EF 7.0.3 behavior with source location.

CURRENT VEILFRAME PATH
----------------------
Actual classes/functions and call chain.

DIFFERENCES
-----------
One row per observable mismatch.

HIDDEN RISKS
------------
Subtle cases that may evade current tests.

PLAN
----
Minimal robust architectural correction.

TESTS
-----
Focused tests + oracle tests.

EXECUTION
---------
Commands actually run and their real results.

STATUS
------
Reference-verified / implementation-verified / unverified / divergent.
```

Do not bury the actual finding underneath generic explanation.

---

# 42. THE ONE-SENTENCE AGENT RULE

> **Act like a hostile compatibility engineer: assume similarity is false until the reference source, the implementation path, and an executable observation all agree.**

---

# 43. SOURCE-BOUNDARY NOTE

The parity audit that motivated this guide is itself a specification and tracking document. Some statuses in it describe the local working tree and existing tests, not independently regenerated EF oracle artifacts.

Therefore, when implementing the guide:

```text
Audit statement
    ↓
inspect actual EF 7.0.3 source
    ↓
inspect actual current VeilFrame code
    ↓
execute targeted test
    ↓
generate/compare oracle artifact where appropriate
    ↓
only then promote the status
```

Never turn a plan into a fake fact.

---

# 44. STOP CONDITIONS

Stop and ask for clarification only when the task itself is ambiguous.

Do **not** stop merely because parity is difficult.

For difficult but well-defined problems:

```text
inspect more source
trace more call sites
make a diagnostic fixture
build a smaller reproduction
compare intermediate artifacts
attack the assumption
```

Do not replace missing evidence with confidence.

---

# 45. FINAL OPERATING PRINCIPLES

```text
REFERENCE FIRST
BEHAVIOR OVER INTENT
SOURCE OVER COMMENTS
ARTIFACTS OVER SCREENSHOTS
INTERMEDIATE STATE OVER FINAL APPEARANCE
TEST THE CONTRACT, NOT THE CURRENT CODE
ONE SHARED SEMANTIC MODEL OVER DUPLICATED RENDERER LOGIC
DO NOT OVER-CLAMP
DO NOT OVER-NORMALIZE
DO NOT MODIFY THE REFERENCE TO MAKE IT PASS
DO NOT CLAIM TESTS YOU DID NOT RUN
DO NOT CLAIM PARITY WHILE UNKNOWN/DIVERGENT ROWS REMAIN
```

The agent's success criterion is not “the patch looks good.”

It is:

```text
EFQRCode 7.0.3 behavioral contract
        ↓
correctly understood
        ↓
correctly mapped to VeilFrame
        ↓
implemented once at the right abstraction layer
        ↓
verified by focused tests
        ↓
validated against the reference oracle
        ↓
regressions prevented
```
