package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
class RembgModelRepositoryTest {

    private lateinit var repository: RembgModelRepository

    @Before
    fun setUp() {
        repository = RembgModelRepository(RuntimeEnvironment.getApplication())
        repository.clearAllModels()
    }

    @Test
    fun testBundledModelIsAlwaysReady() {
        assertTrue(repository.isModelReady(RembgModel.U2NETP))
        assertFalse(repository.deleteModel(RembgModel.U2NETP))
    }

    @Test
    fun testDownloadableModelRequiresFiles() {
        val model = RembgModel.MODNET
        assertFalse(repository.isModelReady(model))

        val binFile = repository.getModelFile(model)
        val paramFile = repository.getParamFile(model)

        // Create dummy files smaller than minBytes
        binFile.parentFile?.mkdirs()
        binFile.writeBytes(ByteArray(100))
        paramFile.writeBytes(ByteArray(100))
        assertFalse(repository.isModelReady(model))

        // Satisfy sizes
        paramFile.writeBytes(ByteArray(1024))
        RandomAccessFile(binFile, "rw").use { raf ->
            raf.setLength(model.minBytes + 1024)
        }
        assertTrue(repository.isModelReady(model))

        // Test deletion
        assertTrue(repository.deleteModel(model))
        assertFalse(repository.isModelReady(model))
    }
}
