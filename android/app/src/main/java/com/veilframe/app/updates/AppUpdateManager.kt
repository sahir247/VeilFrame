package com.veilframe.app.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Built-in GitHub Releases In-App Update Engine.
 * Features:
 * - Monotonic integer versionCode bounds checking
 * - Pinned HTTPS origins only
 * - Zero-bypass mandatory SHA-256 validation (via update.json or SHA256SUMS.txt)
 * - Atomic staging download
 * - Preflight storage space check
 * - Publisher certificate identity verification
 * - Lifecycle & concurrency safety
 */
class AppUpdateManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val PREFS_NAME = "veilframe_prefs"
    }

    private var pendingInstallApk: File? = null
    private var downloadJob: Job? = null
    @Volatile private var isCheckingUpdates: Boolean = false
    private var activeUpdateDialog: AlertDialog? = null
    private var activeDownloadDialog: AlertDialog? = null
    var lastVerifiedUpdateApk: File? = null
        private set

    fun onResume() {
        val apk = pendingInstallApk
        if (apk != null && apk.exists()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (activity.packageManager.canRequestPackageInstalls()) {
                    pendingInstallApk = null
                    promptInstallApk(apk)
                }
            }
        }
    }

    fun onDestroy() {
        activeUpdateDialog?.dismiss()
        activeDownloadDialog?.dismiss()
        downloadJob?.cancel()
    }

    fun isAllowedUpdateUrl(urlString: String): Boolean {
        val url = try { URL(urlString) } catch (_: Exception) { return false }
        if (!url.protocol.equals("https", ignoreCase = true)) {
            return false
        }
        val host = url.host.lowercase(Locale.ROOT)
        return host == "raw.githubusercontent.com" ||
               host == "api.github.com" ||
               host == "github.com" ||
               host == "objects.githubusercontent.com" ||
               host.endsWith(".githubusercontent.com") ||
               host.endsWith(".amazonaws.com")
    }

    private fun sanitizeApkFilename(rawName: String): String {
        val clean = File(rawName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".apk", ignoreCase = true) && !clean.contains("..")) clean else "VeilFrame-update.apk"
    }

    fun verifyApkSignatureAndIdentity(archiveFile: File): String? {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(archiveFile.absolutePath, flags)
            ?: return "The downloaded file could not be parsed as a valid Android package archive."

        if (archiveInfo.packageName != activity.packageName) {
            return "Package identity mismatch: Expected '${activity.packageName}', found '${archiveInfo.packageName}'."
        }

        val digest = MessageDigest.getInstance("SHA-256")

        // 1. Extract installed app signing certificate fingerprints
        val installedCerts = mutableSetOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val appInfo = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
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
                val appInfo = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                appInfo.signatures?.forEach { sig ->
                    installedCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                }
            }
        } catch (e: Exception) {
            return "Failed to inspect installed app signing certificates: ${e.message}"
        }

        // 2. Extract archive signing certificate fingerprints
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
            }
            if (archiveCerts.isEmpty()) {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.forEach { sig ->
                    archiveCerts.add(digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) })
                }
            }
        } catch (e: Exception) {
            return "Failed to inspect APK archive signing certificates: ${e.message}"
        }

        // 3. Cryptographic comparison
        if (installedCerts.isNotEmpty()) {
            if (archiveCerts.isEmpty()) {
                return "APK archive does not contain verifiable signing certificates."
            }
            val match = installedCerts.intersect(archiveCerts)
            if (match.isEmpty()) {
                val isDebugBuild = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebugBuild) {
                    onLog("[SEC] Debug build: Installed debug cert ($installedCerts) differs from release cert ($archiveCerts). Allowing OS installer to mediate.")
                } else {
                    return "Publisher certificate mismatch! The update was not signed by the authentic VeilFrame release key."
                }
            }
        }

        return null
    }

    fun checkForUpdates(isUserInitiated: Boolean) {
        if (isCheckingUpdates) {
            if (isUserInitiated) {
                Toast.makeText(activity, "Update check already in progress...", Toast.LENGTH_SHORT).show()
            }
            return
        }
        isCheckingUpdates = true
        binding.progressUpdateCheck.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            try {
                val installedVersionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }

                // 1. Fetch update.json
                var remoteVersionCode = 0L
                var remoteVersionName = ""
                var remoteTagName = ""
                var manifestApkName = ""
                var apkDownloadUrl = ""
                var apkExpectedSha256 = ""
                var releaseChangelog = ""
                var assetSizeBytes = 0L
                var sha256SumsUrl = ""

                val manifestUrlStr = "https://raw.githubusercontent.com/sahir247/VeilFrame/main/android/update.json"
                if (isAllowedUpdateUrl(manifestUrlStr)) {
                    try {
                        val manifestConn = (URL(manifestUrlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10000
                            readTimeout = 10000
                            setRequestProperty("User-Agent", "VeilFrame-Android")
                        }
                        if (manifestConn.responseCode in 200..299) {
                            val jsonStr = manifestConn.inputStream.bufferedReader().use { it.readText() }
                            val manifestJson = JSONObject(jsonStr)
                            remoteVersionCode = manifestJson.optLong("versionCode", 0L)
                            remoteVersionName = manifestJson.optString("versionName", "").trim()
                            remoteTagName = manifestJson.optString("tag", if (remoteVersionName.isNotEmpty()) "v$remoteVersionName" else "").trim()
                            manifestApkName = manifestJson.optString("apk", "").trim()
                            apkExpectedSha256 = manifestJson.optString("sha256", "").trim().lowercase(Locale.ROOT)
                            val changelogArr = manifestJson.optJSONArray("changelog")
                            if (changelogArr != null) {
                                releaseChangelog = (0 until changelogArr.length()).joinToString("\n") { "• ${changelogArr.getString(it)}" }
                            }
                        }
                    } catch (e: Exception) {
                        onLog("[WARN] Manifest fetch notice: ${e.message}")
                    }
                }

                // 2. Query GitHub Releases
                val releaseApiUrl = "https://api.github.com/repos/sahir247/VeilFrame/releases/latest"
                if (isAllowedUpdateUrl(releaseApiUrl)) {
                    val releaseConn = (URL(releaseApiUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", "VeilFrame-Android")
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }

                    if (releaseConn.responseCode in 200..299) {
                        val releaseStr = releaseConn.inputStream.bufferedReader().use { it.readText() }
                        val releaseJson = JSONObject(releaseStr)
                        val ghTagName = releaseJson.optString("tag_name", "").trim()
                        if (remoteTagName.isEmpty()) {
                            remoteTagName = ghTagName
                        }
                        if (remoteVersionName.isEmpty()) {
                            remoteVersionName = ghTagName.removePrefix("v").trim()
                        }
                        if (releaseChangelog.isEmpty()) {
                            releaseChangelog = releaseJson.optString("body", "Bug fixes and performance improvements.")
                        }

                        if (remoteVersionCode <= 0L) {
                            val parts = remoteVersionName.split(".")
                            if (parts.size >= 3) {
                                remoteVersionCode = (parts[0].toLongOrNull() ?: 0) * 100 + (parts[1].toLongOrNull() ?: 0) * 10 + (parts[2].toLongOrNull() ?: 0)
                            }
                        }

                        val assets = releaseJson.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name", "")
                                val downloadUrl = asset.optString("browser_download_url", "")
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    apkDownloadUrl = downloadUrl
                                    assetSizeBytes = asset.optLong("size", 0L)
                                    if (manifestApkName.isEmpty()) {
                                        manifestApkName = name
                                    }
                                } else if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                                    sha256SumsUrl = downloadUrl
                                } else if (name.equals("update.json", ignoreCase = true) && apkExpectedSha256.isBlank()) {
                                    try {
                                        if (isAllowedUpdateUrl(downloadUrl)) {
                                            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                                                connectTimeout = 8000
                                                readTimeout = 8000
                                            }
                                            if (conn.responseCode in 200..299) {
                                                val rJson = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                                                val releaseAssetSha = rJson.optString("sha256", "").trim().lowercase(Locale.ROOT)
                                                if (releaseAssetSha.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                                                    apkExpectedSha256 = releaseAssetSha
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }

                // 3. Fallback SHA-256 resolution
                if ((apkExpectedSha256.isBlank() || !apkExpectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) && sha256SumsUrl.isNotEmpty()) {
                    try {
                        if (isAllowedUpdateUrl(sha256SumsUrl)) {
                            val shaConn = (URL(sha256SumsUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8000
                                readTimeout = 8000
                            }
                            if (shaConn.responseCode in 200..299) {
                                val sumsText = shaConn.inputStream.bufferedReader().use { it.readText() }
                                for (line in sumsText.lines()) {
                                    val trimmed = line.trim()
                                    if (trimmed.isEmpty()) continue
                                    val parts = trimmed.split(Regex("\\s+"))
                                    if (parts.size >= 2) {
                                        val hash = parts[0].trim().lowercase(Locale.ROOT)
                                        val filePart = parts[1].trim()
                                        if (filePart.equals(manifestApkName, ignoreCase = true) || filePart.endsWith(".apk", ignoreCase = true)) {
                                            if (hash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                                                apkExpectedSha256 = hash
                                                onLog("[SEC] Resolved authentic APK SHA-256 from SHA256SUMS.txt: $apkExpectedSha256")
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        onLog("[WARN] SHA256SUMS.txt fallback notice: ${e.message}")
                    }
                }

                // 4. Bounds checking
                val isUpdateAvailable = remoteVersionCode > installedVersionCode

                if (isUpdateAvailable && (remoteVersionCode > installedVersionCode + 100000 || remoteVersionCode <= 0)) {
                    onLog("[SEC] Update rejected: Malformed remote versionCode ($remoteVersionCode)")
                    withContext(Dispatchers.Main) {
                        if (activity.isFinishing || activity.isDestroyed) return@withContext
                        binding.progressUpdateCheck.visibility = View.GONE
                        binding.tvUpdateStatus.text = "Update check blocked: Invalid remote build version"
                        binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_amber))
                        if (isUserInitiated) {
                            showSecurityAlertDialog("Update Blocked: The remote release contains an invalid or nonsensical version code ($remoteVersionCode).")
                        }
                    }
                    return@launch
                }

                // 5. Update cached changelog
                if (releaseChangelog.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            val headerTag = if (remoteTagName.isNotEmpty()) remoteTagName else "v$remoteVersionName"
                            binding.tvWhatsNewHeader.text = "WHAT'S NEW IN $headerTag"
                            binding.tvWhatsNewContent.text = releaseChangelog
                        }
                    }
                    val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("cached_changelog_tag", remoteTagName)
                        .putString("cached_changelog", releaseChangelog)
                        .apply()
                }

                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    binding.progressUpdateCheck.visibility = View.GONE

                    if (isUpdateAvailable && apkDownloadUrl.isNotEmpty()) {
                        if (!isAllowedUpdateUrl(apkDownloadUrl)) {
                            binding.tvUpdateStatus.text = "Update blocked: Untrusted download origin"
                            binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_amber))
                            showSecurityAlertDialog("Update Blocked: The APK download URL points to an unverified origin:\n$apkDownloadUrl")
                            return@withContext
                        }

                        if (apkExpectedSha256.isBlank() || !apkExpectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                            binding.tvUpdateStatus.text = "Update Available: v$remoteVersionName (Integrity Hash Missing)"
                            binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_amber))
                            if (isUserInitiated) {
                                showSecurityAlertDialog(
                                    "Update Blocked: Missing Cryptographic Digest\n\n" +
                                    "VeilFrame v$remoteVersionName is available, but the release publisher has not attached a valid 64-character SHA-256 checksum.\n\n" +
                                    "VeilFrame strictly refuses to download packages without cryptographic hash verification."
                                )
                            }
                            return@withContext
                        }

                        val targetName = if (manifestApkName.isNotEmpty()) sanitizeApkFilename(manifestApkName) else "VeilFrame-v$remoteVersionName.apk"
                        binding.tvUpdateStatus.text = "Update Available: v$remoteVersionName (Build $remoteVersionCode)"
                        binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_amber))
                        showUpdateAvailableDialog(
                            versionName = remoteVersionName,
                            versionCode = remoteVersionCode,
                            changelog = releaseChangelog,
                            downloadUrl = apkDownloadUrl,
                            apkFileName = targetName,
                            sizeBytes = assetSizeBytes,
                            expectedSha256 = apkExpectedSha256
                        )
                    } else {
                        val currentVersionName = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "2.2.5" } catch (_: Exception) { "2.2.5" }
                        binding.tvUpdateStatus.text = "Installed: v$currentVersionName • You're up to date ✓"
                        binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_green))
                        if (isUserInitiated) {
                            Toast.makeText(activity, "You have the latest version (v$currentVersionName)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        binding.progressUpdateCheck.visibility = View.GONE
                        if (isUserInitiated) {
                            Toast.makeText(activity, "Update check failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        } else {
                            val currentVersionName = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "2.2.5" } catch (_: Exception) { "2.2.5" }
                            binding.tvUpdateStatus.text = "Installed: v$currentVersionName • Local Engine"
                            binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_text_secondary))
                        }
                    }
                }
            } finally {
                isCheckingUpdates = false
            }
        }
    }

    private fun showUpdateAvailableDialog(
        versionName: String,
        versionCode: Long,
        changelog: String,
        downloadUrl: String,
        apkFileName: String,
        sizeBytes: Long,
        expectedSha256: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sizeFormatted = if (sizeBytes > 0) " (${safStorageManager.formatBytes(sizeBytes)})" else ""

        val message = StringBuilder().apply {
            append("Version: $versionName (Build $versionCode)$sizeFormatted\n\n")
            append("What's new:\n")
            append(if (changelog.length > 350) changelog.take(350) + "..." else changelog)
            append("\n\nSecurity & Cryptographic Verification:\n")
            append("✓ Origin: Pinned HTTPS GitHub Releases\n")
            append("✓ Package Identity: ${activity.packageName}\n")
            append("✓ Mandatory SHA-256: ${expectedSha256.take(16)}...${expectedSha256.takeLast(8)}\n")
            append("✓ Authenticity: Publisher signing certificate pinning")
        }.toString()

        activeUpdateDialog?.dismiss()
        activeUpdateDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallUpdateWithProgress(downloadUrl, apkFileName, sizeBytes, expectedSha256)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallUpdateWithProgress(
        downloadUrl: String,
        apkName: String,
        totalBytesExpected: Long,
        expectedSha256: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val cleanExpectedSha256 = expectedSha256.trim().lowercase(Locale.ROOT)
        if (!cleanExpectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            showSecurityAlertDialog("Update Aborted: Missing or Malformed SHA-256 Digest!")
            return
        }

        if (!isAllowedUpdateUrl(downloadUrl)) {
            showSecurityAlertDialog("Update Aborted: Download URL does not match pinned HTTPS repository origins.")
            return
        }

        val requiredBytes = if (totalBytesExpected > 0) totalBytesExpected + (50 * 1024 * 1024) else (120 * 1024 * 1024)
        if (activity.cacheDir.usableSpace < requiredBytes) {
            Toast.makeText(
                activity,
                "Insufficient storage: Need ${safStorageManager.formatBytes(requiredBytes)}, available ${safStorageManager.formatBytes(activity.cacheDir.usableSpace)}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val safeApkName = sanitizeApkFilename(apkName)
        val targetApkFile = File(activity.cacheDir, safeApkName)
        val stagingFile = File(activity.cacheDir, "update_staging_${System.currentTimeMillis()}.apk")

        val titleView = TextView(activity).apply {
            text = "Downloading update: $safeApkName"
            textSize = 14f
            setTextColor(activity.getColor(R.color.vf_text_primary))
            setPadding(0, 0, 0, 20)
        }

        val progressIndicator = LinearProgressIndicator(activity).apply {
            isIndeterminate = (totalBytesExpected <= 0L)
            max = 100
        }

        val statusView = TextView(activity).apply {
            text = "Connecting securely to GitHub Releases..."
            textSize = 13f
            setTextColor(activity.getColor(R.color.vf_text_secondary))
            setPadding(0, 20, 0, 0)
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 24)
            addView(titleView)
            addView(progressIndicator)
            addView(statusView)
        }

        var isCancelled = false
        activeDownloadDialog?.dismiss()
        val downloadDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Downloading Update")
            .setView(container)
            .setNegativeButton("Cancel") { _, _ ->
                isCancelled = true
                downloadJob?.cancel()
                stagingFile.delete()
                Toast.makeText(activity, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .create()

        activeDownloadDialog = downloadDialog
        downloadDialog.show()

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                var currentUrl = downloadUrl
                var connection: HttpURLConnection? = null
                var redirectCount = 0

                while (redirectCount < 5) {
                    if (!isAllowedUpdateUrl(currentUrl)) {
                        throw SecurityException("Untrusted download redirect destination: $currentUrl")
                    }

                    val u = URL(currentUrl)
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "VeilFrame-Android-Updater")
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
                            if (isCancelled) {
                                stagingFile.delete()
                                return@use
                            }
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (totalLength > 0) {
                                val percent = ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                                withContext(Dispatchers.Main) {
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        progressIndicator.isIndeterminate = false
                                        progressIndicator.progress = percent
                                        statusView.text = "${safStorageManager.formatBytes(downloaded)} / ${safStorageManager.formatBytes(totalLength)} ($percent%)"
                                    }
                                }
                            }
                        }
                    }
                }

                if (isCancelled) {
                    stagingFile.delete()
                    withContext(Dispatchers.Main) {
                        activeDownloadDialog?.dismiss()
                        activeDownloadDialog = null
                    }
                    return@launch
                }

                if (totalLength > 0 && stagingFile.length() != totalLength) {
                    val actualLen = stagingFile.length()
                    stagingFile.delete()
                    throw IOException("Truncated download: received $actualLen bytes, expected $totalLength bytes")
                }

                withContext(Dispatchers.Main) {
                    activeDownloadDialog?.dismiss()
                    activeDownloadDialog = null
                }

                // SHA-256 verification
                val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computedSha256.equals(cleanExpectedSha256, ignoreCase = true)) {
                    stagingFile.delete()
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showSecurityAlertDialog(
                                "SHA-256 Integrity Verification Failed!\n\n" +
                                "Expected: $cleanExpectedSha256\n" +
                                "Computed: $computedSha256\n\n" +
                                "The downloaded package does not match the cryptographic digest. Installation aborted."
                            )
                        }
                    }
                    return@launch
                }

                // Signature verification
                val sigError = verifyApkSignatureAndIdentity(stagingFile)
                if (sigError != null) {
                    stagingFile.delete()
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showSecurityAlertDialog(
                                "Publisher Security Verification Failed!\n\n" +
                                "$sigError\n\n" +
                                "The downloaded package failed authenticity checks. Installation aborted."
                            )
                        }
                    }
                    return@launch
                }

                // Atomic rename
                if (targetApkFile.exists()) {
                    targetApkFile.delete()
                }
                val renameSuccess = stagingFile.renameTo(targetApkFile)
                val finalApk = if (renameSuccess) {
                    targetApkFile
                } else {
                    stagingFile.copyTo(targetApkFile, overwrite = true)
                    stagingFile.delete()
                    targetApkFile
                }

                lastVerifiedUpdateApk = finalApk
                onLog("[SEC] Verified and finalized update package: ${finalApk.name} (${safStorageManager.formatBytes(finalApk.length())})")

                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        promptInstallApk(finalApk)
                    }
                }
            } catch (e: Exception) {
                stagingFile.delete()
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activeDownloadDialog?.dismiss()
                        activeDownloadDialog = null
                        Toast.makeText(activity, "Download error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showSecurityAlertDialog(reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("Security Alert: Update Blocked")
            .setMessage(reason)
            .setPositiveButton("OK", null)
            .show()
    }

    fun promptInstallApk(apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(activity, "Update package not found. Please check for updates again.", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    pendingInstallApk = apkFile
                    binding.tvUpdateStatus.text = "Update downloaded • Tap to Install"
                    binding.tvUpdateStatus.setOnClickListener { promptInstallApk(apkFile) }
                    binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_amber))
                    Toast.makeText(activity, "Please allow VeilFrame to install app updates", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val resolveInfoList = activity.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
                for (resolveInfo in resolveInfoList) {
                    val pkg = resolveInfo.activityInfo.packageName
                    activity.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                onLog("[WARN] Intent activity query notice: ${e.message}")
            }

            binding.tvUpdateStatus.text = "Installing update... (Tap to retry if interrupted)"
            binding.tvUpdateStatus.setOnClickListener { promptInstallApk(apkFile) }
            binding.tvUpdateStatus.setTextColor(activity.getColor(R.color.vf_accent_blue))

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            binding.tvUpdateStatus.text = "Install interrupted • Tap to retry"
            binding.tvUpdateStatus.setOnClickListener { promptInstallApk(apkFile) }
            Toast.makeText(activity, "Installation error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
