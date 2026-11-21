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
    fun rejectsDtdInUtf8AndUtf16BeforeXmlParsing() {
        val xml = """<!DOCTYPE document [<!ENTITY secret SYSTEM "file:///private/file">]><document><body>&secret;</body></document>"""
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = xml.toByteArray(charset)
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                OpenXmlDocumentParser.XmlReadBudget().read(bytes.inputStream())
            }
        }
        val valid = "<document><body>বাংলা</body></document>"
        assertEquals(valid, OpenXmlDocumentParser.XmlReadBudget().read(valid.toByteArray(Charsets.UTF_16LE).inputStream()))
    }

    @Test
    fun measuresActualInflatedPartAndCumulativeBytes() {
        val budget = OpenXmlDocumentParser.XmlReadBudget(totalLimit = 12, partLimit = 8)
        assertEquals("12345678", budget.read("12345678".byteInputStream(), reportedSize = 1))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            budget.read("12345".byteInputStream(), reportedSize = 1)
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            OpenXmlDocumentParser.XmlReadBudget(totalLimit = 20, partLimit = 8)
                .read("123456789".byteInputStream(), reportedSize = -1)
        }
    }

    @Test
    fun readsPrefixedWorksheetAndDoesNotAddPhoneticGuidesToValues() {
        val file = createZipFile(mapOf(
            "xl/workbook.xml" to """<x:workbook xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheets><x:sheet name="Data" sheetId="1"/></x:sheets></x:workbook>""",
            "xl/sharedStrings.xml" to """<x:sst xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:si><x:r><x:t>First </x:t></x:r><x:r><x:t>Second</x:t></x:r><x:rPh sb="0" eb="5"><x:t>Guide</x:t></x:rPh></x:si></x:sst>""",
            "xl/worksheets/sheet1.xml" to """<x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheetData><x:row r="1"><x:c r="A1" t="s"><x:v>0</x:v></x:c><x:c r="B1" t="inlineStr"><x:is><x:r><x:t>Visible</x:t></x:r><x:rPh sb="0" eb="1"><x:t>Not visible</x:t></x:rPh></x:is></x:c></x:row></x:sheetData></x:worksheet>"""
        ))
        try {
            assertEquals(listOf("First Second", "Visible"), OpenXmlDocumentParser.parseXlsx(file).sheets.single().rows.single())
        } finally { file.delete() }
    }

    @Test
    fun preservesVisibleWordLinkAndRevisionTextWithAlternatePrefix() {
        val file = createZipFile(mapOf("word/document.xml" to
            """<q:document xmlns:q="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><q:body><q:p><q:r><q:t>Before </q:t></q:r><q:hyperlink><q:r><q:t>link</q:t></q:r></q:hyperlink><q:del><q:r><q:delText>deleted</q:delText></q:r></q:del><q:ins><q:r><q:tab/><q:t>added</q:t><q:br/><q:t>after</q:t></q:r></q:ins></q:p></q:body></q:document>"""
        ))
        try {
            assertEquals("Before link\tadded\nafter", OpenXmlDocumentParser.parseDocx(file).paragraphs.single().fullText)
        } finally { file.delete() }
    }

    @Test
    fun workbookRelationshipsControlSheetOrderAndNonContiguousPartNames() {
        val workbook = """<workbook><sheets>
            <sheet name="First" sheetId="8" r:id="rId9"/>
            <sheet name="Second" sheetId="3" r:id="rId2"/>
            </sheets></workbook>"""
        val rels = """<Relationships>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
            <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet8.xml"/>
            </Relationships>"""
        fun worksheet(value: String) = """<worksheet><sheetData><row r="1"><c r="A1"><v>$value</v></c></row></sheetData></worksheet>"""
        val entries = mapOf("xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels,
            "xl/worksheets/sheet3.xml" to worksheet("23"), "xl/worksheets/sheet8.xml" to worksheet("87"))
        val file = createZipFile(entries)
        try {
            val sheets = OpenXmlDocumentParser.parseXlsx(file).sheets
            assertEquals(listOf("First", "Second"), sheets.map { it.name })
            assertEquals(listOf("87", "23"), sheets.map { it.rows.first().first() })
        } finally { file.delete() }
        val bad = createZipFile(entries + ("xl/worksheets/sheet3.xml" to "<worksheet><broken>"))
        try {
            org.junit.Assert.assertThrows(Exception::class.java) { OpenXmlDocumentParser.parseXlsx(bad) }
        } finally { bad.delete() }
        val missing = createZipFile(entries - "xl/worksheets/sheet3.xml")
        try {
            org.junit.Assert.assertThrows(Exception::class.java) { OpenXmlDocumentParser.parseXlsx(missing) }
        } finally { missing.delete() }
    }

    @Test
    fun rejectsMalformedCellsAndPreservesAllInlineRuns() {
        val workbook = """<workbook><sheets><sheet name="Data" sheetId="1"/></sheets></workbook>"""
        fun file(cell: String) = createZipFile(mapOf(
            "xl/workbook.xml" to workbook,
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\">$cell</row></sheetData></worksheet>"
        ))
        val rich = file("""<c r="A1" t="inlineStr"><is><r><t>hello </t></r><r><t>world</t></r></is></c>""")
        try { assertEquals("hello world", OpenXmlDocumentParser.parseXlsx(rich).sheets.single().rows.single().single()) }
        finally { rich.delete() }
        for (cell in listOf("""<c r="XFE1"><v>1</v></c>""",
            """<c r="A2"><v>1</v></c>""", """<c r="A1" t="s"><v>999</v></c>""",
            """<c r="A1"><f>1+2</f></c>""", """<c r="A1"><v>1</v></c><c r="A1"><v>2</v></c>""")) {
            val bad = file(cell)
            try { org.junit.Assert.assertThrows(Exception::class.java) { OpenXmlDocumentParser.parseXlsx(bad) } }
            finally { bad.delete() }
        }
    }

    @Test
    fun xlsxFormatsStoredNumbersAndCachedFormulasWithWorkbookDateSystem() {
        val workbook = """<workbook><workbookPr date1904="1"/><sheets><sheet name="Data" sheetId="1" r:id="r1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="formats/custom.xml"/></Relationships>"""
        val styles = """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="00000"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/><numFmt numFmtId="166" formatCode="[Red]0.0;(0.0)"/></numFmts><cellStyleXfs><xf numFmtId="4"/></cellStyleXfs><cellXfs><xf numFmtId="0"/><xf numFmtId="10"/><xf numFmtId="164"/><xf numFmtId="165"/><xf numFmtId="166"/><xf xfId="0" numFmtId="0" applyNumberFormat="0"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>0.125</v></c><c r="B1" s="2"><v>23</v></c><c r="C1" s="3"><v>0</v></c><c r="D1" t="b"><v>1</v></c><c r="E1" s="5"><f>1000+234.5</f><v>1234.5</v></c><c r="F1" s="4"><v>-1.25</v></c><c r="G1" t="str"><f>1</f><v>  cached  </v></c><c r="H1"><v>1234567890123456789</v></c></row></sheetData></worksheet>"""
        val file = createZipFile(mapOf("xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels, "xl/formats/custom.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(file).sheets.single()
            assertEquals(listOf("12.50%", "00023", "1904-01-01", "TRUE", "1,234.50",
                "-1.25 [format 166 unsupported]", "  cached  ", "1234567890123456789"), parsed.rows.single())
            assertEquals(2, parsed.cachedFormulaCount)
        } finally { file.delete() }
    }

    @Test
    fun rejectsInvalidStyleReferencesAndEmptyNumericFormulaCache() {
        val workbook = """<workbook><sheets><sheet name="Data" sheetId="1"/></sheets></workbook>"""
        for (cell in listOf("""<c r="A1" s="2"><v>1</v></c>""",
            """<c r="A1"><f>1+2</f><v/></c>""")) {
            val file = createZipFile(mapOf("xl/workbook.xml" to workbook,
                "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\">$cell</row></sheetData></worksheet>"))
            try {
                org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                    OpenXmlDocumentParser.parseXlsx(file)
                }
            } finally { file.delete() }
        }
    }

    @Test
    fun parsesSavedRowAndColumnDimensionsAndRejectsAmbiguousRanges() {
        val workbook = """<workbook><sheets><sheet name="Geometry" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetFormatPr defaultColWidth="12" defaultRowHeight="24"/><cols><col min="2" max="3" width="40"/></cols><sheetData><row r="1" ht="60"><c r="C1"><v>1</v></c></row><row r="2"><c r="A2"><v>2</v></c></row></sheetData></worksheet>"""
        fun parse(xml: String): OpenXmlDocumentParser.XlsxSheetData {
            val file = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/worksheets/sheet1.xml" to xml))
            try { return OpenXmlDocumentParser.parseXlsx(file).sheets.single() }
            finally { file.delete() }
        }
        val dimensions = parse(sheet).dimensions
        assertEquals(63f, dimensions.columnMinPt(0), 0.01f)
        assertEquals(210f, dimensions.columnMinPt(1), 0.01f)
        assertEquals(210f, dimensions.columnMinPt(2), 0.01f)
        assertEquals(60f, dimensions.rowMinPt(0), 0.01f)
        assertEquals(24f, dimensions.rowMinPt(1), 0.01f)
        for (bad in listOf(sheet.replace("width=\"40\"", "width=\"NaN\""),
            sheet.replace("</cols>", "<col min=\"3\" max=\"4\" width=\"10\"/></cols>"),
            sheet.replace("ht=\"60\"", "ht=\"500\""),
            sheet.replace("r=\"2\"><c r=\"A2\"", "r=\"1\" ht=\"61\"><c r=\"A1\""))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parse(bad) }
        }
    }

    @Test
    fun parsesExplicitCellAppearanceAndSparseStyleIndices() {
        val workbook = """<workbook><sheets><sheet name="Styles" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><fonts><font/><font><b/><i val="1"/><color rgb="FF112233"/></font></fonts><fills><fill/><fill><patternFill patternType="solid"><fgColor rgb="FFAABBCC"/></patternFill></fill></fills><cellStyleXfs><xf fontId="1" fillId="1"><alignment horizontal="center" vertical="center" textRotation="135"/></xf></cellStyleXfs><cellXfs><xf/><xf xfId="0" applyFont="0" applyFill="0" applyAlignment="0"/><xf fontId="1" applyFont="1"><alignment horizontal="right" vertical="bottom"/></xf></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="C1" s="1" t="inlineStr"><is><t>center</t></is></c></row><row r="1"><c r="E1" s="2" t="inlineStr"><is><t>right</t></is></c></row><row r="2"><c r="A2" s="1"/></row></sheetData></worksheet>"""
        val file = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(file).sheets.single()
            val center = parsed.styles.at(0, 2)
            assertTrue(center.bold && center.italic)
            assertEquals(0x112233, center.textRgb ?: -1)
            assertEquals(0xAABBCC, center.fillRgb ?: -1)
            assertEquals("center", center.horizontal)
            assertEquals("center", center.vertical)
            assertEquals(135, center.textRotation)
            assertEquals(0, parsed.styles.at(0, 4).textRotation)
            assertEquals("bottom", parsed.styles.at(0, 4).vertical)
            assertEquals("right", parsed.styles.at(0, 4).horizontal)
            assertEquals(XlsxCellStyle(), parsed.styles.at(0, 1))
            assertEquals(center, parsed.styles.at(1, 0))
            assertEquals(2, parsed.rows.size)
        } finally { file.delete() }
    }

    @Test
    fun resolvesThemeRelationshipAndCustomIndexedColorsWithTint() {
        val workbook = """<workbook><sheets><sheet name="Palette" sheetId="1" r:id="r1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="themes/custom.xml"/></Relationships>"""
        // Deliberately not in spreadsheet index order.
        val theme = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:clrScheme name="Test"><a:dk1><a:sysClr val="windowText" lastClr="123456"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:accent1><a:srgbClr val="0000FF"/></a:accent1></a:clrScheme></a:themeElements></a:theme>"""
        val styles = """<styleSheet><fonts><font><color theme="1"/></font><font><color theme="4" tint="0.5"/></font></fonts><fills><fill/><fill><patternFill patternType="solid"><fgColor indexed="2"/></patternFill></fill></fills><cellXfs><xf/><xf fontId="1" fillId="1"/></cellXfs><colors><indexedColors><rgbColor rgb="FF000000"/><rgbColor rgb="FFFFFFFF"/><rgbColor rgb="FF123ABC"/></indexedColors></colors></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c><c r="B1" s="1"><v>2</v></c></row></sheetData></worksheet>"""
        fun parse(styleXml: String): OpenXmlDocumentParser.XlsxSheetData {
            val file = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels,
                "xl/themes/custom.xml" to theme, "xl/styles.xml" to styleXml, "xl/worksheets/sheet1.xml" to sheet))
            try { return OpenXmlDocumentParser.parseXlsx(file).sheets.single() }
            finally { file.delete() }
        }
        val parsed = parse(styles)
        assertEquals(0x123456, parsed.styles.at(0, 0).textRgb ?: -1)
        assertEquals(0x8080FF, parsed.styles.at(0, 1).textRgb ?: -1)
        assertEquals(0x123ABC, parsed.styles.at(0, 1).fillRgb ?: -1)
        for (bad in listOf(styles.replace("tint=\"0.5\"", "tint=\"NaN\""),
            styles.replace("theme=\"4\"", "theme=\"99\""),
            styles.replace("indexed=\"2\"", "indexed=\"2\" rgb=\"FF000000\""))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parse(bad) }
        }
    }

    @Test
    fun parsesBorderStylesAndGridlineVisibility() {
        val workbook = """<workbook><sheets><sheet name="Borders" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><borders><border/><border><left style="double"><color rgb="FF123456"/></left><bottom style="dashed"><color indexed="2"/></bottom></border></borders><cellStyleXfs><xf borderId="1"/></cellStyleXfs><cellXfs><xf/><xf xfId="0" borderId="0" applyBorder="0"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetViews><sheetView showGridLines="0"/></sheetViews><sheetData><row r="1"><c r="A1" s="1"><v>1</v></c></row></sheetData></worksheet>"""
        val input = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertTrue(!parsed.styles.showGridLines)
            val lines = parsed.styles.at(0, 0).borders
            assertEquals(XlsxBorderLine("double", 0x123456), lines.left)
            assertEquals(XlsxBorderLine("dashed", 0xFF0000), lines.bottom)
            assertEquals(null, lines.top)
        } finally { input.delete() }
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

            // Verify sparseRows collection
            assertEquals(3, sheet.sparseRows.size)
            assertEquals(1, sheet.sparseRows[0].rowIndex)
            assertEquals("FirstRow", sheet.sparseRows[0].cells[0])
            assertEquals(3, sheet.sparseRows[1].rowIndex)
            assertEquals("ThirdRow", sheet.sparseRows[1].cells[1])
            assertEquals(5, sheet.sparseRows[2].rowIndex)
            assertEquals("FifthRow", sheet.sparseRows[2].cells[2])
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testParseXlsxOutOfOrderAndDuplicateRows() {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
                <si><t>ItemRow5</t></si>
                <si><t>ItemRow2ColA</t></si>
                <si><t>ItemRow2ColB</t></si>
                <si><t>ItemRow1</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="ReorderedSheet" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        // Out-of-order: row 5, then row 2 (part 1), then row 2 (duplicate tag, part 2), then row 1
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="5">
                        <c r="A5" t="s"><v>0</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="B2" t="s"><v>2</v></c>
                    </row>
                    <row r="1">
                        <c r="A1" t="s"><v>3</v></c>
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

            // Sorted rows: row 1, row 2, row 3 (empty), row 4 (empty), row 5
            assertEquals(5, sheet.rows.size)

            // Row 1
            assertEquals("ItemRow1", sheet.rows[0][0])

            // Row 2: merged from the two duplicate <row r="2"> elements
            assertEquals("ItemRow2ColA", sheet.rows[1][0])
            assertEquals("ItemRow2ColB", sheet.rows[1][1])

            // Row 3 and 4: padded empty spacer rows
            assertTrue(sheet.rows[2].isEmpty() || sheet.rows[2].all { it.isBlank() })
            assertTrue(sheet.rows[3].isEmpty() || sheet.rows[3].all { it.isBlank() })

            // Row 5
            assertEquals("ItemRow5", sheet.rows[4][0])

            // Sparse rows: exact keys 1, 2, 5
            assertEquals(3, sheet.sparseRows.size)
            assertEquals(1, sheet.sparseRows[0].rowIndex)
            assertEquals(2, sheet.sparseRows[1].rowIndex)
            assertEquals(5, sheet.sparseRows[2].rowIndex)
        } finally {
            tempFile.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseXlsxOversizedGapRejection() {
        val sharedStringsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                <si><t>RowOne</t></si>
                <si><t>RowTwentyThousand</t></si>
            </sst>
        """.trimIndent()

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets>
                    <sheet name="OversizedGapSheet" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>
        """.trimIndent()

        // Row 1 followed directly by row 20000 (gap = 19,998)
        val sheet1Xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                    <row r="20000"><c r="A20000" t="s"><v>1</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val tempFile = createZipFile(mapOf(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml
        ))

        try {
            OpenXmlDocumentParser.parseXlsx(tempFile)
        } finally {
            tempFile.delete()
        }
    }
    @Test
    fun numericConditionalFormattingUsesRawValuesAndGlobalPriority() {
        val workbook = """<workbook><sheets><sheet name="CF" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><cellXfs><xf numFmtId="9"/></cellXfs><dxfs><dxf><font><b/><color rgb="FF0000FF"/></font></dxf><dxf><fill><patternFill><bgColor rgb="FFFF0000"/></patternFill></fill></dxf></dxfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>0.75</v></c><c r="B1"><v>0.25</v></c><c r="C1"><v>0.75</v></c></row></sheetData><conditionalFormatting sqref="A1:C1"><cfRule type="cellIs" operator="greaterThan" priority="2" dxfId="1"><formula>0.5</formula></cfRule></conditionalFormatting><conditionalFormatting sqref="A1 B1"><cfRule type="cellIs" operator="greaterThan" priority="1" stopIfTrue="1" dxfId="0"><formula>0.5</formula></cfRule></conditionalFormatting><conditionalFormatting sqref="C1"><cfRule type="expression" priority="3" dxfId="0"><formula>C1&gt;0</formula></cfRule></conditionalFormatting></worksheet>"""
        val input = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertEquals("75%", parsed.rows[0][0])
            assertTrue(parsed.styles.at(0, 0).bold)
            assertEquals(0x0000FF, parsed.styles.at(0, 0).textRgb)
            assertEquals(null, parsed.styles.at(0, 0).fillRgb)
            assertEquals(null, parsed.styles.at(0, 1).fillRgb)
            assertEquals(0xFF0000, parsed.styles.at(0, 2).fillRgb)
            assertTrue(parsed.styles.at(0, 2).bold) // Supported C1>0 expression complements the earlier fill
            assertEquals(0, parsed.unsupportedConditionalRules)
        } finally { input.delete() }
    }
    @Test
    fun literalTextConditionalRulesRespectTypeAndPriority() {
        val workbook = """<workbook><sheets><sheet name="Text" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><dxfs><dxf><font><b/></font></dxf><dxf><fill><patternFill><bgColor rgb="FFFF0000"/></patternFill></fill></dxf></dxfs><cellXfs><xf/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>PAID invoice</t></is></c><c r="B1" t="str"><v>unpaid</v></c><c r="C1"><v>12</v></c></row></sheetData><conditionalFormatting sqref="A1:C1"><cfRule type="beginsWith" operator="beginsWith" text="paid" priority="1" stopIfTrue="1" dxfId="0"/><cfRule type="containsText" operator="containsText" text="paid" priority="2" dxfId="1"/></conditionalFormatting><conditionalFormatting sqref="C1:C1"><cfRule type="cellIs" operator="greaterThan" priority="3" dxfId="0"><formula>10</formula></cfRule></conditionalFormatting></worksheet>"""
        val input = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertTrue(parsed.styles.at(0,0).bold)
            assertEquals(null, parsed.styles.at(0,0).fillRgb)
            assertEquals(0xFF0000, parsed.styles.at(0,1).fillRgb)
            assertTrue(parsed.styles.at(0,2).bold)
            assertEquals(0, parsed.unsupportedConditionalRules)
        } finally { input.delete() }
    }
    @Test
    fun conditionalColorScaleUsesFullRawRangeAndThreeStops() {
        val workbook = """<workbook><sheets><sheet name="Scale" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><cellXfs><xf numFmtId="9"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>0</v></c><c r="B1"><v>0.25</v></c></row><row r="2"><c r="A2"><v>0.5</v></c><c r="B2"><v>1</v></c></row></sheetData><conditionalFormatting sqref="A1:B2"><cfRule type="colorScale" priority="1"><colorScale><cfvo type="min"/><cfvo type="percent" val="50"/><cfvo type="max"/><color rgb="FF000000"/><color rgb="FF808080"/><color rgb="FFFFFFFF"/></colorScale></cfRule></conditionalFormatting></worksheet>"""
        val input = createZipFile(mapOf("xl/workbook.xml" to workbook, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet))
        try {
            val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
            assertEquals("25%", parsed.rows[0][1])
            assertEquals(0x000000, parsed.styles.at(0,0).fillRgb)
            assertEquals(0x404040, parsed.styles.at(0,1).fillRgb)
            assertEquals(0x808080, parsed.styles.at(1,0).fillRgb)
            assertEquals(0xFFFFFF, parsed.styles.at(1,1).fillRgb)
            assertEquals(0, parsed.unsupportedConditionalRules)
        } finally { input.delete() }
    }
    @Test
    fun blankConditionalRangesHaveBoundedEvaluationCost() {
        val wb = """<workbook><sheets><sheet name="CFBudget" sheetId="1"/></sheets></workbook>"""
        val manyRules = (1..160).joinToString("") { priority ->
            """<cfRule type="expression" priority="$priority"><formula>MOD(ROW(),2)=0</formula></cfRule>"""
        }
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="100"><c r="CV100"><v>2</v></c></row></sheetData><conditionalFormatting sqref="A1:CV100">$manyRules</conditionalFormatting></worksheet>"""
        val input = createZipFile(mapOf("xl/workbook.xml" to wb, "xl/worksheets/sheet1.xml" to sheet))
        try {
            val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                OpenXmlDocumentParser.parseXlsx(input)
            }
            assertTrue(error.message.orEmpty().contains("blank-cell evaluation exceeds budget"))
        } finally { input.delete() }
    }
}
