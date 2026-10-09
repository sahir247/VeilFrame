package com.veilframe.app.cv.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Unit tests verifying the mathematical decoding, NMS suppression,
 * solid redaction, QualityGate residual probe verification, and ONNX Runtime
 * inference of [PiiOnnxEngine].
 */
@RunWith(RobolectricTestRunner::class)
class PiiOnnxEngineTest {

    @Test
    fun testCalculateIoUDisjoint() {
        val a = RectF(0f, 0f, 10f, 10f)
        val b = RectF(20f, 20f, 30f, 30f)
        val iou = PiiOnnxEngine.calculateIoU(a, b)
        assertEquals(0.0f, iou, 0.0001f)
    }

    @Test
    fun testCalculateIoUIdentical() {
        val a = RectF(10f, 10f, 50f, 50f)
        val b = RectF(10f, 10f, 50f, 50f)
        val iou = PiiOnnxEngine.calculateIoU(a, b)
        assertEquals(1.0f, iou, 0.0001f)
    }

    @Test
    fun testCalculateIoUPartialOverlap() {
        // Box A: 0..10 x 0..10 -> area = 100
        // Box B: 5..15 x 0..10 -> area = 100
        // Intersection: 5..10 x 0..10 -> area = 50
        // Union = 100 + 100 - 50 = 150
        // IoU = 50 / 150 = 0.33333334
        val a = RectF(0f, 0f, 10f, 10f)
        val b = RectF(5f, 0f, 15f, 10f)
        val iou = PiiOnnxEngine.calculateIoU(a, b)
        assertEquals(50f / 150f, iou, 0.0001f)
    }

    @Test
    fun testApplyNMSSuppressesRedundantOverlappingBoxes() {
        val boxHigh = PiiDetection(
            rect = RectF(10f, 10f, 50f, 50f),
            piiClass = PiiClass.FACE,
            score = 0.95f
        )
        val boxLow = PiiDetection(
            rect = RectF(12f, 12f, 52f, 52f), // Highly overlapping with boxHigh
            piiClass = PiiClass.FACE,
            score = 0.70f
        )
        val boxFar = PiiDetection(
            rect = RectF(100f, 100f, 150f, 150f), // Disjoint
            piiClass = PiiClass.FACE,
            score = 0.85f
        )

        val kept = PiiOnnxEngine.applyNMS(listOf(boxHigh, boxLow, boxFar), iouThreshold = 0.45f)
        assertEquals(2, kept.size)
        assertEquals(0.95f, kept[0].score, 0.0001f)
        assertEquals(0.85f, kept[1].score, 0.0001f)
    }

    @Test
    fun testApplyNMSPreservesDifferentClassesEvenIfOverlapping() {
        // e.g. A Credit Card held over a person's chest
        val boxFace = PiiDetection(
            rect = RectF(10f, 10f, 50f, 50f),
            piiClass = PiiClass.FACE,
            score = 0.90f
        )
        val boxCard = PiiDetection(
            rect = RectF(15f, 15f, 45f, 45f),
            piiClass = PiiClass.CARD,
            score = 0.88f
        )

        val kept = PiiOnnxEngine.applyNMS(listOf(boxFace, boxCard), iouThreshold = 0.45f)
        assertEquals("Different PII classes must never suppress each other", 2, kept.size)
    }

    @Test
    fun testPostprocessDecodesAndScalesYoloOutput() {
        val numClasses = 5
        val totalRows = 4 + numClasses
        val numAnchors = 8400
        val syntheticOutput = Array(totalRows) { FloatArray(numAnchors) }

        // Anchor 42: A high-confidence Face in center of 640x640 space
        // cx = 320, cy = 320, w = 100, h = 100
        syntheticOutput[0][42] = 320f
        syntheticOutput[1][42] = 320f
        syntheticOutput[2][42] = 100f
        syntheticOutput[3][42] = 100f
        syntheticOutput[4][42] = 0.92f // Class 0: FACE

        // Anchor 100: A low-confidence box that should be filtered out
        syntheticOutput[0][100] = 100f
        syntheticOutput[1][100] = 100f
        syntheticOutput[2][100] = 50f
        syntheticOutput[3][100] = 50f
        syntheticOutput[7][100] = 0.20f // Class 3: CARD, below 0.40 threshold

        val origW = 1920
        val origH = 1080

        val detections = PiiOnnxEngine.postprocess(
            output = syntheticOutput,
            origW = origW,
            origH = origH,
            conf = 0.40f,
            iouThreshold = 0.45f
        )

        assertEquals(1, detections.size)
        val det = detections[0]
        assertEquals(PiiClass.FACE, det.piiClass)
        assertEquals(0.92f, det.score, 0.0001f)

        // Verify coordinate scaling to 1920x1080:
        // left = (320 - 50) * (1920 / 640) = 270 * 3 = 810
        // right = (320 + 50) * 3 = 370 * 3 = 1110
        // top = (320 - 50) * (1080 / 640) = 270 * 1.6875 = 455.625
        // bottom = (320 + 50) * 1.6875 = 370 * 1.6875 = 624.375
        assertEquals(810f, det.rect.left, 0.01f)
        assertEquals(1110f, det.rect.right, 0.01f)
        assertEquals(455.625f, det.rect.top, 0.01f)
        assertEquals(624.375f, det.rect.bottom, 0.01f)
    }

    @Test
    fun testClassMappingFiveClassAndCoco() {
        assertEquals(PiiClass.FACE, PiiOnnxEngine.mapClassIndex(0, numClasses = 5))
        assertEquals(PiiClass.PLATE, PiiOnnxEngine.mapClassIndex(1, numClasses = 5))
        assertEquals(PiiClass.SIGNATURE, PiiOnnxEngine.mapClassIndex(2, numClasses = 5))
        assertEquals(PiiClass.CARD, PiiOnnxEngine.mapClassIndex(3, numClasses = 5))
        assertEquals(PiiClass.BARCODE, PiiOnnxEngine.mapClassIndex(4, numClasses = 5))

        // COCO mapping
        assertEquals(PiiClass.FACE, PiiOnnxEngine.mapClassIndex(0, numClasses = 80)) // person
        assertEquals(PiiClass.PLATE, PiiOnnxEngine.mapClassIndex(2, numClasses = 80)) // car
        assertEquals(PiiClass.PLATE, PiiOnnxEngine.mapClassIndex(3, numClasses = 80)) // motorcycle
        assertEquals(PiiClass.CARD, PiiOnnxEngine.mapClassIndex(67, numClasses = 80)) // cell phone
        assertEquals(PiiClass.CARD, PiiOnnxEngine.mapClassIndex(73, numClasses = 80)) // book
        assertEquals(null, PiiOnnxEngine.mapClassIndex(16, numClasses = 80)) // dog -> null
    }

    @Test
    fun testApplySolidRedactionAndQualityGateVerification() {
        // Create a 100x100 white bitmap with simulated sensitive content
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val detections = listOf(
            PiiDetection(
                rect = RectF(10f, 10f, 40f, 40f),
                piiClass = PiiClass.FACE,
                score = 0.95f
            )
        )

        // Before redaction, pixels inside the box are white, so zero-residual test fails
        assertFalse(
            "Unredacted sensitive region must fail the zero-residual probe check",
            PiiOnnxEngine.verifyZeroResidualSignals(bitmap, detections, Color.BLACK)
        )

        // Apply solid constant fill redaction
        val redacted = PiiOnnxEngine.applySolidRedaction(bitmap, detections, Color.BLACK)

        // After redaction, every pixel inside 10..40 x 10..40 is pure black (0x000000)
        assertTrue(
            "Solid redacted image must satisfy QualityGate Contract 4 with zero residual leakage",
            PiiOnnxEngine.verifyZeroResidualSignals(redacted, detections, Color.BLACK)
        )

        // Verify outside pixels remain intact (white)
        assertEquals(Color.WHITE, redacted.getPixel(50, 50))
    }

    @Test
    fun testExportRedactionManifestJson() {
        val detections = listOf(
            PiiDetection(
                rect = RectF(10f, 20f, 110f, 120f),
                piiClass = PiiClass.FACE,
                score = 0.94f
            ),
            PiiDetection(
                rect = RectF(200f, 300f, 400f, 450f),
                piiClass = PiiClass.CARD,
                score = 0.89f
            )
        )

        val manifest = PiiOnnxEngine.exportRedactionManifest(
            imageWidth = 1920,
            imageHeight = 1080,
            detections = detections
        )

        assertEquals("1.0.0", manifest.getString("version"))
        assertEquals("Layer_C_Isolated_Solid_Redaction", manifest.getString("layer"))
        assertEquals(1920, manifest.getInt("imageWidth"))
        assertEquals(1080, manifest.getInt("imageHeight"))

        val array = manifest.getJSONArray("redactions")
        assertEquals(2, array.length())

        val item0 = array.getJSONObject(0)
        assertEquals("FACE", item0.getString("class"))
        assertEquals(0.94, item0.getDouble("confidence"), 0.001)
        val coords0 = item0.getJSONObject("coordinates")
        assertEquals(10.0, coords0.getDouble("left"), 0.001)
        assertEquals(20.0, coords0.getDouble("top"), 0.001)
    }

    @Test
    fun testOnnxModelLoadingAndInference() {
        val modelFile = findModelFile("pii_yolov8n.onnx")
        if (modelFile == null || !modelFile.exists()) {
            println("Skipping ONNX live session test; model file not found in test search path.")
            return
        }

        PiiOnnxEngine.fromFile(modelFile).use { engine ->
            assertEquals("images", engine.inputName)

            // Run inference with pre-allocated zero buffer (blank canvas)
            val detections = engine.runInference(
                origW = 640,
                origH = 640,
                confThreshold = 0.50f,
                iouThreshold = 0.45f
            )
            assertNotNull(detections)
            // A pure black 640x640 canvas contains zero PII
            assertTrue(detections.isEmpty())
        }
    }

    @Test
    fun testScaleDetections() {
        val original = listOf(
            PiiDetection(
                rect = RectF(10f, 20f, 50f, 60f),
                piiClass = PiiClass.FACE,
                score = 0.95f
            )
        )

        // Scale from 100x200 to 200x400 (2.0x scale in both dimensions)
        val scaled = PiiOnnxEngine.scaleDetections(
            detections = original,
            srcW = 100,
            srcH = 200,
            dstW = 200,
            dstH = 400
        )

        assertEquals(1, scaled.size)
        val rect = scaled[0].rect
        assertEquals(20f, rect.left, 0.001f)
        assertEquals(40f, rect.top, 0.001f)
        assertEquals(100f, rect.right, 0.001f)
        assertEquals(120f, rect.bottom, 0.001f)
        assertEquals(PiiClass.FACE, scaled[0].piiClass)
    }

    @Test
    fun testImageTransformEngineAppliesPiiSolidRedaction() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // Fill canvas with white
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                bmp.setPixel(x, y, Color.WHITE)
            }
        }

        val state = com.veilframe.app.media.ImageEditState(
            piiDetections = listOf(
                PiiDetection(
                    rect = RectF(10f, 10f, 50f, 50f),
                    piiClass = PiiClass.FACE,
                    score = 0.92f
                )
            )
        )

        val result = com.veilframe.app.media.transform.ImageTransformEngine.transform(
            src = bmp,
            state = state,
            origFullW = 100,
            origFullH = 100
        )

        // Verify inside redaction is pure black
        for (y in 10 until 50) {
            for (x in 10 until 50) {
                assertEquals(Color.BLACK, result.getPixel(x, y))
            }
        }

        // Verify outside redaction remains white
        assertEquals(Color.WHITE, result.getPixel(5, 5))
        assertEquals(Color.WHITE, result.getPixel(70, 70))

        // Contract 4 verification
        val isCompliant = PiiOnnxEngine.verifyZeroResidualSignals(result, state.piiDetections)
        assertTrue(isCompliant)
    }

    @Test
    fun testImageEditStatePiiResetAndHasEdits() {
        val state = com.veilframe.app.media.ImageEditState()
        assertFalse(state.hasEdits())

        state.piiDetections = listOf(
            PiiDetection(
                rect = RectF(0f, 0f, 10f, 10f),
                piiClass = PiiClass.CARD,
                score = 0.88f
            )
        )
        assertTrue(state.hasEdits())

        state.reset()
        assertFalse(state.hasEdits())
        assertTrue(state.piiDetections.isEmpty())
    }

    private fun findModelFile(name: String): File? {
        val candidates = listOf(
            File("android/app/src/main/assets/$name"),
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/android/app/src/main/assets/$name"),
            File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/$name")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0L }
    }
}
