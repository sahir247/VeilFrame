package com.veilframe.app.media.rife

import android.graphics.Bitmap

/**
 * NCNN native JNI interface for RIFE v4 Real-Time Intermediate Flow Estimation.
 * Provides AI neural frame interpolation and FPS boost with Vulkan GPU acceleration.
 */
object RifeNcnnNative {
    init {
        System.loadLibrary("veilframe_ncnn")
    }

    @JvmStatic
    external fun nativeInit(
        modelDir: String,
        useGpu: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeInterpolate(
        bitmap0: Bitmap,
        bitmap1: Bitmap,
        timestep: Float
    ): Bitmap?

    @JvmStatic
    external fun nativeRelease()
}
