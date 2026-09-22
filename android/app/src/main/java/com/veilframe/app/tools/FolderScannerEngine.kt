package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Native Kotlin Folder Scanner and Security Audit Engine.
 * Traverses Android SAF DocumentFile trees directly on Dispatchers.IO,
 * computes streaming cryptographic SHA-256 hashes, detects leaked secrets via regex,
 * and exports injection-safe reports in HTML, JSON, Markdown, and CSV.
 */
object FolderScannerEngine {

    enum class ScanStatus {
        COMPLETE,
        PARTIAL,
        FAILED
    }

    data class ScanConfig(
        val recursive: Boolean = true,
        val calculateHashes: Boolean = true,
        val detectSecrets: Boolean = true,
        val maxFileSizeToHash: Long = 100 * 1024 * 1024L, // 100 MB
        val maxFiles: Int = 50_000,
        val maxDepth: Int = 32,
        val maxTotalBytes: Long = 10L * 1024 * 1024 * 1024L, // 10 GB
        val maxFileBytesForContentScan: Long = 5 * 1024 * 1024L, // 5 MB
        val maxFindingsPerFile: Int = 100
    )

    data class ScannedFileRecord(
        val relativePath: String,
        val name: String,
        val sizeBytes: Long,
        val sha256: String?,
        val detectedSecrets: List<String>
    )

    data class ScanResult(
        val rootDisplayName: String,
        val timestamp: String,
        val totalFiles: Int,
        val totalBytes: Long,
        val totalSecretsFound: Int,
        val status: ScanStatus = ScanStatus.COMPLETE,
        val isTruncated: Boolean = false,
        val statusReason: String? = null,
        val files: List<ScannedFileRecord>
    )

    private val SECRET_PATTERNS = listOf(
        Pattern.compile("(?i)(?:api_key|apikey|secret|token|password|auth|bearer)\\s*[:=]\\s*[\"']?([a-zA-Z0-9_\\-\\.]{16,})"),
        Pattern.compile("(?i)AIza[0-9A-Za-z\\-_]{35}"), // Google API Key
        Pattern.compile("(?i)ghp_[0-9a-zA-Z]{36}"), // GitHub Token
        Pattern.compile("(?i)AKIA[0-9A-Z]{16}"), // AWS Key ID
        Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----") // Private Key
    )

    /**
     * Scans an Android SAF Tree URI directly using ContentResolver on Dispatchers.IO,
     * with cooperative cancellation and operational boundary limits.
     */
    suspend fun scanSafTree(
        context: Context,
        treeUri: Uri,
        config: ScanConfig = ScanConfig(),
        onProgress: ((currentFile: String, count: Int) -> Unit)? = null
    ): ScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootDoc = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (_: Exception) {
            null
        }

        val rootName = rootDoc?.name ?: treeUri.lastPathSegment ?: "Target Folder"
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        if (rootDoc == null || !rootDoc.exists()) {
            return@withContext ScanResult(
                rootDisplayName = rootName,
                timestamp = timeStamp,
                totalFiles = 0,
                totalBytes = 0L,
                totalSecretsFound = 0,
                status = ScanStatus.FAILED,
                isTruncated = true,
                statusReason = "Could not access or resolve target folder URI via Storage Access Framework.",
                files = emptyList()
            )
        }

        val records = mutableListOf<ScannedFileRecord>()
        var totalBytes = 0L
        var totalSecrets = 0
        var isTruncated = false
        var truncationReason: String? = null
        var lastProgressTime = 0L

        suspend fun walkDoc(doc: DocumentFile, currentPath: String, depth: Int) {
            ensureActive()
            if (depth > config.maxDepth) {
                isTruncated = true
                truncationReason = "Maximum traversal depth (${config.maxDepth}) reached."
                return
            }

            val children = try {
                doc.listFiles()
            } catch (e: Exception) {
                isTruncated = true
                truncationReason = "Read permission or provider error at '$currentPath': ${e.message}"
                emptyArray()
            }

            for (child in children) {
                ensureActive()
                if (records.size >= config.maxFiles) {
                    isTruncated = true
                    truncationReason = "Maximum file count limit (${config.maxFiles}) reached."
                    return
                }

                if (totalBytes >= config.maxTotalBytes) {
                    isTruncated = true
                    truncationReason = "Maximum volume limit (${formatBytes(config.maxTotalBytes)}) reached."
                    return
                }

                val childName = child.name ?: "unnamed"
                val relativePath = if (currentPath.isEmpty()) childName else "$currentPath/$childName"

                if (child.isDirectory) {
                    if (config.recursive) {
                        walkDoc(child, relativePath, depth + 1)
                    }
                } else if (child.isFile) {
                    val size = try { child.length() } catch (_: Exception) { 0L }
                    totalBytes += size

                    var sha256: String? = null
                    val secrets = mutableListOf<String>()

                    if (config.calculateHashes && size <= config.maxFileSizeToHash) {
                        sha256 = computeHashFromUri(resolver, child.uri)
                    }

                    if (config.detectSecrets && isTextCandidate(childName, size, config.maxFileBytesForContentScan)) {
                        val found = scanSecretsFromUri(resolver, child.uri, config.maxFindingsPerFile)
                        secrets.addAll(found)
                        totalSecrets += found.size
                    }

                    val record = ScannedFileRecord(
                        relativePath = relativePath,
                        name = childName,
                        sizeBytes = size,
                        sha256 = sha256,
                        detectedSecrets = secrets
                    )
                    records.add(record)

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 200 || records.size % 50 == 0) {
                        lastProgressTime = now
                        onProgress?.invoke(childName, records.size)
                    }
                }
            }
        }

        walkDoc(rootDoc, "", 1)
        onProgress?.invoke("Complete", records.size)

        val finalStatus = when {
            isTruncated -> ScanStatus.PARTIAL
            else -> ScanStatus.COMPLETE
        }

        ScanResult(
            rootDisplayName = rootName,
            timestamp = timeStamp,
            totalFiles = records.size,
            totalBytes = totalBytes,
            totalSecretsFound = totalSecrets,
            status = finalStatus,
            isTruncated = isTruncated,
            statusReason = truncationReason,
            files = records
        )
    }

    private fun computeHashFromUri(resolver: ContentResolver, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanSecretsFromUri(resolver: ContentResolver, uri: Uri, maxFindings: Int): List<String> {
        val detected = mutableListOf<String>()
        try {
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                var lineCount = 0
                for (line in lines) {
                    if (++lineCount > 5000) break // Cap scanning to first 5000 lines
                    for (pattern in SECRET_PATTERNS) {
                        val matcher = pattern.matcher(line)
                        if (matcher.find()) {
                            // Mask completely for security - do not leak sensitive prefix/suffix
                            detected.add("[REDACTED]")
                            if (detected.size >= maxFindings) return@useLines
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return detected.distinct()
    }

    private fun isTextCandidate(name: String, size: Long, maxSize: Long): Boolean {
        if (size > maxSize) return false
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val textExtensions = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "env", "properties", "conf", "ini",
            "kt", "java", "py", "js", "ts", "html", "css", "sh", "bat", "c", "cpp", "h", "gradle", "kts",
            "dockerfile", "makefile", "license"
        )
        return ext in textExtensions || name.startsWith(".env", ignoreCase = true) || name.equals("dockerfile", ignoreCase = true)
    }

    /**
     * Exports the scan result into the chosen format.
     */
    fun exportReport(result: ScanResult, outputFile: File, format: String) {
        outputFile.parentFile?.mkdirs()
        when (format.lowercase(Locale.ROOT)) {
            "json" -> outputFile.writeText(buildJsonReport(result), Charsets.UTF_8)
            "md", "markdown" -> outputFile.writeText(buildMarkdownReport(result), Charsets.UTF_8)
            "csv" -> outputFile.writeText(buildCsvReport(result), Charsets.UTF_8)
            "txt" -> outputFile.writeText(buildPlainTextReport(result), Charsets.UTF_8)
            else -> outputFile.writeText(buildHtmlReport(result), Charsets.UTF_8)
        }
    }

    private fun buildHtmlReport(r: ScanResult): String {
        val safeRoot = TextUtils.htmlEncode(r.rootDisplayName)
        val safeTime = TextUtils.htmlEncode(r.timestamp)

        val statusBadge = when (r.status) {
            ScanStatus.COMPLETE -> "<span style=\"color:#10B981;font-weight:bold;\">COMPLETE</span>"
            ScanStatus.PARTIAL -> "<span style=\"color:#F59E0B;font-weight:bold;\">PARTIAL (TRUNCATED)</span>"
            ScanStatus.FAILED -> "<span style=\"color:#EF4444;font-weight:bold;\">FAILED</span>"
        }

        val reasonHtml = if (!r.statusReason.isNullOrEmpty()) {
            "<p style=\"color:#F59E0B;font-size:12px;margin:4px 0 0 0;\">Notice: ${TextUtils.htmlEncode(r.statusReason)}</p>"
        } else ""

        val rows = r.files.joinToString("\n") { f ->
            val safePath = TextUtils.htmlEncode(f.relativePath)
            val safeHash = TextUtils.htmlEncode(f.sha256 ?: "N/A")
            val secretsBadge = if (f.detectedSecrets.isNotEmpty()) {
                "<span style=\"color:#EF4444;font-weight:bold;\">[ALERT] ${f.detectedSecrets.size} secret(s)</span>"
            } else {
                "<span style=\"color:#10B981;\">Clean</span>"
            }
            """
            <tr>
                <td style="padding:8px;border-bottom:1px solid #27272A;">$safePath</td>
                <td style="padding:8px;border-bottom:1px solid #27272A;">${formatBytes(f.sizeBytes)}</td>
                <td style="padding:8px;border-bottom:1px solid #27272A;font-family:monospace;font-size:11px;">$safeHash</td>
                <td style="padding:8px;border-bottom:1px solid #27272A;">$secretsBadge</td>
            </tr>
            """.trimIndent()
        }

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>VeilFrame Security Audit: $safeRoot</title>
            <style>
                body { background:#0D0D11; color:#E4E4E7; font-family:-apple-system,BlinkMacSystemFont,sans-serif; padding:24px; }
                .card { background:#18181B; border:1px solid #27272A; border-radius:12px; padding:20px; margin-bottom:20px; }
                table { width:100%; border-collapse:collapse; text-align:left; font-size:13px; }
                th { background:#27272A; padding:10px; color:#A1A1AA; font-weight:600; }
                h1 { color:#F4F4F5; font-size:22px; margin-top:0; }
                .stat { display:inline-block; margin-right:24px; }
                .stat-num { font-size:24px; font-weight:bold; color:#60A5FA; }
                .stat-label { font-size:12px; color:#71717A; }
            </style>
        </head>
        <body>
            <h1>VeilFrame Folder Security Audit</h1>
            <div class="card">
                <div class="stat"><div class="stat-num">${r.totalFiles}</div><div class="stat-label">FILES ANALYZED</div></div>
                <div class="stat"><div class="stat-num">${formatBytes(r.totalBytes)}</div><div class="stat-label">TOTAL VOLUME</div></div>
                <div class="stat"><div class="stat-num" style="color:${if (r.totalSecretsFound > 0) "#EF4444" else "#10B981"}">${r.totalSecretsFound}</div><div class="stat-label">SECRETS DETECTED</div></div>
                <div class="stat"><div class="stat-num" style="font-size:16px;padding-top:6px;">$statusBadge</div><div class="stat-label">SCAN STATUS</div></div>
                $reasonHtml
                <p style="margin-top:16px;color:#A1A1AA;font-size:12px;">Target: $safeRoot • Generated: $safeTime • Engine: VeilFrame Native Kotlin</p>
            </div>
            <div class="card">
                <table>
                    <thead>
                        <tr><th>Relative Path</th><th>Size</th><th>SHA-256 Checksum</th><th>Security Status</th></tr>
                    </thead>
                    <tbody>
                        $rows
                    </tbody>
                </table>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildMarkdownReport(r: ScanResult): String {
        val sb = StringBuilder()
        sb.append("# VeilFrame Folder Security Audit\n\n")
        sb.append("- **Target:** ${r.rootDisplayName.replace("|", "\\|")}\n")
        sb.append("- **Timestamp:** ${r.timestamp}\n")
        sb.append("- **Status:** ${r.status}\n")
        if (!r.statusReason.isNullOrEmpty()) {
            sb.append("- **Notice:** ${r.statusReason.replace("|", "\\|")}\n")
        }
        sb.append("- **Files Analyzed:** ${r.totalFiles}\n")
        sb.append("- **Total Volume:** ${formatBytes(r.totalBytes)}\n")
        sb.append("- **Secrets Detected:** ${r.totalSecretsFound}\n\n")
        sb.append("| Relative Path | Size | SHA-256 | Status |\n")
        sb.append("| :--- | :--- | :--- | :--- |\n")
        for (f in r.files) {
            val status = if (f.detectedSecrets.isNotEmpty()) "[ALERT] ${f.detectedSecrets.size} secrets" else "Clean"
            val safePath = f.relativePath.replace("|", "\\|").replace("`", "")
            sb.append("| $safePath | ${formatBytes(f.sizeBytes)} | `${f.sha256 ?: "N/A"}` | $status |\n")
        }
        return sb.toString()
    }

    private fun buildJsonReport(r: ScanResult): String {
        val rootObj = JSONObject().apply {
            put("target", r.rootDisplayName)
            put("timestamp", r.timestamp)
            put("status", r.status.name)
            put("is_truncated", r.isTruncated)
            if (r.statusReason != null) {
                put("status_reason", r.statusReason)
            }
            put("total_files", r.totalFiles)
            put("total_bytes", r.totalBytes)
            put("total_secrets", r.totalSecretsFound)
            put("engine", "VeilFrame Native Kotlin")

            val filesArray = JSONArray()
            for (f in r.files) {
                val fObj = JSONObject().apply {
                    put("path", f.relativePath)
                    put("size_bytes", f.sizeBytes)
                    put("sha256", f.sha256 ?: JSONObject.NULL)
                    val secArr = JSONArray()
                    for (s in f.detectedSecrets) {
                        secArr.put(s)
                    }
                    put("detected_secrets", secArr)
                }
                filesArray.put(fObj)
            }
            put("files", filesArray)
        }
        return rootObj.toString(2)
    }

    private fun buildCsvReport(r: ScanResult): String {
        val sb = StringBuilder()
        sb.append("Path,SizeBytes,SHA256,SecretsCount\n")
        for (f in r.files) {
            val safePath = f.relativePath.replace("\"", "\"\"")
            sb.append("\"$safePath\",${f.sizeBytes},\"${f.sha256 ?: ""}\",${f.detectedSecrets.size}\n")
        }
        return sb.toString()
    }

    private fun buildPlainTextReport(r: ScanResult): String {
        val sb = StringBuilder()
        sb.append("VeilFrame Security Audit\n")
        sb.append("Target: ${r.rootDisplayName}\nDate: ${r.timestamp}\nStatus: ${r.status}\n")
        if (!r.statusReason.isNullOrEmpty()) {
            sb.append("Notice: ${r.statusReason}\n")
        }
        sb.append("Files: ${r.totalFiles}\nVolume: ${formatBytes(r.totalBytes)}\n\n")
        for (f in r.files) {
            sb.append("${f.relativePath} (${formatBytes(f.sizeBytes)}) - SHA256: ${f.sha256 ?: "N/A"}\n")
        }
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 4)
        val pre = "KMGT"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
