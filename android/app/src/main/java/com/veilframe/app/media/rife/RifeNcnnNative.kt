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
    @JvmOverloads
    external fun nativeInit(
        modelDir: String,
        useGpu: Boolean,
        padding: Int = 0
    ): Boolean

    @JvmStatic
    external fun nativeInterpolate(
        bitmap0: Bitmap,
        bitmap1: Bitmap,
        timestep: Float
    ): Bitmap?

    @JvmStatic
    external fun nativeRelease()

    @JvmStatic
    external fun nativeGetGpuName(): String

    /**
     * Resolves the hardware GPU device name (e.g. "Adreno (TM) 829", "Mali-G715 Immortalis",
     * "Samsung Xclipse 940", etc.) via Vulkan physical device properties.
     */
    fun getGpuDeviceName(): String {
        if (isEmulator()) {
            return "Host Emulator Vulkan"
        }
        return try {
            val name = nativeGetGpuName().trim()
            if (name.isNotEmpty()) name else "Vulkan GPU"
        } catch (t: Throwable) {
            "Vulkan GPU"
        }
    }

    /**
     * Detects if running on Android Studio Emulator / QEMU / Virtual device.
     * Used to safely route RIFE to CPU OpenMP on emulators where host GPU Vulkan is unstable.
     */
    fun isEmulator(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val hardware = android.os.Build.HARDWARE.lowercase()
        val product = android.os.Build.PRODUCT.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_gphone") ||
            product.contains("google_sdk") ||
            manufacturer.contains("genymotion")
    }
}
