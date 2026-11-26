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

    @Test fun javaStreamToListSupportsPoiOnPreApi34Runtime() {
        // Java 16 Stream.toList is not a Kotlin collection extension: its
        // unmodifiable result distinguishes it from Collectors.toList.
        val values = java.util.stream.Stream.of("slide","table").toList()
        assertEquals(listOf("slide","table"),values)
        assertTrue(runCatching { (values as MutableList<String>).add("unexpected") }
            .exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun attributedPptxTextShapesScriptsAndRejectsOverflowBeforeDrawing() {
        val bitmap = Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = android.graphics.Canvas(bitmap)
            val runs = listOf(
                PptxTextLayout.Run("বাংলা हिन्दी ",34f,Color.RED,bold=true),
                PptxTextLayout.Run("العربية",28f,Color.BLUE,italic=true))
            val paragraph = PptxTextLayout.Paragraph(runs,"CENTER")
            PptxTextLayout.draw(canvas,listOf(paragraph),20f,20f,560f,250f)
            var red = 0; var blue = 0
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x,y)
                if (Color.red(p)>150 && Color.green(p)<100 && Color.blue(p)<100) red++
                if (Color.blue(p)>150 && Color.green(p)<100 && Color.red(p)<100) blue++
            }
            assertTrue("Missing styled Bengali/Hindi pixels",red>10)
            assertTrue("Missing styled Arabic pixels",blue>10)
            bitmap.eraseColor(Color.WHITE)
            val failure = runCatching {
                PptxTextLayout.draw(canvas,listOf(paragraph,paragraph),0f,0f,300f,5f)
            }.exceptionOrNull()
            assertTrue(failure is OfficeTextEncodingException)
            assertEquals(Color.WHITE,bitmap.getPixel(25,25))
        } finally { bitmap.recycle() }
    }

    @Test fun slidePicturesCropWithoutCopiesAndRefuseCorruptPayload() {
        val source = Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until 10) for(x in 0 until 10) source.setPixel(x,y,if(x<5) Color.RED else Color.BLUE)
            val bytes = java.io.ByteArrayOutputStream().also { check(source.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
            val canvas = android.graphics.Canvas(output)
            val rect = android.graphics.RectF(0f,0f,20f,20f)
            PptxPictureRenderer.draw(canvas,bytes,PptxPictureRenderer.Crop(left=0.5f),rect)
            assertEquals(Color.BLUE,output.getPixel(10,10))
            assertTrue(runCatching { PptxPictureRenderer.draw(canvas,byteArrayOf(1,2,3),
                PptxPictureRenderer.Crop(),rect) }.exceptionOrNull() is java.io.IOException)
            assertEquals(4,PptxPictureRenderer.sampleSize(16000,16000))
        } finally { source.recycle(); output.recycle() }
    }

    @Test fun stackedWordArtPreservesClustersAndColumnDirection() {
        val first = PptxTextLayout.Run("A",26f,Color.RED)
        val mark = PptxTextLayout.Run("\u0301B",26f,Color.BLUE)
        val clusters = PptxTextLayout.stackedRuns(listOf(first,mark))
        assertEquals(listOf("A\u0301","B"),clusters.map { parts -> parts.joinToString("") { it.text } })
        assertEquals(2,clusters.first().size)
        val bitmap = Bitmap.createBitmap(240,200,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val paragraphs = listOf(PptxTextLayout.Paragraph(listOf(first.copy(text="AB"))),
                PptxTextLayout.Paragraph(listOf(mark.copy(text="CD"))))
            PptxTextLayout.drawStacked(android.graphics.Canvas(bitmap),paragraphs,10f,10f,220f,180f,"TOP",true)
            var redX=0L; var blueX=0L; var reds=0; var blues=0
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val pixel=bitmap.getPixel(x,y)
                if(Color.red(pixel)>150 && Color.blue(pixel)<100 && Color.green(pixel)<100) { redX+=x; reds++ }
                if(Color.blue(pixel)>150 && Color.red(pixel)<100 && Color.green(pixel)<100) { blueX+=x; blues++ }
            }
            assertTrue(reds>0 && blues>0)
            assertTrue(redX.toDouble()/reds>blueX.toDouble()/blues)
            bitmap.eraseColor(Color.WHITE)
            assertTrue(runCatching { PptxTextLayout.drawStacked(android.graphics.Canvas(bitmap),
                paragraphs,10f,10f,5f,180f,"TOP",true) }.isFailure)
            assertEquals(Color.WHITE,bitmap.getPixel(20,20))
        } finally { bitmap.recycle() }
    }

    @Test fun nativeVerticalCjkMixedOrientationPaintsAndRejectsOverflow() {
        val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val p=PptxTextLayout.Paragraph(listOf(
                PptxTextLayout.Run("日本語「文」",28f,Color.RED),
                PptxTextLayout.Run(" ABC",24f,Color.BLUE)))
            PptxVerticalText.draw(android.graphics.Canvas(bitmap),listOf(p),10f,10f,300f,300f,"TOP",true)
            var red=0; var blue=0
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val v=bitmap.getPixel(x,y)
                if(Color.red(v)>150 && Color.blue(v)<100 && Color.green(v)<100) red++
                if(Color.blue(v)>150 && Color.red(v)<100 && Color.green(v)<100) blue++
            }
            assertTrue(red>10 && blue>10)
            bitmap.eraseColor(Color.WHITE)
            assertTrue(runCatching { PptxVerticalText.draw(android.graphics.Canvas(bitmap),listOf(p),
                0f,0f,5f,5f,"TOP",true) }.isFailure)
            assertEquals(Color.WHITE,bitmap.getPixel(1,1))
        } finally { bitmap.recycle() }
    }

    /** Opt-in capture of a supplied, synthetic PowerPoint corpus file; not an ordinary CI pass. */
    @Test fun capturePowerPointReferenceCandidate(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val name=args.getString("pptxReferenceFixture")
        org.junit.Assume.assumeTrue("No external reference fixture requested",!name.isNullOrBlank())
        requireNotNull(name)
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.pptx"))) { "Invalid fixture filename" }
        val commit=args.getString("pptxReferenceCommit").orEmpty()
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Pass the exact tested commit SHA" }
        val root=requireNotNull(context.getExternalFilesDir(null))
        val input=File(File(root,"pptx_reference_inputs"),name)
        require(input.isFile && input.length() in 1L..134217728L) { "Missing or oversized reference fixture" }
        val before=digest(input)
        val folder=runDirectory("pptx_reference_candidate")
        val slides=OfficeConverter().renderPptxToBitmaps(input,1920)
        try {
            check(slides.isNotEmpty())
            for((index,bitmap) in slides.withIndex()) {
                val filename=String.format(java.util.Locale.ROOT,"slide-%04d.png",index+1)
                File(folder,filename).outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            }
            check(before==digest(input)) { "Fixture mutated during candidate capture" }
            File(folder,"manifest.json").writeText(JSONObject()
                .put("schema",1).put("source_sha256",before).put("source_name",name)
                .put("renderer","VeilFrame Android").put("renderer_version",commit)
                .put("device_fingerprint",Build.FINGERPRINT).put("sdk",Build.VERSION.SDK_INT)
                .put("slide_count",slides.size).toString(2))
            Log.i("VeilFrame.OfficeDevice","PPTX reference candidate: ${folder.absolutePath}")
        } finally { slides.forEach { if(!it.isRecycled) it.recycle() } }
        Unit
    }

    @Test fun powerPointTableRendersMergedCellAndDistinctFills(): Unit = runBlocking {
        val dir=runDirectory("pptx_table")
        val fixture=File(dir,"table.pptx")
        val p="http://schemas.openxmlformats.org/presentationml/2006/main"
        val a="http://schemas.openxmlformats.org/drawingml/2006/main"
        val r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun cell(text: String,rgb: String,attrs: String="") =
            """<a:tc $attrs><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="1600"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:rPr><a:t>$text</a:t></a:r><a:endParaRPr lang="en-US"/></a:p></a:txBody><a:tcPr>${if(rgb.isEmpty()) "" else """<a:solidFill><a:srgbClr val="$rgb"/></a:solidFill>"""}</a:tcPr></a:tc>"""
        val styleId="{12345678-1234-1234-1234-123456789ABC}"
        val styles="""<a:tblStyleLst xmlns:a="$a" def="$styleId"><a:tblStyle styleId="$styleId" styleName="Fixture">
            <a:wholeTbl><a:tcStyle><a:tcBdr><a:insideV><a:ln w="50800"><a:solidFill><a:srgbClr val="FF00FF"/></a:solidFill></a:ln></a:insideV></a:tcBdr>
            <a:fillRef idx="1"><a:srgbClr val="00FF00"/></a:fillRef></a:tcStyle></a:wholeTbl>
            <a:firstRow><a:tcStyle><a:fill><a:solidFill><a:srgbClr val="FFFF00"/></a:solidFill></a:fill></a:tcStyle></a:firstRow>
            </a:tblStyle></a:tblStyleLst>"""
        val table="""<a:tbl><a:tblPr firstRow="1"><a:tableStyleId>$styleId</a:tableStyleId></a:tblPr><a:tblGrid><a:gridCol w="2540000"/><a:gridCol w="2540000"/></a:tblGrid>
            <a:tr h="1270000">${cell("Merged title","FF0000", "gridSpan=\"2\"")}${cell("","FF0000","hMerge=\"1\"")}</a:tr>
            <a:tr h="1270000">${cell("Left","")}${cell("Right","0000FF")}</a:tr></a:tbl>"""
        val slide="""<p:sld xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:cSld><p:spTree>
            <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
            <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="2" name="Fixture table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="1270000" y="1270000"/><a:ext cx="5080000" cy="2540000"/></p:xfrm>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table">$table</a:graphicData></a:graphic></p:graphicFrame>
            <p:cxnSp><p:nvCxnSpPr><p:cNvPr id="3" name="Horizontal double arrow"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>
            <p:spPr><a:xfrm><a:off x="1270000" y="4445000"/><a:ext cx="6350000" cy="0"/></a:xfrm>
            <a:prstGeom prst="line"><a:avLst/></a:prstGeom>
            <a:ln w="152400" cmpd="dbl"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill><a:tailEnd type="triangle" w="med" len="med"/></a:ln>
            </p:spPr></p:cxnSp>
            </p:spTree></p:cSld></p:sld>"""
        val emptyTree="""<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree>"""
        val clrMap="""<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>"""
        val master="""<p:sldMaster xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:cSld>$emptyTree</p:cSld>$clrMap<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"""
        val layout="""<p:sldLayout xmlns:p="$p" xmlns:a="$a" xmlns:r="$r" type="blank" preserve="1"><p:cSld name="Blank">$emptyTree</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
        val colors=linkedMapOf("dk1" to "000000","lt1" to "FFFFFF","dk2" to "203040","lt2" to "EEEEEE","accent1" to "4472C4","accent2" to "ED7D31","accent3" to "A5A5A5","accent4" to "FFC000","accent5" to "5B9BD5","accent6" to "70AD47","hlink" to "0563C1","folHlink" to "954F72")
        val scheme=colors.entries.joinToString("") { (key,value) -> """<a:$key><a:srgbClr val="$value"/></a:$key>""" }
        val font="""<a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/>"""
        val solid="""<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val line="""<a:ln w="12700">$solid<a:prstDash val="solid"/></a:ln>"""
        val theme="""<a:theme xmlns:a="$a" name="Fixture"><a:themeElements><a:clrScheme name="Fixture">$scheme</a:clrScheme><a:fontScheme name="Fixture"><a:majorFont>$font</a:majorFont><a:minorFont>$font</a:minorFont></a:fontScheme><a:fmtScheme name="Fixture"><a:fillStyleLst>${solid.repeat(3)}</a:fillStyleLst><a:lnStyleLst>${line.repeat(3)}</a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst>${solid.repeat(3)}</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
        val relNs="http://schemas.openxmlformats.org/package/2006/relationships"
        fun rels(vararg targets: Pair<String,String>) = """<Relationships xmlns="$relNs">"""+
            targets.mapIndexed { index,(kind,target) -> """<Relationship Id="rId${index+1}" Type="$r/$kind" Target="$target"/>""" }.joinToString("")+"</Relationships>"
        val entries=mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/><Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/></Types>""",
            "_rels/.rels" to rels("officeDocument" to "ppt/presentation.xml"),
            "ppt/presentation.xml" to """<p:presentation xmlns:p="$p" xmlns:a="$a" xmlns:r="$r"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId2"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="9144000" cy="5143500"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels("slide" to "slides/slide1.xml","slideMaster" to "slideMasters/slideMaster1.xml","tableStyles" to "tableStyles.xml"),
            "ppt/slides/slide1.xml" to slide,
            "ppt/slides/_rels/slide1.xml.rels" to rels("slideLayout" to "../slideLayouts/slideLayout1.xml"),
            "ppt/slideLayouts/slideLayout1.xml" to layout,
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels("slideMaster" to "../slideMasters/slideMaster1.xml"),
            "ppt/slideMasters/slideMaster1.xml" to master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels("slideLayout" to "../slideLayouts/slideLayout1.xml","theme" to "../theme/theme1.xml"),
            "ppt/theme/theme1.xml" to theme,"ppt/tableStyles.xml" to styles)
        java.util.zip.ZipOutputStream(fixture.outputStream()).use { zip ->
            for((name,value) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        val images=OfficeConverter().renderPptxToBitmaps(fixture,720)
        try {
            assertEquals(1,images.size)
            val bitmap=images.single()
            assertEquals(Color.RED,bitmap.getPixel(115,115))
            assertEquals(Color.RED,bitmap.getPixel(495,115))
            assertEquals(Color.GREEN,bitmap.getPixel(115,215))
            assertEquals(Color.BLUE,bitmap.getPixel(495,215))
            assertEquals("Inherited internal border",Color.MAGENTA,bitmap.getPixel(300,270))
            assertEquals("Merged header suppresses internal border",Color.RED,bitmap.getPixel(300,170))
            var dark=0
            for(y in 102 until 195) for(x in 102 until 495) {
                val c=bitmap.getPixel(x,y)
                if(Color.red(c)<35 && Color.green(c)<35 && Color.blue(c)<35) dark++
            }
            assertTrue("Merged cell must contain visible text",dark>10)
            // A zero-height real OPC connector must reach the native renderer.
            assertEquals("Upper compound band",Color.RED,bitmap.getPixel(350,346))
            assertEquals("Transparent center gap",Color.WHITE,bitmap.getPixel(350,350))
            assertEquals("Lower compound band",Color.RED,bitmap.getPixel(350,354))
            assertEquals("Filled arrowhead",Color.RED,bitmap.getPixel(580,350))
        } finally { images.forEach { it.recycle() } }
        Unit
    }

    @Test fun connectorInkPreservesUnderlyingPixelsAndSingleAlpha() {
        val bitmap=Bitmap.createBitmap(240,100,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            val spec=PptxStrokeStyle.Spec(18f,compound="dbl",
                tail=PptxStrokeStyle.End("triangle","med","med"))
            val color=Color.argb(128,255,0,0)
            PptxConnectorRenderer.draw(android.graphics.Canvas(bitmap),20f,50f,220f,50f,spec,color)
            assertEquals(Color.GREEN,bitmap.getPixel(100,50))
            val band=bitmap.getPixel(100,44)
            assertTrue(Color.red(band) in 126..129 && Color.green(band) in 126..129)
            val head=bitmap.getPixel(190,50)
            assertTrue(Color.red(head) in 126..129 && Color.green(head) in 126..129)
        } finally { bitmap.recycle() }
    }

    @Test fun customPathUsesCurvesAndDoesNotPaintBoundingRectangle() {
        val bitmap=Bitmap.createBitmap(120,120,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val shape=PptxCustomGeometry.Subpath(100.0,100.0,true,false,listOf(
                PptxCustomGeometry.Command("moveTo",listOf(0.0,0.0)),
                PptxCustomGeometry.Command("lnTo",listOf(100.0,0.0)),
                PptxCustomGeometry.Command("quadBezTo",listOf(50.0,100.0,0.0,100.0)),
                PptxCustomGeometry.Command("close",emptyList())))
            PptxCustomGeometry.draw(android.graphics.Canvas(bitmap),listOf(shape),android.graphics.RectF(10f,10f,110f,110f),
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=Color.RED })
            assertEquals(Color.RED,bitmap.getPixel(20,20))
            assertEquals(Color.WHITE,bitmap.getPixel(100,100))
        } finally { bitmap.recycle() }
    }

    @Test fun slideChartDrawsSignedBarsAndSeriesColor() {
        val bitmap=Bitmap.createBitmap(640,420,Bitmap.Config.ARGB_8888)
        try {
            val data=XlsxCachedChart("Revenue","column",listOf("Alpha","Beta"),
                listOf(XlsxCachedChart.Series("Series",listOf(-3.0,7.0),rgb=0xFF0000)))
            PptxChartRenderer.draw(android.graphics.Canvas(bitmap),data,android.graphics.RectF(0f,0f,640f,420f),1f) {}
            var left=0; var right=0
            for(y in 60 until 390) for(x in 110 until 620) {
                if(bitmap.getPixel(x,y)==Color.RED) {
                    if(x<360) left++ else right++
                }
            }
            assertTrue("Missing signed column marks",left>100 && right>100)
            assertTrue("Positive value should occupy more area",right>left)
        } finally { bitmap.recycle() }
    }

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
