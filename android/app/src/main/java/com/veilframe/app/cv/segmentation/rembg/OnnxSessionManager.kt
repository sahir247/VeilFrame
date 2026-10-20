package com.veilframe.app.cv.segmentation.rembg

/**
 * Backward compatibility alias for [RembgOnnxSessionManager].
 * Use [RembgOnnxSessionManager] directly to avoid shadowing [com.veilframe.app.upscale.inference.OnnxSessionManager].
 */
@Deprecated(
    message = "Use RembgOnnxSessionManager to avoid shadowing upscale OnnxSessionManager",
    replaceWith = ReplaceWith("RembgOnnxSessionManager")
)
typealias OnnxSessionManager = RembgOnnxSessionManager
