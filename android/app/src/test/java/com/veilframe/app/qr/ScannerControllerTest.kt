package com.veilframe.app.qr

import com.veilframe.app.qr.scanner.ScannerController
import com.veilframe.app.qr.scanner.ScannerState
import org.junit.Assert.*
import org.junit.Test

class ScannerControllerTest {

    @Test
    fun testScannerStateTransitionToStable() {
        val controller = ScannerController(
            frameThrottleMs = 50L,
            duplicateCooldownMs = 1000L,
            requiredStableFrames = 2
        )

        assertEquals(ScannerState.IDLE, controller.state.value)

        // First candidate frame -> DETECTED
        val res1 = controller.onPayloadDecoded("https://veilframe.app")
        assertFalse(res1)
        assertEquals(ScannerState.DETECTED, controller.state.value)

        // Second matching frame -> STABLE
        val res2 = controller.onPayloadDecoded("https://veilframe.app")
        assertTrue(res2)
        assertEquals(ScannerState.STABLE, controller.state.value)

        // Mark presented
        controller.markPresented()
        assertEquals(ScannerState.PRESENTED, controller.state.value)

        // Immediate identical payload -> COOLDOWN
        val res3 = controller.onPayloadDecoded("https://veilframe.app")
        assertFalse(res3)
        assertEquals(ScannerState.COOLDOWN, controller.state.value)
    }

    @Test
    fun testFrameMissResetsCandidate() {
        val controller = ScannerController(requiredStableFrames = 2)

        controller.onPayloadDecoded("candidate1")
        assertEquals(ScannerState.DETECTED, controller.state.value)

        controller.onFrameMiss()
        assertEquals(ScannerState.IDLE, controller.state.value)
    }

    @Test
    fun testCooldownExpiryRequiresFreshStabilization() {
        val controller = ScannerController(
            frameThrottleMs = 10L,
            duplicateCooldownMs = 100L,
            requiredStableFrames = 2
        )

        // Reach stable and present
        controller.onPayloadDecoded("payloadA")
        assertTrue(controller.onPayloadDecoded("payloadA"))
        controller.markPresented()

        // During cooldown -> suppressed
        assertFalse(controller.onPayloadDecoded("payloadA"))
        assertEquals(ScannerState.COOLDOWN, controller.state.value)

        // Wait for cooldown to expire
        Thread.sleep(120L)

        // First frame after cooldown must be DETECTED, NOT immediately STABLE/presented
        val firstFrame = controller.onPayloadDecoded("payloadA")
        assertFalse("First frame after cooldown should not immediately trigger", firstFrame)
        assertEquals(ScannerState.DETECTED, controller.state.value)

        // Second frame reaches STABLE
        val secondFrame = controller.onPayloadDecoded("payloadA")
        assertTrue("Second frame after cooldown reaches stable", secondFrame)
        assertEquals(ScannerState.STABLE, controller.state.value)
    }

    private fun createMockProxy(onClose: (() -> Unit)? = null): androidx.camera.core.ImageProxy {
        return java.lang.reflect.Proxy.newProxyInstance(
            androidx.camera.core.ImageProxy::class.java.classLoader,
            arrayOf(androidx.camera.core.ImageProxy::class.java)
        ) { _, method, _ ->
            if (method.name == "close") {
                onClose?.invoke()
            }
            null
        } as androidx.camera.core.ImageProxy
    }

    @Test
    fun testFrameTokenSingleFlightAndWatchdog() {
        val controller = ScannerController(
            frameThrottleMs = 20L,
            analysisTimeoutMs = 80L
        )

        var closed1 = false
        val proxy1 = createMockProxy { closed1 = true }
        val token1 = controller.acquireFrameToken(proxy1)
        assertNotNull("First token must be acquired", token1)
        assertTrue("Token1 should be active", controller.isTokenActive(token1!!))
        assertFalse("Proxy1 must not be closed yet", closed1)

        // Attempt second acquisition while token1 is still running -> must be rejected and closed immediately
        var closed2 = false
        val proxy2 = createMockProxy { closed2 = true }
        val token2 = controller.acquireFrameToken(proxy2)
        assertNull("Second concurrent token must be rejected", token2)
        assertTrue("Rejected proxy2 must be closed immediately", closed2)

        // Complete processing for token1
        controller.finishFrameProcessing(token1)
        assertTrue("Finished proxy1 must be closed", closed1)
        assertFalse("Token1 is no longer active", controller.isTokenActive(token1))

        // Wait past throttle window
        Thread.sleep(30L)

        // Now token3 can be acquired
        var closed3 = false
        val proxy3 = createMockProxy { closed3 = true }
        val token3 = controller.acquireFrameToken(proxy3)
        assertNotNull("Token3 acquired after token1 finished", token3)
        assertTrue("Token3 should be active", controller.isTokenActive(token3!!))

        // Simulate watchdog timeout: wait past analysisTimeoutMs (80ms)
        Thread.sleep(100L)

        // Acquire new frame token4 past timeout -> watchdog invalidates token3 and grants token4
        var closed4 = false
        val proxy4 = createMockProxy { closed4 = true }
        val token4 = controller.acquireFrameToken(proxy4)
        assertNotNull("Token4 acquired after token3 timed out", token4)
        assertTrue("Token4 is now active", controller.isTokenActive(token4!!))
        assertFalse("Token3 is now invalidated", controller.isTokenActive(token3))

        // Simulate stale late callback from token3 calling finishFrameProcessing
        controller.finishFrameProcessing(token3)
        // Token4 MUST STILL BE ACTIVE! The stale token3 callback must not release token4's lock!
        assertTrue("Token4 must remain active even after stale token3 finishes", controller.isTokenActive(token4))

        // Finally finish token4
        controller.finishFrameProcessing(token4)
        assertFalse("Token4 is now finished", controller.isTokenActive(token4))
    }
}
