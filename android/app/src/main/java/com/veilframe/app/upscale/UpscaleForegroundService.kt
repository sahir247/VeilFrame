package com.veilframe.app.upscale

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
import java.io.File

/**
 * UpscaleForegroundService — F6: keeps long neural-upscale jobs alive and
 * properly scheduled.
 *
 * Root problem fixed: minutes of heavy CPU with NO foreground service made the
 * process a prime target for OEM phantom-process killers and low-memory kills
 * — users experienced "the app crashed" mid-upscale with no stack trace. The
 * FGS also gives the job a visible, cancellable notification (honest progress)
 * and a proper scheduling class.
 *
 * Mirrors the proven VeilFrameProcessingService pattern (channel + dataSync
 * type + graceful degradation), scoped to the upscaler so its lifecycle stays
 * trivial. Cancellation is cooperative: the notification action invokes
 * [cancelHook], which the controller binds to its inference job.
 */
class UpscaleForegroundService : Service() {

    companion object {
        private const val TAG = "VeilFrame.UpscaleFgs"
        const val CHANNEL_ID = "veilframe_upscale"
        const val NOTIFICATION_ID = 1101

        const val ACTION_START = "com.veilframe.action.UPSCALE_START"
        const val ACTION_PROGRESS = "com.veilframe.action.UPSCALE_PROGRESS"
        const val ACTION_CANCEL = "com.veilframe.action.UPSCALE_CANCEL"
        const val ACTION_COMPLETE = "com.veilframe.action.UPSCALE_COMPLETE"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_PERCENT = "extra_percent"
        const val EXTRA_RESULT_PATH = "extra_result_path"

        /**
         * Cooperative cancel hook, bound by ImageUpscalerController while a job
         * runs. Volatile; invoked on the main thread from the notification action.
         */
        @Volatile
        var cancelHook: (() -> Unit)? = null

        @Volatile
        private var lastProgressUpdateMs: Long = 0L

        fun start(context: Context, label: String) {
            lastProgressUpdateMs = 0L
            val intent = Intent(context, UpscaleForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            try {
                // When called from the foreground app UI, startService is safe and does not enforce
                // the strict 10s startForegroundService timeout.
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    Log.w(TAG, "FGS start denied: ${e.message}")
                }
            }
        }

        fun updateProgress(context: Context, label: String, percent: Int) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (percent in 1..99 && now - lastProgressUpdateMs < 500L) return
            lastProgressUpdateMs = now
            val intent = Intent(context, UpscaleForegroundService::class.java).apply {
                action = ACTION_PROGRESS
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
            }
            runCatching { context.startService(intent) }
        }

        fun notifyComplete(context: Context, label: String, resultPath: String? = null) {
            val intent = Intent(context, UpscaleForegroundService::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_RESULT_PATH, resultPath)
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, UpscaleForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Promote immediately in onCreate to satisfy any startForegroundService() contract
        startAsForeground("AI Upscaling", -1)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "AI upscale"
                startAsForeground(label, -1)
            }
            ACTION_PROGRESS -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "AI upscale"
                val pct = intent.getIntExtra(EXTRA_PERCENT, -1)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, buildNotification(label, pct))
            }
            ACTION_CANCEL -> {
                cancelHook?.invoke()
                stopSelfSafely(removeNotification = true)
            }
            ACTION_COMPLETE -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Image ready"
                val resultPath = intent.getStringExtra(EXTRA_RESULT_PATH)
                showCompletionNotification(label, resultPath)
                stopSelfSafely(removeNotification = false)
            }
            else -> stopSelfSafely()
        }
        // Do NOT resurrect: a killed upscale is surfaced honestly, not re-run
        // blindly against a possibly-dead source Uri.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelHook = null
        super.onDestroy()
    }

    private fun startAsForeground(label: String, percent: Int = -1) {
        val notification = buildNotification(label, percent)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground mediaProcessing failed: ${e.message}; attempting fallback")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Fallback startForeground failed: ${e2.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (_: Exception) {
                    // Do not call stopSelf() here if startForeground failed; let the service run
                    // without dying and taking down the process.
                }
            }
        }
    }

    private fun buildNotification(label: String, percent: Int = -1): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, UpscaleForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = if (percent in 0..100) {
            "Processing on-device ($percent%) — safe to switch apps"
        } else {
            "Running on-device. Progress continues while you navigate."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame — $label")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download) // neutral system icon; no new assets
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI Upscaling",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress for on-device AI image upscaling jobs"
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun showCompletionNotification(label: String, resultPath: String?) {
        val openIntent = Intent(this, com.veilframe.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_navigate_destination", "IMAGE_UPSCALER")
            if (resultPath != null) putExtra("extra_media_path", resultPath)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            3,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Image Upscale Finished")
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

    private fun stopSelfSafely(removeNotification: Boolean = true) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(removeNotification)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }
}
