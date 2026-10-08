package com.veilframe.app.upscale.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resumeWithException

/**
 * ONNX session factory with the F7 execution-provider ladder and the F8
 * bounded thread budget.
 *
 * Root problems fixed:
 *  - Sessions used to be CPU-only with intraOp = cores*3/4 AND interOp = 4,
 *    times up to 4 parallel tile workers: 20-30 saturated threads on an 8-core
 *    SoC, starving the compositor and thermally soaking the whole phone.
 *  - No accelerator was ever attempted, so flagships ran Real-ESRGAN-class
 *    models entirely on CPU.
 *
 * New behavior: NNAPI (API 27+, delegate chosen by the vendor driver — GPU/DSP
 * where supported) → XNNPACK (fp32/fp16-optimized CPU EP, 2 threads) → plain
 * CPU (bounded: intra ≤ 4, inter 1, SEQUENTIAL). The first backend that builds
 * a session wins; the choice is cached per model in SharedPreferences so the
 * probe cost is paid once. [lastBackend] feeds honest diagnostics/status.
 *
 * Note: if a cached accelerator turns out to be slow/flaky for a specific
 * device+model pair, clearing app cache (or the follow-up benchmark-gated
 * selection in plan F7) re-runs the ladder.
 */
object OnnxSessionManager {
    private const val TAG = "VeilFrame.SessionMgr"
    private const val PREFS_NAME = "veilframe_upscale_prefs"
    private const val KEY_BACKEND_PREFIX = "ep_backend_"

    /** F7: execution-provider ladder rungs. */
    enum class Backend { NNAPI, XNNPACK, CPU }

    /** Backend of the most recently created session (diagnostics/status lines). */
    @Volatile
    var lastBackend: Backend = Backend.CPU
        private set

    /**
     * XNNPACK first: NNAPI is officially DEPRECATED as of Android 15 ("we
     * expect the majority of devices in the future to use the CPU backend" —
     * developer.android.com/ndk/guides/neuralnetworks), and ORT's own NNAPI
     * docs warn that unsupported ops silently partition back to CPU with
     * extra overhead — a documented pitfall for ESRGAN-family models
     * (onnxruntime#22346). So the order is a starting point only; the
     * benchmark below makes the real decision.
     */
    private val defaultOrder: List<Backend> = listOf(Backend.XNNPACK, Backend.NNAPI, Backend.CPU)

    fun createSession(modelFile: File, context: Context? = null): OrtSession {
        val modelName = modelFile.name
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs?.getString(KEY_BACKEND_PREFIX + modelName, null)?.let { name ->
            runCatching { Backend.valueOf(name) }.getOrNull()
        }

        if (cached != null && isUsable(cached)) {
            try {
                val session = buildSession(modelFile, modelName, cached)
                lastBackend = cached
                Log.i(TAG, "ONNX session for $modelName on cached backend $cached")
                return session
            } catch (e: Exception) {
                Log.w(TAG, "Cached backend $cached failed for $modelName; re-selecting", e)
                prefs?.edit()?.remove(KEY_BACKEND_PREFIX + modelName)?.apply()
            }
        }

        // First run for this model: build every viable candidate and MEASURE.
        // Audit fix: "session creation succeeded" does NOT mean "fast" — NNAPI
        // happily creates sessions whose ops mostly fall back to CPU with
        // partitioning overhead. One small synthetic run per candidate
        // (256px tile, warm-up + timed) decides; the choice is cached per
        // model, so the cost is paid once.
        val built = LinkedHashMap<Backend, OrtSession>()
        val timings = LinkedHashMap<Backend, Double>()
        var lastError: Exception? = null
        for (backend in defaultOrder) {
            if (!isUsable(backend)) continue
            try {
                val session = buildSession(modelFile, modelName, backend)
                built[backend] = session
                benchmark(session, modelName)?.let { timings[backend] = it }
            } catch (e: Exception) {
                Log.w(TAG, "$backend backend unavailable for $modelName: ${e.message}")
                lastError = e
            }
        }
        if (built.isEmpty()) {
            throw lastError ?: IllegalStateException("No usable ONNX backend for $modelName")
        }

        val winner = timings.minByOrNull { it.value }?.key ?: built.keys.first()
        built.forEach { (backend, session) ->
            if (backend != winner) runCatching { session.close() }
        }
        lastBackend = winner
        prefs?.edit()?.putString(KEY_BACKEND_PREFIX + modelName, winner.name)?.apply()
        Log.i(
            TAG,
            "Backend selection for $modelName: $winner " +
                "(timings: ${timings.entries.joinToString { "${it.key}=${"%,.0f".format(it.value)}ms" }})"
        )
        return built.getValue(winner)
    }

    private fun isUsable(backend: Backend): Boolean = when (backend) {
        Backend.NNAPI -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 // API 27+ (minSdk is 26)
        else -> true
    }

    /**
     * Synthetic timing run on a 256px tile (warm-up + measured pass).
     * Returns null when the model refuses the synthetic input — the candidate
     * stays selectable but unranked (creation-gated fallback).
     */
    private fun benchmark(session: OrtSession, modelName: String): Double? {
        val tensors = linkedMapOf<String, OnnxTensor>()
        return try {
            val info = ModelInfo(
                session = session,
                modelName = modelName,
                explicitScale = null,
                chunkSize = 256,
                overlap = 0
            )
            val size = info.tensorSizeFor(TensorSize(256, 256))
            val data = FloatArray(info.inputChannels * size.pixelCount) // zeros
            val shape = longArrayOf(
                1,
                info.inputChannels.toLong(),
                size.height.toLong(),
                size.width.toLong()
            )
            tensors[info.inputName] = createInputTensor(data, shape, info.isFp16)
            appendControlInputs(tensors, info)

            session.run(tensors).use { } // warm-up (lazy EP compilation)
            val t0 = System.nanoTime()
            session.run(tensors).use { }
            (System.nanoTime() - t0) / 1_000_000.0
        } catch (t: Throwable) {
            Log.w(TAG, "Benchmark failed for $modelName: ${t.message}")
            null
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun buildSession(modelFile: File, modelName: String, backend: Backend): OrtSession {
        val options = OrtSession.SessionOptions()
        try {
            val processors = Runtime.getRuntime().availableProcessors()
            when (backend) {
                Backend.NNAPI -> {
                    // Vendor driver picks GPU/DSP/CPU delegation per op support.
                    options.addNnapi()
                }
                Backend.XNNPACK -> {
                    // F8: bounded — XNNPACK manages its own threadpool.
                    options.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                }
                Backend.CPU -> {
                    // F8: was (cores*3/4) intra + 4 inter PER WORKER. Now bounded
                    // and sequential-execution so tiles compose predictably.
                    try {
                        options.setIntraOpNumThreads(minOf(4, maxOf(1, (processors * 3) / 4)))
                    } catch (e: OrtException) {
                        Log.w(TAG, "Error setting IntraOpNumThreads: ${e.message}")
                    }
                    try {
                        options.setInterOpNumThreads(1)
                    } catch (e: OrtException) {
                        Log.w(TAG, "Error setting InterOpNumThreads: ${e.message}")
                    }
                    try {
                        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    } catch (e: OrtException) {
                        Log.w(TAG, "Error setting ExecutionMode: ${e.message}")
                    }
                }
            }

            try {
                when {
                    modelName.endsWith(".ort", ignoreCase = true) -> {
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    }
                    modelName.startsWith("scunet_", ignoreCase = true) -> {
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    }
                    modelName.startsWith("fbcnn_", ignoreCase = true) -> {
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                    }
                    else -> {
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                }
            } catch (e: OrtException) {
                Log.w(TAG, "Error setting OptimizationLevel: ${e.message}")
            }

            return OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
        } catch (e: Exception) {
            runCatching { options.close() }
            throw e
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun OrtSession.runCancellable(
    inputs: Map<String, OnnxTensor>
): OrtSession.Result = suspendCancellableCoroutine { continuation ->
    val runOptions = OrtSession.RunOptions()

    continuation.invokeOnCancellation {
        runCatching {
            runOptions.setTerminate(true)
        }
    }

    runCatching {
        run(inputs, runOptions)
    }.onSuccess { result ->
        continuation.resume(result) { _ ->
            result.close()
        }
    }.onFailure { throwable ->
        continuation.resumeWithException(throwable)
    }.also {
        runCatching {
            runOptions.close()
        }
    }
}
