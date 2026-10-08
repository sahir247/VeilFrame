package com.veilframe.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * MediaDecoder — the ONE sampled-decode policy for every image entry point
 * (CV plan B5, shared with upscaler F1).
 *
 * Root problem: controllers used to call BitmapFactory.decodeStream raw, so a
 * 48–108 MP photo became a 192–432 MB heap bitmap before any work started —
 * the single biggest crash source in the app. Everything that only needs to
 * LOOK at pixels (previews, analysis) decodes sampled; only export-grade
 * paths take full resolution, and those go through admission budgets.
 */
object MediaDecoder {

    private const val TAG = "VeilFrame.MediaDecoder"

    /** Working-resolution caps by purpose (pixels). Tiers refine these at call sites. */
    const val PREVIEW_PIXEL_CAP = 4_200_000L      // ~2048px long edge
    const val ANALYSIS_PIXEL_CAP = 16_000_000L    // ~4000px long edge

    data class Bounds(val width: Int, val height: Int) {
        val pixelCount: Long get() = width.toLong() * height.toLong()
        val isValid: Boolean get() = width > 0 && height > 0
    }

    fun bounds(resolver: ContentResolver, uri: Uri): Bounds? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) Bounds(opts.outWidth, opts.outHeight) else null
    } catch (e: Exception) {
        Log.w(TAG, "bounds decode failed for $uri", e)
        null
    }

    /** Power-of-two sample factor bringing [width]x[height] under [pixelCap]. */
    fun sampleSizeFor(width: Int, height: Int, pixelCap: Long): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > pixelCap) {
            sample *= 2
        }
        return sample
    }

    /** Sampled decode from a content Uri; returns null on any failure (caller stays honest). */
    fun decodeSampled(
        resolver: ContentResolver,
        uri: Uri,
        pixelCap: Long = PREVIEW_PIXEL_CAP
    ): Bitmap? {
        val b = bounds(resolver, uri) ?: return null
        val sample = sampleSizeFor(b.width, b.height, pixelCap)
        return decodeWithSample(resolver, uri, sample)
    }

    /** Sampled decode from a file (e.g. streamed PNG outputs). */
    fun decodeSampledFile(file: File, pixelCap: Long = PREVIEW_PIXEL_CAP): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val sample = sampleSizeFor(opts.outWidth, opts.outHeight, pixelCap)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (e: Exception) {
            Log.w(TAG, "file decode failed: ${file.name}", e)
            null
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "file decode OOM: ${file.name}", oom)
            null
        }
    }

    private fun decodeWithSample(resolver: ContentResolver, uri: Uri, sample: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "sampled decode failed for $uri", e)
            null
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "sampled decode OOM for $uri", oom)
            null
        }
    }
}
