package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.LineNode
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.QrMatrix

enum class LineOrientation {
    HORIZONTAL,
    VERTICAL,
    DIAGONAL_DOWN,
    DIAGONAL_UP
}

/**
 * Geometric line segment representing a continuous contiguous run of QR modules.
 */
data class LineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val orientation: LineOrientation,
    val strokeWidth: Float
)

/**
 * Authoritative run-based topology builder for [com.veilframe.app.qr.QrStyle.LINE].
 *
 * Implements run-oriented line graph topology with true directional support:
 * 1. Horizontal continuous runs (length >= 2).
 * 2. Vertical continuous runs (length >= 2).
 * 3. Diagonal down runs (x+i, y+i) and diagonal up runs (x+i, y-i) for [LineDirection.X].
 * 4. Rounded stroke spines (isRoundCap = true).
 * 5. Circular node pads at data module positions.
 * 6. Target accent rings at key nodes (matching target circuit appearance).
 * 7. Protected finder patterns.
 */
object LineTopologyBuilder {

    private fun pseudoRandom(x: Int, y: Int, tag: Int, min: Float, max: Float): Float {
        val hash = (x * 374761393 + y * 668265263 + tag * 982451653) and 0x7FFFFFFF
        val norm = (hash % 10000) / 10000f
        return min + norm * (max - min)
    }

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
        direction: LineDirection = LineDirection.X,
        addAccentRings: Boolean = true
    ): List<QrGeometryNode> {
        val n = matrix.size
        val nodes = mutableListOf<QrGeometryNode>()
        val baseStrokeWidth = cs * thicknessFraction.coerceIn(0.15f, 0.85f)
        val nodeRadius = (baseStrokeWidth * 0.55f).coerceAtLeast(cs * 0.18f)

        // Mask of dark data modules (excluding finders)
        val isDataDark = Array(n) { col ->
            BooleanArray(n) { row ->
                matrix.isDark(col, row) && !VeilPositionPatternGeometry.isFinderArea(col, row, n)
            }
        }

        val segments = mutableListOf<LineSegment>()

        when (direction) {
            LineDirection.HORIZONTAL -> {
                val ava = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (y in 0 until n) {
                    var x = 0
                    while (x < n) {
                        if (ava[x][y]) {
                            var end = x
                            while (end + 1 < n && ava[end + 1][y]) {
                                end++
                            }
                            if (end > x) {
                                for (i in x..end) ava[i][y] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (end + 0.5f) * cs
                                val ly2 = oy + (y + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.HORIZONTAL, baseStrokeWidth))
                            }
                            x = end + 1
                        } else {
                            x++
                        }
                    }
                }
            }

            LineDirection.VERTICAL -> {
                val ava = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (x in 0 until n) {
                    var y = 0
                    while (y < n) {
                        if (ava[x][y]) {
                            var end = y
                            while (end + 1 < n && ava[x][end + 1]) {
                                end++
                            }
                            if (end > y) {
                                for (i in y..end) ava[x][i] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + 0.5f) * cs
                                val ly2 = oy + (end + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.VERTICAL, baseStrokeWidth))
                            }
                            y = end + 1
                        } else {
                            y++
                        }
                    }
                }
            }

            LineDirection.CROSS -> {
                // Vertical runs up to length 4
                val avaV = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (x in 0 until n) {
                    var y = 0
                    while (y < n) {
                        if (avaV[x][y]) {
                            var end = y
                            while (end + 1 < n && avaV[x][end + 1] && (end - y) < 3) {
                                end++
                            }
                            if (end > y) {
                                for (i in y..end) avaV[x][i] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + 0.5f) * cs
                                val ly2 = oy + (end + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.VERTICAL, baseStrokeWidth))
                            }
                            y = end + 1
                        } else {
                            y++
                        }
                    }
                }
                // Horizontal runs up to length 4
                val avaH = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (y in 0 until n) {
                    var x = 0
                    while (x < n) {
                        if (avaH[x][y]) {
                            var end = x
                            while (end + 1 < n && avaH[end + 1][y] && (end - x) < 3) {
                                end++
                            }
                            if (end > x) {
                                for (i in x..end) avaH[i][y] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (end + 0.5f) * cs
                                val ly2 = oy + (y + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.HORIZONTAL, baseStrokeWidth))
                            }
                            x = end + 1
                        } else {
                            x++
                        }
                    }
                }
            }

            LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT, LineDirection.DIAGONAL_FORWARD -> {
                val ava = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        if (ava[x][y] && (x == 0 || y == 0 || !isDataDark[x - 1][y - 1])) {
                            var end = 0
                            while (x + end < n && y + end < n && isDataDark[x + end][y + end]) {
                                end++
                            }
                            if (end > 1) {
                                for (i in 0 until end) ava[x + i][y + i] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.DIAGONAL_DOWN, baseStrokeWidth))
                            }
                        }
                    }
                }
            }

            LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT, LineDirection.DIAGONAL_BACKWARD -> {
                val ava = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        if (ava[x][y] && (x == 0 || y == n - 1 || !isDataDark[x - 1][y + 1])) {
                            var end = 0
                            while (x + end < n && y - end >= 0 && isDataDark[x + end][y - end]) {
                                end++
                            }
                            if (end > 1) {
                                for (i in 0 until end) ava[x + i][y - i] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y - end + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.DIAGONAL_UP, baseStrokeWidth))
                            }
                        }
                    }
                }
            }

            LineDirection.X -> {
                // True X cross-hatching: both diagonal runs + circuit spine connectors
                val avaUp = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                val avaDown = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }

                // Diagonal UP: (x+i, y-i)
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        if (avaUp[x][y] && (x == 0 || y == n - 1 || !isDataDark[x - 1][y + 1])) {
                            var end = 0
                            while (x + end < n && y - end >= 0 && isDataDark[x + end][y - end]) {
                                end++
                            }
                            if (end > 1) {
                                for (i in 0 until end) avaUp[x + i][y - i] = false
                                val sw = baseStrokeWidth * pseudoRandom(x, y, 1, 0.45f, 1.0f)
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y - end + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.DIAGONAL_UP, sw))
                            }
                        }
                    }
                }

                // Diagonal DOWN: (x+i, y+i)
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        if (avaDown[x][y] && (x == 0 || y == 0 || !isDataDark[x - 1][y - 1])) {
                            var end = 0
                            while (x + end < n && y + end < n && isDataDark[x + end][y + end]) {
                                end++
                            }
                            if (end > 1) {
                                for (i in 0 until end) avaDown[x + i][y + i] = false
                                val sw = baseStrokeWidth * pseudoRandom(x, y, 2, 0.45f, 1.0f)
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (x + end - 0.5f) * cs
                                val ly2 = oy + (y + end - 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.DIAGONAL_DOWN, sw))
                            }
                        }
                    }
                }

                // In Target #6 circuit mode, also connect short orthogonal spine bridges between isolated clusters
                val avaH = Array(n) { col -> BooleanArray(n) { row -> isDataDark[col][row] } }
                for (y in 0 until n) {
                    var x = 0
                    while (x < n) {
                        if (avaH[x][y]) {
                            var end = x
                            while (end + 1 < n && avaH[end + 1][y] && (end - x) < 3) {
                                end++
                            }
                            if (end > x && (x + y) % 3 == 0) {
                                for (i in x..end) avaH[i][y] = false
                                val lx1 = ox + (x + 0.5f) * cs
                                val ly1 = oy + (y + 0.5f) * cs
                                val lx2 = ox + (end + 0.5f) * cs
                                val ly2 = oy + (y + 0.5f) * cs
                                segments.add(LineSegment(lx1, ly1, lx2, ly2, LineOrientation.HORIZONTAL, baseStrokeWidth * 0.75f))
                            }
                            x = end + 1
                        } else {
                            x++
                        }
                    }
                }
            }

            else -> {}
        }

        // 1. Emit all line spine segments with round caps
        for (seg in segments) {
            nodes.add(
                LineNode(
                    x1 = seg.x1,
                    y1 = seg.y1,
                    x2 = seg.x2,
                    y2 = seg.y2,
                    strokeColor = lineColor,
                    strokeWidth = seg.strokeWidth,
                    isRoundCap = true
                )
            )
        }

        // 2. Circular node pads and target rings at data module positions
        for (x in 0 until n) {
            for (y in 0 until n) {
                if (!isDataDark[x][y]) continue

                val cx = ox + (x + 0.5f) * cs
                val cy = oy + (y + 0.5f) * cs

                if (direction == LineDirection.X) {
                    // Variable radius node circle matching reference & Target #6 circuit pads
                    val r = cs * 0.5f * pseudoRandom(x, y, 3, 0.40f, 0.88f)
                    nodes.add(CircleNode(cx, cy, r, fill = lineColor))

                    // Accent target rings on selected circuit nodes (Target #6)
                    if (addAccentRings && (x * 17 + y * 23) % 7 == 0) {
                        val ringR = cs * 0.46f
                        val ringSw = (cs * 0.08f).coerceAtLeast(1.5f)
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
                    val hasLeft = x > 0 && isDataDark[x - 1][y]
                    val hasRight = x + 1 < n && isDataDark[x + 1][y]
                    val hasUp = y > 0 && isDataDark[x][y - 1]
                    val hasDown = y + 1 < n && isDataDark[x][y + 1]

                    val degree = (if (hasLeft) 1 else 0) + (if (hasRight) 1 else 0) +
                        (if (hasUp) 1 else 0) + (if (hasDown) 1 else 0)

                    if (degree <= 1) {
                        nodes.add(CircleNode(cx, cy, nodeRadius, fill = lineColor))
                        if (addAccentRings && (x * 19 + y * 23) % 5 == 0) {
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
                    }
                }
            }
        }

        return nodes
    }
}
