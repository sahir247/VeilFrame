package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Visual style enum corresponding to the 11 EFQRCode rendering modes.
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
    STYLE_FUNCTION      // Style-level function override
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
 * Designed to mirror EFQRCode's EFStyleParams hierarchy while being
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
    /** Optional background image (used by IMAGE* and IMAGE_FILL styles). */
    val backgroundImage: Bitmap? = null,
    /** Blend alpha for background image overlay (0.0–1.0). */
    val backgroundImageAlpha: Float = 0.85f,
    // --- 2.5D specific ---
    val d25TopColor: Int = Color.BLACK,
    val d25LeftColor: Int = Color.DKGRAY,
    val d25RightColor: Int = 0xFF888888.toInt(),
    val d25DataHeight: Float = 0.5f,
    val d25PositionHeight: Float = 0.5f,
    // --- Bubble specific ---
    val bubbleOutlineColor: Int = 0xFF8ED1FC.toInt(),
    val bubbleCenterColor: Int = Color.WHITE,
    // --- Line specific ---
    val lineVerticalColor: Int? = null,   // null → use foreground
    val lineHorizontalColor: Int? = null,
    // --- Random Rectangle specific ---
    val randomRectSeed: Long = 42L,
    // --- Gradient (optional, applied over rendered bitmap) ---
    val gradientStart: Int? = null,
    val gradientEnd: Int? = null,
)
