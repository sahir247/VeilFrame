package com.veilframe.app.qr.model

import android.graphics.RectF

/**
 * Maps logical QR matrix coordinates into output canvas pixel coordinates,
 * strictly incorporating the non-negotiable 4-module Quiet Zone.
 */
class QrGeometry(
    val matrixSize: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val quietZoneModules: Int = 4
) {
    val totalModules: Int = matrixSize + (2 * quietZoneModules)
    val moduleWidth: Float = outputWidth.toFloat() / totalModules
    val moduleHeight: Float = outputHeight.toFloat() / totalModules
    val moduleSize: Float = minOf(moduleWidth, moduleHeight)

    // Offsets to center the QR matrix inside the canvas if non-square
    val offsetX: Float = (outputWidth - (totalModules * moduleSize)) / 2f + (quietZoneModules * moduleSize)
    val offsetY: Float = (outputHeight - (totalModules * moduleSize)) / 2f + (quietZoneModules * moduleSize)

    val contentWidth: Float get() = matrixSize * moduleSize
    val contentHeight: Float get() = matrixSize * moduleSize

    /**
     * Returns the bounding rectangle in output pixels for the module at (col, row).
     */
    fun moduleRect(col: Int, row: Int, scale: Float = 1.0f): RectF {
        val left = offsetX + (col * moduleSize)
        val top = offsetY + (row * moduleSize)
        val right = left + moduleSize
        val bottom = top + moduleSize

        if (scale == 1.0f) {
            return RectF(left, top, right, bottom)
        }

        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val halfW = (moduleSize * scale) / 2f
        val halfH = (moduleSize * scale) / 2f
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /**
     * Returns the center pixel coordinates (cx, cy) of the module at (col, row).
     */
    fun moduleCenter(col: Int, row: Int): Pair<Float, Float> {
        val cx = offsetX + (col + 0.5f) * moduleSize
        val cy = offsetY + (row + 0.5f) * moduleSize
        return Pair(cx, cy)
    }

    /**
     * Returns the pixel bounding box for the entire data region (excluding quiet zone).
     */
    fun dataRegionBounds(): RectF {
        return RectF(
            offsetX,
            offsetY,
            offsetX + (matrixSize * moduleSize),
            offsetY + (matrixSize * moduleSize)
        )
    }

    /**
     * Returns the 7x7 finder pattern bounds in pixels for Top-Left (0), Bottom-Left (1), or Top-Right (2).
     */
    fun finderBounds(finderIndex: Int): RectF {
        val (startCol, startRow) = when (finderIndex) {
            0 -> Pair(0, 0)                   // Top-Left
            1 -> Pair(0, matrixSize - 7)       // Bottom-Left
            2 -> Pair(matrixSize - 7, 0)       // Top-Right
            else -> throw IllegalArgumentException("Finder index must be 0, 1, or 2")
        }
        val left = offsetX + (startCol * moduleSize)
        val top = offsetY + (startRow * moduleSize)
        return RectF(left, top, left + (7 * moduleSize), top + (7 * moduleSize))
    }

    /**
     * Computes the logo destination rectangle given a center scale fraction (e.g. 0.20 = 20% of QR size).
     */
    fun computeLogoRect(scaleFraction: Float): RectF {
        val qrPixelSize = matrixSize * moduleSize
        val logoPixelSize = (qrPixelSize * scaleFraction.coerceIn(0.05f, 0.33f))
        val cx = offsetX + (qrPixelSize / 2f)
        val cy = offsetY + (qrPixelSize / 2f)
        val half = logoPixelSize / 2f
        return RectF(cx - half, cy - half, cx + half, cy + half)
    }

    /**
     * Maps a canvas pixel rectangle to the range of affected matrix module coordinates (colMin..colMax, rowMin..rowMax).
     */
    fun mapPixelRectToModules(rect: RectF): Pair<IntRange, IntRange> {
        val colMin = (((rect.left - offsetX) / moduleSize).toInt()).coerceIn(0, matrixSize - 1)
        val colMax = (((rect.right - offsetX) / moduleSize).toInt()).coerceIn(0, matrixSize - 1)
        val rowMin = (((rect.top - offsetY) / moduleSize).toInt()).coerceIn(0, matrixSize - 1)
        val rowMax = (((rect.bottom - offsetY) / moduleSize).toInt()).coerceIn(0, matrixSize - 1)
        return Pair(colMin..colMax, rowMin..rowMax)
    }
}
