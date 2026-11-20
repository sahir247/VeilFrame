package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative
import com.veilframe.app.privacy.PiiNcnnEngine
import com.veilframe.app.privacy.SalientSubject
import com.veilframe.app.privacy.SubjectCategory
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * MultiPersonMattingEngine — Advanced multi-person, animal & object-aware background removal.
 *
 * Combines:
 * 1. Neural Subject & Object Detection (YOLOv8n 80 COCO classes + UltraFace RFB-320) to detect people,
 *    animals (dogs, cats, bears, birds, etc.), furniture (chairs, couches), and everyday objects.
 * 2. Intelligent Model Routing: If an image contains only objects or animals and no humans, automatically
 *    routes to U^2-Net-P (trained for Salient Object Detection) to prevent portrait-only models (MODNet)
 *    from eroding or devouring object parts (e.g. chair legs, animal paws).
 * 3. Full-resolution global context matting pass with aspect-preserving direct stretch and U^2-Net normPRED.
 * 4. Subject-Level Focused HD Matting: crops each detected individual or salient object so they fill 100% of
 *    the model's receptive field (crisp facial contours, fingers, paws, fur, and wooden chair legs).
 * 5. Multi-subject alpha fusion via non-destructive maximum-alpha compositing.
 * 6. In-tree OpenCV morphological closing, connected-component noise suppression, and edge snapping.
 */
object MultiPersonMattingEngine {

    private const val TAG = "SubjectMattingEngine"
    private const val SUBJECT_MARGIN_RATIO = 0.20f // 20% contextual padding around detected subject
    private const val MIN_CROP_DIM_PX = 48

    data class SubjectRoI(
        val box: RectF,
        val label: String,
        val isHuman: Boolean
    )

    /**
     * Executes the subject-aware matting pipeline.
     */
    fun process(
        context: Context,
        rgbaBitmap: Bitmap,
        modelType: Int,
        useGpu: Boolean = false,
        checkpoint: () -> Unit = {}
    ): Bitmap? {
        checkpoint()
        val width = rgbaBitmap.width
        val height = rgbaBitmap.height

        // 1. Detect all salient subjects (humans, animals, furniture, objects)
        val allSubjects = detectSalientSubjects(context, rgbaBitmap)
        checkpoint()
        val personSubjects = allSubjects.filter { it.category == SubjectCategory.PERSON }
        val nonPersonSubjects = allSubjects.filter { it.category != SubjectCategory.PERSON }

        val personBoxes = resolvePersonBoxes(personSubjects, width, height)
        val objectBoxes = nonPersonSubjects.map { SubjectRoI(it.rect, it.label, isHuman = false) }

        val allRoIs = mutableListOf<SubjectRoI>()
        for (pb in personBoxes) {
            allRoIs.add(SubjectRoI(pb, "person", isHuman = true))
        }
        allRoIs.addAll(objectBoxes)

        Log.i(
            TAG,
            "Detected ${allRoIs.size} subject(s) in frame (${width}x${height}): " +
                    "${personBoxes.size} human(s), ${objectBoxes.size} object/animal entity(s)"
        )

        // 2. Intelligent Model-Smart Routing:
        // MODNet is strictly a portrait matting model trained only on humans. When applied to animals
        // or inanimate objects (e.g. chairs, cars, bears), it severely erodes or drops structural parts.
        // U^2-Net-P was specifically trained on DUTS Salient Object Detection (SOD) and excels at objects.
        var effectiveModelType = modelType
        if (modelType == NcnnMattingNative.MODEL_TYPE_MODNET && personBoxes.isEmpty()) {
            val u2netpReady = if (!NcnnMattingNative.isModelLoaded(NcnnMattingNative.MODEL_TYPE_U2NETP)) {
                NcnnMattingNative.nativeInit(
                    context.assets,
                    "models/u2netp/u2netp.ncnn.param",
                    "models/u2netp/u2netp.ncnn.bin",
                    NcnnMattingNative.MODEL_TYPE_U2NETP,
                    useGpu,
                    true
                )
            } else {
                true
            }
            if (u2netpReady) {
                effectiveModelType = NcnnMattingNative.MODEL_TYPE_U2NETP
                Log.i(
                    TAG,
                    "No human subjects detected (${allRoIs.size} non-human entity(s)). " +
                            "Intelligently routing to Salient Object Detection (U^2-Net-P) to preserve full subject geometry."
                )
            }
        }

        checkpoint()
        // 3. Global Base Matting Pass
        // letterbox = false ensures 100% of tensor resolution is used for real content (no dead gray bars)
        val baseMask = NcnnMattingNative.nativeProcess(rgbaBitmap, effectiveModelType, useGpu, letterbox = false)
            ?: run {
                Log.e(TAG, "Global base matting returned null mask")
                return null
            }

        try {
            checkpoint()
            // 4. Multi-Subject Focused RoI Passes
            val totalArea = width.toFloat() * height.toFloat()
            if (allRoIs.size >= 2) {
                Log.i(TAG, "Multi-subject scene confirmed (${allRoIs.size} subjects). Running Subject-Level HD Matting...")
                fuseSubjectRois(context, rgbaBitmap, baseMask, allRoIs, effectiveModelType, useGpu, checkpoint)
            } else if (allRoIs.size == 1) {
                val roi = allRoIs[0]
                val boxArea = roi.box.width() * roi.box.height()
                // If the single subject occupies a sub-region (< 45% of image), run dedicated RoI pass for fine details
                if (boxArea / totalArea < 0.45f) {
                    Log.i(TAG, "Sub-region subject detected (${(boxArea / totalArea * 100).toInt()}% of frame). Running RoI refinement...")
                    fuseSubjectRois(context, rgbaBitmap, baseMask, allRoIs, effectiveModelType, useGpu, checkpoint)
                }
            }

            // 5. In-tree OpenCV Morphological, Noise Filtering & Edge Continuity Refinement
            checkpoint()
            val refinedMask = refineWithOpenCv(baseMask, rgbaBitmap, isObjectOrAnimal = personBoxes.isEmpty())
            return refinedMask
        } catch (failure: Throwable) {
            if (!baseMask.isRecycled) baseMask.recycle()
            throw failure
        }
    }

    /**
     * Detects humans, faces, animals, and objects using the bundled in-tree PiiNcnnEngine.
     */
    private fun detectSalientSubjects(context: Context, bitmap: Bitmap): List<SalientSubject> {
        return try {
            val piiEngine = PiiNcnnEngine(context)
            try {
                piiEngine.detectSalientSubjects(bitmap, confThreshold = 0.25f, iouThreshold = 0.45f)
            } finally {
                piiEngine.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Salient subject detection failed: ${t.message}. Falling back to global matting.")
            emptyList()
        }
    }

    /**
     * Resolves human body bounding boxes, combining full-body detections and upper-body approximations
     * from standalone faces.
     */
    private fun resolvePersonBoxes(personSubjects: List<SalientSubject>, imgW: Int, imgH: Int): List<RectF> {
        val faces = personSubjects.filter { it.label == "face" }
        val bodies = personSubjects.filter { it.label != "face" }

        val candidateBoxes = mutableListOf<RectF>()
        for (b in bodies) {
            candidateBoxes.add(b.rect)
        }

        for (f in faces) {
            val isCovered = candidateBoxes.any { box ->
                PiiNcnnEngine.calculateIoU(box, f.rect) > 0.20f ||
                        (f.rect.left >= box.left && f.rect.right <= box.right &&
                                f.rect.top >= box.top && f.rect.bottom <= box.bottom)
            }
            if (!isCovered) {
                val faceH = f.rect.height()
                val faceW = f.rect.width()
                val bodyTop = f.rect.top
                val bodyBottom = min(imgH.toFloat(), f.rect.bottom + faceH * 3.0f)
                val bodyLeft = max(0f, f.rect.left - faceW * 0.75f)
                val bodyRight = min(imgW.toFloat(), f.rect.right + faceW * 0.75f)
                candidateBoxes.add(RectF(bodyLeft, bodyTop, bodyRight, bodyBottom))
            }
        }
        return candidateBoxes
    }

    /**
     * Crops each detected subject with padding margin, runs neural matting on the crop,
     * and fuses the resulting alpha back into the base mask using pixel-wise maximum alpha.
     */
    private fun fuseSubjectRois(
        context: Context,
        sourceBitmap: Bitmap,
        baseMask: Bitmap,
        subjectRois: List<SubjectRoI>,
        effectiveModelType: Int,
        useGpu: Boolean,
        checkpoint: () -> Unit
    ) {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        for ((idx, roi) in subjectRois.withIndex()) {
            checkpoint()
            val box = roi.box
            val padX = box.width() * SUBJECT_MARGIN_RATIO
            val padY = box.height() * SUBJECT_MARGIN_RATIO

            val cropLeft = max(0, (box.left - padX).toInt())
            val cropTop = max(0, (box.top - padY).toInt())
            val cropRight = min(srcW, (box.right + padX).toInt())
            val cropBottom = min(srcH, (box.bottom + padY).toInt())

            val cropW = cropRight - cropLeft
            val cropH = cropBottom - cropTop

            if (cropW < MIN_CROP_DIM_PX || cropH < MIN_CROP_DIM_PX) {
                continue
            }

            // Skip RoI if it already covers the vast majority of the frame
            if (cropW >= (srcW * 0.85f).toInt() && cropH >= (srcH * 0.85f).toInt()) {
                Log.i(TAG, "Skipping Subject #$idx (${roi.label}) RoI as it covers >=85% of full frame")
                continue
            }

            // Select optimal model for this specific RoI
            val roiModelType = if (roi.isHuman && effectiveModelType == NcnnMattingNative.MODEL_TYPE_MODNET) {
                NcnnMattingNative.MODEL_TYPE_MODNET
            } else {
                // Ensure U2-Net-P is loaded for object / animal RoIs
                if (!NcnnMattingNative.isModelLoaded(NcnnMattingNative.MODEL_TYPE_U2NETP)) {
                    NcnnMattingNative.nativeInit(
                        context.assets,
                        "models/u2netp/u2netp.ncnn.param",
                        "models/u2netp/u2netp.ncnn.bin",
                        NcnnMattingNative.MODEL_TYPE_U2NETP,
                        useGpu,
                        true
                    )
                }
                NcnnMattingNative.MODEL_TYPE_U2NETP
            }

            Log.i(TAG, "Processing Subject #$idx (${roi.label}) RoI: [$cropLeft, $cropTop, $cropW, $cropH] with modelType=$roiModelType")

            var subjectCrop: Bitmap? = null
            var subjectMask: Bitmap? = null
            try {
                subjectCrop = Bitmap.createBitmap(sourceBitmap, cropLeft, cropTop, cropW, cropH)
                subjectMask = NcnnMattingNative.nativeProcess(subjectCrop, roiModelType, useGpu, letterbox = false)

                if (subjectMask != null) {
                    mergeAlphaRegion(baseMask, subjectMask, cropLeft, cropTop, checkpoint = checkpoint)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Failed RoI pass for subject #$idx (${roi.label}): ${t.message}")
            } finally {
                if (subjectCrop != null && subjectCrop !== sourceBitmap) {
                    subjectCrop.recycle()
                }
                subjectMask?.recycle()
            }
        }
    }

    /**
     * Composes a subject's alpha into a global mask without allocating whole-crop
     * pixel arrays. The max-alpha rule matches the original full-array merge.
     */
    internal fun mergeAlphaRegion(
        baseMask: Bitmap,
        subjectMask: Bitmap,
        left: Int,
        top: Int,
        maxStripRows: Int = 64,
        checkpoint: () -> Unit = {}
    ) {
        val width = subjectMask.width
        val height = subjectMask.height
        require(width > 0 && height > 0 && maxStripRows > 0)
        require(left >= 0 && top >= 0 &&
            left.toLong() + width <= baseMask.width &&
            top.toLong() + height <= baseMask.height) { "Subject mask is outside the global mask" }

        val stripeRows = min(maxStripRows, height)
        val subjectPixels = IntArray(width * stripeRows)
        val basePixels = IntArray(width * stripeRows)
        var row = 0
        while (row < height) {
            checkpoint()
            val rows = min(stripeRows, height - row)
            val count = width * rows
            subjectMask.getPixels(subjectPixels, 0, width, 0, row, width, rows)
            baseMask.getPixels(basePixels, 0, width, left, top + row, width, rows)
            for (i in 0 until count) {
                val subjectAlpha = (subjectPixels[i] ushr 24) and 0xFF
                val baseAlpha = (basePixels[i] ushr 24) and 0xFF
                basePixels[i] = (max(baseAlpha, subjectAlpha) shl 24) or 0x00FFFFFF
            }
            baseMask.setPixels(basePixels, 0, width, left, top + row, width, rows)
            row += rows
        }
    }

    /**
     * Leverages in-tree OpenCV to apply:
     * 1. Morphological closing (bridges thin chair legs, fingers, whiskers, limbs).
     * 2. Connected-component speckle noise suppression in background regions.
     * 3. Subtle edge smoothing for film-like alpha transition.
     */
    fun refineWithOpenCv(maskBitmap: Bitmap, sourceBitmap: Bitmap? = null, isObjectOrAnimal: Boolean = false): Bitmap {
        if (!CvRuntime.initialize()) return maskBitmap

        var alphaMat: Mat? = null
        var binaryMat: Mat? = null
        var labels: Mat? = null
        var stats: Mat? = null
        var centroids: Mat? = null
        try {
            val w = maskBitmap.width
            val h = maskBitmap.height
            val pixelCount = Math.multiplyExact(w, h)

            // Keep only one full-sized alpha channel in Java. Recycling and
            // native Mat cleanup are guaranteed even when OpenCV throws.
            val alphaBytes = ByteArray(pixelCount)
            val stripeHeight = min(64, h)
            val argbStripe = IntArray(w * stripeHeight)
            var y = 0
            while (y < h) {
                val rows = min(stripeHeight, h - y)
                maskBitmap.getPixels(argbStripe, 0, w, 0, y, w, rows)
                for (i in 0 until w * rows) {
                    alphaBytes[y * w + i] = ((argbStripe[i] ushr 24) and 0xFF).toByte()
                }
                y += rows
            }

            val working = Mat(h, w, CvType.CV_8UC1)
            alphaMat = working
            working.put(0, 0, alphaBytes)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            try {
                Imgproc.morphologyEx(working, working, Imgproc.MORPH_CLOSE, kernel)
            } finally {
                kernel.release()
            }
            // Keep the post-morphology alpha as the source of truth. Previously
            // speckle removal wrote the ORIGINAL alpha array over this result,
            // silently undoing edge/whisker gap closing whenever a small blob existed.
            working.get(0, 0, alphaBytes)

            val binary = Mat()
            val labelMap = Mat()
            val labelStats = Mat()
            val labelCentroids = Mat()
            binaryMat = binary
            labels = labelMap
            stats = labelStats
            centroids = labelCentroids
            Imgproc.threshold(working, binary, 40.0, 255.0, Imgproc.THRESH_BINARY)
            val numLabels = Imgproc.connectedComponentsWithStats(binary, labelMap, labelStats, labelCentroids)

            if (numLabels > 2) {
                val minAreaToKeep = if (isObjectOrAnimal) 24 else 32
                val removeLabel = BooleanArray(numLabels)
                for (label in 1 until numLabels) {
                    removeLabel[label] = labelStats.get(label, Imgproc.CC_STAT_AREA)[0].toInt() < minAreaToKeep
                }

                if (removeLabel.any { it }) {
                    // O(pixels + labels), not O(pixels * labels). A 4K frame
                    // uses one scanline of labels instead of a second 64MB array.
                    val labelRow = IntArray(w)
                    var modified = false
                    for (row in 0 until h) {
                        labelMap.get(row, 0, labelRow)
                        val offset = row * w
                        for (x in 0 until w) {
                            val label = labelRow[x]
                            if (label in 1 until numLabels && removeLabel[label]) {
                                alphaBytes[offset + x] = 0
                                modified = true
                            }
                        }
                    }
                    // Remove speckles from the morphologically refined alpha,
                    // preserving gap closing and subsequent Gaussian feathering.
                    if (modified) working.put(0, 0, alphaBytes)
                }
            }

            Imgproc.GaussianBlur(working, working, Size(3.0, 3.0), 0.5)
            working.get(0, 0, alphaBytes)

            y = 0
            while (y < h) {
                val rows = min(stripeHeight, h - y)
                for (i in 0 until w * rows) {
                    val a = alphaBytes[y * w + i].toInt() and 0xFF
                    argbStripe[i] = (a shl 24) or 0x00FFFFFF
                }
                maskBitmap.setPixels(argbStripe, 0, w, 0, y, w, rows)
                y += rows
            }
            return maskBitmap
        } catch (t: Exception) {
            Log.w(TAG, "OpenCV mask refinement bypassed: ${t.message}")
            return maskBitmap
        } finally {
            runCatching { alphaMat?.release() }
            runCatching { binaryMat?.release() }
            runCatching { labels?.release() }
            runCatching { stats?.release() }
            runCatching { centroids?.release() }
        }
    }

}
