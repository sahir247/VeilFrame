package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test

class BrushReplayJournalTest {
    private fun stroke(x: Float) = CutoutStroke(100, 100, x, 4f, x, 8f, 4f, false)

    @Test fun replaySurvivesUndoRedoAndBranching() {
        val journal = BrushReplayJournal(4)
        val first = stroke(1f)
        val second = stroke(2f)
        val third = stroke(3f)
        journal.append(first); journal.commit()
        journal.append(second); journal.commit()
        journal.undo()
        assertEquals(listOf(first), journal.snapshot())
        journal.redo()
        assertEquals(listOf(first, second), journal.snapshot())
        journal.undo()
        journal.append(third); journal.commit()
        journal.redo()
        assertEquals(listOf(first, third), journal.snapshot())
    }

    @Test fun overflowNeverReturnsAnIncompleteJournal() {
        val journal = BrushReplayJournal(2)
        repeat(3) { journal.append(stroke(it.toFloat())); journal.commit() }
        assertNull(journal.snapshot())
        journal.undo()
        assertNull(journal.snapshot())
        journal.clear()
        assertEquals(emptyList<CutoutStroke>(), journal.snapshot())
    }
}
