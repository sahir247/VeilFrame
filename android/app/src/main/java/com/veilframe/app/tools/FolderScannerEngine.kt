package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Native Kotlin Folder Scanner and Security Audit Engine.
 * Traverses Android SAF DocumentFile trees directly without copying files to disk,
 * computes streaming cryptographic SHA-256 hashes, detects leaked secrets via regex,
 * and exports comprehensive reports in HTML, JSON, Markdown, and CSV.
 */
object FolderScannerEngine {

    data class ScanConfig(
        val recursive: Boolean = true,
        val calculateHashes: Boolean = true,
        val detectSecrets: Boolean = true,
        val maxFileSizeToHash: Long = 100 * 1024 * 1024 // 100 MB
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
     * Scans an Android SAF Tree URI directly using ContentResolver, streaming bytes
     * without writing intermediate copies to the Android filesystem.
     */
    fun scanSafTree(
        context: Context,
        treeUri: Uri,
        config: ScanConfig,
        onProgress: ((currentFile: String, count: Int) -> Unit)? = null
    ): ScanResult {
        val resolver = context.contentResolver
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        val rootName = rootDoc?.name ?: treeUri.lastPathSegment ?: "Target Folder"

        val records = mutableListOf<ScannedFileRecord>()
        var totalBytes = 0L
        var totalSecrets = 0

        fun walkDoc(doc: DocumentFile, currentPath: String) {
            val children = doc.listFiles()
            for (child in children) {
                val childName = child.name ?: "unnamed"
                val relativePath = if (currentPath.isEmpty()) childName else "$currentPath/$childName"

                if (child.isDirectory) {
                    if (config.recursive) {
                        walkDoc(child, relativePath)
                    }
                } else if (child.isFile) {
                    val size = child.length()
                    totalBytes += size

                    var sha256: String? = null
                    val secrets = mutableListOf<String>()

                    if (config.calculateHashes && size <= config.maxFileSizeToHash) {
                        sha256 = computeHashFromUri(resolver, child.uri)
                    }

                    if (config.detectSecrets && isTextCandidate(childName, size)) {
                        val found = scanSecretsFromUri(resolver, child.uri)
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

                    onProgress?.invoke(childName, records.size)
                }
            }
        }

        if (rootDoc != null) {
            walkDoc(rootDoc, "")
        }

        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return ScanResult(
            rootDisplayName = rootName,
            timestamp = timeStamp,
            totalFiles = records.size,
            totalBytes = totalBytes,
            totalSecretsFound = totalSecrets,
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

    private fun scanSecretsFromUri(resolver: ContentResolver, uri: Uri): List<String> {
        val detected = mutableListOf<String>()
        try {
            resolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                var count = 0
                for (line in lines) {
                    if (count++ > 5000) break // Cap scanning to first 5000 lines
                    for (pattern in SECRET_PATTERNS) {
                        val matcher = pattern.matcher(line)
                        if (matcher.find()) {
                            val matchStr = matcher.group()
                            val masked = if (matchStr.length > 8) matchStr.take(4) + "..." + matchStr.takeLast(4) else "***"
                            detected.add(masked)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return detected.distinct()
    }

    private fun isTextCandidate(name: String, size: Long): Boolean {
        if (size > 5 * 1024 * 1024) return false // Skip files larger than 5 MB for secret search
        val ext = name.substringAfterLast('.', "").lowercase()
        val textExtensions = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "env", "properties", "conf", "ini",
            "kt", "java", "py", "js", "ts", "html", "css", "sh", "bat", "c", "cpp", "h", "gradle", "kts"
        )
        return ext in textExtensions || name.startsWith(".env")
    }

    /**
     * Exports the scan result into the chosen format.
     */
    fun exportReport(result: ScanResult, outputFile: File, format: String) {
        outputFile.parentFile?.mkdirs()
        when (format.lowercase(Locale.US)) {
            "json" -> outputFile.writeText(buildJsonReport(result), Charsets.UTF_8)
            "md", "markdown" -> outputFile.writeText(buildMarkdownReport(result), Charsets.UTF_8)
            "csv" -> outputFile.writeText(buildCsvReport(result), Charsets.UTF_8)
            "txt" -> outputFile.writeText(buildPlainTextReport(result), Charsets.UTF_8)
            else -> outputFile.writeText(buildHtmlReport(result), Charsets.UTF_8)
        }
    }

    private fun buildHtmlReport(r: ScanResult): String {
        val rows = r.files.joinToString("\n") { f ->
            val secretsBadge = if (f.detectedSecrets.isNotEmpty()) {
                "<span style=\"color:#EF4444;font-weight:bold;\">⚠ ${f.detectedSecrets.size} secret(s)</span>"
            } else {
                "<span style=\"color:#10B981;\">Clean</span>"
            }
            """
            <tr>
                <td style="padding:8px;border-bottom:1px solid #27272A;">${f.relativePath}</td>
                <td style="padding:8px;border-bottom:1px solid #27272A;">${formatBytes(f.sizeBytes)}</td>
                <td style="padding:8px;border-bottom:1px solid #27272A;font-family:monospace;font-size:11px;">${f.sha256 ?: "N/A"}</td>
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
            <title>VeilFrame Security Audit: ${r.rootDisplayName}</title>
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
                <div class="stat"><div class="stat-num">${r.totalFiles}</div><div class="stat-label">FILES SCANNED</div></div>
                <div class="stat"><div class="stat-num">${formatBytes(r.totalBytes)}</div><div class="stat-label">TOTAL VOLUME</div></div>
                <div class="stat"><div class="stat-num" style="color:${if (r.totalSecretsFound > 0) "#EF4444" else "#10B981"}">${r.totalSecretsFound}</div><div class="stat-label">SECRETS FLAGGED</div></div>
                <p style="margin-top:16px;color:#A1A1AA;font-size:12px;">Target: ${r.rootDisplayName} • Generated: ${r.timestamp} • Engine: VeilFrame Native Kotlin</p>
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
        sb.append("- **Target:** ${r.rootDisplayName}\n")
        sb.append("- **Timestamp:** ${r.timestamp}\n")
        sb.append("- **Files Analyzed:** ${r.totalFiles}\n")
        sb.append("- **Total Volume:** ${formatBytes(r.totalBytes)}\n")
        sb.append("- **Secrets Detected:** ${r.totalSecretsFound}\n\n")
        sb.append("| Relative Path | Size | SHA-256 | Status |\n")
        sb.append("| :--- | :--- | :--- | :--- |\n")
        for (f in r.files) {
            val status = if (f.detectedSecrets.isNotEmpty()) "⚠ ${f.detectedSecrets.size} secrets" else "Clean"
            sb.append("| ${f.relativePath} | ${formatBytes(f.sizeBytes)} | `${f.sha256 ?: "N/A"}` | $status |\n")
        }
        return sb.toString()
    }

    private fun buildJsonReport(r: ScanResult): String {
        val filesJson = r.files.joinToString(",\n") { f ->
            val secretsArr = f.detectedSecrets.joinToString(",") { "\"$it\"" }
            """
            {
                "path": "${f.relativePath.replace("\\", "\\\\")}",
                "size_bytes": ${f.sizeBytes},
                "sha256": ${if (f.sha256 != null) "\"${f.sha256}\"" else "null"},
                "detected_secrets": [$secretsArr]
            }
            """.trimIndent()
        }

        return """
        {
            "target": "${r.rootDisplayName.replace("\\", "\\\\")}",
            "timestamp": "${r.timestamp}",
            "total_files": ${r.totalFiles},
            "total_bytes": ${r.totalBytes},
            "total_secrets": ${r.totalSecretsFound},
            "engine": "VeilFrame Native Kotlin",
            "files": [
                $filesJson
            ]
        }
        """.trimIndent()
    }

    private fun buildCsvReport(r: ScanResult): String {
        val sb = StringBuilder()
        sb.append("Path,SizeBytes,SHA256,SecretsCount\n")
        for (f in r.files) {
            sb.append("\"${f.relativePath.replace("\"", "\"\"")}\",${f.sizeBytes},\"${f.sha256 ?: ""}\",${f.detectedSecrets.size}\n")
        }
        return sb.toString()
    }

    private fun buildPlainTextReport(r: ScanResult): String {
        val sb = StringBuilder()
        sb.append("VeilFrame Security Audit\n")
        sb.append("Target: ${r.rootDisplayName}\nDate: ${r.timestamp}\nFiles: ${r.totalFiles}\n\n")
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
