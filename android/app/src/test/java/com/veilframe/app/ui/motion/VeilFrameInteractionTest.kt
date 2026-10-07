package com.veilframe.app.ui.motion

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit test for [VeilFrameInteraction] API presence.
 */
class VeilFrameInteractionTest {

    @Test
    fun `test VeilFrameInteraction singleton is available`() {
        assertNotNull(VeilFrameInteraction)
    }
}
