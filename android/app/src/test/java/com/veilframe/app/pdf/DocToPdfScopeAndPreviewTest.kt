package com.veilframe.app.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying picker scoping, supported office document MIME types,
 * and preview metadata properties for DOC to PDF conversion.
 */
class DocToPdfScopeAndPreviewTest {

    @Test
    fun docMimeTypes_strictlyScopedToOfficeAndTextDocuments() {
        val types = PdfQuickToolController.DOC_MIME_TYPES

        // Must include DOCX, DOC, XLSX, XLS, PPTX, PPT, TXT, RTF
        assertTrue("Must include docx", types.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertTrue("Must include doc", types.contains("application/msword"))
        assertTrue("Must include xlsx", types.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertTrue("Must include xls", types.contains("application/vnd.ms-excel"))
        assertTrue("Must include pptx", types.contains("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertTrue("Must include ppt", types.contains("application/vnd.ms-powerpoint"))
        assertTrue("Must include txt", types.contains("text/plain"))
        assertTrue("Must include rtf", types.contains("application/rtf") || types.contains("text/rtf"))

        // Must NOT include wildcard */*, video/*, image/*, or pdf
        assertFalse("Must not include wildcard */*", types.contains("*/*"))
        assertFalse("Must not include video wildcard", types.any { it.startsWith("video/") })
        assertFalse("Must not include image wildcard", types.any { it.startsWith("image/") })
        assertFalse("Must not include application/pdf", types.contains("application/pdf"))
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
}
