package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RembgModelTest {

    @Test
    fun testDefaultModelIsBiRefNetLite() {
        assertEquals(RembgModel.BIREFNET_GENERAL_LITE, RembgModel.DEFAULT)
        assertTrue(RembgModel.BIREFNET_GENERAL_LITE.isRecommended)
        assertTrue(RembgModel.BIREFNET_GENERAL_LITE.needsSigmoidOutput)
    }

    @Test
    fun testAllModelsHaveValidUrlsAndDimensions() {
        RembgModel.entries.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://"))
            assertTrue(model.fileName.endsWith(".onnx"))
            assertTrue(model.minBytes > 100_000_000L)
            assertTrue(model.inputWidth > 0)
            assertTrue(model.inputHeight > 0)
            assertEquals(3, model.mean.size)
            assertEquals(3, model.std.size)
            assertNotNull(model.sizeMbFormatted)
        }
    }

    @Test
    fun testFromIdLookup() {
        assertEquals(RembgModel.BIREFNET_GENERAL_LITE, RembgModel.fromId("birefnet-general-lite"))
        assertEquals(RembgModel.ISNET_GENERAL, RembgModel.fromId("isnet-general-use"))
        assertEquals(RembgModel.U2NET_HUMAN, RembgModel.fromId("u2net_human_seg"))
        // Safe fallback to DEFAULT on unknown ID
        assertEquals(RembgModel.DEFAULT, RembgModel.fromId("unknown-model-id"))
    }
}
