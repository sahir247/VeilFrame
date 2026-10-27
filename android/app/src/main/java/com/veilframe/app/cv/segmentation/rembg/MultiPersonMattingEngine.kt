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
        useGpu: Boolean = false
    ): Bitmap? {
        val width = rgbaBitmap.width
        val height = rgbaBitmap.height

        // 1. Detect all salient subjects (humans, animals, furniture, objects)
        val allSubjects = detectSalientSubjects(context, rgbaBitmap)
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

        // 3. Global Base Matting Pass
        // letterbox = false ensures 100% of tensor resolution is used for real content (no dead gray bars)
        val baseMask = NcnnMattingNative.nativeProcess(rgbaBitmap, effectiveModelType, useGpu, letterbox = false)
            ?: run {
                Log.e(TAG, "Global base matting returned null mask")
                return null
            }

        // 4. Multi-Subject Focused RoI Passes
        val totalArea = width.toFloat() * height.toFloat()
        if (allRoIs.size >= 2) {
            Log.i(TAG, "Multi-subject scene confirmed (${allRoIs.size} subjects). Running Subject-Level HD Matting...")
            fuseSubjectRois(context, rgbaBitmap, baseMask, allRoIs, effectiveModelType, useGpu)
        } else if (allRoIs.size == 1) {
            val roi = allRoIs[0]
            val boxArea = roi.box.width() * roi.box.height()
            // If the single subject occupies a sub-region (< 45% of image), run dedicated RoI pass for fine details
            if (boxArea / totalArea < 0.45f) {
                Log.i(TAG, "Sub-region subject detected (${(boxArea / totalArea * 100).toInt()}% of frame). Running RoI refinement...")
                fuseSubjectRois(context, rgbaBitmap, baseMask, allRoIs, effectiveModelType, useGpu)
            }
        }

        // 5. In-tree OpenCV Morphological, Noise Filtering & Edge Continuity Refinement
        val refinedMask = refineWithOpenCv(baseMask, rgbaBitmap, isObjectOrAnimal = personBoxes.isEmpty())
        return refinedMask
    }

    /**
     * Detects humans, faces, animals, and objects using the bundled in-tree PiiNcnnEngine.
     */
    private fun detectSalientSubjects(context: Context, bitmap: Bitmap): List<SalientSubject> {
        return try {
            val piiEngine = PiiNcnnEngine(context)
            val detections = piiEngine.detectSalientSubjects(bitmap, confThreshold = 0.25f, iouThreshold = 0.45f)
            piiEngine.close()
            detections
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
        useGpu: Boolean
    ) {
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        for ((idx, roi) in subjectRois.withIndex()) {
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
                    val cropPixels = IntArray(cropW * cropH)
                    val basePixels = IntArray(cropW * cropH)

                    subjectMask.getPixels(cropPixels, 0, cropW, 0, 0, cropW, cropH)
                    baseMask.getPixels(basePixels, 0, cropW, cropLeft, cropTop, cropW, cropH)

                    for (i in 0 until cropW * cropH) {
                        val subAlpha = (cropPixels[i] ushr 24) and 0xFF
                        val baseAlpha = (basePixels[i] ushr 24) and 0xFF
                        val mergedAlpha = max(baseAlpha, subAlpha)
                        basePixels[i] = (mergedAlpha shl 24) or 0x00FFFFFF
                    }

                    baseMask.setPixels(basePixels, 0, cropW, cropLeft, cropTop, cropW, cropH)
                }
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
     * Leverages in-tree OpenCV to apply:
     * 1. Morphological closing (bridges thin chair legs, fingers, whiskers, limbs).
     * 2. Connected-component speckle noise suppression in background regions.
     * 3. Subtle edge smoothing for film-like alpha transition.
     */
    fun refineWithOpenCv(maskBitmap: Bitmap, sourceBitmap: Bitmap? = null, isObjectOrAnimal: Boolean = false): Bitmap {
        if (!CvRuntime.initialize()) {
            return maskBitmap
        }

        try {
            val w = maskBitmap.width
            val h = maskBitmap.height

            val alphaPixels = IntArray(w * h)
            maskBitmap.getPixels(alphaPixels, 0, w, 0, 0, w, h)

            val alphaBytes = ByteArray(w * h)
            for (i in 0 until w * h) {
                alphaBytes[i] = ((alphaPixels[i] ushr 24) and 0xFF).toByte()
            }

            val alphaMat = Mat(h, w, CvType.CV_8UC1)
            alphaMat.put(0, 0, alphaBytes)

            // 1. Morphological Closing (3x3 ellipse) bridges micro-gaps without distorting silhouettes
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.morphologyEx(alphaMat, alphaMat, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // 2. Connected Components Noise Suppression:
            // Remove tiny isolated specks (< 64px) in the background that are floating debris
            val binaryMat = Mat()
            Imgproc.threshold(alphaMat, binaryMat, 40.0, 255.0, Imgproc.THRESH_BINARY)
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val numLabels = Imgproc.connectedComponentsWithStats(binaryMat, labels, stats, centroids)

            if (numLabels > 2) {
                val labelsData = IntArray(w * h)
                labels.get(0, 0, labelsData)
                val minAreaToKeep = if (isObjectOrAnimal) 24 else 32
                var modified = false

                for (label in 1 until numLabels) {
                    val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                    if (area < minAreaToKeep) {
                        for (idx in 0 until w * h) {
                            if (labelsData[idx] == label) {
                                alphaBytes[idx] = 0.toByte()
                                modified = true
                            }
                        }
                    }
                }
                if (modified) {
                    alphaMat.put(0, 0, alphaBytes)
                }
            }
            binaryMat.release()
            labels.release()
            stats.release()
            centroids.release()

            // 3. Subtle Gaussian blur (1px radius) for smooth film-like alpha transition
            Imgproc.GaussianBlur(alphaMat, alphaMat, Size(3.0, 3.0), 0.5)

            // Read refined alpha back into mask bitmap
            alphaMat.get(0, 0, alphaBytes)
            alphaMat.release()

            for (i in 0 until w * h) {
                val a = alphaBytes[i].toInt() and 0xFF
                alphaPixels[i] = (a shl 24) or 0x00FFFFFF
            }

            maskBitmap.setPixels(alphaPixels, 0, w, 0, 0, w, h)
            return maskBitmap
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV mask refinement bypassed: ${t.message}")
            return maskBitmap
        }
    }
}
