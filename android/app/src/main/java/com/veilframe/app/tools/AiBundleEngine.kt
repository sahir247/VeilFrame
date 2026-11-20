package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Native Kotlin AI Bundle Builder.
 * Traverses Android SAF DocumentFile trees directly without disk materialization,
 * applies smart ignore rules (skipping build artifacts, binary blobs, and test suites),
 * heuristically masks recognized credential patterns and packages project context
 * formatted for LLM prompts within an estimated token budget.
 */
object AiBundleEngine {

    data class BundleConfig(
        val tokenBudget: Long = 64_000L,
        val outputFormat: String = "md",
        val maskSecrets: Boolean = true,
        val excludeTests: Boolean = false,
        val compressManifests: Boolean = false,
        val maxDepth: Int? = null
    )

    data class BundleResult(
        val totalFiles: Int,
        val includedFiles: Int,
        val totalTokens: Long,
        val outputPath: String,
        val omittedEntries: Int = 0
    )

    private val IGNORED_DIRECTORIES = setOf(
        ".git", ".gradle", "build", "dist", ".idea", ".vscode", "node_modules",
        "__pycache__", ".venv", "env", "bin", "obj", "target", ".dart_tool"
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "bmp", "pdf", "zip", "tar", "gz",
        "apk", "aab", "aar", "jar", "so", "dylib", "dll", "exe", "class", "pyc",
        "mp4", "mkv", "mov", "avi", "mp3", "wav", "flac", "ttf", "otf", "woff", "woff2"
    )

    // Heuristics, not a guarantee that the bundle contains no credentials.
    private val SECRET_PATTERNS = listOf(
        Pattern.compile("""(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"""),
        Pattern.compile("""(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9_./+=-]+"""),
        // Match whole escaped quoted values instead of leaving a secret suffix.
        Pattern.compile("""(?i)\b(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|token|password|passwd|authorization|auth|client[_-]?secret|refresh[_-]?token|private[_-]?key|accountkey)\b["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n]++)*+"|'(?:\\.|[^'\\\r\n]++)*+')"""),
        Pattern.compile("""(?i)\b(?:password|passwd|api[_-]?key|secret|token|private[_-]?key|client[_-]?secret)\b\s*[:=]\s*[|>]\s*\r?\n(?:[ \t]+[^\r\n]*(?:\r?\n|$))++"""),
        Pattern.compile("""\b(?:glpat-[A-Za-z0-9_-]{15,}|npm_[A-Za-z0-9]{20,}|pypi-[A-Za-z0-9_-]{20,}|hf_[A-Za-z0-9]{20,}|sk_live_[A-Za-z0-9]{12,})\b"""),
        Pattern.compile("""(?i)\b(?:accountkey|client[_-]?secret|refresh[_-]?token|private[_-]?token|aws[_-]?secret[_-]?access[_-]?key)\b["']?\s*[:=]\s*(?:"[^"\r\n]+"|'[^'\r\n]+'|[^\s,;}"']+)"""),
        Pattern.compile("""(?i)\b(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|token|password|passwd|authorization|auth)\b["']?\s*[:=]\s*(?:"[^"\r\n]+"|'[^'\r\n]+'|[^\s,;}"']{4,})"""),
        Pattern.compile("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"""),
        Pattern.compile("""\bAIza[A-Za-z0-9_-]{35}\b"""),
        Pattern.compile("""\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"""),
        Pattern.compile("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"""),
        Pattern.compile("""(?i)\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis|amqp(?:s)?|https?)://[^/\s:@]+:[^/\s@]+@"""),
        Pattern.compile("""\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})\b""")
    )

    private val MANIFEST_LOCK_FILES = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock",
        "cargo.lock", "pipfile.lock", "composer.lock", "packages.lock.json"
    )

    // Credential containers should not enter the bundle merely because their contents
    // do not match one of our text patterns. Masking remains opt-out, not a guarantee.
    internal fun isSensitiveFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower == ".env.example") return false
        return lower == ".env" || lower.startsWith(".env.") ||
            lower in setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "keystore.properties",
                "credentials.json", "service-account.json", ".npmrc", ".netrc", ".pypirc",
                "google-services.json", "secrets.properties", "secrets.json", "local.settings.json",
                "application-default-credentials.json", "application_default_credentials.json") ||
            lower.startsWith("secrets.") || lower.endsWith(".tfstate") || lower.endsWith(".tfstate.backup") ||
            lower.substringAfterLast('.', "") in setOf("pem", "key", "p12", "pfx", "jks", "keystore", "kdbx")
    }

    fun maskSecrets(raw: String): String {
        var masked = raw
        for (pattern in SECRET_PATTERNS) {
            val matcher = pattern.matcher(masked)
            masked = matcher.replaceAll("[REDACTED_SECRET]")
        }
        return masked
    }

    private fun condenseManifest(content: String): String {
        val lines = content.lines()
        if (lines.size <= 40) return content
        val head = lines.take(25).joinToString("\n")
        val tail = lines.takeLast(10).joinToString("\n")
        return "$head\n\n// ... [Dependency Manifest Condensed: ${lines.size} lines total] ...\n\n$tail"
    }

    private const val MAX_FILE_BYTES = 500 * 1024
    private const val MAX_DIRECTORY_DEPTH = 128
    private const val MAX_ENTRIES = 50_000
    private const val MAX_BUNDLE_BYTES = 32L * 1024 * 1024
    private const val MAX_PATH_CHARS = 4096
    private class InputTooLarge : IOException("File exceeds 500 KiB input limit")

    suspend fun buildBundleFromSaf(
        context: Context,
        treeUri: Uri,
        outputFile: File,
        config: BundleConfig,
        onProgress: ((currentFile: String, included: Int) -> Unit)? = null
    ): BundleResult {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Cannot open selected folder" }
        return buildBundle(context, root.name ?: "Project", root, null, outputFile, config, onProgress)
    }

    suspend fun buildSingleFileBundle(
        context: Context, uri: Uri, name: String, outputFile: File, config: BundleConfig
    ): BundleResult = buildBundle(context, name, null, uri, outputFile, config, null)

    private suspend fun buildBundle(
        context: Context, rootName: String, root: DocumentFile?, single: Uri?,
        outputFile: File, config: BundleConfig, onProgress: ((String, Int) -> Unit)?
    ): BundleResult {
        val format = config.outputFormat.lowercase(Locale.US)
        require(format in setOf("json", "md", "aibundle")) { "Unsupported bundle format" }
        val budget = if (config.tokenBudget <= 0) MAX_BUNDLE_BYTES
            else minOf(MAX_BUNDLE_BYTES, config.tokenBudget.coerceAtMost(Long.MAX_VALUE / 4) * 4)
        // Reserve space for headers/omissions. The final serialized UTF-8 byte count
        // is checked as well, including the report, before publication.
        val contentBudget = budget - minOf(budget / 8, 256 * 1024L)
        val parent = requireNotNull(outputFile.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create bundle output directory" }
        val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "bundle_").toFile()
        val body = File(scratch, "body")
        val omissions = File(scratch, "omissions")
        var staged: File? = null
        var chars = 0L
        var files = 0
        var included = 0
        var omitted = 0
        var condensed = 0
        var entered = 0
        var omissionBytes = 0L
        val visited = HashSet<String>()
        val includedPaths = HashSet<String>()
        fun safePath(path: String): String = if (config.maskSecrets) maskSecrets(path) else path
        val exportedRoot = safePath(rootName).take(MAX_PATH_CHARS)
        try {
            body.bufferedWriter(Charsets.UTF_8).use { writer ->
                omissions.bufferedWriter(Charsets.UTF_8).use { skipped ->
                    fun omit(path: String, reason: String) {
                        val shownPath = safePath(path).take(MAX_PATH_CHARS)
                        val line = if (format == "json") {
                            (if (omitted > 0) ",\n" else "") +
                                "{\"path\":${JSONObject.quote(shownPath)},\"reason\":${JSONObject.quote(reason)}}"
                        } else "${JSONObject.quote(shownPath)} — $reason\n"
                        omissionBytes += line.toByteArray(Charsets.UTF_8).size
                        if (omissionBytes > MAX_BUNDLE_BYTES) throw IOException("Omission report exceeds bundle limit")
                        skipped.write(line)
                        omitted++
                    }
                    suspend fun include(uri: Uri, path: String, reportedSize: Long) {
                        currentCoroutineContext().ensureActive()
                        val name = path.substringAfterLast('/')
                        if (path.length > MAX_PATH_CHARS) { omit(path, "path length limit"); return }
                        val exportedPath = safePath(path)
                        if (exportedPath in includedPaths) { omit(path, "duplicate exported path"); return }
                        if (config.maskSecrets && isSensitiveFileName(name)) {
                            omit(path, "potential credential container (masking enabled)")
                            return
                        }
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                        if (ext in BINARY_EXTENSIONS) { omit(path, "binary file type"); return }
                        if (config.excludeTests && isTestFile(name, path)) { omit(path, "test filter"); return }
                        if (reportedSize > MAX_FILE_BYTES) { omit(path, "input exceeds 500 KiB"); return }
                        var content = try {
                            readBoundedText(context.contentResolver, uri)
                        } catch (e: InputTooLarge) {
                            omit(path, "input exceeds 500 KiB"); return
                        } catch (e: java.nio.charset.CharacterCodingException) {
                            omit(path, "not valid UTF-8 text"); return
                        } catch (e: IOException) {
                            omit(path, "unreadable file"); return
                        } catch (e: SecurityException) {
                            omit(path, "permission denied"); return
                        }
                        if (content.isBlank()) { omit(path, "empty file"); return }
                        if (config.maskSecrets) content = maskSecrets(content)
                        val beforeCondense = content
                        if (config.compressManifests && name.lowercase(Locale.US) in MANIFEST_LOCK_FILES) {
                            content = condenseManifest(content)
                        }
                        if (format == "aibundle" && !isNativeBundleEntrySafe(exportedPath, content)) {
                            omit(path, "native delimiter conflict; select JSON format"); return
                        }
                        val section = buildFileSection(exportedPath, content, format, included + 1)
                        val separator = if (format == "json" && included > 0) ",\n" else ""
                        val len = section.toByteArray(Charsets.UTF_8).size.toLong() + separator.length
                        if (len > contentBudget - chars) { omit(path, "estimated content budget"); return }
                        writer.write(separator)
                        writer.write(section)
                        chars += len
                        includedPaths.add(exportedPath)
                        if (content != beforeCondense) condensed++
                        included++
                        onProgress?.invoke(safePath(name), included)
                    }
                    suspend fun walk(doc: DocumentFile, path: String, depth: Int) {
                        currentCoroutineContext().ensureActive()
                        if (!visited.add(doc.uri.toString())) { omit(path, "repeated directory reference"); return }
                        for (child in doc.listFiles().sortedWith(compareBy({ it.name.orEmpty() }, { it.uri.toString() }))) {
                            currentCoroutineContext().ensureActive()
                            if (++entered > MAX_ENTRIES) throw IOException("Folder exceeds the 50,000 entry inspection limit")
                            val name = child.name ?: "(unnamed)"
                            val relative = if (path.isEmpty()) name else "$path/$name"
                            if (child.isFile) files++
                            if (name.startsWith(".") && name != ".env.example") { omit(relative, "hidden entry"); continue }
                            if (child.isDirectory) {
                                when {
                                    name.lowercase(Locale.US) in IGNORED_DIRECTORIES -> omit(relative, "excluded directory")
                                    depth >= MAX_DIRECTORY_DEPTH || (config.maxDepth != null && depth >= config.maxDepth) ->
                                        omit(relative, "depth limit")
                                    else -> walk(child, relative, depth + 1)
                                }
                            } else if (child.isFile) include(child.uri, relative, child.length())
                            else omit(relative, "unsupported entry")
                        }
                    }
                    if (root != null) walk(root, "", 0)
                    else { files = 1; include(requireNotNull(single), rootName, -1) }
                }
            }
            require(included > 0) { "No readable files fit this format, filters and estimated content budget" }
            val tokens = chars / 4 + if (chars % 4 == 0L) 0 else 1
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val safeName = JSONObject.quote(exportedRoot)
            val security = if (config.maskSecrets) "Heuristic masking enabled; review for missed secrets before sharing."
                else "Masking disabled; review all content before sharing."
            val temp = File.createTempFile("bundle_", ".tmp", parent)
            staged = temp
            temp.bufferedWriter(Charsets.UTF_8).use { out ->
                suspend fun appendFile(file: File) {
                    file.bufferedReader(Charsets.UTF_8).use { input ->
                        val buffer = CharArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                when (format) {
                    "json" -> {
                        out.write("{\"project\":$safeName,\"generated\":${JSONObject.quote(timestamp)},\"included_files\":$included,\"evaluated_files\":$files,\"estimated_tokens\":$tokens,\"condensed_files\":$condensed,\"budget_scope\":\"UTF-8 bytes / 4; whole bundle checked before publication\",\"security\":${JSONObject.quote(security)},\"files\":{\n")
                        appendFile(body)
                        out.write("\n},\"omissions\":[\n")
                        appendFile(omissions)
                        out.write("\n]}\n")
                    }
                    "aibundle" -> {
                        out.write("@VEILFRAME_BUNDLE\nversion=1\nproject=${exportedRoot.replace('\n', ' ').replace('\r', ' ')}\ntimestamp=$timestamp\ntotal_tokens=$tokens\nincluded_files=$included\nexcluded_files=${files - included}\nmax_depth=${config.maxDepth ?: "unlimited"}\n\n@SUMMARY\nEvaluated files: $files\nCondensed manifests: $condensed\nBudget: UTF-8 bytes / 4; complete bundle checked\n\n@FILES\n")
                        appendFile(body)
                        out.write("\n@OMISSIONS\n")
                        appendFile(omissions)
                        out.write("\n@SECURITY\n$security\n\n@END\n")
                    }
                    else -> {
                        out.write("# VeilFrame AI Context Bundle: $safeName\n\nGenerated: $timestamp\nIncluded: $included / $files evaluated files\nEstimated file-section tokens: ~$tokens (not exact); whole bundle budget checked\nCondensed manifests: $condensed\n$security\n\n")
                        appendFile(body)
                        out.write("\n## Omitted entries\n\n")
                        appendFile(omissions)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val finalBytes = temp.length()
            if (finalBytes > budget) {
                throw IOException("Complete bundle, including the omission report, exceeds the estimated budget. Increase the budget or narrow the selection.")
            }
            Files.move(temp.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val wholeEstimate = finalBytes / 4 + if (finalBytes % 4 == 0L) 0 else 1
            return BundleResult(files, included, wholeEstimate, outputFile.absolutePath, omitted)
        } finally {
            staged?.delete()
            scratch.deleteRecursively()
        }
    }

    private suspend fun readBoundedText(resolver: ContentResolver, uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read input")
        val bytes = input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_FILE_BYTES) throw InputTooLarge()
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        if (bytes.any { it == 0.toByte() }) throw IOException("Binary content")
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    internal fun isNativeBundleEntrySafe(path: String, content: String): Boolean =
        path.none { it == '"' || it == '\\' || it.code < 32 || it.code == 127 } &&
            content.lineSequence().none { it.trim() == ">>>" || it.trim() == "<<<" }

    private fun isTestFile(name: String, path: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        val lowerPath = path.lowercase(Locale.US)
        return lowerName.contains("test") || lowerName.contains("spec") ||
                lowerPath.contains("/test/") || lowerPath.contains("/tests/") || lowerPath.contains("/androidtest/")
    }

    private fun buildFileSection(path: String, content: String, format: String, fileIndex: Int): String {
        return when (format.lowercase(Locale.US)) {
            "json" -> {
                "${org.json.JSONObject.quote(path)}: ${org.json.JSONObject.quote(content)}"
            }
            "aibundle" -> {
                val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
                val id = "F%03d".format(fileIndex)
                "@FILE id=\"$id\" path=\"$path\" language=\"$ext\"\n<<<\n$content\n>>>\n\n"
            }
            else -> {
                val lang = when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
                    "kt" -> "kotlin"
                    "java" -> "java"
                    "py" -> "python"
                    "js" -> "javascript"
                    "ts" -> "typescript"
                    "json" -> "json"
                    "xml" -> "xml"
                    "gradle", "kts" -> "kotlin"
                    "html" -> "html"
                    "css" -> "css"
                    "sh" -> "bash"
                    else -> ""
                }
                val fence = "`".repeat(maxOf(3, Regex("`+").findAll(content).maxOfOrNull { it.value.length + 1 } ?: 3))
                "### ${JSONObject.quote(path)}\n\n$fence$lang\n$content\n$fence\n\n"
            }
        }
    }

}
