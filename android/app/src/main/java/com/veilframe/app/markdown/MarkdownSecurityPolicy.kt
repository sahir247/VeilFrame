package com.veilframe.app.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast

/**
 * Defensive security policy enforcing least-privilege for the Markdown WebView:
 * - Disables file and content scheme access (assets are served via secure virtual origin)
 * - Intercepts all external web navigation and launches in Android browser
 * - Sanitizes dangerous HTML constructs from untrusted user documents
 */
object MarkdownSecurityPolicy {

    private val DANGEROUS_HTML_PATTERN = Regex(
        "(?i)<(script|iframe|object|embed|form|input|base|meta|link)[^>]*>.*?</\\1>|<(script|iframe|object|embed|form|input|base|meta|link)[^>]*>",
        RegexOption.DOT_MATCHES_ALL
    )

    private val JAVASCRIPT_URI_PATTERN = Regex("(?i)javascript\\s*:", RegexOption.IGNORE_CASE)
    private val EVENT_HANDLER_PATTERN = Regex("(?i)\\bon[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false

        // Enforce strict local filesystem isolation
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // Zoom capabilities
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Cache & layout
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Privacy offline-by-default: block remote network loads and images until user gives explicit consent
        settings.blockNetworkImage = true
        settings.blockNetworkLoads = true
    }

    fun setNetworkAccessEnabled(settings: WebSettings, enabled: Boolean) {
        settings.blockNetworkImage = !enabled
        settings.blockNetworkLoads = !enabled
    }

    fun hasRemoteImageResources(markdown: String): Boolean {
        val pattern = Regex("""(?i)(<img[^>]+src=["']https?://|!\[.*?\]\(https?://)""")
        return pattern.containsMatchIn(markdown)
    }

    fun handleExternalLink(context: Context, url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private val CODE_BLOCK_PATTERN = Regex("""(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\r\n]+`)""", RegexOption.MULTILINE)
    private val PLACEHOLDER_PATTERN = Regex("""%%VF_CODE_BLOCK_HOLD_(\d+)%%""")

    fun sanitizeMarkdown(markdown: String): String {
        if (markdown.isEmpty()) return ""
        if (!markdown.contains('<') && !markdown.contains("javascript:", ignoreCase = true)) {
            return markdown
        }

        // Protect fenced code blocks (```...``` or ~~~...~~~) and inline code (`...`)
        val codeBlocks = mutableListOf<String>()
        val placeholderPrefix = "%%VF_CODE_BLOCK_HOLD_"
        val protectedMarkdown = CODE_BLOCK_PATTERN.replace(markdown) { match ->
            val idx = codeBlocks.size
            codeBlocks.add(match.value)
            "$placeholderPrefix${idx}%%"
        }

        // Strip dangerous executable HTML tags
        var cleaned = DANGEROUS_HTML_PATTERN.replace(protectedMarkdown, "")
        // Strip inline event handlers
        cleaned = EVENT_HANDLER_PATTERN.replace(cleaned, "")
        // Strip javascript: pseudo-protocol in links or images
        cleaned = JAVASCRIPT_URI_PATTERN.replace(cleaned, "")

        // Restore code blocks in a single O(N) pass
        return PLACEHOLDER_PATTERN.replace(cleaned) { match ->
            val idx = match.groupValues[1].toIntOrNull()
            if (idx != null && idx in codeBlocks.indices) {
                codeBlocks[idx]
            } else {
                match.value
            }
        }
    }
}
