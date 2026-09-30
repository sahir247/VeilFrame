package com.veilframe.app.qr.model

import com.veilframe.app.qr.error.QrError

/**
 * Public output format abstraction establishing full parity with EFQRCode generator surface:
 * Static: PNG, JPEG, SVG, PDF
 * Animated: GIF, APNG, SVG, MP4, MOV, M4V.
 */
sealed interface QrOutputFormat {
    val mimeType: String
    val extension: String

    data object Png : QrOutputFormat {
        override val mimeType: String = "image/png"
        override val extension: String = "png"
    }

    data class Jpeg(val quality: Int = 90) : QrOutputFormat {
        init {
            require(quality in 1..100) { "JPEG quality must be between 1 and 100" }
        }
        override val mimeType: String = "image/jpeg"
        override val extension: String = "jpg"
    }

    data object Svg : QrOutputFormat {
        override val mimeType: String = "image/svg+xml"
        override val extension: String = "svg"
    }

    data class Pdf(
        val pageWidthPoints: Int = 595,
        val pageHeightPoints: Int = 842
    ) : QrOutputFormat {
        override val mimeType: String = "application/pdf"
        override val extension: String = "pdf"
    }

    data class Gif(val loopCount: Int = 0) : QrOutputFormat {
        override val mimeType: String = "image/gif"
        override val extension: String = "gif"
    }

    data class Apng(
        val loopCount: Int = 0,
        val fps: Int = 15
    ) : QrOutputFormat {
        init {
            require(fps in 1..120) { "APNG FPS must be between 1 and 120" }
        }
        override val mimeType: String = "image/apng"
        override val extension: String = "png"
    }

    enum class VideoContainer(val ext: String, val mime: String) {
        MP4("mp4", "video/mp4"),
        MOV("mov", "video/quicktime"),
        M4V("m4v", "video/x-m4v")
    }

    data class Video(
        val fps: Int = 15,
        val container: VideoContainer = VideoContainer.MP4
    ) : QrOutputFormat {
        init {
            require(fps in 1..120) { "Video FPS must be between 1 and 120" }
        }

        constructor(fps: Int, isMov: Boolean) : this(
            fps = fps,
            container = if (isMov) VideoContainer.MOV else VideoContainer.MP4
        )

        val isMov: Boolean get() = container == VideoContainer.MOV

        override val mimeType: String get() = container.mime
        override val extension: String get() = container.ext
    }
}

/**
 * Disciplined public result boundary for QR export and output operations,
 * guaranteeing callers receive either a typed value or a structured [QrError].
 */
sealed interface QrOutputResult<out T> {
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): QrError? = (this as? Failure)?.error

    data class Success<T>(val value: T) : QrOutputResult<T>
    data class Failure(val error: QrError) : QrOutputResult<Nothing>
}
