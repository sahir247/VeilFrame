package com.veilframe.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.veilframe.app.MainActivity

/**
 * Foreground Service keeping long Motion Lab frame interpolation and re-encoding jobs alive.
 * Features:
 * - Linear progress reporting (0..100%)
 * - Notification-tray Cancel action button
 * - Completion notification with Open action button
 */
class MotionLabForegroundService : Service() {

    companion object {
        private const val TAG = "VeilFrame.MotionFgs"
        const val CHANNEL_ID = "veilframe_motion_lab"
        const val NOTIFICATION_ID = 1102

        const val ACTION_START = "com.veilframe.action.MOTION_START"
        const val ACTION_PROGRESS = "com.veilframe.action.MOTION_PROGRESS"
        const val ACTION_CANCEL = "com.veilframe.action.MOTION_CANCEL"
        const val ACTION_COMPLETE = "com.veilframe.action.MOTION_COMPLETE"

        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_PERCENT = "extra_percent"
        const val EXTRA_RESULT_PATH = "extra_result_path"

        @Volatile
        var cancelHook: (() -> Unit)? = null

        @Volatile
        private var lastProgressUpdateMs: Long = 0L

        fun isVeilFrameServiceRunning(): Boolean {
            val active = com.veilframe.app.VeilFrameProcessingService.activeJob
            return active != null && active.type == JobType.MOTION_LAB_RIFE && active.state == JobState.RUNNING
        }

        fun start(context: Context, label: String) {
            if (isVeilFrameServiceRunning()) {
                Log.i(TAG, "VeilFrameProcessingService is already active; suppressing duplicate MotionLabForegroundService")
                return
            }
            lastProgressUpdateMs = 0L
            val intent = Intent(context, MotionLabForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MotionLab FGS start failed: ${e.message}")
            }
        }

        fun updateProgress(context: Context, label: String, percent: Int) {
            val active = com.veilframe.app.VeilFrameProcessingService.activeJob
            if (active != null && active.type == JobType.MOTION_LAB_RIFE &&
                active.state == JobState.RUNNING) {
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (percent in 1..99 && now - lastProgressUpdateMs < 400L) return
            lastProgressUpdateMs = now
            val intent = Intent(context, MotionLabForegroundService::class.java).apply {
                action = ACTION_PROGRESS
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
            }
            runCatching { context.startService(intent) }
        }

        fun notifyComplete(context: Context, label: String, resultPath: String? = null) {
            val intent = Intent(context, MotionLabForegroundService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_RESULT_PATH, resultPath)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MotionLabForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground("Synthesizing frames…", -1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Motion Lab interpolation"
                startAsForeground(label, -1)
            }
            ACTION_PROGRESS -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Motion Lab interpolation"
                val pct = intent.getIntExtra(EXTRA_PERCENT, -1)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, buildProgressNotification(label, pct))
            }
            ACTION_CANCEL -> {
                cancelHook?.invoke()
                stopSelfSafely(removeNotification = true)
            }
            ACTION_COMPLETE -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Video ready"
                val resultPath = intent.getStringExtra(EXTRA_RESULT_PATH)
                showCompletionNotification(label, resultPath)
                stopSelfSafely(removeNotification = false)
            }
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelHook = null
        super.onDestroy()
    }

    private fun startAsForeground(label: String, percent: Int) {
        val notification = buildProgressNotification(label, percent)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun buildProgressNotification(label: String, percent: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MotionLabForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (percent in 0..100) {
            "$label ($percent%) — safe to switch apps"
        } else {
            "$label — processing on-device"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame — Motion Lab")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    private fun showCompletionNotification(label: String, resultPath: String?) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_navigate_destination", "MOTION_LAB")
            if (resultPath != null) putExtra("extra_media_path", resultPath)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motion Interpolation Finished")
            .setContentText("$label • Tap Open to view.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Motion Lab Processing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress for on-device video frame interpolation jobs"
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun stopSelfSafely(removeNotification: Boolean = true) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(removeNotification)
            }
        } catch (_: Exception) {}
        stopSelf()
    }
}
