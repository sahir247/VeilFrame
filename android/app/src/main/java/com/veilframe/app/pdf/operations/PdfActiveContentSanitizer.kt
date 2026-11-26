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
        if (value !is COSObject) return value
        var current: COSBase? = value
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
        fun raw(key: String) = dereference(dict.getItem(name(key)))
        return (javascript && raw("S") == name("JavaScript")) ||
            (attachments && (
                raw("Subtype") == name("FileAttachment") ||
                raw("Type") == name("EmbeddedFile") ||
                dict.containsKey(name("EF")) || dict.containsKey(name("RF")) ||
                (raw("Type") == name("Filespec") && dict.containsKey(name("EP")))
            ))
    }

    fun remove(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        maxVisitedNodes: Int = MAX_REACHABLE_NODES,checkpoint: ()->Unit = {}
    ) {
        if (!javascript && !attachments) return
        visit(document, javascript, attachments, mutate = true, maxVisitedNodes = maxVisitedNodes,checkpoint=checkpoint)
        verify(document, javascript, attachments, maxVisitedNodes,checkpoint)
    }

    fun verify(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        maxVisitedNodes: Int = MAX_REACHABLE_NODES,checkpoint: ()->Unit = {}
    ) {
        if (!javascript && !attachments) return
        visit(document, javascript, attachments, mutate = false, maxVisitedNodes = maxVisitedNodes,checkpoint=checkpoint)
    }

    private fun visit(
        document: PDDocument, javascript: Boolean, attachments: Boolean,
        mutate: Boolean, maxVisitedNodes: Int,checkpoint: ()->Unit
    ) {
        require(maxVisitedNodes in 1..MAX_REACHABLE_NODES) {
            "Invalid PDF sanitization traversal budget"
        }
        val handledLeaves = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val seenTrees = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var treeWork = 0
        fun charge(amount: Int = 1) {
            checkpoint()
            if (amount < 0 || amount > maxVisitedNodes - treeWork) throw IOException("PDF name tree exceeds sanitization safety budget")
            treeWork += amount
        }
        data class Range(val first: COSString, val last: COSString)
        fun compareKeys(left: COSString, right: COSString): Int {
            val a = left.bytes
            val b = right.bytes
            for (i in 0 until minOf(a.size, b.size)) {
                val delta = (a[i].toInt() and 255) - (b[i].toInt() and 255)
                if (delta != 0) return delta
            }
            return a.size.compareTo(b.size)
        }
        fun setLimits(node: COSDictionary, range: Range?, root: Boolean) {
            if (!mutate) return
            if (range == null || root) node.removeItem(name("Limits"))
            else node.setItem(name("Limits"), COSArray().apply {
                add(range.first); add(range.last)
            })
        }
        // Catalog name-tree roots have a known structure; rebuild affected
        // ancestors and prune empty branches without touching unrelated values.
        fun repairTree(node: COSDictionary, root: Boolean, depth: Int): Pair<Range?, Boolean> {
            charge()
            if (depth > 128 || !seenTrees.add(node)) throw IOException("Cyclic, shared or excessively deep PDF name tree")
            val rawNames = node.getItem(name("Names"))
            val rawKids = node.getItem(name("Kids"))
            if ((rawNames == null) == (rawKids == null)) throw IOException("Malformed PDF name tree node")
            val survivors = COSArray()
            var range: Range? = null
            var changed = false
            if (rawNames != null) {
                handledLeaves.add(node)
                val pairs = dereference(rawNames) as? COSArray ?: throw IOException("Malformed PDF name tree leaf")
                charge(pairs.size())
                if (pairs.size() % 2 != 0) throw IOException("Malformed PDF name tree pairs")
                var previous: COSString? = null
                for (index in 0 until pairs.size() step 2) {
                    checkpoint()
                    val key = dereference(pairs.get(index)) as? COSString ?: throw IOException("Malformed PDF name tree key")
                    if (previous != null && compareKeys(previous, key) >= 0) throw IOException("Unordered or duplicate PDF name tree key")
                    previous = key
                    val value = pairs.get(index + 1)
                    if (forbiddenReference(value, javascript, attachments)) {
                        if (!mutate) throw IOException("Active content remains in PDF name tree")
                        changed = true
                        continue
                    }
                    survivors.add(pairs.get(index)); survivors.add(value)
                    range = Range(range?.first ?: key, key)
                }
                if (changed) node.setItem(name("Names"), survivors)
            } else {
                val kids = dereference(rawKids) as? COSArray ?: throw IOException("Malformed PDF name tree children")
                charge(kids.size())
                for (index in 0 until kids.size()) {
                    val child = dereference(kids.get(index)) as? COSDictionary ?: throw IOException("Malformed PDF name tree child")
                    val (childRange, childChanged) = repairTree(child, false, depth + 1)
                    changed = changed || childChanged
                    if (childRange == null) {
                        if (mutate) changed = true
                        else throw IOException("Empty non-root PDF name tree branch")
                        continue
                    }
                    if (range != null && compareKeys(range.last, childRange.first) >= 0) throw IOException("Overlapping PDF name tree branches")
                    range = Range(range?.first ?: childRange.first, childRange.last)
                    survivors.add(kids.get(index))
                }
                if (changed) {
                    if (survivors.size() == 0) {
                        node.removeItem(name("Kids"))
                        node.setItem(name("Names"), COSArray())
                        handledLeaves.add(node)
                    } else node.setItem(name("Kids"), survivors)
                }
            }
            if (changed) setLimits(node, range, root)
            return range to changed
        }
        val catalog = document.documentCatalog.cosObject
        val catalogNames = dereference(catalog.getItem(name("Names")))
        if (catalogNames is COSDictionary) {
            for (key in catalogNames.keySet().toList()) {
                charge()
                if ((javascript && key == name("JavaScript")) || (attachments && key == name("EmbeddedFiles"))) continue
                val tree = dereference(catalogNames.getItem(key)) as? COSDictionary
                    ?: throw IOException("Malformed catalog name tree")
                repairTree(tree, true, 0)
            }
        } else if (catalogNames != null) throw IOException("Malformed PDF catalog Names dictionary")
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
            checkpoint()
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
                    val subtype = dereference(node.getItem(name("Subtype")))
                    if ((javascript || attachments) && subtype in setOf(name("RichMedia"), name("3D"), name("Movie"), name("Sound"), name("Screen"))) {
                        throw IOException("Cannot safely sanitize opaque multimedia annotation payloads")
                    }
                    for (key in node.keySet().toList()) {
                        checkpoint()
                        val child = node.getItem(key)
                        val forbiddenKey = (javascript && key in listOf(name("JS"), name("JavaScript"))) ||
                            (attachments && (key in listOf(name("EmbeddedFiles"), name("AF"), name("EF"), name("RF")) ||
                                (key == name("EP") && dereference(node.getItem(name("Type"))) == name("Filespec"))))
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
                                    // Outside catalog name trees we cannot identify all
                                    // parents of a hierarchical leaf. Refuse to publish
                                    // rather than leave stale ancestor search limits.
                                    if (node !in handledLeaves && node.containsKey(name("Limits"))) {
                                        throw IOException("Unsupported non-catalog hierarchical name tree")
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
                        checkpoint()
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
