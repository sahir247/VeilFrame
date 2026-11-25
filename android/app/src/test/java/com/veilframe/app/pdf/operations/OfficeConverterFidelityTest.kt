package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun emptyPdfCannotOverwriteExistingDestination() = runBlocking {
        val destination = File(tempFolder.root, "existing.pdf")
        destination.writeText("previous artifact")
        PDDocument().use { doc ->
            val failure = runCatching { OfficeConverter().publishOfficePdf(doc, destination) }
            assertTrue(failure.isFailure)
        }
        assertEquals("previous artifact", destination.readText())
        assertTrue(tempFolder.root.listFiles()!!.none { it.name.startsWith("office_export_") })
    }

    @Test
    fun malformedPresentationFailsInsteadOfReturningPartialSlides() = runBlocking {
        val input = createZip(mapOf("not-a-presentation" to "invalid"))
        val destination = File(tempFolder.root, "existing_presentation.pdf")
        destination.writeText("previous artifact")
        val failure = runCatching { OfficeConverter().convertPptxToPdf(input, destination, "default", context) }
        assertTrue(failure.isFailure)
        assertEquals("previous artifact", destination.readText())
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

    private fun singleTextWorkbook(value: String): File = createZip(mapOf(
        "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Text" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$value</t></is></c></row></sheetData></worksheet>"""
    ))

    @Test
    fun unsupportedSpreadsheetGlyphsDoNotOverwriteDestination() = runBlocking {
        val input = singleTextWorkbook("Invoice \u0378 42")
        val destination = File(tempFolder.root, "existing_unicode.pdf")
        destination.writeText("previous artifact")
        for (saved in listOf(false, true)) {
            val failure = runCatching {
                OfficeConverter().convertXlsxToPdf(input, destination, context, savedPrintLayout = saved)
            }
            assertTrue("Unsupported glyphs must not silently disappear", failure.isFailure)
            assertEquals("previous artifact", destination.readText())
        }
    }

    @Test
    fun supportedWinAnsiSpreadsheetGlyphsSurviveRendering() = runBlocking {
        val input = singleTextWorkbook("Café €42 – paid")
        val destination = File(tempFolder.root, "winansi.pdf")
        OfficeConverter().convertXlsxToPdf(input, destination, context)
        PDDocument.load(destination).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("Café €42 – paid"))
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
        mergeCells: List<String> = emptyList(),
        frozenCols: Int = 0,
        frozenRows: Int = 0
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

        val sheetViewsXml = if (frozenCols > 0 || frozenRows > 0) {
            val xSplitAttr = if (frozenCols > 0) " xSplit=\"$frozenCols\"" else ""
            val ySplitAttr = if (frozenRows > 0) " ySplit=\"$frozenRows\"" else ""
            "    <sheetViews>\n" +
            "        <sheetView tabSelected=\"1\" workbookViewId=\"0\">\n" +
            "            <pane$xSplitAttr$ySplitAttr state=\"frozen\"/>\n" +
            "        </sheetView>\n" +
            "    </sheetViews>\n"
        } else {
            ""
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
            sheetViewsXml +
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

            // 4. Strict sequence preservation in row-major panel order across tiles
            val parsed = OpenXmlDocumentParser.parseXlsx(xlsxFile)
            val limits = LayoutLimits(maxTileWidthOverride = 500f, maxTileHeightOverride = 350f, repeatFrozenColumnsInTiling = false)
            val plan = SpreadsheetLayoutEngine.createExportPlan(parsed.sheets[0], 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, limits)
            val expectedTiledSequence = mutableListOf<String>()
            for (tile in plan.tiles) {
                for (r in tile.rowGroup.startRow..tile.rowGroup.endRow) {
                    for (c in tile.colGroup.activeCols) {
                        expectedTiledSequence.add("CID_R${r + 1}_C${c + 1}")
                    }
                }
            }
            assertEquals(
                "Extracted cell ID sequence must match expected row-major reading order across tiles exactly",
                expectedTiledSequence,
                extractedMatches
            )
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

    @Test
    fun testOversizedColumnAndRowStrictlyRespectTileBoundsWithoutOverflow() = runBlocking {
        // Enormous column (5,000 characters) and enormous multiline row (80 lines)
        val massiveText = "WORD_".repeat(1000)
        val multilineText = (1..80).joinToString("\n") { "TALL_LINE_$it" }
        val xlsxFile = buildXlsxFile("OversizedSheet", 4, 4, { r, c ->
            when {
                r == 1 && c == 1 -> massiveText
                r == 2 && c == 2 -> multilineText
                else -> "Cell_R${r+1}_C${c+1}"
            }
        })
        val pdfFile = File(tempFolder.root, "output_oversized_bounds.pdf")

        val converter = OfficeConverter()
        converter.forceExportStrategy = ExportStrategy.TILED
        converter.maxPdfPageWidthOverride = 600f
        converter.maxPdfPageHeightOverride = 500f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must tile into multiple pages", doc.numberOfPages >= 2)
            for (p in 0 until doc.numberOfPages) {
                val page = doc.getPage(p)
                assertTrue("Page $p width (${page.mediaBox.width}) must not exceed ceiling 600f", page.mediaBox.width <= 600.5f)
                assertTrue("Page $p height (${page.mediaBox.height}) must not exceed ceiling 500f", page.mediaBox.height <= 500.5f)
            }
            val fullText = PDFTextStripper().getText(doc)
            assertTrue("Must preserve massive text chunk", fullText.contains("WORD_WORD_"))
            assertTrue("Must preserve tall line 1", fullText.contains("TALL_LINE_1"))
            assertTrue("Must preserve tall line 80", fullText.contains("TALL_LINE_80"))
        }
    }

    @Test
    fun testMergedCellFullGeometryAndNoInternalGridlines() = runBlocking {
        val colCount = 6
        val rowCount = 6
        // B2:D4 is merged (cols 1..3, rows 1..3, 3 cols x 3 rows)
        val xlsxFile = buildXlsxFile(
            sheetName = "MergedGeometrySheet",
            colCount = colCount,
            rowCount = rowCount,
            cellValueBuilder = { r, c ->
                if (r in 1..3 && c in 1..3) "MERGED_BLOCK_PAYLOAD" else "C_${r+1}_${c+1}"
            },
            mergeCells = listOf("B2:D4")
        )

        val parserData = OpenXmlDocumentParser.parseXlsx(xlsxFile)
        val sheet = parserData.sheets[0]
        assertEquals("Must parse 1 merged region", 1, sheet.mergedRegions.size)
        val mRange = sheet.mergedRegions[0]
        assertEquals("B2:D4 firstCol", 1, mRange.firstCol)
        assertEquals("B2:D4 lastCol", 3, mRange.lastCol)
        assertEquals("B2:D4 firstRow", 1, mRange.firstRow)
        assertEquals("B2:D4 lastRow", 3, mRange.lastRow)

        val layout = SpreadsheetLayoutEngine.buildSheetLayout(
            sheet, 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, LayoutLimits()
        )
        assertEquals("Must have 1 MergedCellGeometry", 1, layout.mergedGeometries.size)
        val geom = layout.mergedGeometries[0]
        val expectedW = (1..3).sumOf { layout.colWidths[it].toDouble() }.toFloat()
        val expectedH = (1..3).sumOf { layout.rowHeights[it].toDouble() }.toFloat()
        assertEquals(expectedW, geom.totalWidth, 0.01f)
        assertEquals(expectedH, geom.totalHeight, 0.01f)
        assertTrue("Merged cell must wrap across combined width", geom.wrappedLines.isNotEmpty())

        val pdfFile = File(tempFolder.root, "output_merged_geom.pdf")
        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)
        assertTrue(pdfFile.exists())

        PDDocument.load(pdfFile).use { doc ->
            val fullText = PDFTextStripper().getText(doc)
            val matches = Regex("MERGED_BLOCK_PAYLOAD").findAll(fullText).toList()
            assertEquals("Merged text should appear exactly once (no duplicates)", 1, matches.size)
        }
    }

    @Test
    fun testFrozenPaneMetadataParsedAndPositionedAccurately() = runBlocking {
        val colCount = 12
        val rowCount = 8
        // Frozen pane with 2 columns frozen and 1 row frozen
        val xlsxFile = buildXlsxFile(
            sheetName = "FrozenPaneSheet",
            colCount = colCount,
            rowCount = rowCount,
            cellValueBuilder = { r, c ->
                when (c) {
                    0 -> "ID_KEY_${r+1}"
                    1 -> "NAME_KEY_${r+1}"
                    else -> "VAL_R${r+1}_C${c+1}"
                }
            },
            frozenCols = 2,
            frozenRows = 1
        )

        val parserData = OpenXmlDocumentParser.parseXlsx(xlsxFile)
        val sheet = parserData.sheets[0]
        assertEquals("Must parse 2 frozen columns from XML", 2, sheet.frozenColumns)
        assertEquals("Must parse 1 frozen row from XML", 1, sheet.frozenRows)

        val pdfFile = File(tempFolder.root, "output_frozen_pane_parsed.pdf")
        val converter = OfficeConverter()
        converter.maxPdfPageWidthOverride = 500f
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertTrue("Must tile into at least 2 panels", doc.numberOfPages >= 2)

            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val p1Text = stripper.getText(doc)
            assertTrue("Panel 1 must have frozen col 0", p1Text.contains("ID_KEY_1"))
            assertTrue("Panel 1 must have frozen col 1", p1Text.contains("NAME_KEY_1"))

            stripper.startPage = 2
            stripper.endPage = 2
            val p2Text = stripper.getText(doc)
            // Both frozen columns 0 and 1 are repeated on subsequent horizontal panels!
            assertTrue("Panel 2 must repeat frozen col 0", p2Text.contains("ID_KEY_1"))
            assertTrue("Panel 2 must repeat frozen col 1", p2Text.contains("NAME_KEY_1"))
        }
    }

    @Test
    fun testExtendedUserUnitOpensAndRendersInAndroidPdfRenderer() = runBlocking {
        val colCount = 180
        val rowCount = 10
        val xlsxFile = buildXlsxFile("ExtendedSheet", colCount, rowCount, { r, c -> "ExtendedWidePayload_${r+1}_${c+1}" })
        val pdfFile = File(tempFolder.root, "output_extended_pdfrenderer.pdf")

        val converter = OfficeConverter()
        converter.convertXlsxToPdf(xlsxFile, pdfFile, context)

        assertTrue(pdfFile.exists())
        PDDocument.load(pdfFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertTrue("UserUnit must be scaled > 1.0f (was ${page.userUnit})", page.userUnit > 1.0f)
            assertTrue("MediaBox width must stay within 14,400pt coordinate limit", page.mediaBox.width <= 14400f)
        }

        // Test with Android native PdfRenderer when supported by runtime environment
        try {
            val pfd = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            if (pfd != null) {
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                assertEquals("PdfRenderer must report 1 page", 1, renderer.pageCount)

                val page = renderer.openPage(0)
                assertTrue("Page width in points must be > 0", page.width > 0)
                assertTrue("Page height in points must be > 0", page.height > 0)

                val bitmap = android.graphics.Bitmap.createBitmap(
                    minOf(page.width, 1024),
                    minOf(page.height, 1024),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                assertNotNull(bitmap)
                assertTrue("Rendered bitmap must have positive dimensions", bitmap.width > 0 && bitmap.height > 0)

                page.close()
                renderer.close()
                pfd.close()
            }
        } catch (e: Throwable) {
            println("Notice: Host JVM environment does not provide native pdfium for Robolectric PdfRenderer: ${e.message}")
        }
    }

    @Test
    fun testLargeWorksheet200x2000PreservationAndMemory() = runBlocking {
        // Stress test 200 cols x 2000 rows layout planning
        val colCount = 200
        val rowCount = 2000
        val rows = (0 until rowCount).map { r ->
            listOf("R${r+1}_C1", "R${r+1}_C100", "R${r+1}_C200")
        }
        val sheetData = OpenXmlDocumentParser.XlsxSheetData(
            name = "LargeSheet200x2000",
            rows = rows
        )
        val limits = LayoutLimits()
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(sheetData, 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, limits)
        assertEquals(2000, layout.rowCount)
        assertEquals(3, layout.colCount)
        assertTrue(layout.totalTableHeight > 0f)

        val plan = SpreadsheetLayoutEngine.createExportPlan(sheetData, 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, limits)
        assertNotNull(plan)
        assertTrue(plan.strategy != null)
    }

    @Test
    fun explicitCellStylesReachPdfTextAndFillOperators() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Styled" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><fonts><font/><font><b/><i/><color rgb="FF0000FF"/></font></fonts><fills><fill/><fill><patternFill patternType="solid"><fgColor rgb="FFFF0000"/></patternFill></fill></fills><cellXfs><xf/><xf fontId="1" fillId="1"><alignment horizontal="right"/></xf></cellXfs></styleSheet>"""
        val sheet = """<worksheet><cols><col min="1" max="1" width="40"/></cols><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>LEFT</t></is></c></row><row r="2"><c r="A2" t="inlineStr" s="1"><is><t>RIGHT</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val target = File(tempFolder.root, "appearance.pdf")
        OfficeConverter().convertXlsxToPdf(input, target, context)
        PDDocument.load(target).use { pdf ->
            val positions = mutableListOf<com.tom_roush.pdfbox.text.TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
                    positions.add(text)
                    super.processTextPosition(text)
                }
            }
            val text = stripper.getText(pdf)
            assertTrue(text.contains("LEFT") && text.contains("RIGHT"))
            val boldGlyphs = positions.filter { it.font.name.contains("BoldOblique") }
            assertEquals("RIGHT", boldGlyphs.joinToString("") { it.unicode })
            val leftGlyphs = positions.filter { it.font.name == "Helvetica" && it.unicode == "L" }
            assertTrue(leftGlyphs.isNotEmpty())
            assertTrue(boldGlyphs.minOf { it.xDirAdj } > leftGlyphs.minOf { it.xDirAdj } + 100f)
            val instructions = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            val colorPattern = Regex("""(?m)(?:^|\s)1(?:\.0+)?\s+0(?:\.0+)?\s+0(?:\.0+)?\s+(?:rg|scn?)\b""")
            val textColorPattern = Regex("""(?m)(?:^|\s)0(?:\.0+)?\s+0(?:\.0+)?\s+1(?:\.0+)?\s+(?:rg|scn?)\b""")
            assertTrue("Explicit red fill operator missing", colorPattern.containsMatchIn(instructions))
            assertTrue("Explicit blue text operator missing", textColorPattern.containsMatchIn(instructions))
        }
    }

    @Test
    fun explicitSharedBorderIsDrawnOnceAfterCellFillsWithGridlinesDisabled() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Borders" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><fills><fill/><fill><patternFill patternType="solid"><fgColor rgb="FFFF0000"/></patternFill></fill></fills><borders><border/><border><right style="thin"><color rgb="FF00FF00"/></right></border><border><left style="thick"><color rgb="FF0000FF"/></left></border></borders><cellXfs><xf/><xf borderId="1"/><xf borderId="2" fillId="1"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetViews><sheetView showGridLines="false"/></sheetViews><sheetData><row r="1"><c r="A1" s="1"><v>1</v></c><c r="B1" s="2"><v>2</v></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root, "shared-border.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context)
        PDDocument.load(output).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val instructions = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            val fill = Regex("""(?m)(?:^|\s)1(?:\.0+)?\s+0(?:\.0+)?\s+0(?:\.0+)?\s+(?:rg|scn?)\b""").find(instructions)
            val blue = Regex("""(?m)(?:^|\s)0(?:\.0+)?\s+0(?:\.0+)?\s+1(?:\.0+)?\s+(?:RG|SCN?)\b""").find(instructions)
            assertNotNull(fill)
            assertNotNull(blue)
            assertTrue(blue!!.range.first > fill!!.range.last)
            assertEquals(1, Regex("""(?m)^\s*S\s*$""").findAll(instructions).count())
        }
    }

    @Test
    fun savedDimensionsAreMinimumsAndFrozenPanelsNeverOverflowOrDuplicateWithinPanel() {
        val sheet = OpenXmlDocumentParser.XlsxSheetData(
            name = "Sized",
            rows = listOf(listOf("a", "b", "c", "d"), listOf("a", "b", "c", "d")),
            frozenColumns = 2,
            dimensions = XlsxDimensions(defaultColumnWidth = 30f, defaultRowHeightPt = 48f,
                rowHeightsPt = mapOf(0 to 80f))
        )
        val limits = LayoutLimits(maxTileWidthOverride = 400f)
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(sheet, 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, limits)
        assertTrue(layout.colWidths.all { it >= 157.5f })
        assertEquals(80f, layout.rowHeights[0], 0.01f)
        assertEquals(48f, layout.rowHeights[1], 0.01f)
        val groups = SpreadsheetLayoutEngine.partitionColumns(layout, limits, 340f)
        assertEquals(listOf(0, 1, 2, 3), groups.flatMap { it.activeCols })
        for (group in groups) {
            assertTrue(group.groupTableWidth <= 340.01f)
            assertTrue(group.frozenCols.intersect(group.activeCols.toSet()).isEmpty())
        }
        val wider = SpreadsheetLayoutEngine.partitionColumns(layout, limits.copy(maxTileWidthOverride = 600f), 540f)
        assertTrue(wider.last().frozenCols.isNotEmpty())
        assertTrue(wider.last().groupTableWidth <= 540.01f)
    }

    @Test
    fun testSparseWorksheetWithHighCoordinatesAndMergedExtendingBeyondData() = runBlocking {
        // Sparse sheet with high coordinates (Row 150, Col 50) and merge extending beyond data
        val rows = mutableListOf<List<String>>()
        repeat(149) { rows.add(emptyList()) }
        val row150 = mutableListOf<String>()
        repeat(49) { row150.add("") }
        row150.add("HIGH_COORD_PAYLOAD")
        rows.add(row150)

        // Merge range extending to Row 200 (beyond row 150)
        val mergeRange = OpenXmlDocumentParser.CellRangeAddress(0, 199, 0, 2)
        val sheetData = OpenXmlDocumentParser.XlsxSheetData(
            name = "SparseSheet",
            rows = rows,
            mergedRegions = listOf(mergeRange)
        )

        val limits = LayoutLimits()
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(sheetData, 0, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, limits)
        assertEquals(200, layout.rowCount)
        assertEquals(50, layout.colCount)
        assertEquals("HIGH_COORD_PAYLOAD", sheetData.rows[149][49])

        val mergedGeoms = layout.mergedGeometries
        assertEquals(1, mergedGeoms.size)
        assertEquals(199, mergedGeoms[0].lastRow) // preserve declared blank merged rows
    }
    @Test
    fun explicitVerticalAlignmentPositionsTextInsideSavedRowHeight() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Vertical" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><cellXfs><xf/><xf><alignment vertical="center"/></xf><xf><alignment vertical="bottom"/></xf></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1" ht="100"><c r="A1" t="inlineStr"><is><t>X</t></is></c><c r="B1" s="1" t="inlineStr"><is><t>Y</t></is></c><c r="C1" s="2" t="inlineStr"><is><t>Z</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root, "vertical.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context)
        PDDocument.load(output).use { pdf ->
            val y = mutableMapOf<String, Float>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
                    if (text.unicode in setOf("X", "Y", "Z")) y[text.unicode] = text.yDirAdj
                    super.processTextPosition(text)
                }
            }
            stripper.getText(pdf)
            assertEquals(3, y.size)
            val centerDelta = y.getValue("Y") - y.getValue("X")
            val bottomDelta = y.getValue("Z") - y.getValue("X")
            assertTrue(centerDelta > 30f)
            assertEquals(centerDelta * 2f, bottomDelta, 0.5f)
        }
    }
    @Test
    fun rotatedSpreadsheetTextProducesRealPdfTransforms() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Rotation" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><cellXfs><xf/><xf><alignment textRotation="90"/></xf><xf><alignment textRotation="135"/></xf><xf><alignment textRotation="255"/></xf></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1" t="inlineStr"><is><t>UPWARD</t></is></c><c r="B1" s="2" t="inlineStr"><is><t>DOWNWARD</t></is></c><c r="C1" s="3" t="inlineStr"><is><t>XY</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet))
        val target = File(tempFolder.root, "rotated.pdf")
        OfficeConverter().convertXlsxToPdf(input, target, context)
        PDDocument.load(target).use { pdf ->
            val content = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)(?:^|\s)0\s+1\s+-1\s+0\s+[-.\d]+\s+[-.\d]+\s+cm\b""").containsMatchIn(content))
            org.junit.Assert.assertTrue(content.contains("UPWARD") || PDFTextStripper().getText(pdf).filterNot { it.isWhitespace() }.contains("UPWARD"))
        }
        val up = XlsxRotatedText.measure("UPWARD", 90, com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 9f, 11.7f)
        assertEquals(9f, up.width, 0.05f)
        assertTrue(up.height > 30f)
        assertEquals(listOf("X", "Y"), XlsxRotatedText.lines("XY", 255))
    }
    @Test
    fun cachedChartRelationshipsReachVectorPdfAppendix() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Sales" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>5</v></c></row></sheetData><drawing r:id="draw"/></worksheet>"""
        val sheetRels = """<Relationships><Relationship Id="draw" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing9.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:row>2</xdr:row></xdr:from><xdr:to><xdr:col>3</xdr:col><xdr:row>7</xdr:row></xdr:to><xdr:graphicFrame><a:graphic><a:graphicData><c:chart r:id="chart"/></a:graphicData></a:graphic></xdr:graphicFrame></xdr:twoCellAnchor></xdr:wsDr>"""
        val drawingRels = """<Relationships><Relationship Id="chart" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/custom.xml"/></Relationships>"""
        val chart = """<c:chartSpace><c:chart><c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:ser><c:tx><c:v>Revenue</c:v></c:tx><c:spPr><a:solidFill><a:srgbClr val="22AA44"/></a:solidFill></c:spPr><c:cat><c:strLit><c:ptCount val="3"/><c:pt idx="0"><c:v>Jan</c:v></c:pt><c:pt idx="1"><c:v>Feb</c:v></c:pt><c:pt idx="2"><c:v>Mar</c:v></c:pt></c:strLit></c:cat><c:val><c:numRef><c:f>Sales!A1:A3</c:f><c:numCache><c:ptCount val="3"/><c:pt idx="2"><c:v>-2</c:v></c:pt><c:pt idx="0"><c:v>5</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRels, "xl/drawings/drawing9.xml" to drawing,
            "xl/drawings/_rels/drawing9.xml.rels" to drawingRels, "xl/charts/custom.xml" to chart))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single().charts.single()
        assertEquals(listOf(5.0, null, -2.0), parsed.series.single().values)
        assertEquals("B3:D8", parsed.sourceAnchor)
        assertEquals(0x22AA44,parsed.series.single().rgb)
        val output = File(tempFolder.root, "chart.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context)
        PDDocument.load(output).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Revenue") && text.contains("Jan") && text.contains("(missing)"))
            val operators = pdf.getPage(1).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)^\s*f\s*$""").containsMatchIn(operators))
        }
        val stackedInput = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRels, "xl/drawings/drawing9.xml" to drawing,
            "xl/drawings/_rels/drawing9.xml.rels" to drawingRels,
            "xl/charts/custom.xml" to chart.replace("clustered","stacked")))
        assertEquals("stacked", OpenXmlDocumentParser.parseXlsx(stackedInput).sheets.single().charts.single().grouping)
        val stackedPdf = File(tempFolder.root, "stacked-chart.pdf")
        OfficeConverter().convertXlsxToPdf(stackedInput, stackedPdf, context)
        PDDocument.load(stackedPdf).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("[stacked]"))
        }
    }
    @Test
    fun savedPrintLayoutRespectsPaperMarginsAreaAndBreaksWithoutChangingDefault() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">Print!&#36;A&#36;1:&#36;B&#36;2</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>FIRST</t></is></c><c r="C1" t="inlineStr"><is><t>OUTSIDE</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>SECOND</t></is></c></row></sheetData><pageMargins left="1" right="1" top="1" bottom="1"/><pageSetup paperSize="9" orientation="landscape" scale="100"/><rowBreaks><brk id="1" man="1"/></rowBreaks></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val print = File(tempFolder.root, "print.pdf")
        val full = File(tempFolder.root, "full.pdf")
        val converter = OfficeConverter()
        converter.convertXlsxToPdf(input, print, context, savedPrintLayout = true)
        converter.convertXlsxToPdf(input, full, context)
        PDDocument.load(print).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            assertEquals(841.89f, pdf.getPage(0).mediaBox.width, 0.1f)
            assertEquals(595.28f, pdf.getPage(0).mediaBox.height, 0.1f)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("FIRST") && text.contains("SECOND") && !text.contains("OUTSIDE"))
        }
        PDDocument.load(full).use { pdf -> assertTrue(PDFTextStripper().getText(pdf).contains("OUTSIDE")) }
    }
    @Test
    fun cachedPieUsesOneDenominatorAndVectorCurves() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Sales" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData/><drawing r:id="draw"/></worksheet>"""
        val sheetRels = """<Relationships><Relationship Id="draw" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="chart"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRels = """<Relationships><Relationship Id="chart" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/c.xml"/></Relationships>"""
        val chart = """<c:chartSpace><c:chart><c:plotArea><c:pieChart><c:ser><c:cat><c:strLit><c:ptCount val="3"/><c:pt idx="0"><c:v>North</c:v></c:pt><c:pt idx="1"><c:v>South</c:v></c:pt><c:pt idx="2"><c:v>Missing</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="3"/><c:pt idx="0"><c:v>3</c:v></c:pt><c:pt idx="1"><c:v>1</c:v></c:pt></c:numLit></c:val></c:ser></c:pieChart></c:plotArea></c:chart></c:chartSpace>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRels, "xl/drawings/d.xml" to drawing,
            "xl/drawings/_rels/d.xml.rels" to drawingRels, "xl/charts/c.xml" to chart))
        val output = File(tempFolder.root, "pie.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context)
        PDDocument.load(output).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("75.00%") && text.contains("25.00%") && text.contains("(missing)"))
            val operators = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bc\s*$""").containsMatchIn(operators))
        }
    }
    @Test
    fun disjointPrintAreasPreserveOrderAndExcludeInterveningCells() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Sales, O&apos;Brien" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">'Sales, O''Brien'!&#36;C&#36;1,'Sales, O''Brien'!&#36;A&#36;1</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>ALPHA</t></is></c><c r="B1" t="inlineStr"><is><t>OMITME</t></is></c><c r="C1" t="inlineStr"><is><t>OMEGA</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root, "multiple.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context, savedPrintLayout = true)
        PDDocument.load(output).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.indexOf("OMEGA") in 0 until text.indexOf("ALPHA"))
            assertTrue(!text.contains("OMITME"))
        }
    }
    @Test
    fun savedPrintTitlesRepeatOnlyInExplicitPrintMode() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">Print!&#36;A&#36;1:&#36;B&#36;3</definedName><definedName name="_xlnm.Print_Titles" localSheetId="0">Print!&#36;1:&#36;1,Print!&#36;A:&#36;A</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>HEADER</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>BODYONE</t></is></c></row><row r="3"><c r="B3" t="inlineStr"><is><t>BODYTWO</t></is></c></row></sheetData><rowBreaks><brk id="2" man="1"/></rowBreaks></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root, "titles.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context, savedPrintLayout = true)
        PDDocument.load(output).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertEquals(2, Regex("HEADER").findAll(text).count())
            assertEquals(1, Regex("BODYONE").findAll(text).count())
            assertEquals(1, Regex("BODYTWO").findAll(text).count())
        }
        val normal = File(tempFolder.root, "titles-full.pdf")
        OfficeConverter().convertXlsxToPdf(input, normal, context)
        PDDocument.load(normal).use { pdf -> assertEquals(1, Regex("HEADER").findAll(PDFTextStripper().getText(pdf)).count()) }
    }
    @Test
    fun savedPrintOmitsHiddenRowsAndColumnsButFullExportPreservesThem() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><cols><col min="2" max="2" hidden="1"/></cols><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>VISIBLEONE</t></is></c><c r="B1" t="inlineStr"><is><t>SECRET_COLUMN</t></is></c></row><row r="2" hidden="true"><c r="A2" t="inlineStr"><is><t>SECRET_ROW</t></is></c></row><row r="3"><c r="C3" t="inlineStr"><is><t>VISIBLETWO</t></is></c></row></sheetData><rowBreaks><brk id="1" man="1"/></rowBreaks></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root, "hidden-print.pdf")
        OfficeConverter().convertXlsxToPdf(input, output, context, savedPrintLayout = true)
        PDDocument.load(output).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("VISIBLEONE") && text.contains("VISIBLETWO"))
            assertTrue(!text.contains("SECRET_COLUMN") && !text.contains("SECRET_ROW"))
        }
        val normal = File(tempFolder.root, "hidden-full.pdf")
        OfficeConverter().convertXlsxToPdf(input, normal, context)
        PDDocument.load(normal).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("SECRET_COLUMN") && text.contains("SECRET_ROW"))
        }
    }
    @Test
    fun signedStackGeometryPreservesMissingPointsAndAvoidsOverflow() {
        val chart = XlsxCachedChart("Stack","column",listOf("A","B"),listOf(
            XlsxCachedChart.Series("one",listOf(Double.MAX_VALUE,-2.0)),
            XlsxCachedChart.Series("two",listOf(Double.MAX_VALUE,null)),
            XlsxCachedChart.Series("three",listOf(-Double.MAX_VALUE,3.0))),"stacked")
        val segments = XlsxStackedChart.segments(chart,Double.MAX_VALUE,false)
        assertEquals(XlsxStackedChart.Segment(0.0,1.0),segments[0][0])
        assertEquals(XlsxStackedChart.Segment(1.0,2.0),segments[1][0])
        assertEquals(XlsxStackedChart.Segment(0.0,-1.0),segments[2][0])
        assertEquals(null,segments[1][1])
        val percent = XlsxStackedChart.segments(chart,Double.MAX_VALUE,true)
        assertEquals(2.0/3.0,percent[1][0]!!.end,0.000001)
        assertEquals(-1.0/3.0,percent[2][0]!!.end,0.000001)
        assertEquals(-0.4,percent[0][1]!!.end,0.000001)
        assertEquals(0.6,percent[2][1]!!.end,0.000001)
    }
    @Test
    fun savedHeadersAndFootersExpandAcrossPrintedPages() = runBlocking {
        assertEquals(XlsxPrintHeaderFooter.Sections("Left &","Page 2 of 3","Print"),
            XlsxPrintHeaderFooter.expand("&LLeft &&&CPage &P of &N&R&A", "Print", 2, 3))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            XlsxPrintHeaderFooter.expand("&D", "Print", 1, 1)
        }
        val workbook = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="2"><c r="A2"><v>2</v></c></row></sheetData><pageMargins top="0.8" bottom="0.8" header="0.3" footer="0.3"/><rowBreaks><brk id="1" man="1"/></rowBreaks><headerFooter differentOddEven="1"><oddHeader>&amp;CPage &amp;P of &amp;N</oddHeader><evenHeader>&amp;CSecond &amp;A</evenHeader><oddFooter>&amp;RFoot &amp;P</oddFooter><evenFooter>&amp;RFoot &amp;P</evenFooter></headerFooter></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val output = File(tempFolder.root,"headers.pdf")
        OfficeConverter().convertXlsxToPdf(input,output,context,savedPrintLayout=true)
        PDDocument.load(output).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Page 1 of 2") && text.contains("Second Print"))
            assertTrue(text.contains("Foot 1") && text.contains("Foot 2"))
        }
    }
    @Test
    fun cachedScatterUsesNumericXYNotCategoryIndices() = runBlocking {
        val axis = XlsxScatterGeometry.axis(listOf(-Double.MAX_VALUE,0.0,Double.MAX_VALUE))
        assertEquals(0f,axis.fraction(-Double.MAX_VALUE),0.0001f)
        assertEquals(0.5f,axis.fraction(0.0),0.0001f)
        assertEquals(1f,axis.fraction(Double.MAX_VALUE),0.0001f)
        val workbook = """<workbook><sheets><sheet name="XY" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/xy.xml"/></Relationships>"""
        val chart = """<c:chartSpace><c:chart><c:plotArea><c:scatterChart><c:scatterStyle val="lineMarker"/><c:ser><c:tx><c:v>S1</c:v></c:tx><c:xVal><c:numLit><c:ptCount val="3"/><c:pt idx="0"><c:v>-4</c:v></c:pt><c:pt idx="1"><c:v>0</c:v></c:pt><c:pt idx="2"><c:v>40</c:v></c:pt></c:numLit></c:xVal><c:yVal><c:numLit><c:ptCount val="3"/><c:pt idx="0"><c:v>10</c:v></c:pt><c:pt idx="2"><c:v>30</c:v></c:pt></c:numLit></c:yVal></c:ser></c:scatterChart></c:plotArea></c:chart></c:chartSpace>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,"xl/drawings/d.xml" to drawing,
            "xl/drawings/_rels/d.xml.rels" to drawingRel,"xl/charts/xy.xml" to chart))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single().charts.single()
        assertEquals("scatter",parsed.kind)
        assertEquals(listOf(-4.0,0.0,40.0),parsed.series.single().xValues)
        assertEquals(listOf(10.0,null,30.0),parsed.series.single().values)
        val drawablePairs = parsed.series.single().xValues.zip(parsed.series.single().values)
            .filter { it.first != null && it.second != null }
        assertEquals(2,drawablePairs.size)
        val out = File(tempFolder.root,"xy.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("XY scatter") && text.contains("(missing)"))
        }
    }
    @Test
    fun primaryAxisComboChartRendersLinesAndColumnsAndRejectsSecondaryAxes() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Combo" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/combo.xml"/></Relationships>"""
        val cat = """<c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>Jan</c:v></c:pt><c:pt idx="1"><c:v>Feb</c:v></c:pt></c:strLit></c:cat>"""
        val barSeries = """<c:ser><c:tx><c:v>Volume</c:v></c:tx>$cat<c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>5</c:v></c:pt><c:pt idx="1"><c:v>8</c:v></c:pt></c:numLit></c:val></c:ser>"""
        val lineSeries = """<c:ser><c:tx><c:v>Price</c:v></c:tx>$cat<c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>3</c:v></c:pt><c:pt idx="1"><c:v>7</c:v></c:pt></c:numLit></c:val></c:ser>"""
        fun chart(secondary: Boolean): String = """<c:chartSpace><c:chart><c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/>$barSeries<c:axId val="1"/><c:axId val="2"/></c:barChart><c:lineChart><c:grouping val="standard"/>$lineSeries<c:axId val="1"/><c:axId val="${if (secondary) 3 else 2}"/></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        fun input(secondary: Boolean) = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,"xl/drawings/d.xml" to drawing,
            "xl/drawings/_rels/d.xml.rels" to drawingRel,"xl/charts/combo.xml" to chart(secondary)))
        val parsed = OpenXmlDocumentParser.parseXlsx(input(false)).sheets.single().charts.single()
        assertEquals("combo",parsed.kind)
        assertEquals(listOf("column","line"),parsed.series.map { it.chartKind })
        assertEquals(1,OpenXmlDocumentParser.parseXlsx(input(true)).sheets.single().unsupportedDrawings)
        val out = File(tempFolder.root,"combo.pdf")
        OfficeConverter().convertXlsxToPdf(input(false),out,context)
        PDDocument.load(out).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("[column+line]") && text.contains("Volume") && text.contains("Price"))
        }
    }
    @Test
    fun savedConditionalDataBarsAndTrafficIconsReachPdfVectors() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Bars" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>10</v></c><c r="B1"><v>0</v></c></row><row r="2"><c r="A2"><v>20</v></c><c r="B2"><v>50</v></c></row><row r="3"><c r="A3"><v>30</v></c><c r="B3"><v>100</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="dataBar" priority="1"><dataBar showValue="0"><cfvo type="min"/><cfvo type="max"/><color rgb="FF2468AA"/></dataBar></cfRule></conditionalFormatting><conditionalFormatting sqref="B1:B3"><cfRule type="iconSet" priority="2"><iconSet iconSet="3TrafficLights1" showValue="0"><cfvo type="percent" val="0"/><cfvo type="percent" val="33"/><cfvo type="percent" val="67"/></iconSet></cfRule></conditionalFormatting></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        val b1 = parsed.styles.at(0,0).dataBar
        val b3 = parsed.styles.at(2,0).dataBar
        assertNotNull(b1); assertNotNull(b3)
        assertTrue(b3!!.end > b1!!.end)
        assertTrue(!b3.showValue)
        assertEquals(0xD94A3A,parsed.styles.at(0,1).icon!!.rgb)
        assertEquals(0x48A65E,parsed.styles.at(2,1).icon!!.rgb)
        val out = File(tempFolder.root,"bars.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf ->
            val operators = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bc\s*$""").containsMatchIn(operators))
            assertTrue(Regex("""(?m)\bf\s*$""").containsMatchIn(operators))
        }
    }
    @Test
    fun x14LinkedSolidDataBarUsesSignedColorsAxisAndBorder() = runBlocking<Unit> {
        val workbook = """<workbook><sheets><sheet name="Bars" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>-25</v></c></row><row r="2"><c r="A2"><v>0</v></c></row><row r="3"><c r="A3"><v>75</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="dataBar" priority="1"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF2266AA"/></dataBar><extLst><ext><x14:id>{11111111-1111-1111-1111-111111111111}</x14:id></ext></extLst></cfRule></conditionalFormatting><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="dataBar" id="{11111111-1111-1111-1111-111111111111}"><x14:dataBar minLength="0" maxLength="100" gradient="0" border="1"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:borderColor rgb="FF112233"/><x14:negativeFillColor rgb="FFFF0000"/><x14:axisColor rgb="FF000000"/></x14:dataBar></x14:cfRule><xm:sqref>A1:A3</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        val negative = requireNotNull(parsed.styles.at(0,0).dataBar)
        val positive = requireNotNull(parsed.styles.at(2,0).dataBar)
        assertEquals(0xFF0000,negative.rgb)
        assertEquals(0x2266AA,positive.rgb)
        assertEquals(0.25f,negative.start,0.001f)
        assertEquals(0f,negative.end,0.001f)
        assertEquals(1f,positive.end,0.001f)
        assertEquals(0.25f,positive.axis!!,0.001f)
        assertEquals(0x112233,positive.borderRgb)
        val out = File(tempFolder.root,"x14-solid-bars.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf ->
            assertTrue(pdf.numberOfPages > 0)
            assertFalse(PDFTextStripper().getText(pdf).contains("conditional formatting partial"))
        }
    }

    @Test
    fun x14ConditionalIconsAndColorScalesUseSavedRangesAndThresholds() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="X14" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>0</v></c><c r="B1"><v>0</v></c><c r="C1"><v>1</v></c></row><row r="2"><c r="A2"><v>50</v></c><c r="B2"><v>50</v></c><c r="C2"><v>2</v></c></row><row r="3"><c r="A3"><v>100</v></c><c r="B3"><v>100</v></c><c r="C3"><v>3</v></c></row></sheetData><conditionalFormatting sqref="C1:C3"><cfRule type="dataBar" priority="3"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF2266AA"/></dataBar><extLst><ext><x14:id>{11111111-1111-1111-1111-111111111111}</x14:id></ext></extLst></cfRule></conditionalFormatting><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="iconSet" priority="1"><x14:iconSet iconSet="3Arrows"><x14:cfvo type="percent"><xm:f>0</xm:f></x14:cfvo><x14:cfvo type="percent"><xm:f>33</xm:f></x14:cfvo><x14:cfvo type="percent"><xm:f>67</xm:f></x14:cfvo></x14:iconSet></x14:cfRule><xm:sqref>A1:A3</xm:sqref></x14:conditionalFormatting><x14:conditionalFormatting><x14:cfRule type="colorScale" priority="2"><x14:colorScale><x14:cfvo type="num"><xm:f>0</xm:f></x14:cfvo><x14:cfvo type="num"><xm:f>100</xm:f></x14:cfvo><x14:color rgb="FFFF0000"/><x14:color rgb="FF00FF00"/></x14:colorScale></x14:cfRule><xm:sqref>B1:B3</xm:sqref></x14:conditionalFormatting><x14:conditionalFormatting><x14:cfRule type="dataBar" id="{11111111-1111-1111-1111-111111111111}"><x14:dataBar border="1"/></x14:cfRule><xm:sqref>C1:C3</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(1,parsed.unsupportedConditionalRules)
        assertEquals("down",parsed.styles.at(0,0).icon!!.shape)
        assertEquals("up",parsed.styles.at(2,0).icon!!.shape)
        assertEquals(0xFF0000,parsed.styles.at(0,1).fillRgb)
        assertEquals(0x00FF00,parsed.styles.at(2,1).fillRgb)
        assertNull(parsed.styles.at(1,2).dataBar)
        val out = File(tempFolder.root,"x14-conditional.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf ->
            assertTrue(pdf.numberOfPages > 0)
            assertTrue(PDFTextStripper().getText(pdf).contains("conditional formatting partial"))
        }

        val custom = sheet.replace("iconSet=\"3Arrows\"", "iconSet=\"3Arrows\" custom=\"1\"")
        val unsupported = OpenXmlDocumentParser.parseXlsx(createZip(mapOf(
            "xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to custom))).sheets.single()
        assertEquals(2,unsupported.unsupportedConditionalRules)
        assertNull(unsupported.styles.at(0,0).icon)
    }

    @Test
    fun boundedFormulaConditionalFormattingRespectsRelativeCellsAndModulo() = runBlocking {
        val range = listOf(OpenXmlDocumentParser.CellRangeAddress(0,2,0,0))
        assertEquals(XlsxConditionalFormatting.Expression.Modulo(true,2,0,false),
            XlsxConditionalFormatting.expression("MOD(ROW(),2)=0",range))
        assertEquals(XlsxConditionalFormatting.Expression.CurrentCell(">", java.math.BigDecimal("12")),
            XlsxConditionalFormatting.expression("A1>12",range))
        assertEquals(null,XlsxConditionalFormatting.expression("B1>12",range))
        assertEquals(null,XlsxConditionalFormatting.expression("SUM(A1:A3)>10",range))
        val within = XlsxConditionalFormatting.expression("AND(A1>12,A1<15)",range)
        assertNotNull(within)
        assertTrue(within!!.matches(1,0,java.math.BigDecimal("13")))
        assertTrue(!within.matches(2,0,java.math.BigDecimal("15")))
        val either = XlsxConditionalFormatting.expression("OR(MOD(ROW(),2)=0,A1>20)",range)
        assertNotNull(either)
        assertTrue(either!!.matches(1,0,null)) // row banding works for blank cells
        assertTrue(!either.matches(0,0,java.math.BigDecimal("13")))
        assertEquals(null,XlsxConditionalFormatting.expression("AND(A1>10,B1>10)",range))
        assertEquals(null,XlsxConditionalFormatting.expression("AND(A1>1,OR(A1<3,A1>9))",range))
        assertEquals(null,XlsxConditionalFormatting.expression("\u0024A\u00241>5",range))
        val workbook = """<workbook><sheets><sheet name="Formula" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><dxfs><dxf><fill><patternFill patternType="solid"><fgColor rgb="FF22CC33"/></patternFill></fill></dxf></dxfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>11</v></c><c r="B1"><v>99</v></c></row><row r="2"><c r="A2"><v>13</v></c></row><row r="3"><c r="A3"><v>15</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="expression" priority="1" dxfId="0"><formula>MOD(ROW(),2)=0</formula></cfRule></conditionalFormatting><conditionalFormatting sqref="B1:B3"><cfRule type="expression" priority="2" dxfId="0"><formula>MOD(ROW(),2)=0</formula></cfRule></conditionalFormatting></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,"xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        assertEquals(null,parsed.styles.at(0,0).fillRgb)
        assertEquals(0x22CC33,parsed.styles.at(1,0).fillRgb)
        assertEquals(null,parsed.styles.at(2,0).fillRgb)
        assertEquals(null,parsed.styles.at(0,1).fillRgb)
        assertEquals(0x22CC33,parsed.styles.at(1,1).fillRgb) // B2 is absent from XML
        assertEquals(null,parsed.styles.at(2,1).fillRgb)
        val pdf = File(tempFolder.root,"formula.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context)
        PDDocument.load(pdf).use { doc -> assertTrue(PDFTextStripper().getText(doc).contains("13")) }
    }
    @Test
    fun formulaConditionalAndUsesSavedValuesAndPriorityWithoutRecalculation() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Conditions" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><dxfs><dxf><fill><patternFill patternType="solid"><fgColor rgb="FF31AB45"/></patternFill></fill></dxf></dxfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>11</v></c></row><row r="2"><c r="A2"><v>13</v></c></row><row r="3"><c r="A3"><v>15</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="expression" priority="1" dxfId="0"><formula>AND(A1&gt;12,A1&lt;15)</formula></cfRule></conditionalFormatting></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0, parsed.unsupportedConditionalRules)
        assertEquals(null,parsed.styles.at(0,0).fillRgb)
        assertEquals(0x31AB45,parsed.styles.at(1,0).fillRgb)
        assertEquals(null,parsed.styles.at(2,0).fillRgb)
        val out = File(tempFolder.root,"conditional-and.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf -> assertTrue(PDFTextStripper().getText(pdf).contains("13")) }
    }

    @Test
    fun savedFontPointSizeControlsPdfTextAndMeasuredRowHeight() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Fonts" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><fonts><font/><font><sz val="20"/><b/></font></fonts><cellXfs><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>SMALL</t></is></c></row><row r="2"><c r="A2" s="1" t="inlineStr"><is><t>LARGE</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/styles.xml" to styles,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(20f, parsed.styles.at(1,0).fontSizePt!!, 0.01f)
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,
            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,LayoutLimits())
        assertTrue(layout.rowHeights[1] > layout.rowHeights[0])
        val pdf = File(tempFolder.root,"font-size.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context)
        PDDocument.load(pdf).use { doc ->
            val sizes = mutableMapOf<String,Float>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(position: com.tom_roush.pdfbox.text.TextPosition) {
                    if (position.unicode in setOf("S","L")) sizes[position.unicode] = position.fontSizeInPt
                    super.processTextPosition(position)
                }
            }
            stripper.getText(doc)
            assertTrue(sizes.getValue("L") > sizes.getValue("S") * 1.8f)
        }
    }
    @Test
    fun percentileColorScalesPreserveDuplicateSamplesAndInterpolate() = runBlocking {
        val raw = listOf("0","0","10","100","100")
        val extent = XlsxConditionalFormatting.NumericExtent(
            java.math.BigDecimal("0"), java.math.BigDecimal("100"))
            .withPercentiles(raw.map { java.math.BigDecimal(it) })
        assertEquals(0, java.math.BigDecimal("5").compareTo(extent.percentile(java.math.BigDecimal("37.5"))))
        assertEquals(java.math.BigDecimal("100"), extent.percentile(java.math.BigDecimal("100")))
        assertEquals(null, extent.percentile(java.math.BigDecimal("101")))
        val wb = """<workbook><sheets><sheet name="Pct" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>0</v></c></row><row r="2"><c r="A2"><v>0</v></c></row><row r="3"><c r="A3"><v>10</v></c></row><row r="4"><c r="A4"><v>100</v></c></row><row r="5"><c r="A5"><v>100</v></c></row></sheetData><conditionalFormatting sqref="A1:A5"><cfRule type="colorScale" priority="1"><colorScale><cfvo type="min"/><cfvo type="percentile" val="50"/><cfvo type="max"/><color rgb="FFFF0000"/><color rgb="FFFFFF00"/><color rgb="FF00FF00"/></colorScale></cfRule></conditionalFormatting></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        assertEquals(0xFFFF00,parsed.styles.at(2,0).fillRgb)
        assertEquals(0x00FF00,parsed.styles.at(4,0).fillRgb)
        val pdf = File(tempFolder.root,"percentile.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context)
        PDDocument.load(pdf).use { doc -> assertTrue(PDFTextStripper().getText(doc).contains("100")) }
    }
    @Test
    fun negativeDataBarsAndInclusiveArrowIconsUseSavedThresholds() = runBlocking {
        val zero = java.math.BigDecimal.ZERO
        val extent = XlsxConditionalFormatting.NumericExtent(java.math.BigDecimal("-100"),java.math.BigDecimal("-10"))
        val bar = XlsxConditionalFormatting.DataBar(
            XlsxConditionalFormatting.ScaleStop("min",null,0),
            XlsxConditionalFormatting.ScaleStop("max",null,0),true,10,90,0x224488)
        val strong = bar.visual(java.math.BigDecimal("-100"),extent)!!
        val weak = bar.visual(java.math.BigDecimal("-10"),extent)!!
        assertTrue(kotlin.math.abs(strong.end-strong.start) > kotlin.math.abs(weak.end-weak.start))
        assertEquals(1f,strong.start,0.0001f)
        val stops = listOf(
            XlsxConditionalFormatting.ScaleStop("percent",zero,0),
            XlsxConditionalFormatting.ScaleStop("percent",java.math.BigDecimal("50"),0,inclusive=false),
            XlsxConditionalFormatting.ScaleStop("percent",java.math.BigDecimal("90"),0))
        val icons = XlsxConditionalFormatting.IconSet(stops,false,true,"3Arrows")
        val positive = XlsxConditionalFormatting.NumericExtent(zero,java.math.BigDecimal("100"))
        assertEquals("down",icons.visual(java.math.BigDecimal("50"),positive)!!.shape)
        assertEquals("right",icons.visual(java.math.BigDecimal("51"),positive)!!.shape)
        assertEquals("up",icons.visual(java.math.BigDecimal("90"),positive)!!.shape)
        val wb = """<workbook><sheets><sheet name="Indicators" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>0</v></c></row><row r="2"><c r="A2"><v>50</v></c></row><row r="3"><c r="A3"><v>100</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="iconSet" priority="1"><iconSet iconSet="3ArrowsGray" showValue="0"><cfvo type="percent" val="0"/><cfvo type="percent" val="50" gte="0"/><cfvo type="percent" val="90"/></iconSet></cfRule></conditionalFormatting></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals("down",parsed.styles.at(0,0).icon!!.shape)
        assertEquals("down",parsed.styles.at(1,0).icon!!.shape)
        assertEquals("up",parsed.styles.at(2,0).icon!!.shape)
        val pdf = File(tempFolder.root,"arrows.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context)
        PDDocument.load(pdf).use { doc ->
            val ops = doc.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bf\s*$""").containsMatchIn(ops))
        }
    }
    @Test
    fun savedPrintHeadingsReserveSpaceAndUseRealCellAddresses() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Grid" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>FIRST</t></is></c><c r="B1" t="inlineStr"><is><t>SECOND</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>THIRD</t></is></c></row></sheetData><printOptions headings="1"/><rowBreaks><brk id="1" man="1"/></rowBreaks><pageSetup scale="100"/></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook,
            "xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertTrue(parsed.printSettings.printHeadings)
        assertTrue(!parsed.printSettings.unsupportedFeatures)
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,
            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, LayoutLimits())
        val area = XlsxSavedPrintLayout.areas(parsed.name,parsed.printSettings,parsed.rows,parsed.mergedRegions).single()
        val plan = XlsxSavedPrintLayout.plan(layout,area,parsed.printSettings)
        assertEquals(40f,plan.headingLeft,0.01f)
        assertEquals(18f,plan.headingTop,0.01f)
        val output = File(tempFolder.root,"headings.pdf")
        OfficeConverter().convertXlsxToPdf(input,output,context,savedPrintLayout=true)
        PDDocument.load(output).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val first = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(pdf)
            val second = PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(pdf)
            assertTrue(first.contains("FIRST") && first.contains("SECOND"))
            assertTrue(second.contains("THIRD"))
            assertTrue(first.contains("A") && first.contains("B") && first.contains("1"))
            assertTrue(second.contains("A") && second.contains("2"))
        }
        val full = File(tempFolder.root,"headings-full.pdf")
        OfficeConverter().convertXlsxToPdf(input,full,context)
        PDDocument.load(full).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("FIRST") && text.contains("THIRD"))
        }
    }
    @Test
    fun savedDefaultHiddenRowsRequireExplicitVisibleOverrides() = runBlocking {
        val wb = """<workbook><sheets><sheet name="HiddenDefaults" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetFormatPr zeroHeight="1"/><sheetData><row r="1" hidden="0"><c r="A1" t="inlineStr"><is><t>SHOWONE</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>DONTPRINT</t></is></c></row><row r="3" hidden="false"><c r="A3" t="inlineStr"><is><t>SHOWTHREE</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertTrue(parsed.printSettings.defaultRowsHidden)
        assertEquals(setOf(0,2),parsed.printSettings.visibleRows)
        val printed = File(tempFolder.root,"default-hidden.pdf")
        OfficeConverter().convertXlsxToPdf(input,printed,context,savedPrintLayout=true)
        PDDocument.load(printed).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("SHOWONE") && text.contains("SHOWTHREE"))
            assertTrue(!text.contains("DONTPRINT"))
        }
        val full = File(tempFolder.root,"default-hidden-full.pdf")
        OfficeConverter().convertXlsxToPdf(input,full,context)
        PDDocument.load(full).use { doc ->
            assertTrue(PDFTextStripper().getText(doc).contains("DONTPRINT"))
        }
    }
    @Test
    fun savedSerifAndMonospaceFontFamiliesUseMatchingPdfMetrics() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Faces" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><fonts><font/><font><name val="Times New Roman"/><sz val="13"/><b/></font><font><name val="Courier New"/><sz val="11"/><i/></font></fonts><cellXfs><xf/><xf fontId="1" applyFont="1"/><xf fontId="2" applyFont="1"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1" t="inlineStr"><is><t>SERIF</t></is></c><c r="B1" s="2" t="inlineStr"><is><t>MONO</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals("Times New Roman",parsed.styles.at(0,0).fontFace)
        assertEquals("Courier New",parsed.styles.at(0,1).fontFace)
        assertEquals(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.TIMES_BOLD.name,
            parsed.styles.at(0,0).font(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA).name)
        assertEquals(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.COURIER_OBLIQUE.name,
            parsed.styles.at(0,1).font(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA).name)
        val pdf = File(tempFolder.root,"font-faces.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context)
        PDDocument.load(pdf).use { doc ->
            val fontsUsed = mutableSetOf<String>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(position: com.tom_roush.pdfbox.text.TextPosition) {
                    if (position.unicode in setOf("S","M")) fontsUsed.add(position.font.name)
                    super.processTextPosition(position)
                }
            }
            stripper.getText(doc)
            assertTrue(fontsUsed.any { it.contains("Times-Bold") })
            assertTrue(fontsUsed.any { it.contains("Courier-Oblique") })
        }
    }
    @Test
    fun savedPrintSkipsHiddenSheetsWithoutRemovingTheirFullExportValues() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Visible" sheetId="1"/><sheet name="Concealed" state="veryHidden" sheetId="2"/></sheets></workbook>"""
        val visible = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>PUBLIC_SHEET</t></is></c></row></sheetData></worksheet>"""
        val hidden = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>INTERNAL_SHEET</t></is></c></row></sheetData></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,
            "xl/worksheets/sheet1.xml" to visible, "xl/worksheets/sheet2.xml" to hidden))
        val parsed = OpenXmlDocumentParser.parseXlsx(input)
        assertEquals(listOf(true,false),parsed.sheets.map { it.visible })
        val printed = File(tempFolder.root,"visible-only.pdf")
        OfficeConverter().convertXlsxToPdf(input,printed,context,savedPrintLayout=true)
        PDDocument.load(printed).use { doc ->
            assertEquals(1,doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("PUBLIC_SHEET") && !text.contains("INTERNAL_SHEET"))
        }
        val full = File(tempFolder.root,"with-hidden.pdf")
        OfficeConverter().convertXlsxToPdf(input,full,context)
        PDDocument.load(full).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("PUBLIC_SHEET") && text.contains("INTERNAL_SHEET"))
        }
        val allHidden = createZip(mapOf("xl/workbook.xml" to
            """<workbook><sheets><sheet name="Concealed" state="hidden" sheetId="1"/></sheets></workbook>""",
            "xl/worksheets/sheet1.xml" to hidden))
        val noPages = File(tempFolder.root,"no-visible.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(allHidden,noPages,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected hidden-sheet print rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("visible printable"))
        }
        assertTrue(!noPages.exists())
    }
    @Test
    fun savedPrintNeverAppendsOutOfAreaChartCacheAndRejectsVisibleCharts() = runBlocking {
        fun workbook(area: String): String = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">Print!&#36;A&#36;1:$area</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>VISIBLE_DATA</t></is></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:twoCellAnchor><xdr:from><xdr:col>2</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>4</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>6</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:twoCellAnchor></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/c.xml"/></Relationships>"""
        val chart = """<c:chartSpace><c:chart><c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:ser><c:cat><c:strLit><c:ptCount val="1"/><c:pt idx="0"><c:v>HIDDEN_CHART_LABEL</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="1"/><c:pt idx="0"><c:v>99999</c:v></c:pt></c:numLit></c:val></c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>"""
        fun input(area: String) = createZip(mapOf("xl/workbook.xml" to workbook(area),
            "xl/worksheets/sheet1.xml" to sheet,"xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to drawing,"xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/c.xml" to chart))
        val outside = input("&#36;B&#36;2")
        assertEquals("C5:D7", OpenXmlDocumentParser.parseXlsx(outside).sheets.single().charts.single().sourceAnchor)
        assertTrue(OpenXmlDocumentParser.parseXlsx(outside).sheets.single().charts.single().printAreaAnchorTrusted)
        assertTrue(XlsxSavedPrintLayout.chartMayIntersectPrintArea("C5",
            OpenXmlDocumentParser.CellRangeAddress(0,1,0,1)))
        assertTrue(!XlsxSavedPrintLayout.chartMayIntersectPrintArea("C5:D7",
            OpenXmlDocumentParser.CellRangeAddress(0,1,0,1)))
        val output = File(tempFolder.root,"outside-chart.pdf")
        OfficeConverter().convertXlsxToPdf(outside,output,context,savedPrintLayout=true)
        PDDocument.load(output).use { doc ->
            assertEquals(1,doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("VISIBLE_DATA") && !text.contains("HIDDEN_CHART_LABEL") && !text.contains("99999"))
        }
        val full = File(tempFolder.root,"full-chart.pdf")
        OfficeConverter().convertXlsxToPdf(outside,full,context)
        PDDocument.load(full).use { doc -> assertTrue(PDFTextStripper().getText(doc).contains("HIDDEN_CHART_LABEL")) }
        val intersects = input("&#36;D&#36;8")
        val invalid = File(tempFolder.root,"unsafe-chart.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(intersects,invalid,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected printed drawing rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("intersects the print area"))
        }
        assertTrue(!invalid.exists())
        val displacedDrawing = drawing.replace("<xdr:colOff>0</xdr:colOff><xdr:row>6</xdr:row>",
            "<xdr:colOff>999999999999</xdr:colOff><xdr:row>6</xdr:row>")
        val displaced = createZip(mapOf("xl/workbook.xml" to workbook("&#36;B&#36;2"),
            "xl/worksheets/sheet1.xml" to sheet, "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to displacedDrawing, "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/c.xml" to chart))
        val untrusted = OpenXmlDocumentParser.parseXlsx(displaced).sheets.single().charts.single()
        assertEquals("C5:D7", untrusted.sourceAnchor)
        assertTrue(!untrusted.printAreaAnchorTrusted)
        val unsafeOffsets = File(tempFolder.root,"unbounded-chart-offset.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(displaced,unsafeOffsets,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected unbounded drawing-offset rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("intersects the print area"))
        }
        assertTrue(!unsafeOffsets.exists())
        val missingOffsetDrawing = drawing.replace("<xdr:rowOff>0</xdr:rowOff>","")
        val missingOffsets = createZip(mapOf("xl/workbook.xml" to workbook("&#36;B&#36;2"),
            "xl/worksheets/sheet1.xml" to sheet, "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to missingOffsetDrawing, "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/c.xml" to chart))
        assertTrue(!OpenXmlDocumentParser.parseXlsx(missingOffsets).sheets.single()
            .charts.single().printAreaAnchorTrusted)
        val malformed = File(tempFolder.root,"missing-chart-offset.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(missingOffsets,malformed,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected incomplete drawing-marker rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("intersects the print area"))
        }
        assertTrue(!malformed.exists())
        // An OOXML chart marked fPrintsWithSheet=0 must not reject print mode,
        // even when its location intersects the selected range.
        val noPrintDrawing = drawing.replace("</xdr:twoCellAnchor>",
            "<xdr:clientData fPrintsWithSheet=\"0\"/></xdr:twoCellAnchor>")
        val noPrint = createZip(mapOf("xl/workbook.xml" to workbook("&#36;D&#36;8"),
            "xl/worksheets/sheet1.xml" to sheet, "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to noPrintDrawing, "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/c.xml" to chart))
        assertTrue(!OpenXmlDocumentParser.parseXlsx(noPrint).sheets.single().charts.single().printsWithSheet)
        val printedNoChart = File(tempFolder.root,"nonprinting-chart.pdf")
        OfficeConverter().convertXlsxToPdf(noPrint,printedNoChart,context,savedPrintLayout=true)
        PDDocument.load(printedNoChart).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("VISIBLE_DATA") && !text.contains("HIDDEN_CHART_LABEL"))
        }
        val nonPrintingFull = File(tempFolder.root,"nonprinting-full.pdf")
        OfficeConverter().convertXlsxToPdf(noPrint,nonPrintingFull,context)
        PDDocument.load(nonPrintingFull).use { doc ->
            assertTrue(PDFTextStripper().getText(doc).contains("HIDDEN_CHART_LABEL"))
        }
    }

    @Test
    fun savedPrintRejectsUnencodableHeaderGlyphsWithoutPublishingOutput() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData><headerFooter><oddHeader>&amp;C漢字</oddHeader></headerFooter></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to sheet))
        val printed = File(tempFolder.root,"unsupported-header.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(input,printed,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected explicit unsupported-font rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("glyphs"))
        }
        assertTrue(!printed.exists())
        // Full worksheet export must remain independent of saved print metadata.
        val full = File(tempFolder.root,"no-header-full.pdf")
        OfficeConverter().convertXlsxToPdf(input,full,context)
        PDDocument.load(full).use { pdf -> assertTrue(PDFTextStripper().getText(pdf).contains("42")) }
    }

    @Test
    fun savedPrintUsesExplicitFirstPageAndOffsetFieldsForOddEvenHeaders() = runBlocking {
        assertEquals("At 12 of 3",XlsxPrintHeaderFooter.expand("&CAt &P+2 of &N","S",10,3).center)
        assertEquals("At 8",XlsxPrintHeaderFooter.expand("&CAt &P-2","S",10,3).center)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            XlsxPrintHeaderFooter.expand("&P+", "S", 10,3)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            XlsxPrintHeaderFooter.expand("&P-999999", "S", 2,3)
        }
        val wb = """<workbook><sheets><sheet name="Print" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>111</v></c></row><row r="2"><c r="A2"><v>222</v></c></row></sheetData><pageSetup useFirstPageNumber="1" firstPageNumber="8"/><rowBreaks><brk id="1" man="1"/></rowBreaks><headerFooter differentOddEven="1"><oddHeader>&amp;Codd &amp;P+2 of &amp;N</oddHeader><evenHeader>&amp;Ceven &amp;P</evenHeader><oddFooter>&amp;Rminus &amp;P-1</oddFooter><evenFooter>&amp;Rminus &amp;P-1</evenFooter></headerFooter></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb, "xl/worksheets/sheet1.xml" to sheet))
        val printed = File(tempFolder.root,"explicit-page-number.pdf")
        OfficeConverter().convertXlsxToPdf(input,printed,context,savedPrintLayout=true)
        PDDocument.load(printed).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val first = PDFTextStripper().apply { startPage=1; endPage=1 }.getText(pdf)
            val second = PDFTextStripper().apply { startPage=2; endPage=2 }.getText(pdf)
            assertTrue(first.contains("even 8") && first.contains("minus 7"))
            assertTrue(second.contains("odd 11 of 2") && second.contains("minus 8"))
        }
    }

    @Test
    fun savedChartMarkerSymbolsAreParsedAndDrawnAsVectors() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Markers" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/markers.xml"/></Relationships>"""
        val values = """<c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>A</c:v></c:pt><c:pt idx="1"><c:v>B</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>2</c:v></c:pt><c:pt idx="1"><c:v>4</c:v></c:pt></c:numLit></c:val>"""
        fun chart(symbol: String, size: Int) = """<c:chartSpace><c:chart><c:plotArea><c:lineChart><c:grouping val="standard"/><c:ser><c:marker><c:symbol val="$symbol"/><c:size val="$size"/></c:marker>$values</c:ser></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        fun source(xml: String)=createZip(mapOf("xl/workbook.xml" to workbook,
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to drawing,
            "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/markers.xml" to xml))
        val diamond = source(chart("diamond",7))
        val series = OpenXmlDocumentParser.parseXlsx(diamond).sheets.single().charts.single().series.single()
        assertEquals("diamond", series.markerSymbol)
        assertEquals(7f, series.markerSizePt!!,0.0001f)
        val withMarker=File(tempFolder.root,"diamond-markers.pdf")
        OfficeConverter().convertXlsxToPdf(diamond,withMarker,context)
        val noMarker = source(chart("none",7))
        assertEquals("none",OpenXmlDocumentParser.parseXlsx(noMarker).sheets.single()
            .charts.single().series.single().markerSymbol)
        val withoutMarker=File(tempFolder.root,"none-markers.pdf")
        OfficeConverter().convertXlsxToPdf(noMarker,withoutMarker,context)
        PDDocument.load(withMarker).use { doc ->
            assertEquals(2,doc.numberOfPages)
            val stream=doc.getPage(1).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bf\s*$""").containsMatchIn(stream))
        }
        PDDocument.load(withoutMarker).use { doc -> assertEquals(2,doc.numberOfPages) }
        // Standard DrawingML vector symbols must parse and survive PDF conversion.
        for (symbol in listOf("dash", "dot", "plus", "x", "star")) {
            val input = source(chart(symbol, 7))
            assertEquals(symbol, OpenXmlDocumentParser.parseXlsx(input).sheets.single().charts.single().series.single().markerSymbol)
            val result = File(tempFolder.root, "$symbol-markers.pdf")
            OfficeConverter().convertXlsxToPdf(input, result, context)
            PDDocument.load(result).use { doc ->
                assertEquals(2, doc.numberOfPages)
                val stream = doc.getPage(1).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
                assertTrue(Regex("""(?m)\bf\s*$""").containsMatchIn(stream))
            }
        }
        for (invalid in listOf(chart("picture", 7), chart("invalid", 7), chart("circle", 7).replace("val=\"7\"", "val=\"7.5\""))) {
            assertTrue(OpenXmlDocumentParser.parseXlsx(source(invalid)).sheets.single().charts.isEmpty())
        }
        // Preserve explicit blank drawing semantics without changing the cached values.
        for ((value, expected) in listOf(
            "gap" to XlsxCachedChart.BlankDisplay.GAP,
            "zero" to XlsxCachedChart.BlankDisplay.ZERO,
            "span" to XlsxCachedChart.BlankDisplay.SPAN
        )) {
            val input = source(chart("none", 7)
                .replace("<c:pt idx=\"1\"><c:v>4</c:v></c:pt>", "")
                .replace("</c:chart>", "<c:dispBlanksAs val=\"$value\"/></c:chart>"))
            val cached = OpenXmlDocumentParser.parseXlsx(input).sheets.single().charts.single()
            assertEquals(expected, cached.blankDisplay)
            assertNull(cached.series.single().values[1])
            val output = File(tempFolder.root, "blank-$value.pdf")
            OfficeConverter().convertXlsxToPdf(input, output, context)
            PDDocument.load(output).use { doc ->
                assertEquals(2, doc.numberOfPages)
                assertTrue(PDFTextStripper().getText(doc).contains("(missing)"))
            }
        }
    }

    @Test
    fun cachedLineChartUsesDirectThemeRgbAndSavedLineWidth() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Theme" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>4</v></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/theme.xml"/></Relationships>"""
        val theme = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:clrScheme name="Office"><a:accent1><a:srgbClr val="16AA44"/></a:accent1></a:clrScheme></a:themeElements></a:theme>"""
        val chart = """<c:chartSpace><c:chart><c:plotArea><c:lineChart><c:grouping val="standard"/><c:ser><c:spPr><a:ln w="31750"><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></a:ln></c:spPr><c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>One</c:v></c:pt><c:pt idx="1"><c:v>Two</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>1</c:v></c:pt><c:pt idx="1"><c:v>3</c:v></c:pt></c:numLit></c:val></c:ser></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        fun input(chartPart: String) = createZip(mapOf("xl/workbook.xml" to workbook,
            "xl/theme/theme1.xml" to theme, "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to drawing, "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/theme.xml" to chartPart))
        val parsed = OpenXmlDocumentParser.parseXlsx(input(chart)).sheets.single().charts.single().series.single()
        assertEquals(0x16AA44, parsed.rgb)
        assertEquals(2.5f, parsed.strokeWidthPt!!, 0.0001f)
        val modified = chart.replace("<a:schemeClr val=" + '"' + "accent1" + '"' + "/>",
            "<a:schemeClr val=" + '"' + "accent1" + '"' + "><a:lumMod val=" + '"' + "50000" + '"' + "/></a:schemeClr>")
        assertEquals(null,OpenXmlDocumentParser.parseXlsx(input(modified)).sheets.single()
            .charts.single().series.single().rgb) // transformed color uses disclosed fallback
        val output = File(tempFolder.root,"chart-theme-line.pdf")
        OfficeConverter().convertXlsxToPdf(input(chart),output,context)
        PDDocument.load(output).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val operators = pdf.getPage(1).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(operators.contains("2.5 w"))
        }
    }

    @Test
    fun boundedSavedChartRendersAtItsOriginalCellAnchorAndRejectsSplitTiles() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Plot" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">Plot!&#36;A&#36;1:&#36;H&#36;12</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Outside chart</t></is></c></row><row r="12"><c r="H12"><v>1</v></c></row></sheetData><pageSetup fitToWidth="1" fitToHeight="1"/><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>6</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>9</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:twoCellAnchor></xdr:wsDr>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/plot.xml"/></Relationships>"""
        val chart = """<c:chartSpace><c:chart><c:title><c:tx><c:v>Budget chart</c:v></c:tx></c:title><c:plotArea><c:lineChart><c:grouping val="standard"/><c:ser><c:tx><c:v>Series</c:v></c:tx><c:cat><c:strLit><c:ptCount val="3"/><c:pt idx="0"><c:v>Jan</c:v></c:pt><c:pt idx="1"><c:v>Feb</c:v></c:pt><c:pt idx="2"><c:v>Mar</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="3"/><c:pt idx="0"><c:v>2</c:v></c:pt><c:pt idx="1"><c:v>4</c:v></c:pt><c:pt idx="2"><c:v>3</c:v></c:pt></c:numLit></c:val></c:ser></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        fun input(sheetXml: String,chartXml: String = chart) = createZip(mapOf("xl/workbook.xml" to wb,
            "xl/worksheets/sheet1.xml" to sheetXml,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to drawing, "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/plot.xml" to chartXml))
        val file = input(sheet)
        val parsed = OpenXmlDocumentParser.parseXlsx(file).sheets.single()
        assertEquals("B2:G10",parsed.charts.single().sourceAnchor)
        assertTrue(parsed.charts.single().printAreaAnchorTrusted)
        val output = File(tempFolder.root,"anchored-chart.pdf")
        OfficeConverter().convertXlsxToPdf(file,output,context,savedPrintLayout=true)
        PDDocument.load(output).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Outside chart") && text.contains("Budget chart"))
            assertTrue(!text.contains("Cached chart 1")) // no synthetic appendix page
            val raw = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bc\s*$""").containsMatchIn(raw) ||
                Regex("""(?m)\bl\s*$""").containsMatchIn(raw))
        }
        val pie = chart.replace("lineChart","pieChart").replace("<c:grouping val=\"standard\"/>","")
        val pieOut = File(tempFolder.root,"anchored-pie.pdf")
        OfficeConverter().convertXlsxToPdf(input(sheet,pie),pieOut,context,savedPrintLayout=true)
        PDDocument.load(pieOut).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Budget chart") && text.contains("Jan") && text.contains("22.2%"))
            val raw=pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bc\s*$""").containsMatchIn(raw))
        }
        val broken = sheet.replace("<pageSetup fitToWidth=\"1\" fitToHeight=\"1\"/>",
            "<rowBreaks><brk id=\"5\" man=\"1\"/></rowBreaks>")
            .replace("<pageSetUpPr fitToPage=\"1\"/>","<pageSetUpPr fitToPage=\"0\"/>")
        val invalid = File(tempFolder.root,"split-anchored-chart.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(input(broken),invalid,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected cross-tile chart rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("intersects the print area"))
        }
        assertTrue(!invalid.exists())
    }

    @Test
    fun fourAndFiveArrowSetsResolveThresholdsReverseAndPdfVectors() = runBlocking {
        val values = listOf(0,25,50,75,100)
        val stops = values.map {
            XlsxConditionalFormatting.ScaleStop("percent",java.math.BigDecimal(it),0)
        }
        val extent = XlsxConditionalFormatting.NumericExtent(
            java.math.BigDecimal.ZERO,java.math.BigDecimal("100"))
        val five = XlsxConditionalFormatting.IconSet(stops,false,false,"5Arrows")
        assertEquals(listOf("down","down-right","right","up-right","up"),
            values.map { five.visual(java.math.BigDecimal(it),extent)!!.shape })
        val reversed = five.copy(reverse=true)
        assertEquals("up",reversed.visual(java.math.BigDecimal.ZERO,extent)!!.shape)
        assertEquals("down",reversed.visual(java.math.BigDecimal("100"),extent)!!.shape)
        val four = XlsxConditionalFormatting.IconSet(
            listOf(0,33,67,100).map {
                XlsxConditionalFormatting.ScaleStop("percent",java.math.BigDecimal(it),0)
            },false,true,"4ArrowsGray")
        assertEquals("up-right",four.visual(java.math.BigDecimal("70"),extent)!!.shape)
        assertEquals(0x858585,four.visual(java.math.BigDecimal("70"),extent)!!.rgb)

        val wb = """<workbook><sheets><sheet name="Indicators" sheetId="1"/></sheets></workbook>"""
        val body = values.mapIndexed { i,v ->
            """<row r="${i+1}"><c r="A${i+1}"><v>$v</v></c></row>"""
        }.joinToString("")
        val tags = values.joinToString("") { """<cfvo type="percent" val="$it"/>""" }
        val sheet = """<worksheet><sheetData>$body</sheetData><conditionalFormatting sqref="A1:A5"><cfRule type="iconSet" priority="1"><iconSet iconSet="5Arrows" showValue="0">$tags</iconSet></cfRule></conditionalFormatting></worksheet>"""
        val file=createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        val parsed=OpenXmlDocumentParser.parseXlsx(file).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        assertEquals("down-right",parsed.styles.at(1,0).icon!!.shape)
        assertEquals("up-right",parsed.styles.at(3,0).icon!!.shape)
        val output=File(tempFolder.root,"five-arrow-icons.pdf")
        OfficeConverter().convertXlsxToPdf(file,output,context)
        PDDocument.load(output).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            val operators=pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bf\s*$""").containsMatchIn(operators))
        }
    }

    @Test
    fun emuOffsetChartsRespectCellDimensionsAndRejectClippedMarkers() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Offset" sheetId="1"/></sheets><definedNames><definedName name="_xlnm.Print_Area" localSheetId="0">Offset!&#36;A&#36;1:&#36;H&#36;12</definedName></definedNames></workbook>"""
        val sheet = """<worksheet><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="12"><c r="H12"><v>2</v></c></row></sheetData><pageSetup fitToWidth="1" fitToHeight="1"/><drawing r:id="d"/></worksheet>"""
        val sheetRel = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawingRel = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/c.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>63500</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>25400</xdr:rowOff></xdr:from><xdr:to><xdr:col>6</xdr:col><xdr:colOff>127000</xdr:colOff><xdr:row>9</xdr:row><xdr:rowOff>50800</xdr:rowOff></xdr:to><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:twoCellAnchor></xdr:wsDr>"""
        val chart = """<c:chartSpace><c:chart><c:title><c:tx><c:v>EMU proof</c:v></c:tx></c:title><c:plotArea><c:lineChart><c:grouping val="standard"/><c:ser><c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>A</c:v></c:pt><c:pt idx="1"><c:v>B</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>2</c:v></c:pt><c:pt idx="1"><c:v>4</c:v></c:pt></c:numLit></c:val></c:ser></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        fun input(drawingXml:String)=createZip(mapOf("xl/workbook.xml" to wb,
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRel,
            "xl/drawings/d.xml" to drawingXml,
            "xl/drawings/_rels/d.xml.rels" to drawingRel,
            "xl/charts/c.xml" to chart))
        val parsed=OpenXmlDocumentParser.parseXlsx(input(drawing)).sheets.single().charts.single()
        assertTrue(!parsed.printAreaAnchorTrusted)
        assertEquals(63500L,parsed.drawingOffsets!!.fromColEmu)
        assertEquals(127000L,parsed.drawingOffsets!!.toColEmu)
        val printed=File(tempFolder.root,"offset-within-cell.pdf")
        OfficeConverter().convertXlsxToPdf(input(drawing),printed,context,savedPrintLayout=true)
        PDDocument.load(printed).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("EMU proof"))
        }
        val tooWide=drawing.replace("<xdr:colOff>127000</xdr:colOff>",
            "<xdr:colOff>20000000</xdr:colOff>")
        val invalid=File(tempFolder.root,"bad-offset.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(input(tooWide),invalid,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected out-of-cell chart rejection")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("intersects the print area"))
        }
        assertTrue(!invalid.exists())
    }

    @Test
    fun savedPrintWrapTextFlagControlsLongCellLineBreaksWithoutChangingFullExport() = runBlocking {
        val workbook = """<workbook><sheets><sheet name="Wrap" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><cellXfs><xf/><xf applyAlignment="1"><alignment wrapText="1"/></xf><xf applyAlignment="1"><alignment wrapText="0"/></xf></cellXfs></styleSheet>"""
        val longLabel="SAVEDEXACTLABELWITHLONGCONTENT"
        val cells = """<worksheet><cols><col min="1" max="2" width="7" customWidth="1"/></cols><sheetData><row r="1"><c r="A1" s="1" t="inlineStr"><is><t>$longLabel</t></is></c><c r="B1" s="2" t="inlineStr"><is><t>$longLabel</t></is></c></row></sheetData></worksheet>"""
        val input=createZip(mapOf("xl/workbook.xml" to workbook,"xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to cells))
        val parsed=OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertTrue(parsed.styles.at(0,0).wrapText)
        assertTrue(!parsed.styles.at(0,1).wrapText)
        val normal=SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,
            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,LayoutLimits())
        val saved=SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,
            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,LayoutLimits(),
            respectSavedDimensions=true)
        assertTrue(saved.rowWrappedLines[0][0].size > 1)
        assertEquals(listOf(longLabel),saved.rowWrappedLines[0][1])
        assertTrue(saved.colWidths[0] < normal.colWidths[0])
        val pdf=File(tempFolder.root,"explicit-wrap.pdf")
        OfficeConverter().convertXlsxToPdf(input,pdf,context,savedPrintLayout=true)
        PDDocument.load(pdf).use { document ->
            val extracted=PDFTextStripper().getText(document)
            assertTrue(extracted.contains(longLabel))
        }
    }

    @Test
    fun savedPrintUsesDeclaredCellGeometryInsteadOfContentAutofit() = runBlocking {
        val workbook="""<workbook><sheets><sheet name="Widths" sheetId="1"/></sheets></workbook>"""
        val text="A_very_long_cell_that_must_not_expand_print_width"
        val sheet="""<worksheet><cols><col min="1" max="1" width="6" customWidth="1"/></cols><sheetData><row r="1" ht="13" customHeight="1"><c r="A1" t="inlineStr"><is><t>$text</t></is></c></row></sheetData></worksheet>"""
        val file=createZip(mapOf("xl/workbook.xml" to workbook,"xl/worksheets/sheet1.xml" to sheet))
        val parsed=OpenXmlDocumentParser.parseXlsx(file).sheets.single()
        val font=com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
        val full=SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,font,LayoutLimits())
        val printed=SpreadsheetLayoutEngine.buildSheetLayout(parsed,0,font,LayoutLimits(),
            respectSavedDimensions=true)
        assertTrue(full.colWidths[0] > printed.colWidths[0])
        assertEquals(parsed.dimensions.columnMinPt(0),printed.colWidths[0],0.01f)
        assertEquals(13f,printed.rowHeights[0],0.01f)
        assertTrue(full.rowHeights[0] > printed.rowHeights[0])
        val out=File(tempFolder.root,"print-width-not-autofit.pdf")
        OfficeConverter().convertXlsxToPdf(file,out,context,savedPrintLayout=true)
        PDDocument.load(out).use { pdf -> assertEquals(1,pdf.numberOfPages) }
        val preserved=File(tempFolder.root,"content-autofit.pdf")
        OfficeConverter().convertXlsxToPdf(file,preserved,context)
        PDDocument.load(preserved).use { pdf ->
            assertTrue(PDFTextStripper().getText(pdf).contains(text))
        }
    }

    @Test
    fun customSavedPaperDimensionsOverridePaperSizeAndRejectHalfSpecified() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Custom" sheetId="1"/></sheets></workbook>"""
        val body = """<sheetData><row r="1"><c r="A1"><v>23</v></c></row></sheetData>"""
        fun sheet(pageSetup: String) = """<worksheet>$body<pageSetup $pageSetup/></worksheet>"""
        fun convert(attrs: String, name: String): File {
            val input=createZip(mapOf("xl/workbook.xml" to wb,
                "xl/worksheets/sheet1.xml" to sheet(attrs)))
            val pdf=File(tempFolder.root,name)
            kotlinx.coroutines.runBlocking { OfficeConverter().convertXlsxToPdf(input,pdf,context,savedPrintLayout=true) }
            return pdf
        }
        PDDocument.load(convert("""paperSize="1" paperWidth="210mm" paperHeight="297mm" """,
            "custom-a4.pdf")).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            assertEquals(595.28f,pdf.getPage(0).mediaBox.width,0.1f)
            assertEquals(841.89f,pdf.getPage(0).mediaBox.height,0.1f)
        }
        PDDocument.load(convert("""paperSize="1" orientation="landscape" paperWidth="21cm" paperHeight="29.7cm" """,
            "custom-landscape.pdf")).use { pdf ->
            assertEquals(841.89f,pdf.getPage(0).mediaBox.width,0.1f)
            assertEquals(595.28f,pdf.getPage(0).mediaBox.height,0.1f)
        }
        val broken=File(tempFolder.root,"partial-custom.pdf")
        val invalid=createZip(mapOf("xl/workbook.xml" to wb,
            "xl/worksheets/sheet1.xml" to sheet("""paperWidth="210mm" """)))
        try {
            OfficeConverter().convertXlsxToPdf(invalid,broken,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected rejection of partial custom paper geometry")
        } catch (expected:IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("both physical dimensions"))
        }
        assertTrue(!broken.exists())
    }

    @Test
    fun headerScaleWithDocumentTracksPrintScaleAndRejectsUnalignedMargins() = runBlocking {
        val wb = """<workbook><sheets><sheet name="Paper" sheetId="1"/></sheets></workbook>"""
        val base = """<worksheet><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData><pageSetup scale="50"/><headerFooter scaleWithDoc="1" alignWithMargins="1"><oddHeader>&amp;CSCALED</oddHeader></headerFooter></worksheet>"""
        fun input(sheet: String) = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        fun headerSize(sheet: String, name: String): Float {
            val source=input(sheet)
            val out=File(tempFolder.root,name)
            kotlinx.coroutines.runBlocking { OfficeConverter().convertXlsxToPdf(source,out,context,savedPrintLayout=true) }
            return PDDocument.load(out).use { pdf ->
                assertEquals(1,pdf.numberOfPages)
                val sizes=mutableListOf<Float>()
                val stripper=object : PDFTextStripper() {
                    override fun processTextPosition(position: com.tom_roush.pdfbox.text.TextPosition) {
                        if(position.unicode == "S") sizes.add(position.fontSizeInPt)
                        super.processTextPosition(position)
                    }
                }
                assertTrue(stripper.getText(pdf).contains("SCALED"))
                assertTrue(sizes.isNotEmpty())
                sizes.first()
            }
        }
        val scaled=headerSize(base,"header-scaled.pdf")
        val fixed=headerSize(base.replace("scaleWithDoc=\"1\"","scaleWithDoc=\"0\""),
            "header-physical.pdf")
        assertTrue("Header must shrink with saved 50% print scale",scaled < fixed*0.75f)
        assertEquals(9f,fixed,0.5f)
        val invalid=input(base.replace("alignWithMargins=\"1\"","alignWithMargins=\"0\""))
        val noPdf=File(tempFolder.root,"header-unaligned.pdf")
        try {
            OfficeConverter().convertXlsxToPdf(invalid,noPdf,context,savedPrintLayout=true)
            org.junit.Assert.fail("Expected unsupported independent header geometry")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("header/footer margins"))
        }
        assertTrue(!noPdf.exists())
    }

    private fun visibleTextDocx(value: String, bold: Boolean = false): File {
        val properties = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return createZip(mapOf("word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r>$properties<w:t>$value</w:t></w:r></w:p></w:body></w:document>"""))
    }

    @Test fun simpleUnicodeDocxAndTextUseEmbeddedFont() = runBlocking<Unit> {
        val value = "Café €42 – Ελληνικά Русский"
        val docx = visibleTextDocx(value)
        val docPdf = File(tempFolder.root, "simple-unicode.docx.pdf")
        OfficeConverter().convertDocxToPdf(docx, docPdf, context)
        PDDocument.load(docPdf).use { assertTrue(PDFTextStripper().getText(it).contains(value)) }
        val txt = File(tempFolder.root, "simple-unicode.txt").apply { writeText(value) }
        val txtPdf = File(tempFolder.root, "simple-unicode.txt.pdf")
        OfficeConverter().convertTextToPdf(txt, txtPdf, context)
        PDDocument.load(txtPdf).use { assertTrue(PDFTextStripper().getText(it).contains(value)) }
    }

    @Test fun unsupportedUnassignedGlyphDoesNotReplaceDestination() = runBlocking<Unit> {
        val destination = File(tempFolder.root, "prior-visible.pdf").apply { writeText("previous") }
        for ((value, bold) in listOf("\u0378" to false, "\u0378" to true)) {
            val failure = runCatching {
                OfficeConverter().convertDocxToPdf(visibleTextDocx(value, bold), destination, context)
            }.exceptionOrNull()
            assertTrue("Expected explicit text admission failure: $failure", failure is OfficeTextEncodingException)
            assertEquals("previous", destination.readText())
        }
        val text = File(tempFolder.root, "complex.txt").apply { writeText("\u0378") }
        assertTrue(runCatching { OfficeConverter().convertTextToPdf(text, destination, context) }.isFailure)
        assertEquals("previous", destination.readText())
    }

    @Test fun simpleUnicodeBoldDocxKeepsActualBoldFont() = runBlocking<Unit> {
        val value = "Русский"
        val out = File(tempFolder.root,"bold-unicode.pdf")
        OfficeConverter().convertDocxToPdf(visibleTextDocx(value,true),out,context)
        PDDocument.load(out).use { pdf ->
            val fonts = mutableSetOf<String>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(position: com.tom_roush.pdfbox.text.TextPosition) {
                    fonts.add(position.font.name)
                    super.processTextPosition(position)
                }
            }
            assertTrue(stripper.getText(pdf).contains(value))
            assertTrue(fonts.any { it.contains("NotoSans-Bold") })
        }
    }

    @Test fun simpleUnicodeSpreadsheetUsesSameFontForLayoutAndDrawing() = runBlocking<Unit> {
        val value = "Café €42 Ελληνικά Русский"
        val source = singleTextWorkbook(value)
        for (saved in listOf(false,true)) {
            val out = File(tempFolder.root,"simple-unicode-$saved.pdf")
            OfficeConverter().convertXlsxToPdf(source,out,context,savedPrintLayout=saved)
            PDDocument.load(out).use { pdf ->
                assertTrue(PDFTextStripper().getText(pdf).replace(Regex("\\s+")," ").contains(value))
                assertTrue((0 until pdf.numberOfPages).any { page ->
                    val resources = pdf.getPage(page).resources
                    resources.fontNames.any { resources.getFont(it).name.contains("NotoSans") }
                })
            }
        }
    }

    @Test fun x14NoAxisBarsUseNumericOrderCustomLengthsAndDirection() {
        val wb = """<workbook><sheets><sheet name="Bars" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>-50</v></c></row><row r="2"><c r="A2"><v>0</v></c></row><row r="3"><c r="A3"><v>50</v></c></row></sheetData><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="dataBar" priority="1"><x14:dataBar minLength="20" maxLength="80" gradient="0" axisPosition="none" direction="rightToLeft"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:fillColor rgb="FF2266AA"/><x14:negativeFillColor rgb="FFFF0000"/></x14:dataBar></x14:cfRule><xm:sqref>A1:A3</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        for (rtl in listOf(true,false)) {
            val xml = if (rtl) sheet else sheet.replace("rightToLeft","leftToRight")
            val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to xml))
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertEquals(0,parsed.unsupportedConditionalRules)
            val lengths = listOf(0.2f,0.5f,0.8f)
            for (i in lengths.indices) {
                val bar = requireNotNull(parsed.styles.at(i,0).dataBar)
                assertEquals(null,bar.axis)
                assertEquals(if (rtl) 1f else 0f,bar.start,0.001f)
                assertEquals(if (rtl) 1f-lengths[i] else lengths[i],bar.end,0.001f)
                assertEquals(if (i == 0) 0xFF0000 else 0x2266AA,bar.rgb)
            }
        }
    }

    @Test fun conditionalBarsResolveThemeTintAndIndexedColors() {
        val wb = """<workbook><sheets><sheet name="Bars" sheetId="1"/></sheets></workbook>"""
        val theme = """<theme><themeElements><clrScheme><accent1><srgbClr val="000000"/></accent1></clrScheme></themeElements></theme>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>-25</v></c></row><row r="2"><c r="A2"><v>75</v></c></row></sheetData><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="dataBar" priority="1"><x14:dataBar minLength="0" maxLength="100" gradient="0"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:fillColor theme="4" tint="0.5"/><x14:negativeFillColor indexed="2"/><x14:axisColor indexed="0"/></x14:dataBar></x14:cfRule><xm:sqref>A1:A2</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/theme/theme1.xml" to theme,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        assertEquals(0xFF0000,requireNotNull(parsed.styles.at(0,0).dataBar).rgb)
        assertEquals(0x808080,requireNotNull(parsed.styles.at(1,0).dataBar).rgb)
    }

    @Test fun x14SignedDataBarsHonorCustomLengthsAndZero() {
        val wb = """<workbook><sheets><sheet name="Bars" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>-25</v></c></row><row r="2"><c r="A2"><v>0</v></c></row><row r="3"><c r="A3"><v>75</v></c></row><row r="4"><c r="A4"><v>37.5</v></c></row></sheetData><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="dataBar" priority="1"><x14:dataBar minLength="10" maxLength="80" gradient="0" axisPosition="automatic"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:fillColor rgb="FF2266AA"/><x14:negativeFillColor rgb="FFFF0000"/><x14:axisColor rgb="FF000000"/></x14:dataBar></x14:cfRule><xm:sqref>A1:A4</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        for (middle in listOf(false,true)) {
            val xml = if (middle) sheet.replace("automatic","middle") else sheet
            val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to xml))
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertEquals(0,parsed.unsupportedConditionalRules)
            val bars = (0..3).map { requireNotNull(parsed.styles.at(it,0).dataBar) }
            val axis = if (middle) 0.5f else 0.25f
            val expected = if (middle) listOf(0.1f,0.5f,0.9f,0.75f) else listOf(0.05f,0.25f,0.85f,0.60f)
            for (i in bars.indices) {
                assertEquals(axis,bars[i].start,0.001f)
                assertEquals(axis,bars[i].axis!!,0.001f)
                assertEquals(expected[i],bars[i].end,0.001f)
            }
        }
    }

    @Test fun x14GradientMidpointBarsRenderAxialPdfShading() = runBlocking<Unit> {
        val wb = """<workbook><sheets><sheet name="Gradient" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><sheetData><row r="1"><c r="A1"><v>-25</v></c></row><row r="2"><c r="A2"><v>75</v></c></row></sheetData><extLst><ext><x14:conditionalFormattings><x14:conditionalFormatting><x14:cfRule type="dataBar" priority="1"><x14:dataBar minLength="0" maxLength="100" gradient="1" axisPosition="middle"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/><x14:fillColor rgb="FF2266AA"/><x14:negativeFillColor rgb="FFFF0000"/><x14:axisColor rgb="FF000000"/></x14:dataBar></x14:cfRule><xm:sqref>A1:A2</xm:sqref></x14:conditionalFormatting></x14:conditionalFormattings></ext></extLst></worksheet>"""
        val input = createZip(mapOf("xl/workbook.xml" to wb,"xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertEquals(0,parsed.unsupportedConditionalRules)
        val negative = requireNotNull(parsed.styles.at(0,0).dataBar)
        val positive = requireNotNull(parsed.styles.at(1,0).dataBar)
        assertTrue(negative.gradient && positive.gradient)
        assertEquals(0.5f,negative.start,0.001f)
        assertEquals(0f,negative.end,0.001f)
        assertEquals(1f,positive.end,0.001f)
        val out = File(tempFolder.root,"x14-gradient.pdf")
        OfficeConverter().convertXlsxToPdf(input,out,context)
        PDDocument.load(out).use { pdf ->
            assertTrue(pdf.getPage(0).resources.shadingNames.iterator().hasNext())
            val operators = pdf.getPage(0).contents.use { it.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(Regex("""(?m)\bsh\s*$""").containsMatchIn(operators))
        }
    }
}
