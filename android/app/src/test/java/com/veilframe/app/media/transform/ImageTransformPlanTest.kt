package com.veilframe.app.media.transform

import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.ImageOutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTransformPlanTest {

    @Test
    fun testPassportPresetPlanEnforcement() {
        val state = ImageEditState()
        state.cropAspect = "Passport (600×600)"

        val outputConfig = ImageOutputConfig()
        outputConfig.format = "JPG"
        outputConfig.quality = 90

        val plan = ImageTransformEngine.calculateTransformPlan(
            origFullW = 4000,
            origFullH = 3000,
            state = state,
            outputConfig = outputConfig
        )

        assertEquals(600, plan.targetWidth)
        assertEquals(600, plan.targetHeight)
        assertEquals("JPG", plan.outputFormat)
        assertEquals(90, plan.quality)
        assertEquals(OrientationPolicy.NORMALIZE_EXIF, plan.orientationPolicy)
        assertEquals(MetadataPolicy.STRIP_ALL, plan.metadataPolicy)

        // Must have cropRect centered 1:1
        assertNotNull(plan.cropRect)
        val rect = plan.cropRect!!
        // For 4000x3000, minDim is 3000.
        // left = (4000 - 3000) / 2 / 4000 = 500 / 4000 = 0.125f
        assertEquals(0.125f, rect.left, 0.001f)
        assertEquals(0.0f, rect.top, 0.001f)
        assertEquals(0.875f, rect.right, 0.001f)
        assertEquals(1.0f, rect.bottom, 0.001f)
        assertTrue(plan.isCropped)
        assertTrue(plan.hasGeometricTransform)
    }

    @Test
    fun testDefaultPlanNoTransform() {
        val state = ImageEditState()
        val outputConfig = ImageOutputConfig()

        val plan = ImageTransformEngine.calculateTransformPlan(
            origFullW = 1920,
            origFullH = 1080,
            state = state,
            outputConfig = outputConfig
        )

        assertEquals(1920, plan.targetWidth)
        assertEquals(1080, plan.targetHeight)
        assertEquals(0f, plan.rotationDegrees, 0.001f)
        assertEquals(false, plan.flipH)
        assertEquals(false, plan.flipV)
        assertEquals(OrientationPolicy.NORMALIZE_EXIF, plan.orientationPolicy)
    }
}
