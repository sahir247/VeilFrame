package com.veilframe.app.qr.renderer

import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrModule

/**
 * Visual Grammar Shape Engine.
 *
 * Delegates to [ShapeGeometry] to translate discrete [QrModule] instances
 * into resolution-independent [Path] geometry.
 */
object ShapeEngine {

    /**
     * Builds the geometric path for a given module into [outPath].
     */
    fun buildModulePath(
        module: QrModule,
        rect: RectF,
        design: QrDesign,
        outPath: Path = Path()
    ): Path {
        return ShapeGeometry.buildCanvasPath(
            shape = design.moduleStyle.shape,
            module = module,
            rect = rect,
            design = design,
            outPath = outPath
        )
    }
}

