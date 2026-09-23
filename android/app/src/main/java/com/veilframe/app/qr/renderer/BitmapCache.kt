package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Thread-safe LRU cache for resized source images and reusable layer buffers.
 *
 * Prevents memory allocation churn and OutOfMemoryErrors during live editing.
 * Enforces a strict maximum canvas dimension cap (4096px).
 */
object BitmapCache {

    private const val MAX_DIMENSION = 4096
    private const val CACHE_SIZE_BYTES = 32 * 1024 * 1024 // 32 MB

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            if (evicted && oldValue != newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    /**
     * Obtains or creates a scaled bitmap safely within memory boundaries.
     */
    fun getScaled(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        filter: Boolean = true
    ): Bitmap {
        val safeW = targetWidth.coerceIn(16, MAX_DIMENSION)
        val safeH = targetHeight.coerceIn(16, MAX_DIMENSION)

        if (source.width == safeW && source.height == safeH) {
            return source
        }

        val key = "${source.generationId}_${System.identityHashCode(source)}_${source.width}x${source.height}_${safeW}x${safeH}_$filter"
        synchronized(cache) {
            val cached = cache.get(key)
            if (cached != null && !cached.isRecycled) {
                return cached
            }
        }

        val scaled = Bitmap.createScaledBitmap(source, safeW, safeH, filter)
        synchronized(cache) {
            cache.put(key, scaled)
        }
        return scaled
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }
}
