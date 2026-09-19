package com.veilframe.app.markdown

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 * Controller managing the offline Markdown viewer & GitHub-style live editor:
 * - State machine: IDLE -> LOADING_VIEWER -> VIEWER_READY -> RENDERING -> (MERMAID_LOADING -> MERMAID_RENDERING) -> RENDERED
 * - Only RENDERED hides the loading indicator
 * - Watchdog distinguishes viewer initialization vs rendering vs mermaid timeouts
 * - Non-blocking asynchronous Mermaid rendering
 * - Token-based Marked.js 15 API compatibility
 * - Live Markdown Editor with Write / Preview mode switching (renders on tab switch, not keystroke)
 * - In-place overwrite (Save) and SAF export (Save As)
 * - Dirty-state guard on back navigation (Save / Discard / Cancel)
 * - "New MD Maker" to create Markdown documents from scratch
 */
class MarkdownViewerController(
    private val activity: AppCompatActivity,
    private val binding: LayoutMarkdownViewerBinding,
    private val onNavigateBack: () -> Unit,
    private val onOpenMarkdownFileRequest: () -> Unit = {},
    private val onSaveAsMarkdownRequest: (suggestedName: String, content: String) -> Unit = { _, _ -> }
) : MarkdownRenderer {

    companion object {
        private const val TAG = "VeilFrame.MdController"
        private const val VIEWER_URL = "https://appassets.androidplatform.net/assets/markdown/viewer.html"
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L
        private const val MAX_INIT_TIMEOUT_MS = 5000L
        private const val MAX_RENDER_TIMEOUT_MS = 8000L
        private const val MAX_MERMAID_TIMEOUT_MS = 12000L
    }

    private val state = MarkdownViewerState()
    private val resourceResolver = MarkdownResourceResolver(activity)
    private var isPageLoaded = false
    private var pendingRenderTask: (() -> Unit)? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogStartTime: Long = 0L
    private var watchdogRunnable: Runnable? = null

    fun init() {
        state.lifecycleState = ViewerLifecycleState.LOADING_VIEWER
        setupWebView()
        setupToolbar()
        setupModeSwitcher()
        setupEditor()
        setupSearch()
        setupErrorView()
    }

    @SuppressLint("JavascriptInterface")
    private fun setupWebView() {
        Log.d(TAG, "[MARKDOWN] Viewer initializing")
        MarkdownSecurityPolicy.configureWebSettings(binding.wvMarkdown.settings)

        binding.wvMarkdown.webViewClient = MarkdownWebViewClient(
            context = activity,
            resourceResolver = resourceResolver,
            onPageFinishedDiagnostic = { url ->
                Log.d(TAG, "[MARKDOWN] onPageFinished diagnostic callback: $url")
            }
        )

        binding.wvMarkdown.addJavascriptInterface(object {
            @JavascriptInterface
            fun onViewerReady() {
                activity.runOnUiThread {
                    Log.d(TAG, "[MARKDOWN] Viewer ready (JS onViewerReady signal)")
                    isPageLoaded = true
                    state.lifecycleState = ViewerLifecycleState.VIEWER_READY
                    binding.layoutMarkdownError.visibility = View.GONE

                    // Sync initial styling properties to CSS variables without reloading
                    setTheme(state.theme)
                    setTextScale(state.textScale)
                    setReadingWidth(state.readingWidth)

                    // Execute any pending render task. NOTE: Loading indicator remains visible until RENDERED!
                    pendingRenderTask?.invoke()
                    pendingRenderTask = null
                }
            }

            @JavascriptInterface
            fun onStageChanged(stage: String) {
                activity.runOnUiThread {
                    when (stage) {
                        "RENDERING" -> {
                            Log.d(TAG, "[MARKDOWN] Render started")
                            state.lifecycleState = ViewerLifecycleState.RENDERING
                        }
                        "MERMAID_LOADING" -> {
                            Log.d(TAG, "[MARKDOWN] Mermaid loading")
                            state.lifecycleState = ViewerLifecycleState.MERMAID_LOADING
                        }
                        "MERMAID_RENDERING" -> {
                            Log.d(TAG, "[MARKDOWN] Mermaid rendering")
                            state.lifecycleState = ViewerLifecycleState.MERMAID_RENDERING
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onTableOfContents(json: String) {
                activity.runOnUiThread {
                    parseTocJson(json)
                }
            }

            @JavascriptInterface
            fun onRenderFinished() {
                activity.runOnUiThread {
                    Log.d(TAG, "[MARKDOWN] Render finished")
                    stopWatchdog()
                    state.lifecycleState = ViewerLifecycleState.RENDERED

                    // ONLY RENDERED hides the progress indicator
                    binding.progressMarkdownLoading.visibility = View.GONE
                    binding.layoutMarkdownError.visibility = View.GONE

                    // Staged restore of saved scroll position if any
                    val key = getDocumentKey()
                    val savedY = scrollPositions[key] ?: state.scrollPosition
                    if (savedY > 0) {
                        setScrollPosition(savedY)
                    }
                }
            }

            @JavascriptInterface
            fun onRenderError(stage: String, error: String) {
                activity.runOnUiThread {
                    Log.e(TAG, "[MARKDOWN] Render error in $stage: $error")
                    stopWatchdog()
                    state.lifecycleState = ViewerLifecycleState.ERROR
                    binding.progressMarkdownLoading.visibility = View.GONE
                    binding.layoutMarkdownError.visibility = View.VISIBLE
                    binding.tvMarkdownErrorDetails.text = "[$stage] $error"
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
        startWatchdog()
    }

    private fun setupToolbar() {
        binding.btnMarkdownBack.setOnClickListener {
            handleBackAction()
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

        binding.btnMarkdownSave.setOnClickListener {
            saveCurrentDocument()
        }

        binding.btnMarkdownSaveAs.setOnClickListener {
            saveAsDocument()
        }
    }

    private fun setupModeSwitcher() {
        binding.toggleMarkdownMode.check(R.id.btnMarkdownTabPreview)
        binding.toggleMarkdownMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnMarkdownTabPreview -> {
                        switchToPreviewMode()
                    }
                    R.id.btnMarkdownTabWrite -> {
                        switchToWriteMode()
                    }
                }
            }
        }
    }

    private fun setupEditor() {
        binding.etMarkdownSource.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (state.isEditMode) {
                    state.isModified = true
                    binding.btnMarkdownSave.visibility = View.VISIBLE
                    binding.btnMarkdownSaveAs.visibility = View.VISIBLE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupErrorView() {
        binding.btnMarkdownRetry.setOnClickListener {
            reload()
        }

        binding.btnMarkdownSwitchToEditor.setOnClickListener {
            switchToWriteMode()
            binding.toggleMarkdownMode.check(R.id.btnMarkdownTabWrite)
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

    fun switchToPreviewMode() {
        if (state.isEditMode) {
            val edited = binding.etMarkdownSource.text.toString()
            state.rawMarkdown = edited
            loadMarkdown(edited, state.documentUri, getDocumentKey())
        }
        state.isEditMode = false
        binding.wvMarkdown.visibility = View.VISIBLE
        binding.layoutMarkdownEditor.visibility = View.GONE
        binding.layoutMarkdownError.visibility = View.GONE

        if (state.isModified) {
            binding.btnMarkdownSave.visibility = View.VISIBLE
            binding.btnMarkdownSaveAs.visibility = View.VISIBLE
        } else {
            binding.btnMarkdownSave.visibility = View.GONE
            binding.btnMarkdownSaveAs.visibility = View.GONE
        }
    }

    fun switchToWriteMode() {
        state.isEditMode = true
        binding.wvMarkdown.visibility = View.GONE
        binding.layoutMarkdownEditor.visibility = View.VISIBLE
        binding.layoutMarkdownError.visibility = View.GONE

        binding.etMarkdownSource.setText(state.rawMarkdown)
        binding.etMarkdownSource.setSelection(binding.etMarkdownSource.text.length)

        binding.btnMarkdownSave.visibility = View.VISIBLE
        binding.btnMarkdownSaveAs.visibility = View.VISIBLE
    }

    fun createNewDocument() {
        state.documentUri = null
        state.documentFile = null
        state.documentTitle = "Untitled.md"
        state.rawMarkdown = "# New Document\n\nWrite your Markdown content here...\n"
        state.isModified = true

        binding.tvMarkdownTitle.text = state.documentTitle
        binding.toggleMarkdownMode.check(R.id.btnMarkdownTabWrite)
        switchToWriteMode()
        Toast.makeText(activity, "New Markdown document created", Toast.LENGTH_SHORT).show()
    }

    fun saveCurrentDocument() {
        val content = if (state.isEditMode) {
            val edited = binding.etMarkdownSource.text.toString()
            state.rawMarkdown = edited
            edited
        } else {
            state.rawMarkdown
        }

        // 1. Direct local file save
        val localFile = state.documentFile
        if (localFile != null && localFile.canWrite()) {
            try {
                localFile.writeText(content, Charsets.UTF_8)
                state.isModified = false
                binding.btnMarkdownSave.visibility = View.GONE
                Toast.makeText(activity, "Saved ${localFile.name}", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing to local file: ${e.message}", e)
            }
        }

        // 2. SAF Content URI in-place overwrite
        val uri = state.documentUri
        if (uri != null && uri.scheme == "content") {
            try {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                state.isModified = false
                binding.btnMarkdownSave.visibility = View.GONE
                Toast.makeText(activity, "Saved document", Toast.LENGTH_SHORT).show()
                return
            } catch (e: Exception) {
                Log.w(TAG, "In-place URI write not permitted or failed: ${e.message}")
            }
        }

        // 3. Fallback to Save As
        Toast.makeText(activity, "Please select destination to save file", Toast.LENGTH_SHORT).show()
        saveAsDocument()
    }

    fun saveAsDocument() {
        val content = if (state.isEditMode) {
            val edited = binding.etMarkdownSource.text.toString()
            state.rawMarkdown = edited
            edited
        } else {
            state.rawMarkdown
        }
        val suggestedName = if (state.documentTitle.endsWith(".md", ignoreCase = true) ||
            state.documentTitle.endsWith(".markdown", ignoreCase = true)) {
            state.documentTitle
        } else {
            "${state.documentTitle}.md"
        }
        onSaveAsMarkdownRequest(suggestedName, content)
    }

    fun onDocumentSavedAs(newUri: Uri, newName: String) {
        state.documentUri = newUri
        state.documentFile = null
        state.documentTitle = newName
        state.isModified = false
        binding.tvMarkdownTitle.text = newName
        binding.btnMarkdownSave.visibility = View.GONE
        Toast.makeText(activity, "Saved as $newName", Toast.LENGTH_SHORT).show()
    }

    /**
     * Handles back press with dirty-state guard. Returns true if consumed (e.g. showing unsaved dialog).
     */
    fun handleBackPressed(): Boolean {
        if (state.isSearchActive) {
            toggleSearch(false)
            return true
        }

        if (state.isModified) {
            AlertDialog.Builder(activity)
                .setTitle("Unsaved changes")
                .setMessage("You have unsaved changes in '${state.documentTitle}'. Do you want to save before leaving?")
                .setPositiveButton("Save") { _, _ ->
                    saveCurrentDocument()
                    onNavigateBack()
                }
                .setNegativeButton("Discard") { _, _ ->
                    state.isModified = false
                    onNavigateBack()
                }
                .setNeutralButton("Cancel", null)
                .show()
            return true
        }

        return false
    }

    private fun handleBackAction() {
        if (!handleBackPressed()) {
            onNavigateBack()
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
        val resolvedTitle = title ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.md"
        Log.d(TAG, "[MARKDOWN] Document loaded: $resolvedTitle")
        state.documentUri = uri
        state.documentFile = null
        state.documentTitle = resolvedTitle
        state.isModified = false
        binding.tvMarkdownTitle.text = resolvedTitle

        binding.progressMarkdownLoading.visibility = View.VISIBLE
        binding.layoutMarkdownError.visibility = View.GONE

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
                state.rawMarkdown = content
                binding.etMarkdownSource.setText(content)
                binding.toggleMarkdownMode.check(R.id.btnMarkdownTabPreview)
                switchToPreviewMode()
                loadMarkdown(content, uri, uri.toString())
            }
        }
    }

    fun openDocument(file: File) {
        Log.d(TAG, "[MARKDOWN] Document loaded: ${file.name}")
        state.documentFile = file
        state.documentUri = Uri.fromFile(file)
        state.documentTitle = file.name
        state.isModified = false
        binding.tvMarkdownTitle.text = file.name

        binding.progressMarkdownLoading.visibility = View.VISIBLE
        binding.layoutMarkdownError.visibility = View.GONE

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read document from File: ${e.message}", e)
                "# Error Opening Document\n\nCould not read `${file.name}`: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                state.rawMarkdown = content
                binding.etMarkdownSource.setText(content)
                binding.toggleMarkdownMode.check(R.id.btnMarkdownTabPreview)
                switchToPreviewMode()
                loadMarkdown(content, Uri.fromFile(file), file.absolutePath)
            }
        }
    }

    fun openMarkdownContent(content: String, title: String, baseUri: Uri? = null) {
        Log.d(TAG, "[MARKDOWN] Document loaded: $title")
        state.documentFile = null
        state.documentUri = baseUri
        state.documentTitle = title
        state.rawMarkdown = content
        state.isModified = false
        binding.tvMarkdownTitle.text = title
        binding.etMarkdownSource.setText(content)
        binding.toggleMarkdownMode.check(R.id.btnMarkdownTabPreview)
        switchToPreviewMode()
        loadMarkdown(content, baseUri, title)
    }

    override fun loadMarkdown(content: String, baseUri: Uri?, documentId: String?) {
        state.rawMarkdown = content
        val sanitized = MarkdownSecurityPolicy.sanitizeMarkdown(content)
        val safeBaseUri = baseUri?.toString() ?: ""

        val execute = {
            state.lifecycleState = ViewerLifecycleState.RENDERING
            Log.d(TAG, "[MARKDOWN] Render started")
            binding.progressMarkdownLoading.visibility = View.VISIBLE
            binding.layoutMarkdownError.visibility = View.GONE
            startWatchdog()

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

    /**
     * Watchdog timer distinguishing stage-specific timeouts:
     * - LOADING_VIEWER: viewer initialization timeout
     * - RENDERING: Markdown rendering timeout
     * - MERMAID_LOADING / MERMAID_RENDERING: Mermaid rendering timeout
     */
    private fun startWatchdog() {
        stopWatchdog()
        watchdogStartTime = System.currentTimeMillis()
        watchdogRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - watchdogStartTime
                val currentState = state.lifecycleState

                val timedOut = when (currentState) {
                    ViewerLifecycleState.LOADING_VIEWER -> elapsed > MAX_INIT_TIMEOUT_MS
                    ViewerLifecycleState.RENDERING -> elapsed > MAX_RENDER_TIMEOUT_MS
                    ViewerLifecycleState.MERMAID_LOADING, ViewerLifecycleState.MERMAID_RENDERING -> elapsed > MAX_MERMAID_TIMEOUT_MS
                    else -> false
                }

                if (timedOut && currentState != ViewerLifecycleState.RENDERED && currentState != ViewerLifecycleState.ERROR) {
                    val stageName = when (currentState) {
                        ViewerLifecycleState.LOADING_VIEWER -> "Viewer Initialization"
                        ViewerLifecycleState.RENDERING -> "Markdown Rendering"
                        ViewerLifecycleState.MERMAID_LOADING -> "Mermaid Loading"
                        ViewerLifecycleState.MERMAID_RENDERING -> "Mermaid Rendering"
                        else -> "Processing"
                    }
                    Log.w(TAG, "[MARKDOWN] Watchdog timeout in stage $stageName after ${elapsed}ms")

                    binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.getDiagnostics() : '{}'") { diag ->
                        Log.w(TAG, "[MARKDOWN] JS diagnostics on timeout: $diag")
                        state.lifecycleState = ViewerLifecycleState.ERROR
                        binding.progressMarkdownLoading.visibility = View.GONE
                        binding.layoutMarkdownError.visibility = View.VISIBLE
                        binding.tvMarkdownErrorDetails.text = "$stageName took longer than expected. Diagnostics: $diag"
                    }
                    return
                }

                if (currentState != ViewerLifecycleState.RENDERED && currentState != ViewerLifecycleState.ERROR) {
                    mainHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(watchdogRunnable!!, WATCHDOG_CHECK_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    override fun reload() {
        loadMarkdown(state.rawMarkdown, state.documentUri, getDocumentKey())
    }

    override fun clear() {
        stopWatchdog()
        state.lifecycleState = ViewerLifecycleState.IDLE
        state.rawMarkdown = ""
        state.tableOfContents = emptyList()
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.render('', '') : null;", null)
    }

    override fun setTheme(theme: MarkdownTheme) {
        state.theme = theme
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.setTheme('${theme.jsValue}') : null;"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun setTextScale(scale: Float) {
        state.textScale = scale.coerceIn(0.75f, 2.0f)
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.setTextScale(${state.textScale}) : null;"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun setReadingWidth(width: ReadingWidth) {
        state.readingWidth = width
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.setReadingWidth('${width.jsValue}') : null;"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun scrollToHeading(id: String) {
        val safeId = escapeJsString(id)
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.scrollToHeading('$safeId') : null;"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun find(query: String) {
        val escaped = escapeJsString(query)
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.find('$escaped') : 0;"
        binding.wvMarkdown.evaluateJavascript(js, null)
    }

    override fun findNext() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.findNext() : null;", null)
    }

    override fun findPrevious() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.findPrevious() : null;", null)
    }

    override fun clearFind() {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.clearFind() : null;", null)
    }

    override fun getScrollPosition(callback: (Int) -> Unit) {
        binding.wvMarkdown.evaluateJavascript("window.VeilFrameMarkdown ? window.VeilFrameMarkdown.getScrollY() : 0;") { res ->
            val pos = res?.toIntOrNull() ?: 0
            callback(pos)
        }
    }

    override fun setScrollPosition(position: Int) {
        val js = "window.VeilFrameMarkdown ? window.VeilFrameMarkdown.setScrollY($position) : null;"
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
        popup.menu.add(0, 20, 0, "New Markdown Document")
        popup.menu.add(0, 10, 0, "Contents (TOC)")
        popup.menu.add(0, 11, 0, "Find in Document")
        popup.menu.add(0, 12, 0, "Open File...")
        popup.menu.add(0, 21, 0, "Save Document")
        popup.menu.add(0, 22, 0, "Save As...")
        popup.menu.add(0, 1, 0, if (state.readingWidth == ReadingWidth.COMFORTABLE) "Width: Full" else "Width: Comfortable")
        popup.menu.add(0, 2, 0, "Font Size...")
        popup.menu.add(0, 3, 0, if (state.theme == MarkdownTheme.DARK) "Theme: Light" else "Theme: Dark")
        popup.menu.add(0, 4, 0, "Copy Raw Markdown")
        popup.menu.add(0, 5, 0, "Share Document...")
        popup.menu.add(0, 7, 0, "Open With...")
        popup.menu.add(0, 6, 0, "Reload")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                20 -> {
                    createNewDocument()
                    true
                }
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
                21 -> {
                    saveCurrentDocument()
                    true
                }
                22 -> {
                    saveAsDocument()
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
        val content = if (state.isEditMode) binding.etMarkdownSource.text.toString() else state.rawMarkdown
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Markdown Document", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(activity, "Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareDocument() {
        try {
            val content = if (state.isEditMode) binding.etMarkdownSource.text.toString() else state.rawMarkdown
            val file = state.documentFile ?: run {
                val tempFile = File(activity.cacheDir, state.documentTitle)
                tempFile.writeText(content, Charsets.UTF_8)
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
