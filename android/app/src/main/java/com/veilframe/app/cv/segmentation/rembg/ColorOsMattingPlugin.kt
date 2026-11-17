package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.core.LiteRtEnvironment
import com.veilframe.app.cv.core.LiteRtModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ColorOS Lift & Shift Neural Plugin for VeilFrame.
 *
 * Inference uses the bundled LiteRT CompiledModel API, reuses tensor buffers, and keeps all
 * model creation/inference/close operations on one background thread for GPU driver affinity.
 */
object ColorOsMattingPlugin {
    private const val TAG = "ColorOsMattingPlugin"
    private const val MODEL_ASSET_PATH = "models/lift_shift/image_matting_mobile.tflite"
    private const val TENSOR_WIDTH = 640
    private const val TENSOR_HEIGHT = 640
    private const val INPUT_FLOAT_COUNT = TENSOR_WIDTH * TENSOR_HEIGHT * 3
    private const val OUTPUT_FLOAT_COUNT = TENSOR_WIDTH * TENSOR_HEIGHT

    @Volatile
    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()

    // Reused only on mattingDispatcher, so concurrent process() calls cannot race these buffers.
    private val inputPixels = IntArray(TENSOR_WIDTH * TENSOR_HEIGHT)
    private val inputValues = FloatArray(INPUT_FLOAT_COUNT)

    @Volatile
    var isGpuAccelerated: Boolean = false
        private set

    @Volatile
    var lastInferenceTimeMs: Long = 0L
        private set

    private val modelLock = ReentrantLock()
    private val mattingExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vf-liftshift-litert").apply { isDaemon = true }
    }
    private val mattingDispatcher = mattingExecutor.asCoroutineDispatcher()

    fun isModelReady(context: Context): Boolean {
        val appContext = context.applicationContext
        val candidates = listOf(
            File(appContext.filesDir, "models/rembg/image_matting_mobile.tflite"),
            File(appContext.filesDir, "models/lift_shift/image_matting_mobile.tflite")
        )
        if (candidates.any { it.isFile && it.length() >= 8_000_000L }) return true
        return try {
            appContext.assets.openFd(MODEL_ASSET_PATH).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrInitCompiledModelLocked(context: Context): CompiledModel {
        compiledModel?.let { return it }
        val appContext = context.applicationContext
        val candidates = listOf(
            File(appContext.filesDir, "models/rembg/image_matting_mobile.tflite"),
            File(appContext.filesDir, "models/lift_shift/image_matting_mobile.tflite")
        )
        val modelFile = candidates.firstOrNull { it.isFile && it.length() >= 8_000_000L }
        val environment = LiteRtEnvironment.get(appContext)
        val gpuAvailable = runCatching {
            environment.getAvailableAccelerators().contains(Accelerator.GPU)
        }.getOrDefault(false)
        val cpuThreads = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() / 2))

        // Downloaded models already have file paths; stage only the bundled fallback.
        val modelPath = modelFile?.absolutePath ?: LiteRtModelStorage.stageBundled(
            appContext, MODEL_ASSET_PATH, "coloros-liftshift"
        ).absolutePath
        fun create(options: CompiledModel.Options): CompiledModel =
            CompiledModel.create(modelPath, options, environment)

        data class ReadyModel(
            val model: CompiledModel,
            val inputs: List<TensorBuffer>,
            val outputs: List<TensorBuffer>,
            val gpu: Boolean
        )

        fun createAndWarm(options: CompiledModel.Options, gpu: Boolean): ReadyModel {
            var model: CompiledModel? = null
            var inputs: List<TensorBuffer> = emptyList()
            var outputs: List<TensorBuffer> = emptyList()
            try {
                model = create(options)
                inputs = model.createInputBuffers()
                outputs = model.createOutputBuffers()
                check(inputs.isNotEmpty() && outputs.isNotEmpty()) {
                    "ColorOS matting model must expose input and output tensors"
                }
                inputValues.fill(0f)
                inputs[0].writeFloat(inputValues)
                model.run(inputs, outputs)
                outputs[0].readFloat() // Wait for GPU completion and pay shader compilation up front.
                return ReadyModel(model, inputs, outputs, gpu)
            } catch (error: Throwable) {
                outputs.forEach { runCatching { it.close() } }
                inputs.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
                throw error
            }
        }

        val ready = if (gpuAvailable) {
            try {
                val gpuOptions = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads, xnnPackFlags = 0x23)
                }
                createAndWarm(gpuOptions, gpu = true)
            } catch (gpuError: Throwable) {
                Log.w(TAG, "LiteRT GPU setup failed; falling back to CPU", gpuError)
                val cpuOptions = CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads, xnnPackFlags = 0x23)
                }
                createAndWarm(cpuOptions, gpu = false)
            }
        } else {
            val cpuOptions = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads, xnnPackFlags = 0x23)
            }
            createAndWarm(cpuOptions, gpu = false)
        }

        compiledModel = ready.model
        inputBuffers = ready.inputs
        outputBuffers = ready.outputs
        isGpuAccelerated = ready.gpu
        Log.i(
            TAG,
            "ColorOS Lift & Shift CompiledModel ready (backend=${if (isGpuAccelerated) "GPU with CPU fallback" else "CPU"}, threads=$cpuThreads)"
        )
        return ready.model
    }

    private fun closeLocked() {
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers = emptyList()
        inputBuffers = emptyList()
        runCatching { compiledModel?.close() }
        compiledModel = null
        isGpuAccelerated = false
    }

    fun close() {
        mattingExecutor.execute {
            modelLock.withLock { closeLocked() }
        }
    }

    suspend fun process(
        context: Context,
        imageBytes: ByteArray,
        trim: Boolean = false,
        onProgress: suspend (RembgProgress) -> Unit = {}
    ): RembgResult = withContext(Dispatchers.Default) {
        ensureActive()
        CvRuntime.requireAvailable()
        val sourceInfo = analyzeSource(imageBytes)
        val warnings = sourceInfo.warnings.toMutableList()

        onProgress(RembgProgress(15, "Initializing Neural Matting Engine…"))
        ensureActive()
        onProgress(RembgProgress(30, "Decoding image…"))
        ensureActive()

        val sourceBitmap = RembgImageUtils.decodeOrientedBitmap(
            imageBytes,
            maxDimension = RembgImageUtils.MAX_MATTING_DIMENSION,
            inMutable = true
        )
        var cutoutBitmap: Bitmap? = null
        try {
            val srcW = sourceBitmap.width
            val srcH = sourceBitmap.height

            onProgress(RembgProgress(50, "Running Neural Subject Matting…"))
            ensureActive()

            // Reuse one input tensor and its backing buffers; model work stays on mattingDispatcher.
            val alphaValues = withContext(mattingDispatcher) {
                ensureActive()
                modelLock.withLock {
                    val model = getOrInitCompiledModelLocked(context)
                    val scaledBitmap = Bitmap.createScaledBitmap(
                        sourceBitmap, TENSOR_WIDTH, TENSOR_HEIGHT, true
                    )
                    try {
                        scaledBitmap.getPixels(
                            inputPixels, 0, TENSOR_WIDTH, 0, 0, TENSOR_WIDTH, TENSOR_HEIGHT
                        )
                    } finally {
                        if (scaledBitmap !== sourceBitmap && !scaledBitmap.isRecycled) {
                            scaledBitmap.recycle()
                        }
                    }

                    for (i in inputPixels.indices) {
                        val pixel = inputPixels[i]
                        val offset = i * 3
                        inputValues[offset] = ((pixel shr 16) and 0xFF) / 255.0f
                        inputValues[offset + 1] = ((pixel shr 8) and 0xFF) / 255.0f
                        inputValues[offset + 2] = (pixel and 0xFF) / 255.0f
                    }

                    inputBuffers[0].writeFloat(inputValues)
                    val startedAt = SystemClock.elapsedRealtime()
                    model.run(inputBuffers, outputBuffers)
                    val result = outputBuffers[0].readFloat()
                    check(result.size == OUTPUT_FLOAT_COUNT) {
                        "Unexpected ColorOS matting output size: ${result.size}"
                    }
                    val latency = SystemClock.elapsedRealtime() - startedAt
                    lastInferenceTimeMs = latency
                    Log.i(
                        TAG,
                        "ColorOS neural matting completed in $latency ms (backend=${if (isGpuAccelerated) "LiteRT GPU+CPU" else "LiteRT CPU"})"
                    )
                    result
                }
            }

            onProgress(RembgProgress(75, "Extracting connected components…"))
            ensureActive()

            // Preserve the continuous neural alpha while filtering isolated background components.
            val rawAlphaBytes = ByteArray(OUTPUT_FLOAT_COUNT)
            val binaryBytes = ByteArray(OUTPUT_FLOAT_COUNT)
            val threshold = 5.0f / 255.0f
            for (i in 0 until OUTPUT_FLOAT_COUNT) {
                val alpha = alphaValues[i].coerceIn(0f, 1f)
                rawAlphaBytes[i] = (alpha * 255.0f + 0.5f).toInt().coerceIn(0, 255).toByte()
                binaryBytes[i] = if (alpha >= threshold) 255.toByte() else 0.toByte()
            }

            val fullMaskBytes = ByteArray(srcW * srcH)
            val rawMaskMat = Mat(TENSOR_HEIGHT, TENSOR_WIDTH, CvType.CV_8UC1)
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val cleanMaskMat = Mat.zeros(TENSOR_HEIGHT, TENSOR_WIDTH, CvType.CV_8UC1)
            val fullMaskMat = Mat()
            try {
                rawMaskMat.put(0, 0, binaryBytes)
                val numComponents = Imgproc.connectedComponentsWithStats(rawMaskMat, labels, stats, centroids)
                val centerX = TENSOR_WIDTH / 2
                val centerY = TENSOR_HEIGHT / 2
                var bestLabel = -1
                var bestScore = -1.0

                for (i in 1 until numComponents) {
                    val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                    val width = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
                    val height = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
                    if (width < 15 || height < 15 || area < 100) continue

                    val cx = centroids.get(i, 0)[0]
                    val cy = centroids.get(i, 1)[0]
                    val distance = Math.hypot(cx - centerX, cy - centerY)
                    val score = area / (1.0 + distance * 0.005)
                    if (score > bestScore) {
                        bestScore = score
                        bestLabel = i
                    }
                }

                if (bestLabel != -1) {
                    val labelData = IntArray(OUTPUT_FLOAT_COUNT)
                    labels.get(0, 0, labelData)
                    val cleanData = ByteArray(OUTPUT_FLOAT_COUNT)
                    for (index in labelData.indices) {
                        if (labelData[index] == bestLabel) cleanData[index] = rawAlphaBytes[index]
                    }
                    cleanMaskMat.put(0, 0, cleanData)
                } else {
                    cleanMaskMat.put(0, 0, rawAlphaBytes)
                }

                onProgress(RembgProgress(90, "Compositing 4K Cutout…"))
                ensureActive()
                Imgproc.resize(
                    cleanMaskMat, fullMaskMat, Size(srcW.toDouble(), srcH.toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR
                )
                fullMaskMat.get(0, 0, fullMaskBytes)
            } finally {
                rawMaskMat.release()
                labels.release()
                stats.release()
                centroids.release()
                cleanMaskMat.release()
                fullMaskMat.release()
            }

            // Full-resolution alpha composite; keep source RGB unchanged.
            cutoutBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            val sourceRow = IntArray(srcW)
            val outputRow = IntArray(srcW)
            for (y in 0 until srcH) {
                sourceBitmap.getPixels(sourceRow, 0, srcW, 0, y, srcW, 1)
                val rowOffset = y * srcW
                for (x in 0 until srcW) {
                    val alpha = fullMaskBytes[rowOffset + x].toInt() and 0xFF
                    outputRow[x] = (alpha shl 24) or (sourceRow[x] and 0x00FFFFFF)
                }
                cutoutBitmap.setPixels(outputRow, 0, srcW, 0, y, srcW, 1)
            }

            val finalCutout = if (trim) {
                val trimmed = MaskPostprocessor.trimTransparent(cutoutBitmap)
                if (trimmed !== cutoutBitmap && !cutoutBitmap.isRecycled) cutoutBitmap.recycle()
                trimmed
            } else {
                cutoutBitmap
            }
            cutoutBitmap = null
            onProgress(RembgProgress(100, "Done"))
            RembgResult(finalCutout, sourceInfo, RembgModel.COLOROS_LIFT_SHIFT, warnings)
        } finally {
            if (cutoutBitmap != null && !cutoutBitmap.isRecycled) cutoutBitmap.recycle()
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }

    internal fun detectMimeFormat(imageBytes: ByteArray): String {
        if (imageBytes.size >= 4) {
            if (imageBytes[0] == 0x89.toByte() && imageBytes[1] == 0x50.toByte() &&
                imageBytes[2] == 0x4E.toByte() && imageBytes[3] == 0x47.toByte()
            ) return "image/png"
            if (imageBytes[0] == 0x47.toByte() && imageBytes[1] == 0x49.toByte() &&
                imageBytes[2] == 0x46.toByte()
            ) return "image/gif"
            if (imageBytes.size >= 12 &&
                imageBytes[0] == 'R'.code.toByte() && imageBytes[1] == 'I'.code.toByte() &&
                imageBytes[2] == 'F'.code.toByte() && imageBytes[3] == 'F'.code.toByte() &&
                imageBytes[8] == 'W'.code.toByte() && imageBytes[9] == 'E'.code.toByte()
            ) return "image/webp"
        }
        return "image/jpeg"
    }

    private fun analyzeSource(imageBytes: ByteArray): RembgSourceInfo {
        val (width, height) = RembgImageUtils.orientedDimensions(imageBytes)
        return RembgSourceInfo(
            width = width,
            height = height,
            format = detectMimeFormat(imageBytes),
            fileSize = imageBytes.size.toLong(),
            warnings = emptyList()
        )
    }
}
