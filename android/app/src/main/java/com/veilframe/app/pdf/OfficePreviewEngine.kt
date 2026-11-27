package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.veilframe.app.pdf.operations.OfficeSourceFormat
import com.veilframe.app.pdf.operations.OpenXmlDocumentParser
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.io.File

/** UI-independent, bounded content inspection. This is not Office page-layout rendering. */
internal object OfficePreviewEngine {
    private const val TAG = "OfficePreviewEngine"
    data class Preview(val format: OfficeSourceFormat, val summary: String, val text: String, val bitmap: Bitmap?,
        val sourceIdentity: OfficeSourceSnapshot.Identity)

    /** Called on the IO dispatcher. Caller owns the bitmap only after return. */
    suspend fun inspect(context: Context, uri: Uri, selectedName: String): Preview {
        var snapshot: OfficeSourceSnapshot? = null
        var previewBitmap: Bitmap? = null
        var transferred = false
        try {
            val format = OfficeSourceFormat.fromName(selectedName)
            val staged = OfficeSourceSnapshot.open(context,uri,format)
            snapshot = staged
            val source = staged.file
            val paragraphs = mutableListOf<String>()
            val grid = mutableListOf<List<String>>()
            val slides = mutableListOf<String>()
            var tables = 0
            var summary = format.label
            val previewText = when (format) {
                OfficeSourceFormat.DOCX -> {
                    try {
                        source.inputStream().use { stream ->
                            XWPFDocument(stream).use { doc ->
                                tables = doc.tables.size
                                summary = "Word • ${doc.paragraphs.size} paragraphs, $tables tables"
                                for (paragraph in doc.paragraphs.asSequence().take(40)) {
                                    coroutineContext.ensureActive()
                                    paragraph.text?.trim()?.take(800)?.takeIf { it.isNotBlank() }?.let { paragraphs.add(it) }
                                }
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (unsupported: LinkageError) {
                        // Android may lack POI desktop rendering classes. The fallback
                        // parser is also used by the converter, not a binary text decoder.
                        val doc = OpenXmlDocumentParser.parseDocx(source)
                        require(doc.nodes.isNotEmpty()) { "Cannot inspect Word document" }
                        tables = doc.tables.size
                        summary = "Word • extracted content (fallback parser)"
                        paragraphs.addAll(doc.paragraphs.take(40).map { it.fullText.take(800) })
                    }
                    paragraphs.take(15).joinToString("\n\n")
                }
                OfficeSourceFormat.XLSX -> {
                    val workbook = OpenXmlDocumentParser.parseXlsx(source)
                    require(workbook.sheets.isNotEmpty()) { "Workbook has no sheets" }
                    val sheet = workbook.sheets.first()
                    summary = "Excel • ${workbook.sheets.size} sheets; first sheet: ${sheet.name.take(120)}"
                    for (row in sheet.rows.take(15)) {
                        coroutineContext.ensureActive()
                        // Retain empty cells; filtering them shifts later columns.
                        grid.add(row.take(6).map { it.take(120) })
                    }
                    grid.joinToString("\n") { it.joinToString(" | ", "[ ", " ]") }
                }
                OfficeSourceFormat.PPTX -> {
                    source.inputStream().use { stream ->
                        XMLSlideShow(stream).use { ppt ->
                            require(ppt.slides.isNotEmpty()) { "Presentation has no slides" }
                            summary = "PowerPoint • ${ppt.slides.size} slides"
                            for ((idx, slide) in ppt.slides.take(4).withIndex()) {
                                coroutineContext.ensureActive()
                                slides.add("Slide ${idx + 1}")
                                for (shape in slide.shapes.asSequence().filterIsInstance<XSLFTextShape>().take(12)) {
                                    coroutineContext.ensureActive()
                                    shape.text.trim().replace('\n', ' ').take(300).takeIf { it.isNotBlank() }?.let { slides.add(it) }
                                }
                            }
                            slides.joinToString("\n")
                        }
                    }
                }
                OfficeSourceFormat.TEXT -> {
                    // Bounded sample, not readLine() over an arbitrarily long line.
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    java.io.InputStreamReader(source.inputStream(), decoder).use { reader ->
                        val chars = CharArray(8192)
                        val size = reader.read(chars)
                        if (size <= 0) throw java.io.IOException("Empty text document")
                        val sample = String(chars, 0, size)
                        require(!sample.contains('\u0000')) { "Not a plain text document" }
                        paragraphs.addAll(sample.lines().take(40).map { it.take(800) })
                        summary = "Plain text • first characters"
                        paragraphs.take(20).joinToString("\n")
                    }
                }
            }
            coroutineContext.ensureActive()
            previewBitmap = renderOfficePreviewBitmap(
                format.name, selectedName.take(120), paragraphs, tables,
                if (format == OfficeSourceFormat.XLSX) grid else null,
                if (format == OfficeSourceFormat.PPTX) slides else null
            )

            coroutineContext.ensureActive()
            return Preview(format,summary,previewText,previewBitmap,staged.identity).also { transferred = true }
        } finally {
            if (!transferred) previewBitmap?.recycle()
            snapshot?.close()
        }
    }

    private fun renderOfficePreviewBitmap(
        docType: String,
        docTitle: String,
        paragraphs: List<String>,
        tablesCount: Int,
        sheetGrid: List<List<String>>?,
        slideElements: List<String>?
    ): Bitmap? {
        var allocated: Bitmap? = null
        return try {
            val width = 720
            val height = if (docType == "PPTX") 480 else 1018
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            allocated = bitmap
            val canvas = Canvas(bitmap)

            // Clean white paper
            canvas.drawColor(Color.WHITE)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E5E7EB")
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)

            val marginX = 48f
            val marginY = 48f
            val printableWidth = (width - marginX * 2).toInt()
            var currentY = marginY

            // 1. Doc Type Badge (Top Right)
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (docType) {
                    "DOCX" -> Color.parseColor("#EFF6FF")
                    "XLSX" -> Color.parseColor("#ECFDF5")
                    "PPTX" -> Color.parseColor("#FFF7ED")
                    else -> Color.parseColor("#F3F4F6")
                }
                style = Paint.Style.FILL
            }
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (docType) {
                    "DOCX" -> Color.parseColor("#1D4ED8")
                    "XLSX" -> Color.parseColor("#047857")
                    "PPTX" -> Color.parseColor("#C2410C")
                    else -> Color.parseColor("#4B5563")
                }
                textSize = 22f
                isFakeBoldText = true
            }

            val badgeRect = RectF(width - marginX - 90f, marginY - 10f, width - marginX, marginY + 26f)
            canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBgPaint)
            val badgeTextWidth = badgeTextPaint.measureText(docType)
            canvas.drawText(docType, badgeRect.centerX() - badgeTextWidth / 2f, badgeRect.centerY() + 8f, badgeTextPaint)

            // 2. Document Title
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827")
                textSize = 28f
                isFakeBoldText = true
            }
            val cleanTitle = if (docTitle.length > 34) docTitle.take(32) + "…" else docTitle
            canvas.drawText(cleanTitle, marginX, currentY + 18f, titlePaint)
            currentY += 44f

            // Divider
            val dividerPaint = Paint().apply {
                color = Color.parseColor("#F3F4F6")
                strokeWidth = 2f
            }
            canvas.drawLine(marginX, currentY, width - marginX, currentY, dividerPaint)
            currentY += 24f

            // 3. Body Content
            when (docType) {
                "XLSX" -> {
                    val grid = sheetGrid ?: emptyList()
                    val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#1F2937")
                        textSize = 18f
                    }
                    val headerCellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#374151")
                        textSize = 18f
                        isFakeBoldText = true
                    }
                    val colWidth = printableWidth / 4f
                    val rowHeight = 36f

                    val colHeaders = listOf("A", "B", "C", "D")
                    for (c in 0 until 4) {
                        val x = marginX + c * colWidth
                        val headerRect = RectF(x, currentY, x + colWidth, currentY + rowHeight)
                        canvas.drawRect(headerRect, Paint().apply { color = Color.parseColor("#F9FAFB") })
                        canvas.drawRect(headerRect, borderPaint)
                        canvas.drawText(colHeaders[c], x + colWidth / 2f - 6f, currentY + 24f, headerCellPaint)
                    }
                    currentY += rowHeight

                    for (r in 0 until minOf(grid.size, 18)) {
                        val row = grid[r]
                        for (c in 0 until 4) {
                            val x = marginX + c * colWidth
                            val cellRect = RectF(x, currentY, x + colWidth, currentY + rowHeight)
                            canvas.drawRect(cellRect, borderPaint)
                            val text = if (c < row.size) row[c] else ""
                            val truncatedText = if (text.length > 12) text.take(11) + "…" else text
                            if (truncatedText.isNotEmpty()) {
                                canvas.drawText(truncatedText, x + 8f, currentY + 24f, cellPaint)
                            }
                        }
                        currentY += rowHeight
                        if (currentY > height - marginY - 40f) break
                    }
                }
                "PPTX" -> {
                    val slideTexts = slideElements ?: emptyList()
                    val slideTitle = slideTexts.firstOrNull() ?: "Presentation Slide"
                    val slideTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#0F172A")
                        textSize = 32f
                        isFakeBoldText = true
                    }
                    canvas.drawText(slideTitle.take(30), marginX + 16f, currentY + 36f, slideTitlePaint)
                    currentY += 60f

                    val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#2563EB")
                        style = Paint.Style.FILL
                    }
                    val bulletTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#334155")
                        textSize = 20f
                    }

                    for (bullet in slideTexts.drop(1).take(5)) {
                        canvas.drawCircle(marginX + 20f, currentY + 12f, 4f, bulletPaint)
                        val text = if (bullet.length > 55) bullet.take(53) + "…" else bullet
                        canvas.drawText(text, marginX + 36f, currentY + 18f, bulletTextPaint)
                        currentY += 36f
                    }
                }
                else -> {
                    val bodyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#374151")
                        textSize = 20f
                        letterSpacing = 0.01f
                    }
                    val headingTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#111827")
                        textSize = 24f
                        isFakeBoldText = true
                    }

                    val maxBodyY = height - marginY - 50f
                    for ((idx, para) in paragraphs.withIndex()) {
                        if (currentY >= maxBodyY) break
                        val isHeading = idx == 0 || (para.length in 4..45 && !para.endsWith(".") && !para.endsWith(","))
                        val paint = if (isHeading) headingTextPaint else bodyTextPaint

                        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            StaticLayout.Builder.obtain(para, 0, para.length, paint, printableWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(4f, 1.15f)
                                .setIncludePad(false)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(para, paint, printableWidth, Layout.Alignment.ALIGN_NORMAL, 1.15f, 4f, false)
                        }

                        if (currentY + layout.height > maxBodyY) {
                            canvas.save()
                            canvas.clipRect(marginX, currentY, marginX + printableWidth, maxBodyY)
                            canvas.translate(marginX, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY = maxBodyY + 10f
                            break
                        } else {
                            canvas.save()
                            canvas.translate(marginX, currentY)
                            layout.draw(canvas)
                            canvas.restore()
                            currentY += layout.height + if (isHeading) 16f else 12f
                        }
                    }

                    if (tablesCount > 0 && currentY < maxBodyY - 60f) {
                        val tableY = currentY + 8f
                        val tableW = printableWidth.toFloat()
                        val tableH = minOf(90f, maxBodyY - tableY)
                        val tableRect = RectF(marginX, tableY, marginX + tableW, tableY + tableH)

                        val tableFill = Paint().apply { color = Color.parseColor("#F9FAFB") }
                        canvas.drawRect(tableRect, tableFill)
                        canvas.drawRect(tableRect, borderPaint)

                        canvas.drawLine(marginX, tableY + 30f, marginX + tableW, tableY + 30f, borderPaint)
                        canvas.drawLine(marginX + tableW / 2f, tableY, marginX + tableW / 2f, tableY + tableH, borderPaint)

                        val tableLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#6B7280")
                            textSize = 16f
                            isFakeBoldText = true
                        }
                        canvas.drawText("Table 1: Data Grid", marginX + 12f, tableY + 20f, tableLabelPaint)
                    }
                }
            }

            // 4. Subtle Page 1 Footer
            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9CA3AF")
                textSize = 16f
            }
            val footerText = "— Schematic content sample —"
            val footerWidth = footerPaint.measureText(footerText)
            canvas.drawText(footerText, (width - footerWidth) / 2f, height - marginY / 2f, footerPaint)

            bitmap
        } catch (t: Throwable) {
            allocated?.recycle()
            if (t !is Exception) throw t
            Log.e(TAG, "renderOfficePreviewBitmap failed: ${t.message}", t)
            null
        }
    }

}
