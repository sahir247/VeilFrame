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
}
