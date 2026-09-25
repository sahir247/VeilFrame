package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Visual style enum corresponding to the 11 VeilFrame Art Engine rendering modes.
 */
enum class QrStyle {
    BASIC,              // Classic square modules with per-zone shape overrides
    BUBBLE,             // Organic bubble-cluster modules
    D25,                // 2.5D isometric projection (three-face cubes)
    DSJ,                // DJ-inspired radial/cross position patterns
    IMAGE_FILL,         // Background image baked into module color
    IMAGE,              // Full background image overlay composited with modules
    IMAGE_RESAMPLE,     // Pixelated image resample into QR matrix
    LINE,               // Horizontal/vertical stripe patterns
    RANDOM_RECTANGLE,   // Randomized rectangle modules with noise
    FUNCTION,           // Function-based custom module shapes
    STYLE_FUNCTION,     // Style-level function override
    CONNECTED_ORGANIC   // Organic graph-connected module paths
}

/** Shape options for individual module zones (position patterns, data modules, etc.). */
enum class ModuleShape {
    RECTANGLE,
    ROUND,
    ROUNDED_RECTANGLE,
    PLANETS,
    DSJ
}

/**
 * Unified parameter bundle for QR code generation.
 *
 * Designed to mirror VeilFrame Art Engine's style parameter hierarchy while being
 * idiomatic Kotlin/Android. Renderers read only the fields relevant to
 * their style; unused fields are safely ignored.
 */
data class QrStyleParams(
    /** Target output bitmap size in pixels. */
    val outputSize: Int = 512,
    /** QR module foreground color (dark modules). */
    val foreground: Int = Color.BLACK,
    /** Background color (light modules / canvas). */
    val background: Int = Color.WHITE,
    /** Style to render. */
    val style: QrStyle = QrStyle.BASIC,
    /** Data module shape (used by BASIC style). */
    val dataShape: ModuleShape = ModuleShape.RECTANGLE,
    /** Data module scale relative to one cell (0.0–1.0). */
    val dataScale: Float = 0.85f,
    /** Position-detection pattern shape. */
    val positionShape: ModuleShape = ModuleShape.RECTANGLE,
    /** Position pattern fill color (defaults to foreground). */
    val positionColor: Int? = null,
    /** Alignment pattern shape. */
    val alignShape: ModuleShape = ModuleShape.RECTANGLE,
    /** Timing pattern shape. */
    val timingShape: ModuleShape = ModuleShape.RECTANGLE,
    /** Optional logo/icon centered over the QR code. */
    val logo: Bitmap? = null,
    /** Logo size as fraction of output size (0.0–0.33). */
    val logoFraction: Float = 0.22f,
    /** Optional logo border color. */
    val logoBorderColor: Int? = null,
    /** Optional logo border width in pixels. */
    val logoBorderWidth: Float = 0f,
    /** Optional logo shape (SQUIRCLE, CIRCLE, SQUARE). */
    val logoShape: com.veilframe.app.qr.model.LogoShape = com.veilframe.app.qr.model.LogoShape.SQUIRCLE,
    /** Optional background image (used by IMAGE* and IMAGE_FILL styles). */
    /** Optional background image (used by backdrop and legacy styles). */
    val backgroundImage: Bitmap? = null,
    /** Blend alpha for background image overlay (0.0–1.0). */
    val backgroundImageAlpha: Float = 0.85f,
    /** Source image specifically for IMAGE, IMAGE_FILL, and IMAGE_RESAMPLE styles (distinct from backdrop). */
    val sourceImage: Bitmap? = null,
    /** Alpha for the style source image (0.0–1.0). */
    val sourceImageAlpha: Float = 1.0f,
    // --- 2.5D specific (VeilFrameStyle25D) ---
    val d25TopColor: Int = Color.BLACK,
    val d25LeftColor: Int = 0x33000000,
    val d25RightColor: Int = 0x99000000.toInt(),
    val d25DataHeight: Float = 1.0f,
    val d25PositionHeight: Float = 1.0f,
    // --- IMAGE style specific (VeilFrameStyleImage) ---
    val imageAllowTransparent: Boolean = true,
    val imageDataDarkColor: Int = Color.BLACK,
    val imageDataLightColor: Int = Color.WHITE,
    val imagePositionDarkColor: Int = Color.BLACK,
    val imagePositionLightColor: Int = Color.WHITE,
    val imagePositionSize: Float = 1.0f,
    val imageTimingDarkColor: Int = Color.BLACK,
    val imageTimingLightColor: Int = Color.WHITE,
    val imageTimingSize: Float = 1.0f,
    val imageAlignDarkColor: Int = Color.BLACK,
    val imageAlignLightColor: Int = Color.WHITE,
    val imageAlignSize: Float = 1.0f,
    // --- IMAGE_FILL style specific (VeilFrameStyleImageFill) ---
    val imageFillBackgroundColor: Int = Color.WHITE,
    val imageFillMaskColor: Int = 0x1A000000, // 10% black
    // --- Bubble specific ---
    val bubbleOutlineColor: Int = 0xFF8ED1FC.toInt(),
    val bubbleCenterColor: Int = Color.WHITE,
    // --- DSJ specific (VeilFrameStyleDSJ) ---
    val dsjLineSize: Float = 0.7f,
    val dsjXSize: Float = 0.7f,
    val dsjHorizontalLineColor: Int = 0xFFF6B506.toInt(),
    val dsjVerticalLineColor: Int = 0xFFE02020.toInt(),
    val dsjXColor: Int = 0xFF0B2D97.toInt(),
    // --- Function specific (VeilFrameStyleFunction) ---
    val functionType: com.veilframe.app.qr.model.VeilFunctionType = com.veilframe.app.qr.model.VeilFunctionType.FADE,
    val functionDataStyle: com.veilframe.app.qr.model.VeilFunctionDataStyle = com.veilframe.app.qr.model.VeilFunctionDataStyle.ROUND,
    val functionDataColor: Int = Color.BLACK,
    val functionCircleColor: Int = Color.BLACK,
    // --- Line specific (VeilFrameStyleLine) ---
    val lineDirection: com.veilframe.app.qr.model.LineDirection = com.veilframe.app.qr.model.LineDirection.X,
    val lineThickness: Float = 0.5f,
    val lineColor: Int? = null,
    val lineVerticalColor: Int? = null,   // legacy null → use foreground
    val lineHorizontalColor: Int? = null,
    // --- Random Rectangle specific (VeilFrameStyleRandomRectangle) ---
    val randomRectColor: Int? = null,
    val randomRectSeed: Long = 42L,
    // --- Quiet Zone (null defaults to 4 for SAFE mode, 1 for artistic mode) ---
    val quietZone: Int? = null,
    val quietZoneLeft: Int? = null,
    val quietZoneTop: Int? = null,
    val quietZoneRight: Int? = null,
    val quietZoneBottom: Int? = null,
    // --- Gradient (optional, applied over rendered bitmap) ---
    val gradientStart: Int? = null,
    val gradientEnd: Int? = null,
    // --- Resample specific (VeilFrameStyleResampleImage) ---
    val resampleUseSourceAsBackdrop: Boolean = true,
    val resampleBackdropOpacity: Float = 1.0f,
    val resampleBackdropScaleMode: com.veilframe.app.qr.model.ImageScaleMode = com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL,
    val resampleSeed: Long = 42L,
    val resampleBackdropTint: Int? = null,
    // --- Per-zone colors ---
    val timingColor: Int? = null,
    val alignmentColor: Int? = null
)
