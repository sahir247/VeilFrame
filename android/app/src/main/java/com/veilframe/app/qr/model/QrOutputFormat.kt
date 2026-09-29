package com.veilframe.app.qr.model

import com.veilframe.app.qr.error.QrError

/**
 * Public output format abstraction mirroring EFQRCode generator surface:
 * PNG, JPEG, SVG, GIF, MP4, MOV.
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

    data class Gif(val loopCount: Int = 0) : QrOutputFormat {
        override val mimeType: String = "image/gif"
        override val extension: String = "gif"
    }

    data class Video(
        val fps: Int = 15,
        val isMov: Boolean = false
    ) : QrOutputFormat {
        init {
            require(fps in 1..120) { "Video FPS must be between 1 and 120" }
        }
        override val mimeType: String = if (isMov) "video/quicktime" else "video/mp4"
        override val extension: String = if (isMov) "mov" else "mp4"
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
