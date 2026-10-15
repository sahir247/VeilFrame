package com.veilframe.app.upscale.inference

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI wrapper for VeilFrame's native NCNN Vulkan super-resolution engine.
 */
object NcnnNative {
    private const val TAG = "VeilFrame.NcnnNative"

    // Stable JNI status contract. Keep values synchronized with realsr_jni.cpp.
    const val STATUS_UNKNOWN_FAILURE = -1
    const val STATUS_VULKAN_FAILURE = -2
    const val STATUS_INVALID_ARGUMENT = -3
    const val STATUS_CPU_FAILURE = -4
    const val STATUS_CANCELLED = -10
    const val STATUS_OUT_OF_MEMORY = -11
    const val STATUS_CALLBACK_EXCEPTION = -12
    const val STATUS_CALLBACK_FAILURE = -13
    const val STATUS_MODEL_PARAM_FAILURE = -20
    const val STATUS_MODEL_WEIGHTS_FAILURE = -21
    const val STATUS_MODEL_GRAPH_FAILURE = -22
    const val STATUS_MODEL_LAYER_FAILURE = -23

    private val libraryLoaded: Boolean = try {
        System.loadLibrary("veilframe_ncnn")
        nativeGlobalInit() == 0
    } catch (t: Throwable) {
        Log.w(TAG, "Native NCNN library not loaded (expected on host JVM unit tests): ${t.message}")
        false
    }

    /**
     * True if the native library was successfully loaded and initialized.
     */
    val isAvailable: Boolean
        get() = libraryLoaded

    /**
     * Explicit process-level teardown of native NCNN global state and GPU instance.
     * Safe to call multiple times or during application shutdown.
     */
    fun globalDestroy() {
        if (isAvailable) {
            nativeGlobalDestroy()
        }
    }

    /**
     * Number of available Vulkan-capable GPUs detected by NCNN.
     */
    fun getGpuCount(): Int {
        if (!isAvailable) return 0
        return nativeGetGpuCount()
    }

    /**
     * Retrieve the vendor device name for the given GPU ID.
     */
    fun getGpuDeviceName(gpuid: Int): String? {
        if (!isAvailable) return null
        return nativeGetGpuDeviceName(gpuid)
    }

    /**
     * Instantiate a native RealSR instance.
     *
     * @param gpuid GPU ID (0 for primary GPU, -1 for CPU fallback).
     * @param ttaMode True to enable test-time augmentation (8x slower, higher quality).
     * @param numThreads CPU thread count when falling back to CPU mode.
     * @return Native pointer handle, or 0 if creation failed.
     */
    fun createInstance(gpuid: Int, ttaMode: Boolean = false, numThreads: Int = 4): Long {
        check(isAvailable) { "NCNN native library is not available" }
        return nativeCreateInstance(gpuid, ttaMode, numThreads)
    }

    /**
     * Destroy a native RealSR instance safely.
     */
    fun destroyInstance(handle: Long) {
        if (handle != 0L && isAvailable) {
            nativeDestroyInstance(handle)
        }
    }

    /**
     * Load an NCNN model pair (.param and .bin).
     *
     * @return 0 on success, negative error code on failure.
     */
    fun loadModel(
        handle: Long,
        paramPath: String,
        binPath: String,
        scale: Int,
        tilesize: Int,
        prepadding: Int = 10
    ): Int {
        check(isAvailable) { "NCNN native library is not available" }
        return nativeLoadModel(handle, paramPath, binPath, scale, tilesize, prepadding)
    }

    /**
     * Execute tiled super-resolution with direct streaming row output.
     *
     * @return 0 on completion, non-zero if cancelled or failed.
     */
    fun processStreaming(
        handle: Long,
        inputDirectBuffer: ByteBuffer,
        width: Int,
        height: Int,
        channels: Int,
        rowCallback: NcnnRowCallback,
        progressCallback: NcnnProgressCallback? = null
    ): Int {
        check(isAvailable) { "NCNN native library is not available" }
        require(inputDirectBuffer.isDirect) { "inputDirectBuffer must be a direct ByteBuffer" }
        require(width > 0 && height > 0 && (channels == 3 || channels == 4)) {
            "Invalid image dimensions or channel count"
        }
        require(inputDirectBuffer.position() == 0) {
            "inputDirectBuffer must be positioned at zero because JNI reads from its base address"
        }
        val requiredBytes = Math.multiplyExact(
            Math.multiplyExact(width.toLong(), height.toLong()),
            channels.toLong()
        )
        require(inputDirectBuffer.limit().toLong() >= requiredBytes) {
            "inputDirectBuffer limit ${inputDirectBuffer.limit()} is smaller than required pixel bytes $requiredBytes"
        }
        return nativeProcessStreaming(handle, inputDirectBuffer, width, height, channels, rowCallback, progressCallback)
    }

    /**
     * Execute tiled super-resolution with dual-ended bounded memory streaming:
     * fetches horizontal input strips on demand via [stripCallback] and delivers
     * output scanlines directly via [rowCallback].
     *
     * @return 0 on completion, non-zero if cancelled or failed.
     */
    fun processStripStreaming(
        handle: Long,
        width: Int,
        height: Int,
        channels: Int,
        stripCallback: NcnnStripCallback,
        rowCallback: NcnnRowCallback,
        progressCallback: NcnnProgressCallback? = null
    ): Int {
        check(isAvailable) { "NCNN native library is not available" }
        return nativeProcessStripStreaming(handle, width, height, channels, stripCallback, rowCallback, progressCallback)
    }

    // Native method signatures
    @JvmStatic private external fun nativeGlobalInit(): Int
    @JvmStatic private external fun nativeGlobalDestroy()
    @JvmStatic private external fun nativeGetGpuCount(): Int
    @JvmStatic private external fun nativeGetGpuDeviceName(gpuid: Int): String?
    @JvmStatic private external fun nativeCreateInstance(gpuid: Int, ttaMode: Boolean, numThreads: Int): Long
    @JvmStatic private external fun nativeDestroyInstance(handle: Long)
    @JvmStatic private external fun nativeLoadModel(
        handle: Long, paramPath: String, binPath: String, scale: Int, tilesize: Int, prepadding: Int
    ): Int
    @JvmStatic private external fun nativeProcessStreaming(
        handle: Long, inputDirectBuffer: ByteBuffer, width: Int, height: Int, channels: Int,
        rowCallback: NcnnRowCallback, progressCallback: NcnnProgressCallback?
    ): Int
    @JvmStatic private external fun nativeProcessStripStreaming(
        handle: Long, width: Int, height: Int, channels: Int,
        stripCallback: NcnnStripCallback, rowCallback: NcnnRowCallback, progressCallback: NcnnProgressCallback?
    ): Int
}
