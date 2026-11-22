package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
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
    // Bound hostile / malformed reachable COS graphs before retaining unbounded
    // work queues. The limit is explicit: very large unsupported PDFs fail
    // closed instead of hanging or exhausting resources during sanitization.
    private const val MAX_REACHABLE_NODES = 250_000

    private fun name(value: String) = COSName.getPDFName(value)

    private fun dereference(value: COSBase?): COSBase? {
        var current = value
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        var depth = 0
        while (current is COSObject) {
            if (++depth > 1024) throw IOException("PDF indirect reference chain exceeds sanitization safety limit")
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

    fun remove(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        maxVisitedNodes: Int = MAX_REACHABLE_NODES
    ) {
        visit(document, javascript, attachments, mutate = true, maxVisitedNodes = maxVisitedNodes)
        verify(document, javascript, attachments, maxVisitedNodes)
    }

    fun verify(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        maxVisitedNodes: Int = MAX_REACHABLE_NODES
    ) {
        visit(document, javascript, attachments, mutate = false, maxVisitedNodes = maxVisitedNodes)
    }

    private fun visit(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        mutate: Boolean, maxVisitedNodes: Int
    ) {
        require(maxVisitedNodes in 1..MAX_REACHABLE_NODES) {
            "Invalid PDF sanitization traversal budget"
        }
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>()
        fun queue(node: COSBase) {
            if (pending.size >= maxVisitedNodes) {
                throw IOException("PDF active-content graph exceeds sanitization safety budget")
            }
            pending.add(node)
        }
        queue(document.documentCatalog.cosObject)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!seen.add(node)) continue
            if (seen.size > maxVisitedNodes) {
                throw IOException("PDF active-content graph exceeds sanitization safety budget")
            }
            when (node) {
                is COSObject -> node.getObject()?.let { queue(it) }
                is COSDictionary -> {
                    if (node.keySet().size > maxVisitedNodes) {
                        throw IOException("PDF active-content dictionary exceeds sanitization safety budget")
                    }
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
                        } else if (key == name("Names") && dereference(child) is COSArray) {
                            // Name-tree leaves are alternating key/value pairs. Removing
                            // only the forbidden value would corrupt every following pair.
                            val names = dereference(child) as COSArray
                            if (names.size() > maxVisitedNodes) {
                                throw IOException("PDF active-content name tree exceeds sanitization safety budget")
                            }
                            if (names.size() % 2 != 0) throw IOException("Malformed PDF name tree")
                            for (i in names.size() - 2 downTo 0 step 2) {
                                if (dereference(names.get(i)) !is COSString) throw IOException("Malformed PDF name-tree key")
                                if (forbiddenReference(names.get(i + 1), javascript, attachments)) {
                                    if (!mutate) throw IOException("Active content remains in PDF name tree")
                                    names.remove(i + 1)
                                    names.remove(i)
                                    // Limits may no longer match; they are optional for the root.
                                    // Keep the leaf's limits consistent with the surviving names.
                                    if (names.size() == 0) node.removeItem(name("Limits"))
                                    else if (node.containsKey(name("Limits"))) {
                                        node.setItem(name("Limits"), COSArray().apply {
                                            add(names.get(0)); add(names.get(names.size() - 2))
                                        })
                                    }
                                }
                            }
                            queue(names)
                        } else {
                            child?.let { queue(it) }
                        }
                    }
                }
                is COSArray -> {
                    if (node.size() > maxVisitedNodes) {
                        throw IOException("PDF active-content array exceeds sanitization safety budget")
                    }
                    for (index in node.size() - 1 downTo 0) {
                        val child = node.get(index)
                        if (forbiddenReference(child, javascript, attachments)) {
                            if (!mutate) throw IOException("Active content remains in a PDF array")
                            node.remove(index)
                        } else {
                            child?.let { queue(it) }
                        }
                    }
                }
            }
        }
    }
}
