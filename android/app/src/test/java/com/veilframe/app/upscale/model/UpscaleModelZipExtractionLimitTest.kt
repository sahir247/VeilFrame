package com.veilframe.app.upscale.model

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class UpscaleModelZipExtractionLimitTest {

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = UpscaleModelRepository(context)
    }

    @Test
    fun testZipEntryCountLimitExceededThrowsSecurityException() {
        val zipFile = File(context.cacheDir, "bomb_entries.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // MAX_NCNN_ZIP_ENTRIES is 25, add 26 entries
                for (i in 1..26) {
                    val entry = ZipEntry("file_$i.txt")
                    zos.putNextEntry(entry)
                    zos.write("data_$i".toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            try {
                repository.importCustomNcnnFromZip(Uri.fromFile(zipFile), "bomb_entries.zip", 4)
                fail("Expected SecurityException due to exceeding entry limit")
            } catch (e: SecurityException) {
                assertTrue(e.message!!.contains("exceeds maximum entry limit"))
            }
        } finally {
            zipFile.delete()
        }
    }

    @Test
    fun testZipPathTraversalThrowsSecurityException() {
        val zipFile = File(context.cacheDir, "traversal.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val maliciousEntry = ZipEntry("../../etc/passwd")
                zos.putNextEntry(maliciousEntry)
                zos.write("root:x:0:0".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            try {
                repository.importCustomNcnnFromZip(Uri.fromFile(zipFile), "traversal.zip", 4)
                fail("Expected SecurityException due to path traversal attempt")
            } catch (e: SecurityException) {
                // Either malformed entry path or path traversal caught
                assertTrue(
                    e.message!!.contains("Malformed entry path") ||
                    e.message!!.contains("traversal")
                )
            }
        } finally {
            zipFile.delete()
        }
    }

    @Test
    fun testValidZipExtractionAndCleanup() {
        val zipFile = File(context.cacheDir, "valid_model.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val paramEntry = ZipEntry("test_custom.param")
                zos.putNextEntry(paramEntry)
                zos.write("7767517\n1 1\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val binEntry = ZipEntry("test_custom.bin")
                zos.putNextEntry(binEntry)
                zos.write(ByteArray(32) { 0x42.toByte() })
                zos.closeEntry()
            }

            val imported = repository.importCustomNcnnFromZip(Uri.fromFile(zipFile), "valid_model.zip", 4)
            assertNotNull(imported)
            assertEquals(ModelType.AI_NCNN, imported.type)
            assertTrue(imported.isImported)
            assertTrue(imported.paramSha256!!.isNotEmpty())
            assertTrue(imported.binSha256!!.isNotEmpty())

            // Ensure temp directories in cacheDir were cleaned up
            val remainingExtractDirs = context.cacheDir.listFiles()?.filter {
                it.isDirectory && it.name.startsWith("ncnn_extract_")
            } ?: emptyList()
            assertTrue(remainingExtractDirs.isEmpty())
        } finally {
            zipFile.delete()
        }
    }
}
