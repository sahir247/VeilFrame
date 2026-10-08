# VeilFrame Architecture Decision Records (ADRs)

This directory maintains the immutable architectural history and governance records for VeilFrame.

## Architectural Decision Register

| ADR | Title | Status | Baseline | Scope |
|:---:|:---|:---:|:---:|:---|
| [**0002**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0002-remove-vmaf-from-production-quality-pipeline.md) | Remove VMAF from Production Quality Pipeline | Accepted | v2.1.0 | Deprecate heavy synthetic VMAF modeling in favor of native mobile perceptual metrics. |
| [**0003**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0003-efqrcode-parity-architecture-and-verification.md) | EFQRCode Parity Architecture & Verification | Accepted | v2.2.0 | Canonical Geometry IR, gradient angle math, and differential Canvas/SVG testing against EFQRCode 7.0.3. |
| [**0004**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0004-android-ui-redesign-architecture.md) | Android UI Redesign Architecture | Accepted | v2.2.5 | Single-activity ViewBinding architecture, 13 canonical workspaces, decoupled from legacy batch ToolMode. |
| [**0005**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0005-m3-expressive-design-system.md) | Material 3 Expressive Design System ("Quiet Intensity") | Accepted | v2.2.9 | MDC 1.14.0 migration, physics springs (`VfSprings v3.1`), surface-container hierarchy, emphasized type, and monochrome-first theming. |
| [**0006**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0006-cv-governance-dynamic-memory.md) | CV Subsystem Governance & Dynamic Memory Policy | Accepted | v2.2.9 | Governed execution for 6–16 GB device fleet, streaming band-processing for upscaling, and fail-honest error surfacing. |
| [**0007**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0007-motion-lab-optical-flow-synthesis.md) | Motion Lab & Native Optical Flow Frame Interpolation | Accepted | v2.2.9 | Introduction of the 14th canonical workspace (`MOTION_LAB`), DIS/Farnebäck optical flow synthesis, and flat-memory pairwise frame processing. |
| [**0008**](file:///c:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/docs/adr/0008-insets-contract-and-camera-stabilization.md) | Real-Time Edge-to-Edge System Insets & Viewport Stabilization | Accepted | v2.2.9 | Centralized `WorkspaceInsets` contract for edge-to-edge layout, single-flight CameraX capture ownership, and EMA `QuadStabilizer`. |
