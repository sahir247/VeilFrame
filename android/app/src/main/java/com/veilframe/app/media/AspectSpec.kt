package com.veilframe.app.media

/**
 * Canonical aspect ratio specification for media transformations.
 */
sealed class AspectSpec {
    object Original : AspectSpec() {
        override fun toString(): String = "Original"
    }

    object Free : AspectSpec() {
        override fun toString(): String = "Free"
    }

    data class Ratio(val widthRatio: Float, val heightRatio: Float) : AspectSpec() {
        init {
            require(widthRatio > 0f && heightRatio > 0f) {
                "Width and height ratio must be positive, got $widthRatio:$heightRatio"
            }
        }

        val aspect: Float get() = widthRatio / heightRatio

        override fun toString(): String {
            return if (widthRatio == 1f && heightRatio == 1f) "1:1"
            else "${widthRatio.toInt()}:${heightRatio.toInt()}"
        }
    }

    companion object {
        val ASPECT_1_1 = Ratio(1f, 1f)
        val ASPECT_4_3 = Ratio(4f, 3f)
        val ASPECT_3_4 = Ratio(3f, 4f)
        val ASPECT_16_9 = Ratio(16f, 9f)
        val ASPECT_9_16 = Ratio(9f, 16f)

        fun fromString(aspect: String?): AspectSpec {
            if (aspect.isNullOrBlank()) return Original
            val clean = aspect.trim()
            return when {
                clean.equals("Original", ignoreCase = true) -> Original
                clean.equals("Free", ignoreCase = true) -> Free
                clean == "1:1" || clean.contains("Passport", ignoreCase = true) -> ASPECT_1_1
                clean.contains("9:16") -> ASPECT_9_16
                clean.contains("16:9") -> ASPECT_16_9
                clean.contains("4:3") -> ASPECT_4_3
                clean.contains("3:4") -> ASPECT_3_4
                clean.contains("1:1") -> ASPECT_1_1
                else -> {
                    val parts = clean.split(":", "x", "×", "/")
                    if (parts.size == 2) {
                        val w = parts[0].trim().toFloatOrNull()
                        val h = parts[1].trim().toFloatOrNull()
                        if (w != null && h != null && w > 0f && h > 0f) {
                            Ratio(w, h)
                        } else Original
                    } else Original
                }
            }
        }
    }
}
