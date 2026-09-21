package com.veilframe.app.media

import android.net.Uri
import java.io.File
import java.util.UUID

enum class ProcessingState {
    PROCESS_SUCCEEDED,
    PROCESS_FAILED,
    CANCELLED
}

enum class ExportState {
    EXPORT_SUCCEEDED,
    EXPORT_FAILED,
    NOT_REQUESTED
}

data class ProcessingError(
    val code: String,
    val message: String,
    val cause: Throwable? = null
)

/**
 * Standardized, unambiguous processing result model.
 * Clearly separates encoding/processing success from SAF storage export success.
 */
data class ProcessingResult(
    val jobId: UUID,
    val sourceUri: Uri,
    val localOutput: File?,
    val exportedUri: Uri?,
    val processingState: ProcessingState,
    val exportState: ExportState,
    val outputBytes: Long?,
    val error: ProcessingError? = null
) {
    val isFullySuccessful: Boolean
        get() = processingState == ProcessingState.PROCESS_SUCCEEDED &&
                (exportState == ExportState.EXPORT_SUCCEEDED || exportState == ExportState.NOT_REQUESTED)
}
