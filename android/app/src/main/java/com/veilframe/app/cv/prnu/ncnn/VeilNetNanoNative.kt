package com.veilframe.app.cv.prnu.ncnn

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log

data class PurifyResult(
    val bitmap: Bitmap,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

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

    fun purifyResult(
        context: Context,
        src: Bitmap,
        useGpu: Boolean = false,
        coreSize: Int = 480,
        halo: Int = 16
    ): PurifyResult {
        if (!ensureInitialized(context, useGpu)) {
            val msg = "VeilNet-Nano initialization failed"
            Log.w(TAG, "$msg; returning copy of source")
            return PurifyResult(
                bitmap = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true),
                isSuccess = false,
                errorMessage = msg
            )
        }

        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val success = try {
            nativePurifyBitmap(src, dst, coreSize, halo)
        } catch (e: Throwable) {
            Log.e(TAG, "nativePurifyBitmap failed: ${e.message}", e)
            false
        }

        return if (success) {
            PurifyResult(bitmap = dst, isSuccess = true)
        } else {
            dst.recycle()
            PurifyResult(
                bitmap = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true),
                isSuccess = false,
                errorMessage = "nativePurifyBitmap execution returned false"
            )
        }
    }

    fun purify(
        context: Context,
        src: Bitmap,
        useGpu: Boolean = false,
        coreSize: Int = 480,
        halo: Int = 16
    ): Bitmap = purifyResult(context, src, useGpu, coreSize, halo).bitmap

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
