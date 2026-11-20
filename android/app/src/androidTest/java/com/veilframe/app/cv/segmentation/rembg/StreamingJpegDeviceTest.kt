package com.veilframe.app.cv.segmentation.rembg

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

@RunWith(AndroidJUnit4::class)
class StreamingJpegDeviceTest {
    private fun scratch() = java.nio.file.Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "jpeg_codec").toFile()

    @Test fun independentDecoderReadsOddSizedRowsAndColors() {
        val dir = scratch()
        try {
            val image = File(dir, "odd.jpg")
            StreamingJpeg.write(image, 127, 53) { y, row ->
                row.fill(if (y < 24) Color.RED else Color.BLUE)
            }
            StreamingJpeg.verify(image, 127, 53)
            val decoded = checkNotNull(BitmapFactory.decodeFile(image.absolutePath))
            try {
                assertEquals(127, decoded.width)
                assertEquals(53, decoded.height)
                assertTrue(Color.red(decoded.getPixel(60, 5)) >= 250)
                assertTrue(Color.blue(decoded.getPixel(60, 48)) >= 250)
            } finally { decoded.recycle() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun verifierRejectsTruncationWrongDimensionsAndTrailingData() {
        val dir = scratch()
        try {
            val image = File(dir, "good.jpg")
            StreamingJpeg.write(image, 31, 17) { _, row -> row.fill(Color.GREEN) }
            StreamingJpeg.verify(image, 31, 17)
            val bytes = image.readBytes()
            assertThrows(IOException::class.java) { StreamingJpeg.verify(image, 30, 17) }
            for ((index, bad) in listOf(bytes.copyOf(bytes.size - 2), bytes.copyOf(bytes.size / 2),
                bytes + byteArrayOf(1, 2, 3)).withIndex()) {
                val damaged = File(dir, "bad$index.jpg").apply { writeBytes(bad) }
                assertThrows(IOException::class.java) { StreamingJpeg.verify(damaged, 31, 17) }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationAndNonOpaqueRowsReleaseNativeHandles() {
        val dir = scratch()
        try {
            val image = File(dir, "cancel.jpg")
            var calls = 0
            assertThrows(CancellationException::class.java) {
                StreamingJpeg.write(image, 31, 80, checkpoint = {
                    if (++calls == 10) throw CancellationException("cancel")
                }) { _, row -> row.fill(Color.WHITE) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                StreamingJpeg.write(image, 31, 17) { _, row -> row.fill(Color.TRANSPARENT) }
            }
            StreamingJpeg.write(image, 31, 17) { _, row -> row.fill(Color.BLACK) }
            StreamingJpeg.verify(image, 31, 17)
            assertThrows(CancellationException::class.java) {
                StreamingJpeg.verify(image, 31, 17) { throw CancellationException("cancel") }
            }
            StreamingJpeg.verify(image, 31, 17)
        } finally { dir.deleteRecursively() }
    }
}
