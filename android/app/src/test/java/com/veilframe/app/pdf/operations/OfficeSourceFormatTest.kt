package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OfficeSourceFormatTest {
    @Test fun onlyImplementedFormatsAreAdmitted() {
        assertEquals(OfficeSourceFormat.DOCX, OfficeSourceFormat.fromName("report.DOCX"))
        assertEquals(OfficeSourceFormat.XLSX, OfficeSourceFormat.fromName("budget.xlsx"))
        assertEquals(OfficeSourceFormat.PPTX, OfficeSourceFormat.fromName("slides.pptx"))
        assertEquals(OfficeSourceFormat.TEXT, OfficeSourceFormat.fromName("notes.log"))
        for (name in listOf("legacy.doc", "legacy.xls", "legacy.ppt", "formatted.rtf", "wrong.pdf", "unknown")) {
            assertThrows(IOException::class.java) { OfficeSourceFormat.fromName(name) }
        }
    }
}
