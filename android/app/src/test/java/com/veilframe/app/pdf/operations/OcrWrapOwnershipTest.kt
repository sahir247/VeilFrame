package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrWrapOwnershipTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun repeatedFastImportsGetDistinctOwnedFilePaths() {
        val directory = temp.newFolder("ocr")
        val first = allocateOcrWrapFile(directory)
        val second = allocateOcrWrapFile(directory)
        try {
            assertNotEquals(first.absolutePath, second.absolutePath)
            assertEquals(directory.canonicalFile, first.parentFile.canonicalFile)
            assertEquals(directory.canonicalFile, second.parentFile.canonicalFile)
            first.writeText("first")
            second.writeText("second")
            first.delete()
            assertTrue(second.isFile)
            assertEquals("second", second.readText())
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun horizontalMirrorAndTransposedOrientationHaveDistinctMatrices() {
        assertNull(exifOcrTransform(0, false))
        val mirror = exifOcrTransform(0, true)
        assertNotNull(mirror)
        val mirrored = floatArrayOf(2f, 1f)
        mirror!!.mapPoints(mirrored)
        assertEquals(-2f, mirrored[0], 0.001f)
        assertEquals(1f, mirrored[1], 0.001f)

        // EXIF TRANSPOSE: flip horizontally, then rotate 270 degrees.
        val transpose = exifOcrTransform(270, true)!!
        val point = floatArrayOf(2f, 1f)
        transpose.mapPoints(point)
        assertEquals(1f, point[0], 0.001f)
        assertEquals(2f, point[1], 0.001f)
        assertThrows(IllegalArgumentException::class.java) {
            exifOcrTransform(45, false)
        }
    }
}
