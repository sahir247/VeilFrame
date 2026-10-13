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
import java.security.MessageDigest
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

    // Lock for serializing qualification per model to prevent concurrent native-memory spikes
    private val qualificationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Computes a cryptographic SHA-256 digest of the model file contents.
     * Prevents cache collisions where a model is replaced with different weights
     * but identical filename, size, and lastModified timestamp.
     * Returns null if digest calculation fails; does NOT substitute weak non-cryptographic hashes.
     */
    fun computeContentDigest(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(65536).use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to compute SHA-256 digest for ${file.name}: ${t.message}")
            null
        }
    }

    /**
     * Compute a cache key bound to:
     * 1. Model filename
     * 2. Cryptographic SHA-256 content digest (first 16 hex chars)
     * 3. Hardware, OS, and build identity (to invalidate across driver/OS updates)
     * Returns null if content digest is unavailable (remains uncached).
     */
    fun getCacheKey(modelFile: File): String? {
        val modelName = modelFile.name
        val contentDigest = computeContentDigest(modelFile) ?: return null
        val deviceFingerprint = "${Build.HARDWARE}_${Build.VERSION.SDK_INT}_${Build.FINGERPRINT}"
        val devHash = deviceFingerprint.hashCode().toUInt().toString(16)
        return "$KEY_BACKEND_PREFIX${modelName}_${contentDigest.take(16)}_$devHash"
    }

    fun createSession(modelFile: File, context: Context? = null): OrtSession {
        val modelName = modelFile.name
        val cacheKey = getCacheKey(modelFile)
        val prefs = if (cacheKey != null) context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) else null
        val cached = if (cacheKey != null) prefs?.getString(cacheKey, null)?.let { name ->
            runCatching { Backend.valueOf(name) }.getOrNull()
        } else null

        if (cached != null && isUsable(cached)) {
            try {
                val session = buildSession(modelFile, modelName, cached)
                lastBackend = cached
                Log.i(TAG, "ONNX session for $modelName on cached backend $cached (key=$cacheKey)")
                return session
            } catch (e: Exception) {
                Log.w(TAG, "Cached backend $cached failed for $modelName; re-selecting", e)
                if (cacheKey != null) prefs?.edit()?.remove(cacheKey)?.apply()
            }
        }

        // Serialize qualification for this model so concurrent calls don't duplicate candidate creation
        val lock = qualificationLocks.computeIfAbsent(modelName) { Any() }
        synchronized(lock) {
            // Re-check cache inside lock in case another thread just qualified it
            val recheckedCached = if (cacheKey != null) prefs?.getString(cacheKey, null)?.let { name ->
                runCatching { Backend.valueOf(name) }.getOrNull()
            } else null
            if (recheckedCached != null && isUsable(recheckedCached)) {
                try {
                    val session = buildSession(modelFile, modelName, recheckedCached)
                    lastBackend = recheckedCached
                    return session
                } catch (_: Exception) {
                    if (cacheKey != null) prefs?.edit()?.remove(cacheKey)?.apply()
                }
            }

            var bestSession: OrtSession? = null
            var bestBackend: Backend? = null
            var bestTiming: Double = Double.MAX_VALUE
            val timingLog = mutableListOf<String>()
            var lastError: Exception? = null

            for (backend in defaultOrder) {
                if (!isUsable(backend)) continue
                var candidateSession: OrtSession? = null
                try {
                    candidateSession = buildSession(modelFile, modelName, backend)
                    val timing = benchmark(candidateSession, modelName)
                    if (timing != null) {
                        timingLog.add("$backend=${"%,.0f".format(timing)}ms")
                        if (timing < bestTiming) {
                            // Close previously retained candidate session immediately to release native memory
                            bestSession?.let { runCatching { it.close() } }
                            bestSession = candidateSession
                            bestBackend = backend
                            bestTiming = timing
                            candidateSession = null // Ownership transferred to bestSession
                        }
                    } else {
                        timingLog.add("$backend=unranked")
                        if (bestSession == null) {
                            // Unranked fallback candidate: keep as conservative fallback until a ranked candidate appears
                            bestSession = candidateSession
                            bestBackend = backend
                            bestTiming = Double.MAX_VALUE
                            candidateSession = null
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$backend backend unavailable for $modelName: ${e.message}")
                    lastError = e
                } finally {
                    // Close candidateSession immediately if not chosen/retained
                    candidateSession?.let { runCatching { it.close() } }
                }
            }

            val winnerSession = bestSession ?: throw (lastError ?: IllegalStateException("No usable ONNX backend for $modelName"))
            val winnerBackend = bestBackend ?: Backend.CPU
            lastBackend = winnerBackend
            if (cacheKey != null) {
                prefs?.edit()?.putString(cacheKey, winnerBackend.name)?.apply()
            }
            Log.i(
                TAG,
                "Backend selection for $modelName: $winnerBackend (timings: ${timingLog.joinToString()})"
            )
            return winnerSession
        }
    }

    private fun isUsable(backend: Backend): Boolean = when (backend) {
        Backend.NNAPI -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 // API 27+ (minSdk is 26)
        else -> true
    }

    /**
     * Synthetic timing run on a 256px tile with 2 warm-up passes and
     * a 3-iteration median timed measurement to reject scheduler/frequency scaling noise.
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

            // Warm-up: 2 passes to trigger lazy EP compilation, kernel JIT, and thread allocation
            repeat(2) {
                session.run(tensors).use { }
            }

            // Timed runs: 3 iterations, take median to reject CPU governor & scheduling jitter
            val samples = DoubleArray(3)
            for (i in 0 until 3) {
                val t0 = System.nanoTime()
                session.run(tensors).use { }
                samples[i] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samples.sort()
            samples[1] // median
        } catch (t: Throwable) {
            Log.w(TAG, "Benchmark failed for $modelName: ${t.message}")
            null
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun buildSession(modelFile: File, modelName: String, backend: Backend): OrtSession {
        return OrtSession.SessionOptions().use { options ->
            val processors = Runtime.getRuntime().availableProcessors()
            val targetThreads = UpscaleInferenceParams.computeBudget(processors).intraOpThreads
            when (backend) {
                Backend.NNAPI -> {
                    // Vendor driver picks GPU/DSP/CPU delegation per op support.
                    options.addNnapi()
                }
                Backend.XNNPACK -> {
                    // Coupled compute budget matching parallel tile workers
                    options.addXnnpack(mapOf("intra_op_num_threads" to targetThreads.toString()))
                }
                Backend.CPU -> {
                    // Coupled compute budget matching parallel tile workers
                    try {
                        options.setIntraOpNumThreads(targetThreads)
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

            OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
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
