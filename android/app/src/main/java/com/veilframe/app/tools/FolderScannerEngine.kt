package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
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
    ) {
        val duplicateGroups: Map<String, List<ScannedFileRecord>>
            get() = files.filter { !it.sha256.isNullOrEmpty() }.groupBy { it.sha256!! }.filter { it.value.size > 1 }
    }

    private val SECRET_PATTERNS = listOf(
        Pattern.compile("(?i)(?:api_key|apikey|secret|token|password|auth|bearer)\\s*[:=]\\s*[\"']?([a-zA-Z0-9_\\-\\.]{16,})"),
        Pattern.compile("(?i)AIza[0-9A-Za-z\\-_]{35}"), // Google API Key
        Pattern.compile("(?i)ghp_[0-9a-zA-Z]{36}"), // GitHub Token
        Pattern.compile("(?i)AKIA[0-9A-Z]{16}"), // AWS Key ID
        Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----") // Private Key
    )

    private class AuditAccumulator(private val config: ScanConfig) {
        val records = ArrayList<ScannedFileRecord>()
        var totalBytes = 0L
        var totalSecrets = 0
        private val issues = linkedSetOf<String>()
        fun issue(reason: String) { if (issues.size < 12) issues.add(reason) }
        fun canInspect(): Boolean {
            if (exhausted) return false
            if (records.size >= config.maxFiles) {
                issue("File count limit reached"); return false
            }
            if (totalBytes >= config.maxTotalBytes) {
                issue("Volume limit reached"); return false
            }
            return true
        }

        suspend fun inspect(resolver: ContentResolver, uri: Uri, path: String, name: String, knownSize: Long?) {
            if (!canInspect()) return
            val cc = kotlin.coroutines.coroutineContext
            val maximum = config.maxTotalBytes - totalBytes
            if (knownSize != null && knownSize > maximum) { issue("Volume limit reached"); return }
            val wantHash = config.calculateHashes
            val wantText = config.detectSecrets && isTextCandidate(name, 0L, config.maxFileBytesForContentScan)
            var count = knownSize ?: -1L
            var hash: String? = null
            var findings = emptyList<String>()
            if (wantHash || wantText || knownSize == null) {
                try {
                    val hashLimit = if (wantHash) config.maxFileSizeToHash else 0L
                    val textLimit = if (wantText) config.maxFileBytesForContentScan else 0L
                    // For size-only reads with unknown metadata, measure at most
                    // the remaining total-volume budget.
                    val limit = if (!wantHash && !wantText) maximum else minOf(maximum, maxOf(hashLimit, textLimit))
                    val digest = if (wantHash && (knownSize == null || knownSize <= hashLimit)) MessageDigest.getInstance("SHA-256") else null
                    val content = if (wantText && (knownSize == null || knownSize <= textLimit)) ByteArrayOutputStream() else null
                    if (knownSize != null && knownSize > limit) {
                        issue("Requested content checks exceed per-file limits")
                    } else {
                        val input = resolver.openInputStream(uri) ?: throw IOException("Missing provider stream")
                        val measured = input.use { readAuditBytes(it, limit, hashLimit, textLimit, digest, content) { cc.ensureActive() } }
                        if (measured == null) {
                            // The actual bytes were larger than admitted. Stop the
                            // traversal instead of undercounting an unknown volume.
                            issue("Actual file bytes exceeded scan limits")
                            if (knownSize == null || limit == maximum) exhausted = true
                        } else {
                            count = measured
                            if (knownSize != null && knownSize != measured) {
                                issue("Provider size disagreed with bytes read")
                            } else {
                                if (digest != null && measured <= hashLimit) {
                                    hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
                                }
                                if (content != null && measured <= textLimit) {
                                    val scan = scanMaskedFindings(content.toString("UTF-8"), config.maxFindingsPerFile) { cc.ensureActive() }
                                    findings = scan.first
                                    if (scan.second) issue("Finding limit reached")
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    issue("A requested file read failed")
                }
                if (wantHash && hash == null) issue("At least one requested hash was unavailable")
                if (wantText && (count < 0 || count > config.maxFileBytesForContentScan)) issue("At least one text file exceeded content scan limits")
            }
            if (count < 0) { issue("At least one file size was unavailable"); exhausted = true }
            if (count > maximum) { issue("Volume limit reached"); exhausted = true; return }
            totalBytes += count.coerceAtLeast(0L)
            totalSecrets += findings.size
            records.add(ScannedFileRecord(path, name, count, hash, findings))
        }

        var exhausted = false
            private set

        fun result(name: String, failed: Boolean = false): ScanResult = ScanResult(
            rootDisplayName = name,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            totalFiles = records.size, totalBytes = totalBytes, totalSecretsFound = totalSecrets,
            status = if (failed) ScanStatus.FAILED else if (issues.isEmpty()) ScanStatus.COMPLETE else ScanStatus.PARTIAL,
            isTruncated = failed || issues.isNotEmpty(),
            statusReason = issues.takeIf { it.isNotEmpty() }?.joinToString("; "),
            files = records.toList()
        )
    }

    internal fun readAuditBytes(input: java.io.InputStream, limit: Long, hashLimit: Long, textLimit: Long,
                                digest: MessageDigest?, content: ByteArrayOutputStream?,
                                checkpoint: () -> Unit = {}): Long? {
        require(limit >= 0 && hashLimit >= 0 && textLimit >= 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkpoint()
            val request = minOf(buffer.size.toLong(), (limit - total).coerceAtMost(buffer.size.toLong()) + 1L).toInt()
            val n = input.read(buffer, 0, request)
            if (n < 0) return total
            if (n == 0) throw IOException("Provider stream made no progress")
            if (n.toLong() > limit - total) return null
            total += n
            if (total <= hashLimit) digest?.update(buffer, 0, n)
            if (total <= textLimit) content?.write(buffer, 0, n)
        }
    }

    internal fun scanMaskedFindings(text: String, maxFindings: Int, checkpoint: () -> Unit = {}): Pair<List<String>, Boolean> {
        require(maxFindings in 1..1000)
        val findings = ArrayList<String>()
        // Search the entire admitted file, not just the first 5,000 lines.
        for (pattern in SECRET_PATTERNS) {
            checkpoint()
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                checkpoint()
                if (findings.size >= maxFindings) return findings to true
                findings.add("[REDACTED]")
            }
        }
        return findings to false
    }

    private fun requireConfig(config: ScanConfig) {
        require(config.maxFiles in 1..50_000 && config.maxDepth in 1..128 &&
            config.maxTotalBytes > 0 && config.maxFileSizeToHash >= 0 &&
            config.maxFileBytesForContentScan in 0..(5L * 1024 * 1024) &&
            config.maxFindingsPerFile in 1..1000)
    }

    suspend fun scanSingleFile(context: Context, uri: Uri, name: String,
                               config: ScanConfig = ScanConfig()): ScanResult = withContext(Dispatchers.IO) {
        requireConfig(config)
        val audit = AuditAccumulator(config)
        val size = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value >= 0L } else null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        audit.inspect(context.contentResolver, uri, name, name, size)
        audit.result(name)
    }

    suspend fun scanSafTree(
        context: Context,
        treeUri: Uri,
        config: ScanConfig = ScanConfig(),
        onProgress: ((currentFile: String, count: Int) -> Unit)? = null
    ): ScanResult = withContext(Dispatchers.IO) {
        requireConfig(config)
        val resolver = context.contentResolver
        val audit = AuditAccumulator(config)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        val rootName = root?.name ?: "Target Folder"
        if (root == null || !root.exists()) {
            audit.issue("Could not access selected folder")
            return@withContext audit.result(rootName, failed = true)
        }
        val visited = HashSet<String>()
        var entries = 0
        var lastProgress = 0L
        data class Child(val uri: Uri, val name: String, val mime: String, val size: Long?)
        suspend fun walk(doc: Uri, path: String, depth: Int) {
            ensureActive()
            if (depth > config.maxDepth) { audit.issue("Maximum traversal depth reached"); return }
            if (!visited.add(doc.toString())) { audit.issue("Repeated directory identity"); return }
            val children = ArrayList<Child>()
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(doc, DocumentsContract.getDocumentId(doc))
                val cursor = resolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                    ?: throw IOException("Missing directory cursor")
                cursor.use {
                    if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        !it.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                        audit.issue("Provider directory listing is incomplete")
                    }
                    val names = HashSet<String>()
                    while (it.moveToNext()) {
                        ensureActive()
                        if (++entries > 50_000) { audit.issue("Directory entry limit reached"); break }
                        val id = it.getString(0)
                        val name = it.getString(1)
                        val mime = it.getString(2)
                        if (id.isNullOrBlank() || name.isNullOrBlank() || mime.isNullOrBlank() ||
                            name == "." || name == ".." || name.any { ch -> ch == '/' || ch == '\\' || ch.code < 32 } ||
                            !names.add(name)) { audit.issue("Invalid or ambiguous provider entry"); continue }
                        children.add(Child(DocumentsContract.buildDocumentUriUsingTree(doc, id), name, mime,
                            if (it.isNull(3)) null else it.getLong(3).takeIf { value -> value >= 0L }))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { audit.issue("Directory read failed"); return }
            for (child in children) {
                ensureActive()
                if (audit.exhausted || !audit.canInspect()) return
                val relative = if (path.isEmpty()) child.name else "$path/" + child.name
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (config.recursive) walk(child.uri, relative, depth + 1)
                } else {
                    audit.inspect(resolver, child.uri, relative, child.name, child.size)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= 200) {
                        lastProgress = now
                        onProgress?.invoke(child.name, audit.records.size)
                    }
                }
            }
        }
        walk(root.uri, "", 1)
        onProgress?.invoke("Complete", audit.records.size)
        audit.result(rootName)
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
            "zip" -> {
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(outputFile)).use { zos ->
                    val entries = listOf(
                        "report.html" to buildHtmlReport(result).toByteArray(Charsets.UTF_8),
                        "report.json" to buildJsonReport(result).toByteArray(Charsets.UTF_8),
                        "report.csv" to buildCsvReport(result).toByteArray(Charsets.UTF_8),
                        "report.md" to buildMarkdownReport(result).toByteArray(Charsets.UTF_8)
                    )
                    for ((name, data) in entries) {
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
            }
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

        val duplicatesHtml = if (r.duplicateGroups.isNotEmpty()) {
            val dupRows = r.duplicateGroups.entries.joinToString("\n") { (hash, dups) ->
                val filesList = dups.joinToString("<br>") { TextUtils.htmlEncode(it.relativePath) + " (" + formatBytes(it.sizeBytes) + ")" }
                """
                <tr>
                    <td style="padding:8px;border-bottom:1px solid #27272A;font-family:monospace;font-size:11px;">${TextUtils.htmlEncode(hash)}</td>
                    <td style="padding:8px;border-bottom:1px solid #27272A;">$filesList</td>
                </tr>
                """.trimIndent()
            }
            """
            <div class="card">
                <h2 style="color:#F59E0B;font-size:16px;margin-top:0;">Duplicate Files Detected (${r.duplicateGroups.size} groups)</h2>
                <table>
                    <thead><tr><th>SHA-256 Checksum</th><th>Matching Files</th></tr></thead>
                    <tbody>$dupRows</tbody>
                </table>
            </div>
            """.trimIndent()
        } else ""

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
            $duplicatesHtml
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

        if (r.duplicateGroups.isNotEmpty()) {
            sb.append("## Duplicate Files Detected (${r.duplicateGroups.size} groups)\n\n")
            sb.append("| SHA-256 Checksum | Matching Files |\n")
            sb.append("| :--- | :--- |\n")
            for ((hash, dups) in r.duplicateGroups) {
                val filesList = dups.joinToString("<br>") { it.relativePath.replace("|", "\\|") + " (${formatBytes(it.sizeBytes)})" }
                sb.append("| `$hash` | $filesList |\n")
            }
            sb.append("\n")
        }

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

            val dupObj = JSONObject()
            for ((hash, dups) in r.duplicateGroups) {
                val dupArr = JSONArray()
                for (d in dups) {
                    dupArr.put(d.relativePath)
                }
                dupObj.put(hash, dupArr)
            }
            put("duplicate_groups", dupObj)

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
