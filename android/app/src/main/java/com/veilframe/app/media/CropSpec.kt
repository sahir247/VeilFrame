package com.veilframe.app.media

/**
 * Normalized crop specification (0.0f .. 1.0f).
 * Coordinates are normalized relative to width and height:
 * - left: [0.0, 1.0)
 * - top: [0.0, 1.0)
 * - right: (0.0, 1.0]
 * - bottom: (0.0, 1.0]
 */
data class CropSpec(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "CropSpec coordinates must be normalized between 0.0 and 1.0, got ($left, $top, $right, $bottom)"
        }
        require(left < right && top < bottom) {
            "CropSpec left/top must be strictly less than right/bottom: ($left, $top, $right, $bottom)"
        }
    }

    val widthFraction: Float get() = right - left
    val heightFraction: Float get() = bottom - top

    val width: Float get() = widthFraction
    val height: Float get() = heightFraction
    fun width(): Float = widthFraction
    fun height(): Float = heightFraction

    fun isIdentity(): Boolean =
        left <= 0.0001f && top <= 0.0001f && right >= 0.9999f && bottom >= 0.9999f

    companion object {
        val FULL = CropSpec(0f, 0f, 1f, 1f)

        /**
         * Creates a CropSpec from a percentage crop (e.g. customCropPercent 0..80).
         * Cuts equally from top and bottom while preserving full width.
         */
        fun fromCustomCropPercent(percent: Int): CropSpec {
            val p = percent.coerceIn(0, 80).toFloat()
            if (p <= 0.001f) return FULL
            val inset = (p / 200f) // e.g. 20% -> 0.10 top and 0.10 bottom
            return CropSpec(
                left = 0f,
                top = inset,
                right = 1f,
                bottom = 1f - inset
            )
        }
    }
}
