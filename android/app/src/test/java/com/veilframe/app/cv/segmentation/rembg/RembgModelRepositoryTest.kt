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
    fun testModelNotReadyByDefault() {
        assertFalse(repository.isModelReady(RembgModel.BIREFNET_GENERAL_LITE))
        assertEquals(0L, repository.getCachedSizeBytes(RembgModel.BIREFNET_GENERAL_LITE))
        assertTrue(repository.listReadyModels().isEmpty())
    }

    @Test
    fun testModelReadyWhenFileSizeMeetsMinBytes() {
        val model = RembgModel.U2NET_HUMAN
        val file = repository.getModelFile(model)

        // Create dummy file smaller than minBytes
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(100))
        assertFalse(repository.isModelReady(model))

        // Expand file using RandomAccessFile to satisfy minBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(model.minBytes + 1024)
        }
        assertTrue(repository.isModelReady(model))
        assertEquals(model.minBytes + 1024, repository.getCachedSizeBytes(model))
        assertEquals(listOf(model), repository.listReadyModels())

        // Test deletion
        assertTrue(repository.deleteModel(model))
        assertFalse(repository.isModelReady(model))
    }
}
