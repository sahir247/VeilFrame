package com.veilframe.app.qr.geometry

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF

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
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val opacity: Float = 1f,
    val alwaysEmitOpacity: Boolean = false,
    val transform: String? = null
) : QrGeometryNode

data class CircleNode(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val fill: Int? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
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
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val opacity: Float = 1f,
    val transform: String? = null
) : QrGeometryNode

data class PathNode(
    val svgPathData: String,
    val androidPath: Path? = null,
    val fill: Int? = null,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
    val opacity: Float = 1f,
    val transform: String? = null
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
    val clipOutRects: List<RectF> = emptyList(),
    val transform: String? = null
) : QrGeometryNode

data class GroupNode(
    val children: List<QrGeometryNode>,
    val maskId: String? = null,
    val opacity: Float = 1f,
    val transform: String? = null
) : QrGeometryNode

/**
 * Complete document-level geometry definition for a QR code.
 */
data class QrGeometryIr(
    val width: Float,
    val height: Float,
    val viewBox: String = "0 0 ${width.toInt()} ${height.toInt()}",
    val defs: List<String> = emptyList(),
    val rootNodes: List<QrGeometryNode> = emptyList()
)
