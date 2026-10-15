package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build

/**
 * F1 (memory correctness): abstraction over the upscale SOURCE image.
 *
 * Two implementations:
 *  - [BitmapSource]: the whole source is already in memory (small/medium images
 *    within the heap budget) — zero-copy region access.
 *  - [UriRegionSource]: the source NEVER lives in memory as a whole. Tiles are
 *    region-decoded from the content Uri on demand, so a 48–108 MP photo can be
 *    upscaled without a 192–432 MB source bitmap (the old pipeline OOM-crashed
 *    or lagged the whole device here).
 *
 * Ownership contract:
 *  - [full] returns a bitmap the caller must NOT recycle (it may be the shared
 *    in-memory source).
 *  - [region] returns a bitmap the caller OWNS and must recycle when done.
 */
interface SourceAccess : AutoCloseable {
    val width: Int
    val height: Int
    val hasAlpha: Boolean get() = true

    /** Full source. Caller must NOT recycle the result. */
    fun full(): Bitmap

    /** Owner-transfer: caller MUST recycle the returned bitmap. */
    fun region(x: Int, y: Int, width: Int, height: Int): Bitmap

    override fun close() {}
}

/** In-memory source (image fit within the admission budget). */
class BitmapSource(private val bitmap: Bitmap) : SourceAccess {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val hasAlpha: Boolean get() = bitmap.hasAlpha()

    override fun full(): Bitmap = bitmap

    override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap {
        val subset = Bitmap.createBitmap(bitmap, x, y, width, height)
        // createBitmap MAY return the shared original when the rect covers it
        // exactly; never hand out a bitmap whose recycle() would kill the source.
        return if (subset === bitmap) {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            subset
        }
    }
}

/**
 * Streaming source: maintains a cached, mutex-synchronized BitmapRegionDecoder
 * so strip/tile fetches read directly from the open stream without re-parsing
 * image headers on every tile.
 */
class UriRegionSource(
    private val context: Context,
    private val uri: Uri,
    override val width: Int,
    override val height: Int,
) : SourceAccess {

    private val decoderLock = Any()
    private var cachedStream: java.io.InputStream? = null
    private var cachedDecoder: BitmapRegionDecoder? = null
    private var closed = false

    override val hasAlpha: Boolean by lazy {
        try {
            val probe = region(0, 0, 1.coerceAtMost(width), 1.coerceAtMost(height))
            val alpha = probe.hasAlpha()
            probe.recycle()
            alpha
        } catch (_: Throwable) {
            true
        }
    }

    private fun obtainDecoderLocked(): BitmapRegionDecoder {
        check(!closed) { "UriRegionSource is already closed" }
        val existing = cachedDecoder
        if (existing != null && !existing.isRecycled) {
            return existing
        }
        runCatching { cachedStream?.close() }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw TileIOException("content resolver returned no stream for $uri")
        val decoder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw TileIOException("BitmapRegionDecoder failed to open $uri: ${t.message}", t)
        } ?: run {
            runCatching { stream.close() }
            throw TileIOException("BitmapRegionDecoder could not open $uri")
        }
        cachedStream = stream
        cachedDecoder = decoder
        return decoder
    }

    override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap {
        synchronized(decoderLock) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rect = Rect(x, y, x + width, y + height)
            return try {
                obtainDecoderLocked().decodeRegion(rect, options)
            } catch (_: Throwable) {
                // Reset decoder once if underlying stream invalidated
                resetDecoderLocked()
                obtainDecoderLocked().decodeRegion(rect, options)
            } ?: throw TileIOException("region decode returned null (${x},${y} ${width}x${height})")
        }
    }

    override fun full(): Bitmap {
        // Callers must budget-check before invoking this on a streaming source
        // (the controller refuses algorithmic paths that require it).
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw TileIOException("content resolver returned no stream for $uri")
        return stream.use { input ->
            BitmapFactory.decodeStream(input)
                ?: throw TileIOException("full decode returned null for $uri")
        }
    }

    private fun resetDecoderLocked() {
        runCatching { cachedDecoder?.recycle() }
        cachedDecoder = null
        runCatching { cachedStream?.close() }
        cachedStream = null
    }

    override fun close() {
        synchronized(decoderLock) {
            closed = true
            resetDecoderLocked()
        }
    }
}
