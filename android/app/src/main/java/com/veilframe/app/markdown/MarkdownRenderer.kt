package com.veilframe.app.markdown

import android.net.Uri

interface MarkdownRenderer {
    fun loadMarkdown(content: String, baseUri: Uri?, documentId: String?)
    fun reload()
    fun clear()
    fun setTheme(theme: MarkdownTheme)
    fun setTextScale(scale: Float)
    fun setReadingWidth(width: ReadingWidth)
    fun scrollToHeading(id: String)
    fun find(query: String)
    fun findNext()
    fun findPrevious()
    fun clearFind()
    fun getScrollPosition(callback: (Int) -> Unit)
    fun setScrollPosition(position: Int)
}
