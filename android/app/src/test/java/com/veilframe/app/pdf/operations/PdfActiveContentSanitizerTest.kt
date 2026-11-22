package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PdfActiveContentSanitizerTest {
    private fun n(value: String) = COSName.getPDFName(value)
    @Test fun loadedRewriteRemovesScriptSentinelAndPreservesNameTreePairs() {
        val marker = "VeilFramePrivateScriptPayload_37601"
        val source = PDDocument().use { document ->
            document.addPage(PDPage())
            val names = COSDictionary().apply {
                setItem(n("Names"), COSArray().apply {
                    add(com.tom_roush.pdfbox.cos.COSString("a-script"))
                    add(COSDictionary().apply { setItem(n("S"), n("JavaScript")); setString(n("JS"), marker) })
                    add(com.tom_roush.pdfbox.cos.COSString("b-keep"))
                    add(COSDictionary().apply { setString(n("Value"), "retained") })
                })
            }
            document.documentCatalog.cosObject.setItem(n("Names"), COSDictionary().apply { setItem(n("Custom"), names) })
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        assertTrue(String(source, Charsets.ISO_8859_1).contains(marker))
        val saved = PDDocument.load(source).use { document ->
            PdfActiveContentSanitizer.remove(document, javascript = true, attachments = false)
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        assertFalse(String(saved, Charsets.ISO_8859_1).contains(marker))
        PDDocument.load(saved).use { document ->
            PdfActiveContentSanitizer.verify(document, javascript = true, attachments = false)
            val root = document.documentCatalog.cosObject.getDictionaryObject(n("Names")) as COSDictionary
            val leaf = root.getDictionaryObject(n("Custom")) as COSDictionary
            val pairs = leaf.getDictionaryObject(n("Names")) as COSArray
            assertEquals(2, pairs.size())
            assertEquals("b-keep", (pairs.getObject(0) as com.tom_roush.pdfbox.cos.COSString).string)
        }
    }

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

    @Test
    fun maliciouslyLargeReachableCosGraphFailsClosedAtExplicitBudget() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val entries = COSArray()
            repeat(40) { index ->
                entries.add(COSDictionary().apply {
                    setString(n("Evidence"), "entry-$index")
                })
            }
            document.documentCatalog.cosObject.setItem(n("AdversarialEntries"), entries)
            try {
                PdfActiveContentSanitizer.verify(document, javascript = true, attachments = false, maxVisitedNodes = 12)
                fail("A bounded sanitizer must reject unsupported object graphs")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("safety budget") == true)
            }
        }
    }

    @Test
    fun selfReferentialCosDictionaryDoesNotLoopForever() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val cyclic = COSDictionary()
            cyclic.setItem(n("Self"), cyclic)
            document.documentCatalog.cosObject.setItem(n("CyclicRef"), cyclic)
            PdfActiveContentSanitizer.verify(document, javascript = true, attachments = false)
        }
    }
}
