package com.veilframe.app.cv.segmentation.rembg

import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative

/**
 * Manages NCNN native sessions and lifecycle for background removal models.
 * Routes model lifecycle directly to the native Tencent NCNN matting pipeline.
 */
class RembgSessionManager(
    private val modelRepository: RembgModelRepository? = null,
) {
    fun closeSession(model: RembgModel) {
        NcnnMattingNative.nativeRelease()
    }

    fun closeAll() {
        NcnnMattingNative.nativeRelease()
    }
}

@Deprecated(
    message = "Renamed to RembgSessionManager; ONNX Runtime has been completely eliminated.",
    replaceWith = ReplaceWith("RembgSessionManager")
)
typealias RembgOnnxSessionManager = RembgSessionManager
