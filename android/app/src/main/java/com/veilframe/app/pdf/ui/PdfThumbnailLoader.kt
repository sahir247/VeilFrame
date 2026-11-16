package com.veilframe.app.pdf.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounds thumbnails to a predictable pixel envelope while preserving page aspect ratio. */
internal object PdfThumbnailSizing {
    fun calculate(pageWidth: Int, pageHeight: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int>? {
        if (pageWidth <= 0 || pageHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) return null
        val scale = min(maxWidth.toFloat() / pageWidth, maxHeight.toFloat() / pageHeight)
        return maxOf(1, (pageWidth * scale).roundToInt()) to
            maxOf(1, (pageHeight * scale).roundToInt())
    }
}

/**
 * Process-wide, memory-bounded PDF thumbnail renderer.
 * Rendering is limited to two concurrent PdfRenderer instances. The cache has an absolute
 * upper bound independent of largeHeap, and Android memory-trim events release cached bitmaps.
 */
class PdfThumbnailLoader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun loadPageThumbnail(uri: Uri, pageIndex: Int, targetWidth: Int = DEFAULT_WIDTH): Bitmap? =
        withContext(Dispatchers.IO) {
            if (pageIndex < 0) return@withContext null
            val boundedWidth = targetWidth.coerceIn(MIN_WIDTH, MAX_WIDTH)
            val key = "$uri-page-$pageIndex-width-$boundedWidth"
            memoryCache.get(key)?.let { return@withContext it }

            renderSemaphore.withPermit {
                memoryCache.get(key)?.let { return@withPermit it }
                renderThumbnail(uri, pageIndex, boundedWidth, key)
            }
        }

    private fun renderThumbnail(uri: Uri, pageIndex: Int, targetWidth: Int, key: String): Bitmap? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(descriptor)
            if (pageIndex >= renderer.pageCount) return null
            page = renderer.openPage(pageIndex)

            val dimensions = PdfThumbnailSizing.calculate(
                page.width, page.height, targetWidth, MAX_HEIGHT
            ) ?: return null
            bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            memoryCache.put(key, bitmap)
            return bitmap
        } catch (cancelled: CancellationException) {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            throw cancelled
        } catch (_: OutOfMemoryError) {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            memoryCache.evictAll()
            return null
        } catch (_: Exception) {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            return null
        } finally {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    fun clearCache() = memoryCache.evictAll()

    companion object {
        private const val DEFAULT_WIDTH = 220
        private const val MIN_WIDTH = 64
        private const val MAX_WIDTH = 512
        private const val MAX_HEIGHT = 1024
        private const val MIB = 1024 * 1024
        private val cacheMaxBytes = (Runtime.getRuntime().maxMemory() / 16L)
            .coerceIn(2L * MIB, 32L * MIB).toInt()

        private val memoryCache = object : LruCache<String, Bitmap>(cacheMaxBytes) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.allocationByteCount
        }
        private val renderSemaphore = Semaphore(permits = 2)

        /** Called by the Application when Android reports process memory pressure. */
        fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> memoryCache.evictAll()
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> memoryCache.trimToSize(cacheMaxBytes / 4)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> memoryCache.trimToSize(cacheMaxBytes / 2)
            }
        }

        fun clearAllCaches() = memoryCache.evictAll()
    }
}
