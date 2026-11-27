package com.veilframe.app.cv.segmentation.rembg

/** Composite straight sRGB cutout pixels against an explicitly selected opaque backdrop. */
internal enum class CutoutBackdrop(private val channel: Int?) {
    TRANSPARENT(null), WHITE(255), BLACK(0);

    fun apply(row: IntArray) {
        val background = channel ?: return
        for (i in row.indices) {
            val pixel = row[i]
            val alpha = pixel ushr 24
            if (alpha == 255) continue
            val base = background * (255 - alpha)
            val r = ((pixel ushr 16 and 255) * alpha + base + 127) / 255
            val g = ((pixel ushr 8 and 255) * alpha + base + 127) / 255
            val b = ((pixel and 255) * alpha + base + 127) / 255
            row[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
