package com.veilframe.app.pdf

import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.veilframe.app.pdf.operations.PageRange
import com.veilframe.app.pdf.operations.PdfMerger
import com.veilframe.app.pdf.operations.PdfOrganizer
import com.veilframe.app.pdf.operations.PdfSplitter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfImportedResourcesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source(): File {
        val file = temp.newFile("inherited.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 500f)
                stream.showText("Inherited font witness")
                stream.endText()
            }
            val parent = page.cosObject.getDictionaryObject(COSName.PARENT) as COSDictionary
            parent.setItem(COSName.RESOURCES, page.resources.cosObject)
            page.cosObject.removeItem(COSName.RESOURCES)
            doc.save(file)
        }
        PDDocument.load(file).use { doc ->
            assertFalse(doc.getPage(0).cosObject.containsKey(COSName.RESOURCES))
            assertNotNull(doc.getPage(0).resources)
            assertTrue(PDFTextStripper().getText(doc).contains("Inherited font witness"))
        }
        return file
    }

    private fun verify(bytes: ByteArray) {
        PDDocument.load(bytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertTrue(doc.getPage(0).cosObject.containsKey(COSName.RESOURCES))
            assertTrue(PDFTextStripper().getText(doc).contains("Inherited font witness"))
        }
    }

    @Test fun allPageImportRoutesPreserveInheritedFonts() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val uri = Uri.fromFile(source())
        val plan = PdfMergePagePlanner.build(context, listOf(uri)) { "inherited.pdf" }
        val merge = ByteArrayOutputStream()
        assertTrue(PdfMerger().mergePageItems(context, plan, merge).isSuccess)
        verify(merge.toByteArray())
        val reorder = ByteArrayOutputStream()
        assertTrue(PdfOrganizer().reorderPages(context, uri, reorder, listOf(1)).isSuccess)
        verify(reorder.toByteArray())
        val extract = ByteArrayOutputStream()
        assertTrue(PdfSplitter().extractPages(context, uri, listOf(1), extract).isSuccess)
        verify(extract.toByteArray())
        assertTrue(PdfSplitter().splitAllPages(context, uri, { _, input -> verify(input.readBytes()) }).isSuccess)
        assertTrue(PdfSplitter().splitByRanges(context, uri, listOf(PageRange(1, 1)),
            { _, input -> verify(input.readBytes()) }).isSuccess)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("vf_split_part_") })
    }

    @Test fun invalidLateRangeDoesNotPublishEarlierPart() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val uri = Uri.fromFile(source())
        var callbacks = 0
        val result = PdfSplitter().splitByRanges(context, uri, listOf(PageRange(1, 1), PageRange(2, 2)),
            { _, _ -> callbacks++ })
        assertTrue(result.isFailure)
        assertEquals(0, callbacks)
    }
}
