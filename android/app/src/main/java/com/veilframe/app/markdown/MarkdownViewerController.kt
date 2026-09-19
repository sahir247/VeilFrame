package com.veilframe.app.markdown

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutMarkdownViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Controller managing the offline Markdown viewer screen:
 * - WebView lifecycle and secure local rendering pipeline
 * - Dynamic theme, font size, and reader width updates
 * - Table of Contents navigation
 * - In-page search with real-time match cycling
 * - Scroll position persistence and restoration
 */
class MarkdownViewerController(
    private val activity: AppCompatActivity,
    private val binding: LayoutMarkdownViewerBinding,
    private val onNavigateBack: () -> Unit,
    private val onOpenMarkdownFileRequest: () -> Unit = {}
) : MarkdownRenderer {

    companion object {
        private const val TAG = "VeilFrame.MdController"
        private const val VIEWER_URL = "https://appassets.androidplatform.net/assets/markdown/viewer.html"
    }

    private val state = MarkdownViewerState()
    private val resourceResolver = MarkdownResourceResolver(activity)
    private var isPageLoaded = false
    private var pendingRenderTask: (() -> Unit)? = null
    private val scrollPositions = mutableMapOf<String, Int>()

    fun init() {
        setupWebView()
        setupToolbar()
        setupSearch()
    }

    @SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        MarkdownSecurityPolicy.configureWebSettings(binding.wvMarkdown.settings)

        binding.wvMarkdown.webViewClient = MarkdownWebViewClient(
            context = activity,
            resourceResolver = resourceResolver,
            onPageReady = {
                isPageLoaded = true
                binding.progressMarkdownLoading.visibility = View.GONE
                // Sync initial theme
                setTheme(state.theme)
                setTextScale(state.textScale)
                setReadingWidth(state.readingWidth)
                pendingRenderTask?.invoke()
                pendingRenderTask = null
            }
        )

        binding.wvMarkdown.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTableOfContents(json: String) {
                activity.runOnUiThread {
                    parseTocJson(json)
                }
            }

            @JavascriptInterface
            fun onRenderFinished() {
                activity.runOnUiThread {
                    binding.progressMarkdownLoading.visibility = View.GONE
                    // Restore saved scroll position if any
                    val key = getDocumentKey()
                    val savedY = scrollPositions[key] ?: state.scrollPosition
                    if (savedY > 0) {
                        setScrollPosition(savedY)
                    }
                }
            }

            @JavascriptInterface
            fun onScrollPositionChanged(scrollPos: Int, docHeight: Int) {
                val key = getDocumentKey()
                scrollPositions[key] = scrollPos
                state.scrollPosition = scrollPos
            }

            @JavascriptInterface
            fun onSearchMatchChanged(current: Int, total: Int) {
                activity.runOnUiThread {
                    state.currentMatchIndex = current
                    state.totalMatches = total
                    if (total > 0) {
                        binding.tvMarkdownSearchCount.text = "$current / $total"
                    } else if (state.searchQuery.isNotEmpty()) {
                        binding.tvMarkdownSearchCount.text = "0 / 0"
                    } else {
                        binding.tvMarkdownSearchCount.text = ""
                    }
                }
            }
        }, "VeilFrameBridge")

        binding.progressMarkdownLoading.visibility = View.VISIBLE
        binding.wvMarkdown.loadUrl(VIEWER_URL)
    }

    private fun setupToolbar() {
        binding.btnMarkdownBack.setOnClickListener {
            onNavigateBack()
        }

        binding.btnMarkdownToc.setOnClickListener {
            showTableOfContentsDialog()
        }

        binding.btnMarkdownSearch.setOnClickListener {
            toggleSearch(true)
        }

        binding.btnMarkdownMenu.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun setupSearch() {
        binding.btnMarkdownSearchClose.setOnClickListener {
            toggleSearch(false)
        }

        binding.btnMarkdownSearchNext.setOnClickListener {
            findNext()
        }

        binding.btnMarkdownSearchPrev.setOnClickListener {
            findPrevious()
        }

        binding.etMarkdownSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                state.searchQuery = query
                find(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etMarkdownSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext()
                true
            } else {
                false
            }
        }
    }

    fun loadMarkdown(uri: Uri, title: String? = null) {
        openDocument(uri, title)
    }

    fun loadMarkdown(file: File, title: String? = null) {
        openDocument(file)
        if (title != null) {
            state.documentTitle = title
            binding.tvMarkdownTitle.text = title
        }
    }

    fun openDocument(uri: Uri, title: String? = null) {
        state.documentUri = uri
        state.documentFile = null
        val resolvedTitle = title ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.md"
        state.documentTitle = resolvedTitle
        binding.tvMarkdownTitle.text = resolvedTitle

        binding.progressMarkdownLoading.visibility = View.VISIBLE

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val content = try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read document from URI: ${e.message}", e)
                "# Error Opening Document\n\nCould not read file: `${e.message}`"
            }

            withContext(Dispatchers.Main) {
                loadMarkdown(content, uri, uri.toString())
            }
        }
    }

    fun openDocument(file: File) {
        state.documentFile = file
        state.documentUri = Uri.fromFile(file)
        state.documentTitle = file.name
        binding.tvMarkdownTitle.text = file.name

        binding.progressMarkdownLoading.visibility = View.VISIBLE

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                "# Error Opening Document\n\nCould not read `${file.name}`: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                loadMarkdown(content, Uri.fromFile(file), file.absolutePath)
            }
        }
    }

    fun openMarkdownContent(content: String, title: String, baseUri: Uri? = null) {
        state.documentFile = null
        state.documentUri = baseUri
        state.documentTitle = title
        binding.tvMarkdownTitle.text = title
        loadMarkdown(content, baseUri, title)
    }

    override fun loadMarkdown(content: String, baseUri: Uri?, documentId: String?) {
        state.rawMarkdown = content
        val sanitized = MarkdownSecurityPolicy.sanitizeMarkdown(content)
        val safeBaseUri = baseUri?.toString() ?: ""

        val execute = {
            binding.progressMarkdownLoading.visibility = View.VISIBLE
            val escapedContent = escapeJsString(sanitized)
            val escapedBase = escapeJsString(safeBaseUri)
            val js = "window.VeilFrameMarkdown.render('$escapedContent', '$escapedBase');"
            binding.wvMarkdown.evaluateJavascript(js, null)
        }

        if (isPageLoaded) {
            execute()
        } else {
            pendingRenderTask = execute
        }
    }

    override fun reload() {
        loadMarkdown(state.rawMarkdown, state.documentUri, getDocumentKey())
    }

    override fun clear() {
        state.rawMarkdown = ""
        state.tableOfContents = emptyList()
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown.render('', '');", null)
    }

    override fun setTheme(theme: MarkdownTheme) {
        state.theme = theme
        val js = "window.VeilFrameMarkdown.setTheme('${theme.jsValue}');"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun setTextScale(scale: Float) {
        state.textScale = scale.coerceIn(0.75f, 2.0f)
        val js = "window.VeilFrameMarkdown.setTextScale(${state.textScale});"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun setReadingWidth(width: ReadingWidth) {
        state.readingWidth = width
        val js = "window.VeilFrameMarkdown.setReadingWidth('${width.jsValue}');"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun scrollToHeading(id: String) {
        val safeId = escapeJsString(id)
        val js = "window.VeilFrameMarkdown.scrollToHeading('$safeId');"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun find(query: String) {
        val escaped = escapeJsString(query)
        val js = "window.VeilFrameMarkdown.find('$escaped');"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun findNext() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown.findNext();", null)
    }

    override fun findPrevious() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown.findPrevious();", null)
    }

    override fun clearFind() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown.clearFind();", null)
    }

    override fun getScrollPosition(callback: (Int) -> Unit) {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown.getScrollY();") { res ->
            val pos = res?.toIntOrNull() ?: 0
            callback(pos)
        }
    }

    override fun setScrollPosition(position: Int) {
        val js = "window.VeilFrameMarkdown.setScrollY($position);"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    private fun toggleSearch(show: Boolean) {
        state.isSearchActive = show
        binding.layoutMarkdownSearch.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.etMarkdownSearch.requestFocus()
            if (state.searchQuery.isNotEmpty()) {
                find(state.searchQuery)
            }
        } else {
            clearFind()
            binding.etMarkdownSearch.setText("")
            binding.tvMarkdownSearchCount.text = ""
        }
    }

    private fun showTableOfContentsDialog() {
        if (state.tableOfContents.isEmpty()) {
            Toast.makeText(activity, "No headings found in document", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.dialog_markdown_toc, null)

        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerToc)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        recycler.adapter = TocAdapter(state.tableOfContents) { item ->
            scrollToHeading(item.id)
            dialog.dismiss()
        }

        val btnClose = view.findViewById<View>(R.id.btnCloseToc)
        btnClose?.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 10, 0, "Contents (TOC)")
        popup.menu.add(0, 11, 0, "Find in Document")
        popup.menu.add(0, 12, 0, "Open File...")
        popup.menu.add(0, 1, 0, if (state.readingWidth == ReadingWidth.COMFORTABLE) "Width: Full" else "Width: Comfortable")
        popup.menu.add(0, 2, 0, "Font Size...")
        popup.menu.add(0, 3, 0, if (state.theme == MarkdownTheme.DARK) "Theme: Light" else "Theme: Dark")
        popup.menu.add(0, 4, 0, "Copy Raw Markdown")
        popup.menu.add(0, 5, 0, "Share Document...")
        popup.menu.add(0, 7, 0, "Open With...")
        popup.menu.add(0, 6, 0, "Reload")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> {
                    showTableOfContentsDialog()
                    true
                }
                11 -> {
                    toggleSearch(true)
                    true
                }
                12 -> {
                    onOpenMarkdownFileRequest()
                    true
                }
                1 -> {
                    val nextWidth = if (state.readingWidth == ReadingWidth.COMFORTABLE) ReadingWidth.FULL else ReadingWidth.COMFORTABLE
                    setReadingWidth(nextWidth)
                    true
                }
                2 -> {
                    showFontSizeDialog()
                    true
                }
                3 -> {
                    val nextTheme = if (state.theme == MarkdownTheme.DARK) MarkdownTheme.LIGHT else MarkdownTheme.DARK
                    setTheme(nextTheme)
                    true
                }
                4 -> {
                    copyRawMarkdown()
                    true
                }
                5 -> {
                    shareDocument()
                    true
                }
                7 -> {
                    openWithExternalApp()
                    true
                }
                6 -> {
                    reload()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openWithExternalApp() {
        try {
            val file = state.documentFile ?: run {
                val tempFile = File(activity.cacheDir, state.documentTitle)
                tempFile.writeText(state.rawMarkdown, Charsets.UTF_8)
                tempFile
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/markdown")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Open With"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not open with external app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf("Small (85%)", "Normal (100%)", "Large (120%)", "Extra Large (140%)")
        val scales = floatArrayOf(0.85f, 1.0f, 1.20f, 1.40f)
        val currentIndex = when {
            state.textScale <= 0.90f -> 0
            state.textScale <= 1.10f -> 1
            state.textScale <= 1.30f -> 2
            else -> 3
        }

        AlertDialog.Builder(activity)
            .setTitle("Text Size")
            .setSingleChoiceItems(sizes, currentIndex) { dialog, which ->
                setTextScale(scales[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyRawMarkdown() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Markdown Document", state.rawMarkdown)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(activity, "Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareDocument() {
        try {
            val file = state.documentFile ?: run {
                // Stage temporary file for sharing
                val tempFile = File(activity.cacheDir, state.documentTitle)
                tempFile.writeText(state.rawMarkdown, Charsets.UTF_8)
                tempFile
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, state.documentTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Markdown Document"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseTocJson(json: String) {
        try {
            val array = JSONArray(json)
            val list = mutableListOf<TocItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TocItem(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", ""),
                        level = obj.optInt("level", 1)
                    )
                )
            }
            state.tableOfContents = list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing TOC JSON: ${e.message}", e)
        }
    }

    private fun getDocumentKey(): String {
        return state.documentUri?.toString() ?: state.documentFile?.absolutePath ?: state.documentTitle
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    // RecyclerView Adapter for Table of Contents
    private class TocAdapter(
        private val items: List<TocItem>,
        private val onItemClick: (TocItem) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TocViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TocViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_markdown_toc, parent, false)
            return TocViewHolder(view)
        }

        override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
            val item = items[position]
            val indent = ((item.level - 1) * 16).coerceAtLeast(0)
            holder.title.setPadding(
                (16 + indent) * holder.itemView.resources.displayMetrics.density.toInt(),
                12 * holder.itemView.resources.displayMetrics.density.toInt(),
                16 * holder.itemView.resources.displayMetrics.density.toInt(),
                12 * holder.itemView.resources.displayMetrics.density.toInt()
            )
            holder.title.text = item.title
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class TocViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val title: android.widget.TextView = view.findViewById(R.id.tvTocTitle)
    }
}
