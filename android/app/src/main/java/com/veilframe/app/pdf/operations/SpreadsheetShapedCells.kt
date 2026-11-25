package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream

/** Shared worksheet shaping contract: no nominal glyph drawing or invisible-only success. */
internal class SpreadsheetShapedCells(private val shaped: OfficeShapedText) {
    private fun prepare(text: String,style: XlsxCellStyle,size: Float,width: Float): OfficeShapedText.Block =
        shaped.prepare(listOf(OpenXmlDocumentParser.DocxRun(text, isBold = style.bold,isItalic = style.italic)),
            false,size,width.coerceAtMost(1200f),style.horizontal,style.textRgb ?: 0,preserveLineBreaks = true)

    fun measure(text: String,style: XlsxCellStyle,width: Float): Float? = try {
        prepare(text,style,style.fontSizePt ?: 9f,width).height
    } catch (_: OfficeTextEncodingException) {
        // Unprinted cells must not block saved-print export. Actual drawing
        // always repeats strict admission and never substitutes missing text.
        null
    }

    fun draw(page: PDPage,stream: PDPageContentStream,text: String,style: XlsxCellStyle,size: Float,
        left: Float,top: Float,width: Float,height: Float,coordinateScale: Float,
        offsetX: Float,offsetY: Float,savedPrint: Boolean) {
        val iconSpace = if (style.icon != null) 13f else 0f
        val available = width-8f-iconSpace
        val block = prepare(text,style,size,available)
        if (savedPrint && !style.wrapText &&
            block.lines > text.replace("\r\n","\n").replace('\r','\n').count { it == '\n' }+1)
            throw OfficeTextEncodingException("Complex-script text would wrap in a fixed non-wrapping print cell")
        if (block.height > height-8f+0.05f)
            throw OfficeTextEncodingException("Complex-script text exceeds its visible cell height")
        val spare = (height-8f-block.height).coerceAtLeast(0f)
        val excessWidth = (available-1200f).coerceAtLeast(0f)
        val x = left+4f+iconSpace+when(style.horizontal) { "right" -> excessWidth; "center" -> excessWidth/2; else -> 0f }
        var y = top-4f-when(style.vertical) { "bottom" -> spare; "center" -> spare/2; else -> 0f }
        for (i in 0 until block.lines) {
            shaped.drawLine(page,stream,block,i,x,y,coordinateScale,offsetX,offsetY)
            y -= block.lineHeight(i)
        }
    }
}
