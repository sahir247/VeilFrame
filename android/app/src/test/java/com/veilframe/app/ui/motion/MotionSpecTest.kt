package com.veilframe.app.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSpecTest {

    @Test
    fun testMotionScalesReflectM3Physics() {
        assertEquals(0.94f, MotionSpec.PRESS_SCALE, 0.001f)
        assertEquals(0.95f, MotionSpec.JELLY_DOWN_SCALE, 0.001f)
        assertEquals(1.045f, MotionSpec.JELLY_OVERSHOOT_SCALE, 0.001f)
    }

    @Test
    fun testMotionDurationsFollowHierarchy() {
        assertTrue(MotionSpec.FAST < MotionSpec.NORMAL)
        assertTrue(MotionSpec.NORMAL < MotionSpec.EXPRESSIVE)
        assertTrue(MotionSpec.EXPRESSIVE <= MotionSpec.MORPH)
        assertEquals(320L, MotionSpec.JELLY_DURATION)
    }

    @Test
    fun testDialogMotionStateTransitions() {
        var state = DialogMotionState.CLOSED
        assertEquals(DialogMotionState.CLOSED, state)
        state = DialogMotionState.OPENING
        assertEquals(DialogMotionState.OPENING, state)
        state = DialogMotionState.OPEN
        assertEquals(DialogMotionState.OPEN, state)
        state = DialogMotionState.CLOSING
        assertEquals(DialogMotionState.CLOSING, state)
        state = DialogMotionState.DISMISSED
        assertEquals(DialogMotionState.DISMISSED, state)
    }
}
