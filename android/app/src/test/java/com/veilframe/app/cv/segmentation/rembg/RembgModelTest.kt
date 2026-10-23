package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RembgModelTest {

    @Test
    fun testDefaultModelIsU2NetP() {
        assertEquals(RembgModel.U2NETP, RembgModel.DEFAULT)
        assertTrue(RembgModel.U2NETP.isRecommended)
        assertTrue(RembgModel.U2NETP.isBundled)
    }

    @Test
    fun testAllModelsHaveValidFilesAndDimensions() {
        RembgModel.entries.forEach { model ->
            assertTrue(model.fileName.endsWith(".bin"))
            assertTrue(model.paramFileName.endsWith(".param"))
            assertTrue(model.inputWidth > 0)
            assertTrue(model.inputHeight > 0)
            assertNotNull(model.sizeMbFormatted)
            if (!model.isBundled) {
                assertTrue(model.downloadUrl.startsWith("https://"))
                assertTrue(model.paramDownloadUrl.startsWith("https://"))
                assertTrue(model.minBytes > 1_000_000L)
            }
        }
    }

    @Test
    fun testFromIdLookup() {
        assertEquals(RembgModel.U2NETP, RembgModel.fromId("u2netp"))
        assertEquals(RembgModel.MODNET, RembgModel.fromId("modnet"))
        // Safe fallback to DEFAULT on unknown ID
        assertEquals(RembgModel.DEFAULT, RembgModel.fromId("unknown-model-id"))
    }
}
