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
    private val namespaces=setOf("http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main")
    private fun children(node: Node): List<Node> {
        val out=mutableListOf<Node>()
        var child=node.firstChild
        while(child!=null) {
            if(child.nodeType==Node.ELEMENT_NODE && child.namespaceURI in namespaces) out.add(child)
            child=child.nextSibling
        }
        return out
    }
    private fun one(node: Node,name: String): Node? {
        val found=children(node).filter { it.localName==name }
        require(found.size<=1) { "Ambiguous slide table formatting" }
        return found.singleOrNull()
    }
    private fun attr(node: Node,name: String)=node.attributes?.getNamedItem(name)?.nodeValue
    private fun fill(node: Node,theme: (String)->Int): Int? {
        val fills=children(node).filter { it.localName in setOf("solidFill","noFill","gradFill","blipFill","pattFill","grpFill") }
        require(fills.size<=1) { "Ambiguous slide table fill" }
        val f=fills.singleOrNull() ?: return null
        if(f.localName=="noFill") return null
        if(f.localName!="solidFill") throw IOException("Unsupported non-solid slide table fill")
        val color=children(f).singleOrNull() ?: throw IOException("Missing slide table color")
        val value=attr(color,"val") ?: throw IOException("Missing slide table color value")
        val rgb=when(color.localName) {
            "srgbClr" -> if(value.matches(Regex("[0-9A-Fa-f]{6}"))) value.toInt(16) or 0xff000000.toInt()
                else throw IOException("Invalid slide table RGB")
            "schemeClr" -> theme(value)
            else -> throw IOException("Unsupported slide table color")
        }
        val effects=children(color)
        if(effects.any { it.localName!="alpha" } || effects.size>1)
            throw IOException("Unsupported slide table color transform")
        val alpha=effects.singleOrNull()?.let {
            val v=attr(it,"val")?.toIntOrNull() ?: throw IOException("Invalid slide table alpha")
            require(v in 0..100000)
            (v*255/100000)
        } ?: (rgb ushr 24)
        return (rgb and 0xffffff) or (alpha shl 24)
    }
    private data class Border(val color: Int?,val style: PptxStrokeStyle.Spec?)
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
        theme: (String)->Int,checkpoint: ()->Unit,text: (XSLFTableCell,RectF)->Unit) {
        val rows=table.numberOfRows
        val cols=table.numberOfColumns
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
            val color=properties?.let { fill(it,theme) }
            val borders=mutableMapOf<String,Border>()
            if(properties!=null) {
                for(edge in listOf("lnL","lnR","lnT","lnB","lnTlToBr","lnBlToTr")) {
                    val line=one(properties,edge) ?: continue
                    val lineColor=fill(line,theme)
                    if(one(line,"noFill")!=null) {
                        borders[edge]=Border(null,null)
                    } else if(lineColor!=null) {
                        val style=PptxStrokeStyle.parse(line,fontScale,fontScale)
                        borders[edge]=Border(lineColor,style)
                    }
                }
            }
            Visual(entry,cell,RectF(xs[entry.col],ys[entry.row],xs[entry.col+entry.cols],ys[entry.row+entry.rows]),color,borders)
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
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
                return sharedBorder(above?.borders?.get("lnB"),below?.borders?.get("lnT"),
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
                return sharedBorder(left?.borders?.get("lnR"),right?.borders?.get("lnL"),
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
