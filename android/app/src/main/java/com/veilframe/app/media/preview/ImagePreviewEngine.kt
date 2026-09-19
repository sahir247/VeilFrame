package com.veilframe.app.media.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.transform.ImageTransformEngine
import java.io.File

/**
 * High-performance, memory-safe image preview engine.
 * Never decodes massive 48-200 MP photos into full RAM for interactive UI previews.
 * Uses inJustDecodeBounds + inSampleSize to guarantee bounded heap consumption.
 */
object ImagePreviewEngine {

    private const val TAG = "VeilFrame.ImagePreviewEngine"
    private const val MAX_PREVIEW_DIMENSION = 1280

    data class ImageDimensions(val width: Int, val height: Int)

    fun probeDimensions(file: File): ImageDimensions? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                ImageDimensions(opts.outWidth, opts.outHeight)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to probe dimensions: ${e.message}")
            null
        }
    }

    fun decodePreviewBitmap(file: File, maxDim: Int = MAX_PREVIEW_DIMENSION): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)

            val origW = opts.outWidth
            val origH = opts.outHeight
            if (origW <= 0 || origH <= 0) return null

            var inSample = 1
            while ((origW / inSample) > maxDim || (origH / inSample) > maxDim) {
                inSample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode preview bitmap: ${e.message}", e)
            null
        }
    }

    fun decodeFullResolution(file: File): Bitmap? {
        val dims = probeDimensions(file)
        val origW = dims?.width ?: 0
        val origH = dims?.height ?: 0

        // Only downsample upfront if the raw uncompressed bitmap exceeds 70% of maximum heap
        var initialSample = 1
        val maxBudgetBytes = (Runtime.getRuntime().maxMemory() * 0.70).toLong().coerceAtLeast(64L * 1024 * 1024)
        if (origW > 0 && origH > 0) {
            while (initialSample <= 16) {
                val estW = origW / initialSample
                val estH = origH / initialSample
                val estBytes = estW.toLong() * estH.toLong() * 4L
                if (estBytes <= maxBudgetBytes) {
                    break
                }
                initialSample *= 2
            }
        }

        // Progressive decode with retry fallback loop on OOM up to inSampleSize = 16
        var currentSample = initialSample.coerceAtLeast(1)
        while (currentSample <= 16) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = currentSample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    if (currentSample > 1) {
                        Log.w(
                            TAG,
                            "Decoded export bitmap with inSampleSize=$currentSample (${bmp.width}x${bmp.height}) to protect memory budget."
                        )
                    }
                    return bmp
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "OutOfMemoryError decoding at inSampleSize=$currentSample; triggering GC and retrying with downsampling", oom)
                System.gc()
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding bitmap at inSampleSize=$currentSample: ${e.message}", e)
                break
            }
            currentSample *= 2
        }
        return null
    }

    fun renderPreview(
        previewSource: Bitmap?,
        state: ImageEditState,
        origFullW: Int,
        origFullH: Int
    ): Bitmap? {
        val src = previewSource ?: return null
        return ImageTransformEngine.transform(
            src = src,
            state = state,
            origFullW = origFullW,
            origFullH = origFullH,
            useFullRes = false
        )
    }
}
