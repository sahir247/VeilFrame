package com.veilframe.app.qr.renderer

import android.graphics.Canvas
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Style 2 — BUBBLE (Visual Grammar: Organic / Bubble Cluster)
 *
 * Implements VeilFrameStyleBubble with dual mode behavior:
 * - SAFE mode: protects timing, alignment, and finders separately.
 * - ARTISTIC_ENGINE mode (when matrix.typeTable != null): only reserves finders,
 *   allowing timing/alignment to cluster into macro bubbles matching VeilFrameStyleBubble.swift.
 *
 * Clustering grammar:
 * 1. 3x3 cross clusters -> large central bubble with stroke and core dot.
 * 2. 2x2 all-dark corners -> radius sqrt(1/2) circle inscribed at intersection.
 * 3. 1x2 vertical / 2x1 horizontal pairs -> rounded capsule bubbles.
 * 4. Remaining isolated dark cells -> single circles.
 * 5. Position finders via canonical VeilFrame position geometry [VeilPositionPatternGeometry].
 */
class BubbleRenderer : QrRenderer {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val cs = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val fgColor = design.palette.foreground
        val bgColor = design.palette.background
        val rng = Random(design.effects.seed)

        val nodes = mutableListOf<QrGeometryNode>()
        if (geometry.outputWidth > 0 && geometry.outputHeight > 0) {
            nodes.add(
                RectNode(
                    x = 0f,
                    y = 0f,
                    width = geometry.outputWidth.toFloat(),
                    height = geometry.outputHeight.toFloat(),
                    fill = bgColor
                )
            )
        }

        // 1. Position finders via canonical VeilFrame position geometry
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(n - 4, 3),
            Pair(3, n - 4)
        )
        for ((fx, fy) in finderCenters) {
            nodes.addAll(
                VeilPositionPatternGeometry.toIrNodes(
                    x = fx,
                    y = fy,
                    moduleSize = cs,
                    offsetX = ox,
                    offsetY = oy,
                    style = design.eyeStyle.style,
                    size = design.positionSize,
                    color = design.eyeStyle.outerColor ?: fgColor
                )
            )
        }

        // 2. Setup availability matrices
        val avail = Array(n) { BooleanArray(n) { true } }
        val avail2 = Array(n) { BooleanArray(n) { true } }

        val isEfMode = matrix.typeTable != null

        if (isEfMode) {
            // Artistic mode: Only finders are excluded from clustering
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (VeilPositionPatternGeometry.isFinderArea(col, row, n)) {
                        avail[col][row] = false
                        avail2[col][row] = false
                    }
                }
            }
        } else {
            // SAFE mode: Reserve protected function patterns
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.functionMask.isProtected(col, row)) {
                        avail[col][row] = false
                        avail2[col][row] = false
                        // Draw timing and alignment patterns as clean rounded bubbles
                        if (matrix.isDark(col, row) && !matrix.functionMask.isFinder(col, row) && !matrix.functionMask.isSeparator(col, row)) {
                            val (cx, cy) = geometry.moduleCenter(col, row)
                            nodes.add(
                                CircleNode(
                                    cx = cx,
                                    cy = cy,
                                    radius = cs * 0.42f,
                                    fill = fgColor
                                )
                            )
                        }
                    }
                }
            }
        }

        val outlineColor = fgColor
        val centerColor = bgColor

        // 3. Scan for 3x3 Cross Bubbles
        for (col in 0 until n - 2) {
            for (row in 0 until n - 2) {
                if (avail[col][row] && avail2[col][row]) {
                    var allAvail2 = true
                    for (di in 0..2) {
                        for (dj in 0..2) {
                            if (!avail2[col + di][row + dj]) allAvail2 = false
                        }
                    }

                    if (allAvail2 &&
                        matrix.isDark(col + 1, row) && matrix.isDark(col + 1, row + 2) &&
                        matrix.isDark(col, row + 1) && matrix.isDark(col + 2, row + 1)
                    ) {
                        val (cx, cy) = geometry.moduleCenter(col + 1, row + 1)
                        val sw = (0.35f + rng.nextFloat() * 0.15f) * cs

                        nodes.add(CircleNode(cx = cx, cy = cy, radius = cs * 1.0f, fill = centerColor))
                        nodes.add(CircleNode(cx = cx, cy = cy, radius = cs * 1.0f, stroke = outlineColor, strokeWidth = sw))

                        if (matrix.isDark(col + 1, row + 1)) {
                            val r2 = (0.28f + rng.nextFloat() * 0.20f) * cs
                            nodes.add(CircleNode(cx = cx, cy = cy, radius = r2, fill = outlineColor))
                        }

                        for (di in 0..2) {
                            for (dj in 0..2) {
                                avail[col + di][row + dj] = false
                                avail2[col + di][row + dj] = false
                            }
                        }
                    }
                }
            }
        }

        // 4. Scan for 2x2 Square Bubbles
        for (col in 0 until n - 1) {
            for (row in 0 until n - 1) {
                if (avail[col][row] &&
                    matrix.isDark(col, row) && matrix.isDark(col + 1, row) &&
                    matrix.isDark(col, row + 1) && matrix.isDark(col + 1, row + 1)
                ) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (cx2, cy2) = geometry.moduleCenter(col + 1, row + 1)
                    val midX = (cx1 + cx2) / 2f
                    val midY = (cy1 + cy2) / 2f
                    val sw = (0.33f + rng.nextFloat() * 0.15f) * cs

                    nodes.add(CircleNode(cx = midX, cy = midY, radius = cs * sqrt(0.5f), fill = centerColor))
                    nodes.add(CircleNode(cx = midX, cy = midY, radius = cs * sqrt(0.5f), stroke = outlineColor, strokeWidth = sw))

                    for (di in 0..1) {
                        for (dj in 0..1) {
                            avail[col + di][row + dj] = false
                            avail2[col + di][row + dj] = false
                        }
                    }
                }
            }
        }

        // 5. Scan for Pairs (Vertical & Horizontal)
        for (col in 0 until n) {
            for (row in 0 until n) {
                // Vertical pair
                if (avail[col][row] && row < n - 1 && matrix.isDark(col, row) && matrix.isDark(col, row + 1)) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (_, cy2) = geometry.moduleCenter(col, row + 1)
                    val midY = (cy1 + cy2) / 2f
                    val sw = (0.35f + rng.nextFloat() * 0.1f) * cs

                    nodes.add(CircleNode(cx = cx1, cy = midY, radius = cs * 0.48f, fill = centerColor))
                    nodes.add(CircleNode(cx = cx1, cy = midY, radius = cs * 0.48f, stroke = outlineColor, strokeWidth = sw))

                    avail[col][row] = false
                    avail[col][row + 1] = false
                }

                // Horizontal pair
                if (avail[col][row] && col < n - 1 && matrix.isDark(col, row) && matrix.isDark(col + 1, row)) {
                    val (cx1, cy1) = geometry.moduleCenter(col, row)
                    val (cx2, _) = geometry.moduleCenter(col + 1, row)
                    val midX = (cx1 + cx2) / 2f
                    val sw = (0.35f + rng.nextFloat() * 0.1f) * cs

                    nodes.add(CircleNode(cx = midX, cy = cy1, radius = cs * 0.48f, fill = centerColor))
                    nodes.add(CircleNode(cx = midX, cy = cy1, radius = cs * 0.48f, stroke = outlineColor, strokeWidth = sw))

                    avail[col][row] = false
                    avail[col + 1][row] = false
                }

                // 6. Remaining Single Isolated Dark Modules & Ambient Light Modules
                if (avail[col][row]) {
                    if (matrix.isDark(col, row)) {
                        val (cx, cy) = geometry.moduleCenter(col, row)
                        val r = (0.30f + rng.nextFloat() * 0.15f) * cs
                        nodes.add(CircleNode(cx = cx, cy = cy, radius = r, fill = outlineColor))
                        avail[col][row] = false
                    } else if (matrix.roleAt(col, row) == QrModuleRole.DATA && design.clusterStyle.ambientBubbles) {
                        if (rng.nextFloat() < design.clusterStyle.ambientDensity) {
                            val (cx, cy) = geometry.moduleCenter(col, row)
                            val r = 0.5f * (0.85f + rng.nextFloat() * 0.45f) * cs
                            val sw = (0.15f + rng.nextFloat() * 0.18f) * cs
                            nodes.add(CircleNode(cx = cx, cy = cy, radius = r, fill = centerColor, stroke = outlineColor, strokeWidth = sw))
                            avail[col][row] = false
                        }
                    }
                }
            }
        }

        return QrGeometryIr(
            width = geometry.outputWidth.toFloat(),
            height = geometry.outputHeight.toFloat(),
            rootNodes = nodes
        )
    }

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val ir = generateGeometry(matrix, design, geometry)
        IrCanvasRenderer.render(ir, canvas)
        drawLogo(canvas, design, geometry, context)
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
