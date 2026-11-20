package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import java.io.File

/**
 * Result of an upscale run (F2 band-streaming).
 *
 * Small/medium outputs come back [InMemory] (the historical behavior).
 * Outputs beyond the dynamic RAM budget are composed band-by-band straight
 * into a PNG file ([Streamed]) — the full-size bitmap NEVER exists in RAM,
 * which is what made 24–150 MP outputs crash on 6–16 GB devices.
 */
sealed class UpscaleOutput {

    /** Output held as a bitmap (within the dynamic in-RAM budget). */
    data class InMemory(val bitmap: Bitmap) : UpscaleOutput() {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    /**
     * Output written to [file] (valid PNG, RGBA). [preview] is a sampled
     * (≤2048px) bitmap for display; [width]/[height] are the FULL output
     * dimensions. The file lives in the app cache until the controller moves
     * (save/share) or replaces it.
     */
    data class Streamed(
        val file: File,
        val preview: Bitmap?,
        override val width: Int,
        override val height: Int,
        /** Backend of the completed native attempt; null for non-neural outputs. */
        val inferenceBackend: String? = null,
    ) : UpscaleOutput()

    /** Only for unpublished output; a controller takes ownership after delivery. */
    internal fun discardUnclaimed() {
        when (this) {
            is InMemory -> if (!bitmap.isRecycled) bitmap.recycle()
            is Streamed -> {
                preview?.let { if (!it.isRecycled) it.recycle() }
                file.delete()
            }
        }
    }

    abstract val width: Int
    abstract val height: Int

    /** Best bitmap for display without materializing the full output. Null if preview generation was skipped or failed. */
    fun previewBitmap(): Bitmap? = when (this) {
        is InMemory -> bitmap
        is Streamed -> preview
    }
}
