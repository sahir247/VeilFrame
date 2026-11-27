package com.veilframe.app.pdf.operations

import org.w3c.dom.Node
import java.io.IOException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Fixed gallery definitions, never used in preference to a saved local style.
 * Source and license are retained under resources/veilframe. */
internal object PptxBuiltInTableStyles {
    private val styles: Map<String,Node> by lazy {
        val stream=PptxBuiltInTableStyles::class.java.getResourceAsStream("/veilframe/builtin-table-styles.xml")
            ?: throw IOException("Bundled table style catalog missing")
        val factory=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        }
        val root=stream.use { input ->
            factory.newDocumentBuilder().apply {
                setEntityResolver { _,_ -> throw org.xml.sax.SAXException("External table style entity forbidden") }
            }.parse(input).documentElement
        }
        val entries=PptxTableStyle.children(root)
        require(entries.size==74 && entries.all { it.localName=="tblStyle" })
        entries.associateBy { requireNotNull(PptxTableStyle.attr(it,"styleId")).uppercase(Locale.ROOT) }
            .also { require(it.size==74) }
    }
    fun find(id: String): Node?=styles[id.uppercase(Locale.ROOT)]
    internal fun count(): Int=styles.size
}
