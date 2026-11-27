package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.ceil

/** Shared worksheet shaping contract: no nominal glyph drawing or invisible-only success. */
internal class SpreadsheetShapedCells(private val shaped: OfficeShapedText,
    private val visibleFonts: OfficeVisibleFonts) {
    /** Single-line labels share the same strict glyph/shape policy as cells. */
    fun label(page: PDPage,stream: PDPageContentStream,text: String,x: Float,baseline: Float,size: Float,
        width: Float,bold: Boolean = false,alignment: String = "left",
        coordinateScale: Float = 1f,offsetX: Float = 0f,offsetY: Float = 0f) {
        if (text.isEmpty()) return
        require(width.isFinite() && width>=4f) { "Spreadsheet label has no visible width" }
        require(text.none { it == '\r' || it == '\n' }) { "Multiline chart or print label is unsupported" }
        stream.setNonStrokingColor(0,0,0)
        if (needsOfficeShaping(text)) {
            val style = XlsxCellStyle(bold = bold,horizontal = alignment)
            val block = prepare(text,style,size,width)
            if (block.lines != 1 || block.widestLine > block.width+0.05f)
                throw OfficeTextEncodingException("Complex-script label exceeds its available rectangle")
            val extra = (width-block.width).coerceAtLeast(0f)
            val left = x+when(alignment) { "center" -> extra/2f; "right" -> extra; else -> 0f }
            shaped.drawLine(page,stream,block,0,left,baseline+block.baseline(0),coordinateScale,offsetX,offsetY)
        } else {
            val preferred = if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
            val font = visibleFonts.select(preferred,text)
            val value = normalizeOfficeText(text)
            val w = font.getStringWidth(value)*size/1000f
            require(w<=width+0.05f) { "Spreadsheet label exceeds its available rectangle" }
            val left = x+when(alignment) { "center" -> (width-w)/2f; "right" -> width-w; else -> 0f }
            stream.beginText(); stream.setFont(font,size); stream.newLineAtOffset(left,baseline)
            stream.showText(value); stream.endText()
        }
    }

    private fun prepare(text: String,style: XlsxCellStyle,size: Float,width: Float): OfficeShapedText.Block =
        shaped.prepare(listOf(OpenXmlDocumentParser.DocxRun(text, isBold = style.bold,isItalic = style.italic)),
            false,size,width.coerceAtMost(1200f),style.horizontal,style.textRgb ?: 0,preserveLineBreaks = true)

    private fun rotated(text: String,style: XlsxCellStyle,size: Float): Pair<OfficeShapedText.Block,XlsxRotatedText.Block> {
        if (style.textRotation == 255) throw OfficeTextEncodingException("Stacked complex-script cells are unsupported")
        val expectedLines = text.replace("\r\n","\n").replace('\r','\n').count { it == '\n' }+1
        val wide = prepare(text,style,size,1200f)
        if (wide.lines != expectedLines) throw OfficeTextEncodingException("Rotated shaped line exceeds layout width")
        val block = prepare(text,style,size,(ceil(wide.widestLine)+2f).coerceIn(4f,1200f))
        if (block.lines != expectedLines) throw OfficeTextEncodingException("Rotated shaped line cannot fit")
        return block to XlsxRotatedText.geometry(block.width,block.height,style.textRotation)
    }

    fun measureWidth(text: String,style: XlsxCellStyle,size: Float): Float? = try {
        rotated(text,style,size).second.width
    } catch (_: OfficeTextEncodingException) { null }

    fun measure(text: String,style: XlsxCellStyle,width: Float): Float? = try {
        val size = style.fontSizePt ?: 9f
        if (style.textRotation != 0) rotated(text,style,size).second.height
        else prepare(text,style,size,width).height
    } catch (_: OfficeTextEncodingException) {
        // Unprinted cells must not block saved-print export. Actual drawing
        // always repeats strict admission and never substitutes missing text.
        null
    }

    fun draw(page: PDPage,stream: PDPageContentStream,text: String,style: XlsxCellStyle,size: Float,
        left: Float,top: Float,width: Float,height: Float,coordinateScale: Float,
        offsetX: Float,offsetY: Float,savedPrint: Boolean) {
        if (style.textRotation != 0) {
            val (block,geometry) = rotated(text,style,size)
            if (geometry.width > width-8f+0.05f || geometry.height > height-8f+0.05f)
                throw OfficeTextEncodingException("Rotated complex-script cell does not fit its visible rectangle")
            val sx = (width-8f-geometry.width).coerceAtLeast(0f)
            val sy = (height-8f-geometry.height).coerceAtLeast(0f)
            val x = left+4f+when(style.horizontal) { "right" -> sx; "center" -> sx/2; else -> 0f }-geometry.minX
            val y = top-4f-geometry.height-when(style.vertical) { "bottom" -> sy; "center" -> sy/2; else -> 0f }-geometry.minY
            stream.saveGraphicsState()
            try {
                stream.transform(Matrix(geometry.cosine,geometry.sine,-geometry.sine,geometry.cosine,x,y))
                var lineTop = block.height
                for (i in 0 until block.lines) {
                    shaped.drawLine(page,stream,block,i,0f,lineTop,coordinateScale,
                        offsetX+x*coordinateScale,offsetY+y*coordinateScale,geometry.cosine,geometry.sine)
                    lineTop -= block.lineHeight(i)
                }
            } finally { stream.restoreGraphicsState() }
            return
        }
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
