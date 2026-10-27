package com.veilframe.app.cv.segmentation.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * High-performance NCNN native JNI interface for Portrait & Object Background Removal.
 * Eliminates ONNX Runtime and executes U^2-Net-P and MODNet with Vulkan GPU / OpenMP acceleration.
 */
object NcnnMattingNative {
    init {
        System.loadLibrary("veilframe_ncnn")
    }

    const val MODEL_TYPE_U2NETP = 0
    const val MODEL_TYPE_MODNET = 1

    @JvmStatic
    external fun nativeInit(
        assetManager: AssetManager?,
        paramPath: String,
        binPath: String,
        modelType: Int,
        useGpu: Boolean,
        isAsset: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeProcess(
        srcBitmap: Bitmap,
        modelType: Int,
        useGpu: Boolean,
        letterbox: Boolean
    ): Bitmap?

    @JvmStatic
    fun nativeProcess(
        srcBitmap: Bitmap,
        modelType: Int,
        useGpu: Boolean
    ): Bitmap? = nativeProcess(srcBitmap, modelType, useGpu, letterbox = false)

    @JvmStatic
    external fun nativeProcessLegacy(
        srcBitmap: Bitmap,
        modelType: Int,
        useGpu: Boolean
    ): Bitmap?

    @JvmStatic
    external fun isModelLoaded(modelType: Int): Boolean

    @JvmStatic
    external fun nativeRelease()
}
