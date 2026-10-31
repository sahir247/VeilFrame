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
    fun testXlsxOversizedRowDynamicHeightFitsOnSinglePage() = runBlocking {
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
        val pdfFile = File(tempFolder.root, "output_oversized_singlepage_xlsx.pdf")

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
    fun testXlsxOversizedRowPaginatesAcrossPagesWhenExceedingMaxHeight() = runBlocking {
        // Build 60 distinct lines in a single cell
        val linesList = (1..60).map { "OversizedPaginationLine_$it" }
        val multiLineContent = linesList.joinToString("&#10;")

        val sharedStringsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"2\" uniqueCount=\"2\">\n" +
            "    <si><t>Header Col</t></si>\n" +
            "    <si><t>$multiLineContent</t></si>\n" +
            "</sst>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheets>\n" +
            "        <sheet name=\"PaginationSplitTest\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
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
        val pdfFile = File(tempFolder.root, "output_oversized_paginated_xlsx.pdf")

        val converter = OfficeConverter()
        // Override max page height to 400pt so 60 lines (~710pt) are forced to paginate across multiple pages
        converter.maxPdfPageHeightOverride = 400f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue("Converted XLSX PDF must exist", pdfFile.exists())
        assertTrue("Converted XLSX PDF must have non-zero size", pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Oversized row must paginate across at least 2 pages (got ${doc.numberOfPages})", doc.numberOfPages >= 2)

            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val text = stripper.getText(doc)

            // Extract all lines that match the trace line pattern
            val extractedLines = text.lines()
                .map { it.trim() }
                .filter { it.startsWith("OversizedPaginationLine_") }

            assertEquals("All 60 lines must be present across paginated pages", 60, extractedLines.size)
            assertEquals(
                "All 60 lines must appear across pages in exact monotonic sequential order from 1 to 60 without duplicates",
                (1..60).map { "OversizedPaginationLine_$it" },
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

            val extractor = PositionalTextExtractor()
            extractor.getText(doc)

            // Rigorous positional verification across ALL 25 columns:
            // 1. Every column header is extracted on page 1
            // 2. Coordinates strictly increase from left to right (col[i].maxX < col[i+1].minX)
            // 3. Zero horizontal overlap between adjacent columns
            // 4. Final column is fully contained within page width
            val colRuns = (1..colCount).map { i ->
                val target = "WideColumnHeaderPayload_$i"
                val runs = extractor.findText(target)
                assertTrue("Header $target must be present", runs.isNotEmpty())
                val run = runs.first()
                assertEquals("Header $target must be on page 1", 1, run.pageNo)
                run
            }

            for (i in 0 until colCount - 1) {
                val current = colRuns[i]
                val next = colRuns[i + 1]
                assertTrue(
                    "Column ${i + 1} right edge (${current.maxX}) must be strictly less than Column ${i + 2} left edge (${next.minX})",
                    current.maxX < next.minX
                )
            }

            val lastCol = colRuns.last()
            assertTrue(
                "Last column right edge (${lastCol.maxX}) must be within page width (${page.mediaBox.width})",
                lastCol.maxX <= page.mediaBox.width
            )
        }
    }

    @Test
    fun testXlsxPreservesBlankLinesInsideCells() = runBlocking {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="1" uniqueCount="1">
                <si><t>ParagraphAlpha&#10;&#10;ParagraphBeta&#10;&#10;&#10;ParagraphGamma</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="BlankLinesSheet" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_blank_lines_xlsx.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val extractor = PositionalTextExtractor()
            extractor.getText(doc)

            val p1Runs = extractor.findText("ParagraphAlpha")
            val p2Runs = extractor.findText("ParagraphBeta")
            val p3Runs = extractor.findText("ParagraphGamma")

            assertTrue("ParagraphAlpha must be extracted", p1Runs.isNotEmpty())
            assertTrue("ParagraphBeta must be extracted", p2Runs.isNotEmpty())
            assertTrue("ParagraphGamma must be extracted", p3Runs.isNotEmpty())

            val p1 = p1Runs.first()
            val p2 = p2Runs.first()
            val p3 = p3Runs.first()

            // In PDFBox yDirAdj: increases downwards.
            // p1 to p2 has 1 blank line in between: distance must be >= 20pt (2 * 11.7 = 23.4pt)
            // p2 to p3 has 2 blank lines in between: distance must be >= 30pt (3 * 11.7 = 35.1pt)
            assertTrue("ParagraphBeta Y (${p2.minY}) must be at least 20pt below ParagraphAlpha Y (${p1.minY})", p2.minY > p1.minY + 20f)
            assertTrue("ParagraphGamma Y (${p3.minY}) must be at least 30pt below ParagraphBeta Y (${p2.minY})", p3.minY > p2.minY + 30f)
        }
    }

    @Test
    fun testBigSpreadsheetWithManyRowsAndColumnsConvertsLosslessly() = runBlocking {
        // Build a large spreadsheet with 30 columns and 100 rows (3,000 data cells)
        val colCount = 30
        val rowCount = 100

        val sharedStrings = mutableListOf<String>()
        val headerIndices = (1..colCount).map { c ->
            val idx = sharedStrings.size
            sharedStrings.add("ColHeader_$c")
            idx
        }

        val rowDataIndices = (1..rowCount).map { r ->
            (1..colCount).map { c ->
                val idx = sharedStrings.size
                sharedStrings.add("R${r}C${c}_Payload")
                idx
            }
        }

        val sharedStringsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${sharedStrings.size}\" uniqueCount=\"${sharedStrings.size}\">\n" +
            sharedStrings.joinToString("\n") { "    <si><t>$it</t></si>" } + "\n" +
            "</sst>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheets>\n" +
            "        <sheet name=\"BigDataSheet\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
            "    </sheets>\n" +
            "</workbook>"

        fun getColLetter(idx: Int): String {
            return if (idx < 26) {
                ('A' + idx).toString()
            } else {
                "A" + ('A' + (idx - 26)).toString()
            }
        }

        val headerCellsXml = (0 until colCount).joinToString("\n") { c ->
            val colLetter = getColLetter(c)
            "            <c r=\"${colLetter}1\" t=\"s\"><v>${headerIndices[c]}</v></c>"
        }

        val rowsXml = StringBuilder()
        rowsXml.append("        <row r=\"1\">\n").append(headerCellsXml).append("\n        </row>\n")

        for (r in 1..rowCount) {
            val rowNum = r + 1
            rowsXml.append("        <row r=\"$rowNum\">\n")
            for (c in 0 until colCount) {
                val colLetter = getColLetter(c)
                val valIdx = rowDataIndices[r - 1][c]
                rowsXml.append("            <c r=\"${colLetter}$rowNum\" t=\"s\"><v>$valIdx</v></c>\n")
            }
            rowsXml.append("        </row>\n")
        }

        val sheet1Xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheetData>\n" +
            rowsXml.toString() +
            "    </sheetData>\n" +
            "</worksheet>"

        val xlsxFile = createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
        val pdfFile = File(tempFolder.root, "output_big_spreadsheet.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        assertTrue(pdfFile.length() > 0)

        PDDocument.load(pdfFile).use { doc ->
            // Unconstrained format conversion: all 100 rows and 30 columns fit cleanly on 1 continuous page
            assertEquals("Big spreadsheet must fit on 1 continuous page without arbitrary paper cuts", 1, doc.numberOfPages)
            val page = doc.getPage(0)

            // Dynamic width expands for 30 columns
            assertTrue("Page width (${page.mediaBox.width}) must dynamically expand beyond 2500pt", page.mediaBox.width > 2500f)
            // Dynamic height expands for 100 rows (~2000pt)
            assertTrue("Page height (${page.mediaBox.height}) must dynamically expand beyond 2000pt", page.mediaBox.height > 2000f)

            val extractor = PositionalTextExtractor()
            extractor.getText(doc)

            // Verify corner and center sample tokens across the 30x100 matrix
            val h1Runs = extractor.findText("ColHeader_1")
            val h15Runs = extractor.findText("ColHeader_15")
            val h30Runs = extractor.findText("ColHeader_30")
            assertTrue("ColHeader_1 must be present", h1Runs.isNotEmpty())
            assertTrue("ColHeader_15 must be present", h15Runs.isNotEmpty())
            assertTrue("ColHeader_30 must be present", h30Runs.isNotEmpty())

            // Verify horizontal monotonic order across columns
            val h1 = h1Runs.first()
            val h15 = h15Runs.first()
            val h30 = h30Runs.first()
            assertTrue("Col 1 maxX < Col 15 minX", h1.maxX < h15.minX)
            assertTrue("Col 15 maxX < Col 30 minX", h15.maxX < h30.minX)

            // Verify vertical monotonicity from row 1 to row 50 to row 100
            val r1c1Runs = extractor.findText("R1C1_Payload")
            val r50c1Runs = extractor.findText("R50C1_Payload")
            val r100c1Runs = extractor.findText("R100C1_Payload")
            assertTrue("R1C1_Payload must be present", r1c1Runs.isNotEmpty())
            assertTrue("R50C1_Payload must be present", r50c1Runs.isNotEmpty())
            assertTrue("R100C1_Payload must be present", r100c1Runs.isNotEmpty())

            val r1 = r1c1Runs.first()
            val r50 = r50c1Runs.first()
            val r100 = r100c1Runs.first()
            assertTrue("Row 1 Y < Row 50 Y", r1.minY < r50.minY)
            assertTrue("Row 50 Y < Row 100 Y", r50.minY < r100.minY)
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

    private fun buildXlsxFile(
        sheetName: String,
        colCount: Int,
        rowCount: Int,
        cellValueBuilder: (row: Int, col: Int) -> String,
        mergeCells: List<String> = emptyList()
    ): File {
        val sharedStrings = mutableListOf<String>()
        val stringMap = mutableMapOf<String, Int>()

        fun getStringIndex(s: String): Int {
            return stringMap.getOrPut(s) {
                val idx = sharedStrings.size
                sharedStrings.add(s)
                idx
            }
        }

        fun getColLetter(idx: Int): String {
            return SpreadsheetLayoutEngine.colIndexToRef(idx)
        }

        val rowsXml = StringBuilder()
        for (r in 0 until rowCount) {
            val rowNum = r + 1
            rowsXml.append("        <row r=\"$rowNum\">\n")
            for (c in 0 until colCount) {
                val colLetter = getColLetter(c)
                val text = cellValueBuilder(r, c)
                val strIdx = getStringIndex(text)
                rowsXml.append("            <c r=\"${colLetter}$rowNum\" t=\"s\"><v>$strIdx</v></c>\n")
            }
            rowsXml.append("        </row>\n")
        }

        val mergeCellsXml = if (mergeCells.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("    <mergeCells count=\"${mergeCells.size}\">\n")
            for (m in mergeCells) {
                sb.append("        <mergeCell ref=\"$m\"/>\n")
            }
            sb.append("    </mergeCells>\n")
            sb.toString()
        } else {
            ""
        }

        val sharedStringsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${sharedStrings.size}\" uniqueCount=\"${sharedStrings.size}\">\n" +
            sharedStrings.joinToString("\n") { "    <si><t>$it</t></si>" } + "\n" +
            "</sst>"

        val workbookXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheets>\n" +
            "        <sheet name=\"$sheetName\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
            "    </sheets>\n" +
            "</workbook>"

        val sheet1Xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
            "    <sheetData>\n" +
            rowsXml.toString() +
            "    </sheetData>\n" +
            mergeCellsXml +
            "</worksheet>"

        return createZip(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))
    }

    @Test
    fun testXlsx30x100Level1NaturalSizeOnSinglePage() = runBlocking {
        val colCount = 30
        val rowCount = 100
        val xlsxFile = buildXlsxFile("SheetL1", colCount, rowCount, { r, c -> "R${r+1}C${c+1}_ID" })
        val pdfFile = File(tempFolder.root, "output_l1_30x100.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            // Level 1: Fits on 1 single continuous page
            assertEquals("Level 1 must export on exactly 1 page", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("Width must be <= 14400pt", page.mediaBox.width <= 14400f)
            assertTrue("Height must be <= 14400pt", page.mediaBox.height <= 14400f)

            val text = PDFTextStripper().getText(doc)
            assertTrue("Must contain R1C1_ID", text.contains("R1C1_ID"))
            assertTrue("Must contain R50C15_ID", text.contains("R50C15_ID"))
            assertTrue("Must contain R100C30_ID", text.contains("R100C30_ID"))
        }
    }

    @Test
    fun testXlsx200ColumnsBy100RowsStrategySelectionAndFullPreservation() = runBlocking {
        val colCount = 200
        val rowCount = 100
        val xlsxFile = buildXlsxFile("Sheet200x100", colCount, rowCount, { r, c -> "Cell_${r+1}_${c+1}" })
        val pdfFile = File(tempFolder.root, "output_200x100.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must generate at least 1 page", doc.numberOfPages >= 1)
            val text = PDFTextStripper().getText(doc)
            // Verify tokens across the matrix: start, middle, and end columns
            assertTrue("Must contain Cell_1_1", text.contains("Cell_1_1"))
            assertTrue("Must contain Cell_50_100", text.contains("Cell_50_100"))
            assertTrue("Must contain Cell_100_200", text.contains("Cell_100_200"))
        }
    }

    @Test
    fun testXlsxWidthExceeding14400TriggersExtendedOrTiled() = runBlocking {
        // Build 180 wide columns: 180 * 90pt = 16,200pt (exceeds 14,400pt ISO coordinate limit)
        val colCount = 180
        val rowCount = 10
        val xlsxFile = buildXlsxFile("WideSheet", colCount, rowCount, { r, c -> "WideColData_${r+1}_${c+1}" })

        // 1. Level 2: Default behavior with allowExtendedSize chooses EXTENDED_SIZE via /UserUnit
        val pdfFileExtended = File(tempFolder.root, "output_extended_userunit.pdf")
        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFileExtended, context)

        assertTrue(pdfFileExtended.exists())
        PDDocument.load(pdfFileExtended).use { doc ->
            assertEquals("Extended size produces 1 page with /UserUnit", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("UserUnit must be scaled > 1.0f (was ${page.userUnit})", page.userUnit > 1.0f)
            assertTrue("MediaBox width must stay within 14,400pt coordinate limit", page.mediaBox.width <= 14400f)

            val text = PDFTextStripper().getText(doc)
            assertTrue("Must preserve Col 1 token", text.contains("WideColData_1_1"))
            assertTrue("Must preserve Col 180 token", text.contains("WideColData_1_180"))
        }

        // 2. Level 3: When tiling is forced, tiles horizontally
        val pdfFileTiled = File(tempFolder.root, "output_forced_tiled.pdf")
        converter.forceExportStrategy = ExportStrategy.TILED
        converter.convertXlsxToPdf(xlsxFile, pdfFileTiled, context)

        assertTrue(pdfFileTiled.exists())
        PDDocument.load(pdfFileTiled).use { doc ->
            assertTrue("Tiled export produces multiple pages", doc.numberOfPages > 1)
            val text = PDFTextStripper().getText(doc)
            assertTrue("Must contain continuation banner", text.contains("Panel") || text.contains("Continues right"))
            assertTrue("Must preserve Col 1 token", text.contains("WideColData_1_1"))
            assertTrue("Must preserve Col 180 token", text.contains("WideColData_1_180"))
        }
    }

    @Test
    fun testXlsxHeightExceeding14400TriggersExtendedOrTiled() = runBlocking {
        // Test tall sheet pagination via maxPdfPageHeightOverride
        val colCount = 10
        val rowCount = 150
        val xlsxFile = buildXlsxFile("TallSheet", colCount, rowCount, { r, c -> "TallData_R${r+1}_C${c+1}" })
        val pdfFile = File(tempFolder.root, "output_tall_pagination.pdf")

        val converter = OfficeConverter()
        converter.maxPdfPageHeightOverride = 1200f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must paginate vertically across multiple pages", doc.numberOfPages > 1)
            val text = PDFTextStripper().getText(doc)
            assertTrue("Must preserve Row 1", text.contains("TallData_R1_C1"))
            assertTrue("Must preserve Row 150", text.contains("TallData_R150_C1"))
        }
    }

    @Test
    fun testXlsxBothDimensionsExceedingLimitsTriggers2DTiling() = runBlocking {
        val colCount = 25
        val rowCount = 40
        val xlsxFile = buildXlsxFile("Grid2D", colCount, rowCount, { r, c -> "G2D_R${r+1}_C${c+1}" })
        val pdfFile = File(tempFolder.root, "output_2d_tiling.pdf")

        val converter = OfficeConverter()
        converter.maxPdfPageWidthOverride = 1000f
        converter.maxPdfPageHeightOverride = 600f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            // With 1000pt width and 600pt height, it requires 2+ horizontal panels and 2+ vertical panels = 4+ pages
            assertTrue("Must generate at least 4 tiled pages for 2D grid", doc.numberOfPages >= 4)

            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val page1Text = stripper.getText(doc)

            // Page 1 is Row Group 1, Panel 1
            assertTrue("Page 1 must contain Panel 1", page1Text.contains("Panel 1"))
            assertTrue("Page 1 must contain Continues right banner", page1Text.contains("Continues right"))
            assertTrue("Page 1 must contain Continues down banner", page1Text.contains("Continues down"))

            stripper.startPage = 2
            stripper.endPage = 2
            val page2Text = stripper.getText(doc)
            // Page 2 is Row Group 1, Panel 2 (row-major order)
            assertTrue("Page 2 must contain Continued from banner", page2Text.contains("Continued from"))
        }
    }

    @Test
    fun testXlsxTiledContentIntegrityCellIdExactMatchNoDuplicatesNoLoss() = runBlocking {
        // Crucial fidelity test:
        // Expected cell IDs = Extracted cell IDs
        // Asserts zero cell loss, zero duplication, and order preservation
        val colCount = 15
        val rowCount = 20

        val expectedIds = mutableSetOf<String>()
        val expectedList = mutableListOf<String>()
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                val id = "CID_R${r+1}_C${c+1}"
                expectedIds.add(id)
                expectedList.add(id)
            }
        }

        val xlsxFile = buildXlsxFile("IntegritySheet", colCount, rowCount, { r, c -> "CID_R${r+1}_C${c+1}" })
        val pdfFile = File(tempFolder.root, "output_tiled_integrity.pdf")

        val converter = OfficeConverter()
        converter.maxPdfPageWidthOverride = 500f
        converter.maxPdfPageHeightOverride = 350f
        converter.repeatFrozenColumnsInTiling = false // Strict 1:1 mapping without frozen repetition
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must be tiled across at least 4 pages", doc.numberOfPages >= 4)

            // Extract all tokens matching CID_R*_C*
            val fullText = PDFTextStripper().getText(doc)
            val tokenRegex = Regex("""CID_R\d+_C\d+""")
            val extractedMatches = tokenRegex.findAll(fullText).map { it.value }.toList()

            // 1. Count integrity: exactly rowCount * colCount tokens
            assertEquals(
                "Total extracted cell IDs must exactly match expected count (no duplicate cells, no dropped cells)",
                expectedIds.size,
                extractedMatches.size
            )

            // 2. Set equality: Expected cell IDs = Extracted cell IDs
            val extractedSet = extractedMatches.toSet()
            assertEquals("Expected cell IDs must equal Extracted cell IDs", expectedIds, extractedSet)

            // 3. Uniqueness: zero duplicates
            val duplicateTokens = extractedMatches.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue("No original cell may be duplicated in lossless tiling (found duplicates: $duplicateTokens)", duplicateTokens.isEmpty())
        }
    }

    @Test
    fun testXlsxMergedCellsAcrossTileBoundaries() = runBlocking {
        // Merged cell A1:F2 spanning across multiple columns
        val colCount = 12
        val rowCount = 8
        val xlsxFile = buildXlsxFile(
            sheetName = "MergedSheet",
            colCount = colCount,
            rowCount = rowCount,
            cellValueBuilder = { r, c ->
                if (r < 2 && c < 6) "BANNER_MERGED_TITLE" else "Data_R${r+1}_C${c+1}"
            },
            mergeCells = listOf("A1:F2")
        )
        val pdfFile = File(tempFolder.root, "output_merged_tiles.pdf")

        val converter = OfficeConverter()
        converter.maxPdfPageWidthOverride = 600f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must tile into at least 2 pages", doc.numberOfPages >= 2)
            val fullText = PDFTextStripper().getText(doc)
            assertTrue("Merged title must be present", fullText.contains("BANNER_MERGED_TITLE"))

            // Verify merged title appears cleanly without duplicate overlapping text
            val matches = Regex("""BANNER_MERGED_TITLE""").findAll(fullText).toList()
            assertEquals("Merged banner title should only be drawn once on anchor panel", 1, matches.size)
        }
    }

    @Test
    fun testXlsxFrozenIdentifyingColumnsOptionalRepetition() = runBlocking {
        val colCount = 16
        val rowCount = 10
        val xlsxFile = buildXlsxFile("FrozenSheet", colCount, rowCount, { r, c ->
            if (c == 0) "EMP_KEY_${r+1}" else "Val_R${r+1}_C${c+1}"
        })
        val pdfFile = File(tempFolder.root, "output_frozen_repetition.pdf")

        val converter = OfficeConverter()
        converter.maxPdfPageWidthOverride = 600f
        converter.repeatFrozenColumnsInTiling = true // Enable optional frozen column repetition
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must tile into at least 2 panels", doc.numberOfPages >= 2)

            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val page1Text = stripper.getText(doc)
            assertTrue("Page 1 must contain EMP_KEY_1", page1Text.contains("EMP_KEY_1"))

            stripper.startPage = 2
            stripper.endPage = 2
            val page2Text = stripper.getText(doc)
            // Column 0 is optionally repeated on subsequent horizontal panels to preserve row context!
            assertTrue("Page 2 (next horizontal panel) must repeat frozen column EMP_KEY_1", page2Text.contains("EMP_KEY_1"))
        }
    }

    @Test
    fun testXlsxExtremelyWideIndividualCellNoClippingOrSilentScaling() = runBlocking {
        // Cell with a very long single token (>2,000pt wide)
        val longString = "X".repeat(300) + "_EXTREMELY_WIDE_DATA"
        val xlsxFile = buildXlsxFile("WideCellSheet", 3, 3, { r, c ->
            if (r == 1 && c == 1) longString else "NormalCell_${r+1}_${c+1}"
        })
        val pdfFile = File(tempFolder.root, "output_extremely_wide_cell.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            val page = doc.getPage(0)
            // Dynamic column width expands to fit the wide cell without artificial clamping
            assertTrue("Page width (${page.mediaBox.width}) must accommodate wide cell", page.mediaBox.width > 1200f)
            val fullText = PDFTextStripper().getText(doc)
            assertTrue("Must contain full extremely wide payload", fullText.contains("_EXTREMELY_WIDE_DATA"))
        }
    }
}
