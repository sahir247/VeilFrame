package com.veilframe.app.cv.segmentation.rembg

/** Half-open original-raster crop, independent from Android bitmap allocation. */
internal data class CutoutBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun find(width: Int, height: Int, checkpoint: () -> Unit = {},
                 readRow: (Int, IntArray) -> Unit): CutoutBounds {
            require(width > 0 && height > 0)
            val row = IntArray(width)
            var left = width
            var right = -1
            var top = height
            var bottom = -1
            for (y in 0 until height) {
                checkpoint()
                readRow(y, row)
                for (x in row.indices) {
                    if (row[x] ushr 24 != 0) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                        top = minOf(top, y)
                        bottom = y
                    }
                }
            }
            // Match the existing preview trim contract for an all-empty cutout.
            return if (right < left || bottom < top) CutoutBounds(0, 0, width, height)
                else CutoutBounds(left, top, right - left + 1, bottom - top + 1)
        }
    }
}
