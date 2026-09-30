package com.veilframe.app.qr.model

import android.graphics.RectF
import com.veilframe.app.qr.QrStyle

/**
 * Maps logical QR matrix coordinates into output canvas pixel coordinates,
 * strictly incorporating the non-negotiable 4-module Quiet Zone.
 */
/**
 * Canonical resolved quiet zone margins in floating-point module units.
 */
data class ResolvedQuietZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val leftInt: Int get() = Math.round(left)
    val topInt: Int get() = Math.round(top)
    val rightInt: Int get() = Math.round(right)
    val bottomInt: Int get() = Math.round(bottom)
    val maxMargin: Float get() = maxOf(left, top, right, bottom)
    val maxMarginInt: Int get() = Math.round(maxMargin)
}

class QrGeometry(
    val matrixSize: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val quietZoneModules: Int = 4,
    val quietZoneLeft: Int = quietZoneModules,
    val quietZoneTop: Int = quietZoneModules,
    val quietZoneRight: Int = quietZoneModules,
    val quietZoneBottom: Int = quietZoneModules,
    val quietZoneLeftFloat: Float = quietZoneLeft.toFloat(),
    val quietZoneTopFloat: Float = quietZoneTop.toFloat(),
    val quietZoneRightFloat: Float = quietZoneRight.toFloat(),
    val quietZoneBottomFloat: Float = quietZoneBottom.toFloat()
) {
    val totalModulesXFloat: Float = matrixSize + quietZoneLeftFloat + quietZoneRightFloat
    val totalModulesYFloat: Float = matrixSize + quietZoneTopFloat + quietZoneBottomFloat
    val totalModulesX: Int = Math.round(totalModulesXFloat)
    val totalModulesY: Int = Math.round(totalModulesYFloat)
    val totalModules: Int = maxOf(totalModulesX, totalModulesY)
    val moduleWidth: Float = outputWidth.toFloat() / totalModulesXFloat
    val moduleHeight: Float = outputHeight.toFloat() / totalModulesYFloat
    val moduleSize: Float = minOf(moduleWidth, moduleHeight)

    // Offsets to center the QR matrix inside the canvas if non-square or if quiet zones differ
    val offsetX: Float = (outputWidth - (totalModulesXFloat * moduleSize)) / 2f + (quietZoneLeftFloat * moduleSize)
    val offsetY: Float = (outputHeight - (totalModulesYFloat * moduleSize)) / 2f + (quietZoneTopFloat * moduleSize)

    val contentWidth: Float get() = matrixSize * moduleSize
    val contentHeight: Float get() = matrixSize * moduleSize

    companion object {
        fun resolveDefaultQuietZone(style: QrStyle, defaultFallback: Int = 1): Int {
            return when (style) {
                QrStyle.D25 -> 0
                else -> defaultFallback
            }
        }

        /**
         * Resolves canonical quiet zone margins following the unified precedence:
         * 1. [BackdropStyle.fractionalQuietZone] (percentage of matrix size)
         * 2. [QrDesign.directionalQuietZone] (asymmetric float insets)
         * 3. [QrDesign.explicitQuietZone] (explicit integer override)
         * 4. [QrDesign.quietZoneModules] (design-level module count)
         * 5. Style default (e.g. D25 defaults to 0 unless explicitly configured)
         */
        fun resolveQuietZone(design: QrDesign, matrixSize: Int = 21): ResolvedQuietZone {
            val fracQz = design.backdropStyle.fractionalQuietZone
            if (fracQz != null) {
                return ResolvedQuietZone(
                    left = fracQz.left * matrixSize,
                    top = fracQz.top * matrixSize,
                    right = fracQz.right * matrixSize,
                    bottom = fracQz.bottom * matrixSize
                )
            }
            val dirQz = design.directionalQuietZone
            if (dirQz != null) {
                return ResolvedQuietZone(
                    left = dirQz.leftFloat,
                    top = dirQz.topFloat,
                    right = dirQz.rightFloat,
                    bottom = dirQz.bottomFloat
                )
            }
            val eqz = design.explicitQuietZone
            if (eqz != null) {
                val f = eqz.toFloat()
                return ResolvedQuietZone(f, f, f, f)
            }
            val fallback = resolveDefaultQuietZone(design.style, design.quietZoneModules).toFloat()
            return ResolvedQuietZone(fallback, fallback, fallback, fallback)
        }

        fun resolveQuietZone(design: QrDesign): Int {
            return resolveQuietZone(design, 21).maxMarginInt
        }

        fun fromDesign(
            matrixSize: Int,
            outputWidth: Int,
            outputHeight: Int,
            design: QrDesign,
            defaultQuietZone: Int? = null
        ): QrGeometry {
            val resolvedQz = if (defaultQuietZone != null &&
                design.explicitQuietZone == null &&
                design.backdropStyle.fractionalQuietZone == null &&
                design.directionalQuietZone == null
            ) {
                val f = defaultQuietZone.toFloat()
                ResolvedQuietZone(f, f, f, f)
            } else {
                resolveQuietZone(design, matrixSize)
            }

            return QrGeometry(
                matrixSize = matrixSize,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                quietZoneModules = resolvedQz.maxMarginInt,
                quietZoneLeft = resolvedQz.leftInt,
                quietZoneTop = resolvedQz.topInt,
                quietZoneRight = resolvedQz.rightInt,
                quietZoneBottom = resolvedQz.bottomInt,
                quietZoneLeftFloat = resolvedQz.left,
                quietZoneTopFloat = resolvedQz.top,
                quietZoneRightFloat = resolvedQz.right,
                quietZoneBottomFloat = resolvedQz.bottom
            )
        }
    }

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
