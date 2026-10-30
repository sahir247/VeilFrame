package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenXmlDocumentParserTest {

    private fun createZipFile(entries: Map<String, String>): File {
        val tempFile = File.createTempFile("test_openxml_", ".zip")
        tempFile.deleteOnExit()
        tempFile.outputStream().use { fos ->
            ZipOutputStream(fos).use { zos ->
                for ((name, content) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
        return tempFile
    }

    @Test
    fun testParseXlsxMinimal() {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                <si><t>Department</t></si>
                <si><t>Engineering</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="Summary" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1"><v>42000</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>1</v></c>
                        <c r="B2"><v>55000</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val tempFile = createZipFile(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))

        try {
            val workbook = OpenXmlDocumentParser.parseXlsx(tempFile)
            assertNotNull(workbook)
            assertEquals(1, workbook.sheets.size)
            val sheet = workbook.sheets[0]
            assertEquals("Summary", sheet.name)
            assertEquals(2, sheet.rows.size)

            // Row 1: "Department", "42000"
            assertEquals("Department", sheet.rows[0][0])
            assertEquals("42000", sheet.rows[0][1])

            // Row 2: "Engineering", "55000"
            assertEquals("Engineering", sheet.rows[1][0])
            assertEquals("55000", sheet.rows[1][1])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testColRefToIndexAndSparseColumns() {
        assertEquals(0, OpenXmlDocumentParser.colRefToIndex("A1"))
        assertEquals(1, OpenXmlDocumentParser.colRefToIndex("B2"))
        assertEquals(2, OpenXmlDocumentParser.colRefToIndex("C10"))
        assertEquals(25, OpenXmlDocumentParser.colRefToIndex("Z99"))
        assertEquals(26, OpenXmlDocumentParser.colRefToIndex("AA1"))
        assertEquals(27, OpenXmlDocumentParser.colRefToIndex("AB5"))
        assertEquals(51, OpenXmlDocumentParser.colRefToIndex("AZ12"))
        assertEquals(52, OpenXmlDocumentParser.colRefToIndex("BA1"))

        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>HeaderA</t></si>
                <si><t>HeaderC</t></si>
                <si><t>DataB</t></si>
                <si><t>DataD</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="SparseSheet" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        // Row 1 has A1 and C1 (B1 omitted)
        // Row 2 has B2 and D2 (A2 and C2 omitted)
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

        val tempFile = createZipFile(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))

        try {
            val workbook = OpenXmlDocumentParser.parseXlsx(tempFile)
            assertNotNull(workbook)
            val sheet = workbook.sheets[0]
            assertEquals(2, sheet.rows.size)

            // Row 1: Col A is "HeaderA", Col B is "", Col C is "HeaderC"
            assertEquals("HeaderA", sheet.rows[0][0])
            assertEquals("", sheet.rows[0][1])
            assertEquals("HeaderC", sheet.rows[0][2])

            // Row 2: Col A is "", Col B is "DataB", Col C is "", Col D is "DataD"
            assertEquals("", sheet.rows[1][0])
            assertEquals("DataB", sheet.rows[1][1])
            assertEquals("", sheet.rows[1][2])
            assertEquals("DataD", sheet.rows[1][3])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testParseDocxHeadingsAndParagraphs() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:pPr>
                            <w:pStyle w:val="Heading1"/>
                            <w:jc w:val="center"/>
                        </w:pPr>
                        <w:r>
                            <w:t>Document Header</w:t>
                        </w:r>
                    </w:p>
                    <w:p>
                        <w:pPr>
                            <w:spacing w:before="120" w:after="240"/>
                        </w:pPr>
                        <w:r>
                            <w:rPr>
                                <w:b/>
                            </w:rPr>
                            <w:t>This is bold introductory text.</w:t>
                        </w:r>
                    </w:p>
                    <w:tbl>
                        <w:tr>
                            <w:tc><w:p><w:r><w:t>Cell 1</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>Cell 2</w:t></w:r></w:p></w:tc>
                        </w:tr>
                    </w:tbl>
                </w:body>
            </w:document>
        """.trimIndent()

        val tempFile = createZipFile(mapOf(
            "word/document.xml" to documentXml
        ))

        try {
            val doc = OpenXmlDocumentParser.parseDocx(tempFile)
            assertNotNull(doc)
            assertEquals(3, doc.nodes.size)

            // Node 1: Heading
            val p1 = (doc.nodes[0] as OpenXmlDocumentParser.DocxNode.ParagraphNode).paragraph
            assertTrue(p1.isHeading)
            assertEquals(1, p1.headingLevel)
            assertEquals("Document Header", p1.fullText)
            assertEquals("center", p1.alignment)

            // Node 2: Paragraph with bold run
            val p2 = (doc.nodes[1] as OpenXmlDocumentParser.DocxNode.ParagraphNode).paragraph
            assertEquals(1, p2.runs.size)
            assertEquals("This is bold introductory text.", p2.runs[0].text)
            assertTrue(p2.runs[0].isBold)
            assertEquals(6f, p2.spaceBeforePt, 0.1f)
            assertEquals(12f, p2.spaceAfterPt, 0.1f)

            // Node 3: Table
            val tbl = (doc.nodes[2] as OpenXmlDocumentParser.DocxNode.TableNode).table
            assertEquals(1, tbl.rows.size)
            assertEquals(2, tbl.rows[0].size)
            assertEquals("Cell 1", tbl.rows[0][0])
            assertEquals("Cell 2", tbl.rows[0][1])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testParseXlsxExplicitRowNumbersAndSparseRows() {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                <si><t>FirstRow</t></si>
                <si><t>ThirdRow</t></si>
                <si><t>FifthRow</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="ExplicitRows" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        // XML only has row 1, 3, 5 (rows 2 and 4 are physically empty/omitted)
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                    </row>
                    <row r="3">
                        <c r="B3" t="s"><v>1</v></c>
                    </row>
                    <row r="5">
                        <c r="C5" t="s"><v>2</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val tempFile = createZipFile(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))

        try {
            val workbook = OpenXmlDocumentParser.parseXlsx(tempFile)
            assertNotNull(workbook)
            assertEquals(1, workbook.sheets.size)
            val sheet = workbook.sheets[0]
            assertEquals("ExplicitRows", sheet.name)
            // Exactly 5 rows must exist, preserving the 1-based row indexing of rows 1, 3, and 5
            assertEquals(5, sheet.rows.size)

            // Row 1 (index 0)
            assertEquals("FirstRow", sheet.rows[0][0])

            // Row 2 (index 1) - padded empty spacer row
            assertTrue(sheet.rows[1].isEmpty() || sheet.rows[1].all { it.isBlank() })

            // Row 3 (index 2) - sparse col B3 -> index 1
            assertEquals("", sheet.rows[2][0])
            assertEquals("ThirdRow", sheet.rows[2][1])

            // Row 4 (index 3) - padded empty spacer row
            assertTrue(sheet.rows[3].isEmpty() || sheet.rows[3].all { it.isBlank() })

            // Row 5 (index 4) - sparse col C5 -> index 2
            assertEquals("", sheet.rows[4][0])
            assertEquals("", sheet.rows[4][1])
            assertEquals("FifthRow", sheet.rows[4][2])
        } finally {
            tempFile.delete()
        }
    }
}
