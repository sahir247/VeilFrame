package com.veilframe.app.qr.geometry

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF

/**
 * Backend-neutral paint fill definition (Solid, LinearGradient, RadialGradient).
 * Bridges Canvas Shader allocation with SVG gradient element references.
 */
sealed interface GeometryFill {
    data class Solid(val color: Int) : GeometryFill
    data class LinearGradient(
        val startColor: Int,
        val endColor: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float
    ) : GeometryFill
    data class RadialGradient(
        val centerColor: Int,
        val edgeColor: Int,
        val cx: Float,
        val cy: Float,
        val radius: Float
    ) : GeometryFill
}

/**
 * Unified Geometry Intermediate Representation (IR) node.
 * Single source of truth for both Canvas rasterization and SVG vector emission.
 */
sealed interface QrGeometryNode

data class RectNode(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val fill: Int? = null,
    val fillString: String? = null,
    val geometryFill: GeometryFill? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val strokeWidthString: String? = null,
    val opacity: Float = 1f,
    val opacityString: String? = null,
    val alwaysEmitOpacity: Boolean = false,
    val transform: String? = null
) : QrGeometryNode

data class CircleNode(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val fill: Int? = null,
    val fillString: String? = null,
    val geometryFill: GeometryFill? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val strokeWidthString: String? = null,
    val opacity: Float = 1f,
    val strokeDashArray: String? = null
) : QrGeometryNode

data class LineNode(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val strokeColor: Int,
    val strokeWidth: Float,
    val isRoundCap: Boolean = true,
    val strokeDashArray: String? = null
) : QrGeometryNode

data class PolygonNode(
    val points: String,
    val pointsList: List<Pair<Float, Float>> = emptyList(),
    val fill: Int? = null,
    val fillString: String? = null,
    val geometryFill: GeometryFill? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val opacity: Float = 1f,
    val transform: String? = null
) : QrGeometryNode

data class PathNode(
    val svgPathData: String,
    val androidPath: Path? = null,
    val fill: Int? = null,
    val fillString: String? = null,
    val geometryFill: GeometryFill? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val strokeWidthString: String? = null,
    val canvasStrokeWidth: Float = strokeWidth,
    val opacity: Float = 1f,
    val transform: String? = null
) : QrGeometryNode

data class AnimatedGroupNode(
    val framePrefix: String = "resfm",
    val frameNodes: List<List<QrGeometryNode>>,
    val frameDelaysMs: List<Int>
) : QrGeometryNode

data class ImageNode(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val bitmap: Bitmap? = null,
    val base64Data: String? = null,
    val opacity: Float = 1f,
    val preserveAspectRatio: String = "xMidYMid slice",
    val maskId: String? = null,
    val clipPathId: String? = null,
    val clipOutRects: List<RectF> = emptyList(),
    val clipPath: Path? = null,
    val transform: String? = null,
    val style: String? = null,
    val key: String? = null
) : QrGeometryNode

data class AnimatedImageNode(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val frames: List<Bitmap> = emptyList(),
    val base64Frames: List<String> = emptyList(),
    val frameDelaysMs: List<Int> = emptyList(),
    val opacity: Float = 1f,
    val preserveAspectRatio: String = "xMidYMid slice",
    val maskId: String? = null,
    val clipPathId: String? = null,
    val clipOutRects: List<RectF> = emptyList(),
    val clipPath: Path? = null,
    val transform: String? = null,
    val style: String? = null,
    val framePrefix: String = "1fm"
) : QrGeometryNode

data class GroupNode(
    val children: List<QrGeometryNode>,
    val maskId: String? = null,
    val clipPathId: String? = null,
    val clipPath: Path? = null,
    val clipOutRects: List<RectF> = emptyList(),
    val opacity: Float = 1f,
    val transform: String? = null
) : QrGeometryNode

/**
 * Backend-neutral mask and clipping definition.
 * Holds canonical vector paths and bounding exclusion rectangles for Canvas and SVG parity.
 */
data class QrMaskDefinition(
    val id: String,
    val clipPath: Path? = null,
    val clipOutRects: List<RectF> = emptyList()
)

/**
 * Complete document-level geometry definition for a QR code.
 */
data class QrGeometryIr(
    val width: Float,
    val height: Float,
    val viewBox: String = defaultViewBox(width, height),
    val defs: List<String> = emptyList(),
    val masks: Map<String, QrMaskDefinition> = emptyMap(),
    val rootNodes: List<QrGeometryNode> = emptyList()
) {
    companion object {
        fun defaultViewBox(w: Float, h: Float): String {
            val wStr = if (w % 1f == 0f) w.toInt().toString() else String.format(java.util.Locale.US, "%.4f", w).trimEnd('0').trimEnd('.')
            val hStr = if (h % 1f == 0f) h.toInt().toString() else String.format(java.util.Locale.US, "%.4f", h).trimEnd('0').trimEnd('.')
            return "0 0 $wStr $hStr"
        }
    }
}
