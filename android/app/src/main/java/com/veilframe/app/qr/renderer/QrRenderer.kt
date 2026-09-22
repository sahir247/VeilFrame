package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.qr.QrMatrix
import com.veilframe.app.qr.QrStyleParams

/**
 * Base contract for all 11 QR rendering styles.
 *
 * Each renderer receives:
 *  - [matrix]: the classified module grid
 *  - [params]: all visual parameters
 *  - [cellSize]: the float pixel size of each QR module cell
 *
 * The renderer draws onto a [Canvas] and may also draw a center logo
 * if [params.logo] is non-null.
 */
interface QrRenderer {
    fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float)
}

// ---------------------------------------------------------------------------
// Shared helpers available to all renderers
// ---------------------------------------------------------------------------

internal val Paint.fillPaint: Paint get() = apply { style = Paint.Style.FILL }
internal val Paint.strokePaint: Paint get() = apply { style = Paint.Style.STROKE }

/**
 * Returns a new anti-aliased fill [Paint] with the given [color].
 */
internal fun solidPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

/**
 * Draws the center logo if [params.logo] is non-null.
 * Clips the logo inside a rounded-square region to avoid covering scanner-critical finder bits.
 */
internal fun drawLogo(canvas: Canvas, params: QrStyleParams, outputSize: Int) {
    val logo = params.logo ?: return
    val logoSize = (outputSize * params.logoFraction).coerceIn(1f, outputSize * 0.33f)
    val left = (outputSize - logoSize) / 2f
    val top  = (outputSize - logoSize) / 2f
    val dst = RectF(left, top, left + logoSize, top + logoSize)
    // White backing square so logo is readable on any background
    val pad = logoSize * 0.08f
    val bgPaint = solidPaint(0xFFFFFFFF.toInt())
    canvas.drawRoundRect(
        RectF(dst.left - pad, dst.top - pad, dst.right + pad, dst.bottom + pad),
        pad * 2f, pad * 2f, bgPaint
    )
    canvas.drawBitmap(logo, null, dst, null)
}
