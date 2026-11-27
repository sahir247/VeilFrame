package com.veilframe.app.cv.segmentation.rembg


/**
 * Manages NCNN native sessions and lifecycle for background removal models.
 * Routes model lifecycle directly to the native Tencent NCNN matting pipeline.
 */
class RembgSessionManager(
    private val modelRepository: RembgModelRepository? = null,
) {
    fun closeSession(model: RembgModel) {
        NcnnMattingTransactions.requestRelease()
    }

    fun closeAll() {
        NcnnMattingTransactions.requestRelease()
    }
}

@Deprecated(
    message = "Renamed to RembgSessionManager; ONNX Runtime has been completely eliminated.",
    replaceWith = ReplaceWith("RembgSessionManager")
)
typealias RembgOnnxSessionManager = RembgSessionManager
