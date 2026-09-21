package com.veilframe.app.upscale.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Concrete device hardware probe describing physical CPU, memory, API, thermal, and accelerator capabilities.
 * These are constraints and parameters for the benchmark engine and execution planner, not hardcoded worker policies.
 */
data class DeviceCapabilityProfile(
    val manufacturer: String,
    val model: String,
    val cpuCores: Int,
    val supportedAbis: List<String>,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val javaHeapHeadroom: Long = 256L * 1024 * 1024,
    val systemAvailableMemory: Long = 512L * 1024 * 1024,
    val apiLevel: Int,
    val lowRamDevice: Boolean,
    val supportsNnapi: Boolean,
    val supportsNnapiFp16: Boolean,
    val isQualcommSoc: Boolean = false,
    val supportsXnnpack: Boolean,
    val thermalStatus: Int,
    val hardware: String = "Unknown",
    // Backward-compatible aliases for legacy callers
    val javaHeapBudget: Long = javaHeapHeadroom,
    val nativeProcessBudget: Long = systemAvailableMemory
) {
    companion object {
        fun probe(context: Context): DeviceCapabilityProfile {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val runtime = Runtime.getRuntime()
            val javaMax = runtime.maxMemory()
            val javaUsed = runtime.totalMemory() - runtime.freeMemory()
            val javaHeap = (javaMax - javaUsed).coerceAtLeast(32L * 1024 * 1024)

            val totalMem = if (memInfo.totalMem > 0L) memInfo.totalMem else javaMax
            val sysAvail = if (memInfo.availMem > 0L) memInfo.availMem else javaHeap

            val api = Build.VERSION.SDK_INT
            val thermal = if (api >= Build.VERSION_CODES.Q) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.currentThermalStatus ?: 0
                } catch (_: Throwable) { 0 }
            } else {
                0
            }

            // Check if device is Qualcomm SoC
            val hardwareStr = (Build.HARDWARE + " " + Build.BOARD + " " + Build.MANUFACTURER).lowercase(java.util.Locale.ROOT)
            val isQcom = hardwareStr.contains("qcom") ||
                    hardwareStr.contains("qualcomm") ||
                    hardwareStr.contains("snapdragon") ||
                    hardwareStr.startsWith("sm") ||
                    hardwareStr.startsWith("sdm")

            return DeviceCapabilityProfile(
                manufacturer = Build.MANUFACTURER ?: "Unknown",
                model = Build.MODEL ?: "Unknown",
                cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
                totalMemoryBytes = totalMem,
                availableMemoryBytes = sysAvail,
                javaHeapHeadroom = javaHeap,
                systemAvailableMemory = sysAvail,
                apiLevel = api,
                lowRamDevice = actManager?.isLowRamDevice ?: (totalMem < 3L * 1024 * 1024 * 1024),
                supportsNnapi = api >= Build.VERSION_CODES.O_MR1,
                supportsNnapiFp16 = api >= Build.VERSION_CODES.Q,
                isQualcommSoc = isQcom,
                supportsXnnpack = false,
                thermalStatus = thermal,
                hardware = Build.HARDWARE ?: "Unknown"
            )
        }
    }
}
