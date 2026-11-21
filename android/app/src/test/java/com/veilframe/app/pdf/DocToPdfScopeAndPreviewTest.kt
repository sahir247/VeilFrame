package com.veilframe.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying picker scoping, supported office document MIME types,
 * and preview metadata properties for DOC to PDF conversion.
 */
class DocToPdfScopeAndPreviewTest {

    @Test
    fun docMimeTypes_matchImplementedOfficeAndPlainTextFormatsExactly() {
        // Legacy DOC/XLS/PPT and RTF are deliberately unsupported; the picker
        // must not advertise conversions the engine cannot perform.
        val expected = setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain"
        )
        val types = PdfQuickToolController.DOC_MIME_TYPES
        assertEquals("Picker must expose exactly the implemented formats", expected, types.toSet())
        assertEquals("Picker MIME types must not contain duplicates", expected.size, types.size)
    }

    @Test
    fun quickMode_docToPdf_hasCorrectTitleAndButtonText() {
        val mode = PdfQuickToolController.QuickMode.DOC_TO_PDF
        assertTrue(mode.title.contains("DOC to PDF", ignoreCase = true))
        assertTrue(mode.buttonText.contains("Convert", ignoreCase = true))
    }

    @Test
    fun testExtractDocxTextViaZip_extractsParagraphsFromOpenXml() {
        val tempDocx = java.io.File.createTempFile("test_sample", ".docx")
        try {
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(tempDocx)).use { zos ->
                val entry = java.util.zip.ZipEntry("word/document.xml")
                zos.putNextEntry(entry)
                val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>
                        <w:p><w:r><w:t>VeilFrame Forensic Report</w:t></w:r></w:p>
                        <w:p><w:r><w:t>Paragraph 2 with details &amp; evidence</w:t></w:r></w:p>
                    </w:body>
                </w:document>""".trimIndent()
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val converter = com.veilframe.app.pdf.operations.OfficeConverter()
            val text = converter.extractDocxTextViaZip(tempDocx)
            assertTrue("Must contain first paragraph", text.contains("VeilFrame Forensic Report"))
            assertTrue("Must contain unescaped text", text.contains("Paragraph 2 with details & evidence"))
        } finally {
            tempDocx.delete()
        }
    }

    @Test
    fun testExtractDocxTextViaZip_corruptFileFailsGracefullyWithoutThrowing() {
        val tempCorrupt = java.io.File.createTempFile("corrupt_doc", ".docx")
        try {
            tempCorrupt.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
            val converter = com.veilframe.app.pdf.operations.OfficeConverter()
            val text = converter.extractDocxTextViaZip(tempCorrupt)
            assertTrue("Should return empty string for corrupt file", text.isEmpty())
        } finally {
            tempCorrupt.delete()
        }
    }

    @Test
    fun testDocxPreviewParagraphFormatting_splitsMultipleParagraphsCleanly() {
        val tempDocx = java.io.File.createTempFile("test_multi_para", ".docx")
        try {
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(tempDocx)).use { zos ->
                val entry = java.util.zip.ZipEntry("word/document.xml")
                zos.putNextEntry(entry)
                val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>
                        <w:p><w:r><w:t>Header: Monthly Summary</w:t></w:r></w:p>
                        <w:p><w:r><w:t>First section content with &quot;quoted metrics&quot; &amp; statistics.</w:t></w:r></w:p>
                        <w:p><w:r><w:t>Second section: conclusion &lt;final&gt;.</w:t></w:r></w:p>
                    </w:body>
                </w:document>""".trimIndent()
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val converter = com.veilframe.app.pdf.operations.OfficeConverter()
            val text = converter.extractDocxTextViaZip(tempDocx)
            val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }

            org.junit.Assert.assertEquals(3, paragraphs.size)
            assertTrue("First paragraph is header", paragraphs[0].contains("Header: Monthly Summary"))
            assertTrue("Entities unescaped in paragraph 2", paragraphs[1].contains("\"quoted metrics\" & statistics."))
            assertTrue("Entities unescaped in paragraph 3", paragraphs[2].contains("<final>"))
        } finally {
            tempDocx.delete()
        }
    }
}
