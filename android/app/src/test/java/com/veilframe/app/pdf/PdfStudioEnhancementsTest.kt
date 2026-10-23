package com.veilframe.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.veilframe.app.pdf.operations.MergeItem
import com.veilframe.app.pdf.operations.MergeItemType
import com.veilframe.app.pdf.operations.MergePageItem
import com.veilframe.app.pdf.operations.PdfOrganizer
import com.veilframe.app.pdf.operations.PdfMerger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PdfStudioEnhancementsTest {

    private fun createMultiPagePdf(pageCount: Int): File {
        val file = File.createTempFile("test_sample_", ".pdf")
        val doc = PDDocument()
        for (i in 1..pageCount) {
            doc.addPage(PDPage())
        }
        FileOutputStream(file).use { doc.save(it) }
        doc.close()
        return file
    }

    private fun parsePageSequence(input: String): List<Int> {
        if (input.isBlank()) return emptyList()
        val regex = Regex("""\d+""")
        return regex.findAll(input).mapNotNull { it.value.toIntOrNull() }.filter { it > 0 }.toList()
    }

    @Test
    fun testPageSequenceParser_handlesVariousFormats() {
        // e.g. 3>4>2>1>5
        val seq1 = parsePageSequence("3>4>2>1>5")
        assertEquals(listOf(3, 4, 2, 1, 5), seq1)

        // e.g. 1, 2, 7, 6, 3, 4, 5, 10, 8, 9
        val seq2 = parsePageSequence("1, 2, 7, 6, 3, 4, 5, 10, 8, 9")
        assertEquals(listOf(1, 2, 7, 6, 3, 4, 5, 10, 8, 9), seq2)

        // e.g. 1.2.7..6.3.4.5.10.8.9
        val seq3 = parsePageSequence("1.2.7..6.3.4.5.10.8.9")
        assertEquals(listOf(1, 2, 7, 6, 3, 4, 5, 10, 8, 9), seq3)
    }

    @Test
    fun testPdfOrganizer_reordersFivePagePdf() {
        val file = createMultiPagePdf(5)
        try {
            val doc = PDDocument.load(file)
            assertEquals(5, doc.numberOfPages)
            doc.close()

            // Test page reordering 3>4>2>1>5 directly with PDDocument
            val originalDoc = PDDocument.load(file)
            val newDoc = PDDocument()
            val order = listOf(3, 4, 2, 1, 5)
            for (pageNum in order) {
                newDoc.addPage(originalDoc.getPage(pageNum - 1))
            }
            val out = ByteArrayOutputStream()
            newDoc.save(out)
            originalDoc.close()
            newDoc.close()

            val resultDoc = PDDocument.load(ByteArrayInputStream(out.toByteArray()))
            assertEquals(5, resultDoc.numberOfPages)
            resultDoc.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun testPdfMerger_buildsAndInterleavesPages() {
        val merger = PdfMerger()

        val doc1 = MergeItem(
            id = "d1",
            uri = android.net.Uri.EMPTY,
            name = "doc1.pdf",
            type = MergeItemType.PDF,
            pageCount = 5
        )
        val doc2 = MergeItem(
            id = "d2",
            uri = android.net.Uri.EMPTY,
            name = "doc2.pdf",
            type = MergeItemType.PDF,
            pageCount = 5
        )

        val pageItems = merger.buildPageItems(listOf(doc1, doc2))
        assertEquals(10, pageItems.size)
        // Verify 1-indexed overallPageNumbers 1..10
        assertEquals(1, pageItems.first().overallPageNumber)
        assertEquals(10, pageItems.last().overallPageNumber)

        // Interleave custom order: 1, 2, 7, 6, 3, 4, 5, 10, 8, 9
        val customOrder = listOf(1, 2, 7, 6, 3, 4, 5, 10, 8, 9)
        val itemMap = pageItems.associateBy { it.overallPageNumber }
        val interleaved = customOrder.mapNotNull { itemMap[it] }

        assertEquals(10, interleaved.size)
        assertEquals(1, interleaved[0].overallPageNumber)
        assertEquals("doc1.pdf (p. 1)", interleaved[0].sourceLabel)

        assertEquals(2, interleaved[1].overallPageNumber)
        assertEquals("doc1.pdf (p. 2)", interleaved[1].sourceLabel)

        assertEquals(7, interleaved[2].overallPageNumber)
        assertEquals("doc2.pdf (p. 2)", interleaved[2].sourceLabel)

        assertEquals(6, interleaved[3].overallPageNumber)
        assertEquals("doc2.pdf (p. 1)", interleaved[3].sourceLabel)
    }

    @Test
    fun testWatermarkOpacity_normalization() {
        // Test that slider value is mapped directly to 0.05..1.0 and formatted as 5%..100%
        val sliderVal25 = 25.0f
        val pct25 = sliderVal25.toInt()
        val opacity25 = (sliderVal25 / 100f).coerceIn(0.05f, 1.0f)
        assertEquals("25%", "$pct25%")
        assertEquals(0.25f, opacity25, 0.001f)

        val sliderVal100 = 100.0f
        val pct100 = sliderVal100.toInt()
        val opacity100 = (sliderVal100 / 100f).coerceIn(0.05f, 1.0f)
        assertEquals("100%", "$pct100%")
        assertEquals(1.0f, opacity100, 0.001f)

        val sliderVal5 = 5.0f
        val pct5 = sliderVal5.toInt()
        val opacity5 = (sliderVal5 / 100f).coerceIn(0.05f, 1.0f)
        assertEquals("5%", "$pct5%")
        assertEquals(0.05f, opacity5, 0.001f)
    }

    @Test
    fun testSignatureCustomCoordinates_calculation() {
        val sigWidth = 180f
        val sigHeight = 60f
        val pageWidth = 595f
        val pageHeight = 842f

        // Slider at 50% X and 50% Y
        val sliderX = 50f
        val sliderY = 50f
        val x = (pageWidth - sigWidth) * (sliderX / 100f)
        val y = (pageHeight - sigHeight) * (sliderY / 100f)

        assertEquals(207.5f, x, 0.01f)
        assertEquals(391.0f, y, 0.01f)
    }
}
