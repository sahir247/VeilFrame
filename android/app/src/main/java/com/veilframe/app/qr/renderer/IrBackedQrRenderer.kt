package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

/**
 * Contract for renderers whose visual output is completely defined by a unified [QrGeometryIr].
 *
 * Guarantees that Android Canvas rasterization and SVG vector emission execute
 * from the exact same mathematical geometry definitions (Audit Item H-05).
 */
interface IrBackedQrRenderer : QrRenderer {

    /**
     * Generates the authoritative [QrGeometryIr] intermediate representation for [matrix], [design], and [geometry].
     */
    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr

    /**
     * Whether the emitted IR contains the complete document backdrop (including margin quiet zone).
     *
     * When true, higher-level callers such as [com.veilframe.app.qr.QrGenerator] will not
     * render an extra backdrop pass to the Canvas.
     */
    val ownsBackdrop: Boolean
        get() = false
}
