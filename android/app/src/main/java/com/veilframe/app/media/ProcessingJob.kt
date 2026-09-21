package com.veilframe.app.media

import android.net.Uri
import java.util.UUID

enum class JobType {
    IMAGE_TRANSFORM,
    VIDEO_COMPRESSION,
    WHATSAPP_STATUS,
    METADATA_STRIP,
    UPSCALE,
    AI_ANALYZE
}

enum class JobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Authoritative job identity model for foreground services and long-running background tasks.
 * Prevents concurrency collisions and provides deterministic cancellation and progress tracking.
 */
data class ProcessingJob(
    val id: UUID = UUID.randomUUID(),
    val type: JobType,
    val inputUri: Uri,
    val outputUri: Uri? = null,
    val state: JobState = JobState.QUEUED,
    val startedAt: Long = System.currentTimeMillis(),
    val progressPercent: Int = 0,
    val errorMessage: String? = null
)
