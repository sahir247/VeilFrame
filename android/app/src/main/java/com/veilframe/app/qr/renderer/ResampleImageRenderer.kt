package com.veilframe.app.qr.renderer

/**
 * Style 7 — IMAGE_RESAMPLE (Pixelated image resample into QR matrix)
 *
 * Implements authentic VeilFrame Art Engine-inspired 3x3 stochastic subpixel resampling architecture:
 * - Structural patterns (finders, timing tracks, alignment patterns) maintain crisp solid contrast per ISO/IEC 18004.
 * - Center subpixel (1, 1) of every dark data module is strictly reserved as the QR bit anchor.
 * - Surrounding 8 subpixels carry stochastic halftone photo dithering.
 * - Consumes strictly [com.veilframe.app.qr.model.QrDesign.imageSource], with zero fallback to background image.
 * - Delegated canonically to [ComposableQrRenderer].
 */
class ResampleImageRenderer : ComposableQrRenderer()


