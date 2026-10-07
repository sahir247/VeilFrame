package com.veilframe.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DocumentExportEngine] options, dimensions math, orientation mapping,
 * and filename sanitization.
 */
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
        assertEquals(95, DocumentExportEngine.ExportQuality.HIGH.jpegQuality)
        assertEquals(80, DocumentExportEngine.ExportQuality.MEDIUM.jpegQuality)
        assertEquals(60, DocumentExportEngine.ExportQuality.LOW.jpegQuality)
    }
}
