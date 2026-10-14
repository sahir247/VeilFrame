package com.veilframe.app.upscale.inference

/**
 * Typed failure taxonomy for the AI upscaler pipeline (P0 reliability program).
 *
 * Distinguishes user-actionable and operational failures:
 * - Resource / memory limits (pick a smaller scale or image)
 * - Missing / uninstalled model (prompt download)
 * - Native model loading corruption
 * - Device inference failures (eligible for CPU retry)
 * - PNG validation failures
 * - User cancellation
 */
interface UpscaleFailure {
    val message: String?
    val cause: Throwable?
}

open class UpscaleException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause), UpscaleFailure

/** Source dimensions, band memory, or pixel count exceeded the device resource budget. */
class ResourceLimitExceededException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause), UpscaleFailure

/** Requested AI model is not installed or missing required param/bin artifacts. */
class ModelNotInstalledException(
    val modelId: String,
    val modelName: String
) : IllegalStateException("Model '$modelName' ($modelId) is not installed on this device."), UpscaleFailure

/** NCNN failed to parse or load graph parameters or quantized weight tensors. */
class NcnnModelLoadException(
    val code: Int,
    message: String
) : IllegalStateException("Failed to load NCNN model (code $code): $message"), UpscaleFailure

/** Native neural inference execution failed (e.g. Vulkan command failure, allocation failure). */
class DeviceInferenceException(
    val code: Int,
    message: String,
    val backend: String = "Vulkan"
) : UpscaleException("NCNN neural inference failed on $backend (code $code): $message")

/** Completed output image failed structural integrity, size, or format verification. */
class ImageValidationException(
    message: String
) : UpscaleException(message)

/** Job aborted cooperatively due to user cancellation or coroutine completion. */
class UpscaleCancellationException(
    message: String = "Upscaling was cancelled by caller"
) : kotlinx.coroutines.CancellationException(message), UpscaleFailure

/** Neural inference exceeded the device memory budget even after tile-halving retries. */
class UpscaleMemoryException(
    message: String,
    cause: Throwable? = null
) : UpscaleException(message, cause)

/** A tile could not be read back from the per-job working directory. */
class TileIOException(
    message: String,
    cause: Throwable? = null
) : UpscaleException(message, cause)

/** Job aborted cooperatively because the device reached CRITICAL thermal status. */
class ThermalShutdownException(
    message: String
) : UpscaleException(message)

/** Not enough free cache storage to stream tile outputs. */
class InsufficientDiskSpaceException(
    message: String
) : UpscaleException(message)
