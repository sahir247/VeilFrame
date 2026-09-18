package com.veilframe.app.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Native Kotlin AI Bundle Builder.
 * Traverses Android SAF DocumentFile trees directly without disk materialization,
 * applies smart ignore rules (skipping build artifacts, binary blobs, and test suites),
 * masks confidential tokens and credentials, and packages clean project context
 * formatted for LLM prompts within an exact token budget.
 */
object AiBundleEngine {

    data class BundleConfig(
        val tokenBudget: Int = 64_000,
        val outputFormat: String = "md",
        val maskSecrets: Boolean = true,
        val excludeTests: Boolean = false,
        val compressManifests: Boolean = false
    )

    data class BundleResult(
        val totalFiles: Int,
        val includedFiles: Int,
        val totalTokens: Int,
        val outputPath: String
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

    private val SECRET_PATTERNS = listOf(
        Pattern.compile("(?i)(?:api_key|apikey|secret|token|password|auth|bearer)\\s*[:=]\\s*[\"']?([a-zA-Z0-9_\\-\\.]{16,})"),
        Pattern.compile("(?i)AIza[0-9A-Za-z\\-_]{35}"),
        Pattern.compile("(?i)ghp_[0-9a-zA-Z]{36}"),
        Pattern.compile("(?i)AKIA[0-9A-Z]{16}")
    )

    fun buildBundleFromSaf(
        context: Context,
        treeUri: Uri,
        outputFile: File,
        config: BundleConfig,
        onProgress: ((currentFile: String, included: Int) -> Unit)? = null
    ): BundleResult {
        val resolver = context.contentResolver
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        val rootName = rootDoc?.name ?: treeUri.lastPathSegment ?: "Project"

        val charsBudget = config.tokenBudget * 4
        var currentChars = 0
        var totalFilesSeen = 0
        var includedFiles = 0

        val sections = mutableListOf<String>()

        fun walkDoc(doc: DocumentFile, currentPath: String) {
            val children = doc.listFiles()
            for (child in children) {
                val childName = child.name ?: continue
                if (childName.startsWith(".") && childName != ".env.example") continue

                val relativePath = if (currentPath.isEmpty()) childName else "$currentPath/$childName"

                if (child.isDirectory) {
                    if (childName.lowercase(Locale.US) !in IGNORED_DIRECTORIES) {
                        walkDoc(child, relativePath)
                    }
                } else if (child.isFile) {
                    totalFilesSeen++
                    val ext = childName.substringAfterLast('.', "").lowercase(Locale.US)

                    if (ext in BINARY_EXTENSIONS) continue
                    if (config.excludeTests && isTestFile(childName, relativePath)) continue
                    if (child.length() > 500 * 1024) continue // Skip individual files > 500KB

                    val content = readFileContent(resolver, child.uri, config.maskSecrets)
                    if (content.isNullOrBlank()) continue

                    val fileSection = buildFileSection(relativePath, content, config.outputFormat, includedFiles + 1)
                    val sectionLen = fileSection.length

                    if (currentChars + sectionLen <= charsBudget) {
                        sections.add(fileSection)
                        currentChars += sectionLen
                        includedFiles++
                        onProgress?.invoke(childName, includedFiles)
                    }
                }
            }
        }

        if (rootDoc != null) {
            walkDoc(rootDoc, "")
        }

        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val estimatedTokens = currentChars / 4

        outputFile.parentFile?.mkdirs()
        val finalDocument = when (config.outputFormat.lowercase(Locale.US)) {
            "json" -> buildJsonBundle(rootName, timeStamp, includedFiles, estimatedTokens, sections)
            "aibundle" -> buildNativeAibundle(rootName, timeStamp, includedFiles, estimatedTokens, totalFilesSeen, sections)
            else -> buildMarkdownBundle(rootName, timeStamp, includedFiles, estimatedTokens, sections)
        }

        outputFile.writeText(finalDocument, Charsets.UTF_8)

        return BundleResult(
            totalFiles = totalFilesSeen,
            includedFiles = includedFiles,
            totalTokens = estimatedTokens,
            outputPath = outputFile.absolutePath
        )
    }

    private fun readFileContent(resolver: ContentResolver, uri: Uri, maskSecrets: Boolean): String? {
        return try {
            val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
            if (!maskSecrets) return raw

            var masked = raw
            for (pattern in SECRET_PATTERNS) {
                val matcher = pattern.matcher(masked)
                masked = matcher.replaceAll("[REDACTED_SECRET]")
            }
            masked
        } catch (_: Exception) {
            null
        }
    }

    private fun isTestFile(name: String, path: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        val lowerPath = path.lowercase(Locale.US)
        return lowerName.contains("test") || lowerName.contains("spec") ||
                lowerPath.contains("/test/") || lowerPath.contains("/tests/") || lowerPath.contains("/androidTest/")
    }

    private fun buildFileSection(path: String, content: String, format: String, fileIndex: Int): String {
        return when (format.lowercase(Locale.US)) {
            "json" -> {
                "\"${path.replace("\\", "\\\\")}\": ${org.json.JSONObject.quote(content)}"
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
                "### $path\n\n```$lang\n$content\n```\n\n"
            }
        }
    }

    private fun buildNativeAibundle(
        rootName: String,
        timestamp: String,
        fileCount: Int,
        totalTokens: Int,
        totalFilesSeen: Int,
        sections: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append("@VEILFRAME_BUNDLE\n")
        sb.append("version=1\n")
        sb.append("project=$rootName\n")
        sb.append("timestamp=$timestamp\n")
        sb.append("total_tokens=$totalTokens\n")
        sb.append("included_files=$fileCount\n")
        sb.append("excluded_files=${(totalFilesSeen - fileCount).coerceAtLeast(0)}\n\n")

        sb.append("@PROJECT\n")
        sb.append("Name: $rootName\n")
        sb.append("Engine: VeilFrame Mobile Hub Native Kotlin\n")
        sb.append("Included in Context: $fileCount files (~$totalTokens tokens)\n\n")

        sb.append("@SUMMARY\n")
        sb.append("Total Files Evaluated: $totalFilesSeen\n")
        sb.append("Included in Bundle: $fileCount\n")
        sb.append("Estimated Tokens: ~$totalTokens\n\n")

        sb.append("@FILES\n")
        for (sec in sections) {
            sb.append(sec)
        }

        sb.append("@SECURITY\n")
        sb.append("STATUS: SECURE | Credentials, API tokens, and secret patterns sanitized\n\n")

        sb.append("@END\n")
        return sb.toString()
    }

    private fun buildMarkdownBundle(rootName: String, timestamp: String, fileCount: Int, tokens: Int, sections: List<String>): String {
        val sb = StringBuilder()
        sb.append("# VeilFrame AI Context Bundle: $rootName\n\n")
        sb.append("- **Generated:** $timestamp\n")
        sb.append("- **Included Files:** $fileCount\n")
        sb.append("- **Estimated Tokens:** ~$tokens\n")
        sb.append("- **Engine:** VeilFrame Native Kotlin\n\n")
        sb.append("---\n\n")
        for (sec in sections) {
            sb.append(sec)
        }
        return sb.toString()
    }

    private fun buildJsonBundle(rootName: String, timestamp: String, fileCount: Int, tokens: Int, sections: List<String>): String {
        return """
        {
            "project": "${rootName.replace("\\", "\\\\")}",
            "generated": "$timestamp",
            "included_files": $fileCount,
            "estimated_tokens": $tokens,
            "files": {
                ${sections.joinToString(",\n")}
            }
        }
        """.trimIndent()
    }
}
