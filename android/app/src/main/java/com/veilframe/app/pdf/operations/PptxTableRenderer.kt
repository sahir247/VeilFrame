package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTableCell
import org.w3c.dom.Node
import java.io.IOException

/** Saved grid geometry and explicit cell formatting; inherited Office table themes are approximate. */
internal object PptxTableRenderer {
    data class Span(val rows: Int=1,val cols: Int=1,val covered: Boolean=false)
    data class Cell(val row: Int,val col: Int,val rows: Int,val cols: Int)

    fun grid(rows: Int,cols: Int,span: (Int,Int)->Span?): List<Cell> {
        require(rows in 1..1000 && cols in 1..1000 && rows.toLong()*cols<=10000) {
            "Slide table exceeds grid budget"
        }
        val used=BooleanArray(rows*cols)
        val cells=mutableListOf<Cell>()
        for(r in 0 until rows) for(c in 0 until cols) {
            val s=span(r,c)
            val index=r*cols+c
            if(used[index]) {
                require(s==null || s.covered) { "Overlapping slide table cells" }
                continue
            }
            require(s!=null && !s.covered && s.rows in 1..rows-r && s.cols in 1..cols-c) {
                "Missing or invalid slide table merge anchor"
            }
            for(y in r until r+s.rows) for(x in c until c+s.cols) {
                require(!used[y*cols+x]) { "Overlapping slide table merges" }
                used[y*cols+x]=true
            }
            cells.add(Cell(r,c,s.rows,s.cols))
        }
        return cells
    }
    fun edges(values: List<Double>,start: Float,length: Float): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() && it>0 } &&
            start.isFinite() && length.isFinite() && length>0)
        val sum=values.sum()
        require(sum.isFinite() && sum>0)
        val out=FloatArray(values.size+1)
        out[0]=start
        var accumulated=0.0
        for(i in values.indices) {
            accumulated+=values[i]
            out[i+1]=start+(accumulated/sum*length).toFloat()
            require(out[i+1]>out[i]) { "Slide table cell has no visible extent" }
        }
        return out
    }
    private data class Border(val color: Int?,val style: PptxStrokeStyle.Spec?,val priority: Int)
    private fun one(n: Node?,name: String)=PptxTableStyle.one(n,name)
    private fun prefer(a: Border?,b: Border?,aAnchor: Boolean,bAnchor: Boolean): Border? {
        if(a!=null && b!=null && a.priority!=b.priority) return if(a.priority>b.priority) a else b
        return sharedBorder(a,b,aAnchor,bAnchor)
    }
    private data class Visual(val entry: Cell,val cell: XSLFTableCell,val box: RectF,val fill: Int?,val borders: Map<String,Border>)

    // Explicit shared edges: an upper/left anchor takes precedence even when
    // its border is explicitly transparent. A merged-away neighbour does not
    // compete. Missing/inherited styles are not resolved by this helper.
    internal fun <T> sharedBorder(leading: T?,trailing: T?,leadingIsAnchor: Boolean,
        trailingIsAnchor: Boolean): T? = when {
        leadingIsAnchor && leading!=null -> leading
        trailingIsAnchor && trailing!=null -> trailing
        else -> leading ?: trailing
    }
    fun draw(canvas: Canvas,table: XSLFTable,bounds: RectF,fontScale: Float,
        theme: (String)->Int?,checkpoint: ()->Unit,text: (XSLFTableCell,RectF)->Unit) {
        val rows=table.numberOfRows
        val cols=table.numberOfColumns
        val props=one(table.ctTable.domNode,"tblPr")
        val flags=PptxTableStyle.flags(props)
        val style=PptxTableStyle.select(props,table.sheet.slideShow.tableStyles?.xmlObject?.domNode)
        val format=table.sheet.theme?.xmlObject?.themeElements?.fmtScheme
        fun fillLookup(index: Int): Node? {
            if(index==0 || index==1000) return null
            val list=if(index>1000) format?.bgFillStyleLst?.domNode else format?.fillStyleLst?.domNode
            return list?.let { PptxTableStyle.children(it).getOrNull(if(index>1000) index-1001 else index-1) }
        }
        fun lineLookup(index: Int): Node? {
            val lines=format?.lnStyleLst
            return if(lines!=null && index in 1..lines.sizeOfLnArray()) lines.getLnArray(index-1).domNode else null
        }
        val tableBackground=PptxTableStyle.inheritedFill(one(style,"tblBg"),::fillLookup,theme)?.let {
            PptxTableStyle.solid(it.node,theme,it.placeholder)
        }
        val plan=grid(rows,cols) { r,c ->
            checkpoint()
            table.getCell(r,c)?.let { Span(it.rowSpan,it.gridSpan,it.isMerged) }
        }
        val xs=edges(List(cols) { table.getColumnWidth(it) },bounds.left,bounds.width())
        val ys=edges(List(rows) { table.getRowHeight(it) },bounds.top,bounds.height())
        val visuals=plan.map { entry ->
            checkpoint()
            val cell=requireNotNull(table.getCell(entry.row,entry.col))
            val properties=one(cell.xmlObject.domNode,"tcPr")
            val layers=PptxTableStyle.layers(style,entry.row,entry.col,rows,cols,flags)
            val localFill=PptxTableStyle.directFill(properties)
            val inheritedFill=if(localFill==null) layers.asReversed().firstNotNullOfOrNull {
                PptxTableStyle.inheritedFill(it.cell,::fillLookup,theme)
            } else null
            val color=when {
                localFill!=null -> PptxTableStyle.solid(localFill,theme)
                inheritedFill!=null -> PptxTableStyle.solid(inheritedFill.node,theme,inheritedFill.placeholder)
                else -> null
            }
            val borders=mutableMapOf<String,Border>()
            for(edge in listOf("lnL","lnR","lnT","lnB","lnTlToBr","lnBlToTr")) {
                val direct=one(properties,edge)
                val inherited=layers.asReversed().firstNotNullOfOrNull { region ->
                    val role=PptxTableStyle.borderRole(region.name,edge,
                        if(edge=="lnB") entry.row+entry.rows-1 else entry.row,
                        if(edge=="lnR") entry.col+entry.cols-1 else entry.col,rows,cols,flags)
                    val wrapper=one(one(region.cell,"tcBdr"),role)
                    wrapper?.let { PptxTableStyle.inheritedBorder(it,::lineLookup,theme) }
                        ?.let { it to PptxTableStyle.priority(region.name) }
                }
                val localLineFill=PptxTableStyle.directFill(direct)
                val chosenFill=localLineFill ?: PptxTableStyle.directFill(inherited?.first?.node)
                if(chosenFill!=null) {
                    val ink=PptxTableStyle.solid(chosenFill,theme,
                        if(localLineFill!=null) null else inherited?.first?.placeholder)
                    val priority=if(direct!=null) 100 else inherited?.second ?: 0
                    val stroke=if(ink==null) null else PptxStrokeStyle.parse(
                        direct ?: requireNotNull(inherited).first.node,fontScale,fontScale,
                        inherited=inherited?.first?.node)
                    borders[edge]=Border(ink,stroke,priority)
                } else if(direct!=null) {
                    throw IOException("Unresolved explicit table border")
                }
            }
            Visual(entry,cell,RectF(xs[entry.col],ys[entry.row],xs[entry.col+entry.cols],ys[entry.row+entry.rows]),color,borders)
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        tableBackground?.let { paint.color=it; canvas.drawRect(bounds,paint) }
        for(v in visuals) {
            checkpoint()
            v.fill?.let { paint.color=it; canvas.drawRect(v.box,paint) }
        }
        for(v in visuals) {
            checkpoint()
            text(v.cell,v.box)
        }
        val owners=arrayOfNulls<Visual>(rows*cols)
        for(v in visuals) for(r in v.entry.row until v.entry.row+v.entry.rows)
            for(c in v.entry.col until v.entry.col+v.entry.cols) owners[r*cols+c]=v
        fun at(r: Int,c: Int): Visual? =
            if(r in 0 until rows && c in 0 until cols) owners[r*cols+c] else null
        fun anchor(v: Visual?,r: Int,c: Int)=v?.entry?.let { it.row==r && it.col==c }==true
        fun line(b: Border?,x1: Float,y1: Float,x2: Float,y2: Float) {
            val color=b?.color ?: return
            val style=b.style ?: return
            if(style.width>0f) canvas.drawLine(x1,y1,x2,y2,style.paint(color))
        }
        // Coalesce identical adjacent segments so merged edges/dashes stay continuous.
        for(r in 0..rows) {
            checkpoint()
            fun edge(c: Int): Border? {
                val above=at(r-1,c); val below=at(r,c)
                if(above===below) return null
                return prefer(above?.borders?.get("lnB"),below?.borders?.get("lnT"),
                    anchor(above,r-1,c),anchor(below,r,c))
            }
            var start=0
            while(start<cols) {
                val b=edge(start)
                var end=start+1
                while(end<cols && edge(end)==b) end++
                line(b,xs[start],ys[r],xs[end],ys[r])
                start=end
            }
        }
        for(c in 0..cols) {
            checkpoint()
            fun edge(r: Int): Border? {
                val left=at(r,c-1); val right=at(r,c)
                if(left===right) return null
                return prefer(left?.borders?.get("lnR"),right?.borders?.get("lnL"),
                    anchor(left,r,c-1),anchor(right,r,c))
            }
            var start=0
            while(start<rows) {
                val b=edge(start)
                var end=start+1
                while(end<rows && edge(end)==b) end++
                line(b,xs[c],ys[start],xs[c],ys[end])
                start=end
            }
        }
        for(v in visuals) {
            checkpoint()
            val r=v.box
            line(v.borders["lnTlToBr"],r.left,r.top,r.right,r.bottom)
            line(v.borders["lnBlToTr"],r.left,r.bottom,r.right,r.top)
        }
    }
}
