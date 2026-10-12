package com.veilframe.app.storage

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageGovernorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        StorageGovernor.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun `default storage limit is 2 GB`() {
        val limit = StorageGovernor.getMaxStorageGb(context)
        assertEquals(StorageGovernor.LIMIT_2_GB, limit)
    }

    @Test
    fun `storage limit persists and retrieves configured limits`() {
        val limitsToTest = listOf(
            StorageGovernor.LIMIT_1_GB,
            StorageGovernor.LIMIT_2_GB,
            StorageGovernor.LIMIT_4_GB,
            StorageGovernor.LIMIT_8_GB,
            StorageGovernor.LIMIT_UNLIMITED
        )

        for (limit in limitsToTest) {
            StorageGovernor.setMaxStorageGb(context, limit)
            assertEquals(limit, StorageGovernor.getMaxStorageGb(context))
        }
    }

    @Test
    fun `isProtectedModelFile protects AI and CV model paths and extensions`() {
        val onnxFile = File(context.cacheDir, "detector.onnx")
        val tfliteFile = File(context.cacheDir, "segmentation.tflite")
        val binFile = File(context.cacheDir, "model.bin")
        val paramFile = File(context.cacheDir, "model.param")
        val builtinFile = File(context.cacheDir, "core.builtin")

        val modelDirFile = File(File(context.filesDir, "models"), "weights.dat")
        val cvModelDirFile = File(File(context.filesDir, "cv_models"), "net.dat")

        val regularImage = File(context.cacheDir, "exported_image.png")
        val regularVideo = File(context.cacheDir, "exported_video.mp4")
        val regularDoc = File(context.filesDir, "exports/report.pdf")
        val regularZip = File(context.filesDir, "exports/bundle.zip")

        assertTrue(StorageGovernor.isProtectedModelFile(onnxFile))
        assertTrue(StorageGovernor.isProtectedModelFile(tfliteFile))
        assertTrue(StorageGovernor.isProtectedModelFile(binFile))
        assertTrue(StorageGovernor.isProtectedModelFile(paramFile))
        assertTrue(StorageGovernor.isProtectedModelFile(builtinFile))
        assertTrue(StorageGovernor.isProtectedModelFile(modelDirFile))
        assertTrue(StorageGovernor.isProtectedModelFile(cvModelDirFile))

        assertFalse(StorageGovernor.isProtectedModelFile(regularImage))
        assertFalse(StorageGovernor.isProtectedModelFile(regularVideo))
        assertFalse(StorageGovernor.isProtectedModelFile(regularDoc))
        assertFalse(StorageGovernor.isProtectedModelFile(regularZip))
    }

    @Test
    fun `calculateManagedStorageBytes sums non-model files and ignores models`() {
        val cacheDir = context.cacheDir
        cacheDir.mkdirs()

        val sampleFile1 = File(cacheDir, "test1.mp4")
        sampleFile1.writeBytes(ByteArray(1024))

        val sampleFile2 = File(cacheDir, "test2.jpg")
        sampleFile2.writeBytes(ByteArray(2048))

        val protectedModel = File(cacheDir, "network.onnx")
        protectedModel.writeBytes(ByteArray(4096))

        val calculated = StorageGovernor.calculateManagedStorageBytes(context)
        assertEquals(3072L, calculated)

        sampleFile1.delete()
        sampleFile2.delete()
        protectedModel.delete()
    }

    @Test
    fun `enforceStorageLimit returns 0 when limit is unlimited`() = runBlocking {
        StorageGovernor.setMaxStorageGb(context, StorageGovernor.LIMIT_UNLIMITED)
        val freed = StorageGovernor.enforceStorageLimit(context)
        assertEquals(0L, freed)
    }

    @Test
    fun `getManagedFiles excludes user export directories`() {
        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val cvExportsDir = File(context.filesDir, "cv/exports").apply { mkdirs() }
        val userExportFile1 = File(exportsDir, "saved_photo.jpg").apply { writeBytes(ByteArray(1024)) }
        val userExportFile2 = File(cvExportsDir, "saved_doc.pdf").apply { writeBytes(ByteArray(2048)) }

        val cacheFile = File(context.cacheDir, "temp.tmp").apply { writeBytes(ByteArray(512)) }

        val managedFiles = StorageGovernor.getManagedFiles(context)
        assertFalse("files/exports must not be managed for FIFO deletion", managedFiles.contains(userExportFile1))
        assertFalse("files/cv/exports must not be managed for FIFO deletion", managedFiles.contains(userExportFile2))
        assertTrue("cacheDir files should be managed", managedFiles.contains(cacheFile))

        userExportFile1.delete()
        userExportFile2.delete()
        cacheFile.delete()
    }
}
