package com.veilframe.app.qr.model

import android.graphics.Path
import android.graphics.RectF

/**
 * Geometric shape generators translating abstract module geometries into
 * visual [Path] instances for renderers and vector SVG exporters.
 */
object QrVisualGeometry {

    /**
     * Constructs a squircle (smooth rounded rectangle using EFQRCode's normalized sq25 bezier curve).
     */
    fun createSquirclePath(rect: RectF, path: Path = Path()): Path {
        path.reset()
        val w = rect.width()
        val h = rect.height()
        val ox = rect.left
        val oy = rect.top

        // Helper to convert normalized 0..100 coordinate to canvas pixels
        fun px(x: Double): Float = (ox + (x / 100.0) * w).toFloat()
        fun py(y: Double): Float = (oy + (y / 100.0) * h).toFloat()

        path.moveTo(px(32.048565), py(0.0))
        path.lineTo(px(67.951435), py(0.0))
        path.cubicTo(px(79.0954192), py(0.0), px(83.1364972), py(1.16032014), px(87.2105713), py(3.3391588))
        path.cubicTo(px(91.2846454), py(5.51799746), px(94.4820025), py(8.71535463), px(96.6608412), py(12.7894287))
        path.cubicTo(px(98.8396799), py(16.8635028), px(100.0), py(20.9045808), px(100.0), py(32.048565))

        path.lineTo(px(100.0), py(67.951435))
        path.cubicTo(px(100.0), py(79.0954192), px(98.8396799), py(83.1364972), px(96.6608412), py(87.2105713))
        path.cubicTo(px(94.4820025), py(91.2846454), px(91.2846454), py(94.4820025), px(87.2105713), py(96.6608412))
        path.cubicTo(px(83.1364972), py(98.8396799), px(79.0954192), py(100.0), px(67.951435), py(100.0))

        path.lineTo(px(32.048565), py(100.0))
        path.cubicTo(px(20.9045808), py(100.0), px(16.8635028), py(98.8396799), px(12.7894287), py(96.6608412))
        path.cubicTo(px(8.71535463), py(94.4820025), px(5.51799746), py(91.2846454), px(3.3391588), py(87.2105713))
        path.cubicTo(px(1.16032014), py(83.1364972), px(0.0), py(79.0954192), px(0.0), py(67.951435))

        path.lineTo(px(0.0), py(32.048565))
        path.cubicTo(px(0.0), py(20.9045808), px(1.16032014), py(16.8635028), px(3.3391588), py(12.7894287))
        path.cubicTo(px(5.51799746), py(8.71535463), px(8.71535463), py(5.51799746), px(12.7894287), py(3.3391588))
        path.cubicTo(px(16.8635028), py(1.16032014), px(20.9045808), py(0.0), px(32.048565), py(0.0))

        path.close()
        return path
    }

    /**
     * Constructs a diamond / rotated square path.
     */
    fun createDiamondPath(rect: RectF, path: Path = Path()): Path {
        path.reset()
        val cx = rect.centerX()
        val cy = rect.centerY()
        path.moveTo(cx, rect.top)
        path.lineTo(rect.right, cy)
        path.lineTo(cx, rect.bottom)
        path.lineTo(rect.left, cy)
        path.close()
        return path
    }

    /**
     * Constructs a hexagon path fitting the given rectangle.
     */
    fun createHexagonPath(rect: RectF, path: Path = Path()): Path {
        path.reset()
        val w = rect.width()
        val h = rect.height()
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom

        path.moveTo(l + (0.25f * w), t)
        path.lineTo(l + (0.75f * w), t)
        path.lineTo(r, t + (0.5f * h))
        path.lineTo(l + (0.75f * w), b)
        path.lineTo(l + (0.25f * w), b)
        path.lineTo(l, t + (0.5f * h))
        path.close()
        return path
    }

    /**
     * Constructs a rounded rectangle with independent corner radii.
     * radii is an 8-float array: [topLeftX, topLeftY, topRightX, topRightY, bottomRightX, bottomRightY, bottomLeftX, bottomLeftY].
     */
    fun createCustomRoundedRect(rect: RectF, radii: FloatArray, path: Path = Path()): Path {
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        return path
    }

    /**
     * Computes the 3 polygonal faces of an isometric 2.5D column.
     *
     * Matrix projection:
     *   isoX = (col - row) * cos(30°)
     *   isoY = (col + row) * sin(30°) - height
     */
    data class Faces25D(val top: Path, val left: Path, val right: Path)

    fun create25DFaces(
        baseIsoX: Float,
        baseIsoY: Float,
        sideX: Float,
        sideY: Float,
        columnHeight: Float,
        topPath: Path = Path(),
        leftPath: Path = Path(),
        rightPath: Path = Path()
    ): Faces25D {
        topPath.reset()
        leftPath.reset()
        rightPath.reset()

        // 4 vertices of the top face (raised by columnHeight)
        val t0x = baseIsoX
        val t0y = baseIsoY - columnHeight

        val t1x = baseIsoX + sideX
        val t1y = baseIsoY + sideY - columnHeight

        val t2x = baseIsoX
        val t2y = baseIsoY + (2 * sideY) - columnHeight

        val t3x = baseIsoX - sideX
        val t3y = baseIsoY + sideY - columnHeight

        // Top Face (Diamond)
        topPath.moveTo(t0x, t0y)
        topPath.lineTo(t1x, t1y)
        topPath.lineTo(t2x, t2y)
        topPath.lineTo(t3x, t3y)
        topPath.close()

        // Left Face
        leftPath.moveTo(t3x, t3y)
        leftPath.lineTo(t2x, t2y)
        leftPath.lineTo(t2x, t2y + columnHeight)
        leftPath.lineTo(t3x, t3y + columnHeight)
        leftPath.close()

        // Right Face
        rightPath.moveTo(t2x, t2y)
        rightPath.lineTo(t1x, t1y)
        rightPath.lineTo(t1x, t1y + columnHeight)
        rightPath.lineTo(t2x, t2y + columnHeight)
        rightPath.close()

        return Faces25D(topPath, leftPath, rightPath)
    }

    /**
     * Constructs a 5-pointed star path fitting the given rectangle.
     */
    fun createStarPath(rect: RectF, path: Path = Path()): Path {
        path.reset()
        val cx = rect.centerX()
        val cy = rect.centerY()
        val outerR = minOf(rect.width(), rect.height()) / 2f
        val innerR = outerR * 0.45f

        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = -Math.PI / 2.0 + (i * Math.PI / 5.0)
            val x = (cx + r * Math.cos(angle)).toFloat()
            val y = (cy + r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /**
     * Constructs an organic bubble path with soft pill/squircle rounding.
     */
    fun createBubblePath(rect: RectF, path: Path = Path()): Path {
        path.reset()
        val rx = rect.width() * 0.42f
        val ry = rect.height() * 0.42f
        path.addRoundRect(rect, rx, ry, Path.Direction.CW)
        return path
    }

    /**
     * Constructs an organic connected blob path that smoothly merges with adjacent modules
     * based on its [ModuleNeighborhood].
     */
    fun createOrganicBlobPath(rect: RectF, neighbors: ModuleNeighborhood, path: Path = Path()): Path {
        path.reset()
        val w = rect.width()
        val h = rect.height()
        val r = minOf(w, h) * 0.45f

        // Independent corner radii based on adjacent connections:
        // A corner is rounded if NEITHER of its orthogonal edges has a neighbor.
        val rTL = if (!neighbors.up && !neighbors.left) r else 0f
        val rTR = if (!neighbors.up && !neighbors.right) r else 0f
        val rBR = if (!neighbors.down && !neighbors.right) r else 0f
        val rBL = if (!neighbors.down && !neighbors.left) r else 0f

        val radii = floatArrayOf(
            rTL, rTL,
            rTR, rTR,
            rBR, rBR,
            rBL, rBL
        )
        path.addRoundRect(rect, radii, Path.Direction.CW)
        return path
    }

    /**
     * Constructs a directional line module path with optional round end-caps.
     */
    fun createLinePath(
        rect: RectF,
        direction: LineDirection,
        thicknessFraction: Float = 0.5f,
        roundCaps: Boolean = true,
        path: Path = Path()
    ): Path {
        path.reset()
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width()
        val h = rect.height()
        val halfT = (minOf(w, h) * thicknessFraction.coerceIn(0.1f, 1.0f)) / 2f
        val radius = if (roundCaps) halfT else 0f

        when (direction) {
            LineDirection.HORIZONTAL -> {
                val lineRect = RectF(rect.left, cy - halfT, rect.right, cy + halfT)
                path.addRoundRect(lineRect, radius, radius, Path.Direction.CW)
            }
            LineDirection.VERTICAL -> {
                val lineRect = RectF(cx - halfT, rect.top, cx + halfT, rect.bottom)
                path.addRoundRect(lineRect, radius, radius, Path.Direction.CW)
            }
            LineDirection.CROSS -> {
                val hRect = RectF(rect.left, cy - halfT, rect.right, cy + halfT)
                val vRect = RectF(cx - halfT, rect.top, cx + halfT, rect.bottom)
                path.addRoundRect(hRect, radius, radius, Path.Direction.CW)
                path.addRoundRect(vRect, radius, radius, Path.Direction.CW)
            }
            LineDirection.X -> {
                // Diagonal cross path
                val diagR = halfT * 0.8f
                // First diagonal (\)
                path.moveTo(rect.left, rect.top + halfT)
                path.lineTo(rect.left + halfT, rect.top)
                path.lineTo(rect.right, rect.bottom - halfT)
                path.lineTo(rect.right - halfT, rect.bottom)
                path.close()
                // Second diagonal (/)
                val path2 = Path()
                path2.moveTo(rect.right - halfT, rect.top)
                path2.lineTo(rect.right, rect.top + halfT)
                path2.lineTo(rect.left + halfT, rect.bottom)
                path2.lineTo(rect.left, rect.bottom - halfT)
                path2.close()
                path.op(path2, Path.Op.UNION)
            }
            LineDirection.DIAGONAL_FORWARD -> {
                // Forward diagonal (/)
                path.moveTo(rect.right - halfT, rect.top)
                path.lineTo(rect.right, rect.top + halfT)
                path.lineTo(rect.left + halfT, rect.bottom)
                path.lineTo(rect.left, rect.bottom - halfT)
                path.close()
            }
            LineDirection.DIAGONAL_BACKWARD -> {
                // Backward diagonal (\)
                path.moveTo(rect.left, rect.top + halfT)
                path.lineTo(rect.left + halfT, rect.top)
                path.lineTo(rect.right, rect.bottom - halfT)
                path.lineTo(rect.right - halfT, rect.bottom)
                path.close()
            }
            LineDirection.LOOP -> {
                val oval = RectF(cx - halfT * 1.5f, cy - halfT * 1.5f, cx + halfT * 1.5f, cy + halfT * 1.5f)
                path.addOval(oval, Path.Direction.CW)
            }
        }
        return path
    }

    /**
     * Constructs a composite DSJ geometry consisting of a central cross and an X.
     */
    fun createCompositeDSJ(
        rect: RectF,
        lineThickness: Float = 0.22f,
        crossScale: Float = 0.85f,
        path: Path = Path()
    ): Path {
        path.reset()
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width() * crossScale
        val h = rect.height() * crossScale
        val halfW = w / 2f
        val halfH = h / 2f
        val halfT = (minOf(w, h) * lineThickness) / 2f

        // Horizontal bar
        path.addRect(cx - halfW, cy - halfT, cx + halfW, cy + halfT, Path.Direction.CW)
        // Vertical bar
        path.addRect(cx - halfT, cy - halfH, cx + halfT, cy + halfH, Path.Direction.CW)

        // Center dot
        val centerSize = halfT * 1.8f
        path.addRect(cx - centerSize, cy - centerSize, cx + centerSize, cy + centerSize, Path.Direction.CW)
        return path
    }

    /**
     * Constructs grid-aligned 2.5D extruded isometric faces.
     *
     * The top face aligns exactly to the QR module grid footprint to guarantee 100% scanability,
     * while the extruded side and bottom faces provide isometric depth.
     */
    fun createGridAligned25DFaces(
        rect: RectF,
        depth: Float,
        angleDegrees: Float = 45f,
        topPath: Path = Path(),
        leftPath: Path = Path(),
        rightPath: Path = Path()
    ): Faces25D {
        topPath.reset()
        leftPath.reset()
        rightPath.reset()

        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = (depth * rect.width() * 0.4f * Math.cos(radians)).toFloat()
        val dy = (depth * rect.height() * 0.4f * Math.sin(radians)).toFloat()

        // Top face is exactly aligned with the QR module grid
        topPath.addRect(rect, Path.Direction.CW)

        // Extruded Left / Bottom-Left face
        leftPath.moveTo(rect.left, rect.bottom)
        leftPath.lineTo(rect.left + dx, rect.bottom + dy)
        leftPath.lineTo(rect.right + dx, rect.bottom + dy)
        leftPath.lineTo(rect.right, rect.bottom)
        leftPath.close()

        // Extruded Right face
        rightPath.moveTo(rect.right, rect.top)
        rightPath.lineTo(rect.right + dx, rect.top + dy)
        rightPath.lineTo(rect.right + dx, rect.bottom + dy)
        rightPath.lineTo(rect.right, rect.bottom)
        rightPath.close()

        return Faces25D(topPath, leftPath, rightPath)
    }
}
