package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlin.math.abs

/**
 * Bounded print-only chart placement in the same approximate worksheet geometry.
 * Two-cell zero-offset DrawingML anchors use half-open end markers.
 * This is not Excel's original drawing engine or printer-exact rendering.
 */
internal object XlsxAnchoredChartRenderer {
    data class Placement(val plan: Int, val tile: Int,
        val left: Float, val top: Float, val width: Float, val height: Float)
    private data class Marker(val row: Int, val col: Int)
    private fun marker(s: String): Marker? {
        if (!s.matches(Regex("[A-Za-z]{1,3}[1-9][0-9]{0,6}"))) return null
        val col = runCatching { OpenXmlDocumentParser.colRefToIndex(s) }.getOrNull() ?: return null
        val row = s.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: return null
        return if (row in 0 until 1_048_576 && col in 0 until 16_384) Marker(row,col) else null
    }

    /** Refuse clipped, hidden, repeated-title, multi-area and multi-page charts. */
    fun locate(chart: XlsxCachedChart, layout: SheetLayout,
        plans: List<XlsxSavedPrintLayout.Plan>): Placement? {
        if (!chart.printsWithSheet || !chart.printAreaAnchorTrusted ||
            chart.kind !in setOf("column","bar","line","combo","scatter")) return null
        val ends = chart.sourceAnchor?.split(':') ?: return null
        if (ends.size != 2) return null
        val from = marker(ends[0]) ?: return null
        val to = marker(ends[1]) ?: return null
        val rows = to.row-from.row
        val cols = to.col-from.col
        if (rows !in 2..512 || cols !in 2..128 ||
            to.row > layout.rowCount || to.col > layout.colCount) return null
        var found: Placement? = null
        for ((pi,plan) in plans.withIndex()) for ((ti,tile) in plan.tiles.withIndex()) {
            val rr = tile.first; val cc = tile.second
            val r = rr.indexOf(from.row); val c = cc.indexOf(from.col)
            if (r < 0 || c < 0 || r+rows > rr.size || c+cols > cc.size) continue
            if ((0 until rows).any { rr[r+it] != from.row+it } ||
                (0 until cols).any { cc[c+it] != from.col+it }) continue
            val x = cc.take(c).sumOf { layout.colWidths[it].toDouble() }.toFloat()
            val y = -rr.take(r).sumOf { layout.rowHeights[it].toDouble() }.toFloat()
            val w = (from.col until to.col).sumOf { layout.colWidths[it].toDouble() }.toFloat()
            val h = (from.row until to.row).sumOf { layout.rowHeights[it].toDouble() }.toFloat()
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() ||
                w < 120f || h < 80f || found != null) return null
            found = Placement(pi,ti,x,y,w,h)
        }
        return found
    }

    fun draw(stream: PDPageContentStream, chart: XlsxCachedChart, p: Placement,
        checkpoint: () -> Unit) {
        require(chart.series.size in 1..6 && chart.categories.size in 1..240)
        val font = PDType1Font.HELVETICA
        require(chart.title.all { it >= ' ' && it != '\u007f' }) { "Unsupported saved chart title" }
        try {
            font.encode(chart.title)
            require(font.getStringWidth(chart.title)*8f/1000f <= p.width-12f) {
                "Saved chart title exceeds the verified drawing rectangle"
            }
        } catch (_: Exception) { throw IllegalArgumentException("Unsupported saved chart title or glyphs") }
        val palette = intArrayOf(0x2463A0,0xD15A36,0x36845B,0x8354AA,0xA47715,0x247D82)
        fun color(rgb: Int) = stream.setNonStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
        fun stroke(rgb: Int) = stream.setStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
        fun line(x: Float,y: Float,x2: Float,y2: Float) {
            stream.moveTo(x,y); stream.lineTo(x2,y2); stream.stroke()
        }
        val bottom = p.top-p.height
        stream.saveGraphicsState()
        try {
            stream.addRect(p.left,bottom,p.width,p.height); stream.clip()
            color(0xFFFFFF); stream.addRect(p.left,bottom,p.width,p.height); stream.fill()
            stroke(0x9BA4AC); stream.setLineWidth(0.6f)
            stream.addRect(p.left+0.5f,bottom+0.5f,p.width-1f,p.height-1f); stream.stroke()
            if (chart.title.isNotEmpty()) {
                color(0x222222); stream.beginText(); stream.setFont(font,8f)
                stream.newLineAtOffset(p.left+6f,p.top-13f); stream.showText(chart.title); stream.endText()
            }
            val x0=p.left+22f; val y0=bottom+17f
            val w=p.width-33f; val h=p.height-42f
            require(w > 0f && h > 0f)
            stroke(0xD8DDE2); stream.setLineWidth(0.35f)
            for (tick in 0..4) line(x0,y0+tick*h/4f,x0+w,y0+tick*h/4f)

            if (chart.kind == "scatter") {
                require(chart.scatterStyle in setOf("marker","line","lineMarker"))
                val pairs=chart.series.flatMap { series ->
                    series.xValues.zip(series.values).mapNotNull { (x,y) -> if(x == null || y == null) null else x to y }
                }
                require(pairs.isNotEmpty()) { "Saved scatter has no numeric pairs" }
                val ax=XlsxScatterGeometry.axis(pairs.map { it.first })
                val ay=XlsxScatterGeometry.axis(pairs.map { it.second })
                for ((i,s) in chart.series.withIndex()) {
                    checkpoint()
                    require(s.xValues.size == s.values.size)
                    val rgb=s.rgb ?: palette[i]; color(rgb); stroke(rgb)
                    stream.setLineWidth(s.strokeWidthPt ?: 1.2f)
                    var previous: Pair<Float,Float>?=null
                    for ((x,y) in s.xValues.zip(s.values)) {
                        checkpoint()
                        if(x == null || y == null){previous=null;continue}
                        val sx=x0+ax.fraction(x)*w; val sy=y0+ay.fraction(y)*h
                        if(chart.scatterStyle != "marker") previous?.let { line(it.first,it.second,sx,sy) }
                        if(chart.scatterStyle != "line"){
                            stream.addRect(sx-1.4f,sy-1.4f,2.8f,2.8f);stream.fill()
                        }
                        previous=sx to sy
                    }
                }
            } else {
                val stacked=chart.kind in setOf("bar","column") && chart.grouping in setOf("stacked","percentStacked")
                val maxAbs=chart.series.flatMap { it.values }.filterNotNull()
                    .maxOfOrNull { abs(it) }?.takeIf { it>0.0 } ?: 1.0
                val stacks=if(stacked) XlsxStackedChart.segments(chart,maxAbs,chart.grouping=="percentStacked") else emptyList()
                val bounds=if(stacked) stacks.flatten().filterNotNull().flatMap { listOf(it.start,it.end) }
                    else chart.series.flatMap { it.values }.filterNotNull().map { it/maxAbs }
                val min=minOf(0.0,bounds.minOrNull() ?: 0.0)
                val max=maxOf(0.0,bounds.maxOrNull() ?: 0.0)
                val span=(max-min).takeIf { it>0.0 } ?: 1.0
                fun fraction(v:Double)=((v-min)/span).toFloat().coerceIn(0f,1f)
                val horizontal=chart.kind=="bar"
                val slot=(if(horizontal) h else w)/chart.categories.size
                val bars=if(chart.kind=="combo") chart.series.count { it.chartKind=="column" }
                    else chart.series.size
                require(bars>0)
                val thickness=slot*0.77f/(if(stacked) 1 else bars)
                for ((i,s) in chart.series.withIndex()){
                    checkpoint()
                    val rgb=s.rgb ?: palette[i];color(rgb);stroke(rgb)
                    var previous:Pair<Float,Float>?=null
                    for ((point,raw) in s.values.withIndex()){
                        checkpoint()
                        if(raw == null){previous=null;continue}
                        val position=if(horizontal) y0+h-slot*(point+0.5f) else x0+slot*(point+0.5f)
                        if(chart.kind=="line" || (chart.kind=="combo" && s.chartKind=="line")){
                            val sy=y0+fraction(raw/maxAbs)*h
                            stream.setLineWidth(s.strokeWidthPt ?: 1.2f)
                            previous?.let { line(it.first,it.second,position,sy) }
                            stream.addRect(position-1.3f,sy-1.3f,2.6f,2.6f);stream.fill()
                            previous=position to sy
                        }else{
                            val ordinal=if(chart.kind=="combo") chart.series.take(i).count { it.chartKind=="column" } else i
                            val offset=position-slot*0.385f+(if(stacked) 0 else ordinal)*thickness
                            val segment=if(stacked) stacks[i][point] ?: continue
                                else XlsxStackedChart.Segment(0.0,raw/maxAbs)
                            val a=fraction(segment.start);val b=fraction(segment.end)
                            if(abs(a-b)<1e-7f) continue
                            if(horizontal){
                                val ax=x0+a*w;val bx=x0+b*w
                                stream.addRect(minOf(ax,bx),offset,abs(bx-ax),thickness*0.9f)
                            }else{
                                val ay=y0+a*h;val by=y0+b*h
                                stream.addRect(offset,minOf(ay,by),thickness*0.9f,abs(by-ay))
                            }
                            stream.fill()
                        }
                    }
                }
            }
        } finally { stream.restoreGraphicsState() }
    }
}
