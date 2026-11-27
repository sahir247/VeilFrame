package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class PdfVerifiedExportTest {
    @Test
    fun rejectedVerificationNeverOpensDestination() = runBlocking {
        PDDocument().use { document ->
            document.addPage(PDPage())
            var opened = false
            try {
                exportVerifiedPdf(RuntimeEnvironment.getApplication(), document, 1, verify = {
                    throw IOException("rejected")
                }) { opened = true; ByteArrayOutputStream() }
                fail("Expected verification failure")
            } catch (e: IOException) {
                assertEquals("rejected", e.message)
            }
            assertFalse(opened)
        }
    }

    @Test
    fun rotatedCropPlacementMapsImageCorners() {
        val crop = PDRectangle(10f, 20f, 100f, 200f)
        val expected = listOf(10f to 20f, 110f to 20f, 110f to 220f, 10f to 220f)
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val matrix = rasterPlacement(crop, rotation)
            val p = matrix.transformPoint(0f, 0f)
            assertEquals(expected[index].first, p.x, 0.001f)
            assertEquals(expected[index].second, p.y, 0.001f)
            val opposite = matrix.transformPoint(1f, 1f)
            assertEquals(expected[(index + 2) % 4].first, opposite.x, 0.001f)
            assertEquals(expected[(index + 2) % 4].second, opposite.y, 0.001f)
        }
    }

    @Test
    fun embeddedLatinUnicodeSurvivesSerialization() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val input = "café naïve Ångström €"
        val bytes = PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val (font, _) = SearchableOcrFonts(context, document).select(input)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(30f, 100f)
                stream.showText(input)
                stream.endText()
            }
            ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
        }
        PDDocument.load(bytes).use {
            assertEquals(input, PDFTextStripper().getText(it).trim())
            assertArrayEquals(digestOcrWords(sequenceOf(input)), digestSerializedOcrLayer(it.getPage(0)))
            assertFalse(digestOcrWords(sequenceOf("cafe naive Angstrom €")).contentEquals(digestSerializedOcrLayer(it.getPage(0))))
        }
    }

    @Test
    fun metadataMarkerAbsentFromFullRewrite() {
        val source = PDDocument().use { document ->
            document.addPage(PDPage())
            document.documentInformation.author = "VeilFrameUniquePrivateAuthor_4719"
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        assertTrue(String(source, Charsets.ISO_8859_1).contains("VeilFrameUniquePrivateAuthor_4719"))
        val saved = PDDocument.load(source).use { document ->
            stripPdfMetadata(document)
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
        assertFalse(String(saved, Charsets.ISO_8859_1).contains("VeilFrameUniquePrivateAuthor_4719"))
        PDDocument.load(saved).use(::verifyStrippedPdfMetadata)
    }
}
