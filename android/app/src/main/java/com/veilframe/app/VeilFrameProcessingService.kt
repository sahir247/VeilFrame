package com.veilframe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.veilframe.app.media.AndroidMediaBackend
import com.veilframe.app.media.JobState
import com.veilframe.app.media.JobType
import com.veilframe.app.media.ProcessingJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Android Foreground Service for heavy media cleaning and batch folder analysis.
 * Uses Android 15 mediaProcessing foreground service type on API 35+,
 * with graceful dataSync fallback for API 34 and below.
 */
class VeilFrameProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaBackend: AndroidMediaBackend

    companion object {
        const val CHANNEL_ID = "veilframe_media_processing"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PROCESS_VIDEO = "com.veilframe.action.PROCESS_VIDEO"
        const val EXTRA_INPUT_PATH = "extra_input_path"
        const val EXTRA_OUTPUT_PATH = "extra_output_path"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_JOB_TYPE = "extra_job_type"

        @Volatile
        var activeJob: ProcessingJob? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        mediaBackend = AndroidMediaBackend(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputPath = intent?.getStringExtra(EXTRA_INPUT_PATH) ?: ""
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH) ?: ""
        val jobIdStr = intent?.getStringExtra(EXTRA_JOB_ID)
        val jobId = try {
            if (!jobIdStr.isNullOrEmpty()) UUID.fromString(jobIdStr) else UUID.randomUUID()
        } catch (_: Exception) {
            UUID.randomUUID()
        }
        val jobTypeStr = intent?.getStringExtra(EXTRA_JOB_TYPE) ?: JobType.VIDEO_COMPRESSION.name
        val jobType = try {
            JobType.valueOf(jobTypeStr)
        } catch (_: Exception) {
            JobType.VIDEO_COMPRESSION
        }

        val inputUri = Uri.fromFile(File(inputPath))
        val outputUri = if (outputPath.isNotEmpty()) Uri.fromFile(File(outputPath)) else null

        val current = activeJob
        if (current != null && current.state == JobState.RUNNING) {
            // A job is already active; prevent concurrency collisions
            return START_NOT_STICKY
        }

        val job = ProcessingJob(
            id = jobId,
            type = jobType,
            inputUri = inputUri,
            outputUri = outputUri,
            state = JobState.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        activeJob = job

        val notification = buildForegroundNotification("Processing media with VeilFrame...")

        // Android 15 (API 35+) mediaProcessing FGS type compliance
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                if (inputPath.isNotEmpty() && outputPath.isNotEmpty()) {
                    val ok = mediaBackend.cleanVideo(inputPath, outputPath)
                    if (ok) {
                        activeJob = activeJob?.copy(state = JobState.COMPLETED, progressPercent = 100)
                    } else {
                        activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = "Processing failed in native media backend")
                    }
                } else {
                    activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = "Invalid input or output path")
                }
            } catch (e: Exception) {
                activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = e.message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        activeJob = activeJob?.copy(state = JobState.FAILED, errorMessage = "Processing timed out by Android system")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (activeJob?.state == JobState.RUNNING) {
            activeJob = activeJob?.copy(state = JobState.CANCELLED)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VeilFrame Processing Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during video sanitization and bundle generation"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame Privacy Shield")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
