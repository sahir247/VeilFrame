package com.veilframe.app.qr

import android.graphics.Bitmap

/**
 * Deterministic SVG rasterizer test facade.
 * Delegates directly to the canonical production rasterizer [com.veilframe.app.qr.raster.DeterministicSvgRasterizer]
 * to prevent duplicate divergence and ensure all tests exercise production code.
 */
object DeterministicSvgRasterizer {
    fun rasterize(svgString: String, targetWidth: Int, targetHeight: Int): Bitmap {
        return com.veilframe.app.qr.raster.DeterministicSvgRasterizer.rasterize(svgString, targetWidth, targetHeight)
    }
}
