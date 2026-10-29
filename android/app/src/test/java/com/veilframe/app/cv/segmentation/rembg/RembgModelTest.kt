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
                assertTrue("Non-bundled model ${model.id} must define SHA-256 for bin", model.sha256.isNotEmpty())
                assertTrue("Non-bundled model ${model.id} must define SHA-256 for param", model.paramSha256.isNotEmpty())
            }
        }
    }

    @Test
    fun testModnetPinnedChecksums() {
        val modnet = RembgModel.MODNET
        assertEquals("519656a49f07f535fb3132d95cd80d5651a19821c70c07db7b7364805e6a762b", modnet.sha256)
        assertEquals("6748834c44f53dce16a19f19bfcb55c21c823a49d0640e0ce0db5acf041c98d0", modnet.paramSha256)
    }

    @Test
    fun testFromIdLookup() {
        assertEquals(RembgModel.U2NETP, RembgModel.fromId("u2netp"))
        assertEquals(RembgModel.MODNET, RembgModel.fromId("modnet"))
        // Safe fallback to DEFAULT on unknown ID
        assertEquals(RembgModel.DEFAULT, RembgModel.fromId("unknown-model-id"))
    }
}
