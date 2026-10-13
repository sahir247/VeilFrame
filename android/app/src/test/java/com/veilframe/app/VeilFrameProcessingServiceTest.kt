package com.veilframe.app

import android.app.Service
import android.content.Intent
import android.net.Uri
import com.veilframe.app.media.JobState
import com.veilframe.app.media.JobType
import com.veilframe.app.media.ProcessingJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class VeilFrameProcessingServiceTest {

    private lateinit var service: VeilFrameProcessingService

    @Before
    fun setUp() {
        VeilFrameProcessingService.activeJob = null
        val controller = Robolectric.buildService(VeilFrameProcessingService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        VeilFrameProcessingService.activeJob = null
    }

    @Test
    fun testDuplicateStartRejectedWhenJobAlreadyRunning() {
        val initialJobId = UUID.randomUUID()
        val runningJob = ProcessingJob(
            id = initialJobId,
            type = JobType.METADATA_STRIP,
            inputUri = Uri.parse("file:///dummy/in.mp4"),
            outputUri = Uri.parse("file:///dummy/out.mp4"),
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        VeilFrameProcessingService.activeJob = runningJob

        val duplicateIntent = Intent(service, VeilFrameProcessingService::class.java).apply {
            action = VeilFrameProcessingService.ACTION_PROCESS_VIDEO
            putExtra(VeilFrameProcessingService.EXTRA_INPUT_PATH, "/dummy/second.mp4")
            putExtra(VeilFrameProcessingService.EXTRA_OUTPUT_PATH, "/dummy/second_out.mp4")
            putExtra(VeilFrameProcessingService.EXTRA_JOB_ID, UUID.randomUUID().toString())
        }

        val result = service.onStartCommand(duplicateIntent, 0, 42)

        assertEquals("Duplicate start must return START_NOT_STICKY", Service.START_NOT_STICKY, result)
        assertSame("Active job must not be overwritten by duplicate start", runningJob, VeilFrameProcessingService.activeJob)
        assertEquals("Active job ID must remain unchanged", initialJobId, VeilFrameProcessingService.activeJob?.id)
    }

    @Test
    fun testCancelJobActionCancelsRunningJob() {
        val runningJobId = UUID.randomUUID()
        val runningJob = ProcessingJob(
            id = runningJobId,
            type = JobType.METADATA_STRIP,
            inputUri = Uri.parse("file:///dummy/in.mp4"),
            outputUri = Uri.parse("file:///dummy/out.mp4"),
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        VeilFrameProcessingService.activeJob = runningJob

        val cancelIntent = Intent(service, VeilFrameProcessingService::class.java).apply {
            action = VeilFrameProcessingService.ACTION_CANCEL_JOB
            putExtra(VeilFrameProcessingService.EXTRA_JOB_ID, runningJobId.toString())
        }

        val result = service.onStartCommand(cancelIntent, 0, 43)

        assertEquals("Cancel command must return START_NOT_STICKY", Service.START_NOT_STICKY, result)
        assertEquals("Active job must transition to CANCELLED state", JobState.CANCELLED, VeilFrameProcessingService.activeJob?.state)
    }

    @Test
    fun testCancelMismatchedJobIdDoesNotCancelRunningJob() {
        val runningJobId = UUID.randomUUID()
        val otherJobId = UUID.randomUUID()
        val runningJob = ProcessingJob(
            id = runningJobId,
            type = JobType.METADATA_STRIP,
            inputUri = Uri.parse("file:///dummy/in.mp4"),
            outputUri = Uri.parse("file:///dummy/out.mp4"),
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        VeilFrameProcessingService.activeJob = runningJob

        val cancelIntent = Intent(service, VeilFrameProcessingService::class.java).apply {
            action = VeilFrameProcessingService.ACTION_CANCEL_JOB
            putExtra(VeilFrameProcessingService.EXTRA_JOB_ID, otherJobId.toString())
        }

        service.onStartCommand(cancelIntent, 0, 44)

        assertEquals("Active job must remain RUNNING when cancel targets different ID", JobState.RUNNING, VeilFrameProcessingService.activeJob?.state)
    }

    @Test
    fun testServiceDestroyCancelsRunningJob() {
        val runningJobId = UUID.randomUUID()
        val runningJob = ProcessingJob(
            id = runningJobId,
            type = JobType.METADATA_STRIP,
            inputUri = Uri.parse("file:///dummy/in.mp4"),
            outputUri = Uri.parse("file:///dummy/out.mp4"),
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        VeilFrameProcessingService.activeJob = runningJob

        service.onDestroy()

        assertEquals("Active job must be marked CANCELLED on service destroy", JobState.CANCELLED, VeilFrameProcessingService.activeJob?.state)
    }
}
