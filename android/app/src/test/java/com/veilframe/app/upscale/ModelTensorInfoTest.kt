package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.ModelTensorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTensorInfoTest {

    @Test
    fun testParseConventionScale() {
        assertEquals(4, ModelTensorInfo.parseConventionScale("RealESRGAN_x4plus.ort"))
        assertEquals(2, ModelTensorInfo.parseConventionScale("RealESRGAN_x2plus.ort"))
        assertEquals(4, ModelTensorInfo.parseConventionScale("RealESRGAN-x4v3.ort"))
        assertEquals(4, ModelTensorInfo.parseConventionScale("x4-UltraSharpV2_fp32_op17.ort"))
        assertEquals(1, ModelTensorInfo.parseConventionScale("CodeFormer_512.ort"))
        assertEquals(1, ModelTensorInfo.parseConventionScale("1x_JPEG_00-20.ort"))
        assertEquals(1, ModelTensorInfo.parseConventionScale("unknown_model.ort"))
    }
}
