package com.veilframe.app.pdf.operations

import kotlin.math.ceil
import kotlin.math.sqrt

/** Bound the actual PDFRenderer raster allocation, not only an estimated DPI. */
object OcrDpiUtil {
    private const val DEFAULT_DPI = 200f
    internal const val MAX_PIXELS = 4_000_000L
    internal const val MAX_EDGE = 8192
    private const val MIN_DPI = 36f

    fun getSafeOcrDpi(pageWidthPoints: Float, pageHeightPoints: Float): Float {
        require(pageWidthPoints.isFinite() && pageHeightPoints.isFinite() &&
            pageWidthPoints > 0 && pageHeightPoints > 0) { "Invalid PDF crop box dimensions" }
        val width = pageWidthPoints.toDouble()
        val height = pageHeightPoints.toDouble()
        // Compute in Double: Float-to-Int saturation and a 120-DPI floor
        // previously let large-format pages exceed the pixel budget.
        val scale = minOf(DEFAULT_DPI / 72.0, sqrt((MAX_PIXELS - MAX_EDGE * 2) / (width * height)),
            (MAX_EDGE - 1) / width, (MAX_EDGE - 1) / height)
        val dpi = (scale * 72.0).toFloat()
        require(dpi.isFinite() && dpi >= MIN_DPI) {
            "PDF page is too large for bounded rasterization; crop or split the page first"
        }
        val actualScale = dpi / 72f
        val w = ceil((pageWidthPoints * actualScale).toDouble()).toLong().coerceAtLeast(1)
        val h = ceil((pageHeightPoints * actualScale).toDouble()).toLong().coerceAtLeast(1)
        require(w <= MAX_EDGE && h <= MAX_EDGE && w * h <= MAX_PIXELS) {
            "PDF raster exceeds safe image budget"
        }
        return dpi
    }
}
