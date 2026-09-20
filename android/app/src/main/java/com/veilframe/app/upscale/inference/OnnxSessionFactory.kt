package com.veilframe.app.upscale.inference

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import android.util.Log
import java.io.File
import java.util.EnumSet

/**
 * Factory responsible for configuring and creating ONNX Runtime sessions.
 *
 * Implements:
 * - Cross-vendor NNAPI hardware acceleration (Qualcomm, MediaTek, Tensor, Exynos)
 * - Android API level policy:
 *     - API >= 29: NNAPI + CPU_DISABLED (prevents slow NNAPI reference CPU, falls back to ORT kernels)
 *     - API 27-28: standard NNAPI
 *     - API < 27: optimized ORT CPU
 * - Device-aware CPU thread count (cores * 0.75f, min 4 on multi-core)
 * - Fail-safe fallback to optimized ORT CPU on driver or model incompatibility
 * - Truthful backend reporting (NNAPI or ORT CPU)
 */
object OnnxSessionFactory {
    private const val TAG = "VeilFrame.OnnxFactory"

    data class SessionResult(
        val session: OrtSession,
        val options: OrtSession.SessionOptions,
        val backendInfo: InferenceBackendInfo
    )

    /**
     * Calculates optimal intra-op thread count based on hardware processor count.
     */
    fun calculateCpuThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when {
            cores <= 4 -> cores
            else -> (cores * 0.75f).toInt().coerceAtLeast(4)
        }
    }

    /**
     * Creates an OrtSession honoring a full ExecutionProfile.
     */
    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ExecutionProfile
    ): SessionResult {
        val mode = when (profile.backend) {
            Backend.NNAPI -> InferenceAccelerationMode.NNAPI
            Backend.CPU -> InferenceAccelerationMode.CPU
            Backend.XNNPACK -> InferenceAccelerationMode.XNNPACK
            Backend.QNN -> InferenceAccelerationMode.QNN
        }
        return createSession(
            env = env,
            modelFile = modelFile,
            mode = mode,
            precision = profile.precision,
            customIntraOpThreads = profile.intraOpThreads,
            customInterOpThreads = profile.interOpThreads,
            providerConfiguration = profile.providerConfiguration
        )
    }

    /**
     * Creates an OrtSession honoring the requested acceleration mode and precision profile.
     */
    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        mode: InferenceAccelerationMode = InferenceAccelerationMode.AUTO,
        precision: InferencePrecisionMode = InferencePrecisionMode.DEFAULT,
        customIntraOpThreads: Int? = null,
        customInterOpThreads: Int? = null,
        providerConfiguration: Map<String, String> = emptyMap()
    ): SessionResult {
        val intraOpThreads = customIntraOpThreads ?: calculateCpuThreads()
        val interOpThreads = customInterOpThreads ?: 1

        if (mode == InferenceAccelerationMode.CPU) {
            return createCpuSession(env, modelFile, intraOpThreads, interOpThreads, mode)
        }

        // Check if QNN build is requested
        if (mode == InferenceAccelerationMode.QNN) {
            try {
                val qnnOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(intraOpThreads)
                    setInterOpNumThreads(interOpThreads)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    if (providerConfiguration.containsKey("session.disable_cpu_ep_fallback")) {
                        val fallbackVal = providerConfiguration["session.disable_cpu_ep_fallback"] ?: "1"
                        try {
                            addConfigEntry("session.disable_cpu_ep_fallback", fallbackVal)
                            Log.i(TAG, "Configured session.disable_cpu_ep_fallback = $fallbackVal for QNN probe")
                        } catch (eConfig: Throwable) {
                            Log.w(TAG, "Failed to set addConfigEntry: ${eConfig.message}")
                        }
                    }

                    // Explicitly bind the discovered OrtEpDevice instance (never inferred solely from strings)
                    try {
                        val isGpuTarget = providerConfiguration["backend_path"]?.contains("Gpu", ignoreCase = true) == true ||
                                providerConfiguration["backend_type"]?.equals("GPU", ignoreCase = true) == true
                        val target = if (isGpuTarget) com.veilframe.app.upscale.inference.QnnTarget.GPU else com.veilframe.app.upscale.inference.QnnTarget.HTP
                        val matchingDevice = com.veilframe.app.upscale.inference.qnn.QnnAccelerationManager.selectDevice(target)
                            ?: com.veilframe.app.upscale.inference.qnn.QnnAccelerationManager.getDiscoveredDevices().firstOrNull()

                        if (matchingDevice != null) {
                            com.veilframe.app.upscale.inference.qnn.QnnAccelerationManager.bindSessionDevice(
                                options = this,
                                device = matchingDevice,
                                providerOptions = providerConfiguration
                            )
                        }
                    } catch (eDev: Throwable) {
                        Log.w(TAG, "Device binding hook error: ${eDev.message}")
                    }
                }
                // Create session with Qualcomm QNN EP and record active lease session
                val session = env.createSession(modelFile.absolutePath, qnnOptions)
                com.veilframe.app.upscale.inference.qnn.QnnPluginLeaseManager.incrementSession()
                Log.i(TAG, "Created session with Qualcomm QNN EP (active QNN sessions: ${com.veilframe.app.upscale.inference.qnn.QnnPluginLeaseManager.currentSessionCount})")
                return SessionResult(
                    session = session,
                    options = qnnOptions,
                    backendInfo = InferenceBackendInfo(
                        backend = Backend.QNN,
                        accelerationMode = mode,
                        configuredExecutionProvider = "QNNExecutionProvider",
                        cpuFallbackEnabled = providerConfiguration["session.disable_cpu_ep_fallback"] != "1",
                        observedFallback = null,
                        fp16Enabled = precision == InferencePrecisionMode.FP16_RELAXED,
                        intraOpThreads = intraOpThreads,
                        interOpThreads = interOpThreads,
                        providerConfiguration = if (providerConfiguration.isNotEmpty()) providerConfiguration else mapOf("backend_type" to "HTP")
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "QNN EP requested but unavailable in this build (${t.message}); falling back to NNAPI/CPU")
            }
        }

        // Check if NNAPI can be attempted (Android 8.1+ / API 27+)
        val canAttemptNnapi = try {
            Build.VERSION.SDK_INT >= 27
        } catch (_: Throwable) {
            // JVM unit test environment
            false
        }

        if (canAttemptNnapi) {
            val nnapiOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(interOpThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            var nnapiRegistered = false
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val flagsClass = Class.forName("ai.onnxruntime.providers.NNAPIFlags")
                        val cpuDisabled = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "CPU_DISABLED")
                        val flagSet = java.util.HashSet<Any>()
                        flagSet.add(cpuDisabled)
                        if (precision == InferencePrecisionMode.FP16_RELAXED) {
                            val fp16 = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_FP16")
                            flagSet.add(fp16)
                        }
                        val addNnapiMethod = nnapiOptions.javaClass.methods.firstOrNull {
                            it.name == "addNnapi" && it.parameterTypes.size == 1 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[0])
                        }
                        if (addNnapiMethod != null) {
                            addNnapiMethod.invoke(nnapiOptions, flagSet)
                            nnapiRegistered = true
                            Log.i(TAG, "Registered NNAPI with CPU_DISABLED (API ${Build.VERSION.SDK_INT})")
                        } else {
                            nnapiOptions.addNnapi()
                            nnapiRegistered = true
                        }
                    } catch (eFlag: Throwable) {
                        Log.w(TAG, "NNAPIFlags unavailable ($eFlag); using standard addNnapi()")
                        nnapiOptions.addNnapi()
                        nnapiRegistered = true
                    }
                } else {
                    nnapiOptions.addNnapi()
                    nnapiRegistered = true
                }
            } catch (eRegister: Throwable) {
                Log.w(TAG, "Failed to register NNAPI execution provider: ${eRegister.message}")
            }

            if (nnapiRegistered) {
                try {
                    val session = env.createSession(modelFile.absolutePath, nnapiOptions)
                    val isFp16 = (precision == InferencePrecisionMode.FP16_RELAXED)
                    val desc = if (isFp16) "NNAPI (FP16 Relaxed, ORT CPU Fallback)" else "NNAPI (ORT CPU Fallback)"
                    Log.i(TAG, "Created session with $desc")
                    return SessionResult(
                        session = session,
                        options = nnapiOptions,
                        backendInfo = InferenceBackendInfo(
                            backend = Backend.NNAPI,
                            accelerationMode = mode,
                            configuredExecutionProvider = "NNAPIExecutionProvider",
                            cpuFallbackEnabled = true,
                            observedFallback = null,
                            fp16Enabled = isFp16,
                            intraOpThreads = intraOpThreads,
                            interOpThreads = interOpThreads
                        )
                    )
                } catch (eInit: Throwable) {
                    Log.w(TAG, "NNAPI session initialization failed: ${eInit.message}; falling back to optimized CPU session")
                    try {
                        nnapiOptions.close()
                    } catch (_: Throwable) {}
                }
            }
        }

        // Optimized CPU fallback
        return createCpuSession(env, modelFile, intraOpThreads, interOpThreads, mode)
    }

    private fun createCpuSession(
        env: OrtEnvironment,
        modelFile: File,
        intraOpThreads: Int,
        interOpThreads: Int,
        mode: InferenceAccelerationMode
    ): SessionResult {
        val cpuOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setInterOpNumThreads(interOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        val session = env.createSession(modelFile.absolutePath, cpuOptions)
        val desc = "ORT CPU ($intraOpThreads threads)"
        Log.i(TAG, "Created session with $desc")
        return SessionResult(
            session = session,
            options = cpuOptions,
            backendInfo = InferenceBackendInfo(
                backend = Backend.CPU,
                accelerationMode = mode,
                configuredExecutionProvider = "CPUExecutionProvider",
                cpuFallbackEnabled = false,
                observedFallback = null,
                fp16Enabled = false,
                intraOpThreads = intraOpThreads,
                interOpThreads = interOpThreads
            )
        )
    }
}
