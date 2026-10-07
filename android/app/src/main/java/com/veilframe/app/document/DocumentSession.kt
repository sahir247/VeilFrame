package com.veilframe.app.document

import android.graphics.Bitmap
import android.net.Uri
import com.veilframe.app.cv.document.DocumentScanner
import java.util.UUID

/**
 * Single captured page inside a multi-page document scanning session.
 */
data class ScannedPage(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri? = null,
    val originalBitmap: Bitmap,
    var processedBitmap: Bitmap? = null,
    var mode: DocumentScanner.DocumentMode = DocumentScanner.DocumentMode.ENHANCED,
    var corners: List<org.opencv.core.Point>? = null
)

/**
 * Manages the in-memory state of an active multi-page document scanning session.
 * Decoupled from legacy ImageStudio single-photo edit flows.
 */
class DocumentSession {
    private val _pages = mutableListOf<ScannedPage>()
    val pages: List<ScannedPage> get() = _pages.toList()

    var activePageIndex: Int = 0
        private set

    fun addPage(page: ScannedPage) {
        _pages.add(page)
        activePageIndex = _pages.size - 1
    }

    fun addPages(newPages: List<ScannedPage>) {
        if (newPages.isNotEmpty()) {
            _pages.addAll(newPages)
            activePageIndex = _pages.size - 1
        }
    }

    fun removePage(index: Int) {
        if (index in _pages.indices) {
            _pages.removeAt(index)
            if (activePageIndex >= _pages.size) {
                activePageIndex = (_pages.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun selectPage(index: Int) {
        if (index in _pages.indices) {
            activePageIndex = index
        }
    }

    val currentPage: ScannedPage?
        get() = _pages.getOrNull(activePageIndex)

    val pageCount: Int
        get() = _pages.size

    val isEmpty: Boolean
        get() = _pages.isEmpty()

    fun clear() {
        _pages.clear()
        activePageIndex = 0
    }
}
