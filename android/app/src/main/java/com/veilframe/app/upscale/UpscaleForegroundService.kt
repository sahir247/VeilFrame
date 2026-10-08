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

        private const val ACTION_START = "com.veilframe.action.UPSCALE_START"
        private const val ACTION_CANCEL = "com.veilframe.action.UPSCALE_CANCEL"
        private const val EXTRA_LABEL = "extra_label"

        /**
         * Cooperative cancel hook, bound by ImageUpscalerController while a job
         * runs. Volatile; invoked on the main thread from the notification action.
         */
        @Volatile
        var cancelHook: (() -> Unit)? = null

        fun start(context: Context, label: String) {
            val intent = Intent(context, UpscaleForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException etc.: the job still
                // runs in-app; log honestly and continue (never crash the flow).
                Log.w(TAG, "FGS start denied: ${e.message}")
            }
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "AI upscale"
                startAsForeground(label)
            }
            ACTION_CANCEL -> {
                cancelHook?.invoke()
                stopSelfSafely()
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

    private fun startAsForeground(label: String) {
        val notification = buildNotification(label)
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
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelfSafely()
        }
    }

    private fun buildNotification(label: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, UpscaleForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame — $label")
            .setContentText("Running on-device. Progress continues while you navigate.")
            .setSmallIcon(android.R.drawable.stat_sys_download) // neutral system icon; no new assets
            .setOngoing(true)
            .setSilent(true)
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

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }
}
