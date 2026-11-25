package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage

/**
 * PDFBox 2.0.27 importPage copies the content stream and resolved geometry,
 * but intentionally omits resources inherited from the source page tree.
 * The source document must remain open until the destination is saved.
 */
internal fun PDDocument.importPageWithResources(source: PDPage): PDPage {
    val resources = source.resources
    return importPage(source).also { imported ->
        if (resources != null) imported.resources = resources
    }
}
