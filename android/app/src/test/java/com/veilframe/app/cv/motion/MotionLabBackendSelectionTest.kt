package com.veilframe.app.cv.motion

import org.junit.Assert.*
import org.junit.Test

class MotionLabBackendSelectionTest {
    @Test fun gpuFalseFallsBackToCpuBeforeOpticalFlow() {
        val attempts = mutableListOf<Boolean>()
        assertTrue(MotionLabBackendSelection.initialize(true) { gpu ->
            attempts.add(gpu)
            !gpu
        })
        assertEquals(listOf(true,false), attempts)
    }

    @Test fun successfulGpuDoesNotInitializeCpuAndEmulatorNeverAttemptsGpu() {
        val attempts = mutableListOf<Boolean>()
        assertTrue(MotionLabBackendSelection.initialize(true) { attempts.add(it); true })
        assertEquals(listOf(true),attempts)
        attempts.clear()
        assertFalse(MotionLabBackendSelection.initialize(false) { attempts.add(it); false })
        assertEquals(listOf(false),attempts)
    }
}
