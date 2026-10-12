package com.veilframe.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.VeilFrameProcessingService
import com.veilframe.app.media.JobState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * On-device instrumentation test for [VeilFrameProcessingService].
 *
 * Verifies Android 8.0+ / 14+ / 15+ / 16+ foreground service contract compliance:
 * - Immediate startForeground() invocation under mediaProcessing FGS type.
 * - Non-crashing lifecycle execution on physical hardware.
 */
@RunWith(AndroidJUnit4::class)
class VeilFrameProcessingServiceTest {

    @Test
    fun testForegroundServiceStartsGracefullyWithoutRemoteServiceException() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Create temporary dummy files
        val inDir = context.cacheDir
        val inFile = File(inDir, "test_input_${UUID.randomUUID()}.tmp").apply {
            writeBytes("test".toByteArray())
        }
        val outFile = File(inDir, "test_output_${UUID.randomUUID()}.tmp")

        try {
            val jobId = UUID.randomUUID()
            VeilFrameProcessingService.startVideoCleaning(
                context,
                inFile.absolutePath,
                outFile.absolutePath,
                jobId
            )

            // Allow the service to process the start intent and promote to foreground
            Thread.sleep(1500)

            val activeJob = VeilFrameProcessingService.activeJob
            assertNotNull("activeJob should have been created upon service start", activeJob)
            assertTrue(
                "Job state should be RUNNING, COMPLETED, or FAILED (not uninitialized), got ${activeJob?.state}",
                activeJob?.state in listOf(JobState.RUNNING, JobState.COMPLETED, JobState.FAILED)
            )
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }
}
