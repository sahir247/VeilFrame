package com.veilframe.app.pdf.ui

import java.util.IdentityHashMap

/** Bound both placements and retained unique bitmap pixels (copies share the same image). */
internal object SignatureCanvasPlacementBudget {
    const val MAX_PLACEMENTS = 256
    const val MAX_IMAGE_PIXELS = 4_000_000L
    const val MAX_UNIQUE_PIXELS = 16_000_000L

    fun <T : Any> admit(existing: List<T>, candidate: T, pixels: (T) -> Long): Boolean {
        if (existing.size >= MAX_PLACEMENTS) return false
        val seen = IdentityHashMap<T, Boolean>()
        var total = 0L
        for (image in existing + candidate) {
            if (seen.put(image, true) != null) continue
            val size = pixels(image)
            if (size !in 1..MAX_IMAGE_PIXELS) return false
            total += size
            if (total > MAX_UNIQUE_PIXELS) return false
        }
        return true
    }

    fun fit(width: Int, height: Int, pageWidth: Float, pageHeight: Float): Pair<Float, Float> {
        require(width > 0 && height > 0 && pageWidth.isFinite() && pageHeight.isFinite()
            && pageWidth > 0 && pageHeight > 0)
        val factor = minOf(minOf(160.0, pageWidth * 0.6) / width,
            minOf(120.0, pageHeight * 0.6) / height)
        return (width * factor).toFloat() to (height * factor).toFloat()
    }
}
