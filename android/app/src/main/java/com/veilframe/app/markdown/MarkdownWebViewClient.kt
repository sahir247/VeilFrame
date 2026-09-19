package com.veilframe.app.markdown

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Dedicated WebViewClient routing bundled offline assets securely via WebViewAssetLoader
 * and intercepting external hyperlinks and relative media references.
 */
class MarkdownWebViewClient(
    private val context: Context,
    private val resourceResolver: MarkdownResourceResolver,
    private val onPageFinishedDiagnostic: (String) -> Unit = {}
) : WebViewClient() {

    companion object {
        private const val TAG = "VeilFrame.MdClient"
    }

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val uri = request.url

        // 1. Intercept relative Markdown document resources (e.g. images)
        if (uri.path == "/__vf_resource__") {
            val relativePath = uri.getQueryParameter("path")
            val baseUri = uri.getQueryParameter("base")
            if (!relativePath.isNullOrBlank()) {
                val response = resourceResolver.resolveResource(relativePath, baseUri)
                if (response != null) return response
            }
        }

        // 2. Intercept bundled assets (CSS, JS, Fonts)
        return assetLoader.shouldInterceptRequest(uri)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        val uri = Uri.parse(url)
        if (uri.path == "/__vf_resource__") {
            val relativePath = uri.getQueryParameter("path")
            val baseUri = uri.getQueryParameter("base")
            if (!relativePath.isNullOrBlank()) {
                val response = resourceResolver.resolveResource(relativePath, baseUri)
                if (response != null) return response
            }
        }
        return assetLoader.shouldInterceptRequest(uri)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url.startsWith("https://appassets.androidplatform.net/")) {
            return false // Allow internal navigation within bundled viewer
        }
        // Route external links to device browser
        return MarkdownSecurityPolicy.handleExternalLink(context, url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (url.startsWith("https://appassets.androidplatform.net/")) {
            return false
        }
        return MarkdownSecurityPolicy.handleExternalLink(context, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        Log.d(TAG, "[MARKDOWN] WebView onPageFinished: $url")
        onPageFinishedDiagnostic(url)
    }
}
