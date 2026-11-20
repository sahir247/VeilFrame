package com.veilframe.app.cv.segmentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NcnnMattingContractTest {
    @Test fun bundledModelCpuAndVulkanPreserveMaskGeometry() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val type = NcnnMattingNative.MODEL_TYPE_U2NETP
        val image = Bitmap.createBitmap(257, 113, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height) { i ->
            val x = i % image.width
            val y = i / image.width
            if (x in 60..180 && y in 25..90) Color.rgb(210, 95, 40) else Color.rgb(25, 55, 100)
        }
        image.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        var cpu: Bitmap? = null
        var gpu: Bitmap? = null
        try {
            assertTrue(NcnnMattingNative.nativeInit(assets, "models/u2netp/u2netp.ncnn.param",
                "models/u2netp/u2netp.ncnn.bin", type, false, true))
            assertFalse(NcnnMattingNative.isModelGpu(type))
            val cpuMask = requireNotNull(NcnnMattingNative.nativeProcess(image, type, false, true))
            cpu = cpuMask
            assertEquals(image.width, cpuMask.width)
            assertEquals(image.height, cpuMask.height)
            assertTrue(NcnnMattingNative.nativeInit(assets, "models/u2netp/u2netp.ncnn.param",
                "models/u2netp/u2netp.ncnn.bin", type, true, true))
            assumeTrue("Device did not load Vulkan matting", NcnnMattingNative.isModelGpu(type))
            val gpuMask = requireNotNull(NcnnMattingNative.nativeProcess(image, type, true, true))
            gpu = gpuMask
            assertEquals(cpuMask.width, gpuMask.width)
            assertEquals(cpuMask.height, gpuMask.height)
            var sum = 0L
            for (y in 0 until image.height) for (x in 0 until image.width) {
                sum += kotlin.math.abs(Color.alpha(cpuMask.getPixel(x, y)) - Color.alpha(gpuMask.getPixel(x, y)))
            }
            val mean = sum.toDouble() / (image.width * image.height)
            assertTrue("CPU/Vulkan alpha MAE: $mean", mean <= 12.0)
        } finally {
            cpu?.recycle()
            gpu?.recycle()
            image.recycle()
            NcnnMattingNative.nativeRelease()
        }
    }
}
