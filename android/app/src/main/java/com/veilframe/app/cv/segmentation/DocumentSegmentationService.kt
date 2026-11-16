package com.veilframe.app.cv.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.core.LiteRtEnvironment
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Continuous probability segmentation mask produced by the FairScan neural model. */
data class DocumentSegmentation(
    val probmap: FloatArray,
    override val width: Int = 256,
    override val height: Int = 256
) : com.veilframe.app.cv.document.Mask {
    fun get(x: Int, y: Int): Float = probmap[y * width + x]
    override fun toMat(): Mat = toProbMat()

    fun toProbMat(): Mat {
        if (!CvRuntime.isNativeAvailable) return Mat()
        return Mat(height, width, CvType.CV_32FC1).also { it.put(0, 0, probmap) }
    }

    fun toBinaryMat(threshold: Float = 0.5f): Mat {
        if (!CvRuntime.isNativeAvailable) return Mat()
        val data = ByteArray(width * height) { i ->
            if (probmap[i] >= threshold) 255.toByte() else 0.toByte()
        }
        return Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, data) }
    }

    fun toBinaryBitmap(threshold: Float = 0.5f): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in probmap.indices) {
            val value = if (probmap[i] >= threshold) 255 else 0
            pixels[i] = Color.rgb(value, value, value)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DocumentSegmentation
        return width == other.width && height == other.height && probmap.contentEquals(other.probmap)
    }

    override fun hashCode(): Int {
        var result = probmap.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

data class SegmentationResult(
    val segmentation: DocumentSegmentation,
    val inferenceTimeMs: Long
)

/**
 * FairScan MobileNetV2 inference using LiteRT CompiledModel.
 *
 * Model creation, warm-up, inference and close are confined to one background thread.
 * The suspend APIs dispatch internally so callers never accidentally infer on the UI thread.
 */
class DocumentSegmentationService(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "DocSegmentationService"
        const val MODEL_ASSET_NAME = "fairscan-segmentation-model.tflite"
        const val INPUT_WIDTH = 256
        const val INPUT_HEIGHT = 256
        private const val INPUT_FLOAT_COUNT = INPUT_WIDTH * INPUT_HEIGHT * 3
        private const val OUTPUT_FLOAT_COUNT = INPUT_WIDTH * INPUT_HEIGHT

        @Volatile
        private var instance: DocumentSegmentationService? = null

        fun getInstance(context: Context): DocumentSegmentationService {
            return instance ?: synchronized(this) {
                instance ?: DocumentSegmentationService(context.applicationContext).also {
                    instance = it
                    // Compile and warm the model off-main while the scanner is being prepared.
                    it.prepareAsync()
                }
            }
        }
    }

    private val inferenceThread = AtomicReference<Thread?>()
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vf-fairscan-litert").apply {
            isDaemon = true
            inferenceThread.set(this)
        }
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    private var compiledModel: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    private val inputPixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
    private val inputFloats = FloatArray(INPUT_FLOAT_COUNT)

    @Volatile
    var isModelAvailable: Boolean = false
        private set

    @Volatile
    var isGpuAccelerated: Boolean = false
        private set

    @Volatile
    var lastInferenceTimeMs: Long = 0L
        private set

    private fun prepareAsync() {
        inferenceExecutor.execute { initializeOnInferenceThread() }
    }

    /** Synchronous compatibility entry point; all model work still runs on the model thread. */
    fun initialize(modelFile: File? = null): Boolean =
        runOnInferenceThread { initializeOnInferenceThread(modelFile) }

    private fun initializeOnInferenceThread(modelFile: File? = null): Boolean {
        if (compiledModel != null) return true
        val appContext = context?.applicationContext
        if (modelFile == null && appContext == null) {
            isModelAvailable = false
            return false
        }

        val environment = try {
            LiteRtEnvironment.get(appContext)
        } catch (error: Throwable) {
            Log.w(TAG, "LiteRT environment initialization failed", error)
            isModelAvailable = false
            return false
        }
        val gpuAvailable = runCatching {
            environment.getAvailableAccelerators().contains(Accelerator.GPU)
        }.getOrDefault(false)
        val cpuThreads = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() / 2))

        fun create(options: CompiledModel.Options): CompiledModel =
            if (modelFile != null && modelFile.isFile) {
                CompiledModel.create(modelFile.absolutePath, options, environment)
            } else {
                CompiledModel.create(
                    checkNotNull(appContext).assets,
                    MODEL_ASSET_NAME,
                    options,
                    environment
                )
            }

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
                    "FairScan model must expose at least one input and one output tensor"
                }
                inputFloats.fill(0f)
                inputs[0].writeFloat(inputFloats)
                model.run(inputs, outputs)
                outputs[0].readFloat() // Synchronize GPU work and compile first-run kernels.
                return ReadyModel(model, inputs, outputs, gpu)
            } catch (error: Throwable) {
                outputs.forEach { runCatching { it.close() } }
                inputs.forEach { runCatching { it.close() } }
                runCatching { model?.close() }
                throw error
            }
        }

        return try {
            val ready = if (gpuAvailable) {
                try {
                    val gpuOptions = CompiledModel.Options(Accelerator.GPU, Accelerator.CPU).apply {
                        cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads, xnnPackFlags = 0x23)
                    }
                    createAndWarm(gpuOptions, gpu = true)
                } catch (gpuError: Throwable) {
                    Log.w(TAG, "LiteRT GPU model setup failed; using CPU", gpuError)
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
            isModelAvailable = true
            Log.i(
                TAG,
                "FairScan CompiledModel ready (backend=\${if (isGpuAccelerated) "GPU with CPU fallback" else "CPU"}, threads=$cpuThreads)"
            )
            true
        } catch (error: Throwable) {
            compiledModel = null
            inputBuffers = emptyList()
            outputBuffers = emptyList()
            isGpuAccelerated = false
            isModelAvailable = false
            Log.w(TAG, "LiteRT segmentation model initialization skipped: \${error.message}", error)
            false
        }
    }

    /** Runs on the dedicated model dispatcher, never on the caller/UI thread. */
    suspend fun runSegmentation(bitmap: Bitmap): SegmentationResult? = withContext(inferenceDispatcher) {
        currentCoroutineContext().ensureActive()
        if (!isModelAvailable && !initializeOnInferenceThread()) return@withContext null
        val result = runBitmapOnInferenceThread(bitmap)
        currentCoroutineContext().ensureActive()
        result
    }

    /** Synchronous compatibility API for existing non-suspending CV callers. */
    fun runSegmentationDirect(bitmap: Bitmap): SegmentationResult? =
        runOnInferenceThread {
            if (!isModelAvailable && !initializeOnInferenceThread()) null
            else runBitmapOnInferenceThread(bitmap)
        }

    private fun runBitmapOnInferenceThread(bitmap: Bitmap): SegmentationResult? {
        val model = compiledModel ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val scaled = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
            }
            try {
                scaled.getPixels(inputPixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }

            for (i in inputPixels.indices) {
                val pixel = inputPixels[i]
                val offset = i * 3
                inputFloats[offset] = ((pixel shr 16 and 0xFF) / 127.5f) - 1.0f
                inputFloats[offset + 1] = ((pixel shr 8 and 0xFF) / 127.5f) - 1.0f
                inputFloats[offset + 2] = ((pixel and 0xFF) / 127.5f) - 1.0f
            }
            return runInference(inputFloats, model, startedAt)
        } catch (error: Exception) {
            Log.e(TAG, "Segmentation inference failed on Bitmap", error)
            return null
        }
    }

    /** Executes segmentation directly on an OpenCV Mat (BGR, RGBA, or grayscale). */
    fun runSegmentationDirect(mat: Mat): SegmentationResult? =
        runOnInferenceThread {
            if (!isModelAvailable && !initializeOnInferenceThread()) return@runOnInferenceThread null
            runMatOnInferenceThread(mat)
        }

    suspend fun runSegmentation(mat: Mat): SegmentationResult? = withContext(inferenceDispatcher) {
        currentCoroutineContext().ensureActive()
        if (!isModelAvailable && !initializeOnInferenceThread()) return@withContext null
        val result = runMatOnInferenceThread(mat)
        currentCoroutineContext().ensureActive()
        result
    }

    private fun runMatOnInferenceThread(mat: Mat): SegmentationResult? {
        val model = compiledModel ?: return null
        if (!CvRuntime.isNativeAvailable || mat.nativeObj == 0L || mat.empty()) return null
        val startedAt = SystemClock.elapsedRealtime()
        val resized = Mat()
        val rgb = Mat()
        val rgbFloat = Mat()
        try {
            Imgproc.resize(mat, resized, Size(INPUT_WIDTH.toDouble(), INPUT_HEIGHT.toDouble()))
            when (resized.channels()) {
                4 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB)
                1 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_GRAY2RGB)
                3 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
                else -> resized.copyTo(rgb)
            }
            rgb.convertTo(rgbFloat, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
            rgbFloat.get(0, 0, inputFloats)
            return runInference(inputFloats, model, startedAt)
        } catch (error: Exception) {
            Log.e(TAG, "Segmentation inference failed on Mat", error)
            return null
        } finally {
            resized.release()
            rgb.release()
            rgbFloat.release()
        }
    }

    private fun runInference(input: FloatArray, model: CompiledModel, startedAt: Long): SegmentationResult? {
        return try {
            inputBuffers[0].writeFloat(input)
            model.run(inputBuffers, outputBuffers)
            val probabilities = outputBuffers[0].readFloat()
            check(probabilities.size == OUTPUT_FLOAT_COUNT) {
                "Unexpected FairScan output size: \${probabilities.size}"
            }
            for (i in probabilities.indices) probabilities[i] = probabilities[i].coerceIn(0f, 1f)

            val duration = SystemClock.elapsedRealtime() - startedAt
            lastInferenceTimeMs = duration
            Log.i(
                TAG,
                "FairScan segmentation completed in $duration ms (Backend: \${if (isGpuAccelerated) "LiteRT GPU+CPU" else "LiteRT CPU"})"
            )
            SegmentationResult(
                DocumentSegmentation(probabilities, INPUT_WIDTH, INPUT_HEIGHT),
                duration
            )
        } catch (error: Exception) {
            Log.e(TAG, "Segmentation inference failed", error)
            null
        }
    }

    fun close() {
        if (Thread.currentThread() === inferenceThread.get()) {
            closeOnInferenceThread()
        } else {
            inferenceExecutor.execute { closeOnInferenceThread() }
        }
    }

    private fun closeOnInferenceThread() {
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers = emptyList()
        inputBuffers = emptyList()
        runCatching { compiledModel?.close() }
        compiledModel = null
        isGpuAccelerated = false
        isModelAvailable = false
    }

    private fun <T> runOnInferenceThread(block: () -> T): T {
        if (Thread.currentThread() === inferenceThread.get()) return block()
        return try {
            inferenceExecutor.submit(Callable { block() }).get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }
}
