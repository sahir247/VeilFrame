package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import android.graphics.RectF
import com.veilframe.app.qr.model.FunctionPatternType

data class ModuleGeometry(
    val col: Int,
    val row: Int,
    val rect: RectF,
    val isDark: Boolean,
    val type: FunctionPatternType,
    val canvas: Canvas
)

/**
 * Base contract for atomic module rendering in the Visual Grammar Engine.
 */
interface ModuleRenderer {
    fun render(module: ModuleGeometry, context: RenderContext)
}
