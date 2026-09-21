package com.veilframe.app.media.whatsapp

import com.veilframe.app.media.AspectSpec
import com.veilframe.app.media.CropSpec
import java.util.Locale

/**
 * Resolved geometric dimensions and aspect ratios for media processing.
 */
data class ResolvedGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val sampleAspectRatio: String = "1:1",
    val displayAspectRatio: Double = if (height > 0) width.toDouble() / height.toDouble() else 1.0
) {
    val effectiveWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val effectiveHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
    val effectiveDar: Double get() = if (effectiveHeight > 0) effectiveWidth.toDouble() / effectiveHeight.toDouble() else 1.0
}

/**
 * Canonical Aspect Ratio Resolver for media processing pipelines.
 * Provides deterministic geometric resolution, crop rectangle calculation,
 * and display aspect ratio validation with SAR and rotation compensation.
 */
object AspectRatioResolver {

    data class ResolvedAspect(
        val numerator: Int,
        val denominator: Int,
        val isPortrait: Boolean,
        val dar: Double
    )

    /**
     * Resolves aspect ratio into canonical numerator/denominator and orientation.
     */
    fun resolve(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1): ResolvedAspect {
        val sar = if (sarDen > 0) sarNum.toDouble() / sarDen.toDouble() else 1.0
        val effectiveWidth = width.toDouble() * sar
        val effectiveHeight = height.toDouble()
        val dar = if (effectiveHeight > 0) effectiveWidth / effectiveHeight else 1.0
        val isPortrait = effectiveHeight > effectiveWidth

        val (num, den) = when {
            Math.abs(dar - 16.0 / 9.0) < 0.03 -> 16 to 9
            Math.abs(dar - 9.0 / 16.0) < 0.03 -> 9 to 16
            Math.abs(dar - 4.0 / 3.0) < 0.03 -> 4 to 3
            Math.abs(dar - 3.0 / 4.0) < 0.03 -> 3 to 4
            Math.abs(dar - 1.0) < 0.03 -> 1 to 1
            else -> {
                val g = gcd(width, height)
                (width / g) to (height / g)
            }
        }
        return ResolvedAspect(num, den, isPortrait, dar)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return if (x != 0) Math.abs(x) else 1
    }

    /**
     * Parses a SAR string (e.g. "1:1", "4:3", "0:1") into a Double multiplier.
     */
    fun parseSar(sarStr: String?): Double {
        if (sarStr.isNullOrBlank() || sarStr == "N/A" || sarStr == "0:1") return 1.0
        val parts = sarStr.split(":")
        if (parts.size == 2) {
            val num = parts[0].toDoubleOrNull() ?: 1.0
            val den = parts[1].toDoubleOrNull() ?: 1.0
            return if (den > 0) num / den else 1.0
        }
        return 1.0
    }

    /**
     * Resolves source geometry including SAR and rotation into canonical display geometry.
     */
    fun resolveSourceGeometry(
        width: Int,
        height: Int,
        rotation: Int = 0,
        sarStr: String = "1:1"
    ): ResolvedGeometry {
        val sar = parseSar(sarStr)
        val rawDar = if (height > 0) (width.toDouble() * sar) / height.toDouble() else 1.0
        return ResolvedGeometry(
            width = width,
            height = height,
            rotation = rotation,
            sampleAspectRatio = sarStr,
            displayAspectRatio = rawDar
        )
    }

    /**
     * Computes the normalized CropSpec required to crop a source rectangle to the target aspect ratio,
     * centering the crop window.
     */
    fun calculateCenteredCropSpec(
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: AspectSpec
    ): CropSpec {
        if (sourceWidth <= 0 || sourceHeight <= 0) return CropSpec.FULL
        val targetRatio = when (targetAspect) {
            is AspectSpec.Original, is AspectSpec.Free -> return CropSpec.FULL
            is AspectSpec.Ratio -> targetAspect.aspect.toDouble()
        }

        val sourceRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
        return if (Math.abs(sourceRatio - targetRatio) < 0.001) {
            CropSpec.FULL
        } else if (sourceRatio > targetRatio) {
            // Source is wider than target -> crop left & right
            val targetW = sourceHeight * targetRatio
            val insetX = ((sourceWidth - targetW) / 2.0) / sourceWidth.toDouble()
            CropSpec(
                left = insetX.toFloat().coerceIn(0f, 0.49f),
                top = 0f,
                right = (1.0 - insetX).toFloat().coerceIn(0.51f, 1f),
                bottom = 1f
            )
        } else {
            // Source is taller than target -> crop top & bottom
            val targetH = sourceWidth / targetRatio
            val insetY = ((sourceHeight - targetH) / 2.0) / sourceHeight.toDouble()
            CropSpec(
                left = 0f,
                top = insetY.toFloat().coerceIn(0f, 0.49f),
                right = 1f,
                bottom = (1.0 - insetY).toFloat().coerceIn(0.51f, 1f)
            )
        }
    }

    /**
     * Formats a width x height pair into a standard DAR string (e.g. "16:9", "9:16", "1:1").
     */
    fun formatDar(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "1:1"
        val ratio = width.toDouble() / height.toDouble()
        return when {
            Math.abs(ratio - 9.0 / 16.0) < 0.03 -> "9:16"
            Math.abs(ratio - 16.0 / 9.0) < 0.03 -> "16:9"
            Math.abs(ratio - 1.0) < 0.03 -> "1:1"
            Math.abs(ratio - 4.0 / 3.0) < 0.03 -> "4:3"
            Math.abs(ratio - 3.0 / 4.0) < 0.03 -> "3:4"
            else -> String.format(Locale.US, "%.2f:1", ratio)
        }
    }
}
