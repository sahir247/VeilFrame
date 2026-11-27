package com.veilframe.app.pdf.operations

import java.io.IOException

/** Maps PDFRenderer's rotated crop-box bitmap into unrotated PDF user space. */
internal class OcrPageGeometry(
    private val x: Float, private val y: Float,
    private val width: Float, private val height: Float,
    rotation: Int, private val imageWidth: Int, private val imageHeight: Int
) {
    private val rotation = ((rotation % 360) + 360) % 360
    init {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(imageWidth > 0 && imageHeight > 0)
        require(this.rotation in listOf(0, 90, 180, 270))
    }
    data class Placement(val x: Float, val y: Float, val ux: Float, val uy: Float, val width: Float, val height: Float)

    fun place(left: Int, top: Int, right: Int, bottom: Int): Placement {
        val l = left.coerceIn(0, imageWidth)
        val t = top.coerceIn(0, imageHeight)
        val r = right.coerceIn(0, imageWidth)
        val b = bottom.coerceIn(0, imageHeight)
        if (r <= l || b <= t) throw IOException("Invalid OCR word bounds")
        val u = l.toFloat() / imageWidth
        val v = b.toFloat() / imageHeight
        val dw = (r - l).toFloat() / imageWidth
        val dh = (b - t).toFloat() / imageHeight
        return when (rotation) {
            0 -> Placement(x + u * width, y + (1 - v) * height, 1f, 0f, dw * width, dh * height)
            90 -> Placement(x + v * width, y + u * height, 0f, 1f, dw * height, dh * width)
            180 -> Placement(x + (1 - u) * width, y + v * height, -1f, 0f, dw * width, dh * height)
            else -> Placement(x + (1 - v) * width, y + (1 - u) * height, 0f, -1f, dw * height, dh * width)
        }
    }
}
