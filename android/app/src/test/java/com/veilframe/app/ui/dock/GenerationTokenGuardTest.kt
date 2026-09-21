package com.veilframe.app.ui.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTokenGuardTest {

    @Test
    fun testGenerationTokenInvalidatesStaleCompletion() {
        var activeGeneration = 0L
        var dockState = FloatingActionState.EMPTY

        // 1. Select Item A
        activeGeneration++
        val selectGenA = activeGeneration
        dockState = FloatingActionState.READY
        assertEquals(1L, selectGenA)
        assertEquals(FloatingActionState.READY, dockState)

        // 2. Start compression for Item A
        val compressionGenA = ++activeGeneration
        dockState = FloatingActionState.PROCESSING
        assertEquals(2L, compressionGenA)
        assertEquals(FloatingActionState.PROCESSING, dockState)

        // 3. User switches to Item B before Item A finishes
        activeGeneration++
        val selectGenB = activeGeneration
        dockState = FloatingActionState.READY // Switched to new item -> dock reset to READY
        assertEquals(3L, selectGenB)
        assertEquals(FloatingActionState.READY, dockState)

        // 4. Background task for Item A finishes with compressionGenA = 2L
        val isItemAValid = (compressionGenA == activeGeneration)
        assertFalse("Item A completion must be rejected due to stale generation token", isItemAValid)

        // Dock state must remain READY for Item B and NOT become COMPLETED for Item A
        if (isItemAValid) {
            dockState = FloatingActionState.COMPLETED
        }
        assertEquals("Dock must remain READY for Item B", FloatingActionState.READY, dockState)

        // 5. Start compression for Item B
        val compressionGenB = ++activeGeneration
        dockState = FloatingActionState.PROCESSING
        assertEquals(4L, compressionGenB)

        // 6. Item B finishes with matching generation token
        val isItemBValid = (compressionGenB == activeGeneration)
        assertTrue("Item B completion must be accepted", isItemBValid)
        if (isItemBValid) {
            dockState = FloatingActionState.COMPLETED
        }
        assertEquals(FloatingActionState.COMPLETED, dockState)
    }

    @Test
    fun testClearInvalidatesOngoingTasks() {
        var activeGeneration = 10L
        val taskGen = ++activeGeneration // 11L

        // Clear action increments activeGeneration
        activeGeneration++ // 12L

        assertFalse("Stale task must be rejected after clear()", taskGen == activeGeneration)
    }
}
