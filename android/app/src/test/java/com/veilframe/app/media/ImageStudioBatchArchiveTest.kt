package com.veilframe.app.media

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class ImageStudioBatchArchiveTest {
    @Test fun archiveContainsEveryImageWithExactBytes() {
        val dir = Files.createTempDirectory("studio-batch").toFile()
        try {
            val first = File(dir, "one.png").apply { writeBytes(byteArrayOf(1,2,3)) }
            val second = File(dir, "two.png").apply { writeBytes(byteArrayOf(4,5,6,7)) }
            val archive = File(dir, "result.zip")
            ImageStudioBatchArchive.writeVerified(listOf(first, second), archive)
            ZipFile(archive).use { zip ->
                assertEquals(setOf(first.name, second.name), zip.entries().toList().map { it.name }.toSet())
                assertArrayEquals(first.readBytes(), zip.getInputStream(zip.getEntry(first.name)).use { it.readBytes() })
                assertArrayEquals(second.readBytes(), zip.getInputStream(zip.getEntry(second.name)).use { it.readBytes() })
            }
            assertThrows(IllegalArgumentException::class.java) {
                ImageStudioBatchArchive.writeVerified(listOf(first, second), archive)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun partialArchiveIsRemovedWhenCancelled() {
        val dir = Files.createTempDirectory("studio-cancel").toFile()
        try {
            val first = File(dir, "one.png").apply { writeText("one") }
            val second = File(dir, "two.png").apply { writeText("two") }
            val archive = File(dir, "result.zip")
            var checks = 0
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ImageStudioBatchArchive.writeVerified(listOf(first, second), archive) {
                    if (++checks == 2) throw java.util.concurrent.CancellationException("cancelled")
                }
            }
            assertFalse(archive.exists())
            assertTrue(first.isFile && second.isFile)
        } finally { dir.deleteRecursively() }
    }

    @Test fun safeNamesCannotInjectFfmpegOrZipPathSyntax() {
        assertEquals("compressed_image", ImageStudioBatchArchive.safeBaseName(".."))
        assertEquals("my_image", ImageStudioBatchArchive.safeBaseName("my\"image.jpg"))
        assertEquals("photo_1", ImageStudioBatchArchive.safeBaseName("folder/photo_1.png"))
    }
}
