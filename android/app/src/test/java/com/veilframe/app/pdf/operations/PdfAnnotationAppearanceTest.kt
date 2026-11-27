package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PdfAnnotationAppearanceTest {
    @Before fun setup() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }
    private fun key(name: String)=COSName.getPDFName(name)
    private fun floats(vararg values: Float)=COSArray().apply { values.forEach { add(COSFloat(it)) } }
    private fun annotation(subtype: String): PDAnnotation {
        val d=COSDictionary().apply {
            setName(key("Type"),"Annot")
            setName(key("Subtype"),subtype)
            setItem(key("C"),floats(1f,0f,0f))
            setItem(key("CA"),COSFloat(0.8f))
            when(subtype) {
                "Highlight","Underline","StrikeOut","Squiggly" ->
                    setItem(key("QuadPoints"),floats(40f,100f,120f,100f,40f,80f,120f,80f))
                "Ink" -> setItem(key("InkList"),COSArray().apply {
                    add(floats(40f,80f,60f,100f,120f,80f))
                })
                "Line" -> setItem(key("L"),floats(40f,80f,120f,100f))
                "Polygon","PolyLine" -> setItem(key("Vertices"),floats(40f,80f,60f,100f,120f,80f))
                "Square","Circle" -> setItem(key("IC"),floats(0f,0f,1f))
            }
        }
        return PDAnnotation.createAnnotation(d).apply {
            rectangle=PDRectangle(30f,60f,110f,60f)
            isPrinted=true
        }
    }

    @Test fun allSupportedMarkupTypesGenerateAndSurviveSerializedFlatten() {
        for(subtype in listOf("Highlight","Underline","StrikeOut","Squiggly","Ink","Line","Polygon","PolyLine","Square","Circle")) {
            PDDocument().use { doc ->
                val page=PDPage(); doc.addPage(page)
                val annot=annotation(subtype)
                page.annotations.add(annot)
                assertNull(subtype,annot.normalAppearanceStream)
                assertEquals(subtype,1,PdfFlattener().flattenPageAnnotations(doc,page))
                assertTrue(subtype,page.annotations.isEmpty())
                val bytes=ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                PDDocument.load(bytes).use { saved ->
                    val p=saved.getPage(0)
                    assertTrue(subtype,p.annotations.isEmpty())
                    assertTrue(subtype,p.resources.xObjectNames.any())
                    assertTrue(subtype,p.contents.use { it.readBytes().toString(Charsets.US_ASCII) }.contains(" Do"))
                }
            }
        }
    }

    @Test fun invalidLateAnnotationDoesNotAppendOrRemoveEarlierAnnotations() {
        PDDocument().use { doc ->
            val page=PDPage(); doc.addPage(page)
            val valid=annotation("Square")
            val invalid=annotation("Ink").apply { cosObject.setItem(key("InkList"),COSArray()) }
            page.annotations.add(valid); page.annotations.add(invalid)
            assertThrows(IOException::class.java) { PdfFlattener().flattenPageAnnotations(doc,page) }
            assertEquals(2,page.annotations.size)
            assertFalse(page.hasContents())
        }
    }
    @Test fun existingAppearanceIsPreservedAndBrokenStateIsNotRegenerated() {
        PDDocument().use { doc ->
            val original=annotation("Square")
            val generated=PdfAnnotationAppearance.resolve(doc,original)
            assertSame(generated.cosObject,PdfAnnotationAppearance.resolve(doc,original).cosObject)
            val broken=annotation("Circle").apply { cosObject.setItem(key("AP"),COSDictionary()) }
            assertThrows(IOException::class.java) { PdfAnnotationAppearance.resolve(doc,broken) }
        }
    }
    @Test fun malformedAndUnsupportedInputsFailClosed() {
        PDDocument().use { doc ->
            val malformed=annotation("Highlight").apply { cosObject.setItem(key("QuadPoints"),floats(1f,2f,3f)) }
            assertThrows(IOException::class.java) { PdfAnnotationAppearance.resolve(doc,malformed) }
            val collapsed=annotation("Highlight").apply { cosObject.setItem(key("QuadPoints"),floats(1f,2f,3f,2f,1f,2f,3f,2f)) }
            assertThrows(IOException::class.java) { PdfAnnotationAppearance.resolve(doc,collapsed) }
            val unsupported=annotation("Stamp")
            assertThrows(IOException::class.java) { PdfAnnotationAppearance.resolve(doc,unsupported) }
        }
    }
}
