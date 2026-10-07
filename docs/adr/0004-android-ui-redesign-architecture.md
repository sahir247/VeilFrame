# ADR 0004: VeilFrame Android UI/UX Redesign & Modular Workspace Architecture

## Status

Status: Accepted (Locked)  
Implementation Baseline: `v2.2.9` (`dbf4f14`)  
Date: October 2026

---

## 1. Context & Architectural Reality

VeilFrame Android is an established, production-grade application built with **native Android XML layouts, ViewBinding, and Material Components (M3)**. The application runtime is centered around:
- `MainActivity` as the primary host activity
- `MainNavigationController` managing top-level screen visibility and back-press dispatch
- `ActivityMainBinding` generating type-safe bindings for the layout tree
- Dedicated studio controllers: `ImageStudioController`, `VideoStudioController`, `QR Studio` (`QrStudioFragment`)
- Independent tool session managers: `ToolSessionManager`, `ToolExecutionController`
- Media & Storage managers: `SafStorageManager`, `SafDestinationManager`
- Motion & Animation subsystem: `ExpressiveMotion` / `NavigationMotionController`

### Correction Regarding Declarative UI (Jetpack Compose)
A premature migration to Jetpack Compose for this redesign would violate VeilFrame engineering principles:
1. The existing studio engines (`ImageStudioController`, `VideoStudioController`, `QR Studio`) already represent thousands of lines of thoroughly tested, hardware-accelerated View-based implementations.
2. Rewriting the entire UI layer in Compose would introduce massive churn, regression risks, and unnecessary delays without functional benefits.
3. **Decision**: The existing ViewBinding + Material Components architecture will be extended, modernized, and modularized—**not rewritten in Compose**.

### Decoupling `ToolMode` from Navigation
The existing `com.veilframe.app.tools.ToolMode` enum currently encapsulates legacy batch-processing execution workflows:
```kotlin
enum class ToolMode {
    AI_BUNDLE,
    VIDEO_CLEANER,
    IMAGE_CLEANER,
    FOLDER_SCANNER,
    IMAGE_COMPRESSOR,
    VIDEO_COMPRESSOR,
    IMAGE_UPSCALER
}
```
Attempting to force new top-level interactive experiences (such as Document Scanner, Background Remover, or Image Quality) into `ToolMode` would conflate user-facing workspace routing with batch execution state machines.

---

## 2. Decision & Architecture Contracts

```text
                                 ┌────────────────────────┐
                                 │   APP SHELL (ROOT)     │
                                 │ Insets / Motion / Nav  │
                                 └───────────┬────────────┘
                                             │
                   ┌─────────────────────────┼─────────────────────────┐
                   ▼                         ▼                         ▼
             [   HOME   ]              [  TOOLS  ]               [  LIBRARY  ]
         • Intent Launcher          • 13 Workspaces           • Recent Work
         • Quick Tools (≤6)         • Grouped Grid            • Scanner Sessions
         • Status / Privacy         • Search & Synonyms       • Completed Exports
                   │                         │                         │
                   └─────────────────────────┼─────────────────────────┘
                                             ▼
                                  ┌────────────────────┐
                                  │   WORKSPACE ROUTE  │
                                  └──────────┬─────────┘
                                             │
      ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┐
      ▼                  ▼                   ▼                   ▼                  ▼
[ IMAGE STUDIO ]  [ VIDEO STUDIO ]    [ QR STUDIO ]      [ DOC SCANNER ]     [ BG REMOVER ]
(Unchanged UI)    (Unchanged UI)      (Unchanged UI)     • Entry             • Before/After
• Smart Crop      • Motion Engine     • 11 Art Styles    • Live Camera       • 48dp Touch
• Denoise/Enhance • Transcode Engine  • Cleanroom Parity • Page Manager      • Dual Pan/Zoom
                                                         • Shared Editor     • Alpha Masks
                                                         • PDF/Img Export
```

### 2.1 Workspace Routing: Introducing `WorkspaceRoute`
A new `WorkspaceRoute` abstraction is introduced to represent all 13 canonical workspaces across 4 functional domains, leaving `ToolMode` to govern legacy batch executions:

| Category | Workspaces | Destination Type |
|---|---|---|
| **Create & Edit** | `IMAGE_STUDIO`, `VIDEO_STUDIO`, `QR_STUDIO`, `DOCUMENT_SCANNER`, `BACKGROUND_REMOVER`, `IMAGE_UPSCALER` | Dedicated interactive studios |
| **Privacy** | `IMAGE_CLEANER`, `VIDEO_CLEANER` | Metadata & PRNU sanitization tools |
| **Analyze** | `IMAGE_QUALITY`, `FOLDER_SCANNER` | Signal analysis & inspection tools |
| **Developer** | `AI_BUNDLE`, `MARKDOWN_STUDIO`, `PROVENANCE` | Audit, context, and signature tools |

`ScreenState` expands from `[HOME, TOOL, IMAGE_STUDIO, VIDEO_STUDIO, MARKDOWN_VIEWER, IMAGE_UPSCALER, QR_STUDIO]` to:
```kotlin
enum class ScreenState {
    HOME,
    TOOLS,
    LIBRARY,
    TOOL,
    IMAGE_STUDIO,
    VIDEO_STUDIO,
    QR_STUDIO,
    DOCUMENT_SCANNER_ENTRY,
    DOCUMENT_SCANNER_CAMERA,
    DOCUMENT_SCANNER_PAGES,
    DOCUMENT_SCANNER_EDITOR,
    DOCUMENT_SCANNER_EXPORT,
    BACKGROUND_REMOVER,
    IMAGE_QUALITY,
    IMAGE_UPSCALER,
    MARKDOWN_VIEWER
}
```

---

### 2.2 App Shell: Adaptive Navigation & Edge-to-Edge
1. **Three Primary Destinations**:
   - `HOME`: Intent-first starting surface
   - `TOOLS`: Complete catalog of 13 workspaces
   - `LIBRARY`: Resumable work, active sessions, and generated exports
2. **Window Size Classes (Adaptive Layout)**:
   - **Compact (`<600dp` width)**: Fixed bottom navigation bar (`NavigationBarView`).
   - **Medium / Expanded (`≥600dp` width)**: Adaptive vertical navigation rail (`NavigationRailView`), avoiding stretched full-width bottom bars on tablets and foldables.
   - *Rule*: Branching is strictly driven by `WindowMetricsCalculator` / Window Size Classes, never device models or `Build.MODEL`.
3. **Centralized Edge-to-Edge (Android 15 / API 35+)**:
   - Status bar, navigation bar, and IME insets are consumed at the root layout container.
   - Primary interactive action buttons must never be obscured by gesture handles or software keyboards.

---

### 2.3 Intent-First Home Screen
The new Home screen replaces the legacy cluttered dashboard with a focused, intent-first architecture:
- **Header**: Minimalist VeilFrame identity, AMOLED Dark / Monet toggle, and settings launcher.
- **Intent Launcher**: 3 primary action cards:
  - `Image` $\rightarrow$ Shared Source Picker $\rightarrow$ Image Studio
  - `Video` $\rightarrow$ Shared Source Picker $\rightarrow$ Video Studio
  - `Document` $\rightarrow$ Document Scanner Entry
- **Quick Tools**: Strictly $\le 6$ first-tier tools (Image Studio, Video Studio, QR Studio, Document Scanner, Background Remover, AI Upscaler).
- **Privacy & Security Status**: Subtle, offline-verified status indicator.
- *Strict Invariant*: Zero exposure of internal computer vision pipeline jargon (no raw mentions of Canny, Contours, Homography, or raw OpenCV function names).

---

### 2.4 Tools Catalogue with Synonym Search
- Complete catalog containing all 13 workspaces organized under 4 clean category headers (`Create & Edit`, `Privacy`, `Analyze`, `Developer`).
- 2-column Material cards with icons, tool titles, one-line functional summaries, and recent-use badges.
- **Grouped Search with Alias Indexing**:
  - `PDF` $\rightarrow$ Document Scanner
  - `remove bg` / `cutout` $\rightarrow$ Background Remover
  - `metadata` / `exif` $\rightarrow$ Image Cleaner
  - `sharpness` / `blur` $\rightarrow$ Image Quality
  - `QR` / `barcode` $\rightarrow$ QR Code Studio
  - `upscale` / `super-res` $\rightarrow$ AI Upscaler

---

### 2.5 Library Destination
A dedicated work-management hub partitioned into 3 sections:
1. **Recents**: Most recently opened and modified media items with timestamps and tool badges.
2. **Sessions**: Active, resumable multi-page document scanner sessions (e.g., *"Contract 4 pages • Last edited 15m ago"*).
3. **Exports**: Generated `.pdf`, `.png`, `.webp`, `.mp4`, `.aibundle` files with direct `Open`, `Share`, and `Delete` actions.

---

### 2.6 Shared Source Picker
A consolidated modal bottom sheet (`SharedSourcePickerSheet`) offering:
- **Camera** (Hardware CameraX capture)
- **Photos** (Modern Android Photo Picker `ActivityResultContracts.PickVisualMedia`)
- **Files** (Storage Access Framework `ActivityResultContracts.OpenDocument`)

Reused uniformly across Image Studio, Video Studio, Background Remover, AI Upscaler, Image Quality, Image Cleaner, and Video Cleaner.

---

### 2.7 Document Scanner Workflow Architecture
The Document Scanner is a first-class, multi-screen workflow backed by persistent session state:

```text
[ Entry ] ──> [ Document Camera ] ──> [ Page Manager ] ──> [ Document Editor ] ──> [ Export Sheet ]
  Pick /      • Live Quad Detection    • Reorder Pages     • Crop / Rotate          • Single PDF
  Capture     • Stable Shutter         • Rotate / Duplicate• Color / Enhance        • Separate PDFs
              • Rapid Multi-Capture    • Multi-Select      • Scope: Page vs Global  • Images
```

1. **Screen 1 — Entry**: Minimalist page setup screen with *Take Photo*, *Choose from Files*, page counter, and conditional *Continue* button (disabled when `pages.isEmpty()`).
2. **Screen 2 — Document Camera**:
   - Fullscreen CameraX viewfinder.
   - Live document quad tracking via `QuadDetector` rendering an overlay quadrilateral.
   - Status transitions: `Searching...` $\rightarrow$ `Document detected` $\rightarrow$ `Ready`.
   - Rapid capture: Shutter click immediately appends page and resets frame analysis for subsequent captures without terminating camera session.
3. **Screen 3 — Page Manager (`DocumentSession`)**:
   - Multi-page thumbnail grid with drag-and-drop reordering, rotation, deletion, duplication, and page addition.
   - **Process-Death Survival**: `DocumentSession` state and page metadata are persisted to disk cache via structured JSON and local bitmap storage.
4. **Screen 4 — CV Enhancement & Adjustment**:
   - Invisible processing pipeline: Corner Detection $\rightarrow$ Perspective Correction $\rightarrow$ Auto-Crop $\rightarrow$ Binarization/Enhancement.
   - Graceful Degradation: If detection confidence is below threshold, present user with intuitive manual corner drag handles rather than delivering a warped crop.
5. **Screen 5 — Document Editor**:
   - **Zero Engine Duplication**: Editor delegates directly to the underlying `ImageStudioController` image processing pipeline (Crop, Rotate, Color Adjust, Binarize, Sharpen, Denoise).
   - **Edit Scope**: Radio toggle defaulting strictly to `Apply to this page`, with optional `Apply to all pages`.
6. **Screen 6 — Shared Document Export**:
   - Modes: `Single PDF`, `Separate PDFs`, `Individual Images`.
   - Paper Size Policy: `A3`, `A4`, `A5`, `Letter`, `Legal`, `Tabloid`, `Custom`.
   - Orientation: `Auto`, `Portrait`, `Landscape`.
   - Fitting: `Fit`, `Fill`, `Original`.
   - Margins: `None`, `Small`, `Custom`.
   - Raster Quality: `Standard` (150 DPI), `High` (300 DPI), `Maximum` (uncompressed).
   - Per-page override support (e.g., Page 1 in A4 Portrait, Page 2 in A3 Landscape).

---

### 2.8 Background Remover Workflow
- Dedicated screen featuring a split **Before | After** comparison canvas.
- Central draggable divider with $\ge 48\text{dp}$ touch target.
- Synchronized dual pan and zoom transformations between foreground and masked result.
- Physics-based spring settling with haptic ticks at 0%, 50%, and 100%.
- Accessibility: Accessible slider control and TalkBack announcements indicating percentage split.
- Export options: Transparent PNG/WEBP, solid color replacement, custom image backdrop.

---

### 2.9 Image Quality Subsystem
- Standalone analysis workspace evaluating:
  - **Overall Quality Score** (0–100)
  - **Sharpness & Edge Acutance**
  - **Motion & Defocus Blur**
  - **Sensor Noise (ISO Grain)**
  - **Exposure & Dynamic Range**
  - **Contrast & Tone Distribution**
  - **Effective Resolution & Compression Artifacts**
- *Boundary Rule*: Completely decoupled from Image Studio. No intrusive popups or automatic upscaler triggers during standard editing.
- *Future Pipeline*: Groundwork laid for `ImageSession` recommendation gate $\rightarrow$ AI Upscaler handoff.

---

## 3. Implementation Phases

| Phase | Milestone | Deliverables |
|---|---|---|
| **Phase 0** | **Foundation** | Centralize API 35 window insets; introduce `WorkspaceRoute`; declare theme & motion tokens; implement `SharedSourcePickerSheet`; define `SharedExportContract`. |
| **Phase 1** | **App Shell Redesign** | Implement intent-first Home screen; 13-workspace Tools catalog with synonym search; Library destination; adaptive NavigationRail for medium/expanded window classes. |
| **Phase 2** | **Document Scanner** | Implement Entry screen, CameraX Document Camera with live quad detection, persistent `DocumentSession` Page Manager, and Document Editor wired to Image Studio engine. |
| **Phase 3** | **Export Architecture** | Build unified Export sheet supporting single/multi PDF generation, paper size/orientation/margin layout policies, and SAF destination dispatch. |
| **Phase 4** | **Background Remover** | Implement Background Remover studio with interactive draggable Before/After slider, spring physics, dual pan/zoom, and alpha export. |
| **Phase 5** | **Image Quality Engine** | Implement standalone Image Quality analyzer scoring acutance, blur, noise, and compression. |
| **Phase 6** | **Intelligent Handoffs** | Connect `ImageSession` handoff from Image Quality $\rightarrow$ Upscale Recommendation $\rightarrow$ AI Upscaler. |

---

## 4. Architectural Boundaries & Non-Goals

1. **No Compose Rewrite**: All visual components will utilize ViewBinding, XML layouts, and Material 3 Android components.
2. **No Editor Restructuring**: Internal layouts and logic of `ImageStudioController`, `VideoStudioController`, and `QR Studio` remain completely intact.
3. **OpenCV 5 Decoupling**: UI controllers consume abstract Kotlin interfaces (`DocumentDetectionResult`, `SegmentationResult`, `QualityScore`); underlying OpenCV / NDK implementation changes do not alter UI contracts.
4. **ONNX Upscaler Decoupling**: AI Upscaler backend repairs remain isolated from this UI architecture.
