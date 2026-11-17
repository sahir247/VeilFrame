package com.veilframe.app.document

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Unit tests for [DocumentExportEngine] options, dimensions math, orientation mapping,
 * and filename sanitization.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentExportEngineTest {

    @Test
    fun `test sanitizeFileName replaces special characters and truncates`() {
        val input = "My Test File / with : special * chars ? < > | and very very very very long name that exceeds 40 chars.pdf"
        val sanitized = DocumentExportEngine.sanitizeFileName(input)

        assertFalse("Sanitized name must not contain slashes", sanitized.contains("/"))
        assertFalse("Sanitized name must not contain colons", sanitized.contains(":"))
        assertFalse("Sanitized name must not contain spaces", sanitized.contains(" "))
        assertTrue("Sanitized name length must be <= 40", sanitized.length <= 40)
    }

    @Test
    fun `test resolvePageDimensions for A4 portrait`() {
        val options = DocumentExportEngine.ExportOptions(
            paperSize = DocumentExportEngine.PaperSize.A4,
            orientation = DocumentExportEngine.Orientation.PORTRAIT
        )
        val (w, h) = DocumentExportEngine.resolvePageDimensions(1000, 1500, options)

        assertEquals("A4 width in portrait must be 595pt", 595, w)
        assertEquals("A4 height in portrait must be 842pt", 842, h)
    }

    @Test
    fun `test resolvePageDimensions for A4 landscape`() {
        val options = DocumentExportEngine.ExportOptions(
            paperSize = DocumentExportEngine.PaperSize.A4,
            orientation = DocumentExportEngine.Orientation.LANDSCAPE
        )
        val (w, h) = DocumentExportEngine.resolvePageDimensions(1000, 1500, options)

        assertEquals("A4 width in landscape must be 842pt", 842, w)
        assertEquals("A4 height in landscape must be 595pt", 595, h)
    }

    @Test
    fun `test resolvePageDimensions for auto orientation`() {
        val options = DocumentExportEngine.ExportOptions(
            paperSize = DocumentExportEngine.PaperSize.A4,
            orientation = DocumentExportEngine.Orientation.AUTO
        )

        // Landscape image (width > height)
        val (landW, landH) = DocumentExportEngine.resolvePageDimensions(1600, 900, options)
        assertEquals("Landscape image must produce landscape page width", 842, landW)
        assertEquals("Landscape image must produce landscape page height", 595, landH)

        // Portrait image (width < height)
        val (portW, portH) = DocumentExportEngine.resolvePageDimensions(900, 1600, options)
        assertEquals("Portrait image must produce portrait page width", 595, portW)
        assertEquals("Portrait image must produce portrait page height", 842, portH)
    }

    @Test
    fun `test resolvePageDimensions for original image dimensions`() {
        val options = DocumentExportEngine.ExportOptions(
            paperSize = DocumentExportEngine.PaperSize.ORIGINAL_IMAGE
        )
        val (w, h) = DocumentExportEngine.resolvePageDimensions(1920, 1080, options)

        assertEquals("Original image paper size must preserve bitmap width", 1920, w)
        assertEquals("Original image paper size must preserve bitmap height", 1080, h)
    }

    @Test
    fun `test paper sizes and margin points sanity`() {
        assertEquals(595, DocumentExportEngine.PaperSize.A4.widthPt)
        assertEquals(842, DocumentExportEngine.PaperSize.A4.heightPt)
        assertEquals(612, DocumentExportEngine.PaperSize.LETTER.widthPt)
        assertEquals(792, DocumentExportEngine.PaperSize.LETTER.heightPt)

        assertEquals(0, DocumentExportEngine.Margin.NONE.marginPt)
        assertEquals(18, DocumentExportEngine.Margin.COMPACT.marginPt)
        assertEquals(36, DocumentExportEngine.Margin.NORMAL.marginPt)
        assertEquals(72, DocumentExportEngine.Margin.WIDE.marginPt)
    }

    @Test
    fun `test export quality compression levels`() {
        assertEquals(100, DocumentExportEngine.ExportQuality.ORIGINAL.jpegQuality)
        assertEquals(95, DocumentExportEngine.ExportQuality.HIGH.jpegQuality)
        assertEquals(80, DocumentExportEngine.ExportQuality.MEDIUM.jpegQuality)
        assertEquals(60, DocumentExportEngine.ExportQuality.LOW.jpegQuality)
    }

    @Test
    fun `test export image formats and extensions`() {
        assertEquals("jpg", DocumentExportEngine.ImageFormat.JPEG.extension)
        assertEquals("png", DocumentExportEngine.ImageFormat.PNG.extension)
        assertEquals("webp", DocumentExportEngine.ImageFormat.WEBP.extension)
        assertEquals("image/jpeg", DocumentExportEngine.ImageFormat.JPEG.mimeType)
        assertEquals("image/png", DocumentExportEngine.ImageFormat.PNG.mimeType)
        assertEquals("image/webp", DocumentExportEngine.ImageFormat.WEBP.mimeType)
    }

    @Test
    fun `image zip streams each page with valid decodable entries`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val pages = (0 until 4).map { index ->
            Bitmap.createBitmap(24 + index, 18 + index, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index % 2 == 0) Color.RED else Color.BLUE)
            }
        }
        val session = DocumentSession(title = "zip_stream_${UUID.randomUUID()}")
        session.addPages(pages.map { ScannedPage(processedBitmapCache = it) })
        var exported: java.io.File? = null
        try {
            val result = DocumentExportEngine.exportDocument(
                context, session,
                DocumentExportEngine.ExportOptions(
                    format = DocumentExportEngine.OutputFormat.IMAGES_ZIP,
                    imageFormat = DocumentExportEngine.ImageFormat.PNG
                )
            )
            exported = result.file
            assertEquals(4, result.pageCount)
            ZipFile(result.file).use { zip ->
                val entries = java.util.Collections.list(zip.entries())
                assertEquals(4, entries.size)
                entries.forEachIndexed { index, entry ->
                    assertEquals("Page_${index + 1}.png", entry.name)
                    val encoded = zip.getInputStream(entry).use { it.readBytes() }
                    assertTrue(encoded.size > 8)
                    val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                    assertTrue("Invalid PNG at page ${index + 1}", decoded != null)
                    assertEquals(24 + index, decoded!!.width)
                    assertEquals(18 + index, decoded.height)
                    decoded.recycle()
                }
            }
        } finally {
            exported?.delete()
            pages.forEach { it.recycle() }
        }
    }

    @Test
    fun `failed zip page removes incomplete archive instead of reporting success`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val validBitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val name = "zip_missing_${UUID.randomUUID()}"
        val session = DocumentSession(title = name)
        session.addPage(ScannedPage(processedBitmapCache = validBitmap))
        session.addPage(ScannedPage(originalImagePath = "/nonexistent/veilframe-page.png"))
        val exportDir = java.io.File(context.filesDir, "exports")
        try {
            try {
                DocumentExportEngine.exportDocument(
                    context, session,
                    DocumentExportEngine.ExportOptions(format = DocumentExportEngine.OutputFormat.IMAGES_ZIP)
                )
                fail("An undecodable page must not produce an apparently valid ZIP")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("page 2") == true)
            }
            assertFalse(exportDir.listFiles().orEmpty().any { it.name.startsWith(name) })
        } finally {
            validBitmap.recycle()
        }
    }

}
