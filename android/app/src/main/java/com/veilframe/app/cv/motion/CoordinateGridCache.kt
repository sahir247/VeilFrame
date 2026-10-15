package com.veilframe.app.cv.motion

import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Reuses identity coordinate grids across consecutive frame warps.
 *
 * The cache is deliberately limited to 1080p-sized grids to avoid pinning a
 * large amount of native memory for 4K/8K frames. Larger grids are temporary.
 * The callback runs under the cache lock so a size change cannot release a
 * cached Mat while OpenCV is reading it.
 */
internal object CoordinateGridCache {
    private const val MAX_CACHED_PIXELS = 2_073_600L

    private var cachedRows = 0
    private var cachedCols = 0
    private var cachedX: Mat? = null
    private var cachedY: Mat? = null

    @Synchronized
    fun <T> withGrid(rows: Int, cols: Int, block: (Mat, Mat) -> T): T {
        require(rows > 0 && cols > 0) { "grid dimensions must be positive" }
        if (rows.toLong() * cols.toLong() <= MAX_CACHED_PIXELS) {
            if (cachedRows != rows || cachedCols != cols || cachedX == null || cachedY == null) {
                cachedX?.release()
                cachedY?.release()
                val x = Mat(rows, cols, CvType.CV_32F)
                val y = Mat(rows, cols, CvType.CV_32F)
                fill(x, y)
                cachedRows = rows
                cachedCols = cols
                cachedX = x
                cachedY = y
            }
            return block(cachedX!!, cachedY!!)
        }

        val x = Mat(rows, cols, CvType.CV_32F)
        val y = Mat(rows, cols, CvType.CV_32F)
        try {
            fill(x, y)
            return block(x, y)
        } finally {
            x.release()
            y.release()
        }
    }

    private fun fill(gridX: Mat, gridY: Mat) {
        val rows = gridX.rows()
        val cols = gridX.cols()
        val rowX = FloatArray(cols) { it.toFloat() }
        val rowY = FloatArray(cols)
        for (row in 0 until rows) {
            rowY.fill(row.toFloat())
            gridX.put(row, 0, rowX)
            gridY.put(row, 0, rowY)
        }
    }
}
