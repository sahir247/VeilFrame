package com.veilframe.app.upscale

import ai.onnxruntime.OnnxJavaType
import com.veilframe.app.upscale.inference.OnnxUpscaleRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnnxUpscaleRuntimeTest {

    private fun findModelFile(filename: String): File? {
        val candidates = listOf(
            File("""C:\Users\parve\.gemini\antigravity-ide\brain\d671106b-1f04-431b-a5ca-3e2a52a9c6a7\scratch\$filename"""),
            File("src/test/resources/models/$filename"),
            File(System.getProperty("user.home"), ".cache/veilframe/models/$filename")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0L }
    }

    @Test
    fun testRealESRGAN_x4plus_MetadataAndInspection() {
        val modelFile = findModelFile("RealESRGAN_x4plus.ort")
        if (modelFile == null) {
            println("Skipping real ONNX session test: RealESRGAN_x4plus.ort not found in test environment.")
            return
        }

        val runtime = OnnxUpscaleRuntime(modelFile, scale = 4)
        try {
            assertEquals("input", runtime.inputName)
            assertEquals(OnnxJavaType.FLOAT16, runtime.inputType)
            assertEquals(4, runtime.inputRank)

            assertEquals("output", runtime.outputName)
            assertEquals(OnnxJavaType.FLOAT16, runtime.outputType)
            assertEquals(4, runtime.outputRank)

            assertTrue(runtime.isDynamicSpatial)
            assertEquals(4, runtime.scale)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun testRealESRGAN_x2plus_MetadataAndInspection() {
        val modelFile = findModelFile("RealESRGAN_x2plus.ort")
        if (modelFile == null) {
            println("Skipping real ONNX session test: RealESRGAN_x2plus.ort not found in test environment.")
            return
        }

        val runtime = OnnxUpscaleRuntime(modelFile, scale = 2)
        try {
            assertEquals("input", runtime.inputName)
            assertEquals(OnnxJavaType.FLOAT16, runtime.inputType)
            assertEquals(4, runtime.inputRank)

            assertEquals("output", runtime.outputName)
            assertEquals(OnnxJavaType.FLOAT16, runtime.outputType)
            assertEquals(4, runtime.outputRank)

            assertTrue(runtime.isDynamicSpatial)
            assertEquals(2, runtime.scale)
        } finally {
            runtime.close()
        }
    }
}
