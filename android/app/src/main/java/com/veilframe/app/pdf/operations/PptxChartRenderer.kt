package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Reconstructs supported saved caches in their graphic-frame bounds.
 * Not an Office chart-layout engine; no workbook recalculation or external fetch.
 */
internal object PptxChartRenderer {
    private val palette=intArrayOf(0xff2463a0.toInt(),0xffd15a36.toInt(),0xff36845b.toInt(),
        0xff8354aa.toInt(),0xffa47715.toInt(),0xff247d82.toInt(),0xffab4574.toInt(),
        0xff6d7e35.toInt(),0xff478caa.toInt(),0xff7f6c52.toInt(),0xffba8743.toInt(),0xff797979.toInt())
    private fun color(series: XlsxCachedChart.Series,i: Int)=series.rgb?.let { it or 0xff000000.toInt() } ?: palette[i%palette.size]
    private fun format(v: Double)=String.format(java.util.Locale.ROOT,"%.4g",v)

    fun draw(canvas: Canvas,chart: XlsxCachedChart,box: RectF,scale: Float,checkpoint: ()->Unit) {
        require(box.width() in 100f..8192f && box.height() in 70f..8192f &&
            scale.isFinite() && scale>0f && chart.series.size in 1..6 &&
            chart.categories.size in 1..240) { "Slide chart exceeds supported frame geometry" }
        val dual=SharedChartValueAxes.build(chart)
        val primary=dual.values.singleOrNull { it.axis.position=="l" }
        val secondary=dual.values.singleOrNull { it.axis.position=="r" }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val w=box.width(); val h=box.height()
        val font=(9f*scale).coerceIn(7f,minOf(w,h)/8)
        val pad=maxOf(4f,font*0.5f)
        val lineH=font*1.55f
        fun label(value: String,x: Float,y: Float,width: Float,height: Float=lineH,align: String="LEFT",bold: Boolean=false) {
            if(value.isBlank()) return
            if(width<4f || height<1f) throw IOException("Slide chart label has no visible rectangle")
            PptxTextLayout.draw(canvas,listOf(PptxTextLayout.Paragraph(listOf(
                PptxTextLayout.Run(value,font,Color.BLACK,bold=bold)),align)),x,y,width,height)
        }
        fun rect(x1: Float,y1: Float,x2: Float,y2: Float,rgb: Int) {
            paint.color=rgb; paint.style=Paint.Style.FILL
            canvas.drawRect(min(x1,x2),min(y1,y2),max(x1,x2),max(y1,y2),paint)
        }
        fun line(x: Float,y: Float,x2: Float,y2: Float,rgb: Int,stroke: Float=1f) {
            paint.color=rgb; paint.style=Paint.Style.STROKE; paint.strokeWidth=stroke
            canvas.drawLine(x,y,x2,y2,paint)
        }
        fun pen(ink: XlsxCachedChart.Ink,spec: XlsxCachedChart.Stroke?,width: Float)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=SharedChartInk.argb(ink); style=Paint.Style.STROKE; strokeWidth=width
            strokeCap=when(spec?.cap) { "rnd" -> Paint.Cap.ROUND; "sq" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            val dash=SharedChartInk.dashes(spec,width)
            if(dash.isNotEmpty()) pathEffect=android.graphics.DashPathEffect(dash,0f)
        }
        fun closed(path: Path,fill: XlsxCachedChart.Ink?,outline: XlsxCachedChart.Stroke?,fallback: Int) {
            fill?.let { canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=SharedChartInk.argb(it) }) }
            SharedChartInk.outlineInk(outline,fallback)?.let {
                canvas.drawPath(path,pen(it,outline,(outline?.widthPt ?: 1f)*scale))
            }
        }
        fun closedRect(x1: Float,y1: Float,x2: Float,y2: Float,fill: XlsxCachedChart.Ink?,
            outline: XlsxCachedChart.Stroke?,fallback: Int) {
            if(x1==x2 || y1==y2) return
            val path=Path().apply { addRect(min(x1,x2),min(y1,y2),max(x1,x2),max(y1,y2),Path.Direction.CW) }
            closed(path,fill,outline,fallback)
        }
        fun marker(x: Float,y: Float,s: XlsxCachedChart.Series,rgb: Int) {
            val size=((s.markerSizePt ?: 4f)*scale).coerceIn(2f,36f)
            val r=size/2
            if(s.markerSymbol=="none") return
            val path=Path()
            val half=size/10
            fun bar(x1: Float,y1: Float,x2: Float,y2: Float) {
                path.addRect(x1,y1,x2,y2,Path.Direction.CW)
            }
            fun diagonal(up: Boolean) {
                val sign=if(up) -1 else 1
                path.moveTo(x-r,y+sign*(r-2*half))
                path.lineTo(x-r+2*half,y+sign*r)
                path.lineTo(x+r,y-sign*(r-2*half))
                path.lineTo(x+r-2*half,y-sign*r)
                path.close()
            }
            when(s.markerSymbol ?: "square") {
                "circle" -> path.addCircle(x,y,r,Path.Direction.CW)
                "square" -> bar(x-r,y-r,x+r,y+r)
                "diamond" -> { path.moveTo(x,y-r); path.lineTo(x+r,y); path.lineTo(x,y+r); path.lineTo(x-r,y); path.close() }
                "triangle" -> { path.moveTo(x,y-r); path.lineTo(x+r,y+r); path.lineTo(x-r,y+r); path.close() }
                "dot" -> bar(x-r/2,y-half,x+r/2,y+half)
                "dash" -> bar(x-r,y-half,x+r,y+half)
                "plus","x","star" -> {
                    if(s.markerSymbol!="x") { bar(x-r,y-half,x+r,y+half); bar(x-half,y-r,x+half,y+r) }
                    if(s.markerSymbol!="plus") { diagonal(true); diagonal(false) }
                }
                else -> throw IOException("Unsupported chart marker")
            }
            closed(path,SharedChartInk.resolveInk(s.markerFill,rgb),s.markerStroke,rgb)
        }

        val saved=canvas.save()
        try {
            canvas.clipRect(box)
            rect(box.left,box.top,box.right,box.bottom,Color.WHITE)
            label(chart.title,box.left+pad,box.top+pad,w-2*pad,lineH,"CENTER",true)
            val legendTop=box.top+pad+lineH
            if(chart.kind=="pie") {
                val s=chart.series.single()
                require(s.values.size==chart.categories.size && s.values.size<=12)
                val maxValue=s.values.filterNotNull().maxOrNull()?.takeIf { it>0 } ?: throw IOException("Empty slide pie")
                val sum=s.values.sumOf { (it ?: 0.0)/maxValue }
                val pieWidth=w*0.55f-2*pad
                val pieHeight=box.bottom-legendTop-2*pad
                val diameter=minOf(pieWidth,pieHeight)
                require(diameter>12f && lineH*s.values.size<=pieHeight) { "Slide pie legend exceeds frame" }
                val cx=box.left+pad+pieWidth/2
                val cy=legendTop+pad+pieHeight/2
                val oval=RectF(cx-diameter/2,cy-diameter/2,cx+diameter/2,cy+diameter/2)
                var angle=-90f+chart.firstSliceAngle
                for((i,v) in s.values.withIndex()) {
                    checkpoint()
                    val sweep=(((v ?: 0.0)/maxValue)/sum*360).toFloat()
                    val fallback=XlsxPieChartPainter.palette[i]
                    val ink=SharedChartInk.pointInk(s,i,fallback)
                    val outline=SharedChartInk.pointStroke(s,i)
                    if(sweep>0) {
                        val wedge=Path()
                        if(sweep>=360f) wedge.addOval(oval,Path.Direction.CW)
                        else { wedge.moveTo(cx,cy); wedge.arcTo(oval,angle,sweep,false); wedge.close() }
                        closed(wedge,ink,outline,fallback)
                    }
                    angle+=sweep
                    val x=box.left+w*0.55f
                    val y=legendTop+pad+i*lineH
                    closedRect(x,y+pad,x+font,y+pad+font,ink,outline,fallback)
                    label(chart.categories[i].orEmpty(),x+font+pad,y,box.right-x-font-2*pad)
                }
                return
            }
            // Explicit legend for every saved series, even when the chart's
            // original legend formatting cannot be reconstructed.
            val legendWidth=(w-2*pad)/chart.series.size
            for((i,s) in chart.series.withIndex()) {
                val x=box.left+pad+legendWidth*i
                rect(x,legendTop+pad,x+font,legendTop+pad+font,color(s,i))
                label(s.name+(dual[s.valueAxisId]?.let { if(it.axis.position=="r") " (R)" else " (L)" } ?: ""),x+font+pad,legendTop,legendWidth-font-2*pad)
            }
            val horizontal=chart.kind=="bar"
            val left=box.left+maxOf(font*5f,w*0.16f)
            val top=legendTop+lineH+pad
            val right=box.right-if(secondary!=null) maxOf(font*5f,w*0.16f) else pad+font
            val bottom=box.bottom-pad-lineH
            val pw=right-left; val ph=bottom-top
            require(pw>12f && ph>12f)
            val scatter=chart.kind=="scatter"
            val maxAbs=chart.series.flatMap { it.values }.filterNotNull().maxOfOrNull { abs(it) }?.takeIf { it>0.0 } ?: 1.0
            val stacked=chart.kind in setOf("bar","column") && chart.grouping in setOf("stacked","percentStacked")
            val percent=stacked && chart.grouping=="percentStacked"
            val stacks=if(stacked) XlsxStackedChart.segments(chart,maxAbs,percent) else emptyList()
            val values=if(stacked) stacks.flatten().filterNotNull().flatMap { listOf(it.start,it.end) }
                else chart.series.flatMap { it.values }.filterNotNull().map { it/maxAbs }
            val low=minOf(0.0,values.minOrNull() ?: 0.0)
            val high=maxOf(0.0,values.maxOrNull() ?: 0.0)
            val span=(high-low).takeIf { it>0 } ?: 1.0
            fun f(value: Double)=((value-low)/span).toFloat().coerceIn(0f,1f)
            val pairs=if(scatter) chart.series.flatMap { s ->
                s.xValues.zip(s.values).mapNotNull { (x,y) -> if(x==null || y==null) null else x to y }
            } else emptyList()
            val xAxis=if(scatter) XlsxScatterGeometry.axis(pairs.map { it.first }) else null
            val yAxis=if(scatter) XlsxScatterGeometry.axis(pairs.map { it.second }) else null
            if(!scatter) {
                for(i in 0..4) {
                    val ratio=i/4f
                    val value=low+span*ratio
                    val v=primary?.tick(ratio) ?: if(percent) "${(value*100).toInt()}%" else {
                        val absolute=value*maxAbs
                        if(absolute.isFinite()) format(absolute) else format(value)+" x "+format(maxAbs)
                    }
                    if(horizontal) {
                        val x=left+ratio*pw
                        line(x,top,x,bottom,0xffd8dde2.toInt())
                        label(v,(x-font*2).coerceAtLeast(box.left),bottom+pad,font*4,lineH,"CENTER")
                    } else {
                        val y=bottom-ratio*ph
                        line(left,y,right,y,0xffd8dde2.toInt())
                        label(v,box.left+pad,y-lineH/2,left-box.left-2*pad,lineH,"RIGHT")
                        secondary?.let { label(it.tick(ratio),right+pad,y-lineH/2,box.right-right-2*pad,lineH,"LEFT") }
                    }
                }
            } else {
                line(left,bottom,right,bottom,Color.DKGRAY)
                line(left,top,left,bottom,Color.DKGRAY)
                // Numeric tick endpoints are rendered from the actual saved data.
                label(requireNotNull(xAxis).label(0f),left,bottom+pad,font*5)
                label(xAxis.label(1f),right-font*5,bottom+pad,font*5,lineH,"RIGHT")
                label(requireNotNull(yAxis).label(1f),box.left+pad,top,left-box.left-2*pad,lineH,"RIGHT")
                label(yAxis.label(0f),box.left+pad,bottom-lineH/2,left-box.left-2*pad,lineH,"RIGHT")
            }
            val slot=(if(horizontal) ph else pw)/chart.categories.size
            if(!scatter) {
                if(horizontal && slot<lineH) throw IOException("Slide chart category labels overlap")
                // No silent category truncation: reject frames too small for their labels.
                for((i,category) in chart.categories.withIndex()) {
                    if(horizontal) label(category.orEmpty(),box.left+pad,top+slot*i+(slot-lineH)/2,left-box.left-2*pad,lineH,"RIGHT")
                    else label(category.orEmpty(),left+slot*i,bottom+pad,slot,lineH,"CENTER")
                }
            }
            val barCount=if(chart.kind=="combo") chart.series.count { it.chartKind=="column" } else chart.series.size
            val thickness=slot*0.78f/(if(stacked) 1 else barCount.coerceAtLeast(1))
            var barIndex=0
            val plotSave=canvas.save()
            try {
            if(dual.isNotEmpty()) canvas.clipRect(left,top,right,bottom)
            for((i,s) in chart.series.withIndex()) {
                val axis=dual[s.valueAxisId]
                fun seriesFraction(v: Double)=axis?.fraction(v) ?: f(v/maxAbs)
                val rgb=color(s,i)
                val stroke=((s.stroke?.widthPt ?: s.strokeWidthPt ?: 1.2f)*scale).coerceIn(0.01f,72f)
                val linePath=Path()
                val markers=mutableListOf<Pair<Float,Float>>()
                var previous: Pair<Float,Float>?=null
                var hasLine=false
                fun connect(x: Float,y: Float) {
                    if(previous==null) linePath.moveTo(x,y)
                    else { linePath.lineTo(x,y); hasLine=true }
                    previous=x to y
                }
                val isLine=chart.kind=="line" || chart.kind=="combo" && s.chartKind=="line"
                for((j,cached) in s.values.withIndex()) {
                    checkpoint()
                    if(scatter) {
                        val x=s.xValues[j]
                        if(x==null || cached==null) { previous=null; continue }
                        val px=left+requireNotNull(xAxis).fraction(x)*pw
                        val py=bottom-requireNotNull(yAxis).fraction(cached)*ph
                        if(chart.scatterStyle!="marker") connect(px,py)
                        if(chart.scatterStyle!="line") markers.add(px to py)
                        continue
                    }
                    val v=if(isLine) chart.blankDisplay.lineValue(cached) else cached
                    if(v==null) { if(!isLine || chart.blankDisplay.breakOnMissing()) previous=null; continue }
                    if(isLine) {
                        val px=left+slot*(j+0.5f); val py=bottom-seriesFraction(v)*ph
                        connect(px,py)
                        markers.add(px to py)
                    } else {
                        val ink=SharedChartInk.pointInk(s,j,rgb)
                        val outline=SharedChartInk.pointStroke(s,j)
                        val segment=if(stacked) stacks[i][j] ?: continue else XlsxStackedChart.Segment(0.0,v/maxAbs)
                        val offset=slot*j+slot*0.11f+if(stacked) 0f else barIndex*thickness
                        if(horizontal) closedRect(left+f(segment.start)*pw,top+offset,left+f(segment.end)*pw,top+offset+thickness,ink,outline,rgb)
                        else {
                            val start=axis?.zero() ?: f(segment.start)
                            val end=axis?.fraction(v) ?: f(segment.end)
                            closedRect(left+offset,bottom-start*ph,left+offset+thickness,bottom-end*ph,ink,outline,rgb)
                        }
                    }
                }
                if(hasLine) SharedChartInk.resolveInk(s.stroke?.ink,rgb)?.let {
                    canvas.drawPath(linePath,pen(it,s.stroke,stroke))
                }
                for((x,y) in markers) marker(x,y,s,rgb)
                if(!isLine && !scatter) barIndex++
            }
            } finally { canvas.restoreToCount(plotSave) }
        } finally { canvas.restoreToCount(saved) }
    }
}
