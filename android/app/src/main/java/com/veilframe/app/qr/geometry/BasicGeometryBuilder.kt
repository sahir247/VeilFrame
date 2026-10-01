package com.veilframe.app.qr.geometry

import android.graphics.Color
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry

/**
 * Builds a unified [QrGeometryIr] representation for [com.veilframe.app.qr.QrStyle.BASIC].
 *
 * Provides a single mathematical source of truth for geometric QR code rendering
 * across Android Canvas rasterization and SVG vector emission (P3.2 / H-05).
 */
object BasicGeometryBuilder {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val nCount = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val nodes = mutableListOf<QrGeometryNode>()
        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()

        // 1. Background node if geometry has non-zero size
        if (geometry.outputWidthFloat > 0f && geometry.outputHeightFloat > 0f) {
            val bgColor = design.backdropStyle.color ?: design.palette.background
            if (bgColor != Color.TRANSPARENT) {
                nodes.add(
                    RectNode(
                        x = 0f,
                        y = 0f,
                        width = geometry.outputWidthFloat,
                        height = geometry.outputHeightFloat,
                        fill = bgColor
                    )
                )
            }
        }

        // 2. Finder patterns via canonical position pattern geometry
        val posColor = design.eyeStyle.outerColor ?: design.palette.foreground
        val posStyle = design.eyeStyle.style
        val posSize = design.positionSize
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(nCount - 4, 3),
            Pair(3, nCount - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = posStyle,
                    size = posSize,
                    color = posColor
                )
            )
        }

        // 3. Data & Function modules
        val scale = design.moduleStyle.scale.coerceIn(0.5f, 1.0f)
        val shape = design.moduleStyle.shape
        val fgColor = design.palette.foreground

        val timingColor = design.timingStyle.color ?: design.timingColor ?: fgColor
        val alignColor = design.alignmentStyle.color ?: design.alignmentColor ?: fgColor
        val timingScale = design.timingStyle.scale.coerceIn(0.5f, 1.0f)
        val alignScale = design.alignmentStyle.scale.coerceIn(0.5f, 1.0f)

        for (col in 0 until nCount) {
            for (row in 0 until nCount) {
                if (!matrix.isDark(col, row)) continue
                if (VeilPositionPatternGeometry.isFinderArea(col, row, nCount)) continue

                val role = matrix.roleAt(col, row)
                val (modScale, modColor) = when {
                    role == QrModuleRole.TIMING -> {
                        if (design.timingStyle.shape == ModuleShape.NONE || design.timingStyle.onlyWhite) continue
                        Pair(timingScale, timingColor)
                    }
                    role == QrModuleRole.ALIGNMENT_CENTER || role == QrModuleRole.ALIGNMENT_BORDER -> {
                        if (design.alignmentStyle.shape == ModuleShape.NONE || design.alignmentStyle.onlyWhite) continue
                        Pair(alignScale, alignColor)
                    }
                    else -> Pair(scale, fgColor)
                }

                val rect = geometry.moduleRect(col, row, modScale)
                when (shape) {
                    ModuleShape.CIRCLE, ModuleShape.DOT -> {
                        val r = (minOf(rect.width(), rect.height()) / 2f) * (if (shape == ModuleShape.DOT) 0.75f else 1.0f)
                        nodes.add(CircleNode(cx = rect.centerX(), cy = rect.centerY(), radius = r, fill = modColor))
                    }
                    ModuleShape.ROUNDED -> {
                        val rx = rect.width() * design.moduleStyle.cornerRadiusFraction.coerceIn(0.1f, 0.5f)
                        nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width(), height = rect.height(), rx = rx, ry = rx, fill = modColor))
                    }
                    ModuleShape.PILL -> {
                        val rx = rect.width() / 2f
                        val ry = rect.height() * 0.25f
                        nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width(), height = rect.height(), rx = rx, ry = ry, fill = modColor))
                    }
                    else -> {
                        // Square and default shapes
                        nodes.add(RectNode(x = rect.left, y = rect.top, width = rect.width(), height = rect.height(), fill = modColor))
                    }
                }
            }
        }

        // 4. Logo Node if present
        if (design.logo?.effectiveBitmap != null && !design.logo.effectiveBitmap!!.isRecycled) {
            VeilIconPipeline.appendIconNodes(
                nodes = nodes,
                defs = defs,
                design = design,
                ox = ox,
                oy = oy,
                qrPixelSize = nCount * cs,
                masks = masks
            )
        }

        return QrGeometryIr(
            width = geometry.outputWidthFloat,
            height = geometry.outputHeightFloat,
            defs = defs,
            masks = masks,
            rootNodes = nodes
        )
    }
}
