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
}
