package com.veilframe.app.cv.segmentation.rembg

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ONNX Runtime sessions for background removal models.
 * Thread-safe caching and lifecycle management.
 */
class OnnxSessionManager(
    private val modelRepository: RembgModelRepository,
) {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = ConcurrentHashMap<String, OrtSession>()
    @Volatile private var activeModelId: String? = null

    @Synchronized
    fun getSession(model: RembgModel): OrtSession {
        if (activeModelId != null && activeModelId != model.id) {
            // Evict previous model when switching to keep at most 1 active model resident
            sessions.remove(activeModelId)?.let { runCatching { it.close() } }
        }
        activeModelId = model.id
        return sessions.getOrPut(model.id) {
            createSession(model)
        }
    }

    @Synchronized
    fun recreateSession(model: RembgModel): OrtSession {
        closeSession(model)
        return getSession(model)
    }

    private fun createSession(model: RembgModel): OrtSession {
        val modelFile = modelRepository.getModelFile(model)
        require(modelRepository.isModelReady(model)) {
            "Model ${model.displayName} is not downloaded or incomplete"
        }
        val processors = Runtime.getRuntime().availableProcessors()
        val threads = minOf(4, maxOf(2, processors / 2))
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setMemoryPatternOptimization(true)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(threads)
            environment.createSession(modelFile.absolutePath, options)
        }
    }

    fun closeSession(model: RembgModel) {
        if (activeModelId == model.id) {
            activeModelId = null
        }
        sessions.remove(model.id)?.close()
    }

    fun closeAll() {
        activeModelId = null
        sessions.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    fun getInputName(model: RembgModel): String =
        getSession(model).inputNames.iterator().next()
}
