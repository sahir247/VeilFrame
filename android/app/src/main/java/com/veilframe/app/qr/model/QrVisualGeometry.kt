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
}
