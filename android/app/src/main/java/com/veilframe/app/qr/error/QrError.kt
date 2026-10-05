package com.veilframe.app.qr.error

import com.veilframe.app.qr.QrStyle

/**
 * Unified, authoritative domain error taxonomy for VeilFrame QR Engine.
 *
 * Implements architectural parity with EFQRCode's typed error boundary ([EFQRCodeError]),
 * replacing opaque string/Throwable collapsing with structured domain categories:
 * - [Input]: Blank or malformed QR payloads
 * - [Encoding]: Matrix generation, capacity limits, Reed-Solomon polynomial math
 * - [Design]: Invalid geometry, dimensions, or style configurations
 * - [Image]: Source image/logo decoding, preprocessing, and resizing
 * - [Rendering]: Android Bitmap/Canvas, style shaders, or SVG vector markup
 * - [Animation]: Multi-frame timeline reconciliation and frame sequences
 * - [Output]: Media encoding (PNG, JPEG, SVG, GIF, MP4, MOV)
 * - [Platform]: File I/O, scoped storage permissions, MediaStore URIs
 * - [Internal]: Unhandled system exceptions
 */
sealed class QrError(
    open val description: String,
    override val cause: Throwable? = null
) : RuntimeException(description, cause) {

    /**
     * Input & Payload errors.
     */
    sealed class Input(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data object EmptyContent : Input("QR content must not be blank")
        data class InvalidPayload(val reason: String, override val cause: Throwable? = null) :
            Input("Invalid QR payload: $reason", cause)
        data class UnsupportedPayload(val scheme: String, val reason: String) :
            Input("Unsupported payload scheme '$scheme': $reason")
    }

    /**
     * Data encoding & QR specification capacity errors.
     */
    sealed class Encoding(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class CapacityExceeded(
            val actualBytes: Int,
            val limitBytes: Int,
            override val cause: Throwable? = null
        ) : Encoding(
            if (actualBytes > 0 && limitBytes > 0)
                "Data length ($actualBytes bytes) exceeds maximum QR Code capacity limit ($limitBytes bytes)"
            else "QR payload exceeds maximum data capacity for selected version and error correction",
            cause
        )

        data class EngineFailure(
            val engine: String,
            val detail: String,
            override val cause: Throwable? = null
        ) : Encoding("Encoder '$engine' failure: $detail", cause)

        data class InvalidErrorCorrection(val detail: String) :
            Encoding("Invalid error correction specification: $detail")
    }

    /**
     * QR Design, geometry, and styling configuration errors.
     */
    sealed class Design(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class InvalidParameter(
            val parameterName: String,
            val value: Any?,
            val reason: String
        ) : Design("Invalid design parameter '$parameterName' ($value): $reason")

        data class InvalidGeometry(
            val width: Int,
            val height: Int,
            val reason: String
        ) : Design("Invalid geometry dimensions (${width}x${height}): $reason")

        data class InvalidStyleConfiguration(
            val style: QrStyle,
            val reason: String
        ) : Design("Invalid configuration for style '${style.name}': $reason")
    }

    /**
     * Media import, logo, and source image errors.
     */
    sealed class Image(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class DecodeFailed(
            val uriOrPath: String?,
            override val cause: Throwable? = null
        ) : Image("Failed to decode image from source: ${uriOrPath ?: "unknown"}", cause)

        data class PreprocessFailed(
            val stage: String,
            override val cause: Throwable? = null
        ) : Image("Image preprocessing failed at stage '$stage'", cause)

        data class ResizeFailed(
            val targetWidth: Int,
            val targetHeight: Int,
            override val cause: Throwable? = null
        ) : Image("Failed to resize image to ${targetWidth}x${targetHeight}", cause)

        data class InvalidFrame(
            val frameIndex: Int,
            val reason: String
        ) : Image("Invalid frame at index $frameIndex: $reason")

        data class UnmaterializedSource(
            val sourceDescription: String
        ) : Image("Image source '$sourceDescription' must be materialized into a Bitmap before rendering")
    }

    /**
     * Rendering & Canvas generation errors.
     */
    sealed class Rendering(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class BitmapAllocationFailed(
            val width: Int,
            val height: Int,
            override val cause: Throwable? = null
        ) : Rendering("Failed to allocate Bitmap buffer for ${width}x${height} QR render", cause)

        data class CanvasRenderFailed(
            val stage: String,
            override val cause: Throwable? = null
        ) : Rendering("Canvas draw failed during '$stage'", cause)

        data class StyleRenderFailed(
            val style: QrStyle,
            override val cause: Throwable? = null
        ) : Rendering("Artistic style renderer '${style.name}' failed during generation", cause)

        data class SvgRenderFailed(
            val detail: String,
            override val cause: Throwable? = null
        ) : Rendering("Vector SVG generation failed: $detail", cause)
    }

    /**
     * Multi-frame animated QR sequence errors.
     */
    sealed class Animation(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data object EmptyFrames : Animation("Animation pipeline requires at least one frame")
        data class InvalidTimeline(val reason: String) : Animation("Invalid animation timeline synchronization: $reason")
        data class FrameRenderFailed(
            val frameIndex: Int,
            override val cause: Throwable? = null
        ) : Animation("Failed rendering animation frame $frameIndex", cause)
        data class EncodingFailed(
            val targetFormat: String,
            override val cause: Throwable? = null
        ) : Animation("Animated sequence encoding failed for format '$targetFormat'", cause)
    }

    /**
     * Export & output format encoding errors.
     */
    sealed class Output(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class PngEncodingFailed(
            val reason: String = "Bitmap compression failed for PNG",
            override val cause: Throwable? = null
        ) : Output("PNG export failed: $reason", cause)

        data class JpegEncodingFailed(
            val reason: String = "Bitmap compression failed for JPEG",
            override val cause: Throwable? = null
        ) : Output("JPEG export failed: $reason", cause)

        data class SvgExportFailed(
            val reason: String,
            override val cause: Throwable? = null
        ) : Output("SVG file write failed: $reason", cause)

        data class GifEncodingFailed(
            val reason: String,
            override val cause: Throwable? = null
        ) : Output("GIF encoding failed: $reason", cause)

        data class ApngEncodingFailed(
            val reason: String,
            override val cause: Throwable? = null
        ) : Output("APNG encoding failed: $reason", cause)

        data class PdfExportFailed(
            val reason: String,
            override val cause: Throwable? = null
        ) : Output("PDF export failed: $reason", cause)

        data class VideoEncodingFailed(
            val stage: String,
            val exitCode: Int? = null,
            override val cause: Throwable? = null
        ) : Output(
            "Video encoding failed at stage '$stage'${if (exitCode != null) " (exit code: $exitCode)" else ""}",
            cause
        )
    }

    /**
     * Platform, MediaStore, and File System errors.
     */
    sealed class Platform(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class PermissionDenied(val permission: String) :
            Platform("System permission denied: $permission")

        data class StorageFailed(
            val targetPath: String?,
            override val cause: Throwable? = null
        ) : Platform("Storage I/O failed while writing to: ${targetPath ?: "unknown"}", cause)

        data class ExportUriUnavailable(val reason: String) :
            Platform("MediaStore URI could not be allocated: $reason")
    }

    /**
     * Strict scanability and verification errors.
     */
    sealed class Validation(description: String, cause: Throwable? = null) : QrError(description, cause) {
        data class ScanabilityFailed(
            val reason: String,
            val warnings: List<String> = emptyList()
        ) : Validation("QR scanability validation failed: $reason")

        data class VectorRasterizationFailed(
            val detail: String = "Vector SVG verification failed (rasterization error)"
        ) : Validation("Vector SVG verification failed: $detail")
    }

    /**
     * Unclassified internal exceptions.
     */
    data class Internal(
        override val description: String,
        override val cause: Throwable? = null
    ) : QrError(description, cause)

    companion object {
        /**
         * Translates arbitrary underlying exceptions (ZXing, Android OS, I/O, OOM)
         * into a strongly typed [QrError] preserving causes and structured diagnostic fields.
         */
        fun fromThrowable(throwable: Throwable?, fallbackMessage: String? = null): QrError {
            if (throwable == null) {
                val msg = fallbackMessage ?: "Unknown QR error"
                return if (msg.contains("blank", ignoreCase = true) || msg.contains("empty", ignoreCase = true)) {
                    Input.EmptyContent
                } else {
                    Internal(msg)
                }
            }

            if (throwable is QrError) return throwable

            val msg = if (!fallbackMessage.isNullOrBlank()) fallbackMessage else (throwable.message ?: throwable.javaClass.simpleName)

            // Coroutine cancellation must propagate unmodified
            if (throwable is kotlinx.coroutines.CancellationException) {
                throw throwable
            }

            // OOM / Memory allocation errors
            if (throwable is OutOfMemoryError || msg.contains("OutOfMemory", ignoreCase = true)) {
                return Rendering.BitmapAllocationFailed(0, 0, throwable)
            }

            // Input validation errors
            if (throwable is IllegalArgumentException &&
                (msg.contains("blank", ignoreCase = true) || msg.contains("empty", ignoreCase = true))
            ) {
                return Input.EmptyContent
            }

            // Capacity & Data limits vs General ZXing WriterException
            val isCapacityLimit = msg.contains("capacity", ignoreCase = true) ||
                msg.contains("exceeds", ignoreCase = true) ||
                msg.contains("data too big", ignoreCase = true) ||
                msg.contains("LimitLength", ignoreCase = true) ||
                msg.contains("Data length", ignoreCase = true)

            if (isCapacityLimit) {
                val match = Regex("""(\d+)\s*bytes.*?(\d+)\s*bytes""", RegexOption.IGNORE_CASE).find(msg)
                val actual = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val limit = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
                return Encoding.CapacityExceeded(actual, limit, throwable)
            }

            if (throwable is com.google.zxing.WriterException) {
                return Encoding.EngineFailure("ZXing", msg, throwable)
            }

            // General IllegalArgumentException
            if (throwable is IllegalArgumentException) {
                return Design.InvalidParameter("content/design", null, msg)
            }

            // Security permissions
            if (throwable is SecurityException) {
                return Platform.PermissionDenied(msg)
            }

            // File / MediaStore storage errors
            if (throwable is java.io.IOException) {
                return Platform.StorageFailed(null, throwable)
            }

            return Internal(msg, throwable)
        }
    }
}
