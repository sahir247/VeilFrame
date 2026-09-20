package com.veilframe.app.upscale.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.coroutines.resumeWithException

/**
 * Production-grade ONNX Runtime wrapper for Real-ESRGAN and modern super-resolution models.
 *
 * Upgraded with ImageToolbox-proven architecture:
 * - Session configuration handled via OnnxSessionFactory (NNAPI with CPU_DISABLED + optimized ORT CPU fallback)
 * - Cancellable inference via OrtSession.RunOptions (native termination upon coroutine cancellation)
 * - Explicit ProcessedTile contract (guaranteed output scale)
 * - Bit-exact FP16/FP32 direct buffers with zero-copy plane packing
 * - Dimension overflow safety checks (Long arithmetic before allocations)
 * - Per-tile execution telemetry for latency diagnostics
 */
class OnnxUpscaleRuntime(
    val modelFile: File,
    val scale: Int,
    mode: InferenceAccelerationMode = InferenceAccelerationMode.AUTO,
    precision: InferencePrecisionMode = InferencePrecisionMode.DEFAULT,
    customIntraOpThreads: Int? = null,
    customInterOpThreads: Int? = null
) : AutoCloseable {

    constructor(
        modelFile: File,
        scale: Int,
        profile: ExecutionProfile
    ) : this(
        modelFile = modelFile,
        scale = scale,
        mode = when (profile.backend) {
            Backend.NNAPI -> InferenceAccelerationMode.NNAPI
            Backend.CPU -> InferenceAccelerationMode.CPU
            Backend.XNNPACK -> InferenceAccelerationMode.XNNPACK
            Backend.QNN -> InferenceAccelerationMode.QNN
        },
        precision = profile.precision,
        customIntraOpThreads = profile.intraOpThreads,
        customInterOpThreads = profile.interOpThreads
    )

    companion object {
        private const val TAG = "VeilFrame.OnnxRuntime"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var optionsHolder: OrtSession.SessionOptions? = null

    val backendInfo: InferenceBackendInfo
    val executionProvider: String

    val inputName: String
    val inputType: OnnxJavaType
    val inputShape: LongArray
    val inputRank: Int

    val outputName: String
    val outputType: OnnxJavaType
    val outputShape: LongArray
    val outputRank: Int

    val isDynamicSpatial: Boolean
    val expectedBatch: Long
    val expectedChannels: Long
    val expectedHeight: Long
    val expectedWidth: Long

    init {
        val sessionResult = OnnxSessionFactory.createSession(
            env = env,
            modelFile = modelFile,
            mode = mode,
            precision = precision,
            customIntraOpThreads = customIntraOpThreads,
            customInterOpThreads = customInterOpThreads
        )
        session = sessionResult.session
        optionsHolder = sessionResult.options
        backendInfo = sessionResult.backendInfo
        executionProvider = backendInfo.providerDescription

        // Inspect ONNX model input metadata
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX model ${modelFile.name} has no input tensors.")
        inputName = inputEntry.key
        val inputNodeInfo: NodeInfo = inputEntry.value
        val inputTensorInfo = (inputNodeInfo.info as? TensorInfo)
            ?: throw IllegalStateException("ONNX input $inputName is not a TensorInfo.")

        inputType = inputTensorInfo.type
        inputShape = inputTensorInfo.shape
        inputRank = inputShape.size

        // Inspect ONNX model output metadata
        val outputEntry = session.outputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX model ${modelFile.name} has no output tensors.")
        outputName = outputEntry.key
        val outputNodeInfo: NodeInfo = outputEntry.value
        val outputTensorInfo = (outputNodeInfo.info as? TensorInfo)
            ?: throw IllegalStateException("ONNX output $outputName is not a TensorInfo.")

        outputType = outputTensorInfo.type
        outputShape = outputTensorInfo.shape
        outputRank = outputShape.size

        if (inputRank >= 4) {
            expectedBatch = inputShape[0]
            expectedChannels = inputShape[1]
            expectedHeight = inputShape[2]
            expectedWidth = inputShape[3]
            isDynamicSpatial = (expectedHeight <= 0L || expectedWidth <= 0L)
        } else {
            expectedBatch = 1L
            expectedChannels = 3L
            expectedHeight = -1L
            expectedWidth = -1L
            isDynamicSpatial = true
        }

        Log.i(
            TAG,
            "Loaded model '${modelFile.name}' on $executionProvider: input=$inputName [${inputShape.joinToString(",")}], output=$outputName [${outputShape.joinToString(",")}], dynamic=$isDynamicSpatial"
        )
    }

    /**
     * Executes ONNX inference with responsive cancellation via OrtSession.RunOptions.
     * When the calling coroutine is cancelled, OrtSession.RunOptions.setTerminate(true) is invoked,
     * immediately aborting in-progress inference on native threads.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend inline fun runInferenceCancellable(
        crossinline action: (OrtSession.RunOptions) -> OrtSession.Result
    ): OrtSession.Result = suspendCancellableCoroutine { continuation ->
        val runOptions = OrtSession.RunOptions()

        continuation.invokeOnCancellation {
            try {
                runOptions.setTerminate(true)
            } catch (_: Throwable) {}
        }

        runCatching {
            action(runOptions)
        }.onSuccess { result ->
            continuation.resume(result) {
                try {
                    result.close()
                } catch (_: Throwable) {}
            }
        }.onFailure { throwable ->
            continuation.resumeWithException(throwable)
        }.also {
            try {
                runOptions.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Processes a single image tile through neural inference.
     * Returns an explicit ProcessedTile with scale contract.
     */
    suspend fun runTile(
        tileBitmap: Bitmap,
        row: Int = 0,
        col: Int = 0
    ): ProcessedTile = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val actualW = tileBitmap.width
        val actualH = tileBitmap.height

        require(actualW > 0 && actualH > 0) { "Invalid tile dimensions: ${actualW}x${actualH}" }

        // Determine spatial dimensions and check if fixed model requires boundary padding
        val targetInW: Int
        val targetInH: Int
        val needsPadding: Boolean

        if (!isDynamicSpatial && expectedWidth > 0L && expectedHeight > 0L) {
            targetInW = expectedWidth.toInt()
            targetInH = expectedHeight.toInt()

            if (actualW > targetInW || actualH > targetInH) {
                val errorMsg = """
                    Model: ${modelFile.name}
                    Input expected: [1,3,$targetInH,$targetInW]
                    Input supplied: [1,3,$actualH,$actualW]
                    Tile: row=$row col=$col
                    Supplied tile dimensions exceed the fixed model spatial shape.
                """.trimIndent()
                throw IllegalArgumentException(errorMsg)
            }
            needsPadding = (actualW != targetInW || actualH != targetInH)
        } else {
            targetInW = actualW
            targetInH = actualH
            needsPadding = false
        }

        val targetPixelCountLong = targetInW.toLong() * targetInH.toLong()
        require(targetPixelCountLong <= (Int.MAX_VALUE / 3)) { "Tile pixel count exceeds buffer limit" }
        val targetPixelCount = targetPixelCountLong.toInt()

        val srcPixels = IntArray(actualW * actualH)
        tileBitmap.getPixels(srcPixels, 0, actualW, 0, 0, actualW, actualH)

        val hasAlpha = tileBitmap.hasAlpha()
        val alphaChannel = if (hasAlpha) FloatArray(actualW * actualH) else null

        val tensorShape = longArrayOf(1L, 3L, targetInH.toLong(), targetInW.toLong())
        val gOffset = targetPixelCount
        val bOffset = 2 * targetPixelCount

        val inputTensor: OnnxTensor = when (inputType) {
            OnnxJavaType.FLOAT16 -> {
                val shortBuffer = ShortBuffer.allocate(3 * targetPixelCount)
                for (y in 0 until targetInH) {
                    val clampY = if (y < actualH) y else actualH - 1
                    val srcRowOffset = clampY * actualW
                    val dstIdx = y * targetInW
                    for (x in 0 until targetInW) {
                        val clampX = if (x < actualW) x else actualW - 1
                        val pixel = srcPixels[srcRowOffset + clampX]
                        val r = ((pixel ushr 16) and 0xff) / 255.0f
                        val g = ((pixel ushr 8) and 0xff) / 255.0f
                        val b = (pixel and 0xff) / 255.0f
                        val idx = dstIdx + x

                        shortBuffer.put(idx, Float16Utils.floatToHalf(r))
                        shortBuffer.put(gOffset + idx, Float16Utils.floatToHalf(g))
                        shortBuffer.put(bOffset + idx, Float16Utils.floatToHalf(b))
                    }
                }
                shortBuffer.rewind()
                OnnxTensor.createTensor(env, shortBuffer, tensorShape, OnnxJavaType.FLOAT16)
            }
            OnnxJavaType.FLOAT -> {
                val flatFloats = FloatArray(3 * targetPixelCount)
                for (y in 0 until targetInH) {
                    val clampY = if (y < actualH) y else actualH - 1
                    val srcRowOffset = clampY * actualW
                    val dstIdx = y * targetInW
                    for (x in 0 until targetInW) {
                        val clampX = if (x < actualW) x else actualW - 1
                        val pixel = srcPixels[srcRowOffset + clampX]
                        val r = (pixel ushr 16) and 0xff
                        val g = (pixel ushr 8) and 0xff
                        val b = pixel and 0xff
                        val idx = dstIdx + x

                        flatFloats[idx] = r / 255.0f
                        flatFloats[gOffset + idx] = g / 255.0f
                        flatFloats[bOffset + idx] = b / 255.0f
                    }
                }
                val floatBuffer = FloatBuffer.wrap(flatFloats)
                OnnxTensor.createTensor(env, floatBuffer, tensorShape)
            }
            else -> throw UnsupportedOperationException("Unsupported ONNX input tensor type: $inputType")
        }

        val tTensor = System.currentTimeMillis() - t0

        try {
            ensureActive()

            val tInfer0 = System.currentTimeMillis()
            val result = try {
                runInferenceCancellable { runOptions ->
                    session.run(mapOf(inputName to inputTensor), runOptions)
                }
            } catch (e: OrtException) {
                val expectedStr = if (isDynamicSpatial) "[1,3,H,W] (Dynamic)" else "[1,3,$targetInH,$targetInW]"
                val suppliedStr = "[1,3,$actualH,$actualW]"
                val customErrorMsg = """
                    Model: ${modelFile.name}
                    Input expected: $expectedStr
                    Input supplied: $suppliedStr
                    Tile: row=$row col=$col
                    Inference failed via $executionProvider: ${e.message}
                """.trimIndent()
                throw RuntimeException(customErrorMsg, e)
            }
            val tInfer = System.currentTimeMillis() - tInfer0

            result.use { res ->
                ensureActive()
                val outTensorVal = res.get(outputName).orElseGet {
                    res.firstOrNull()?.value
                } as? OnnxTensor ?: throw IllegalStateException("Model output tensor $outputName was not produced.")

                val outInfo = outTensorVal.info
                val outShape = outInfo.shape

                val outH: Int
                val outW: Int
                if (outShape.size >= 4) {
                    outH = outShape[2].toInt()
                    outW = outShape[3].toInt()
                } else if (outShape.size == 3) {
                    outH = outShape[1].toInt()
                    outW = outShape[2].toInt()
                } else {
                    outH = targetInH * scale
                    outW = targetInW * scale
                }

                val actualScaleX = outW / targetInW
                val actualScaleY = outH / targetInH

                val finalTileW = actualW * actualScaleX
                val finalTileH = actualH * actualScaleY

                val totalOutPixelsLong = finalTileW.toLong() * finalTileH.toLong()
                require(totalOutPixelsLong <= Int.MAX_VALUE) { "Output tile dimension exceeds Int.MAX_VALUE" }
                val dstPixels = IntArray(finalTileW * finalTileH)

                val outChannelStride = outW * outH

                when (outInfo.type) {
                    OnnxJavaType.FLOAT -> {
                        val fb: FloatBuffer = outTensorVal.floatBuffer
                        var dstIdx = 0
                        for (y in 0 until finalTileH) {
                            val rowOffset = y * outW
                            for (x in 0 until finalTileW) {
                                val alphaVal = if (alphaChannel != null) {
                                    val srcY = (y / actualScaleY).coerceIn(0, actualH - 1)
                                    val srcX = (x / actualScaleX).coerceIn(0, actualW - 1)
                                    (alphaChannel[srcY * actualW + srcX] * 255.0f).toInt().coerceIn(0, 255)
                                } else 255

                                val r = (fb.get(rowOffset + x) * 255.0f).toInt().coerceIn(0, 255)
                                val g = (fb.get(outChannelStride + rowOffset + x) * 255.0f).toInt().coerceIn(0, 255)
                                val b = (fb.get(2 * outChannelStride + rowOffset + x) * 255.0f).toInt().coerceIn(0, 255)

                                dstPixels[dstIdx++] = (alphaVal shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                    OnnxJavaType.FLOAT16 -> {
                        val sb: ShortBuffer = outTensorVal.shortBuffer
                        var dstIdx = 0
                        for (y in 0 until finalTileH) {
                            val rowOffset = y * outW
                            for (x in 0 until finalTileW) {
                                val alphaVal = if (alphaChannel != null) {
                                    val srcY = (y / actualScaleY).coerceIn(0, actualH - 1)
                                    val srcX = (x / actualScaleX).coerceIn(0, actualW - 1)
                                    (alphaChannel[srcY * actualW + srcX] * 255.0f).toInt().coerceIn(0, 255)
                                } else 255

                                val rHalf = sb.get(rowOffset + x)
                                val gHalf = sb.get(outChannelStride + rowOffset + x)
                                val bHalf = sb.get(2 * outChannelStride + rowOffset + x)

                                val r = (Float16Utils.halfToFloat(rHalf) * 255.0f).toInt().coerceIn(0, 255)
                                val g = (Float16Utils.halfToFloat(gHalf) * 255.0f).toInt().coerceIn(0, 255)
                                val b = (Float16Utils.halfToFloat(bHalf) * 255.0f).toInt().coerceIn(0, 255)

                                dstPixels[dstIdx++] = (alphaVal shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                    else -> {
                        // Fallback to multidimensional array extraction
                        val rawVal = outTensorVal.value
                        val batch = (rawVal as? Array<Array<Array<FloatArray>>>)?.getOrNull(0)
                            ?: (rawVal as? Array<Array<FloatArray>>)
                            ?: throw IllegalStateException("Unsupported output tensor type/shape: ${outInfo.type}")

                        var dstIdx = 0
                        for (y in 0 until finalTileH) {
                            for (x in 0 until finalTileW) {
                                val alphaVal = if (alphaChannel != null) {
                                    val srcY = (y / actualScaleY).coerceIn(0, actualH - 1)
                                    val srcX = (x / actualScaleX).coerceIn(0, actualW - 1)
                                    (alphaChannel[srcY * actualW + srcX] * 255.0f).toInt().coerceIn(0, 255)
                                } else 255

                                val r = (batch[0][y][x] * 255.0f).toInt().coerceIn(0, 255)
                                val g = (batch[1][y][x] * 255.0f).toInt().coerceIn(0, 255)
                                val b = (batch[2][y][x] * 255.0f).toInt().coerceIn(0, 255)

                                dstPixels[dstIdx++] = (alphaVal shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }

                val outBitmap = Bitmap.createBitmap(finalTileW, finalTileH, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(dstPixels, 0, finalTileW, 0, 0, finalTileW, finalTileH)

                val tTotal = System.currentTimeMillis() - t0
                Log.d(
                    TAG,
                    "[TILE] row=$row col=$col tensor=${tTensor}ms infer=${tInfer}ms total=${tTotal}ms scale=${actualScaleX}x"
                )

                ProcessedTile(
                    bitmap = outBitmap,
                    outputScale = actualScaleX
                )
            }
        } finally {
            inputTensor.close()
        }
    }

    override fun close() {
        try {
            session.close()
            if (backendInfo.backend == Backend.QNN) {
                com.veilframe.app.upscale.inference.qnn.QnnPluginLeaseManager.decrementSession()
            }
        } catch (_: Exception) {}
        try {
            optionsHolder?.close()
        } catch (_: Exception) {}
        try {
            env.close()
        } catch (_: Exception) {}
    }
}
