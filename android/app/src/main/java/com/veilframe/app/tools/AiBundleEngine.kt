package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
 * formatted for LLM prompts with an exact o200k_base artifact budget or a byte cap.
 */
object AiBundleEngine {

    data class BundleConfig(
        val tokenBudget: Long = 64_000L,
        val outputFormat: String = "md",
        val maskSecrets: Boolean = true,
        val excludeTests: Boolean = false,
        val compressManifests: Boolean = false,
        val maxDepth: Int? = null,
        val excludedRelativePaths: Set<String> = emptySet(),
        val expectedInventoryFingerprint: String? = null,
        val tokenEncoding: String = BundleTokenizer.NAME
    )

    data class BundleResult(
        val totalFiles: Int,
        val includedFiles: Int,
        val totalTokens: Long,
        val outputPath: String,
        val omittedEntries: Int = 0,
        val tokenEncoding: String? = null
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
        // Explicit environment-variable names and short credentials are still credentials.
        Pattern.compile("""(?i)\b(?:aws[_-]?(?:secret[_-]?access[_-]?key|session[_-]?token)|db[_-]?password|database[_-]?password|database[_-]?url|github[_-]?token|gitlab[_-]?token|openai[_-]?api[_-]?key|stripe[_-]?secret[_-]?key|smtp[_-]?password|redis[_-]?password|access[_-]?key|api[_-]?secret)\b["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n]++)*+"|'(?:\\.|[^'\\\r\n]++)*+'|[^\s,;}"']++)"""),
        Pattern.compile("""(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"""),
        Pattern.compile("""(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9_./+=-]+"""),
        // Match whole escaped quoted values instead of leaving a secret suffix.
        Pattern.compile("""(?i)\b(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|token|password|passwd|authorization|auth|client[_-]?secret|refresh[_-]?token|private[_-]?key|accountkey)\b["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n]++)*+"|'(?:\\.|[^'\\\r\n]++)*+')"""),
        Pattern.compile("""(?i)\b(?:password|passwd|api[_-]?key|secret|token|private[_-]?key|client[_-]?secret)\b\s*[:=]\s*[|>]\s*\r?\n(?:[ \t]+[^\r\n]*(?:\r?\n|$))++"""),
        Pattern.compile("""\b(?:glpat-[A-Za-z0-9_-]{15,}|npm_[A-Za-z0-9]{20,}|pypi-[A-Za-z0-9_-]{20,}|hf_[A-Za-z0-9]{20,}|sk_live_[A-Za-z0-9]{12,})\b"""),
        Pattern.compile("""(?i)\b(?:accountkey|client[_-]?secret|refresh[_-]?token|private[_-]?token|aws[_-]?secret[_-]?access[_-]?key)\b["']?\s*[:=]\s*(?:"[^"\r\n]+"|'[^'\r\n]+'|[^\s,;}"']+)"""),
        Pattern.compile("""(?i)\b(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|token|password|passwd|authorization|auth)\b["']?\s*[:=]\s*(?:"[^"\r\n]+"|'[^'\r\n]+'|[^\s,;}"']+)"""),
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
                "credentials", ".git-credentials", "auth.json", "kubeconfig", "terraform.tfvars",
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
    internal data class BundleInput(val uri: String, val path: String, val size: Long, val directory: Boolean,
        val lastModified: Long = -1L)

    internal data class BundleInventory(val entries: List<BundleInput>, val fingerprint: String) {
        val files: List<String> get() = entries.filterNot { it.directory }.map { it.path }
    }

    internal fun inventoryFingerprint(entries: List<BundleInput>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        for (entry in entries) {
            field(entry.path); field(entry.uri); field(entry.size.toString()); field(entry.directory.toString())
            field(entry.lastModified.toString())
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    private fun blockedDirectory(path: String, depth: Int, maxDepth: Int?): String? {
        val name = path.substringAfterLast('/')
        return when {
            name.startsWith(".") && name != ".env.example" -> "hidden entry"
            name.lowercase(Locale.US) in IGNORED_DIRECTORIES -> "excluded directory"
            depth >= MAX_DIRECTORY_DEPTH || (maxDepth != null && depth >= maxDepth) -> "depth limit"
            else -> null
        }
    }

    internal suspend fun previewInventory(context: Context, treeUri: Uri, maxDepth: Int?): BundleInventory {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Cannot open selected folder" }
        require(root.exists() && root.isDirectory) { "Selected folder is unavailable" }
        return enumerateInventory(context, root.uri, maxDepth)
    }

    private suspend fun enumerateInventory(context: Context, root: Uri, maxDepth: Int?): BundleInventory {
        require(maxDepth == null || maxDepth in 0..MAX_DIRECTORY_DEPTH)
        val entries = ArrayList<BundleInput>()
        val visited = HashSet<String>()
        var entered = 0
        var inventoryChars = 0L
        suspend fun walk(doc: Uri, path: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(doc.toString())) throw IOException("Repeated folder identity; narrow the selection")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(doc, DocumentsContract.getDocumentId(doc))
            val cursor = context.contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?: throw IOException("Provider returned no directory listing")
            val children = ArrayList<BundleInput>()
            val names = HashSet<String>()
            cursor.use {
                // Providers may return null or omit this metadata; treat unknown as -1.
                val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                    !it.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                    throw IOException("Provider returned incomplete directory listing")
                }
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (++entered > MAX_ENTRIES) throw IOException("Folder exceeds the 50,000 entry inspection limit")
                    val id = it.getString(0)?.takeIf { value -> value.isNotBlank() } ?: throw IOException("Missing provider document identity")
                    val name = it.getString(1) ?: throw IOException("Missing provider document name")
                    val mime = it.getString(2)?.takeIf { value -> value.isNotBlank() } ?: throw IOException("Missing provider MIME type")
                    if (!isSafeBundlePath(name) || name.contains('/') || !names.add(name)) {
                        throw IOException("Invalid or ambiguous directory entry; no bundle published")
                    }
                    val relative = if (path.isEmpty()) name else "$path/$name"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(doc, id).toString()
                    if (relative.length > MAX_PATH_CHARS) throw IOException("Bundle path exceeds supported length")
                    inventoryChars += relative.length.toLong() + uri.length
                    if (inventoryChars > 8L * 1024 * 1024) throw IOException("Directory metadata exceeds bundle inspection budget")
                    val modifiedAt = if (modifiedColumn >= 0 && !it.isNull(modifiedColumn))
                        it.getLong(modifiedColumn) else -1L
                    children.add(BundleInput(uri, relative, if (it.isNull(3)) -1L else it.getLong(3),
                        mime == DocumentsContract.Document.MIME_TYPE_DIR, modifiedAt))
                }
            }
            for (child in children.sortedWith(compareBy({ it.path }, { it.uri }))) {
                currentCoroutineContext().ensureActive()
                entries.add(child)
                if (child.directory && blockedDirectory(child.path, depth, maxDepth) == null) {
                    walk(Uri.parse(child.uri), child.path, depth + 1)
                }
            }
        }
        walk(root, "", 0)
        return BundleInventory(entries, inventoryFingerprint(entries))
    }

    suspend fun buildBundleFromSaf(
        context: Context,
        treeUri: Uri,
        outputFile: File,
        config: BundleConfig,
        onProgress: ((currentFile: String, included: Int) -> Unit)? = null
    ): BundleResult {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Cannot open selected folder" }
        require(root.exists() && root.isDirectory) { "Selected folder is unavailable" }
        return buildBundle(context, root.name ?: "Project", root, null, outputFile, config, onProgress)
    }

    suspend fun buildSingleFileBundle(
        context: Context, uri: Uri, name: String, outputFile: File, config: BundleConfig
    ): BundleResult = buildBundle(context, name, null, uri, outputFile, config, null)

    private suspend fun buildBundle(
        context: Context, rootName: String, root: DocumentFile?, single: Uri?,
        outputFile: File, config: BundleConfig, onProgress: ((String, Int) -> Unit)?
    ): BundleResult {
        require(config.tokenBudget >= 0L && (config.maxDepth == null || config.maxDepth in 0..MAX_DIRECTORY_DEPTH)) {
            "Invalid bundle budget or folder depth"
        }
        val format = config.outputFormat.lowercase(Locale.US)
        require(format in setOf("json", "md", "aibundle")) { "Unsupported bundle format" }
        val exact = config.tokenBudget > 0
        require(config.tokenEncoding in setOf(BundleTokenizer.NAME, BundleTokenizer.CL100K))
        val budget = if (exact) BundleTokenizer.MAX_TOKENIZED_BYTES else MAX_BUNDLE_BYTES
        // Independent byte and token admission budgets. Section token counts
        // guide packing; they are not additive across arbitrary BPE boundaries.
        val contentBudget = budget - minOf(budget / 8, 256 * 1024L)
        val sectionTokenBudget = config.tokenBudget - minOf(config.tokenBudget / 8, 8192L)
        var sectionTokens = 0L
        val exclusions = FileExclusions(config.excludedRelativePaths)
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
        var omissionBytes = 0L
        val includedPaths = HashSet<String>()
        val excludedIdentities = ExcludedDocumentIdentities()
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
                        if (omissionBytes > budget) throw IOException("Omission report exceeds bundle byte limit")
                        skipped.write(line)
                        omitted++
                    }
                    suspend fun include(uri: Uri, path: String, reportedSize: Long) {
                        currentCoroutineContext().ensureActive()
                        val name = path.substringAfterLast('/')
                        if (path.length > MAX_PATH_CHARS) { omit(path, "path length limit"); return }
                        if (!isSafeBundlePath(path)) { omit(path, "unsafe relative path"); return }
                        if (exclusions.excludes(path)) { omit(path, "explicit user exclusion"); return }
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
                        if (len > contentBudget - chars) { omit(path, "content byte budget"); return }
                        val candidateTokens = if (exact) BundleTokenizer.count(separator + section, config.tokenEncoding) else 0L
                        currentCoroutineContext().ensureActive()
                        if (exact && candidateTokens > sectionTokenBudget - sectionTokens) {
                            omit(path, "${config.tokenEncoding} section budget"); return
                        }
                        sectionTokens += candidateTokens
                        writer.write(separator)
                        writer.write(section)
                        chars += len
                        includedPaths.add(exportedPath)
                        if (content != beforeCondense) condensed++
                        included++
                        onProgress?.invoke(safePath(name), included)
                    }
                    if (root != null) {
                        val inventory = enumerateInventory(context, root.uri, config.maxDepth)
                        if (config.expectedInventoryFingerprint != null &&
                            inventory.fingerprint != config.expectedInventoryFingerprint) {
                            throw IOException("Folder inventory changed since selection; review files again")
                        }
                        val candidates = ArrayList<BundleInput>()
                        for (entry in inventory.entries) {
                            currentCoroutineContext().ensureActive()
                            val name = entry.path.substringAfterLast('/')
                            if (entry.directory) {
                                val reason = blockedDirectory(entry.path, entry.path.count { it == '/' }, config.maxDepth)
                                if (reason != null) omit(entry.path, reason)
                                continue
                            }
                            files++
                            if (exclusions.excludes(entry.path)) {
                                excludedIdentities.exclude(entry.uri)
                                omit(entry.path, "explicit user exclusion")
                            } else if (config.maskSecrets && isSensitiveFileName(name)) {
                                excludedIdentities.exclude(entry.uri)
                                omit(entry.path, "potential credential container (masking enabled)")
                            } else if (name.startsWith(".") && name != ".env.example") {
                                omit(entry.path, "hidden entry")
                            } else candidates.add(entry)
                        }
                        exclusions.requireAllMatched()
                        for (entry in candidates) {
                            currentCoroutineContext().ensureActive()
                            if (excludedIdentities.contains(entry.uri)) omit(entry.path, "alias of excluded document")
                            else include(Uri.parse(entry.uri), entry.path, entry.size)
                        }
                    } else {
                        files = 1
                        if (exclusions.excludes(rootName)) omit(rootName, "explicit user exclusion")
                        else {
                            exclusions.requireAllMatched()
                            include(requireNotNull(single), rootName, -1)
                        }
                    }
                }
            }
            exclusions.requireAllMatched()
            require(included > 0) { "No readable files fit this format, filters and content budget" }
            val tokens = if (exact) sectionTokens else chars / 4 + if (chars % 4 == 0L) 0 else 1
            val tokenScope = if (exact) "Independent ${config.tokenEncoding} file-section counts; exact complete artifact checked before publication"
                else "File-section estimate (UTF-8 bytes / 4); 32 MiB byte cap only"
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
                        out.write("{\"project\":$safeName,\"generated\":${JSONObject.quote(timestamp)},\"included_files\":$included,\"evaluated_files\":$files,\"estimated_tokens\":$tokens,\"condensed_files\":$condensed,\"budget_scope\":${JSONObject.quote(tokenScope)},\"security\":${JSONObject.quote(security)},\"files\":{\n")
                        appendFile(body)
                        out.write("\n},\"omissions\":[\n")
                        appendFile(omissions)
                        out.write("\n]}\n")
                    }
                    "aibundle" -> {
                        out.write("@VEILFRAME_BUNDLE\nversion=1\nproject=${exportedRoot.replace('\n', ' ').replace('\r', ' ')}\ntimestamp=$timestamp\ntotal_tokens=$tokens\nincluded_files=$included\nexcluded_files=${files - included}\nmax_depth=${config.maxDepth ?: "unlimited"}\n\n@SUMMARY\nEvaluated files: $files\nCondensed manifests: $condensed\nBudget: $tokenScope\n\n@FILES\n")
                        appendFile(body)
                        out.write("\n@OMISSIONS\n")
                        appendFile(omissions)
                        out.write("\n@SECURITY\n$security\n\n@END\n")
                    }
                    else -> {
                        out.write("# VeilFrame AI Context Bundle: $safeName\n\nGenerated: $timestamp\nIncluded: $included / $files evaluated files\nFile-section tokens: $tokens ($tokenScope)\nCondensed manifests: $condensed\n$security\n\n")
                        appendFile(body)
                        out.write("\n## Omitted entries\n\n")
                        appendFile(omissions)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val finalBytes = temp.length()
            if (finalBytes > budget) {
                throw IOException("Complete bundle, including omission report, exceeds the byte cap. Narrow the selection or use byte-capped mode.")
            }
            val cc = currentCoroutineContext()
            val wholeTokens = if (exact) BundleTokenizer.verify(temp, config.tokenBudget, config.tokenEncoding) { cc.ensureActive() }
                else finalBytes / 4 + if (finalBytes % 4 == 0L) 0 else 1
            cc.ensureActive()
            Files.move(temp.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return BundleResult(files, included, wholeTokens, outputFile.absolutePath, omitted,
                if (exact) config.tokenEncoding else null)
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
                if (n == 0) throw IOException("Provider stream made no progress")
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

    private const val MAX_EXPLICIT_EXCLUSIONS = 2000

    internal fun parseExcludedPaths(input: String): Set<String> {
        require(input.length <= 128 * 1024) { "Exclusion list is too long" }
        // Do not trim nonblank filenames: spaces can be part of a SAF display name.
        val paths = input.lineSequence().filter { it.isNotBlank() }.toCollection(linkedSetOf())
        FileExclusions(paths) // shared validation for direct callers and UI
        return paths
    }

    internal class ExcludedDocumentIdentities {
        private val identities = HashSet<String>()
        fun exclude(identity: String) { require(identity.isNotBlank()); identities.add(identity) }
        fun contains(identity: String): Boolean = identity in identities
    }

    internal class FileExclusions(paths: Set<String>) {
        private val requested = paths.toSet()
        private val matched = HashSet<String>()
        init {
            require(requested.size <= MAX_EXPLICIT_EXCLUSIONS) { "Too many explicit file exclusions" }
            require(requested.all { it.length <= MAX_PATH_CHARS && isSafeBundlePath(it) }) {
                "Use exact relative file paths, one per line; absolute paths and traversal are not allowed"
            }
        }
        fun excludes(path: String): Boolean {
            if (path !in requested) return false
            matched.add(path)
            return true
        }
        fun requireAllMatched() {
            val remaining = requested - matched
            if (remaining.isNotEmpty()) {
                // Do not echo names which may themselves contain credentials.
                throw IOException("${remaining.size} requested exclusion(s) did not match an inspected file. Check case, relative paths and folder depth; no bundle was published.")
            }
        }
    }

    internal fun isSafeBundlePath(path: String): Boolean =
        path.isNotBlank() && path.none { it == '\\' || it.code < 32 || it.code == 127 } &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    internal fun isNativeBundleEntrySafe(path: String, content: String): Boolean =
        isSafeBundlePath(path) &&
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
                    .takeIf { it.matches(Regex("[a-z0-9]{1,24}")) } ?: "text"
                val id = String.format(Locale.ROOT, "F%03d", fileIndex)
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
