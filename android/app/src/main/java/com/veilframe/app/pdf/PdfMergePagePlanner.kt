package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.pdf.operations.MergeItem
import com.veilframe.app.pdf.operations.MergeItemType
import com.veilframe.app.pdf.operations.MergePageItem
import com.veilframe.app.pdf.operations.PdfMerger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inspect every selected source before constructing a complete, bounded page plan. */
internal object PdfMergePagePlanner {
    const val MAX_PREVIEW_PAGES = 10_000

    suspend fun build(context: Context, sources: List<Uri>, name: (Uri) -> String): List<MergePageItem> {
        require(sources.isNotEmpty() && sources.size <= MAX_PREVIEW_PAGES) { "Invalid merge source count" }
        var total = 0L
        val items = sources.mapIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val image = mime.startsWith("image/") || listOf(".jpg", ".jpeg", ".png", ".webp")
                .any { uri.toString().endsWith(it, ignoreCase = true) }
            val count = if (image) 1 else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { it.numberOfPages }
                } ?: throw IllegalStateException("Cannot open ${name(uri)}")
            }
            require(count > 0) { "A selected PDF has no pages" }
            total += count.toLong()
            require(total <= MAX_PREVIEW_PAGES) { "Page-by-page merge supports at most $MAX_PREVIEW_PAGES pages" }
            MergeItem(id = "doc_$index", uri = uri, name = name(uri),
                type = if (image) MergeItemType.IMAGE else MergeItemType.PDF, pageCount = count)
        }
        return PdfMerger().buildPageItems(items).also {
            check(it.size.toLong() == total) { "Incomplete page plan" }
        }
    }
}
