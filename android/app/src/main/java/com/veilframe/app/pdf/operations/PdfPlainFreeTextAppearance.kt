package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDAppearanceContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.IOException

/** Explicit plain FreeText: no arbitrary DA operators, font substitution or clipping. */
internal object PdfPlainFreeTextAppearance {
    private fun key(s: String)=COSName.getPDFName(s)
    private fun fail(message: String): Nothing=throw IOException(message)
    private fun num(v: Any?)=(v as? COSNumber)?.floatValue()?.takeIf { it.isFinite() }
        ?: fail("Invalid FreeText appearance number")
    private fun baseFont(name: String)=when(name) {
        "Helv","Helvetica" -> PDType1Font.HELVETICA
        "HeBo","Helvetica-Bold" -> PDType1Font.HELVETICA_BOLD
        "HeOb","Helvetica-Oblique" -> PDType1Font.HELVETICA_OBLIQUE
        "HeBO","Helvetica-BoldOblique" -> PDType1Font.HELVETICA_BOLD_OBLIQUE
        "TiRo","Times-Roman" -> PDType1Font.TIMES_ROMAN
        "TiBo","Times-Bold" -> PDType1Font.TIMES_BOLD
        "TiIt","Times-Italic" -> PDType1Font.TIMES_ITALIC
        "TiBI","Times-BoldItalic" -> PDType1Font.TIMES_BOLD_ITALIC
        "Cour","Courier" -> PDType1Font.COURIER
        "CoBo","Courier-Bold" -> PDType1Font.COURIER_BOLD
        "CoOb","Courier-Oblique" -> PDType1Font.COURIER_OBLIQUE
        "CoBO","Courier-BoldOblique" -> PDType1Font.COURIER_BOLD_OBLIQUE
        else -> fail("Unsupported FreeText font: "+name)
    }
    fun generate(document: PDDocument,annotation: PDAnnotation) {
        val d=annotation.cosObject
        val text=annotation.contents ?: fail("Missing FreeText contents")
        if(text.length !in 1..8192 || text.isBlank() || text.any { it.isISOControl() && it!='\n' && it!='\r' })
            fail("Unsupported FreeText contents")
        val da=d.getString(key("DA")) ?: fail("Missing FreeText default appearance")
        if(da.length !in 1..2048 || da.any { it.code>127 }) fail("Invalid FreeText default appearance")
        val parser=PDFStreamParser(da.toByteArray(Charsets.US_ASCII));parser.parse()
        var font: PDType1Font?=null
        var size=0f
        var rgb: List<Float>?=null
        val args=mutableListOf<Any>()
        for(token in parser.tokens) {
            if(token !is Operator) { args.add(token);continue }
            when(token.name) {
                "Tf" -> {
                    if(font!=null || args.size!=2) fail("Ambiguous FreeText font")
                    val name=args[0] as? COSName ?: fail("Invalid FreeText font name")
                    val selected=baseFont(name.name)
                    val actual=document.documentCatalog.acroForm?.defaultResources?.getFont(name)
                    if(actual!=null && (actual !is PDType1Font || actual.name!=selected.name || actual.isEmbedded))
                        fail("Unsupported custom FreeText font resource")
                    font=selected;size=num(args[1])
                    if(size !in 1f..144f) fail("Unsupported FreeText font size")
                }
                "g","rg","k" -> {
                    if(rgb!=null || args.size!=(when(token.name) { "g" -> 1; "rg" -> 3; else -> 4 }))
                        fail("Ambiguous FreeText color")
                    val values=args.map(::num)
                    if(values.any { it !in 0f..1f }) fail("Invalid FreeText color")
                    rgb=values
                }
                else -> fail("Unsupported FreeText default appearance operator")
            }
            args.clear()
        }
        if(args.isNotEmpty()) fail("Incomplete FreeText appearance")
        val typeface=font ?: fail("Missing FreeText font")
        val color=rgb ?: fail("Missing FreeText text color")
        val rect=annotation.rectangle
        val bs=d.getDictionaryObject(key("BS")) as? COSDictionary
        val border=d.getDictionaryObject(key("Border")) as? COSArray
        val width=if(bs!=null) bs.getFloat(key("W"),1f) else border?.let { num(it.getObject(2)) } ?: 1f
        val padding=maxOf(2f,width*2)
        val align=d.getInt(key("Q"),0)
        if(align !in 0..2) fail("Unsupported FreeText alignment")
        val lines=text.replace("\r\n","\n").replace('\r','\n').split('\n')
        val widths=lines.map {
            try { typeface.encode(it);typeface.getStringWidth(it)*size/1000f }
            catch(e: IllegalArgumentException) { throw IOException("Unsupported FreeText glyph",e) }
        }
        val ascent=maxOf(0.8f,typeface.fontDescriptor.ascent/1000f)*size
        val descent=maxOf(0.25f,-typeface.fontDescriptor.descent/1000f)*size
        val step=maxOf(1.2f*size,ascent+descent)
        if(widths.any { it>rect.width-2*padding } || ascent+descent+(lines.size-1)*step>rect.height-2*padding)
            fail("FreeText contents exceed the visible box; refusing to clip")
        val appearance=PDAppearanceStream(document).apply {
            resources=PDResources()
            bBox=PDRectangle(rect.width,rect.height)
        }
        PDAppearanceContentStream(appearance,true).use { cs ->
            val opacity=d.getFloat(key("CA"),1f)
            if(opacity<1f) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                setNonStrokingAlphaConstant(opacity);setStrokingAlphaConstant(opacity)
            })
            fun ink(values: List<Float>,stroke: Boolean) {
                when(values.size) {
                    1 -> if(stroke) cs.setStrokingColor(values[0]) else cs.setNonStrokingColor(values[0])
                    3 -> if(stroke) cs.setStrokingColor(values[0],values[1],values[2])
                        else cs.setNonStrokingColor(values[0],values[1],values[2])
                    4 -> if(stroke) cs.setStrokingColor(values[0],values[1],values[2],values[3])
                        else cs.setNonStrokingColor(values[0],values[1],values[2],values[3])
                    else -> fail("Invalid FreeText ink")
                }
            }
            val background=d.getDictionaryObject(key("C")) as? COSArray
            if(background!=null && background.size()>0) {
                ink((0 until background.size()).map { num(background.getObject(it)) },false)
                cs.addRect(0f,0f,rect.width,rect.height);cs.fill()
            }
            if(width>0f) {
                ink(color,true);cs.setLineWidth(width)
                cs.addRect(width/2,width/2,rect.width-width,rect.height-width);cs.stroke()
            }
            ink(color,false)
            for((i,line) in lines.withIndex()) {
                if(line.isEmpty()) continue
                val x=when(align) { 1 -> (rect.width-widths[i])/2;2 -> rect.width-padding-widths[i];else -> padding }
                cs.beginText();cs.setFont(typeface,size)
                cs.newLineAtOffset(x,rect.height-padding-ascent-i*step);cs.showText(line);cs.endText()
            }
        }
        annotation.appearance=PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
    }
}
