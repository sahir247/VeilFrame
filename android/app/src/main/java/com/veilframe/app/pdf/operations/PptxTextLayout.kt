package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import android.text.style.SuperscriptSpan
import android.text.style.SubscriptSpan
import kotlin.math.ceil

/** Android-native attributed slide text: no per-word measurement or manual bidi reversal. */
internal object PptxTextLayout {
    data class Run(val text: String,val size: Float,val color: Int,val family: String? = null,
        val bold: Boolean = false,val italic: Boolean = false,val underline: Boolean = false,
        val strike: Boolean = false,val superscript: Boolean = false,val subscript: Boolean = false)

    data class Paragraph(val runs: List<Run>,val alignment: String = "LEFT",
        val marginLeft: Float = 0f,val marginRight: Float = 0f,
        val before: Float = 0f,val after: Float = 0f,val lineMultiplier: Float = 1f,
        val firstIndent: Float = 0f)

    private data class Positioned(val layout: StaticLayout,val x: Float,val y: Float)

    internal fun prepare(paragraph: Paragraph,width: Float): StaticLayout {
        val available = width-paragraph.marginLeft-paragraph.marginRight
        if (!available.isFinite() || available !in 4f..8192f ||
            !paragraph.lineMultiplier.isFinite() || paragraph.lineMultiplier !in 0.5f..5f ||
            !paragraph.firstIndent.isFinite() || kotlin.math.abs(paragraph.firstIndent)>available-4f)
            throw OfficeTextEncodingException("Unsupported slide paragraph geometry")
        val content = SpannableStringBuilder()
        val baseSize = paragraph.runs.firstOrNull()?.size ?: 14f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = baseSize
            typeface = Typeface.DEFAULT
        }
        for (run in paragraph.runs) {
            if (!run.size.isFinite() || run.size !in 1f..2048f)
                throw OfficeTextEncodingException("Slide font size exceeds rendering budget")
            val value = run.text.replace("\r\n","\n").replace('\r','\n').replace('\u000b','\n').replace('\t',' ')
            if (value.length > 16384-content.length)
                throw OfficeTextEncodingException("Slide paragraph exceeds text budget")
            val style = (if(run.bold) Typeface.BOLD else 0) or (if(run.italic) Typeface.ITALIC else 0)
            val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(run.family ?: "sans-serif",style)
                textSize = run.size
            }
            if (!value.codePoints().allMatch { cp ->
                    val type = Character.getType(cp)
                    when {
                        cp == 0x200c || cp == 0x200d || Character.isWhitespace(cp) -> true
                        !Character.isDefined(cp) || Character.isISOControl(cp) ||
                            type == Character.SURROGATE.toInt() -> false
                        type == Character.NON_SPACING_MARK.toInt() ||
                            type == Character.COMBINING_SPACING_MARK.toInt() ||
                            type == Character.ENCLOSING_MARK.toInt() -> true
                        else -> glyphPaint.hasGlyph(String(Character.toChars(cp)))
                    }
                }) throw OfficeTextEncodingException("Slide font fallback cannot render all source characters")
            val start = content.length
            content.append(value)
            if (start == content.length) continue
            fun span(value: Any) = content.setSpan(value,start,content.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span(AbsoluteSizeSpan(ceil(run.size).toInt()))
            span(ForegroundColorSpan(run.color))
            run.family?.takeIf { it.isNotBlank() }?.let { span(TypefaceSpan(it)) }
            if (style != 0) span(StyleSpan(style))
            if (run.underline) span(UnderlineSpan())
            if (run.strike) span(StrikethroughSpan())
            if (run.superscript) span(SuperscriptSpan())
            if (run.subscript) span(SubscriptSpan())
        }
        val value = content.toString()
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(value,0,value.length)
        val align = when(paragraph.alignment) {
            "CENTER" -> Layout.Alignment.ALIGN_CENTER
            "RIGHT" -> if(rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            "LEFT" -> if(rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            "JUSTIFY" -> Layout.Alignment.ALIGN_NORMAL
            else -> throw OfficeTextEncodingException("Unsupported slide paragraph alignment: ${paragraph.alignment}")
        }
        val builder = StaticLayout.Builder.obtain(content,0,content.length,paint,available.toInt())
            .setAlignment(align).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true).setLineSpacing(0f,paragraph.lineMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (paragraph.firstIndent != 0f) builder.setIndents(
            if (rtl) null else intArrayOf(paragraph.firstIndent.toInt(),0),
            if (rtl) intArrayOf(paragraph.firstIndent.toInt(),0) else null)
        if (paragraph.alignment == "JUSTIFY") builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        if (android.os.Build.VERSION.SDK_INT>=28) builder.setUseLineSpacingFromFallbacks(true)
        val result = builder.build()
        if (result.lineCount !in 1..4096 || result.height<=0 ||
            (0 until result.lineCount).any { result.getLineWidth(it)>available+1f })
            throw OfficeTextEncodingException("Slide paragraph exceeds visible width")
        return result
    }

    private data class StackGlyph(val layout: StaticLayout,val width: Float)
    private data class StackColumn(val glyphs: List<StackGlyph>,val width: Float,val height: Float,
        val before: Float,val after: Float,val alignment: String)

    /** WordArt stacking uses grapheme boundaries, not UTF-16 or individual codepoints.
     * Keep original run styling even where a cluster crosses a run boundary.
     */
    internal fun stackedRuns(runs: List<Run>): List<List<Run>> {
        val value = runs.joinToString("") { it.text }
        if(value.length>16384) throw OfficeTextEncodingException("Stacked slide paragraph exceeds text budget")
        val boundaries = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        boundaries.setText(value)
        val result = mutableListOf<List<Run>>()
        var runIndex = 0
        var runStart = 0
        var start = boundaries.first()
        while(true) {
            val end = boundaries.next()
            if(end==android.icu.text.BreakIterator.DONE) break
            val cluster = mutableListOf<Run>()
            while(runIndex<runs.size && runStart<end) {
                val run = runs[runIndex]
                val runEnd = runStart+run.text.length
                val from = maxOf(start,runStart)
                val to = minOf(end,runEnd)
                if(to>from) cluster.add(run.copy(text=run.text.substring(from-runStart,to-runStart)))
                if(runEnd<=end) { runStart=runEnd; runIndex++ } else break
            }
            if(cluster.isNotEmpty()) result.add(cluster)
            start=end
        }
        return result
    }

    /** One grapheme per row; explicit paragraphs/newlines and height overflow start new columns.
     * Column order follows wordArtVert or wordArtVertRtl, with all geometry admitted before drawing.
     */
    fun drawStacked(canvas: Canvas,paragraphs: List<Paragraph>,left: Float,top: Float,
        width: Float,height: Float,vertical: String,rtlColumns: Boolean) {
        if(!listOf(left,top,width,height).all { it.isFinite() } || width !in 4f..8192f ||
            height !in 1f..8192f || paragraphs.size>1024 ||
            paragraphs.sumOf { p -> p.runs.sumOf { it.text.length.toLong() } }>16384L)
            throw OfficeTextEncodingException("Stacked slide text exceeds rendering budget")
        val columns = mutableListOf<StackColumn>()
        var count = 0
        var totalWidth = 0f
        for(p in paragraphs) {
            if(!listOf(p.before,p.after,p.marginLeft,p.marginRight,p.firstIndent,p.lineMultiplier).all { it.isFinite() } ||
                p.before<0 || p.after<0 || p.marginLeft!=0f || p.marginRight!=0f || p.firstIndent!=0f ||
                p.lineMultiplier !in 0.5f..5f || p.alignment !in setOf("LEFT","CENTER","RIGHT"))
                throw OfficeTextEncodingException("Unsupported stacked paragraph geometry")
            val available = height-p.marginLeft-p.marginRight
            if(available<1f) throw OfficeTextEncodingException("Stacked paragraph has no visible height")
            var glyphs = mutableListOf<StackGlyph>()
            var columnWidth = 0f
            var columnHeight = 0f
            fun flush() {
                if(glyphs.isEmpty()) return
                val column = StackColumn(glyphs.toList(),columnWidth,columnHeight,p.before,p.after,p.alignment)
                totalWidth += column.width+column.before+column.after
                if(totalWidth>width+0.05f) throw OfficeTextEncodingException("Stacked slide text exceeds visible width")
                columns.add(column)
                glyphs=mutableListOf(); columnWidth=0f; columnHeight=0f
            }
            val normalized = p.runs.map { it.copy(text=it.text.replace("\r\n","\n").replace('\r','\n').replace('\u000b','\n').replace('\t',' ')) }
            for(cluster in stackedRuns(normalized)) {
                if(++count>2048) throw OfficeTextEncodingException("Stacked slide grapheme budget exceeded")
                if(cluster.joinToString("") { it.text }=="\n") {
                    if(glyphs.isEmpty()) throw OfficeTextEncodingException("Empty stacked text column is unsupported")
                    flush(); continue
                }
                val layout=prepare(Paragraph(cluster,"LEFT"),width)
                if(layout.lineCount!=1) throw OfficeTextEncodingException("Stacked grapheme exceeds visible width")
                val w=layout.getLineWidth(0).coerceAtLeast(1f)
                val h=layout.height.toFloat()
                if(h>available) throw OfficeTextEncodingException("Stacked grapheme exceeds visible height")
                val advance=h*maxOf(1f,p.lineMultiplier)
                if(glyphs.isNotEmpty() && columnHeight+advance>available) flush()
                if(advance>available) throw OfficeTextEncodingException("Stacked line spacing exceeds visible height")
                glyphs.add(StackGlyph(layout,w))
                columnWidth=maxOf(columnWidth,w); columnHeight+=advance
            }
            flush()
        }
        val spareX=(width-totalWidth).coerceAtLeast(0f)
        val xOffset=when(vertical) {
            "TOP" -> 0f
            "MIDDLE" -> spareX/2f
            "BOTTOM" -> spareX
            else -> throw OfficeTextEncodingException("Unsupported stacked text anchor")
        }
        // Stack column placement starts at the leading side of the frame.
        var x=if(rtlColumns) left+width-xOffset else left+xOffset
        for(column in columns) {
            val columnX=if(rtlColumns) x-column.before-column.width else x+column.before
            val spareY=(height-column.height).coerceAtLeast(0f)
            var y=top+when(column.alignment) { "CENTER" -> spareY/2f; "RIGHT" -> spareY; else -> 0f }
            val advance=column.height/column.glyphs.sumOf { it.layout.height.toDouble() }.toFloat()
            for(glyph in column.glyphs) {
                val save=canvas.save()
                try {
                    canvas.translate(columnX+(column.width-glyph.width)/2f-glyph.layout.getLineLeft(0),y)
                    glyph.layout.draw(canvas)
                } finally { canvas.restoreToCount(save) }
                y+=glyph.layout.height*advance
            }
            x+=(if(rtlColumns) -1f else 1f)*(column.width+column.before+column.after)
        }
    }

    /** Preflight the entire text box before drawing any paragraph. */
    fun draw(canvas: Canvas,paragraphs: List<Paragraph>,left: Float,top: Float,width: Float,height: Float,
        vertical: String = "TOP") {
        if (paragraphs.isEmpty()) return
        if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite() ||
            width !in 4f..8192f || height !in 1f..8192f || paragraphs.size>1024 ||
            paragraphs.sumOf { p -> p.runs.sumOf { it.text.length.toLong() } }>131072L)
            throw OfficeTextEncodingException("Slide text box exceeds rendering budget")
        val positions = mutableListOf<Positioned>()
        var y = 0f
        for (p in paragraphs) {
            if (!p.before.isFinite() || !p.after.isFinite() || p.before<0f || p.after<0f)
                throw OfficeTextEncodingException("Invalid slide paragraph spacing")
            val layout = prepare(p,width)
            y += p.before
            positions.add(Positioned(layout,p.marginLeft,y))
            y += layout.height+p.after
            if (y>height+1f) throw OfficeTextEncodingException("Slide text exceeds its visible text box; export refused instead of omitting text")
        }
        val spare = (height-y).coerceAtLeast(0f)
        val offset = when(vertical) {
            "TOP" -> 0f
            "MIDDLE" -> spare/2f
            "BOTTOM" -> spare
            else -> throw OfficeTextEncodingException("Unsupported slide vertical alignment: $vertical")
        }
        for (position in positions) {
            val save = canvas.save()
            try {
                canvas.translate(left+position.x,top+offset+position.y)
                position.layout.draw(canvas)
            } finally { canvas.restoreToCount(save) }
        }
    }
}
