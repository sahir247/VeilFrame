package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.StyleSpan
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.ceil
import kotlin.math.abs

/** Complex-script visual fallback: Android shapes full attributed paragraphs.
 * Rasterize only shaped line strips, not whole pages. Separately append and
 * verify a logical-Unicode search layer before the PDF can be published.
 */
internal class OfficeShapedText(context: Context, private val pdf: PDDocument) : AutoCloseable {
    private val fontOwner = lazy { SearchableOcrFonts(context,pdf) }
    private val scale = 3f
    private var pixels = 0L
    private var words = 0
    private data class Word(val text: String,val x: Float,val y: Float,val width: Float,val size: Float)
    private val layers = linkedMapOf<PDPage,MutableList<Word>>()
    private val expected = linkedMapOf<Int,ByteArray>()

    class Block internal constructor(internal val layout: StaticLayout,private val scale: Float) {
        val lines: Int get() = layout.lineCount
        val height: Float get() = layout.height/scale
        fun lineHeight(index: Int): Float =
            (layout.getLineBottom(index)-layout.getLineTop(index))/scale
    }

    fun prepare(runs: List<OpenXmlDocumentParser.DocxRun>,heading: Boolean,size: Float,
        width: Float,alignment: String = "left"): Block {
        val text = SpannableStringBuilder()
        for (run in runs) {
            val start = text.length
            text.append(normalizeOfficeText(run.text))
            val style = (if (heading || run.isBold) Typeface.BOLD else 0) or
                (if(run.isItalic) Typeface.ITALIC else 0)
            if (text.length>start && style != 0) text.setSpan(StyleSpan(style),start,text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (text.length>16384) throw OfficeTextEncodingException("Shaped paragraph exceeds layout budget")
        }
        if (!size.isFinite() || size !in 1f..144f || !width.isFinite() || width !in 4f..1200f)
            throw OfficeTextEncodingException("Unsupported shaped text geometry")
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = size*scale
            typeface = Typeface.create("sans-serif",Typeface.NORMAL)
        }
        val value = text.toString()
        // Check individual base characters with platform font fallback. Marks
        // and joiners require their surrounding shaping context; the separate
        // font-mapped logical layer verifies that no source codepoint is lost.
        val supported = value.codePoints().allMatch { cp ->
            val type = Character.getType(cp)
            when {
                cp == 0x200c || cp == 0x200d || Character.isWhitespace(cp) -> true
                !Character.isDefined(cp) || Character.isISOControl(cp) ||
                    type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() -> false
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt() -> true
                else -> paint.hasGlyph(String(Character.toChars(cp)))
            }
        }
        if (!supported) throw OfficeTextEncodingException("Platform font fallback cannot render all source characters")
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(value,0,value.length)
        val align = when (alignment) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            else -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text,0,text.length,paint,(width*scale).toInt())
            .setAlignment(align).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (android.os.Build.VERSION.SDK_INT>=28) builder.setUseLineSpacingFromFallbacks(true)
        val layout = builder.build()
        if (layout.lineCount !in 1..4096 || layout.height<=0)
            throw OfficeTextEncodingException("Shaped text exceeds layout budget")
        return Block(layout,scale)
    }

    fun prepare(text: String,size: Float,width: Float) =
        prepare(listOf(OpenXmlDocumentParser.DocxRun(text)),false,size,width)

    fun drawLine(page: PDPage,stream: PDPageContentStream,block: Block,index: Int,left: Float,top: Float) {
        val layout = block.layout
        val start = layout.getLineStart(index)
        val end = layout.getLineEnd(index)
        val value = layout.text.subSequence(start,end).toString()
        if (value.isBlank()) return
        val y = layout.getLineTop(index)
        val height = layout.getLineBottom(index)-y
        val width = layout.width
        val count = width.toLong()*height
        if (height<=0 || count !in 1L..2_000_000L || pixels+count>80_000_000L)
            throw OfficeTextEncodingException("Shaped image budget exceeded")
        pixels += count
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(0f,-y.toFloat())
            layout.draw(canvas)
            val image = LosslessFactory.createFromImage(pdf,bitmap)
            stream.drawImage(image,left,top-height/scale,width/scale,height/scale)
        } finally { bitmap.recycle() }
        val list = layers.getOrPut(page) { arrayListOf() }
        for (match in Regex("\\S+").findAll(value)) {
            if (++words>100_000) throw OfficeTextEncodingException("Shaped search layer exceeds word budget")
            val x1 = layout.getPrimaryHorizontal(start+match.range.first)
            val x2 = layout.getPrimaryHorizontal(start+match.range.last+1)
            val baseline = top-(layout.getLineBaseline(index)-y)/scale
            list.add(Word(match.value,left+minOf(x1,x2)/scale,baseline,
                (abs(x2-x1)/scale).coerceAtLeast(1f),height/scale*0.7f))
        }
    }

    fun append(checkpoint: ()->Unit) {
        if (layers.isEmpty()) return
        pdf.documentInformation.subject = "Complex-script passages use shaped 216 dpi line images and a verified logical Unicode search layer; visual layout is approximate."
        val fonts = fontOwner.value
        for ((page,entries) in layers) {
            checkpoint()
            val pageIndex = pdf.pages.indexOf(page)
            check(pageIndex>=0)
            PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
                for (word in entries) {
                    checkpoint()
                    val (font,em) = try { fonts.select(word.text) }
                        catch (failure: java.io.IOException) {
                            throw OfficeTextEncodingException("No embeddable font for the shaped search layer",failure)
                        }
                    val stretch = word.width/(em*word.size)
                    require(stretch.isFinite() && stretch>0f)
                    stream.beginText()
                    stream.setFont(font,word.size)
                    stream.setRenderingMode(RenderingMode.NEITHER)
                    stream.setTextMatrix(Matrix(stretch,0f,0f,1f,word.x,word.y))
                    stream.showText(word.text)
                    stream.endText()
                }
            }
            expected[pageIndex] = digestOcrWords(entries.asSequence().map { it.text })
        }
        layers.clear()
    }

    fun verify(saved: PDDocument,checkpoint: ()->Unit) {
        for ((index,digest) in expected) {
            checkpoint()
            if (!digest.contentEquals(digestSerializedOcrLayer(saved.getPage(index),checkpoint)))
                throw OfficeTextEncodingException("Shaped text failed serialized Unicode verification")
        }
    }

    override fun close() {
        if(fontOwner.isInitialized()) fontOwner.value.close()
    }
}

internal fun needsOfficeShaping(text: String): Boolean = text.codePoints().anyMatch { cp ->
    val type = Character.getType(cp)
    val script = Character.UnicodeScript.of(cp)
    type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        script in setOf(Character.UnicodeScript.ARABIC,Character.UnicodeScript.HEBREW,
            Character.UnicodeScript.BENGALI,Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.GUJARATI,Character.UnicodeScript.GURMUKHI,
            Character.UnicodeScript.TAMIL,Character.UnicodeScript.TELUGU,
            Character.UnicodeScript.KANNADA,Character.UnicodeScript.MALAYALAM,
            Character.UnicodeScript.ORIYA,Character.UnicodeScript.THAI,
            Character.UnicodeScript.MYANMAR,Character.UnicodeScript.KHMER)
}
