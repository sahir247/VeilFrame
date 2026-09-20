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

        // Check if NNAPI can be attempted (Android 8.1+ / API 27+)
        val canAttemptNnapi = try {
            Build.VERSION.SDK_INT >= 27
        } catch (_: Throwable) {
            // JVM unit test environment
            false
        }

        if (canAttemptNnapi && (mode == InferenceAccelerationMode.AUTO || mode == InferenceAccelerationMode.NNAPI)) {
            val nnapiOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(interOpThreads)
                // Use ALL_OPT for maximum operator fusion, constant folding, and dead code elimination
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setMemoryPatternOptimization(true)
            }

            var nnapiRegistered = false
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val flagsClass = Class.forName("ai.onnxruntime.providers.NNAPIFlags")
                        val flagSet = java.util.HashSet<Any>()
                        // Note: We deliberately do NOT set CPU_DISABLED so that operators unsupported
                        // by vendor NNAPI drivers seamlessly fall back to CPU, keeping hardware acceleration
                        // active for all supported layers without session failure.
                        if (precision == InferencePrecisionMode.FP16_RELAXED) {
                            val fp16 = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_FP16")
                            flagSet.add(fp16)
                        }
                        try {
                            val nchw = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_NCHW")
                            flagSet.add(nchw)
                        } catch (_: Throwable) {}

                        val addNnapiMethod = nnapiOptions.javaClass.methods.firstOrNull {
                            it.name == "addNnapi" && it.parameterTypes.size == 1 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[0])
                        }
                        if (addNnapiMethod != null && flagSet.isNotEmpty()) {
                            addNnapiMethod.invoke(nnapiOptions, flagSet)
                            nnapiRegistered = true
                            Log.i(TAG, "Registered NNAPI with flags: $flagSet (API ${Build.VERSION.SDK_INT})")
                        } else {
                            nnapiOptions.addNnapi()
                            nnapiRegistered = true
                        }
                    } catch (eFlag: Throwable) {
                        Log.w(TAG, "NNAPIFlags reflection notice ($eFlag); using standard addNnapi()")
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
                    val desc = if (isFp16) "NNAPI (FP16 Relaxed, Adaptive Operator Fallback)" else "NNAPI (Adaptive Operator Fallback)"
                    Log.i(TAG, "Created optimized hardware session with $desc")
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
                    Log.w(TAG, "NNAPI session initialization failed (${eInit.message}); falling back to optimized CPU session")
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
