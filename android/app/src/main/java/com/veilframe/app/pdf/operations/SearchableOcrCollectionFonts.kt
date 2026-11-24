package com.veilframe.app.pdf.operations

import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Static glyf-based faces only; own the shared TTC stream through PDF serialization. */
internal class SearchableOcrCollectionFonts(
    private val document: PDDocument,
    files: List<File>
) : AutoCloseable {
    private val candidates = files.distinctBy { it.absolutePath }.take(32)
    private val loaded = linkedMapOf<Pair<File,String>, PDType0Font>()
    private val unsupported = hashSetOf<File>()
    private val owners = ArrayList<TrueTypeCollection>()
    private var closed = false

    fun findLoaded(text: String): Pair<PDType0Font,Float>? {
        check(!closed)
        for (font in loaded.values) width(font,text)?.let { return it }
        return null
    }

    fun select(text: String): Pair<PDType0Font,Float>? {
        check(!closed)
        require(text.isNotBlank() && text.length <= 16384)
        findLoaded(text)?.let { return it }
        for (file in candidates) {
            if (loaded.size >= 12) break
            if (file in unsupported) continue
            if (attempt { admitted(file) } != true) {
                unsupported.add(file)
                continue
            }
            val collection = attempt { TrueTypeCollection(file) }
            if (collection == null) {
                unsupported.add(file)
                continue
            }
            var selected: Pair<PDType0Font,Float>? = null
            var retained = false
            var eligibleFace = false
            try {
                val scanned = attempt {
                    collection.processAllFonts { parsed ->
                        if (selected != null || loaded.size >= 12) return@processAllFonts
                        val tables = attempt { parsed.tableMap } ?: return@processAllFonts
                        if ("glyf" !in tables || "fvar" in tables || "CFF " in tables || "CFF2" in tables ||
                            attempt { parsed.os2Windows?.fsType?.toInt() } != 0) return@processAllFonts
                        val name = attempt { parsed.name } ?: return@processAllFonts
                        eligibleFace = true
                        val key = file to name
                        if (key in loaded) return@processAllFonts
                        val cmap = attempt { parsed.unicodeCmapLookup } ?: return@processAllFonts
                        if (attempt { text.codePoints().allMatch { cmap.getGlyphId(it) > 0 } } != true)
                            return@processAllFonts
                        val font = attempt { PDType0Font.load(document,parsed,true) } ?: return@processAllFonts
                        // TTCDataStream.close() deliberately leaves its owner open.
                        // PDFBox may read shared table data during subsetting at save.
                        if (!retained) {
                            owners.add(collection)
                            retained = true
                        }
                        loaded[key] = font
                        selected = width(font,text)
                    }
                    true
                }
                if (scanned == true && !eligibleFace && !retained) unsupported.add(file)
                selected?.let { return it }
            } finally {
                if (!retained) attempt { collection.close() }
            }
        }
        return null
    }

    private fun width(font: PDType0Font, text: String): Pair<PDType0Font,Float>? {
        val value = attempt { font.getStringWidth(text)/1000f } ?: return null
        return if (value.isFinite() && value > 0f) font to value else null
    }

    // Check collection header before FontBox allocates its offset array.
    private fun admitted(file: File): Boolean {
        if (!file.isFile || file.length() !in 16..67_108_864L) return false
        return RandomAccessFile(file,"r").use { input ->
            if (input.readInt() != 0x74746366) return@use false
            if (input.readInt() !in listOf(0x00010000,0x00020000)) return@use false
            val count = input.readInt()
            if (count !in 1..16 || 12L + count*4L > input.length()) return@use false
            val headerEnd = 12L + count*4L
            repeat(count) {
                val offset = input.readInt().toLong() and 0xffffffffL
                if (offset < headerEnd || offset > input.length()-12L) return@use false
            }
            true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owners.asReversed().forEach { attempt { it.close() } }
        owners.clear()
        loaded.clear()
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() }
        catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: SecurityException) { null }
}
