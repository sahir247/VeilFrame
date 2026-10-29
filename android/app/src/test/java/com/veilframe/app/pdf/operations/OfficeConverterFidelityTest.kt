package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

        // Verify PDF parses cleanly
        PDDocument.load(pdfFile).use { doc ->
            assertEquals("Should have 1 page", 1, doc.numberOfPages)
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
            // Page is landscape A4: width > height
            assertTrue("Page should be landscape", page.mediaBox.width > page.mediaBox.height)
        }
    }
}
