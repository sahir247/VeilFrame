package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PdfActiveContentSanitizerTest {
    private fun n(value: String) = COSName.getPDFName(value)
    @Test fun removesNestedActionsAndFileAttachmentsWithoutLosingNavigation() {
        val bytes = PDDocument().use { document ->
            val page = PDPage(); document.addPage(page)
            val action = COSDictionary().apply {
                setItem(n("S"), n("JavaScript")); setString(n("JS"), "app.alert('test')")
            }
            val next = COSDictionary().apply {
                setItem(n("S"), n("GoTo")); setItem(n("Next"), action)
            }
            document.documentCatalog.cosObject.setItem(n("OpenAction"), next)
            page.cosObject.setItem(n("AA"), COSDictionary().apply { setItem(n("O"), action) })
            page.cosObject.setItem(n("Annots"), COSArray().apply {
                add(COSDictionary().apply { setItem(n("Subtype"), n("FileAttachment")) })
                add(COSDictionary().apply { setItem(n("Subtype"), n("Text")) })
            })
            PdfActiveContentSanitizer.remove(document, javascript = true, attachments = true)
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        PDDocument.load(bytes).use { document ->
            val open = document.documentCatalog.cosObject.getDictionaryObject(n("OpenAction")) as COSDictionary
            assertEquals(n("GoTo"), open.getDictionaryObject(n("S")))
            assertNull(open.getItem(n("Next")))
            val page = document.getPage(0)
            val aa = page.cosObject.getDictionaryObject(n("AA")) as COSDictionary
            assertNull(aa.getItem(n("O")))
            val annotations = page.cosObject.getDictionaryObject(n("Annots")) as COSArray
            assertEquals(1, annotations.size())
        }
    }
}
