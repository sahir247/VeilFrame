package com.veilframe.app.upscale.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * Isolated ONNX Runtime wrapper for Real-ESRGAN super-resolution models.
 * Implements NCHW float [0, 1] input normalization and [0, 255] output denormalization,
 * conforming to ImageToolbox's AiProcessor tensor contract.
 */
class OnnxUpscaleRuntime(
    private val modelFile: File,
    private val scale: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "VeilFrame.OnnxRuntime"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputName: String

    init {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        session = env.createSession(modelFile.absolutePath, sessionOptions)
        inputName = session.inputNames.iterator().next()
        Log.i(TAG, "Initialized ONNX session for ${modelFile.name}, inputName: $inputName, scale: $scale")
    }

    suspend fun runTile(tileBitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()
        val width = tileBitmap.width
        val height = tileBitmap.height
        val pixelCount = width * height

        val srcPixels = IntArray(pixelCount)
        tileBitmap.getPixels(srcPixels, 0, width, 0, 0, width, height)

        val hasAlpha = tileBitmap.hasAlpha()
        val alphaChannel = if (hasAlpha) FloatArray(pixelCount) else null

        // NCHW format: 3 channels (R, G, B) normalized to [0.0f, 1.0f]
        val inputFloats = FloatArray(3 * pixelCount)
        val planeG = pixelCount
        val planeB = 2 * pixelCount

        for (i in 0 until pixelCount) {
            val pixel = srcPixels[i]
            if (hasAlpha) {
                alphaChannel!![i] = Color.alpha(pixel) / 255.0f
            }
            inputFloats[i] = Color.red(pixel) / 255.0f
            inputFloats[planeG + i] = Color.green(pixel) / 255.0f
            inputFloats[planeB + i] = Color.blue(pixel) / 255.0f
        }

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val floatBuffer = FloatBuffer.wrap(inputFloats)
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

        try {
            ensureActive()
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val outputValue = result[0].value
                val outWidth = width * scale
                val outHeight = height * scale
                val outPixelCount = outWidth * outHeight

                val outputFloats = when (outputValue) {
                    is Array<*> -> {
                        // Tensor shape [1, 3, outH, outW]
                        @Suppress("UNCHECKED_CAST")
                        val batch = outputValue[0] as Array<Array<FloatArray>>
                        val flat = FloatArray(3 * outPixelCount)
                        val outPlaneG = outPixelCount
                        val outPlaneB = 2 * outPixelCount
                        var idx = 0
                        for (y in 0 until outHeight) {
                            for (x in 0 until outWidth) {
                                flat[idx] = batch[0][y][x]
                                flat[outPlaneG + idx] = batch[1][y][x]
                                flat[outPlaneB + idx] = batch[2][y][x]
                                idx++
                            }
                        }
                        flat
                    }
                    else -> throw IllegalStateException("Unexpected ONNX output structure: ${outputValue?.javaClass}")
                }

                val dstPixels = IntArray(outPixelCount)
                val outPlaneG = outPixelCount
                val outPlaneB = 2 * outPixelCount

                for (idx in 0 until outPixelCount) {
                    val outY = idx / outWidth
                    val outX = idx % outWidth

                    val alphaVal = if (alphaChannel != null) {
                        val srcY = (outY / scale).coerceIn(0, height - 1)
                        val srcX = (outX / scale).coerceIn(0, width - 1)
                        (alphaChannel[srcY * width + srcX] * 255.0f).toInt().coerceIn(0, 255)
                    } else 255

                    val r = (outputFloats[idx] * 255.0f).toInt().coerceIn(0, 255)
                    val g = (outputFloats[outPlaneG + idx] * 255.0f).toInt().coerceIn(0, 255)
                    val b = (outputFloats[outPlaneB + idx] * 255.0f).toInt().coerceIn(0, 255)

                    dstPixels[idx] = Color.argb(alphaVal, r, g, b)
                }

                val outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                outBitmap.setPixels(dstPixels, 0, outWidth, 0, 0, outWidth, outHeight)
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
            env.close()
        } catch (_: Exception) {}
    }
}
