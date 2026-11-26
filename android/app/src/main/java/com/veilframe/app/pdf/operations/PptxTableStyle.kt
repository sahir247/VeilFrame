package com.veilframe.app.pdf.operations

import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.roundToInt

/** DrawingML table styles are a property cascade, not a single winning region.
 * MS-OI29500 2.1.1265 specifies the region application order.
 */
internal object PptxTableStyle {
    private val ns=setOf("http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main")
    internal fun children(node: Node): List<Node> {
        val out=mutableListOf<Node>()
        var c=node.firstChild
        while(c!=null) {
            if(c.nodeType==Node.ELEMENT_NODE && c.namespaceURI in ns) {
                require(out.size<4096) { "Table style node budget exceeded" }
                out.add(c)
            }
            c=c.nextSibling
        }
        return out
    }
    internal fun one(n: Node?,name: String): Node? {
        val matches=n?.let { children(it).filter { child -> child.localName==name } }.orEmpty()
        require(matches.size<=1) { "Ambiguous table style property: "+name }
        return matches.singleOrNull()
    }
    internal fun attr(n: Node?,name: String)=n?.attributes?.getNamedItem(name)?.nodeValue
    private fun value(n: Node): String {
        val s=StringBuilder()
        var c=n.firstChild
        while(c!=null) {
            if(c.nodeType==Node.TEXT_NODE || c.nodeType==Node.CDATA_SECTION_NODE) s.append(c.nodeValue)
            else if(c.nodeType==Node.ELEMENT_NODE) throw IOException("Invalid table style identifier")
            require(s.length<=128)
            c=c.nextSibling
        }
        return s.toString().trim()
    }
    data class Flags(val firstRow: Boolean=false,val lastRow: Boolean=false,
        val firstCol: Boolean=false,val lastCol: Boolean=false,
        val bandRow: Boolean=false,val bandCol: Boolean=false)
    fun flags(props: Node?): Flags {
        fun flag(name: String)=when(attr(props,name)) {
            null,"0","false" -> false
            "1","true" -> true
            else -> throw IOException("Invalid table style toggle: "+name)
        }
        if(flag("rtl")) throw IOException("Right-to-left table style geometry unsupported")
        return Flags(flag("firstRow"),flag("lastRow"),flag("firstCol"),flag("lastCol"),flag("bandRow"),flag("bandCol"))
    }
    internal fun regions(row: Int,col: Int,rows: Int,cols: Int,f: Flags): List<String> {
        require(row in 0 until rows && col in 0 until cols)
        val top=f.firstRow && row==0; val bottom=f.lastRow && row==rows-1
        val left=f.firstCol && col==0; val right=f.lastCol && col==cols-1
        return buildList {
            add("wholeTbl")
            if(f.bandRow && !top && !bottom) add(if((row-(if(f.firstRow) 1 else 0))%2==0) "band1H" else "band2H")
            if(f.bandCol && !left && !right) add(if((col-(if(f.firstCol) 1 else 0))%2==0) "band1V" else "band2V")
            if(right) add("lastCol")
            if(left) add("firstCol")
            if(bottom) add("lastRow")
            if(bottom && right) add("seCell")
            if(bottom && left) add("swCell")
            if(top) add("firstRow")
            if(top && right) add("neCell")
            if(top && left) add("nwCell")
        }
    }

    fun select(props: Node?,styleList: Node?): Node? {
        val inline=one(props,"tableStyle")
        val ref=one(props,"tableStyleId")
        require(inline==null || ref==null) { "Ambiguous table style identity" }
        if(inline!=null) return inline
        val id=ref?.let(::value) ?: attr(styleList,"def") ?: return null
        require(id.matches(Regex("\\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\\}"))) { "Invalid table style ID" }
        val styles=styleList?.let { children(it).filter { child -> child.localName=="tblStyle" &&
            attr(child,"styleId").equals(id,ignoreCase=true) } }.orEmpty()
        if(styles.size!=1) throw IOException("Missing or ambiguous PowerPoint table style: "+id)
        return styles.single()
    }

    data class Region(val name: String,val cell: Node?)
    fun layers(style: Node?,row: Int,col: Int,rows: Int,cols: Int,f: Flags): List<Region> =
        regions(row,col,rows,cols,f).mapNotNull { name ->
            one(style,name)?.let { Region(name,one(it,"tcStyle")) }
        }

    private val fillNames=setOf("solidFill","noFill","gradFill","pattFill","blipFill","grpFill")
    fun directFill(n: Node?): Node? {
        val fills=n?.let { children(it).filter { child -> child.localName in fillNames } }.orEmpty()
        require(fills.size<=1) { "Ambiguous table style fill" }
        return fills.singleOrNull()
    }
    private fun colorNode(n: Node,theme: (String)->Int?,placeholder: Int?=null): Int {
        val raw=attr(n,"val") ?: throw IOException("Missing table style color")
        val base=when(n.localName) {
            "srgbClr" -> if(raw.matches(Regex("[0-9A-Fa-f]{6}"))) raw.toInt(16) or 0xff000000.toInt()
                else throw IOException("Invalid table style RGB")
            "schemeClr" -> (if(raw=="phClr") placeholder else theme(raw))
                ?: throw IOException("Unresolved table theme color: "+raw)
            else -> throw IOException("Unsupported table style color: "+n.localName)
        }
        var alpha=(base ushr 24)/255.0
        var rgb=doubleArrayOf(((base ushr 16) and 255)/255.0,((base ushr 8) and 255)/255.0,(base and 255)/255.0)
        for(effect in children(n)) {
            val v=attr(effect,"val") ?: throw IOException("Missing table color transform value")
            val fraction=if(v.endsWith("%")) v.dropLast(1).toDoubleOrNull()?.div(100)
                else v.toLongOrNull()?.toDouble()?.div(100000)
            require(fraction!=null && fraction.isFinite() && fraction in -1.0..10.0) { "Invalid table color transform" }
            when(effect.localName) {
                "alpha" -> alpha=fraction
                "alphaMod" -> alpha*=fraction
                "alphaOff" -> alpha+=fraction
                "tint" -> { require(fraction in 0.0..1.0); rgb=rgb.map { 1-(1-it)*fraction }.toDoubleArray() }
                "shade" -> { require(fraction in 0.0..1.0); rgb=rgb.map { it*fraction }.toDoubleArray() }
                else -> throw IOException("Unsupported table color transform: "+effect.localName)
            }
            alpha=alpha.coerceIn(0.0,1.0)
        }
        fun byte(x: Double)=(x.coerceIn(0.0,1.0)*255).roundToInt()
        return (byte(alpha) shl 24) or (byte(rgb[0]) shl 16) or (byte(rgb[1]) shl 8) or byte(rgb[2])
    }
    fun solid(fill: Node,theme: (String)->Int?,placeholder: Int?=null): Int? {
        if(fill.localName=="noFill") return null
        if(fill.localName!="solidFill") throw IOException("Unsupported non-solid table style fill")
        val color=children(fill).singleOrNull() ?: throw IOException("Missing table solid color")
        return colorNode(color,theme,placeholder)
    }
    data class Fill(val node: Node,val placeholder: Int?=null)
    private fun reference(ref: Node,lookup: (Int)->Node?,theme: (String)->Int?): Fill {
        val index=attr(ref,"idx")?.toIntOrNull() ?: throw IOException("Invalid table theme style index")
        require(index in 0..9999)
        val node=lookup(index) ?: throw IOException("Unresolved table theme style index: "+index)
        val ph=children(ref).singleOrNull()?.let { colorNode(it,theme) }
        return Fill(node,ph)
    }
    fun inheritedFill(n: Node?,lookup: (Int)->Node?,theme: (String)->Int?): Fill? {
        val local=one(n,"fill")
        val ref=one(n,"fillRef")
        require(local==null || ref==null) { "Ambiguous inherited table fill" }
        if(local!=null) return directFill(local)?.let { Fill(it) }
            ?: throw IOException("Empty inherited table fill")
        return ref?.let { reference(it,lookup,theme) }
    }
    data class Border(val node: Node,val placeholder: Int?=null)
    fun inheritedBorder(wrapper: Node,lookup: (Int)->Node?,theme: (String)->Int?): Border? {
        val line=one(wrapper,"ln")
        val ref=one(wrapper,"lnRef")
        require(line==null || ref==null) { "Ambiguous inherited table border" }
        if(line!=null) return Border(line)
        return ref?.let { reference(it,lookup,theme) }?.let { Border(it.node,it.placeholder) }
    }

    /** Side roles depend on the region's perimeter, not the individual cell.
     * For banding, each stripe is one row/column thick.
     */
    fun borderRole(region: String,edge: String,row: Int,col: Int,rows: Int,cols: Int,
        f: Flags): String {
        val r0=when(region) { "firstRow","neCell","nwCell" -> 0
            "lastRow","seCell","swCell" -> rows-1
            "band1H","band2H" -> row
            else -> 0 }
        val r1=when(region) { "firstRow","neCell","nwCell" -> 0
            "lastRow","seCell","swCell" -> rows-1
            "band1H","band2H" -> row
            else -> rows-1 }
        val c0=when(region) { "firstCol","nwCell","swCell" -> 0
            "lastCol","neCell","seCell" -> cols-1
            "band1V","band2V" -> col
            else -> 0 }
        val c1=when(region) { "firstCol","nwCell","swCell" -> 0
            "lastCol","neCell","seCell" -> cols-1
            "band1V","band2V" -> col
            else -> cols-1 }
        return when(edge) {
            "lnL" -> if(col==c0) "left" else "insideV"
            "lnR" -> if(col==c1) "right" else "insideV"
            "lnT" -> if(row==r0) "top" else "insideH"
            "lnB" -> if(row==r1) "bottom" else "insideH"
            "lnTlToBr" -> "tl2br"
            "lnBlToTr" -> "tr2bl"
            else -> throw IOException("Unknown table border side")
        }
    }
}
