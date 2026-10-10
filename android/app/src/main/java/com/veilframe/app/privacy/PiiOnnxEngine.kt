package com.veilframe.app.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.veilframe.app.cv.core.CvRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer

/**
 * PiiOnnxEngine — High-performance YOLOv8-ONNX inference engine for automated PII detection.
 * Executes on Android via ONNX Runtime (ORT) with NNAPI/XNNPACK/CPU acceleration.
 *
 * Implements Layer C (Isolated Solid Redaction) mask generation and QualityGate
 * zero-residual-probe verification for the VeilFrame Image Privacy Compiler.
 */
class PiiOnnxEngine(
    private val ortSession: OrtSession,
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
) : AutoCloseable {

    companion object {
        const val MODEL_INPUT_SIZE = 640
        const val DEFAULT_INPUT_NAME = "images"
        const val DEFAULT_ASSET_NAME = "pii_yolov8n.onnx"

        /**
         * Mathematical IoU (Intersection-over-Union) calculation between two bounding boxes.
         * Pure evaluator for testing and Non-Maximum Suppression.
         */
        fun calculateIoU(a: RectF, b: RectF): Float {
            val interLeft = Math.max(a.left, b.left)
            val interTop = Math.max(a.top, b.top)
            val interRight = Math.min(a.right, b.right)
            val interBottom = Math.min(a.bottom, b.bottom)
            val interW = Math.max(0f, interRight - interLeft)
            val interH = Math.max(0f, interBottom - interTop)
            val interArea = interW * interH
            val aArea = Math.max(0f, a.right - a.left) * Math.max(0f, a.bottom - a.top)
            val bArea = Math.max(0f, b.right - b.left) * Math.max(0f, b.bottom - b.top)
            val unionArea = aArea + bArea - interArea
            return if (unionArea > 0f) interArea / unionArea else 0f
        }

        /**
         * Pure Non-Maximum Suppression (NMS) to eliminate redundant candidate bounding boxes.
         */
        fun applyNMS(
            detections: List<PiiDetection>,
            iouThreshold: Float = 0.45f
        ): List<PiiDetection> {
            val sorted = detections.sortedByDescending { it.score }.toMutableList()
            val keep = mutableListOf<PiiDetection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                keep.add(best)

                // Discard overlapping bounding boxes of the same class using safe iterator deletion
                val iterator = sorted.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (other.piiClass == best.piiClass && calculateIoU(best.rect, other.rect) > iouThreshold) {
                        iterator.remove()
                    }
                }
            }
            return keep
        }

        /**
         * Decodes raw YOLOv8 output tensor (Shape: [4 + numClasses, numAnchors]).
         * Scales coordinates to [origW] x [origH] and filters by confidence and NMS.
         */
        fun postprocess(
            output: Array<FloatArray>,
            origW: Int,
            origH: Int,
            conf: Float = 0.40f,
            iouThreshold: Float = 0.45f
        ): List<PiiDetection> {
            if (output.isEmpty() || output[0].isEmpty()) return emptyList()

            val totalRows = output.size
            val numAnchors = output[0].size
            val numClasses = totalRows - 4
            if (numClasses <= 0) return emptyList()

            val candidates = mutableListOf<PiiDetection>()
            val scaleX = origW.toFloat() / MODEL_INPUT_SIZE
            val scaleY = origH.toFloat() / MODEL_INPUT_SIZE

            for (n in 0 until numAnchors) {
                var maxScore = -1f
                var bestClassIdx = -1

                for (c in 0 until numClasses) {
                    val score = output[4 + c][n]
                    if (score > maxScore) {
                        maxScore = score
                        bestClassIdx = c
                    }
                }

                if (maxScore > conf && bestClassIdx >= 0) {
                    val cx = output[0][n]
                    val cy = output[1][n]
                    val w = output[2][n]
                    val h = output[3][n]

                    val left = ((cx - w / 2f) * scaleX).coerceIn(0f, origW.toFloat())
                    val top = ((cy - h / 2f) * scaleY).coerceIn(0f, origH.toFloat())
                    val right = ((cx + w / 2f) * scaleX).coerceIn(0f, origW.toFloat())
                    val bottom = ((cy + h / 2f) * scaleY).coerceIn(0f, origH.toFloat())

                    if (right > left && bottom > top) {
                        val piiClass = mapClassIndex(bestClassIdx, numClasses)
                        if (piiClass != null) {
                            candidates.add(
                                PiiDetection(
                                    rect = RectF(left, top, right, bottom),
                                    piiClass = piiClass,
                                    score = maxScore
                                )
                            )
                        }
                    }
                }
            }

            return applyNMS(candidates, iouThreshold)
        }

        /**
         * Maps class index to [PiiClass].
         * Seamlessly handles dedicated 5-class PII models and COCO 80-class models.
         */
        fun mapClassIndex(classIdx: Int, numClasses: Int): PiiClass? {
            return if (numClasses <= 5) {
                when (classIdx) {
                    0 -> PiiClass.FACE
                    1 -> PiiClass.PLATE
                    2 -> PiiClass.SIGNATURE
                    3 -> PiiClass.CARD
                    4 -> PiiClass.BARCODE
                    else -> PiiClass.FACE
                }
            } else {
                when (classIdx) {
                    0 -> PiiClass.FACE // COCO person
                    2, 3, 5, 7 -> PiiClass.PLATE // car, motorcycle, bus, truck
                    67 -> PiiClass.CARD // cell phone
                    73 -> PiiClass.CARD // book/document
                    else -> null // ignore non-PII classes
                }
            }
        }

        /**
         * Scales detection coordinates from source dimensions (srcW, srcH)
         * to destination dimensions (dstW, dstH).
         */
        fun scaleDetections(
            detections: List<PiiDetection>,
            srcW: Int,
            srcH: Int,
            dstW: Int,
            dstH: Int
        ): List<PiiDetection> {
            if (detections.isEmpty() || (srcW == dstW && srcH == dstH)) return detections
            val scaleX = dstW.toFloat() / srcW.toFloat().coerceAtLeast(1f)
            val scaleY = dstH.toFloat() / srcH.toFloat().coerceAtLeast(1f)

            return detections.map { det ->
                det.copy(
                    rect = RectF(
                        det.rect.left * scaleX,
                        det.rect.top * scaleY,
                        det.rect.right * scaleX,
                        det.rect.bottom * scaleY
                    )
                )
            }
        }

        /**
         * Applies Layer C (Isolated Solid Redaction) masks onto a [Bitmap].
         * Pure constant fill guarantees 100% destructive removal of original pixel data.
         */
        fun applySolidRedaction(
            source: Bitmap,
            detections: List<PiiDetection>,
            fillColor: Int = Color.BLACK
        ): Bitmap {
            val mutable = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
            val width = mutable.width
            val height = mutable.height

            // Direct pixel overwrite guarantees 100% destructive removal of original pixel data
            // across both Android runtime and host JVM Robolectric environments
            for (det in detections) {
                val left = Math.round(det.rect.left).coerceIn(0, width)
                val top = Math.round(det.rect.top).coerceIn(0, height)
                val right = Math.round(det.rect.right).coerceIn(0, width)
                val bottom = Math.round(det.rect.bottom).coerceIn(0, height)
                val blockW = right - left
                val blockH = bottom - top

                if (blockW > 0 && blockH > 0) {
                    val solidPixels = IntArray(blockW * blockH) { fillColor }
                    mutable.setPixels(solidPixels, 0, blockW, left, top, blockW, blockH)
                }
            }
            return mutable
        }

        /**
         * Applies Layer C (Isolated Solid Redaction) masks onto an OpenCV [Mat].
         * Safely gated through [CvRuntime.isNativeAvailable].
         */
        fun applySolidRedaction(
            source: Mat,
            detections: List<PiiDetection>,
            fillColor: Scalar = Scalar(0.0, 0.0, 0.0)
        ): Mat {
            if (!CvRuntime.isNativeAvailable) return source
            val out = source.clone()
            for (det in detections) {
                Imgproc.rectangle(
                    out,
                    Point(det.rect.left.toDouble(), det.rect.top.toDouble()),
                    Point(det.rect.right.toDouble(), det.rect.bottom.toDouble()),
                    fillColor,
                    -1 // Filled
                )
            }
            return out
        }

        /**
         * QualityGate Contract 4 Verification:
         * Verifies that all pixels within the redaction masks are strictly the constant solid fill,
         * with zero residual gradients, textures, or forensic leakage.
         */
        fun verifyZeroResidualSignals(
            bitmap: Bitmap,
            detections: List<PiiDetection>,
            expectedColor: Int = Color.BLACK
        ): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val expA = (expectedColor ushr 24) and 0xFF
            val expR = (expectedColor ushr 16) and 0xFF
            val expG = (expectedColor ushr 8) and 0xFF
            val expB = expectedColor and 0xFF

            for (det in detections) {
                val left = Math.round(det.rect.left).coerceIn(0, width)
                val top = Math.round(det.rect.top).coerceIn(0, height)
                val right = Math.round(det.rect.right).coerceIn(0, width)
                val bottom = Math.round(det.rect.bottom).coerceIn(0, height)

                for (y in top until bottom) {
                    val rowOffset = y * width
                    for (x in left until right) {
                        val p = pixels[rowOffset + x]
                        val a = (p ushr 24) and 0xFF
                        val r = (p ushr 16) and 0xFF
                        val g = (p ushr 8) and 0xFF
                        val b = p and 0xFF
                        if (a != expA || r != expR || g != expG || b != expB) {
                            return false // Residual pixel leak detected
                        }
                    }
                }
            }
            return true
        }

        /**
         * Exports detections as a structured JSON manifest for the Privacy QualityGate audit.
         */
        fun exportRedactionManifest(
            detections: List<PiiDetection>,
            imageWidth: Int,
            imageHeight: Int,
            timestamp: Long = System.currentTimeMillis()
        ): JSONObject {
            val root = JSONObject()
            root.put("version", "1.0.0")
            root.put("timestamp", timestamp)
            root.put("layer", "Layer_C_Isolated_Solid_Redaction")
            root.put("imageWidth", imageWidth)
            root.put("imageHeight", imageHeight)
            val boxes = JSONArray()
            for (det in detections) {
                val item = JSONObject()
                item.put("class", det.piiClass.name)
                item.put("label", det.label)
                item.put("confidence", det.score)
                val coords = JSONObject()
                coords.put("left", det.rect.left)
                coords.put("top", det.rect.top)
                coords.put("right", det.rect.right)
                coords.put("bottom", det.rect.bottom)
                item.put("coordinates", coords)
                boxes.put(item)
            }
            root.put("redactions", boxes)
            return root
        }

        private fun createDefaultSessionOptions(): OrtSession.SessionOptions {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addNnapi()
            } catch (ignored: Throwable) {
                // NNAPI unavailable on host JVM or unsupported device; fallback to CPU/XNNPACK
            }
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            return opts
        }

        fun fromAsset(
            context: Context,
            assetName: String = DEFAULT_ASSET_NAME,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): PiiOnnxEngine {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val session = ortEnv.createSession(bytes, createDefaultSessionOptions())
            return PiiOnnxEngine(session, ortEnv)
        }

        fun fromFile(
            file: File,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): PiiOnnxEngine {
            require(file.exists() && file.length() > 0) { "Model file does not exist: ${file.absolutePath}" }
            val session = ortEnv.createSession(file.absolutePath, createDefaultSessionOptions())
            return PiiOnnxEngine(session, ortEnv)
        }

        fun fromBytes(
            bytes: ByteArray,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): PiiOnnxEngine {
            val session = ortEnv.createSession(bytes, createDefaultSessionOptions())
            return PiiOnnxEngine(session, ortEnv)
        }
    }

    val inputName: String = ortSession.inputNames.iterator().next() ?: DEFAULT_INPUT_NAME

    // Pre-allocated 1x3x640x640 planar tensor buffer (640*640*3 = 1,228,800 floats) to eliminate GC pressure
    private val imageTensorBuffer = FloatArray(3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

    /**
     * Secondary constructor for Android Context loading from assets.
     */
    constructor(
        context: Context,
        assetName: String = DEFAULT_ASSET_NAME
    ) : this(
        ortSession = OrtEnvironment.getEnvironment().createSession(
            context.assets.open(assetName).use { it.readBytes() },
            createDefaultSessionOptions()
        )
    )

    /**
     * Secondary constructor from local File.
     */
    constructor(modelFile: File) : this(
        ortSession = OrtEnvironment.getEnvironment().createSession(
            modelFile.absolutePath,
            createDefaultSessionOptions()
        )
    )

    /**
     * Preprocesses a 640x640 RGB [Bitmap] into the pre-allocated planar float buffer.
     * Normalized to [0.0f, 1.0f] in NCHW order (R-plane, G-plane, B-plane).
     */
    fun preprocessToBuffer(bmp: Bitmap) {
        require(bmp.width == MODEL_INPUT_SIZE && bmp.height == MODEL_INPUT_SIZE) {
            "Bitmap must be exactly ${MODEL_INPUT_SIZE}x${MODEL_INPUT_SIZE} (got ${bmp.width}x${bmp.height})"
        }
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        bmp.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        val planeSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
        val gOffset = planeSize
        val bOffset = planeSize * 2

        for (i in 0 until planeSize) {
            val pixel = pixels[i]
            imageTensorBuffer[i] = ((pixel shr 16 and 0xFF) / 255.0f) // R
            imageTensorBuffer[gOffset + i] = ((pixel shr 8 and 0xFF) / 255.0f) // G
            imageTensorBuffer[bOffset + i] = ((pixel and 0xFF) / 255.0f) // B
        }
    }

    /**
     * Preprocesses a 640x640 RGB/BGR [Mat] into the pre-allocated planar float buffer.
     * Safely gated through [CvRuntime.isNativeAvailable].
     */
    fun preprocessToBuffer(mat: Mat) {
        if (!CvRuntime.isNativeAvailable) return
        val planeSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
        val gOffset = planeSize
        val bOffset = planeSize * 2

        val rgbMat = Mat()
        try {
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGRA2RGB)
            } else if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)
            } else {
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_GRAY2RGB)
            }

            val bytes = ByteArray(planeSize * 3)
            rgbMat.get(0, 0, bytes)

            for (i in 0 until planeSize) {
                val r = bytes[i * 3].toInt() and 0xFF
                val g = bytes[i * 3 + 1].toInt() and 0xFF
                val b = bytes[i * 3 + 2].toInt() and 0xFF
                imageTensorBuffer[i] = r / 255.0f
                imageTensorBuffer[gOffset + i] = g / 255.0f
                imageTensorBuffer[bOffset + i] = b / 255.0f
            }
        } finally {
            rgbMat.release()
        }
    }

    /**
     * Runs automated PII detection on an Android [Bitmap].
     *
     * @param bitmap Source input image.
     * @param confThreshold Minimum confidence score (default 0.40).
     * @param iouThreshold NMS overlap threshold (default 0.45).
     * @return List of detected and localized PII items scaled to original bitmap dimensions.
     */
    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.40f,
        iouThreshold: Float = 0.45f
    ): List<PiiDetection> {
        return detect(bitmap, bitmap.width, bitmap.height, confThreshold, iouThreshold)
    }

    /**
     * Runs automated PII detection on an Android [Bitmap], scaling coordinates to [targetWidth] x [targetHeight].
     */
    fun detect(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        confThreshold: Float = 0.40f,
        iouThreshold: Float = 0.45f
    ): List<PiiDetection> {
        val resized = if (bitmap.width == MODEL_INPUT_SIZE && bitmap.height == MODEL_INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        }

        try {
            preprocessToBuffer(resized)
            return runInference(targetWidth, targetHeight, confThreshold, iouThreshold)
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    /**
     * Runs automated PII detection on an OpenCV [Mat].
     * Safely gated through [CvRuntime.isNativeAvailable].
     */
    fun detect(
        sourceMat: Mat,
        confThreshold: Float = 0.40f,
        iouThreshold: Float = 0.45f
    ): List<PiiDetection> {
        if (!CvRuntime.isNativeAvailable) return emptyList()
        val resized = Mat()
        try {
            if (sourceMat.cols() == MODEL_INPUT_SIZE && sourceMat.rows() == MODEL_INPUT_SIZE) {
                preprocessToBuffer(sourceMat)
            } else {
                Imgproc.resize(sourceMat, resized, org.opencv.core.Size(MODEL_INPUT_SIZE.toDouble(), MODEL_INPUT_SIZE.toDouble()))
                preprocessToBuffer(resized)
            }
            return runInference(sourceMat.cols(), sourceMat.rows(), confThreshold, iouThreshold)
        } finally {
            resized.release()
        }
    }

    /**
     * Executes inference on the pre-populated [imageTensorBuffer].
     */
    fun runInference(
        origW: Int,
        origH: Int,
        confThreshold: Float = 0.40f,
        iouThreshold: Float = 0.45f
    ): List<PiiDetection> {
        val shape = longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(imageTensorBuffer), shape).use { inputTensor ->
            val inputs = mapOf(inputName to inputTensor)
            ortSession.run(inputs).use { results ->
                @Suppress("UNCHECKED_CAST")
                val rawOutput = results[0].value as Array<Array<FloatArray>>
                return postprocess(rawOutput[0], origW, origH, confThreshold, iouThreshold)
            }
        }
    }

    override fun close() {
        ortSession.close()
    }
}

/**
 * Standard PII classification categories.
 */
enum class PiiClass(val label: String, val category: String) {
    FACE("Face", "Biometric"),
    PLATE("License Plate", "Identifier"),
    SIGNATURE("Signature", "Legal"),
    CARD("Credit Card", "Financial"),
    BARCODE("Barcode/QR", "Data")
}

/**
 * An individual localized PII detection bounding box and confidence score.
 */
data class PiiDetection(
    val rect: RectF,
    val piiClass: PiiClass,
    val score: Float,
    val label: String = piiClass.label
)
