package com.veilframe.app.pdf.operations

import kotlin.math.abs
import kotlin.math.roundToInt

/** Opaque RGB resolution for spreadsheet cell colors, independent of Android UI theme. */
internal object XlsxColors {
    // ISO/IEC 29500 indexedColors default palette, indices 0..63.
    private val palette = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )
    val themeSlots = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2",
        "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

    fun indexed(index: Int, custom: List<Int>?): Int? {
        require(index in 0..255) { "Invalid indexed color reference" }
        // System-dependent colors are not guessed from the device UI.
        if (index == 64 || index == 65) return null
        return if (custom == null) palette.getOrNull(index) else custom.getOrNull(index)
    }

    fun parseArgb(raw: String): Int {
        require(raw.matches(Regex("[0-9A-Fa-f]{8}"))) { "Invalid explicit cell ARGB color" }
        return raw.takeLast(6).toInt(16)
    }

    fun parseRgb(raw: String): Int {
        require(raw.matches(Regex("[0-9A-Fa-f]{6}"))) { "Invalid theme RGB color" }
        return raw.toInt(16)
    }

    /** OOXML tint changes HSL luminance, not each sRGB channel independently. */
    fun tint(rgb: Int, amount: Double): Int {
        require(rgb in 0..0xFFFFFF && amount.isFinite() && amount in -1.0..1.0)
        if (amount == 0.0) return rgb
        if (amount == -1.0) return 0
        if (amount == 1.0) return 0xFFFFFF
        val r = (rgb ushr 16 and 255) / 255.0
        val g = (rgb ushr 8 and 255) / 255.0
        val b = (rgb and 255) / 255.0
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val lightness = (high + low) / 2
        val changed = if (amount < 0) lightness * (1 + amount)
            else lightness + (1 - lightness) * amount
        val delta = high - low
        val oldChromaFactor = 1 - abs(2 * lightness - 1)
        val ratio = if (delta == 0.0 || oldChromaFactor <= 0.0) 0.0
            else (1 - abs(2 * changed - 1)) / oldChromaFactor
        fun channel(value: Double): Int =
            ((changed + (value - lightness) * ratio) * 255).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
