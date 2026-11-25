package com.veilframe.app.pdf.operations

import android.content.Context
import android.os.Build
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.IOException

/**
 * Invisible OCR text must round-trip exact logical Unicode; no missing-glyph
 * substitution. Bundled static OFL TrueType fonts are the deterministic path.
 * Additional compatible system TrueType fonts may extend device coverage.
 */
internal class SearchableOcrFonts(private val context: Context, private val document: PDDocument) : AutoCloseable {
    private val candidates = listOf<() -> PDType0Font>(
        { context.assets.open("fonts/Helvetica-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.assets.open("fonts/ocr/NotoSans-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.assets.open("fonts/ocr/NotoSansDevanagari-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.assets.open("fonts/ocr/NotoSansBengali-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.assets.open("fonts/ocr/NotoSansArabic-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.assets.open("fonts/ocr/NotoSansHebrew-Regular.ttf").use { PDType0Font.load(document,it) } },
        { context.resources.openRawResource(com.veilframe.app.R.font.font_sans).use { PDType0Font.load(document,it) } },
        { context.resources.openRawResource(com.veilframe.app.R.font.font_serif).use { PDType0Font.load(document,it) } },
        { context.resources.openRawResource(com.veilframe.app.R.font.font_mono).use { PDType0Font.load(document,it) } }
    ).map { loader -> lazy { attempt(loader) } }

    // Only static glyf-based system fonts with explicit unrestricted embedding
    // permissions. CFF and variable fonts remain unsupported.
    private val installedFontFiles by lazy {
        val files = if (Build.VERSION.SDK_INT >= 29) {
            attempt { android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { it.file } }.orEmpty()
        } else File("/system/fonts").listFiles()?.toList().orEmpty()
        files.filter { it.isFile &&
            (it.name.startsWith("NotoSans") || it.name.startsWith("DroidSansFallback")) &&
            !it.name.contains("Bold",true) && !it.name.contains("Italic",true) }
            .distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
    }
    private val systemFiles by lazy {
        installedFontFiles.filter { it.extension.equals("ttf",true) && it.length() in 1..16_777_216L }.take(128)
    }
    private var collectionFallback: SearchableOcrCollectionFonts? = null
    private val loaded = linkedMapOf<File,PDType0Font>()
    private val rejected = hashSetOf<File>()
    private val owned = ArrayList<TrueTypeFont>()
    private var closed = false

    fun select(text: String): Pair<PDType0Font,Float> {
        check(!closed)
        require(text.isNotBlank() && text.length <= 16384) { "Invalid OCR word length" }
        fun width(font: PDType0Font): Pair<PDType0Font,Float>? {
            val value = attempt { font.getStringWidth(text)/1000f } ?: return null
            return if (value.isFinite() && value > 0f) font to value else null
        }
        for (candidate in candidates) candidate.value?.let { width(it)?.let { match -> return match } }
        for (font in loaded.values) width(font)?.let { return it }
        collectionFallback?.findLoaded(text)?.let { return it }
        // Inspect cmap before embedding. Keep only fonts actually used, with
        // a bounded count. Do not register every installed font in the PDF.
        for (file in systemFiles) {
            if (loaded.size >= 12) break
            if (file in loaded || file in rejected) continue
            val parsed = attempt { TTFParser(false,true).parse(file) }
            if (parsed == null) { rejected.add(file); continue }
            var retained = false
            try {
                val tables = parsed.tableMap
                if ("glyf" !in tables || "fvar" in tables || "CFF " in tables ||
                    parsed.getOS2Windows()?.getFsType()?.toInt() != 0) {
                    rejected.add(file)
                    continue
                }
                val cmap = attempt { parsed.unicodeCmapLookup } ?: continue
                if (!text.codePoints().allMatch { cmap.getGlyphId(it) > 0 }) continue
                val font = attempt { PDType0Font.load(document,parsed,true) }
                if (font == null) { rejected.add(file); continue }
                owned.add(parsed)
                retained = true
                loaded[file] = font
                width(font)?.let { return it }
            } finally {
                if (!retained) attempt { parsed.close() }
            }
        }
        val collections = collectionFallback ?: SearchableOcrCollectionFonts(document,
            installedFontFiles.filter { it.extension.equals("ttc",true) }.take(32)).also { collectionFallback = it }
        collections.select(text)?.let { return it }
        throw IOException("Searchable PDF has no compatible embeddable font for all recognized characters; export refused")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            collectionFallback?.close()
            collectionFallback = null
        } finally {
            owned.asReversed().forEach { attempt { it.close() } }
            owned.clear()
        }
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() }
        catch (failure: IOException) { null }
        catch (failure: IllegalArgumentException) { null }
        catch (failure: SecurityException) { null }
}
