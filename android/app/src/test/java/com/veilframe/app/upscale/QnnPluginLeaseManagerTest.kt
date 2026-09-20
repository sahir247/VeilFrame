package com.veilframe.app.upscale

import android.content.ContextWrapper
import com.veilframe.app.upscale.inference.qnn.QnnPluginLeaseManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QnnPluginLeaseManagerTest {

    @Before
    fun setUp() {
        QnnPluginLeaseManager.resetForTesting()
    }

    @After
    fun tearDown() {
        QnnPluginLeaseManager.resetForTesting()
    }

    @Test
    fun testInitialState() {
        assertEquals(0, QnnPluginLeaseManager.currentLeaseCount)
        assertEquals(0, QnnPluginLeaseManager.currentSessionCount)
        assertFalse(QnnPluginLeaseManager.isRemovalPending)
    }

    @Test
    fun testSessionReferenceCounting() {
        assertEquals(0, QnnPluginLeaseManager.currentSessionCount)

        QnnPluginLeaseManager.incrementSession()
        assertEquals(1, QnnPluginLeaseManager.currentSessionCount)

        QnnPluginLeaseManager.incrementSession()
        assertEquals(2, QnnPluginLeaseManager.currentSessionCount)

        QnnPluginLeaseManager.decrementSession()
        assertEquals(1, QnnPluginLeaseManager.currentSessionCount)

        QnnPluginLeaseManager.decrementSession()
        assertEquals(0, QnnPluginLeaseManager.currentSessionCount)

        // Decrementing below 0 is floored at 0
        QnnPluginLeaseManager.decrementSession()
        assertEquals(0, QnnPluginLeaseManager.currentSessionCount)
    }

    @Test
    fun testRemovalDeferredWhenSessionsActive() {
        val dummyContext = ContextWrapper(null)
        QnnPluginLeaseManager.incrementSession()
        assertEquals(1, QnnPluginLeaseManager.currentSessionCount)

        // Attempting to remove while session is active must defer and not execute immediately
        val removed = QnnPluginLeaseManager.remove(dummyContext, null)

        assertFalse("Removal must be deferred while session count > 0", removed)
        assertTrue("Removal pending flag must be set", QnnPluginLeaseManager.isRemovalPending)

        // Decrementing to 0 when context is null doesn't crash on unmocked native files, but verifies decrement
        QnnPluginLeaseManager.decrementSession(null, null)
        assertEquals(0, QnnPluginLeaseManager.currentSessionCount)
        // Removal is still pending until a context executes it
        assertTrue(QnnPluginLeaseManager.isRemovalPending)
    }
}
