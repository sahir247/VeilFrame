package com.veilframe.app.pdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance, LRU-cached thumbnail renderer for PDF pages using Android's native [PdfRenderer].
 */
class PdfThumbnailLoader(private val context: Context) {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 8)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun loadPageThumbnail(uri: Uri, pageIndex: Int, targetWidth: Int = 220): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${uri}_page_${pageIndex}_$targetWidth"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null

        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            page = renderer.openPage(pageIndex)
            val pw = page.width
            val ph = page.height
            val targetHeight = if (pw > 0) ((targetWidth.toFloat() / pw) * ph).toInt().coerceAtLeast(100) else 300

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            memoryCache.put(cacheKey, bitmap)
            bitmap
        } catch (_: Throwable) {
            null
        } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}
