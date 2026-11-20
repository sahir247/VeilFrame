package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Remove reachable standard PDF script actions and embedded-file references.
 * Does not claim to inspect JavaScript in opaque streams, arbitrary payloads,
 * or data left by incremental saves. XFA is rejected rather than silently lost.
 */
internal object PdfActiveContentSanitizer {
    private fun name(value: String) = COSName.getPDFName(value)

    private fun dereference(value: COSBase?): COSBase? {
        var current = value
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        while (current is COSObject) {
            if (!seen.add(current)) throw IOException("Cyclic indirect PDF reference")
            current = current.getObject()
        }
        return current
    }

    private fun forbiddenReference(value: COSBase?, javascript: Boolean, attachments: Boolean): Boolean {
        val dict = dereference(value) as? COSDictionary ?: return false
        return (javascript && dict.getDictionaryObject(name("S")) == name("JavaScript")) ||
            (attachments && (
                dict.getDictionaryObject(name("Subtype")) == name("FileAttachment") ||
                dict.getDictionaryObject(name("Type")) == name("EmbeddedFile") ||
                dict.getDictionaryObject(name("EF")) != null
            ))
    }

    fun remove(document: PDDocument, javascript: Boolean, attachments: Boolean) {
        visit(document, javascript, attachments, mutate = true)
        verify(document, javascript, attachments)
    }

    fun verify(document: PDDocument, javascript: Boolean, attachments: Boolean) {
        visit(document, javascript, attachments, mutate = false)
    }

    private fun visit(document: PDDocument, javascript: Boolean, attachments: Boolean, mutate: Boolean) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>()
        pending.add(document.documentCatalog.cosObject)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!seen.add(node)) continue
            when (node) {
                is COSObject -> node.getObject()?.let { pending.add(it) }
                is COSDictionary -> {
                    if (javascript && node.getItem(name("XFA")) != null) {
                        throw IOException("Cannot guarantee JavaScript removal from an XFA form")
                    }
                    for (key in node.keySet().toList()) {
                        val child = node.getItem(key)
                        val forbiddenKey = (javascript && key in listOf(name("JS"), name("JavaScript"))) ||
                            (attachments && key in listOf(name("EmbeddedFiles"), name("AF"), name("EF"), name("RF")))
                        if (forbiddenKey || forbiddenReference(child, javascript, attachments)) {
                            if (!mutate) throw IOException("Active content remains in PDF: ${key.name}")
                            node.removeItem(key)
                        } else {
                            child?.let { pending.add(it) }
                        }
                    }
                }
                is COSArray -> {
                    for (index in node.size() - 1 downTo 0) {
                        val child = node.get(index)
                        if (forbiddenReference(child, javascript, attachments)) {
                            if (!mutate) throw IOException("Active content remains in a PDF array")
                            node.remove(index)
                        } else {
                            child?.let { pending.add(it) }
                        }
                    }
                }
            }
        }
    }
}
