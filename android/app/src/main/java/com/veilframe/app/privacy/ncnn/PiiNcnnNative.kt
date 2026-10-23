package com.veilframe.app.privacy.ncnn

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * NCNN native JNI interface for automated PII & Face Detection.
 * Combines UltraFace RFB-320 (biometrics) and YOLOv8n NCNN (plates, cards, objects).
 * Eliminates ONNX Runtime and provides fast on-device inference with OpenMP.
 */
object PiiNcnnNative {
    init {
        System.loadLibrary("veilframe_ncnn")
    }

    @JvmStatic
    external fun nativeInit(assetManager: AssetManager): Boolean

    @JvmStatic
    external fun nativeDetectFaces(
        srcBitmap: Bitmap,
        scoreThreshold: Float,
        iouThreshold: Float
    ): FloatArray?

    @JvmStatic
    external fun nativeDetectPii(
        srcBitmap: Bitmap,
        scoreThreshold: Float,
        iouThreshold: Float
    ): FloatArray?

    @JvmStatic
    external fun nativeRelease()
}
