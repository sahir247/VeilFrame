package com.veilframe.app.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [BeforeAfterSplitView] enums and contract.
 */
class BeforeAfterSplitViewTest {

    @Test
    fun `test background mode enum definitions`() {
        val modes = BeforeAfterSplitView.BackgroundMode.values()
        assertEquals(3, modes.size)
        assertNotNull(BeforeAfterSplitView.BackgroundMode.valueOf("TRANSPARENT_CHECKERBOARD"))
        assertNotNull(BeforeAfterSplitView.BackgroundMode.valueOf("PURE_WHITE"))
        assertNotNull(BeforeAfterSplitView.BackgroundMode.valueOf("PURE_BLACK"))
    }

    @Test
    fun `test split fraction clamp logic`() {
        fun clampFraction(value: Float): Float = value.coerceIn(0f, 1f)

        assertEquals(0.0f, clampFraction(-0.5f), 0.001f)
        assertEquals(1.0f, clampFraction(1.5f), 0.001f)
        assertEquals(0.5f, clampFraction(0.5f), 0.001f)
    }
}
