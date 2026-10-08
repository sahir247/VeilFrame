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

    private val defaultOrder: List<Backend> = listOf(Backend.NNAPI, Backend.XNNPACK, Backend.CPU)

    fun createSession(modelFile: File, context: Context? = null): OrtSession {
        val modelName = modelFile.name
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs?.getString(KEY_BACKEND_PREFIX + modelName, null)?.let { name ->
            runCatching { Backend.valueOf(name) }.getOrNull()
        }
        val candidates = if (cached != null) {
            listOf(cached) + defaultOrder.filter { it != cached }
        } else {
            defaultOrder
        }

        var lastError: Exception? = null
        for (backend in candidates) {
            if (backend == Backend.NNAPI && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                continue // NNAPI EP requires API 27+ (minSdk is 26)
            }
            try {
                val session = buildSession(modelFile, modelName, backend)
                lastBackend = backend
                prefs?.edit()?.putString(KEY_BACKEND_PREFIX + modelName, backend.name)?.apply()
                Log.i(TAG, "ONNX session for $modelName created on $backend")
                return session
            } catch (e: Exception) {
                Log.w(TAG, "$backend backend unavailable for $modelName: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No usable ONNX backend for $modelName")
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
