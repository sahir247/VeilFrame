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
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding full-resolution bitmap; falling back to downsampled", e)
            // Fallback: 2x sample if device is memory constrained
            val fallbackOpts = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, fallbackOpts)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap: ${e.message}", e)
            null
        }
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
