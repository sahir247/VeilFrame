package com.veilframe.app.pdf.operations

import java.util.Locale
import java.io.IOException

/** Exactly the formats implemented by the conversion engine. No extension relabeling. */
internal enum class OfficeSourceFormat(val suffix: String, val label: String) {
    DOCX(".docx", "Word document"),
    XLSX(".xlsx", "Excel workbook"),
    PPTX(".pptx", "PowerPoint presentation"),
    TEXT(".txt", "Plain text");

    companion object {
        fun fromName(name: String): OfficeSourceFormat = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "docx" -> DOCX
            "xlsx" -> XLSX
            "pptx" -> PPTX
            "txt", "log" -> TEXT
            else -> throw IOException("Unsupported document format. Use DOCX, XLSX, PPTX or plain text. Convert legacy DOC/XLS/PPT or RTF first.")
        }
    }
}
