package com.veilframe.app.cv.segmentation.rembg

/** A brush segment in the untrimmed editor's own coordinate system. */
internal data class CutoutStroke(val width: Int, val height: Int,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val radius: Float, val restore: Boolean) {
    init {
        require(width > 0 && height > 0 && radius.isFinite() && radius > 0f &&
            listOf(x1, y1, x2, y2).all { it.isFinite() })
    }
}

/**
 * Separate semantic undo history: eviction of bitmap snapshots must not erase
 * older committed strokes from the original-resolution export.
 */
internal class BrushReplayJournal(private val limit: Int = MAX_SEGMENTS) {
    companion object { const val MAX_SEGMENTS = 4096 }
    private val groups = ArrayList<List<CutoutStroke>>()
    private val redo = ArrayList<List<CutoutStroke>>()
    private val pending = ArrayList<CutoutStroke>()
    private var segments = 0
    private var available = true

    fun append(stroke: CutoutStroke) {
        if (!available) return
        if (redo.isNotEmpty()) {
            segments -= redo.sumOf { it.size }
            redo.clear()
        }
        if (segments >= limit) {
            available = false
            groups.clear(); redo.clear(); pending.clear()
            return
        }
        pending.add(stroke)
        segments++
    }

    fun commit() {
        if (!available || pending.isEmpty()) return
        groups.add(pending.toList())
        pending.clear()
    }

    fun undo() {
        if (!available || groups.isEmpty()) return
        check(pending.isEmpty())
        redo.add(groups.removeAt(groups.lastIndex))
    }

    fun redo() {
        if (!available || redo.isEmpty()) return
        groups.add(redo.removeAt(redo.lastIndex))
    }

    fun snapshot(): List<CutoutStroke>? =
        if (!available) null else groups.flatten() + pending

    fun clear() {
        groups.clear(); redo.clear(); pending.clear()
        segments = 0
        available = true
    }
}
