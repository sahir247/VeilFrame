package com.veilframe.app.qr.renderer

/**
 * Geometric bounding box for a 3x3 subpixel element.
 */
data class SubpixelRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * Single mathematical source of truth for 3x3 stochastic subpixel coordinate geometry.
 *
 * Guarantees exact coordinate equivalence across Canvas rendering and SVG vector generation:
 * - Subpixel width & height: `(moduleSize / 3.0) * antiGapScale` (default 1.02 to eliminate subpixel raster seams).
 * - Subpixel offset: `moduleOrigin + (subIndex % 3) * (moduleSize / 3.0)`.
 */
object SubpixelGeometry {

    const val ANTI_GAP_SCALE: Float = 1.02f

    /**
     * Computes the bounding rectangle for subpixel ([subX], [subY]) within a module
     * positioned at ([moduleLeft], [moduleTop]) with dimension [moduleSize].
     */
    fun computeRect(
        subX: Int,
        subY: Int,
        moduleLeft: Float,
        moduleTop: Float,
        moduleSize: Float,
        antiGapScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect {
        val subStep = moduleSize / 3f
        val dx = subX % 3
        val dy = subY % 3
        val offset = (antiGapScale - 1.0f) / 2f * subStep
        val left = moduleLeft + dx * subStep - offset
        val top = moduleTop + dy * subStep - offset
        val w = subStep * antiGapScale
        val h = subStep * antiGapScale
        return SubpixelRect(left, top, w, h)
    }

    /**
     * Computes the bounding rectangle for Canvas rendering directly from logical matrix coordinates.
     * Avoids intermediate RectF allocation.
     */
    fun computeCanvasRect(
        col: Int,
        row: Int,
        offsetX: Float,
        offsetY: Float,
        moduleSize: Float,
        subX: Int,
        subY: Int,
        antiGapScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect {
        val moduleLeft = offsetX + (col * moduleSize)
        val moduleTop = offsetY + (row * moduleSize)
        return computeRect(subX, subY, moduleLeft, moduleTop, moduleSize, antiGapScale)
    }

    /**
     * Computes the bounding rectangle matching exact upstream EFQRCode Sb dot geometry:
     * EF defines `<rect id="Sb" width="1.02" height="1.02"/>` and positions directly at `(x, y)`
     * without symmetric backward centering shift `-(1.02 - 1)/2`.
     */
    fun computeEfRect(
        subX: Int,
        subY: Int,
        moduleLeft: Float,
        moduleTop: Float,
        moduleSize: Float,
        dotScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect {
        val subStep = moduleSize / 3f
        val dx = subX % 3
        val dy = subY % 3
        val left = moduleLeft + dx * subStep
        val top = moduleTop + dy * subStep
        val w = subStep * dotScale
        val h = subStep * dotScale
        return SubpixelRect(left, top, w, h)
    }

    /**
     * Computes the bounding rectangle for exact EFQRCode Sb dot coordinates from matrix column and row.
     */
    fun computeEfCanvasRect(
        col: Int,
        row: Int,
        offsetX: Float,
        offsetY: Float,
        moduleSize: Float,
        subX: Int,
        subY: Int,
        dotScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect {
        val moduleLeft = offsetX + (col * moduleSize)
        val moduleTop = offsetY + (row * moduleSize)
        return computeEfRect(subX, subY, moduleLeft, moduleTop, moduleSize, dotScale)
    }

    /**
     * Normalized coordinate helper for SVG vector export where moduleSize = 1.0.
     *
     * @param col Module column (0 until matrix.size)
     * @param row Module row (0 until matrix.size)
     * @param quietZone Quiet zone margin in modules
     * @param subX Subpixel X in [0, 3 * matrix.size)
     * @param subY Subpixel Y in [0, 3 * matrix.size)
     */
    fun computeSvgRect(
        col: Int,
        row: Int,
        quietZone: Int,
        subX: Int,
        subY: Int,
        antiGapScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect = computeSvgRect(col, row, quietZone, quietZone, subX, subY, antiGapScale)

    /**
     * Normalized coordinate helper for SVG vector export supporting asymmetric quiet zones.
     */
    fun computeSvgRect(
        col: Int,
        row: Int,
        quietZoneLeft: Int,
        quietZoneTop: Int,
        subX: Int,
        subY: Int,
        antiGapScale: Float = ANTI_GAP_SCALE
    ): SubpixelRect {
        val moduleLeft = (col + quietZoneLeft).toFloat()
        val moduleTop = (row + quietZoneTop).toFloat()
        return computeRect(subX, subY, moduleLeft, moduleTop, 1.0f, antiGapScale)
    }
}
