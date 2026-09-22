package com.veilframe.app.upscale.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.EnumSet

/**
 * Factory responsible for configuring and creating ONNX Runtime sessions.
 *
 * Implements:
 * - Cross-vendor NNAPI hardware acceleration (Qualcomm, MediaTek, Tensor, Exynos)
 * - Android API level policy:
 *     - API >= 29: NNAPI candidate probing (FULL_NNAPI_NO_CPU_FALLBACK vs PARTIAL vs UNAVAILABLE)
 *     - API 27-28: standard NNAPI
 *     - API < 27: optimized ORT CPU
 * - Device-aware CPU thread count (cores * 0.75f, min 4 on multi-core)
 * - Fail-safe fallback to optimized ORT CPU on driver or model incompatibility
 * - Truthful backend and coverage reporting
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
            providerConfiguration = profile.providerConfiguration,
            acceleratorConfiguration = profile.acceleratorConfiguration,
            optLevel = profile.optLevel
        )
    }

    /**
     * Creates an OrtSession honoring the requested acceleration mode, precision profile,
     * accelerator configuration, and optional optimization level.
     */
    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        mode: InferenceAccelerationMode = InferenceAccelerationMode.AUTO,
        precision: InferencePrecisionMode = InferencePrecisionMode.DEFAULT,
        customIntraOpThreads: Int? = null,
        customInterOpThreads: Int? = null,
        providerConfiguration: Map<String, String> = emptyMap(),
        acceleratorConfiguration: AcceleratorConfiguration = AcceleratorConfiguration(),
        optLevel: OrtSession.SessionOptions.OptLevel? = null
    ): SessionResult {
        val intraOpThreads = customIntraOpThreads ?: calculateCpuThreads()
        val interOpThreads = customInterOpThreads ?: 1

        val isOrtModel = modelFile.name.endsWith(".ort", ignoreCase = true)
        val defaultOptLevel = if (isOrtModel) OrtSession.SessionOptions.OptLevel.NO_OPT else OrtSession.SessionOptions.OptLevel.ALL_OPT
        val effectiveOptLevel = if (isOrtModel) OrtSession.SessionOptions.OptLevel.NO_OPT else (optLevel ?: defaultOptLevel)

        if (mode == InferenceAccelerationMode.CPU) {
            return createCpuSession(env, modelFile, intraOpThreads, interOpThreads, mode, effectiveOptLevel)
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
                setOptimizationLevel(effectiveOptLevel)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setMemoryPatternOptimization(true)
            }

            var nnapiRegistered = false
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val flagsClass = Class.forName("ai.onnxruntime.providers.NNAPIFlags")
                        val noneOfMethod = java.util.EnumSet::class.java.getMethod("noneOf", Class::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val flagSet = noneOfMethod.invoke(null, flagsClass) as java.util.Set<Any>

                        if (precision == InferencePrecisionMode.FP16_RELAXED || acceleratorConfiguration.useFp16) {
                            val fp16 = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_FP16")
                            flagSet.add(fp16)
                        }
                        if (acceleratorConfiguration.nnapiUseNchw) {
                            try {
                                val nchw = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_NCHW")
                                flagSet.add(nchw)
                            } catch (_: Throwable) {}
                        }

                        val addNnapiMethod = nnapiOptions.javaClass.methods.firstOrNull {
                            it.name == "addNnapi" && it.parameterTypes.size == 1 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[0])
                        }
                        if (addNnapiMethod != null && flagSet.isNotEmpty()) {
                            addNnapiMethod.invoke(nnapiOptions, flagSet)
                            nnapiRegistered = true
                            Log.i(TAG, "Registered NNAPI with EnumSet flags: $flagSet (API ${Build.VERSION.SDK_INT})")
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
                    val isFp16 = (precision == InferencePrecisionMode.FP16_RELAXED || acceleratorConfiguration.useFp16)
                    val layoutStr = if (acceleratorConfiguration.nnapiUseNchw) "NCHW" else "Default"
                    val desc = if (isFp16) "NNAPI (FP16 Relaxed, $layoutStr)" else "NNAPI ($layoutStr)"
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
                            interOpThreads = interOpThreads,
                            providerConfiguration = providerConfiguration,
                            nnapiExecutionCoverage = null,
                            nnapiUseNchw = acceleratorConfiguration.nnapiUseNchw
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
        return createCpuSession(env, modelFile, intraOpThreads, interOpThreads, mode, optLevel)
    }

    /**
     * Probes candidate-specific NNAPI execution coverage on the active device.
     * Evaluates the exact candidate configuration (precision + layout).
     *
     * Step 1: Probe session creation with CPU_DISABLED and candidate flags, followed by 1 test inference pass.
     *         If both succeed -> FULL_NNAPI_NO_CPU_FALLBACK.
     * Step 2: Probe session creation without CPU_DISABLED and candidate flags, followed by 1 test inference pass.
     *         If both succeed -> PARTIAL_NNAPI_WITH_ORT_CPU_FALLBACK.
     * Step 3: If both fail -> UNAVAILABLE.
     */
    fun probeNnapiCoverage(
        env: OrtEnvironment,
        modelFile: File,
        precision: InferencePrecisionMode,
        nnapiUseNchw: Boolean
    ): NnapiExecutionCoverage {
        val canAttemptNnapi = try {
            Build.VERSION.SDK_INT >= 27
        } catch (_: Throwable) {
            false
        }
        if (!canAttemptNnapi) return NnapiExecutionCoverage.UNAVAILABLE

        val flagsClass = try {
            Class.forName("ai.onnxruntime.providers.NNAPIFlags")
        } catch (_: Throwable) {
            return NnapiExecutionCoverage.PARTIAL_NNAPI_WITH_ORT_CPU_FALLBACK
        }

        fun trySessionWithFlags(withCpuDisabled: Boolean): Boolean {
            var testSession: OrtSession? = null
            var probeOptions: OrtSession.SessionOptions? = null
            var testInput: OnnxTensor? = null
            return try {
                val noneOfMethod = java.util.EnumSet::class.java.getMethod("noneOf", Class::class.java)
                @Suppress("UNCHECKED_CAST")
                val probeSet = noneOfMethod.invoke(null, flagsClass) as java.util.Set<Any>

                if (withCpuDisabled) {
                    val cpuDisabled = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "CPU_DISABLED")
                    probeSet.add(cpuDisabled)
                }
                if (precision == InferencePrecisionMode.FP16_RELAXED) {
                    val fp16 = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_FP16")
                    probeSet.add(fp16)
                }
                if (nnapiUseNchw) {
                    val nchw = java.lang.Enum.valueOf(flagsClass.asSubclass(Enum::class.java), "USE_NCHW")
                    probeSet.add(nchw)
                }

                probeOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val addNnapiMethod = probeOptions.javaClass.methods.firstOrNull {
                    it.name == "addNnapi" && it.parameterTypes.size == 1 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (addNnapiMethod != null && probeSet.isNotEmpty()) {
                    addNnapiMethod.invoke(probeOptions, probeSet)
                } else if (!withCpuDisabled) {
                    probeOptions.addNnapi()
                } else {
                    return false
                }

                testSession = env.createSession(modelFile.absolutePath, probeOptions)

                // Execute 1 real test inference pass to confirm operator execution
                val inputEntry = testSession.inputInfo.entries.firstOrNull() ?: return false
                val inputName = inputEntry.key
                val tensorInfo = (inputEntry.value.info as? TensorInfo) ?: return false
                val shape = tensorInfo.shape
                val inH = if (shape.size >= 4 && shape[2] > 0) shape[2].toInt() else 32
                val inW = if (shape.size >= 4 && shape[3] > 0) shape[3].toInt() else 32
                val inC = if (shape.size >= 4 && shape[1] > 0) shape[1].toInt() else 3
                val tensorShape = longArrayOf(1L, inC.toLong(), inH.toLong(), inW.toLong())
                val elemCount = 1 * inC * inH * inW

                testInput = if (tensorInfo.type == OnnxJavaType.FLOAT16) {
                    val sb = ShortBuffer.allocate(elemCount)
                    OnnxTensor.createTensor(env, sb, tensorShape, OnnxJavaType.FLOAT16)
                } else {
                    val fb = FloatBuffer.allocate(elemCount)
                    OnnxTensor.createTensor(env, fb, tensorShape)
                }

                val runResult = testSession.run(mapOf(inputName to testInput))
                runResult.close()
                true
            } catch (t: Throwable) {
                Log.d(TAG, "probeNnapiCoverage(cpuDisabled=$withCpuDisabled, prec=$precision, nchw=$nnapiUseNchw) failed: ${t.message}")
                false
            } finally {
                try { testInput?.close() } catch (_: Throwable) {}
                try { testSession?.close() } catch (_: Throwable) {}
                try { probeOptions?.close() } catch (_: Throwable) {}
            }
        }

        // Step 1: Probe exact flags WITH CPU_DISABLED + test run
        if (Build.VERSION.SDK_INT >= 29 && trySessionWithFlags(withCpuDisabled = true)) {
            Log.i(TAG, "NNAPI probe SUCCESS with CPU_DISABLED (FULL_NNAPI_NO_CPU_FALLBACK) for precision=$precision, nchw=$nnapiUseNchw")
            return NnapiExecutionCoverage.FULL_NNAPI_NO_CPU_FALLBACK
        }

        // Step 2: Probe exact flags WITHOUT CPU_DISABLED + test run
        if (trySessionWithFlags(withCpuDisabled = false)) {
            Log.i(TAG, "NNAPI probe SUCCESS with fallback (PARTIAL_NNAPI_WITH_ORT_CPU_FALLBACK) for precision=$precision, nchw=$nnapiUseNchw")
            return NnapiExecutionCoverage.PARTIAL_NNAPI_WITH_ORT_CPU_FALLBACK
        }

        // Step 3: Complete failure
        Log.w(TAG, "NNAPI probe FAILED both with and without CPU_DISABLED (UNAVAILABLE) for precision=$precision, nchw=$nnapiUseNchw")
        return NnapiExecutionCoverage.UNAVAILABLE
    }

    private fun createCpuSession(
        env: OrtEnvironment,
        modelFile: File,
        intraOpThreads: Int,
        interOpThreads: Int,
        mode: InferenceAccelerationMode,
        optLevel: OrtSession.SessionOptions.OptLevel? = null
    ): SessionResult {
        val isOrtModel = modelFile.name.endsWith(".ort", ignoreCase = true)
        val defaultOptLevel = if (isOrtModel) OrtSession.SessionOptions.OptLevel.NO_OPT else OrtSession.SessionOptions.OptLevel.BASIC_OPT
        val effectiveOptLevel = if (isOrtModel) OrtSession.SessionOptions.OptLevel.NO_OPT else (optLevel ?: defaultOptLevel)
        val cpuOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setInterOpNumThreads(interOpThreads)
            setOptimizationLevel(effectiveOptLevel)
        }
        val session = env.createSession(modelFile.absolutePath, cpuOptions)
        val desc = "ORT CPU ($intraOpThreads threads, optLevel=$effectiveOptLevel)"
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
