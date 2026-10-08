package com.veilframe.app.upscale.inference

/**
 * Typed failure taxonomy for the AI upscaler pipeline (P0 reliability program).
 *
 * These types let the controller distinguish *honest, user-actionable* failures
 * (degrade to Lanczos, free disk space, pick a smaller scale) from generic
 * errors — instead of crashing with OutOfMemoryError or silently producing
 * nothing.
 */

/** Neural inference exceeded the device memory budget even after tile-halving retries. */
class UpscaleMemoryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A tile could not be read back from the per-job working directory. */
class TileIOException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Not enough free cache storage to stream tile outputs. */
class InsufficientDiskSpaceException(
    message: String,
) : Exception(message)
