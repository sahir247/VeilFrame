package com.veilframe.app.cv.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.veilframe.app.cv.core.CvRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Continuous probability segmentation mask produced by the FairScan neural model.
 */
data class DocumentSegmentation(
    val probmap: FloatArray,
    override val width: Int = 256,
    override val height: Int = 256
) : com.veilframe.app.cv.document.Mask {
    fun get(x: Int, y: Int): Float = probmap[y * width + x]

    override fun toMat(): Mat = toProbMat()

    /**
     * Converts the continuous probability buffer to an OpenCV CV_32FC1 Mat.
     * Values are normalized in [0.0..1.0].
     */
    fun toProbMat(): Mat {
        if (!CvRuntime.isNativeAvailable) return Mat()
        val mat = Mat(height, width, CvType.CV_32FC1)
        mat.put(0, 0, probmap)
        return mat
    }

    /**
     * Converts to an 8-bit binary OpenCV CV_8UC1 Mat thresholded at [threshold].
     */
    fun toBinaryMat(threshold: Float = 0.5f): Mat {
        if (!CvRuntime.isNativeAvailable) return Mat()
        val mask = Mat(height, width, CvType.CV_8UC1)
        val data = ByteArray(width * height)
        for (i in probmap.indices) {
            data[i] = if (probmap[i] >= threshold) 255.toByte() else 0.toByte()
        }
        mask.put(0, 0, data)
        return mask
    }

    /**
     * Visual representation of the segmentation mask as an ARGB Bitmap.
     */
    fun toBinaryBitmap(threshold: Float = 0.5f): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in probmap.indices) {
            val v = if (probmap[i] >= threshold) 255 else 0
            pixels[i] = Color.rgb(v, v, v)
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
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

/**
 * Segmentation inference result including inference duration in milliseconds.
 */
data class SegmentationResult(
    val segmentation: DocumentSegmentation,
    val inferenceTimeMs: Long
)

/**
 * DocumentSegmentationService — neural document boundary segmentation engine
 * using the official FairScan MobileNetV2 segmentation model (`fairscan-segmentation-model.tflite`).
 *
 * Input: 256x256 RGB image normalized to [-1.0..1.0] via `(x / 127.5) - 1.0`.
 * Output: 256x256 Float32 probability map in [0.0..1.0].
 */
class DocumentSegmentationService(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "DocSegmentationService"
        const val MODEL_ASSET_NAME = "fairscan-segmentation-model.tflite"
        const val INPUT_WIDTH = 256
        const val INPUT_HEIGHT = 256

        @Volatile
        private var instance: DocumentSegmentationService? = null

        fun getInstance(context: Context): DocumentSegmentationService {
            return instance ?: synchronized(this) {
                instance ?: DocumentSegmentationService(context.applicationContext).also {
                    it.initialize()
                    instance = it
                }
            }
        }
    }

    private var interpreter: Interpreter? = null
    private val inferenceLock = Mutex()

    var isModelAvailable: Boolean = false
        private set

    /**
     * Initializes the TFLite interpreter from Android assets or explicit file.
     * Fails gracefully to classical CV if native libraries are unavailable.
     */
    fun initialize(modelFile: File? = null): Boolean {
        return try {
            val byteBuffer = when {
                modelFile != null && modelFile.exists() -> loadMappedFile(modelFile)
                context != null -> loadMappedAsset(context, MODEL_ASSET_NAME)
                else -> null
            }

            if (byteBuffer != null) {
                val options = Interpreter.Options().apply {
                    numThreads = 2
                }
                interpreter = Interpreter(byteBuffer, options)
                isModelAvailable = true
                Log.i(TAG, "Loaded FairScan document segmentation model successfully")
                true
            } else {
                isModelAvailable = false
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TFLite segmentation model initialization skipped: ${e.message}")
            interpreter = null
            isModelAvailable = false
            false
        }
    }

    /**
     * Executes neural segmentation on an Android [Bitmap].
     */
    suspend fun runSegmentation(bitmap: Bitmap): SegmentationResult? {
        val interp = interpreter ?: return null
        return inferenceLock.withLock {
            try {
                val startTime = SystemClock.uptimeMillis()

                // Resize bitmap to model dimensions
                val scaledBitmap = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
                }

                val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
                scaledBitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                // Preprocess into NCHW/NHWC float buffer: (x / 127.5) - 1.0
                val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * 3).apply {
                    order(ByteOrder.nativeOrder())
                    rewind()
                }
                for (pixel in pixels) {
                    val r = ((pixel shr 16 and 0xFF) / 127.5f) - 1.0f
                    val g = ((pixel shr 8 and 0xFF) / 127.5f) - 1.0f
                    val b = ((pixel and 0xFF) / 127.5f) - 1.0f
                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }
                inputBuffer.rewind()

                val outputBuffer = ByteBuffer.allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT).apply {
                    order(ByteOrder.nativeOrder())
                    rewind()
                }

                interp.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                val probmap = FloatArray(INPUT_WIDTH * INPUT_HEIGHT)
                outputBuffer.asFloatBuffer()[probmap]
                for (i in probmap.indices) {
                    probmap[i] = probmap[i].coerceIn(0f, 1f)
                }

                val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
                SegmentationResult(
                    segmentation = DocumentSegmentation(probmap, INPUT_WIDTH, INPUT_HEIGHT),
                    inferenceTimeMs = inferenceTimeMs
                )
            } catch (e: Exception) {
                Log.e(TAG, "Segmentation inference failed on Bitmap", e)
                null
            }
        }
    }

    /**
     * Executes neural segmentation directly on an OpenCV [Mat] (BGR, RGBA, or Grayscale).
     * Avoids intermediate Android Bitmap creation for camera analysis frames.
     */
    suspend fun runSegmentation(mat: Mat): SegmentationResult? {
        val interp = interpreter ?: return null
        if (!CvRuntime.isNativeAvailable || mat.nativeObj == 0L || mat.empty()) return null

        return inferenceLock.withLock {
            try {
                val startTime = SystemClock.uptimeMillis()

                val resized = Mat()
                Imgproc.resize(mat, resized, Size(INPUT_WIDTH.toDouble(), INPUT_HEIGHT.toDouble()))

                val rgb = Mat()
                when (resized.channels()) {
                    4 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB)
                    1 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_GRAY2RGB)
                    3 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
                    else -> resized.copyTo(rgb)
                }
                resized.release()

                // Normalize: (x / 127.5) - 1.0
                val rgbF = Mat()
                rgb.convertTo(rgbF, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
                rgb.release()

                val inputFloats = FloatArray(INPUT_WIDTH * INPUT_HEIGHT * 3)
                rgbF.get(0, 0, inputFloats)
                rgbF.release()

                val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * 3).apply {
                    order(ByteOrder.nativeOrder())
                    asFloatBuffer().put(inputFloats)
                    rewind()
                }

                val outputBuffer = ByteBuffer.allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT).apply {
                    order(ByteOrder.nativeOrder())
                    rewind()
                }

                interp.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                val probmap = FloatArray(INPUT_WIDTH * INPUT_HEIGHT)
                outputBuffer.asFloatBuffer()[probmap]
                for (i in probmap.indices) {
                    probmap[i] = probmap[i].coerceIn(0f, 1f)
                }

                val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
                SegmentationResult(
                    segmentation = DocumentSegmentation(probmap, INPUT_WIDTH, INPUT_HEIGHT),
                    inferenceTimeMs = inferenceTimeMs
                )
            } catch (e: Exception) {
                Log.e(TAG, "Segmentation inference failed on Mat", e)
                null
            }
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isModelAvailable = false
    }

    private fun loadMappedAsset(context: Context, assetName: String): ByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(assetName)
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetName not found or unreadable: ${e.message}")
            null
        }
    }

    private fun loadMappedFile(file: File): ByteBuffer? {
        return try {
            FileInputStream(file).use { inputStream ->
                inputStream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        } catch (e: Exception) {
            Log.w(TAG, "File ${file.absolutePath} unreadable: ${e.message}")
            null
        }
    }
}
