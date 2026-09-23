package com.veilframe.app.qr.renderer

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Encapsulates thread-local drawing resources (Paint, Path, RectF, Matrix)
 * allocated once per render operation.
 *
 * Prevents object allocation churn and garbage collection pauses inside
 * high-frequency pixel/module rendering loops.
 */
class RenderContext {

    val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    val tempPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val tempRectF: RectF = RectF()
    val tempRectF1: RectF = RectF()
    val tempRectF2: RectF = RectF()
    val tempPath1: Path = Path()
    val tempPath2: Path = Path()
    val tempPath3: Path = Path()
    val tempPath4: Path = Path()
    val tempMatrix: Matrix = Matrix()

    val cornerRadiiBuffer: FloatArray = FloatArray(8)

    /**
     * Resets and configures [fillPaint] with the specified [color].
     */
    fun obtainFill(color: Int): Paint {
        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = color
        return fillPaint
    }

    /**
     * Resets and configures [strokePaint] with [color] and [strokeWidth].
     */
    fun obtainStroke(color: Int, strokeWidth: Float, cap: Paint.Cap = Paint.Cap.ROUND): Paint {
        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidth
        strokePaint.strokeCap = cap
        return strokePaint
    }

    /**
     * Clears cached paths and matrices.
     */
    fun reset() {
        tempPath1.reset()
        tempPath2.reset()
        tempPath3.reset()
        tempPath4.reset()
        tempMatrix.reset()
        tempRectF.setEmpty()
        tempRectF1.setEmpty()
        tempRectF2.setEmpty()
    }
}
