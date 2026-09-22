package com.veilframe.app.upscale.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resumeWithException

object OnnxSessionManager {
    private const val TAG = "VeilFrame.SessionMgr"

    fun createSession(modelFile: File): OrtSession {
        val modelName = modelFile.name
        val options = OrtSession.SessionOptions().apply {
            val processors = Runtime.getRuntime().availableProcessors()
            try {
                setIntraOpNumThreads(if (processors <= 2) 1 else (processors * 3) / 4)
            } catch (e: OrtException) {
                Log.w(TAG, "Error setting IntraOpNumThreads: ${e.message}")
            }
            try {
                setInterOpNumThreads(4)
            } catch (e: OrtException) {
                Log.w(TAG, "Error setting InterOpNumThreads: ${e.message}")
            }
            try {
                when {
                    modelName.endsWith(".ort", ignoreCase = true) -> {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    }
                    modelName.startsWith("scunet_", ignoreCase = true) -> {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    }
                    modelName.startsWith("fbcnn_", ignoreCase = true) -> {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                    }
                    else -> {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                }
            } catch (e: OrtException) {
                Log.w(TAG, "Error setting OptimizationLevel: ${e.message}")
            }
        }

        return OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
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
