package com.veilframe.app.cv.core

import org.opencv.core.Mat

/**
 * Shared value types for the VeilFrame CV Engine.
 *
 * These types are deliberately free of Android framework references so that
 * scheduling, policy and aggregation logic stay unit-testable on the JVM.
 */

/** Dispatch lane. Interactive work must never be starved by video/background work. */
enum class CvPriority {
    /** User-visible, latency-sensitive work (live preview analysis, scanner recovery). */
    INTERACTIVE,

    /** Fire-and-forget work (batch exports, warm-up, cleanup). */
    BACKGROUND,

    /** Sustained frame-by-frame work. Runs in its own lane and must not freeze the UI. */
    VIDEO,
}

/** Lifecycle of a submitted job. */
enum class CvJobState {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** Machine-readable failure taxonomy. */
enum class CvErrorCode {
    /** Cooperative cancellation observed. */
    CANCELLED,

    /** The job's memory estimate exceeds the admission budget. */
    OUT_OF_MEMORY,

    /** Malformed input (empty Mat, unsupported type, negative sizes, ...). */
    INVALID_INPUT,

    /** Requested capability is absent from the pinned OpenCV build. */
    UNSUPPORTED,

    /** The algorithm itself failed or produced no usable output. */
    ALGORITHM_FAILURE,

    /** The OpenCV native library could not be loaded on this device. */
    NATIVE_UNAVAILABLE,

    /** Anything unexpected. */
    INTERNAL,
}

/** A non-fatal observation recorded while a job ran. */
data class CvWarning(
    val code: String,
    val message: String,
)

/** Colour space an image buffer is interpreted in. */
enum class ColorSpace {
    GRAY,
    BGR,
    BGRA,
}

/** Cancellation source shared between a [CvJob] and its running block. */
class CvCancellation {
    @Volatile
    private var cancelled = false

    @Volatile
    private var reason: String? = null

    val isCancelled: Boolean get() = cancelled

    fun cancel(reason: String = "cancelled") {
        if (!cancelled) {
            this.reason = reason
            cancelled = true
        }
    }

    fun reason(): String? = reason

    /** Throws [CvCancelled] if cancellation was requested. */
    fun ensureActive() {
        if (cancelled) throw CvCancelled(reason ?: "cancelled")
    }
}

/** Thrown by [CvCancellation.ensureActive]. */
class CvCancelled(val detail: String) : Exception("cancelled: $detail")

/**
 * Thrown by CvRuntime.requireAvailable() when the OpenCV native library is not
 * loaded. Mapped to [CvErrorCode.NATIVE_UNAVAILABLE] by CvFailureMapper so
 * callers surface an honest "CV engine unavailable" state instead of crashing
 * with UnsatisfiedLinkError or silently fabricating results.
 */
class CvNativeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Bytes actually touched by a job, for observability. */
data class MemoryFootprint(
    val peakBytes: Long,
    val pooledBytes: Long,
    val allocations: Int,
    val reservedBytes: Long = 0L,
)

/**
 * Outcome of a CV job.
 *
 * Successes carry warnings and timings so that callers can surface degraded-but
 * usable results (for example "worked at 1080p instead of source resolution").
 */
sealed class CvResult<out T> {
    data class Ok<T>(
        val value: T,
        val warnings: List<CvWarning> = emptyList(),
        val timingsMs: Map<String, Long> = emptyMap(),
        val memory: MemoryFootprint? = null,
    ) : CvResult<T>()

    data class Err(
        val code: CvErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : CvResult<Nothing>()

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): Err? = this as? Err

    inline fun <R> fold(onOk: (T) -> R, onErr: (Err) -> R): R = when (this) {
        is Ok -> onOk(value)
        is Err -> onErr(this)
    }

    companion object {
        fun <T> ok(value: T, warnings: List<CvWarning> = emptyList()): CvResult<T> =
            Ok(value, warnings)

        fun invalidInput(message: String): CvResult<Nothing> =
            Err(CvErrorCode.INVALID_INPUT, message)

        fun unsupported(message: String): CvResult<Nothing> =
            Err(CvErrorCode.UNSUPPORTED, message)
    }
}

/**
 * Immutable handle to an image buffer flowing through the engine.
 *
 * Ownership semantics:
 * - [wrap]: the caller retains ownership of [mat]; [close] will NOT release it.
 * - [own]:  this handle takes ownership of [mat]; [close] WILL release it.
 *
 * Callers that need long-lived access past the handle's lifetime must clone.
 * Pooled leases never expose ownership here.
 */
class CvImage internal constructor(
    val mat: Mat,
    val colorSpace: ColorSpace,
    /** True when this handle owns [mat] and is responsible for releasing it. */
    val ownsMat: Boolean,
    private val owner: AutoCloseable? = null,
    private val matReleaser: (Mat) -> Unit = { it.release() },
) : AutoCloseable {
    val width: Int get() = mat.cols()
    val height: Int get() = mat.rows()
    val channels: Int get() = mat.channels()
    val isClosed: Boolean get() = closed

    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            owner?.close()
            // Only release the Mat when we own it; wrap() callers retain ownership.
            if (ownsMat) matReleaser(mat)
        }
    }

    companion object {
        /**
         * Wraps [mat] WITHOUT taking ownership.
         *
         * The caller is responsible for releasing [mat]. Closing the returned
         * [CvImage] does NOT call [Mat.release].
         */
        fun wrap(mat: Mat, colorSpace: ColorSpace = colorSpaceOf(mat)): CvImage =
            CvImage(mat, colorSpace, ownsMat = false)

        /**
         * Takes OWNERSHIP of [mat].
         *
         * Closing the returned [CvImage] releases [mat]. The caller must not
         * release [mat] independently after calling this.
         */
        fun own(mat: Mat, colorSpace: ColorSpace = colorSpaceOf(mat)): CvImage =
            CvImage(mat, colorSpace, ownsMat = true)

        fun colorSpaceOf(mat: Mat): ColorSpace = when (mat.channels()) {
            1 -> ColorSpace.GRAY
            4 -> ColorSpace.BGRA
            else -> ColorSpace.BGR
        }
    }
}
