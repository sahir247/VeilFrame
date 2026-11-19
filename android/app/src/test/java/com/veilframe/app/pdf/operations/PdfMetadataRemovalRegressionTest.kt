package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfMetadataRemovalRegressionTest {
    @Test
    fun removesCatalogPageAndResourceMetadataAfterSave() {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            document.documentInformation.author = "Private author"
            val page = PDPage()
            page.resources = PDResources()
            document.addPage(page)
            document.documentCatalog.metadata = PDMetadata(document)
            page.cosObject.setItem(COSName.METADATA, PDMetadata(document).cosObject)
            page.resources.cosObject.setItem(COSName.METADATA, PDMetadata(document).cosObject)
            page.cosObject.setString(COSName.getPDFName("PieceInfo"), "Private data")
            PdfMetadataManager().stripMetadata(document)
            document.save(bytes)
        }
        PDDocument.load(bytes.toByteArray()).use { result ->
            assertEquals(1, result.numberOfPages)
            assertNull(result.documentInformation.author)
            assertNull(result.documentCatalog.metadata)
            assertNull(result.getPage(0).cosObject.getItem(COSName.METADATA))
            assertNull(result.getPage(0).resources.cosObject.getItem(COSName.METADATA))
            assertNull(result.getPage(0).cosObject.getItem(COSName.getPDFName("PieceInfo")))
        }
    }
}
