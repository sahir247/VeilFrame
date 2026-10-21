package com.veilframe.app.updates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Foreground Service for background APK update and repair downloads.
 * Features:
 * - Survives activity destruction / backgrounding
 * - Native system notification with linear progress bar and Cancel action
 * - Zero-bypass cryptographic SHA-256 integrity check and publisher certificate verification
 * - Dynamic completion notification with direct Install action
 */
class AppUpdateDownloadService : Service() {

    interface DownloadListener {
        fun onProgress(downloaded: Long, total: Long, percent: Int)
        fun onComplete(apkFile: File)
        fun onError(message: String)
        fun onCancelled()
    }

    companion object {
        private const val TAG = "VeilFrame.UpdateFgs"
        const val CHANNEL_ID = "veilframe_app_updates"
        const val NOTIFICATION_ID = 1201

        const val ACTION_START_DOWNLOAD = "com.veilframe.action.START_UPDATE_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.veilframe.action.CANCEL_UPDATE_DOWNLOAD"
        const val ACTION_INSTALL_UPDATE = "com.veilframe.action.INSTALL_UPDATE"

        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_APK_NAME = "extra_apk_name"
        const val EXTRA_EXPECTED_SHA256 = "extra_expected_sha256"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"
        const val EXTRA_IS_REPAIR = "extra_is_repair"
        const val EXTRA_APK_PATH = "extra_apk_path"

        @Volatile
        var downloadListener: DownloadListener? = null

        @Volatile
        var isDownloading: Boolean = false
            private set

        @Volatile
        var currentStagingFile: File? = null
            private set

        @Volatile
        var lastCompletedApk: File? = null
            private set

        fun startDownload(
            context: Context,
            downloadUrl: String,
            apkName: String,
            expectedSha256: String,
            totalBytes: Long,
            isRepair: Boolean = false
        ) {
            val intent = Intent(context, AppUpdateDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_APK_NAME, apkName)
                putExtra(EXTRA_EXPECTED_SHA256, expectedSha256)
                putExtra(EXTRA_TOTAL_BYTES, totalBytes)
                putExtra(EXTRA_IS_REPAIR, isRepair)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start update download service: ${e.message}")
            }
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, AppUpdateDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(intent)
            }
        }

        /**
         * Validates APK package archive and verified signer certificate identity.
         */
        fun verifyApkSignatureAndIdentity(context: Context, archiveFile: File): String? {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val archiveInfo = pm.getPackageArchiveInfo(archiveFile.absolutePath, flags)
                ?: return "The downloaded file could not be parsed as a valid Android package archive."

            if (archiveInfo.packageName != context.packageName) {
                return "Package identity mismatch: Expected '${context.packageName}', found '${archiveInfo.packageName}'."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val installedCerts = mutableSetOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val appInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = appInfo.signingInfo
                    if (signingInfo != null) {
                        val sigs = if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.signingCertificateHistory
                        }
                        sigs?.forEach { sig ->
                            installedCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val appInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    appInfo.signatures?.forEach { sig ->
                        installedCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                    }
                }
            } catch (e: Exception) {
                return "Failed to inspect installed app signing certificates: ${e.message}"
            }

            val archiveCerts = mutableSetOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signingInfo = archiveInfo.signingInfo
                    if (signingInfo != null) {
                        val sigs = if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.signingCertificateHistory
                        }
                        sigs?.forEach { sig ->
                            archiveCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    archiveInfo.signatures?.forEach { sig ->
                        archiveCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                    }
                }
            } catch (e: Exception) {
                return "Failed to inspect update archive signing certificates: ${e.message}"
            }

            if (archiveCerts.isEmpty()) {
                return "Security rejection: The downloaded update archive contains no valid signing certificates."
            }

            val matchesInstalled = archiveCerts.any { it in installedCerts }
            val matchesPinnedProduction = archiveCerts.contains(AppUpdateManager.PINNED_PRODUCTION_CERT_SHA256)

            if (!matchesInstalled && !matchesPinnedProduction) {
                return "Publisher certificate mismatch! The update is signed by an untrusted key."
            }

            return null
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground("Preparing download…", 0, isIndeterminate = true, isRepair = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
                val apkName = intent.getStringExtra(EXTRA_APK_NAME) ?: "VeilFrame-update.apk"
                val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256) ?: ""
                val totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L)
                val isRepair = intent.getBooleanExtra(EXTRA_IS_REPAIR, false)
                executeDownload(url, apkName, expectedSha256, totalBytes, isRepair)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                handleCancellation()
            }
            else -> {
                if (!isDownloading) stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDownloading = false
        downloadJob?.cancel()
        serviceScope.cancel()
        currentStagingFile?.delete()
        currentStagingFile = null
        super.onDestroy()
    }

    private fun handleCancellation() {
        isDownloading = false
        downloadJob?.cancel()
        currentStagingFile?.delete()
        currentStagingFile = null
        downloadListener?.onCancelled()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        stopSelfSafely()
    }

    private fun executeDownload(
        downloadUrl: String,
        apkName: String,
        expectedSha256: String,
        totalBytesExpected: Long,
        isRepair: Boolean
    ) {
        if (isDownloading) return
        isDownloading = true

        val safeApkName = AppUpdateManager.sanitizeApkFilename(apkName)
        val targetApkFile = File(cacheDir, safeApkName)
        val stagingFile = File(cacheDir, "update_staging_${System.currentTimeMillis()}.apk")
        currentStagingFile = stagingFile

        startAsForeground(
            if (isRepair) "Downloading repair package…" else "Downloading update…",
            percent = 0,
            isIndeterminate = (totalBytesExpected <= 0L),
            isRepair = isRepair
        )

        downloadJob = serviceScope.launch {
            try {
                var currentUrl = downloadUrl
                var connection: HttpURLConnection? = null
                var redirectCount = 0

                while (redirectCount < 5) {
                    if (!AppUpdateManager.isAllowedUpdateUrl(currentUrl)) {
                        throw SecurityException("Untrusted download redirect destination: $currentUrl")
                    }

                    val u = URL(currentUrl)
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "${com.veilframe.app.util.AppVersionProvider.getUserAgent(this@AppUpdateDownloadService)}-Updater")
                    }
                    val status = conn.responseCode
                    if (status in 300..399) {
                        val redirectLoc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (!redirectLoc.isNullOrEmpty()) {
                            currentUrl = redirectLoc
                            redirectCount++
                            continue
                        }
                    }
                    connection = conn
                    break
                }

                val conn = connection ?: throw IOException("Failed to establish secure download connection")
                if (conn.responseCode !in 200..299) {
                    throw IOException("Server returned HTTP ${conn.responseCode}: ${conn.responseMessage}")
                }

                val totalLength = if (conn.contentLengthLong > 0) conn.contentLengthLong else totalBytesExpected
                val digest = MessageDigest.getInstance("SHA-256")

                conn.inputStream.use { input ->
                    stagingFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isDownloading) {
                                stagingFile.delete()
                                return@use
                            }
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val percent = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100) else 0
                            downloadListener?.onProgress(downloaded, totalLength, percent)

                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastNotificationUpdateMs >= 350L || percent == 100) {
                                lastNotificationUpdateMs = now
                                updateProgressNotification(downloaded, totalLength, percent, isRepair)
                            }
                        }
                    }
                }

                if (!isDownloading) {
                    stagingFile.delete()
                    return@launch
                }

                if (totalLength > 0 && stagingFile.length() != totalLength) {
                    val actualLen = stagingFile.length()
                    stagingFile.delete()
                    throw IOException("Truncated download: received $actualLen bytes, expected $totalLength bytes")
                }

                // Cryptographic SHA-256 verification
                val cleanExpectedSha256 = expectedSha256.trim().lowercase(Locale.ROOT)
                val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (cleanExpectedSha256.isNotEmpty()) {
                    if (!computedSha256.equals(cleanExpectedSha256, ignoreCase = true)) {
                        stagingFile.delete()
                        throw SecurityException("SHA-256 verification failed (expected $cleanExpectedSha256, got $computedSha256)")
                    }
                }

                // Publisher certificate & package identity verification
                val sigError = verifyApkSignatureAndIdentity(this@AppUpdateDownloadService, stagingFile)
                if (sigError != null) {
                    stagingFile.delete()
                    throw SecurityException(sigError)
                }

                // Atomic rename
                if (targetApkFile.exists()) targetApkFile.delete()
                val renamed = stagingFile.renameTo(targetApkFile)
                val finalApk = if (renamed) {
                    targetApkFile
                } else {
                    stagingFile.copyTo(targetApkFile, overwrite = true)
                    stagingFile.delete()
                    targetApkFile
                }

                lastCompletedApk = finalApk
                isDownloading = false
                currentStagingFile = null

                downloadListener?.onComplete(finalApk)
                showDownloadCompleteNotification(finalApk, isRepair)
                stopSelfSafely(removeNotification = false)

            } catch (e: Exception) {
                isDownloading = false
                stagingFile.delete()
                currentStagingFile = null
                Log.e(TAG, "Download failed: ${e.message}", e)
                downloadListener?.onError(e.localizedMessage ?: "Download failed")
                showDownloadFailedNotification(e.localizedMessage ?: "Download failed", isRepair)
                stopSelfSafely(removeNotification = false)
            }
        }
    }

    private fun startAsForeground(label: String, percent: Int, isIndeterminate: Boolean, isRepair: Boolean) {
        val notification = buildProgressNotification(label, percent, isIndeterminate, isRepair)
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

    private fun updateProgressNotification(downloaded: Long, total: Long, percent: Int, isRepair: Boolean) {
        val label = if (total > 0) {
            "${formatBytes(downloaded)} / ${formatBytes(total)} ($percent%) • Safe to close app"
        } else {
            "${formatBytes(downloaded)} downloaded • Safe to close app"
        }
        val notification = buildProgressNotification(label, percent, isIndeterminate = (total <= 0), isRepair = isRepair)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildProgressNotification(
        contentText: String,
        percent: Int,
        isIndeterminate: Boolean,
        isRepair: Boolean
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AppUpdateDownloadService::class.java).setAction(ACTION_CANCEL_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRepair) "VeilFrame Repair — Downloading" else "VeilFrame Update — Downloading"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), isIndeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
    }

    private fun showDownloadCompleteNotification(apkFile: File, isRepair: Boolean) {
        val installPendingIntent = createInstallPendingIntent(apkFile)

        val title = if (isRepair) "VeilFrame Repair Ready" else "VeilFrame Update Ready"
        val content = "Downloaded and verified. Tap Install to proceed."

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(installPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.stat_sys_download_done, "Install", installPendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun showDownloadFailedNotification(errorMsg: String, isRepair: Boolean) {
        val title = if (isRepair) "Repair Download Failed" else "Update Download Failed"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createInstallPendingIntent(apkFile: File): PendingIntent {
        val apkUri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "App Updates & Repairs",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Download progress and installation status for VeilFrame updates"
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
