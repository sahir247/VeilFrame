package com.veilframe.app.media

import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageStudioBatchEditContractTest {
    @Test fun preservesEachImagesCropRotationBlurAndPii() {
        val first = ImageEditState(cropLeft = 0.1f, rotationAngle = 90f, blurScope = ImageBlurScope.FULL,
            piiDetections = listOf(PiiDetection(RectF(5f, 6f, 40f, 60f), PiiClass.TEXT_PII, 0.94f)))
        val second = ImageEditState(cropLeft = 0.4f, rotationAngle = 270f, blurScope = ImageBlurScope.BACKGROUND)
        val draft = first.copy(cropLeft = 0.2f)
        val output = ImageStudioBatchEditContract.snapshot(listOf(first, second), 0, draft)
        assertEquals(0.2f, output[0].cropLeft)
        assertEquals(0.4f, output[1].cropLeft)
        assertEquals(90f, output[0].rotationAngle)
        assertEquals(270f, output[1].rotationAngle)
        assertEquals(ImageBlurScope.FULL, output[0].blurScope)
        assertEquals(ImageBlurScope.BACKGROUND, output[1].blurScope)
        assertEquals(1, output[0].piiDetections.size)
        assertEquals(0, output[1].piiDetections.size)
        assertNotSame(first, output[0])
        assertNotSame(second, output[1])
        assertEquals(0.1f, first.cropLeft)
    }

    @Test fun refusesStaleSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageStudioBatchEditContract.snapshot(listOf(ImageEditState()), 2, ImageEditState())
        }
    }
}
