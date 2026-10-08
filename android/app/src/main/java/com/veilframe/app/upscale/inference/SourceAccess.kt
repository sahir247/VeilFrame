package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri

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
interface SourceAccess {
    val width: Int
    val height: Int

    /** Full source. Caller must NOT recycle the result. */
    fun full(): Bitmap

    /** Owner-transfer: caller MUST recycle the returned bitmap. */
    fun region(x: Int, y: Int, width: Int, height: Int): Bitmap

    fun close() {}
}

/** In-memory source (image fit within the admission budget). */
class BitmapSource(private val bitmap: Bitmap) : SourceAccess {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun full(): Bitmap = bitmap

    override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap {
        val subset = Bitmap.createBitmap(bitmap, x, y, width, height)
        // createBitmap MAY return the shared original when the rect covers it
        // exactly; never hand out a bitmap whose recycle() would kill the source.
        return if (subset === bitmap) bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        else subset
    }
}

/**
 * Streaming source: per-tile BitmapRegionDecoder reads straight from the
 * content resolver. Decoder instances are created per region call (cheap
 * relative to inference) to stay thread-safe across tile workers.
 */
class UriRegionSource(
    private val context: Context,
    private val uri: Uri,
    override val width: Int,
    override val height: Int,
) : SourceAccess {

    override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw TileIOException("content resolver returned no stream for $uri")
        return stream.use { input ->
            @Suppress("DEPRECATION") // BitmapRegionDecoder.newInstance: API 10+, deprecated but functional on 31+
            val decoder = BitmapRegionDecoder.newInstance(input)
                ?: throw TileIOException("BitmapRegionDecoder could not open $uri")
            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decoder.decodeRegion(Rect(x, y, x + width, y + height), options)
                    ?: throw TileIOException("region decode returned null (${x},${y} ${width}x${height})")
            } finally {
                decoder.recycle()
            }
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
}
