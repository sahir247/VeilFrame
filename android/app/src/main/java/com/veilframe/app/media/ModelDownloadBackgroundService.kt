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
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.cv.segmentation.rembg.RembgModelDownloadManager
import com.veilframe.app.cv.segmentation.rembg.RembgModelRepository
import com.veilframe.app.upscale.download.ModelDownloadManager
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground Service for background AI neural model downloads (Upscaler & Rembg).
 * Features:
 * - Survives activity destruction / backgrounding
 * - Silent in foreground: notification only appears when "Run in Background" is selected or dialog is closed
 * - Linear progress bar with model name and Cancel action
 * - Concise, clean notification text (no clutter or unnecessary filler text)
 */
class ModelDownloadBackgroundService : Service() {

    interface DownloadListener {
        fun onProgress(modelId: String, downloaded: Long, total: Long, percent: Int, speedBytesPerSec: Long)
        fun onVerifying(modelId: String)
        fun onSuccess(modelId: String, modelType: String)
        fun onError(modelId: String, error: String)
        fun onCancelled(modelId: String)
    }

    companion object {
        private const val TAG = "VeilFrame.ModelFgs"
        const val CHANNEL_ID = "veilframe_model_downloads"
        const val NOTIFICATION_ID = 1202

        const val ACTION_START_DOWNLOAD = "com.veilframe.action.START_MODEL_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.veilframe.action.CANCEL_MODEL_DOWNLOAD"
        const val ACTION_PROMOTE_TO_BACKGROUND = "com.veilframe.action.PROMOTE_MODEL_DOWNLOAD_TO_BACKGROUND"

        const val EXTRA_MODEL_TYPE = "extra_model_type" // "UPSCALE" or "REMBG"
        const val EXTRA_MODEL_ID = "extra_model_id"
        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_RUN_IN_BACKGROUND = "extra_run_in_background"

        val listeners = CopyOnWriteArraySet<DownloadListener>()

        private val activeDownloads = ConcurrentHashMap<String, String>() // modelId -> modelName

        fun addListener(listener: DownloadListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: DownloadListener) {
            listeners.remove(listener)
        }

        fun isDownloading(modelId: String): Boolean = activeDownloads.containsKey(modelId)

        fun isAnyDownloading(): Boolean = activeDownloads.isNotEmpty()

        fun getActiveModelNames(): List<String> = activeDownloads.values.toList()

        fun startDownload(
            context: Context,
            modelType: String,
            modelId: String,
            modelName: String,
            runInBackground: Boolean = false
        ) {
            val intent = Intent(context, ModelDownloadBackgroundService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_TYPE, modelType)
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_RUN_IN_BACKGROUND, runInBackground)
            }
            try {
                if (runInBackground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        androidx.core.content.ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start model download service: ${e.message}")
            }
        }

        fun promoteToBackground(context: Context) {
            val intent = Intent(context, ModelDownloadBackgroundService::class.java).apply {
                action = ACTION_PROMOTE_TO_BACKGROUND
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to promote model download service: ${e.message}")
            }
        }

        fun cancelDownload(context: Context, modelId: String? = null) {
            val intent = Intent(context, ModelDownloadBackgroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                if (modelId != null) {
                    putExtra(EXTRA_MODEL_ID, modelId)
                }
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private var lastNotificationUpdateMs: Long = 0L

    @Volatile
    private var isBackgroundMode: Boolean = false

    @Volatile
    private var currentModelName: String = "Model"

    @Volatile
    private var lastDownloaded: Long = 0L

    @Volatile
    private var lastTotalBytes: Long = 0L

    @Volatile
    private var lastPercent: Int = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // CV Governance: Verify CvRuntime native gate availability for downstream model consumers
        if (!CvRuntime.isNativeAvailable) {
            Log.d(TAG, "CvRuntime native library not yet initialized; continuing background model transfer")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "UPSCALE"
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: ""
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val runInBackground = intent.getBooleanExtra(EXTRA_RUN_IN_BACKGROUND, false)
                currentModelName = modelName

                if (runInBackground) {
                    isBackgroundMode = true
                    startAsForeground("Preparing $modelName…", 0, isIndeterminate = true)
                }

                if (modelId.isNotEmpty()) {
                    executeDownload(modelType, modelId, modelName)
                }
            }
            ACTION_PROMOTE_TO_BACKGROUND -> {
                isBackgroundMode = true
                if (isAnyDownloading()) {
                    val label = if (lastTotalBytes > 0) {
                        "$currentModelName: ${formatBytes(lastDownloaded)} / ${formatBytes(lastTotalBytes)} ($lastPercent%)"
                    } else if (lastDownloaded > 0) {
                        "$currentModelName: ${formatBytes(lastDownloaded)} downloaded"
                    } else {
                        "Downloading $currentModelName…"
                    }
                    startAsForeground(
                        label,
                        lastPercent,
                        isIndeterminate = (lastTotalBytes <= 0L)
                    )
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                handleCancellation(modelId)
            }
            else -> {
                if (!isAnyDownloading()) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        activeDownloads.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleCancellation(modelId: String?) {
        if (modelId != null) {
            downloadJobs[modelId]?.cancel()
            downloadJobs.remove(modelId)
            activeDownloads.remove(modelId)
            listeners.forEach { it.onCancelled(modelId) }
        } else {
            downloadJobs.values.forEach { it.cancel() }
            downloadJobs.clear()
            val ids = activeDownloads.keys().toList()
            activeDownloads.clear()
            ids.forEach { id -> listeners.forEach { it.onCancelled(id) } }
        }

        if (!isAnyDownloading()) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(NOTIFICATION_ID)
            stopSelfSafely()
        }
    }

    private fun executeDownload(modelType: String, modelId: String, modelName: String) {
        if (activeDownloads.containsKey(modelId)) return
        activeDownloads[modelId] = modelName
        currentModelName = modelName

        val job = serviceScope.launch {
            try {
                if (modelType.equals("REMBG", ignoreCase = true)) {
                    downloadRembgModel(modelId, modelName)
                } else {
                    downloadUpscaleModel(modelId, modelName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $modelName: ${e.message}", e)
                activeDownloads.remove(modelId)
                downloadJobs.remove(modelId)
                listeners.forEach { it.onError(modelId, e.localizedMessage ?: "Download failed") }
                if (isBackgroundMode) {
                    showDownloadFailedNotification(modelName, e.localizedMessage ?: "Download failed")
                }
                if (!isAnyDownloading()) {
                    stopSelfSafely(removeNotification = false)
                }
            }
        }
        downloadJobs[modelId] = job
    }

    private suspend fun downloadUpscaleModel(modelId: String, modelName: String) {
        val repo = UpscaleModelRepository(applicationContext)
        val downloadMgr = ModelDownloadManager(applicationContext, repo)
        val model = UpscaleModelRegistry.getModelById(modelId)
            ?: throw IllegalArgumentException("Unknown upscale model: $modelId")

        val result = downloadMgr.downloadModel(model, object : ModelDownloadManager.DownloadListener {
            override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
                lastDownloaded = downloadedBytes
                lastTotalBytes = totalBytes
                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                lastPercent = percent

                listeners.forEach { it.onProgress(modelId, downloadedBytes, totalBytes, percent, speedBytesPerSec) }

                if (isBackgroundMode) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotificationUpdateMs >= 350L || percent == 100) {
                        lastNotificationUpdateMs = now
                        updateProgressNotification(modelName, downloadedBytes, totalBytes, percent)
                    }
                }
            }

            override fun onVerifying() {
                listeners.forEach { it.onVerifying(modelId) }
                if (isBackgroundMode) {
                    val notification = buildProgressNotification("Verifying $modelName…", 100, isIndeterminate = true)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, notification)
                }
            }

            override fun onSuccess(m: UpscaleModel, destinationFile: File) {
                activeDownloads.remove(modelId)
                downloadJobs.remove(modelId)
                listeners.forEach { it.onSuccess(modelId, "UPSCALE") }
                if (isBackgroundMode) {
                    showDownloadCompleteNotification(modelName)
                }
                if (!isAnyDownloading()) {
                    stopSelfSafely(removeNotification = false)
                }
            }

            override fun onError(error: String) {
                activeDownloads.remove(modelId)
                downloadJobs.remove(modelId)
                listeners.forEach { it.onError(modelId, error) }
                if (isBackgroundMode) {
                    showDownloadFailedNotification(modelName, error)
                }
                if (!isAnyDownloading()) {
                    stopSelfSafely(removeNotification = false)
                }
            }
        })

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: Exception("Upscale model download failed")
        }
    }

    private suspend fun downloadRembgModel(modelId: String, modelName: String) {
        val repo = RembgModelRepository(applicationContext)
        val downloadMgr = RembgModelDownloadManager(applicationContext, repo)
        val model = RembgModel.fromId(modelId)

        val result = downloadMgr.downloadModel(model, object : RembgModelDownloadManager.DownloadListener {
            override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
                lastDownloaded = downloadedBytes
                lastTotalBytes = totalBytes
                val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                lastPercent = percent

                listeners.forEach { it.onProgress(modelId, downloadedBytes, totalBytes, percent, speedBytesPerSec) }

                if (isBackgroundMode) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastNotificationUpdateMs >= 350L || percent == 100) {
                        lastNotificationUpdateMs = now
                        updateProgressNotification(modelName, downloadedBytes, totalBytes, percent)
                    }
                }
            }

            override fun onSuccess(m: RembgModel, destinationFile: File) {
                activeDownloads.remove(modelId)
                downloadJobs.remove(modelId)
                listeners.forEach { it.onSuccess(modelId, "REMBG") }
                if (isBackgroundMode) {
                    showDownloadCompleteNotification(modelName)
                }
                if (!isAnyDownloading()) {
                    stopSelfSafely(removeNotification = false)
                }
            }

            override fun onError(error: String) {
                activeDownloads.remove(modelId)
                downloadJobs.remove(modelId)
                listeners.forEach { it.onError(modelId, error) }
                if (isBackgroundMode) {
                    showDownloadFailedNotification(modelName, error)
                }
                if (!isAnyDownloading()) {
                    stopSelfSafely(removeNotification = false)
                }
            }
        })

        if (result.isFailure) {
            throw result.exceptionOrNull() ?: Exception("Rembg model download failed")
        }
    }

    private fun startAsForeground(label: String, percent: Int, isIndeterminate: Boolean) {
        val notification = buildProgressNotification(label, percent, isIndeterminate)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    private fun updateProgressNotification(modelName: String, downloaded: Long, total: Long, percent: Int) {
        if (!isBackgroundMode) return
        val label = if (total > 0) {
            "$modelName: ${formatBytes(downloaded)} / ${formatBytes(total)} ($percent%)"
        } else {
            "$modelName: ${formatBytes(downloaded)} downloaded"
        }
        val notification = buildProgressNotification(label, percent, isIndeterminate = (total <= 0))
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildProgressNotification(
        contentText: String,
        percent: Int,
        isIndeterminate: Boolean
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ModelDownloadBackgroundService::class.java).setAction(ACTION_CANCEL_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame Models — Downloading")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), isIndeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    private fun showDownloadCompleteNotification(modelName: String) {
        if (!isBackgroundMode) return
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VeilFrame Model Ready")
            .setContentText("$modelName ready to use.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun showDownloadFailedNotification(modelName: String, errorMsg: String) {
        if (!isBackgroundMode) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$modelName Download Failed")
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
                    "Model Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Download progress and status for AI neural models"
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
