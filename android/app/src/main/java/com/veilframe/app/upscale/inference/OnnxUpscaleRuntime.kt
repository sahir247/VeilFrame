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
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Production-grade ONNX Runtime wrapper for Real-ESRGAN and modern super-resolution models.
 * Features:
 * - Comprehensive metadata inspection and logging immediately upon session creation
 * - Support for both dynamic spatial dimensions and fixed spatial dimensions with boundary tile padding & unpadding
 * - Support for both FLOAT32 and FLOAT16 tensors (via bit-exact IEEE-754 binary16 conversion)
 * - Safe output structure validation and dynamic dimension derivation directly from the output tensor
 * - High-clarity error formatting on ORT exceptions indicating model name, expected shape, supplied shape, and tile coordinates
 */
class OnnxUpscaleRuntime(
    val modelFile: File,
    val scale: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "VeilFrame.OnnxRuntime"

        fun isQualcommPlatform(): Boolean {
            return try {
                val hw = android.os.Build.HARDWARE?.lowercase(java.util.Locale.US) ?: ""
                val board = android.os.Build.BOARD?.lowercase(java.util.Locale.US) ?: ""
                val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.os.Build.SOC_MANUFACTURER?.lowercase(java.util.Locale.US) ?: ""
                } else ""
                hw.contains("qcom") || hw.contains("snapdragon") ||
                        soc.contains("qualcomm") || board.contains("qcom")
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

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

    val executionProvider: String
    private var optionsHolder: OrtSession.SessionOptions? = null

    init {
        val availableCores = Runtime.getRuntime().availableProcessors()
        val intraOpThreads = if (availableCores <= 2) 1 else 2

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        optionsHolder = sessionOptions

        var ep = "CPU ($intraOpThreads threads)"
        val isQualcomm = isQualcommPlatform()

        if (isQualcomm) {
            try {
                // Try Qualcomm AI Engine Direct (QNN) HTP (Hexagon Tensor Processor)
                sessionOptions.addQnn(mapOf("backend_path" to "libQnnHtp.so"))
                ep = "QNN (Qualcomm HTP)"
                Log.i(TAG, "Hardware Acceleration: Enabled Qualcomm AI Engine Direct (QNN HTP).")
            } catch (eQnn: Throwable) {
                Log.w(TAG, "Qualcomm QNN HTP provider unavailable: ${eQnn.message}; falling back to NNAPI.")
                try {
                    sessionOptions.addNnapi()
                    ep = "NNAPI (Qualcomm Accelerator)"
                    Log.i(TAG, "Hardware Acceleration: Enabled Android NNAPI.")
                } catch (eNnapi: Throwable) {
                    Log.w(TAG, "NNAPI provider unavailable: ${eNnapi.message}; using optimized CPU fallback.")
                    ep = "CPU ($intraOpThreads threads)"
                }
            }
        } else {
            try {
                sessionOptions.addNnapi()
                ep = "NNAPI (Unified Accelerator)"
                Log.i(TAG, "Hardware Acceleration: Enabled Android NNAPI.")
            } catch (eNnapi: Throwable) {
                Log.w(TAG, "NNAPI provider unavailable: ${eNnapi.message}; using optimized CPU fallback.")
                ep = "CPU ($intraOpThreads threads)"
            }
        }

        session = try {
            env.createSession(modelFile.absolutePath, sessionOptions)
        } catch (eInit: Throwable) {
            Log.w(TAG, "Failed to create session with $ep: ${eInit.message}; falling back to standard CPU session.")
            val cpuOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            optionsHolder = cpuOptions
            ep = "CPU (Fallback $intraOpThreads threads)"
            env.createSession(modelFile.absolutePath, cpuOptions)
        }
        executionProvider = ep

        // 1. Inspect ONNX Runtime model metadata immediately after session creation
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX model ${modelFile.name} has no input tensors.")
        inputName = inputEntry.key
        val inputNodeInfo: NodeInfo = inputEntry.value
        val inputTensorInfo = (inputNodeInfo.info as? TensorInfo)
            ?: throw IllegalStateException("ONNX input ${inputName} is not a TensorInfo.")

        inputType = inputTensorInfo.type
        inputShape = inputTensorInfo.shape
        inputRank = inputShape.size

        val outputEntry = session.outputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX model ${modelFile.name} has no output tensors.")
        outputName = outputEntry.key
        val outputNodeInfo: NodeInfo = outputEntry.value
        val outputTensorInfo = (outputNodeInfo.info as? TensorInfo)
            ?: throw IllegalStateException("ONNX output ${outputName} is not a TensorInfo.")

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

        // Detailed logging of metadata as required by Specification Item 1
        try {
            Log.i(TAG, "==========================================================")
            Log.i(TAG, "ONNX Model Metadata Inspection: ${modelFile.name}")
            Log.i(TAG, "  Model Filename:    ${modelFile.name} (${modelFile.length()} bytes)")
            Log.i(TAG, "  Input Name:        $inputName")
            Log.i(TAG, "  Input Type:        $inputType")
            Log.i(TAG, "  Input Rank:        $inputRank")
            Log.i(TAG, "  Input Dimensions:  ${inputShape.contentToString()}")
            Log.i(TAG, "  Output Name:       $outputName")
            Log.i(TAG, "  Output Type:       $outputType")
            Log.i(TAG, "  Output Rank:       $outputRank")
            Log.i(TAG, "  Output Dimensions: ${outputShape.contentToString()}")
            Log.i(TAG, "  Spatial Dimension: ${if (isDynamicSpatial) "Dynamic H/W" else "Fixed [H=$expectedHeight, W=$expectedWidth]"}")
            Log.i(TAG, "==========================================================")
        } catch (_: Throwable) {
            // Log fallback for non-Android environments (pure JUnit)
            println("ONNX Model: ${modelFile.name}, Input: $inputName $inputType ${inputShape.contentToString()}, Output: $outputName $outputType ${outputShape.contentToString()}, Dynamic: $isDynamicSpatial")
        }
    }

    /**
     * Executes inference for a single image tile.
     * Supports both dynamic models and fixed spatial models with automatic boundary padding and cropping.
     * Supports both FLOAT32 and FLOAT16 models.
     *
     * @param tileBitmap The input tile bitmap (e.g. 512x512 or boundary tile 312x400)
     * @param row The row coordinate in the tile grid for error reporting
     * @param col The col coordinate in the tile grid for error reporting
     */
    suspend fun runTile(tileBitmap: Bitmap, row: Int = 0, col: Int = 0): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()
        val actualW = tileBitmap.width
        val actualH = tileBitmap.height

        if (actualW <= 0 || actualH <= 0) {
            throw IllegalArgumentException("Invalid tile dimensions: ${actualW}x${actualH}")
        }

        // Determine input dimensions and whether boundary padding is required
        val targetInW: Int
        val targetInH: Int
        val needsPadding: Boolean

        if (!isDynamicSpatial && expectedWidth > 0L && expectedHeight > 0L) {
            targetInW = expectedWidth.toInt()
            targetInH = expectedHeight.toInt()

            if (actualW > targetInW || actualH > targetInH) {
                val errorMsg = """
                    Model:
                    ${modelFile.name}

                    Input expected:
                    [1,3,$targetInH,$targetInW]

                    Input supplied:
                    [1,3,$actualH,$actualW]

                    Tile:
                    row=$row col=$col

                    The supplied tile dimensions exceed the fixed model spatial shape.
                """.trimIndent()
                throw IllegalArgumentException(errorMsg)
            }
            needsPadding = (actualW != targetInW || actualH != targetInH)
        } else {
            // Dynamic spatial dimensions
            targetInW = actualW
            targetInH = actualH
            needsPadding = false
        }

        val targetPixelCount = targetInW * targetInH
        val srcPixels = IntArray(actualW * actualH)
        tileBitmap.getPixels(srcPixels, 0, actualW, 0, 0, actualW, actualH)

        val hasAlpha = tileBitmap.hasAlpha()
        val alphaChannel = if (hasAlpha) FloatArray(actualW * actualH) else null

        // Populate NCHW float planes [0.0f, 1.0f]
        // Populate single direct buffer without intermediate plane arrays or duplicates
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
            else -> {
                throw UnsupportedOperationException("Unsupported ONNX input tensor type: $inputType")
            }
        }

        try {
            ensureActive()

            val result = try {
                session.run(mapOf(inputName to inputTensor))
            } catch (e: OrtException) {
                // 8. Improve the ORT error message with exact context
                val expectedStr = if (isDynamicSpatial) "[1,3,H,W] (Dynamic)" else "[1,3,$targetInH,$targetInW]"
                val suppliedStr = "[1,3,$actualH,$actualW]"
                val customErrorMsg = """
                    Model:
                    ${modelFile.name}

                    Input expected:
                    $expectedStr

                    Input supplied:
                    $suppliedStr

                    Tile:
                    row=$row col=$col

                    Inference execution failed: ${e.message}
                    The tile was incompatible with the model shape or internal layer dimensions.
                """.trimIndent()
                throw IllegalStateException(customErrorMsg, e)
            }

            result.use { res ->
                // 7. Validate output structure before casting
                if (res.size() == 0) {
                    throw IllegalStateException("ONNX model ${modelFile.name} returned empty inference results.")
                }

                val outValue = res.get(0)
                val outTensor = (outValue as? OnnxTensor)
                    ?: throw IllegalStateException("Expected OnnxTensor in result[0], but got: ${outValue?.javaClass?.name}")

                val outTensorInfo = (outTensor.info as? TensorInfo)
                    ?: throw IllegalStateException("Output tensor info is not TensorInfo: ${outTensor.info?.javaClass?.name}")

                val actualOutShape = outTensorInfo.shape
                if (actualOutShape.size < 4) {
                    throw IllegalStateException("Expected 4D NCHW output shape, got: ${actualOutShape.contentToString()}")
                }

                // 5. Derive output dimensions from the actual output tensor
                val outTensorH = actualOutShape[2].toInt()
                val outTensorW = actualOutShape[3].toInt()
                val outPixelCount = outTensorW * outTensorH

                val actualScaleX = outTensorW / targetInW
                val actualScaleY = outTensorH / targetInH

                // Crop output dimensions to original unpadded tile size
                val finalTileW = if (needsPadding) actualW * actualScaleX else outTensorW
                val finalTileH = if (needsPadding) actualH * actualScaleY else outTensorH
                val finalPixelCount = finalTileW * finalTileH
                val dstPixels = IntArray(finalPixelCount)

                val gBase = outPixelCount
                val bBase = 2 * outPixelCount

                when (outTensorInfo.type) {
                    OnnxJavaType.FLOAT16 -> {
                        val shortBuf = outTensor.shortBuffer
                        shortBuf.rewind()
                        var dstIdx = 0
                        for (y in 0 until finalTileH) {
                            val outYIdx = y * outTensorW
                            for (x in 0 until finalTileW) {
                                val tensorIdx = outYIdx + x
                                val alphaVal = if (alphaChannel != null) {
                                    val srcY = (y / actualScaleY).coerceIn(0, actualH - 1)
                                    val srcX = (x / actualScaleX).coerceIn(0, actualW - 1)
                                    (alphaChannel[srcY * actualW + srcX] * 255.0f).toInt().coerceIn(0, 255)
                                } else 255

                                val r = (Float16Utils.halfToFloat(shortBuf.get(tensorIdx)) * 255.0f).toInt().coerceIn(0, 255)
                                val g = (Float16Utils.halfToFloat(shortBuf.get(gBase + tensorIdx)) * 255.0f).toInt().coerceIn(0, 255)
                                val b = (Float16Utils.halfToFloat(shortBuf.get(bBase + tensorIdx)) * 255.0f).toInt().coerceIn(0, 255)

                                dstPixels[dstIdx++] = (alphaVal shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                    OnnxJavaType.FLOAT -> {
                        val floatBuf = outTensor.floatBuffer
                        floatBuf.rewind()
                        // Copy single contiguous buffer for direct indexed access (avoids 3 separate FloatArrays)
                        val allFloats = FloatArray(3 * outPixelCount)
                        floatBuf.get(allFloats)

                        var dstIdx = 0
                        for (y in 0 until finalTileH) {
                            val outYIdx = y * outTensorW
                            for (x in 0 until finalTileW) {
                                val tensorIdx = outYIdx + x
                                val alphaVal = if (alphaChannel != null) {
                                    val srcY = (y / actualScaleY).coerceIn(0, actualH - 1)
                                    val srcX = (x / actualScaleX).coerceIn(0, actualW - 1)
                                    (alphaChannel[srcY * actualW + srcX] * 255.0f).toInt().coerceIn(0, 255)
                                } else 255

                                val r = (allFloats[tensorIdx] * 255.0f).toInt().coerceIn(0, 255)
                                val g = (allFloats[gBase + tensorIdx] * 255.0f).toInt().coerceIn(0, 255)
                                val b = (allFloats[bBase + tensorIdx] * 255.0f).toInt().coerceIn(0, 255)

                                dstPixels[dstIdx++] = (alphaVal shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                    else -> {
                        // Multi-dimensional array fallback
                        val rawValue = outTensor.value
                        when (rawValue) {
                            is Array<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                val batch = rawValue[0] as Array<Array<FloatArray>>
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
                            else -> throw IllegalStateException("Unsupported output tensor type / structure: ${outTensorInfo.type}")
                        }
                    }
                }

                val outBitmap = Bitmap.createBitmap(finalTileW, finalTileH, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(dstPixels, 0, finalTileW, 0, 0, finalTileW, finalTileH)
                outBitmap
            }
        } finally {
            inputTensor.close()
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {}
        try {
            optionsHolder?.close()
        } catch (_: Exception) {}
        try {
            env.close()
        } catch (_: Exception) {}
    }
}
