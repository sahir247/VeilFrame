package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfMetadataRemovalRegressionTest {
    @Test
    fun fullRewriteDoesNotRetainUncompressedMetadataPayload() {
        val marker = "VEILFRAME_PRIVATE_METADATA_SENTINEL_72519"
        val original = PDDocument().use { document ->
            document.addPage(PDPage())
            document.documentInformation.author = marker
            document.documentCatalog.metadata = PDMetadata(document).apply {
                cosObject.createOutputStream().use { it.write(marker.toByteArray(Charsets.US_ASCII)) }
            }
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        val sanitized = PDDocument.load(original).use { document ->
            stripPdfMetadata(document)
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        assertFalse(sanitized.toString(Charsets.ISO_8859_1).contains(marker))
        PDDocument.load(sanitized).use { document ->
            verifyStrippedPdfMetadata(document)
            assertEquals(1, document.numberOfPages)
        }
    }

    @Test
    fun clearsCatalogPageAndNestedResourceMetadataAfterSave() {
        val bytes = PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            document.documentInformation.author = "Private author"
            val resources = PDResources()
            page.resources = resources
            val privateEntry = COSName.getPDFName("PieceInfo")
            val nestedKey = COSName.getPDFName("AuditNested")
            val nested = COSDictionary()
            nested.setString(COSName.METADATA, "secret nested payload")
            nested.setString(privateEntry, "private application data")
            resources.cosObject.setItem(nestedKey, nested)
            document.documentCatalog.cosObject.setString(COSName.METADATA, "catalog XMP")
            page.cosObject.setString(COSName.METADATA, "page XMP")

            stripPdfMetadata(document)
            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
        PDDocument.load(bytes).use { result ->
            assertEquals(1, result.numberOfPages)
            assertNull(result.documentInformation.author)
            assertNull(result.documentCatalog.cosObject.getItem(COSName.METADATA))
            val page = result.getPage(0)
            assertNull(page.cosObject.getItem(COSName.METADATA))
            val nested = page.resources.cosObject.getDictionaryObject(COSName.getPDFName("AuditNested")) as COSDictionary
            assertNull(nested.getItem(COSName.METADATA))
            assertNull(nested.getItem(COSName.getPDFName("PieceInfo")))
        }
    }
}
