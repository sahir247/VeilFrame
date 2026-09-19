package com.veilframe.app.markdown

import android.net.Uri
import java.io.File

enum class ReadingWidth(val jsValue: String) {
    COMFORTABLE("comfortable"),
    FULL("full")
}

enum class MarkdownTheme(val jsValue: String) {
    DARK("dark"),
    LIGHT("light")
}

enum class ViewerLifecycleState {
    IDLE,
    LOADING_VIEWER,
    VIEWER_READY,
    RENDERING,
    MERMAID_LOADING,
    MERMAID_RENDERING,
    RENDERED,
    ERROR
}

data class TocItem(
    val id: String,
    val title: String,
    val level: Int
)

data class MarkdownViewerState(
    var lifecycleState: ViewerLifecycleState = ViewerLifecycleState.IDLE,
    var documentUri: Uri? = null,
    var documentFile: File? = null,
    var documentTitle: String = "Document.md",
    var rawMarkdown: String = "",
    var theme: MarkdownTheme = MarkdownTheme.DARK,
    var readingWidth: ReadingWidth = ReadingWidth.COMFORTABLE,
    var textScale: Float = 1.0f,
    var scrollPosition: Int = 0,
    var tableOfContents: List<TocItem> = emptyList(),
    var isSearchActive: Boolean = false,
    var searchQuery: String = "",
    var currentMatchIndex: Int = 0,
    var totalMatches: Int = 0,
    var isEditMode: Boolean = false,
    var isModified: Boolean = false
)
