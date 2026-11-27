package com.veilframe.app.media.preview

import android.graphics.Bitmap
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.transform.ImageTransformEngine

/**
 * Rendering source/edit isolation for the authoritative main Image Studio
 * preview. PII solid redaction edits mutable bitmaps in place: never pass the
 * per-image baseline thumbnail into a transform without making an owned copy.
 *
 * The caller owns the returned bitmap and must release it after removing it
 * from the ImageView. This function never recycles or mutates [source].
 */
object IsolatedImagePreviewRenderer {
    fun render(
        source: Bitmap,
        state: ImageEditState,
        originalWidth: Int,
        originalHeight: Int
    ): Bitmap {
        require(!source.isRecycled) { "Preview source was already retired" }
        val ownedInput = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Unable to allocate an isolated preview bitmap")
        try {
            val result = ImageTransformEngine.transform(
                src = ownedInput,
                state = state,
                origFullW = originalWidth,
                origFullH = originalHeight,
                useFullRes = false
            )
            if (result !== ownedInput) ownedInput.recycle()
            return result
        } catch (failure: Throwable) {
            if (!ownedInput.isRecycled) ownedInput.recycle()
            throw failure
        }
    }
}
