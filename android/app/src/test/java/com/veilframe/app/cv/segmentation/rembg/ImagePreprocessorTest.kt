package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImagePreprocessorTest {

    @Test
    fun testPrepareInputDimensionsAndChannelLayout() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)

        val model = RembgModel.U2NET_HUMAN // 320x320
        val tensor = ImagePreprocessor.prepareInput(bmp, model)

        val expectedSize = 3 * model.inputWidth * model.inputHeight
        assertEquals(expectedSize, tensor.size)

        val buffer = ImagePreprocessor.toFloatBuffer(tensor)
        assertNotNull(buffer)
        assertEquals(expectedSize, buffer.capacity())
    }

    @Test
    fun testPrepareInputAlreadyTargetDimensions() {
        val model = RembgModel.U2NET_HUMAN
        val bmp = Bitmap.createBitmap(model.inputWidth, model.inputHeight, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)

        val tensor = ImagePreprocessor.prepareInput(bmp, model)
        assertEquals(3 * model.inputWidth * model.inputHeight, tensor.size)
    }
}
