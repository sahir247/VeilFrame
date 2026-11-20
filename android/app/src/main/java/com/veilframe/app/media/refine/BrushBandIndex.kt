package com.veilframe.app.media.refine

/**
 * Sweep only strokes intersecting the current output band, in original journal
 * order. Rewind safely for a crop discovery pass followed by the encoding pass.
 * Storage is O(segment count), independent of raster height.
 */
internal class BrushBandIndex(strokes: List<CutoutStroke>, width: Int, height: Int) {
    internal data class Prepared(val order: Int, val stroke: CutoutStroke, val scaleX: Float,
                                 val scaleY: Float, val top: Float, val bottom: Float)

    private val commands = strokes.mapIndexed { i, stroke ->
        val scaleY = height.toFloat() / stroke.height
        Prepared(i, stroke, width.toFloat() / stroke.width, scaleY,
            (minOf(stroke.y1, stroke.y2) - stroke.radius) * scaleY,
            (maxOf(stroke.y1, stroke.y2) + stroke.radius) * scaleY)
    }
    private val byTop = commands.sortedBy { it.top }
    private val byBottom = java.util.PriorityQueue<Prepared>(compareBy { it.bottom })
    private val active = java.util.TreeSet<Int>()
    private var cursor = 0
    private var previousStart = -1
    private var previousEnd = -1

    fun intersecting(y: Int, rows: Int): List<Prepared> {
        require(y >= 0 && rows > 0)
        val end = y + rows
        if (y < previousStart || end < previousEnd) {
            active.clear()
            byBottom.clear()
            cursor = 0
        }
        previousStart = y
        previousEnd = end
        while (cursor < byTop.size && byTop[cursor].top <= end + 1f) {
            val next = byTop[cursor++]
            active.add(next.order)
            byBottom.add(next)
        }
        while (byBottom.isNotEmpty() && byBottom.peek().bottom < y - 1f) {
            active.remove(byBottom.remove().order)
        }
        return active.map { commands[it] }
    }
}
