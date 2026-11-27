package com.veilframe.app.media

/**
 * Immutable export snapshots of image-local edits.
 * A batch is not a global-preset operation: source-specific PII boxes and brush
 * bitmaps must never bleed from the selected photo to other photos.
 */
object ImageStudioBatchEditContract {
    fun snapshot(
        perImage: List<ImageEditState>,
        activeIndex: Int,
        activeDraft: ImageEditState
    ): List<ImageEditState> {
        require(activeIndex in perImage.indices) { "Active image is no longer in the batch" }
        return perImage.mapIndexed { index, state ->
            if (index == activeIndex) activeDraft.deepCopy() else state.deepCopy()
        }
    }
}
