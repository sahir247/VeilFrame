package com.veilframe.app.media

import android.net.Uri
import com.veilframe.app.VeilFrameProcessingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Unit tests verifying:
 * 1. Motion Lab duration limit (60s / 1800 frames) and rate decoupling.
 * 2. Suppression of duplicate notifications in MotionLabForegroundService when VeilFrameProcessingService is active.
 * 3. Prevention of re-entrant prompt loops in MainNavigationController.
 */
@RunWith(RobolectricTestRunner::class)
class MotionLabAndBackgroundNavigationTest {

    @Test
    fun `verify Motion Lab duration and frame limits`() {
        assertEquals(60, MotionLabController.MAX_DURATION_S)
        assertEquals(1800, MotionLabController.MAX_FRAMES)
    }

    @Test
    fun `verify MotionLabForegroundService suppresses duplicate notification when VeilFrameProcessingService is running`() {
        val dummyUri = Uri.parse("content://media/external/video/media/12345")
        try {
            // Simulate VeilFrameProcessingService running Motion Lab job
            VeilFrameProcessingService.activeJob = ProcessingJob(
                id = UUID.randomUUID(),
                type = JobType.MOTION_LAB_RIFE,
                inputUri = dummyUri,
                state = JobState.RUNNING,
                progressPercent = 50
            )

            assertTrue(
                "isVeilFrameServiceRunning must return true when VeilFrameProcessingService has active running MOTION_LAB_RIFE job",
                MotionLabForegroundService.isVeilFrameServiceRunning()
            )

            // Simulate completed job
            VeilFrameProcessingService.activeJob = ProcessingJob(
                id = UUID.randomUUID(),
                type = JobType.MOTION_LAB_RIFE,
                inputUri = dummyUri,
                state = JobState.COMPLETED,
                progressPercent = 100
            )

            assertFalse(
                "isVeilFrameServiceRunning must return false when VeilFrameProcessingService job is COMPLETED",
                MotionLabForegroundService.isVeilFrameServiceRunning()
            )

            // Simulate no active job
            VeilFrameProcessingService.activeJob = null

            assertFalse(
                "isVeilFrameServiceRunning must return false when activeJob is null",
                MotionLabForegroundService.isVeilFrameServiceRunning()
            )
        } finally {
            VeilFrameProcessingService.activeJob = null
        }
    }

    @Test
    fun `verify MainNavigationController contains re-entrant prompt guard`() {
        val navClass = com.veilframe.app.navigation.MainNavigationController::class.java
        val field = navClass.getDeclaredField("isHandlingWorkStatePrompt")
        field.isAccessible = true
        assertTrue(
            "isHandlingWorkStatePrompt must be volatile to ensure thread-safe re-entrancy prevention",
            Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun `verify popWorkspaceAndNavigate method exists in MainNavigationController`() {
        val navClass = com.veilframe.app.navigation.MainNavigationController::class.java
        val methods = navClass.declaredMethods
        val popMethod = methods.find { it.name == "popWorkspaceAndNavigate" }
        assertTrue(
            "popWorkspaceAndNavigate must exist to decouple back navigation from re-entrant prompts",
            popMethod != null
        )
    }

    @Test
    fun `verify RifeModelManager isModelReady returns false when model files are missing`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = com.veilframe.app.media.rife.RifeModelManager(context)
        manager.paramFile.delete()
        manager.binFile.delete()
        assertFalse("isModelReady must be false when model files are missing", manager.isModelReady())
    }
}
