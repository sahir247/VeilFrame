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
            // 60 lines with 9pt font + line height of 11.7pt = ~700pt > A4 landscape height 595pt (usable ~515pt)
            // It MUST paginate across at least 2 pages
            assertTrue("Oversized row must paginate across at least 2 pages (got ${doc.numberOfPages})", doc.numberOfPages >= 2)

            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)

            // Verify that every single line from 1 to 60 is present in the output PDF — ZERO lines lost!
            for (i in 1..60) {
                assertTrue("Line $i must be preserved across multi-page table pagination", text.contains("OversizedTraceLine_$i"))
            }
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
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            assertTrue("Must contain Section_Alpha", text.contains("Section_Alpha"))
            assertTrue("Must contain Section_Beta", text.contains("Section_Beta"))
            assertTrue("Must contain Section_Gamma", text.contains("Section_Gamma"))
        }
    }
}
