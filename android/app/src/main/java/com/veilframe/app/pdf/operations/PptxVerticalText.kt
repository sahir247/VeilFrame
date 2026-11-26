package com.veilframe.app.pdf.operations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.SpannableStringBuilder
import android.text.TextPaint
import androidx.text.vertical.TextOrientation
import androidx.text.vertical.VerticalTextLayout
import kotlin.math.ceil

/** Real mixed-orientation vertical shaping. The AndroidX engine draws right-to-left
 * columns; lay out single columns for both directions to preserve Mongolian order
 * without reflecting the glyphs or reversing the logical source text.
 */
internal object PptxVerticalText {
    private data class Column(val layout: VerticalTextLayout,val before: Float,val after: Float,
        val top: Float,val available: Float,val inkTop: Int,val inkBottom: Int)

    internal fun inlineBounds(height: Float,marginStart: Float,marginEnd: Float,indent: Float,first: Boolean): Pair<Float,Float> {
        val start=marginStart+if(first) indent else 0f
        val available=height-start-marginEnd
        if(!listOf(height,start,marginEnd,available).all { it.isFinite() } ||
            start<0f || marginEnd<0f || available<4f)
            throw OfficeTextEncodingException("Vertical paragraph has no visible height")
        return start to available
    }

    private fun layout(text: CharSequence,paint: TextPaint,start: Int,end: Int,height: Float): VerticalTextLayout =
        VerticalTextLayout(text,start,end,paint,height,TextOrientation.Mixed).also {
            if(!it.isVerticalTextSupported() || it.lineCount<1 || !it.width.isFinite() || it.width<=0)
                throw OfficeTextEncodingException("Vertical text shaping unavailable on this device")
        }

    /** Check actual ink extent, including an overlong unbreakable run. The
     * public beta API exposes width/column count, but not laid-out height.
     * Do not publish a clipped column or infer success from a nonempty layout.
     */
    private fun ink(column: VerticalTextLayout,height: Float,maxSize: Float): Pair<Int,Int> {
        val pad=ceil(maxSize*2+8).toInt()
        val w=ceil(column.width).toInt()
        val h=ceil(height).toInt()
        val bw=w+2*pad
        val bh=h+2*pad
        if(bw !in 1..8192 || bh !in 1..8192 || bw.toLong()*bh>4_000_000L)
            throw OfficeTextEncodingException("Vertical text verification exceeds pixel budget")
        val bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
        try {
            column.draw(Canvas(bitmap),(pad+w).toFloat(),pad.toFloat())
            val row=IntArray(bw)
            var first=Int.MAX_VALUE
            var last=-1
            for(y in 0 until bh) {
                bitmap.getPixels(row,0,bw,0,y,bw,1)
                for(x in row.indices) if((row[x] ushr 24)>8) {
                    // Allow two pixels of antialiasing overhang.
                    if(x<pad-2 || x>=pad+w+2 || y<pad-2 || y>=pad+h+2)
                        throw OfficeTextEncodingException("Vertical glyph ink exceeds visible column")
                    first=minOf(first,y-pad); last=maxOf(last,y-pad)
                }
            }
            if(last<first) throw OfficeTextEncodingException("Vertical text produced no visible ink")
            return first to last+1
        } finally { bitmap.recycle() }
    }

    fun draw(canvas: Canvas,paragraphs: List<PptxTextLayout.Paragraph>,left: Float,top: Float,
        width: Float,height: Float,anchor: String,rtlColumns: Boolean) {
        if(!listOf(left,top,width,height).all { it.isFinite() } || width !in 4f..8192f ||
            height !in 4f..8192f || paragraphs.size>256 ||
            paragraphs.sumOf { p -> p.runs.sumOf { it.text.length.toLong() } }>8192L)
            throw OfficeTextEncodingException("Vertical text exceeds layout budget")
        val columns=mutableListOf<Column>()
        var total=0f
        var attempts=0
        for(p in paragraphs) {
            if(!listOf(p.before,p.after,p.marginLeft,p.marginRight,p.firstIndent,p.lineMultiplier).all { it.isFinite() } ||
                p.before<0 || p.after<0 || p.marginLeft<0 || p.marginRight<0 ||
                p.lineMultiplier !in 1f..5f || p.alignment !in setOf("LEFT","CENTER","RIGHT"))
                throw OfficeTextEncodingException("Unsupported vertical paragraph spacing or indentation")
            val (text,paint)=PptxTextLayout.attributed(p.runs)
            inlineBounds(height,p.marginLeft,p.marginRight,p.firstIndent,true)
            inlineBounds(height,p.marginLeft,p.marginRight,p.firstIndent,false)
            val maxSize=p.runs.maxOfOrNull { it.size } ?: 14f
            if(text.isBlank()) throw OfficeTextEncodingException("Empty vertical paragraph is unsupported")
            var start=0
            while(start<text.length) {
                val newline=text.indexOf('\n',start).let { if(it<0) text.length else it }
                if(newline==start) throw OfficeTextEncodingException("Empty vertical column is unsupported")
                val section=SpannableStringBuilder(text,start,newline)
                val iterator=android.icu.text.BreakIterator.getLineInstance(java.util.Locale.ROOT)
                iterator.setText(section.toString())
                val ends=mutableListOf<Int>()
                var next=iterator.next()
                while(next!=android.icu.text.BreakIterator.DONE) { ends.add(next); next=iterator.next() }
                var offset=0
                var boundary=0
                while(offset<section.length) {
                    if(columns.size>=128) throw OfficeTextEncodingException("Vertical text exceeds column budget")
                    val first=offset==0 && start==0
                    val (inlineStart,available)=inlineBounds(height,p.marginLeft,p.marginRight,p.firstIndent,first)
                    // Find longest legal line-break prefix that fits one column.
                    // Never split a contextual Mongolian word into isolated letters.
                    var low=boundary; var high=ends.lastIndex
                    var best=-1
                    var chosen: VerticalTextLayout?=null
                    while(low<=high) {
                        if(++attempts>2048) throw OfficeTextEncodingException("Vertical line search exceeds budget")
                        val mid=(low+high)/2
                        val candidate=layout(section,paint,offset,ends[mid],available)
                        if(candidate.lineCount==1 && candidate.width<=width-total+0.05f) {
                            best=mid; chosen=candidate; low=mid+1
                        } else high=mid-1
                    }
                    val column=chosen ?: throw OfficeTextEncodingException("Unbreakable vertical text does not fit")
                    val (inkTop,inkBottom)=ink(column,available,maxSize)
                    val spare=(available-(inkBottom-inkTop)).coerceAtLeast(0f)
                    val y=inlineStart+when(p.alignment) { "CENTER" -> spare/2; "RIGHT" -> spare; else -> 0f }
                    val before=if(first) p.before else 0f
                    val last=ends[best]==section.length && newline==text.length
                    val after=if(last) p.after else column.width*(p.lineMultiplier-1f)
                    total+=column.width+before+after
                    if(total>width+0.05f) throw OfficeTextEncodingException("Vertical columns exceed visible width")
                    columns.add(Column(column,before,after,y,available,inkTop,inkBottom))
                    offset=ends[best]; boundary=best+1
                }
                if(newline==text.length) break
                start=newline+1
                if(start==text.length) throw OfficeTextEncodingException("Trailing empty vertical column is unsupported")
            }
        }
        val spare=(width-total).coerceAtLeast(0f)
        val shift=when(anchor) {
            "TOP" -> 0f
            "MIDDLE" -> spare/2f
            "BOTTOM" -> spare
            else -> throw OfficeTextEncodingException("Unsupported vertical text anchor")
        }
        var x=if(rtlColumns) left+width-shift else left+shift
        for(c in columns) {
            val right=if(rtlColumns) x-c.before else x+c.before+c.layout.width
            c.layout.draw(canvas,right,top+c.top-c.inkTop)
            x+=(if(rtlColumns) -1 else 1)*(c.layout.width+c.before+c.after)
        }
    }
}
