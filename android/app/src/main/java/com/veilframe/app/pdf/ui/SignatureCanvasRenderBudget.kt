package com.veilframe.app.pdf.ui

import kotlin.math.sqrt

/** Bound both dimensions and pixels before allocating a canvas preview. */
internal object SignatureCanvasRenderBudget {
    fun dimensions(width: Int, height: Int): Pair<Int,Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.6,4096.0/width,4096.0/height,
            sqrt(4_000_000.0/(width.toDouble()*height)))
        return (width*scale).toInt().coerceAtLeast(1) to (height*scale).toInt().coerceAtLeast(1)
    }
}
