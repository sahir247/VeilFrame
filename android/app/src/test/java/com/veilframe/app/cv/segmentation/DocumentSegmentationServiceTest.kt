package com.veilframe.app.cv.segmentation

import com.veilframe.app.cv.core.CvRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencv.core.Mat

class DocumentSegmentationServiceTest {

    @Test
    fun testDocumentSegmentationDataModel() {
        val w = 256
        val h = 256
        val probmap = FloatArray(w * h) { 0.85f }
        val seg = DocumentSegmentation(probmap, w, h)

        assertEquals(256, seg.width)
        assertEquals(256, seg.height)
        assertEquals(0.85f, seg.get(10, 20), 0.001f)

        if (CvRuntime.isNativeAvailable) {
            val probMat = seg.toProbMat()
            assertNotNull(probMat)

            val binMat = seg.toBinaryMat(0.5f)
            assertNotNull(binMat)
        }
    }

    @Test
    fun testServiceGracefulFallbackWhenUninitialized() = runBlocking {
        val service = DocumentSegmentationService(context = null)
        assertFalse("Model must be unavailable without context or model file", service.isModelAvailable)

        if (CvRuntime.isNativeAvailable) {
            val dummyMat = Mat()
            val result = service.runSegmentation(dummyMat)
            assertNull("Inference must return null when model is unavailable", result)
        }

        assertEquals("fairscan-segmentation-model.tflite", DocumentSegmentationService.MODEL_ASSET_NAME)
        assertEquals(256, DocumentSegmentationService.INPUT_WIDTH)
        assertEquals(256, DocumentSegmentationService.INPUT_HEIGHT)
    }
}
