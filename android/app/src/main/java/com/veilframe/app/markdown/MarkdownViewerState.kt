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

data class TocItem(
    val id: String,
    val title: String,
    val level: Int
)

data class MarkdownViewerState(
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
    var totalMatches: Int = 0
)
