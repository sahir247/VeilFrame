package com.veilframe.app.cv.segmentation.rembg

import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative

/**
 * Manages NCNN native sessions and lifecycle for background removal models.
 * Kept for interface compatibility with existing controllers, routing model lifecycle
 * directly to the native NCNN matting pipeline.
 */
class RembgOnnxSessionManager(
    private val modelRepository: RembgModelRepository? = null,
) {
    fun closeSession(model: RembgModel) {
        NcnnMattingNative.nativeRelease()
    }

    fun closeAll() {
        NcnnMattingNative.nativeRelease()
    }
}
