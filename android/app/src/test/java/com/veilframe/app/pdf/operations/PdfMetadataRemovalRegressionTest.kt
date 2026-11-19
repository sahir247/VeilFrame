package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfMetadataRemovalRegressionTest {
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
