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

    @Synchronized
    fun getSession(model: RembgModel): OrtSession {
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
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(2)
        }
        return environment.createSession(modelFile.absolutePath, options)
    }

    fun closeSession(model: RembgModel) {
        sessions.remove(model.id)?.close()
    }

    fun closeAll() {
        sessions.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sessions.clear()
    }

    fun getInputName(model: RembgModel): String =
        getSession(model).inputNames.iterator().next()
}
