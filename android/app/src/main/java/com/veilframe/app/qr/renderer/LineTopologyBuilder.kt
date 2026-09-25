package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.LineNode
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.model.QrMatrix

enum class LineOrientation {
    HORIZONTAL,
    VERTICAL,
    DIAGONAL
}

/**
 * Geometric line segment representing a continuous contiguous run of QR modules.
 */
data class LineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val orientation: LineOrientation
)

/**
 * Authoritative run-based topology builder for [com.veilframe.app.qr.QrStyle.LINE].
 *
 * Implements run-oriented line graph topology:
 * 1. Scans contiguous horizontal runs of dark modules (length >= 1).
 * 2. Scans contiguous vertical runs of dark modules (length >= 1).
 * 3. Builds continuous stroke spines with round end-caps (strokeCap = ROUND).
 * 4. Adds circular node pads at endpoints, junctions, and isolated modules.
 * 5. Adds outer concentric target/accent rings at selected nodes for futuristic circuit aesthetics.
 * 6. Completely isolates finder patterns to protect barcode readability.
 */
object LineTopologyBuilder {

    /**
     * Builds the complete set of IR geometry nodes for the line data topology.
     */
    fun buildTopology(
        matrix: QrMatrix,
        ox: Float,
        oy: Float,
        cs: Float,
        thicknessFraction: Float,
        lineColor: Int,
        addAccentRings: Boolean = true
    ): List<QrGeometryNode> {
        val n = matrix.size
        val nodes = mutableListOf<QrGeometryNode>()
        val strokeWidth = (cs * thicknessFraction.coerceIn(0.2f, 0.8f))
        val nodeRadius = strokeWidth * 0.65f

        // Mask of dark data modules (excluding finders)
        val isDataDark = Array(n) { col ->
            BooleanArray(n) { row ->
                matrix.isDark(col, row) && !VeilPositionPatternGeometry.isFinderArea(col, row, n)
            }
        }

        val segments = mutableListOf<LineSegment>()

        // 1. Horizontal continuous dark runs
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (isDataDark[x][y]) {
                    var end = x
                    while (end + 1 < n && isDataDark[end + 1][y]) {
                        end++
                    }
                    if (end > x) {
                        val lx1 = ox + (x + 0.5f) * cs
                        val ly1 = oy + (y + 0.5f) * cs
                        val lx2 = ox + (end + 0.5f) * cs
                        val ly2 = oy + (y + 0.5f) * cs
                        segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.HORIZONTAL))
                    }
                    x = end + 1
                } else {
                    x++
                }
            }
        }

        // 2. Vertical continuous dark runs
        for (x in 0 until n) {
            var y = 0
            while (y < n) {
                if (isDataDark[x][y]) {
                    var end = y
                    while (end + 1 < n && isDataDark[x][end + 1]) {
                        end++
                    }
                    if (end > y) {
                        val lx1 = ox + (x + 0.5f) * cs
                        val ly1 = oy + (y + 0.5f) * cs
                        val lx2 = ox + (x + 0.5f) * cs
                        val ly2 = oy + (end + 0.5f) * cs
                        segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.VERTICAL))
                    }
                    y = end + 1
                } else {
                    y++
                }
            }
        }

        // 3. Emit all line spine segments
        for (seg in segments) {
            nodes.add(
                LineNode(
                    x1 = seg.x1,
                    y1 = seg.y1,
                    x2 = seg.x2,
                    y2 = seg.y2,
                    strokeColor = lineColor,
                    strokeWidth = strokeWidth,
                    isRoundCap = true
                )
            )
        }

        // 4. Circular node pads and target rings at data module positions
        for (x in 0 until n) {
            for (y in 0 until n) {
                if (!isDataDark[x][y]) continue

                val cx = ox + (x + 0.5f) * cs
                val cy = oy + (y + 0.5f) * cs

                val hasLeft = x > 0 && isDataDark[x - 1][y]
                val hasRight = x + 1 < n && isDataDark[x + 1][y]
                val hasUp = y > 0 && isDataDark[x][y - 1]
                val hasDown = y + 1 < n && isDataDark[x][y + 1]

                val degree = (if (hasLeft) 1 else 0) + (if (hasRight) 1 else 0) +
                    (if (hasUp) 1 else 0) + (if (hasDown) 1 else 0)

                if (degree == 0) {
                    // Isolated module: smooth circle node
                    val r = cs * (thicknessFraction * 0.48f).coerceIn(0.18f, 0.42f)
                    nodes.add(CircleNode(cx, cy, r, fill = lineColor))
                } else if (degree == 1) {
                    // Endpoint of a line: round terminal node pad
                    nodes.add(CircleNode(cx, cy, nodeRadius, fill = lineColor))

                    // Optional target/accent ring at selected line terminals
                    if (addAccentRings && (x * 19 + y * 23) % 4 == 0) {
                        val ringR = cs * 0.44f
                        val ringSw = cs * 0.08f
                        nodes.add(
                            CircleNode(
                                cx = cx,
                                cy = cy,
                                radius = ringR,
                                stroke = lineColor,
                                strokeWidth = ringSw
                            )
                        )
                    }
                } else {
                    // Junction or corner: node circle to ensure crisp visual fullness
                    nodes.add(CircleNode(cx, cy, nodeRadius, fill = lineColor))

                    // Optional target ring at selected junctions
                    if (addAccentRings && (x * 11 + y * 13) % 6 == 0) {
                        val ringR = cs * 0.45f
                        val ringSw = cs * 0.08f
                        nodes.add(
                            CircleNode(
                                cx = cx,
                                cy = cy,
                                radius = ringR,
                                stroke = lineColor,
                                strokeWidth = ringSw
                            )
                        )
                    }
                }
            }
        }

        return nodes
    }
}
