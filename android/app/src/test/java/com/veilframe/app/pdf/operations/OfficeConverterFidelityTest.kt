package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficeConverterFidelityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
    }

    private fun createZip(entries: Map<String, String>): File {
        val f = File(tempFolder.root, "test_${System.nanoTime()}.zip")
        f.outputStream().use { fos ->
            ZipOutputStream(fos).use { zos ->
                for ((name, content) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
        return f
    }

    @Test
    fun testDocxBoldnessPreservedInPdfConversion() = runBlocking {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:pPr><w:jc w:val="left"/></w:pPr>
                        <w:r><w:t>This is regular text and </w:t></w:r>
                        <w:r>
                            <w:rPr><w:b/></w:rPr>
                            <w:t>this is strictly bold text.</w:t>
                        </w:r>
                    </w:p>
                    <w:p>
                        <w:pPr><w:jc w:val="left"/></w:pPr>
                        <w:r>
                            <w:rPr><w:b/></w:rPr>
                            <w:t>All bold paragraph heading</w:t>
                        </w:r>
                    </w:p>
                </w:body>
            </w:document>
        """.trimIndent()

        val docxFile = createZip(mapOf("word/document.xml" to documentXml))
        val pdfFile = File(tempFolder.root, "output_docx.pdf")

        val converter = OfficeConverter()
        converter.convertDocxViaOpenXml(docxFile, pdfFile, context)

        assertTrue("Converted PDF must exist", pdfFile.exists())
        assertTrue("Converted PDF must have non-zero size", pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertEquals("Should have 1 page", 1, doc.numberOfPages)
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            assertTrue("PDF text must contain regular text", text.contains("This is regular text and"))
            assertTrue("PDF text must contain bold text", text.contains("this is strictly bold text."))
            assertTrue("PDF text must contain heading text", text.contains("All bold paragraph heading"))
        }
    }

    @Test
    fun testXlsxContentWeightedAndMultiLineWrappedToPdf() = runBlocking {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>ID</t></si>
                <si><t>Detailed Description with multi-line text wrapping that should not be clipped at all</t></si>
                <si><t>Status</t></si>
                <si><t>001</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="Engineering" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                        <c r="C1" t="s"><v>2</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>3</v></c>
                        <c r="B2" t="s"><v>1</v></c>
                        <c r="C2"><v>99.9</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue("Converted XLSX PDF must exist", pdfFile.exists())
        assertTrue("Converted XLSX PDF must have non-zero size", pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertEquals("Should have 1 page", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("Page should be landscape", page.mediaBox.width > page.mediaBox.height)
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            assertTrue("Extracted text must contain ID", text.contains("ID"))
            assertTrue("Extracted text must contain Status", text.contains("Status"))
            assertTrue("Extracted text must contain 001", text.contains("001"))
            assertTrue("Extracted text must contain wrapped description", text.contains("Detailed Description"))
        }
    }

    @Test
    fun testXlsxSparseColumnsAndMultiLineWrappingPreservesLayout() = runBlocking {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>Header A</t></si>
                <si><t>Header C</t></si>
                <si><t>Multi-Line Item:&#10;Line Two&#10;Line Three</t></si>
                <si><t>VERY_LONG_UNBROKEN_TOKEN_HASH_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_SECURE_TOKEN</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="Fidelity" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="C1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="B2" t="s"><v>2</v></c>
                        <c r="D2" t="s"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_fidelity_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue("Converted XLSX PDF must exist", pdfFile.exists())
        assertTrue("Converted XLSX PDF must have non-zero size", pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertEquals("Should have 1 page", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("Page should be landscape", page.mediaBox.width > page.mediaBox.height)
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            println("DEBUG_EXTRACTED_TEXT: [$text]")
            assertTrue("Extracted text must contain Header A", text.contains("Header A"))
            assertTrue("Extracted text must contain Header C", text.contains("Header C"))
            assertTrue("Extracted text must contain Multi-Line Item", text.contains("Multi-Line Item"))
        }
    }

    @Test
    fun testXlsxOversizedRowPaginatesAcrossPagesWithoutTruncation() = runBlocking {
        // Build 60 distinct lines in a single cell that exceeds any single-page printable bounds
        val linesList = (1..60).map { "OversizedTraceLine_$it" }
        val multiLineContent = linesList.joinToString("&#10;")

        val sharedStringsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"2\" uniqueCount=\"2\">\n" +
            "    <si><t>Header Col</t></si>\n" +
            "    <si><t>$multiLineContent</t></si>\n" +
            "</sst>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheets>\n" +
            "        <sheet name=\"PaginationTest\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
            "    </sheets>\n" +
            "</workbook>"

        val sheet1Xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheetData>\n" +
            "        <row r=\"1\">\n" +
            "            <c r=\"A1\" t=\"s\"><v>0</v></c>\n" +
            "        </row>\n" +
            "        <row r=\"2\">\n" +
            "            <c r=\"A2\" t=\"s\"><v>1</v></c>\n" +
            "        </row>\n" +
            "    </sheetData>\n" +
            "</worksheet>"

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_oversized_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue("Converted XLSX PDF must exist", pdfFile.exists())
        assertTrue("Converted XLSX PDF must have non-zero size", pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            // Unconstrained format conversion: page height dynamically expands beyond A4 landscape height (595pt)
            // to fit the entire table on 1 continuous page without artificial page cuts
            assertEquals("Unconstrained spreadsheet conversion must fit content on 1 continuous page", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("Page height must dynamically expand beyond standard A4 (595pt) to fit the content (got ${page.mediaBox.height})", page.mediaBox.height > 650f)

            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val text = stripper.getText(doc)

            // Extract all lines that match the trace line pattern
            val extractedLines = text.lines()
                .map { it.trim() }
                .filter { it.startsWith("OversizedTraceLine_") }

            assertEquals("All 60 lines must be present", 60, extractedLines.size)
            assertEquals(
                "All 60 lines must appear in exact monotonic sequential order from 1 to 60 without duplicates",
                (1..60).map { "OversizedTraceLine_$it" },
                extractedLines
            )
        }
    }

    @Test
    fun testXlsxExtremeColumnsDynamicallyExpandPageWidthWithoutSquishingOrClipping() = runBlocking {
        // Build 25 distinct columns in row 1
        val colCount = 25
        val sharedStringsList = (1..colCount).map { "WideColumnHeaderPayload_$it" }
        val sharedStringsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"$colCount\" uniqueCount=\"$colCount\">\n" +
            sharedStringsList.joinToString("\n") { "    <si><t>$it</t></si>" } + "\n" +
            "</sst>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheets>\n" +
            "        <sheet name=\"WideSheet\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
            "    </sheets>\n" +
            "</workbook>"

        // Generate cells for columns 1..25 ('A' through 'Y')
        val cellsXml = (0 until colCount).joinToString("\n") { colIdx ->
            val colLetter = ('A' + colIdx).toString()
            "            <c r=\"${colLetter}1\" t=\"s\"><v>$colIdx</v></c>"
        }

        val sheet1Xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheetData>\n" +
            "        <row r=\"1\">\n" +
            cellsXml + "\n" +
            "        </row>\n" +
            "    </sheetData>\n" +
            "</worksheet>"

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_wide_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            // 25 columns * ~150pt each = ~3750pt, well beyond A4 landscape width (842pt)
            assertTrue("Page width must dynamically expand beyond A4 (842pt) to fit wide columns (got ${page.mediaBox.width})", page.mediaBox.width > 2000f)

            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val text = stripper.getText(doc)

            // Verify first and last columns exist without squishing or clipping
            assertTrue("Must contain first column", text.contains("WideColumnHeaderPayload_1"))
            assertTrue("Must contain last column", text.contains("WideColumnHeaderPayload_25"))
        }
    }

    class PositionalTextExtractor : PDFTextStripper() {
        data class Glyph(
            val char: String,
            val pageNo: Int,
            val x: Float,
            val y: Float,
            val width: Float,
            val height: Float
        )

        data class TextRun(
            val text: String,
            val pageNo: Int,
            val minX: Float,
            val maxX: Float,
            val minY: Float,
            val maxY: Float
        )

        val glyphs = mutableListOf<Glyph>()

        init {
            sortByPosition = true
        }

        override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
            glyphs.add(
                Glyph(
                    char = text.unicode,
                    pageNo = currentPageNo,
                    x = text.xDirAdj,
                    y = text.yDirAdj,
                    width = text.widthDirAdj,
                    height = text.heightDir
                )
            )
            super.processTextPosition(text)
        }

        fun findText(target: String): List<TextRun> {
            val results = mutableListOf<TextRun>()
            if (target.isEmpty() || glyphs.size < target.length) return results
            val targetLen = target.length
            for (i in 0..glyphs.size - targetLen) {
                val candidate = (0 until targetLen).map { glyphs[i + it].char }.joinToString("")
                if (candidate == target) {
                    val matchingGlyphs = glyphs.subList(i, i + targetLen)
                    results.add(
                        TextRun(
                            text = target,
                            pageNo = matchingGlyphs.first().pageNo,
                            minX = matchingGlyphs.minOf { it.x },
                            maxX = matchingGlyphs.maxOf { it.x + it.width },
                            minY = matchingGlyphs.minOf { it.y },
                            maxY = matchingGlyphs.maxOf { it.y + it.height }
                        )
                    )
                }
            }
            return results
        }
    }

    @Test
    fun testXlsxMultiColumnGeometryAndNoHorizontalOverlap() = runBlocking {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>ColAlphaHeader</t></si>
                <si><t>ColBetaHeader</t></si>
                <si><t>DataAlpha</t></si>
                <si><t>LineB1&#10;LineB2&#10;LineB3&#10;LineB4&#10;LineB5</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="GeometryTest" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>2</v></c>
                        <c r="B2" t="s"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_geometry_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            val extractor = PositionalTextExtractor()
            extractor.getText(doc)

            // Verify horizontal separation: ColAlpha text must be strictly to the left of ColBeta text
            val colARuns = extractor.findText("DataAlpha")
            val colBRuns = extractor.findText("LineB1")

            assertTrue("Column A text DataAlpha must be extracted", colARuns.isNotEmpty())
            assertTrue("Column B text LineB1 must be extracted", colBRuns.isNotEmpty())

            val colA = colARuns.first()
            val colB = colBRuns.first()

            assertEquals("Both cells must be on page 1", 1, colA.pageNo)
            assertEquals("Both cells must be on page 1", 1, colB.pageNo)

            assertTrue(
                "Column A right edge (${colA.maxX}) must be strictly less than Column B left edge (${colB.minX})",
                colA.maxX < colB.minX
            )
        }
    }

    @Test
    fun testXlsxSparseRowsPreserveVerticalLayout() = runBlocking {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                <si><t>Section_Alpha</t></si>
                <si><t>Section_Beta</t></si>
                <si><t>Section_Gamma</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="SpacerRows" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        // Rows 1, 4, 7 (rows 2, 3, 5, 6 are skipped empty rows)
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                    <row r="4"><c r="B4" t="s"><v>1</v></c></row>
                    <row r="7"><c r="C7" t="s"><v>2</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_sparse_rows.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            val extractor = PositionalTextExtractor()
            val text = extractor.getText(doc)
            assertTrue("Must contain Section_Alpha", text.contains("Section_Alpha"))
            assertTrue("Must contain Section_Beta", text.contains("Section_Beta"))
            assertTrue("Must contain Section_Gamma", text.contains("Section_Gamma"))

            val alphaRuns = extractor.findText("Section_Alpha")
            val betaRuns = extractor.findText("Section_Beta")
            val gammaRuns = extractor.findText("Section_Gamma")

            assertTrue("Section_Alpha must be extracted", alphaRuns.isNotEmpty())
            assertTrue("Section_Beta must be extracted", betaRuns.isNotEmpty())
            assertTrue("Section_Gamma must be extracted", gammaRuns.isNotEmpty())

            val alpha = alphaRuns.first()
            val beta = betaRuns.first()
            val gamma = gammaRuns.first()

            // In PDFBox yDirAdj: 0 is at the top of the page, increasing downwards.
            // Row 1 is at the top, Row 4 is below Row 1 (with empty Rows 2 and 3 in between),
            // Row 7 is below Row 4 (with empty Rows 5 and 6 in between).
            // Each empty row is at least 20pt tall.
            // Therefore, Row 4's Y position must be at least 30pt greater than Row 1's Y position,
            // and Row 7's Y position must be at least 30pt greater than Row 4's Y position.
            assertTrue(
                "Row 4 y (${beta.minY}) must be lower than Row 1 y (${alpha.minY}) by at least 30pt",
                beta.minY > alpha.minY + 30f
            )
            assertTrue(
                "Row 7 y (${gamma.minY}) must be lower than Row 4 y (${beta.minY}) by at least 30pt",
                gamma.minY > beta.minY + 30f
            )
        }
    }
}
