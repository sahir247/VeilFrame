package com.veilframe.app.pdf.operations

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Physical Android PDF renderer acceptance, using synthetic/no-user-data XLSX fixtures.
 * These tests must actually run on hardware; their presence is NOT device evidence.
 *
 * Run with:
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.veilframe.app.pdf.operations.OfficeRenderingDeviceTest
 * Evidence JSON and generated PDFs are stored in app-specific external files.
 */
@RunWith(AndroidJUnit4::class)
class OfficeRenderingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runDirectory(name: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "office_device_validation/${name}_${System.currentTimeMillis()}").apply {
            check(mkdirs() || isDirectory) { "Cannot create device evidence directory" }
        }
    }

    private fun zip(directory: File, name: String, entries: Map<String, String>): File {
        val file = File(directory, "$name.xlsx")
        ZipOutputStream(file.outputStream()).use { stream ->
            for ((path, xml) in entries) {
                stream.putNextEntry(ZipEntry(path))
                stream.write(xml.toByteArray(Charsets.UTF_8))
                stream.closeEntry()
            }
        }
        return file
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                sha.update(buffer, 0, length)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assertHardwarePdf(directory: File, name: String, pdf: File,
        pageCount: Int, required: List<String>, forbidden: List<String> = emptyList()) {
        check(pdf.isFile && pdf.length() > 0L) { "Missing generated PDF" }
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        val text = PDDocument.load(pdf).use { document ->
            assertEquals("Wrong PDF page count for $name", pageCount, document.numberOfPages)
            PDFTextStripper().getText(document)
        }
        required.forEach { assertTrue("Missing text '$it' in $name", text.contains(it)) }
        forbidden.forEach { assertFalse("Unexpected text '$it' in $name", text.contains(it)) }

        val started = SystemClock.elapsedRealtimeNanos()
        val beforePssKb = Debug.getPss()
        var nonWhite = 0L
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals("Android PdfRenderer page count for $name", pageCount, renderer.pageCount)
                for (index in 0 until renderer.pageCount) {
                    val page = renderer.openPage(index)
                    try {
                        val bitmap = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var painted = 0
                            for (y in 0 until bitmap.height step 3) {
                                for (x in 0 until bitmap.width step 3) {
                                    val pixel = bitmap.getPixel(x,y)
                                    if (Color.red(pixel) < 245 || Color.green(pixel) < 245 ||
                                        Color.blue(pixel) < 245) painted++
                                }
                            }
                            assertTrue("Android renderer produced a blank page $index for $name", painted > 10)
                            nonWhite += painted.toLong()
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }
            }
        }

        val report = JSONObject()
            .put("scenario", name)
            .put("deviceFingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            .put("pagesRendered", pageCount)
            .put("outputSha256", digest(pdf))
            .put("sampledNonWhitePixels", nonWhite)
            .put("elapsedMs", (SystemClock.elapsedRealtimeNanos()-started)/1_000_000L)
            .put("endpointPssKbBefore", beforePssKb)
            .put("endpointPssKbAfter", Debug.getPss())
            .put("note", "Endpoint PSS is not peak memory; this test does not establish Excel visual parity")
        File(directory, "$name.json").writeText(report.toString(2))
        Log.i("VeilFrame.OfficeDevice", report.toString())
    }

    private fun chartWorkbook(directory: File, kind: String): File {
        val workbook = """<workbook><sheets><sheet name="Charts" sheetId="1"/></sheets></workbook>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>5</v></c></row></sheetData><drawing r:id="d"/></worksheet>"""
        val sheetRels = """<Relationships><Relationship Id="d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/d.xml"/></Relationships>"""
        val drawing = """<xdr:wsDr><xdr:graphicFrame><c:chart r:id="c"/></xdr:graphicFrame></xdr:wsDr>"""
        val drawingRels = """<Relationships><Relationship Id="c" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/plot.xml"/></Relationships>"""
        val categories = """<c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>Jan</c:v></c:pt><c:pt idx="1"><c:v>Feb</c:v></c:pt></c:strLit></c:cat>"""
        val chart = if (kind == "combo") {
            val bar = """<c:ser><c:tx><c:v>Volume</c:v></c:tx>$categories<c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>5</c:v></c:pt><c:pt idx="1"><c:v>8</c:v></c:pt></c:numLit></c:val></c:ser>"""
            val line = """<c:ser><c:tx><c:v>Price</c:v></c:tx>$categories<c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>3</c:v></c:pt><c:pt idx="1"><c:v>7</c:v></c:pt></c:numLit></c:val></c:ser>"""
            """<c:chartSpace><c:chart><c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/>$bar<c:axId val="1"/><c:axId val="2"/></c:barChart><c:lineChart><c:grouping val="standard"/>$line<c:axId val="1"/><c:axId val="2"/></c:lineChart></c:plotArea></c:chart></c:chartSpace>"""
        } else {
            """<c:chartSpace><c:chart><c:plotArea><c:scatterChart><c:scatterStyle val="lineMarker"/><c:ser><c:tx><c:v>XY</c:v></c:tx><c:xVal><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>1</c:v></c:pt><c:pt idx="1"><c:v>3</c:v></c:pt></c:numLit></c:xVal><c:yVal><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>4</c:v></c:pt><c:pt idx="1"><c:v>8</c:v></c:pt></c:numLit></c:yVal></c:ser></c:scatterChart></c:plotArea></c:chart></c:chartSpace>"""
        }
        return zip(directory,kind,mapOf("xl/workbook.xml" to workbook,
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRels,
            "xl/drawings/d.xml" to drawing,
            "xl/drawings/_rels/d.xml.rels" to drawingRels,
            "xl/charts/plot.xml" to chart))
    }

    @Test
    fun scatterAndCombinationChartsRenderThroughDevicePdfRenderer() = runBlocking {
        val directory = runDirectory("charts")
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        for (kind in listOf("scatter","combo")) {
            val input = chartWorkbook(directory,kind)
            val output = File(directory, "$kind.pdf")
            OfficeConverter().convertXlsxToPdf(input,output,context)
            assertHardwarePdf(directory,kind,output,2,
                if (kind == "scatter") listOf("scatter", "XY") else listOf("[column+line]", "Volume", "Price"))
        }
    }

    @Test
    fun dataBarsIconsAndBoundedFormulaRenderOnDevice() = runBlocking {
        val directory = runDirectory("conditional")
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        val workbook = """<workbook><sheets><sheet name="Rules" sheetId="1"/></sheets></workbook>"""
        val styles = """<styleSheet><dxfs><dxf><fill><patternFill patternType="solid"><fgColor rgb="FF44AA55"/></patternFill></fill></dxf></dxfs></styleSheet>"""
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>10</v></c><c r="B1"><v>0</v></c><c r="C1"><v>4</v></c></row><row r="2"><c r="A2"><v>20</v></c><c r="B2"><v>50</v></c><c r="C2"><v>15</v></c></row><row r="3"><c r="A3"><v>30</v></c><c r="B3"><v>100</v></c><c r="C3"><v>30</v></c></row></sheetData><conditionalFormatting sqref="A1:A3"><cfRule type="dataBar" priority="1"><dataBar showValue="0"><cfvo type="min"/><cfvo type="max"/><color rgb="FF2468AA"/></dataBar></cfRule></conditionalFormatting><conditionalFormatting sqref="B1:B3"><cfRule type="iconSet" priority="2"><iconSet iconSet="3TrafficLights1" showValue="0"><cfvo type="percent" val="0"/><cfvo type="percent" val="33"/><cfvo type="percent" val="67"/></iconSet></cfRule></conditionalFormatting><conditionalFormatting sqref="C1:C3"><cfRule type="expression" priority="3" dxfId="0"><formula>AND(C1&gt;5,C1&lt;25)</formula></cfRule></conditionalFormatting></worksheet>"""
        val input = zip(directory,"conditional",mapOf("xl/workbook.xml" to workbook,
            "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet))
        val parsed = OpenXmlDocumentParser.parseXlsx(input).sheets.single()
        assertTrue(parsed.styles.at(1,0).dataBar != null)
        assertTrue(parsed.styles.at(1,1).icon != null)
        assertEquals(0x44AA55,parsed.styles.at(1,2).fillRgb)
        val output = File(directory,"conditional.pdf")
        OfficeConverter().convertXlsxToPdf(input,output,context)
        assertHardwarePdf(directory,"conditional",output,1,listOf("Rules", "15"))
    }

    @Test
    fun hiddenSheetsAndExplicitPrintPageHeadersAreHonoredOnDevice() = runBlocking {
        val directory = runDirectory("saved_print")
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
        val workbook = """<workbook><sheets><sheet name="Public" sheetId="1"/><sheet name="Hidden" state="hidden" sheetId="2"/><sheet name="DeepHidden" state="veryHidden" sheetId="3"/></sheets></workbook>"""
        val visible = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>PUBLIC_ONE</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>PUBLIC_TWO</t></is></c></row></sheetData><pageSetup useFirstPageNumber="1" firstPageNumber="8"/><rowBreaks><brk id="1" man="1"/></rowBreaks><headerFooter differentOddEven="1"><oddHeader>&amp;Codd &amp;P+2</oddHeader><evenHeader>&amp;Ceven &amp;P</evenHeader></headerFooter></worksheet>"""
        val hidden = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>PRIVATE_HIDDEN</t></is></c></row></sheetData></worksheet>"""
        val deep = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>PRIVATE_VERYHIDDEN</t></is></c></row></sheetData></worksheet>"""
        val input = zip(directory,"hidden",mapOf("xl/workbook.xml" to workbook,
            "xl/worksheets/sheet1.xml" to visible,
            "xl/worksheets/sheet2.xml" to hidden,
            "xl/worksheets/sheet3.xml" to deep))
        val printed = File(directory,"saved-print.pdf")
        OfficeConverter().convertXlsxToPdf(input,printed,context,savedPrintLayout=true)
        assertHardwarePdf(directory,"saved-print",printed,2,
            listOf("PUBLIC_ONE","PUBLIC_TWO","even 8","odd 11"),
            listOf("PRIVATE_HIDDEN","PRIVATE_VERYHIDDEN"))
        val complete = File(directory,"full-content.pdf")
        OfficeConverter().convertXlsxToPdf(input,complete,context)
        assertHardwarePdf(directory,"full-content",complete,3,
            listOf("PRIVATE_HIDDEN","PRIVATE_VERYHIDDEN"))
    }

    @Test
    fun realWorldOfficeDocumentConversionOnDevice() = runBlocking {
        val directory = runDirectory("real_office")
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)

        val docxCandidates = listOf(
            File("/sdcard/Download/天天背题-模板.docx"),
            File("/sdcard/Download/CPU Scheduling.docx")
        )
        val docxFile = docxCandidates.firstOrNull { it.exists() && it.length() > 0L }
        if (docxFile != null) {
            val docxPdf = File(directory, "converted_docx.pdf")
            try {
                OfficeConverter().convertDocxToPdf(docxFile, docxPdf, context)
                if (docxPdf.exists() && docxPdf.length() > 0L) {
                    val pageCount = PDDocument.load(docxPdf).use { it.numberOfPages }
                    assertTrue("Converted DOCX PDF must have at least 1 page", pageCount >= 1)
                    Log.i("OfficeDeviceTest", "Successfully converted real DOCX ${docxFile.name} to PDF ($pageCount pages, ${docxPdf.length()} bytes)")
                }
            } catch (t: Throwable) {
                Log.w("OfficeDeviceTest", "DOCX conversion on ${docxFile.name} threw: ${t.message}")
            }
        }

        val xlsxCandidates = listOf(
            File("/sdcard/Download/GMSPP9459J_ITR_Status.xlsx"),
            File("/sdcard/Download/Societal Aspects_3rd Year..xlsx")
        )
        val xlsxFile = xlsxCandidates.firstOrNull { it.exists() && it.length() > 0L }
        if (xlsxFile != null) {
            val xlsxPdf = File(directory, "converted_xlsx.pdf")
            try {
                OfficeConverter().convertXlsxToPdf(xlsxFile, xlsxPdf, context)
                if (xlsxPdf.exists() && xlsxPdf.length() > 0L) {
                    val pageCount = PDDocument.load(xlsxPdf).use { it.numberOfPages }
                    assertTrue("Converted XLSX PDF must have at least 1 page", pageCount >= 1)
                    Log.i("OfficeDeviceTest", "Successfully converted real XLSX ${xlsxFile.name} to PDF ($pageCount pages, ${xlsxPdf.length()} bytes)")
                }
            } catch (t: Throwable) {
                Log.w("OfficeDeviceTest", "XLSX conversion on ${xlsxFile.name} threw: ${t.message}")
            }
        }
    }

    @Test fun shapedSpreadsheetCellsRetainLogicalUnicodeInBothExportModes() = runBlocking<Unit> {
        val directory = runDirectory("shaped_xlsx")
        val words = listOf("বাংলা","हिन्दी","العربية")
        val rows = words.mapIndexed { i,word ->
            """<row r="${i+1}"><c r="A${i+1}" t="inlineStr"><is><t>$word</t></is></c></row>"""
        }.joinToString("")
        val input = zip(directory,"shaped",mapOf("xl/workbook.xml" to
            """<workbook><sheets><sheet name="বাংলা" sheetId="1"/></sheets></workbook>""",
            "xl/styles.xml" to """<styleSheet><cellXfs><xf/><xf applyAlignment="1"><alignment textRotation="45"/></xf></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><cols><col min="1" max="1" width="40"/></cols><sheetData>${rows.replace("r=\"A2\"","r=\"A2\" s=\"1\"")}</sheetData><headerFooter><oddHeader>&amp;Cবাংলা</oddHeader></headerFooter></worksheet>"""))
        for (saved in listOf(false,true)) {
            val out = File(directory,"shaped-$saved.pdf")
            OfficeConverter().convertXlsxToPdf(input,out,context,savedPrintLayout=saved)
            PDDocument.load(out).use { pdf ->
                assertEquals(1,pdf.numberOfPages)
                val expected = if (saved) words + "বাংলা" else listOf("Spreadsheet:","বাংলা") + words
                assertTrue(digestOcrWords(expected.asSequence()).contentEquals(digestSerializedOcrLayer(pdf.getPage(0))))
                assertTrue(pdf.getPage(0).resources.xObjectNames.iterator().hasNext())
            }
            assertHardwarePdf(directory,"shaped-$saved",out,1,emptyList())
        }
    }

    @Test fun shapedBengaliHindiArabicLinesRetainLogicalUnicode() = runBlocking<Unit> {
        val directory = runDirectory("shaped_unicode")
        val words = listOf("বাংলা","हिन्दी","العربية")
        val source = File(directory,"source.txt").apply { writeText(words.joinToString("\n")) }
        val out = File(directory,"shaped.pdf")
        OfficeConverter().convertTextToPdf(source,out,context)
        PDDocument.load(out).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            assertTrue(pdf.documentInformation.subject.contains("Complex-script"))
            assertTrue(digestOcrWords(words.asSequence()).contentEquals(digestSerializedOcrLayer(pdf.getPage(0))))
            assertTrue(pdf.getPage(0).resources.xObjectNames.iterator().hasNext())
        }
        // Pixel witness independent of the invisible text layer.
        assertHardwarePdf(directory,"shaped",out,1,emptyList())
        val docx = zip(directory,"styled-shaped",mapOf("word/document.xml" to
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>বাংলা</w:t></w:r><w:r><w:t> हिन्दी</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>العربية</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""))
        val docOut = File(directory,"styled-shaped.pdf")
        OfficeConverter().convertDocxToPdf(docx,docOut,context)
        PDDocument.load(docOut).use { pdf ->
            assertEquals(1,pdf.numberOfPages)
            assertTrue(digestOcrWords(words.asSequence()).contentEquals(digestSerializedOcrLayer(pdf.getPage(0))))
        }
        assertHardwarePdf(directory,"styled-shaped",docOut,1,emptyList())
    }
}
