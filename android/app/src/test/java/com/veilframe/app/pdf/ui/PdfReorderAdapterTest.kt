package com.veilframe.app.pdf.ui

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PdfReorderAdapterTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `merge drag inserts rather than swaps nonadjacent files`() {
        val ids = listOf("a", "b", "c", "d")
        val adapter = PdfMergeFileListAdapter(
            context, CoroutineScope(SupervisorJob()), PdfThumbnailLoader(context)
        )
        adapter.setUris(ids.map { Uri.parse("content://vf/$it") })
        adapter.moveItem(0, 3)
        assertEquals(listOf("b", "c", "d", "a"), adapter.getUris().map { it.lastPathSegment })
        adapter.moveItem(3, 1)
        assertEquals(listOf("b", "a", "c", "d"), adapter.getUris().map { it.lastPathSegment })
    }

    @Test
    fun `manual page reorder never duplicates or drops any pages`() {
        val adapter = PdfPageThumbnailAdapter(
            context, CoroutineScope(SupervisorJob()), PdfThumbnailLoader(context)
        ) { }
        adapter.setDocument(Uri.parse("content://vf/example.pdf"), 5)
        adapter.setPageOrder(listOf(5, 5, 2, 2, -1, 99))
        assertEquals(listOf(5, 2, 1, 3, 4), adapter.getPageOrder())
        adapter.moveItem(0, 4)
        assertEquals(listOf(2, 1, 3, 4, 5), adapter.getPageOrder())
    }
}
