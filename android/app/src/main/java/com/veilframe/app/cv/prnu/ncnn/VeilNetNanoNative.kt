package com.veilframe.app.cv.prnu.ncnn

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log

/**
 * JNI bridge for VeilNet-Nano neural sensor attribution pre-scrambler.
 * Executes on-device NCNN inference with seam-free halo-padded sliding tiling
 * to scrub camera-specific demosaicing manifolds and high-frequency PRNU.
 */
object VeilNetNanoNative {
    private const val TAG = "VeilFrame.VeilNetNano"

    @Volatile
    private var isInitialized = false

    init {
        try {
            System.loadLibrary("veilframe_ncnn")
            Log.i(TAG, "libveilframe_ncnn.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libveilframe_ncnn.so: ${e.message}")
        }
    }

    external fun nativeInit(
        assetManager: AssetManager?,
        paramPath: String,
        binPath: String,
        useGpu: Boolean
    ): Boolean

    external fun nativeIsLoaded(): Boolean

    external fun nativePurifyBitmap(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        coreSize: Int = 480,
        halo: Int = 16
    ): Boolean

    external fun nativeRelease()

    @Synchronized
    fun ensureInitialized(
        context: Context,
        useGpu: Boolean = false,
        paramPath: String = "models/veilnet/veilnet_nano.ncnn.param",
        binPath: String = "models/veilnet/veilnet_nano.ncnn.bin"
    ): Boolean {
        if (isInitialized && nativeIsLoaded()) return true
        val ok = try {
            nativeInit(context.assets, paramPath, binPath, useGpu)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit failed: ${e.message}", e)
            false
        }
        isInitialized = ok
        return ok
    }

    fun purify(
        context: Context,
        src: Bitmap,
        useGpu: Boolean = false,
        coreSize: Int = 480,
        halo: Int = 16
    ): Bitmap {
        if (!ensureInitialized(context, useGpu)) {
            Log.w(TAG, "VeilNet-Nano initialization failed; returning copy of source")
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val success = try {
            nativePurifyBitmap(src, dst, coreSize, halo)
        } catch (e: Throwable) {
            Log.e(TAG, "nativePurifyBitmap failed: ${e.message}", e)
            false
        }

        return if (success) dst else {
            dst.recycle()
            src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        }
    }

    @Synchronized
    fun release() {
        try {
            nativeRelease()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeRelease failed: ${e.message}", e)
        }
        isInitialized = false
    }
}
