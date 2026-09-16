package com.veilframe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.veilframe.app.media.AndroidMediaBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    }

    override fun onCreate() {
        super.onCreate()
        mediaBackend = AndroidMediaBackend(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputPath = intent?.getStringExtra(EXTRA_INPUT_PATH) ?: ""
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH) ?: ""

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
            if (inputPath.isNotEmpty() && outputPath.isNotEmpty()) {
                mediaBackend.cleanVideo(inputPath, outputPath)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
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
