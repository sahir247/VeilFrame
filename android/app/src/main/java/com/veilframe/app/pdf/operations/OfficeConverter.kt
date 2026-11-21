package com.veilframe.app.pdf.operations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTOnOff
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XSLFSimpleShape
import org.apache.poi.xslf.usermodel.XSLFPictureData
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.sl.usermodel.Shape
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Offline-first office document format (Word, Excel) to PDF conversion engine.
 * Synthesizes formatted text blocks and landscape cell tables into standard paginated PDFs.
 * Runs fully on Dispatchers.IO background contexts with aggressive resource management.
 */
class OfficeConverter {

    var maxPdfPageHeightOverride: Float? = null
    var maxPdfPageWidthOverride: Float? = null
    var forceExportStrategy: ExportStrategy? = null
    var repeatFrozenColumnsInTiling: Boolean = false

    /** Stage, reopen and verify page count before atomically replacing the caller's destination. */
    internal suspend fun publishOfficePdf(pdf: PDDocument, destination: File) {
        val expected = pdf.numberOfPages
        require(expected > 0) { "Conversion produced no pages" }
        val parent = destination.absoluteFile.parentFile ?: error("No destination directory")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create destination directory" }
        val staged = File.createTempFile("office_export_", ".pdf", parent)
        try {
            currentCoroutineContext().ensureActive()
            pdf.save(staged)
            PDDocument.load(staged).use { reopened ->
                check(reopened.numberOfPages == expected) { "Serialized Office PDF page count changed" }
            }
            currentCoroutineContext().ensureActive()
            java.nio.file.Files.move(
                staged.toPath(), destination.absoluteFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            staged.delete()
        }
    }

    private data class DocxStyledSegment(val text: String, val font: PDType1Font, val width: Float)
    private data class DocxStyledLine(val segments: List<DocxStyledSegment>, val totalWidth: Float)

    private fun wrapStyledDocxRuns(
        runs: List<OpenXmlDocumentParser.DocxRun>,
        isHeading: Boolean,
        fontSize: Float,
        maxWidth: Float,
        fontNormal: PDType1Font,
        fontBold: PDType1Font
    ): List<DocxStyledLine> {
        val lines = mutableListOf<DocxStyledLine>()
        val curSegments = mutableListOf<DocxStyledSegment>()
        var curLineWidth = 0f

        for (run in runs) {
            val font = if (run.isBold || isHeading) fontBold else fontNormal
            val clean = sanitizeText(run.text)
            if (clean.isEmpty()) continue

            val words = clean.split(Regex("(?<=\\s)|(?=\\s+)"))
            for (w in words) {
                if (w.isEmpty()) continue
                val wordWidth = try { font.getStringWidth(w) / 1000f * fontSize } catch (_: Exception) { w.length * fontSize * 0.5f }
                if (curLineWidth + wordWidth > maxWidth && curSegments.isNotEmpty() && w.isNotBlank()) {
                    lines.add(DocxStyledLine(curSegments.toList(), curLineWidth))
                    curSegments.clear()
                    curLineWidth = 0f
                }
                if (curSegments.isNotEmpty() && curSegments.last().font == font) {
                    val prev = curSegments.removeAt(curSegments.size - 1)
                    curSegments.add(DocxStyledSegment(prev.text + w, font, prev.width + wordWidth))
                } else {
                    curSegments.add(DocxStyledSegment(w, font, wordWidth))
                }
                curLineWidth += wordWidth
            }
        }
        if (curSegments.isNotEmpty()) {
            lines.add(DocxStyledLine(curSegments.toList(), curLineWidth))
        }
        return lines
    }


    /**
     * Resilient offline text and structure extractor from .docx (OpenXML ZIP container).
     * Bypasses POI/AWT dependencies completely, ensuring zero crashes on Android runtime.
     */
    fun extractDocxTextViaZip(docxFile: File): String {
        val sb = StringBuilder()
        try {
            java.util.zip.ZipInputStream(FileInputStream(docxFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xmlContent = zis.bufferedReader(Charsets.UTF_8).readText()
                        val pRegex = Regex("<w:p[ >](.*?)</w:p>")
                        val tRegex = Regex("<w:t[^>]*>(.*?)</w:t>")
                        val matches = pRegex.findAll(xmlContent)
                        for (pMatch in matches) {
                            val pXml = pMatch.value
                            val tMatches = tRegex.findAll(pXml)
                            val pText = tMatches.map { it.groupValues[1] }
                                .joinToString("")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&apos;", "'")
                            if (pText.isNotBlank()) {
                                sb.append(pText).append("\n\n")
                            }
                        }
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Throwable) { }
        return sb.toString()
    }

    /**
     * Converts a DOCX Word file paragraph-by-paragraph to an A4 PDF with dynamic line-wrapping and pagination.
     */
    suspend fun convertDocxToPdf(docxFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        try {
            convertDocxViaOpenXml(docxFile, pdfFile, context)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (primary: Exception) {
            // Each renderer publishes only its verified staged PDF. Keep any prior destination intact.
            android.util.Log.w("OfficeConverter", "OpenXML DOCX conversion failed; trying POI", primary)
            try {
                convertDocxToPdfInternal(docxFile, pdfFile, context)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (secondary: Exception) {
                secondary.addSuppressed(primary)
                throw java.io.IOException("DOCX conversion failed; text-only export would lose formatting.", secondary)
            } catch (unsupported: LinkageError) {
                unsupported.addSuppressed(primary)
                throw java.io.IOException("DOCX fallback renderer unavailable; no faithful PDF was produced.", unsupported)
            }
        }
    }

    /**
     * Converts a DOCX file with rich formatting (headings, paragraph spacing, bullets, alignment, tables)
     * using the pure-Kotlin OpenXML parser, guaranteeing zero POI/AWT crashes on Android.
     */
    suspend fun convertDocxViaOpenXml(docxFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val doc = OpenXmlDocumentParser.parseDocx(docxFile)
        if (doc.nodes.isEmpty()) {
            throw IllegalStateException("Empty or unreadable DOCX document")
        }

        val pdf = PDDocument()
        var contentStream: PDPageContentStream? = null
        try {

        val pageBounds = PDRectangle.A4
        val margin = 50f
        val printableWidth = pageBounds.width - (2 * margin)

        val fontNormal = PDType1Font.HELVETICA
        val fontBold = PDType1Font.HELVETICA_BOLD

        var currentPage = PDPage(pageBounds)
        pdf.addPage(currentPage)
        contentStream = PDPageContentStream(pdf, currentPage)

        var yPosition = pageBounds.height - margin

        try {
            for (node in doc.nodes) {
                currentCoroutineContext().ensureActive()
                when (node) {
                    is OpenXmlDocumentParser.DocxNode.ParagraphNode -> {
                        val p = node.paragraph
                        val isHeading = p.isHeading
                        val fontSize = when (p.headingLevel) {
                            1 -> 18f
                            2 -> 15f
                            3 -> 13f
                            else -> 10.5f
                        }
                        val leading = fontSize * 1.35f

                        // Paragraph spacing before
                        yPosition -= p.spaceBeforePt

                        val effectiveRuns = if (p.isBullet) {
                            listOf(OpenXmlDocumentParser.DocxRun(text = "•  ", isBold = false)) + p.runs
                        } else {
                            p.runs
                        }

                        if (effectiveRuns.any { it.text.isNotBlank() }) {
                            val styledLines = wrapStyledDocxRuns(
                                runs = effectiveRuns,
                                isHeading = isHeading,
                                fontSize = fontSize,
                                maxWidth = printableWidth,
                                fontNormal = fontNormal,
                                fontBold = fontBold
                            )

                            for (line in styledLines) {
                                if (yPosition - leading < margin) {
                                    contentStream?.close()
                                    currentPage = PDPage(pageBounds)
                                    pdf.addPage(currentPage)
                                    contentStream = PDPageContentStream(pdf, currentPage)
                                    yPosition = pageBounds.height - margin
                                }

                                val xOffset = when (p.alignment) {
                                    "center" -> margin + ((printableWidth - line.totalWidth) / 2f).coerceAtLeast(0f)
                                    "right" -> margin + (printableWidth - line.totalWidth).coerceAtLeast(0f)
                                    else -> margin
                                }

                                var segX = xOffset
                                for (seg in line.segments) {
                                    contentStream?.beginText()
                                    contentStream?.setFont(seg.font, fontSize)
                                    contentStream?.newLineAtOffset(segX, yPosition)
                                    safeShowText(contentStream, seg.text, seg.font)
                                    contentStream?.endText()
                                    segX += seg.width
                                }

                                yPosition -= leading
                            }
                        }

                        // Paragraph spacing after
                        yPosition -= p.spaceAfterPt
                    }
                    is OpenXmlDocumentParser.DocxNode.TableNode -> {
                        val table = node.table
                        val rows = table.rows
                        if (rows.isEmpty()) continue

                        val numCols = rows.maxOfOrNull { it.size } ?: 1
                        val colWidth = printableWidth / numCols
                        yPosition -= 8f

                        for (row in rows) {
                            val rowLines = Array(numCols) { c ->
                                val cellText = row.getOrNull(c) ?: ""
                                if (cellText.isNotBlank()) {
                                    wrapText(sanitizeText(cellText), fontNormal, 9f, colWidth - 8f)
                                } else {
                                    emptyList()
                                }
                            }
                            val maxLines = rowLines.maxOfOrNull { it.size } ?: 1
                            val dynamicRowHeight = Math.max(20f, maxLines * 12f + 8f)

                            if (yPosition - dynamicRowHeight < margin) {
                                contentStream?.close()
                                currentPage = PDPage(pageBounds)
                                pdf.addPage(currentPage)
                                contentStream = PDPageContentStream(pdf, currentPage)
                                yPosition = pageBounds.height - margin
                            }

                            for (c in 0 until numCols) {
                                val cellX = margin + (c * colWidth)

                                // Cell grid border
                                contentStream?.setStrokingColor(200, 200, 200)
                                contentStream?.setLineWidth(0.5f)
                                contentStream?.moveTo(cellX, yPosition)
                                contentStream?.lineTo(cellX + colWidth, yPosition)
                                contentStream?.lineTo(cellX + colWidth, yPosition - dynamicRowHeight)
                                contentStream?.lineTo(cellX, yPosition - dynamicRowHeight)
                                contentStream?.lineTo(cellX, yPosition)
                                contentStream?.stroke()

                                val lines = rowLines[c]
                                var textY = yPosition - 4f - 9f
                                for (lText in lines) {
                                    contentStream?.beginText()
                                    contentStream?.setFont(fontNormal, 9f)
                                    contentStream?.newLineAtOffset(cellX + 4f, textY)
                                    safeShowText(contentStream, lText, fontNormal)
                                    contentStream?.endText()
                                    textY -= 12f
                                }
                            }
                            yPosition -= dynamicRowHeight
                        }
                        yPosition -= 8f
                    }
                }
            }
        } finally {
            contentStream?.close()
        }

        publishOfficePdf(pdf, pdfFile)
        } finally {
            pdf.close()
        }
    }

    private suspend fun convertDocxToPdfInternal(docxFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        // Enforce PDFBox resource loading setup
        PDFBoxResourceLoader.init(context)

        var docxStream: FileInputStream? = null
        var docx: XWPFDocument? = null
        var pdf: PDDocument? = null
        var contentStream: PDPageContentStream? = null

        try {
            docxStream = FileInputStream(docxFile)
            docx = XWPFDocument(docxStream)
            pdf = PDDocument()

            val fontNormal = PDType1Font.HELVETICA
            val fontBold = PDType1Font.HELVETICA_BOLD
            val fontSizeNormal = 11f
            val fontSizeHeading = 15f
            val leadingNormal = fontSizeNormal * 1.25f
            val leadingHeading = fontSizeHeading * 1.25f

            // Page geometry comes from the document's own section properties
            // (Letter vs A4, narrow vs normal margins) so converter pagination
            // matches the viewer/print layout instead of assuming A4/50pt.
            var pageBounds = PDRectangle.A4
            var marginLeft = 50f
            var marginRight = 50f
            var marginTop = 50f
            var marginBottom = 50f
            try {
                val sectPr = docx.document.body.sectPr
                val pgSz = sectPr?.pgSz
                val wPt = pgSz?.w?.toString()?.toFloatOrNull()?.div(20f)
                val hPt = pgSz?.h?.toString()?.toFloatOrNull()?.div(20f)
                if (wPt != null && hPt != null && wPt > 0 && hPt > 0) {
                    pageBounds = PDRectangle(wPt, hPt)
                }
                fun twipsToPt(v: Any?): Float? =
                    v?.toString()?.toFloatOrNull()?.takeIf { it >= 0 }?.div(20f)
                twipsToPt(sectPr?.pgMar?.left)?.let { marginLeft = it }
                twipsToPt(sectPr?.pgMar?.right)?.let { marginRight = it }
                twipsToPt(sectPr?.pgMar?.top)?.let { marginTop = it }
                twipsToPt(sectPr?.pgMar?.bottom)?.let { marginBottom = it }
            } catch (_: Exception) { }
            val printableWidth = pageBounds.width - marginLeft - marginRight

            var currentPage = PDPage(pageBounds)
            pdf.addPage(currentPage)
            contentStream = PDPageContentStream(pdf, currentPage)

            var yPosition = pageBounds.height - marginTop
            // Numbered-list counters per numbering id (decimal lists)
            val listCounters = mutableMapOf<String, Int>()

            for (bodyElement in docx.bodyElements) {
                if (bodyElement is org.apache.poi.xwpf.usermodel.XWPFTable) {
                    // Real column widths from the table grid (dxa -> pt), scaled
                    // to the printable width only if the table is wider. Falls
                    // back to equal split when the grid is absent.
                    val gridCols: List<Float> = try {
                        bodyElement.ctTbl.tblGrid?.getGridColList()
                            ?.mapNotNull { it.w?.toString()?.toFloatOrNull()?.div(20f) }
                            ?.filter { it > 0 } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    val gridTotal = gridCols.sum()
                    val gridScale = if (gridTotal > printableWidth && gridTotal > 0) {
                        printableWidth / gridTotal
                    } else {
                        1f
                    }
                    fun spannedWidth(startCol: Int, span: Int): Float {
                        if (gridCols.isEmpty()) return printableWidth
                        var w = 0f
                        for (i in startCol until (startCol + span).coerceAtMost(gridCols.size)) {
                            w += gridCols[i] * gridScale
                        }
                        return if (w > 0) w else printableWidth
                    }
                    for (row in bodyElement.rows) {
                        val cells = row.tableCells
                        val cellCount = cells.size
                        if (cellCount == 0) continue
                        val equalColWidth = printableWidth / cellCount.toFloat()
                        // X offsets per cell, honoring gridSpan merges
                        val cellXList = mutableListOf<Float>()
                        val cellWList = mutableListOf<Float>()
                        var colCursor = 0
                        var xCursorT = marginLeft
                        for (cell in cells) {
                            val span = try {
                                cell.ctTc.tcPr?.gridSpan?.`val`?.toInt() ?: 1
                            } catch (_: Exception) { 1 }
                            val w = if (gridCols.isNotEmpty()) {
                                spannedWidth(colCursor, span.coerceAtLeast(1))
                            } else {
                                equalColWidth * span.coerceAtLeast(1)
                            }
                            cellXList.add(xCursorT)
                            cellWList.add(w)
                            xCursorT += w
                            colCursor += span.coerceAtLeast(1)
                        }

                        // Store lines of text for each cell
                        class CellLine(val text: String, val font: PDType1Font, val fontSize: Float, val leading: Float, val colorHex: String?)
                        val cellLinesList = mutableListOf<List<CellLine>>()
                        var maxCellHeight = 0f

                        for ((cellIndex, cell) in cells.withIndex()) {
                            val cellWrapWidth = (cellWList.getOrNull(cellIndex) ?: equalColWidth) - 10f
                            val lines = mutableListOf<CellLine>()
                            for (para in cell.paragraphs) {
                                val isHeading = para.styleID?.lowercase()?.contains("heading") == true ||
                                        // getFontSize() returns whole points
                                        (para.runs.firstOrNull()?.fontSize ?: 0) > 14

                                for (run in para.runs) {
                                    val font = when {
                                        run.isBold && run.isItalic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                                        run.isBold || isHeading -> PDType1Font.HELVETICA_BOLD
                                        run.isItalic -> PDType1Font.HELVETICA_OBLIQUE
                                        else -> PDType1Font.HELVETICA
                                    }
                                    val fontSizeHalfPoints = run.fontSize
                                    // getFontSize() already returns whole points
                                    val fontSize = if (fontSizeHalfPoints > 0) (fontSizeHalfPoints.toFloat()) else (if (isHeading) fontSizeHeading else fontSizeNormal)
                                    // Word single spacing ~= 1.15; 1.2 keeps converter
                                    // pagination aligned with the viewer/print layout
                                    val leading = fontSize * 1.2f
                                    val runText = run.getText(0) ?: ""
                                    if (runText.isNotEmpty()) {
                                        val wrapped = wrapText(runText, font, fontSize, cellWrapWidth)
                                        for (line in wrapped) {
                                            lines.add(CellLine(line, font, fontSize, leading, run.color))
                                        }
                                    }
                                }
                            }
                            val cellHeight = lines.sumOf { it.leading.toDouble() }.toFloat()
                            if (cellHeight > maxCellHeight) {
                                maxCellHeight = cellHeight
                            }
                            cellLinesList.add(lines)
                        }

                        // Check page bound break for the entire row height
                        if (yPosition - maxCellHeight < marginBottom) {
                            contentStream?.close()
                            currentPage = PDPage(pageBounds)
                            pdf.addPage(currentPage)
                            contentStream = PDPageContentStream(pdf, currentPage)
                            yPosition = pageBounds.height - marginTop
                        }

                        val startY = yPosition
                        var rowBottomY = startY - maxCellHeight
                        if (maxCellHeight == 0f) {
                            rowBottomY = startY - 15f
                        }

                        for (cellIdx in 0 until cellCount) {
                            val cellX = cellXList.getOrNull(cellIdx) ?: (marginLeft + cellIdx * equalColWidth)
                            val cellW = cellWList.getOrNull(cellIdx) ?: equalColWidth
                            val lines = cellLinesList[cellIdx]
                            var currentCellY = startY

                            // Cell background shading (e.g. header rows), read
                            // from the cell properties — absent means no fill.
                            try {
                                val fill = cells[cellIdx].color?.uppercase()
                                if (fill != null && fill.length == 6 && fill != "FFFFFF" && fill != "AUTO") {
                                    contentStream?.setNonStrokingColor(
                                        fill.substring(0, 2).toInt(16),
                                        fill.substring(2, 4).toInt(16),
                                        fill.substring(4, 6).toInt(16)
                                    )
                                    contentStream?.addRect(cellX, rowBottomY, cellW, startY - rowBottomY)
                                    contentStream?.fill()
                                    contentStream?.setNonStrokingColor(0, 0, 0)
                                }
                            } catch (_: Exception) { }

                            for (line in lines) {
                                currentCellY -= line.leading
                                val sanitizedLine = sanitizeText(line.text)
                                contentStream?.beginText()
                                val colorHex = line.colorHex
                                if (colorHex != null && colorHex.length == 6) {
                                    try {
                                        val r = colorHex.substring(0, 2).toInt(16)
                                        val g = colorHex.substring(2, 4).toInt(16)
                                        val b = colorHex.substring(4, 6).toInt(16)
                                        contentStream?.setNonStrokingColor(r, g, b)
                                    } catch (e: Exception) {
                                        contentStream?.setNonStrokingColor(0, 0, 0)
                                    }
                                } else {
                                    contentStream?.setNonStrokingColor(0, 0, 0)
                                }
                                contentStream?.setFont(line.font, line.fontSize)
                                contentStream?.newLineAtOffset(cellX + 5f, currentCellY)
                                safeShowText(contentStream, sanitizedLine, line.font)
                                contentStream?.endText()
                            }

                            // Draw borders for this cell
                            contentStream?.setStrokingColor(200, 200, 200)
                            contentStream?.setLineWidth(0.5f)
                            contentStream?.moveTo(cellX, startY)
                            contentStream?.lineTo(cellX + cellW, startY)
                            contentStream?.lineTo(cellX + cellW, rowBottomY)
                            contentStream?.lineTo(cellX, rowBottomY)
                            contentStream?.lineTo(cellX, startY)
                            contentStream?.stroke()
                        }

                        yPosition = rowBottomY
                    }
                }

                if (bodyElement is org.apache.poi.xwpf.usermodel.XWPFParagraph) {
                    val paragraph = bodyElement
                    val isHeading = paragraph.styleID?.lowercase()?.contains("heading") == true ||
                            (paragraph.runs.firstOrNull()?.fontSize ?: 0) > 14

                    var xCursor = marginLeft

                    // Check if paragraph needs a page break before starting if yPosition is too low
                    if (yPosition - 15f < marginBottom) {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - marginTop
                    }

                    // Explicit Word page break before this paragraph (w:pPr/w:pageBreakBefore):
                    // half-filled pages intentionally continued on the next page must not
                    // flow on from the half page.
                    val pageBreakBefore = try {
                        isOnOff(paragraph.ctp?.pPr?.pageBreakBefore)
                    } catch (_: Exception) { false }
                    if (pageBreakBefore && yPosition < pageBounds.height - marginTop - 0.5f) {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - marginTop
                    }

                    val maxRunSize = paragraph.runs.mapNotNull { run ->
                        try { run.fontSize } catch (_: Exception) { null }
                    }.filter { it > 0 }.maxOrNull()?.toFloat()
                        ?: if (isHeading) fontSizeHeading else fontSizeNormal

                    // Check if paragraph has text or images
                    val hasTextOrImage = paragraph.runs.any { (it.getText(0) ?: "").isNotEmpty() || it.embeddedPictures.isNotEmpty() }
                    if (!hasTextOrImage) {
                        val blankRule = try { paragraph.spacingLineRule?.name } catch (_: Exception) { null }
                        val blankBetween = try { paragraph.spacingBetween } catch (_: Exception) { -1.0 }
                        // OOXML units: EXACT/AT_LEAST w:line is twips (1/20pt),
                        // AUTO w:line is 240ths of a line (240 = single).
                        val blankLeading = when {
                            blankRule == "EXACT" && blankBetween > 0 -> (blankBetween / 20f).toFloat()
                            blankRule == "AT_LEAST" && blankBetween > 0 ->
                                maxOf(maxRunSize * 1.15f, (blankBetween / 20f).toFloat())
                            blankBetween > 0 -> (maxRunSize * (blankBetween / 240f)).toFloat()
                            else -> maxRunSize * 1.15f
                        }
                        val blankBefore = try { paragraph.spacingBefore } catch (_: Exception) { -1 }
                        if (blankBefore > 0 && yPosition < pageBounds.height - marginTop - 0.5f) {
                            yPosition -= blankBefore / 20f
                        }
                        if (yPosition - blankLeading < marginBottom) {
                            contentStream?.close()
                            currentPage = PDPage(pageBounds)
                            pdf.addPage(currentPage)
                            contentStream = PDPageContentStream(pdf, currentPage)
                            yPosition = pageBounds.height - marginTop
                        } else {
                            yPosition -= blankLeading
                        }
                        val blankAfter = try { paragraph.spacingAfter } catch (_: Exception) { -1 }
                        if (blankAfter > 0) yPosition -= blankAfter / 20f
                        xCursor = marginLeft
                        continue
                    }

                    // ---- Paragraph layout: alignment, indents, bullets ----
                    // Words are buffered per line and then emitted, so CENTER /
                    // RIGHT / JUSTIFY and bullet hanging indents can be honored
                    // (the viewer already renders all of these).
                    val alignName = try { paragraph.alignment?.name } catch (_: Exception) { null }
                    val isCenter = alignName == "CENTER"
                    val isRight = alignName == "RIGHT"
                    val isJustify = alignName == "BOTH" || alignName == "JUSTIFY" || alignName == "DISTRIBUTE"
                    val leftIndentPts = (try { paragraph.indentationLeft } catch (_: Exception) { 0 }) / 20f
                    val firstLineExtraPts = (try { paragraph.indentationFirstLine } catch (_: Exception) { 0 }) / 20f
                    val rightIndentPts = (try { paragraph.indentationRight } catch (_: Exception) { 0 }) / 20f
                    val rightEdge = pageBounds.width - marginRight - rightIndentPts
                    val spacingRule = try { paragraph.spacingLineRule?.name } catch (_: Exception) { null }
                    val spacingLineVal = try { paragraph.spacingBetween } catch (_: Exception) { -1.0 }
                    // OOXML units: EXACT/AT_LEAST w:line is twips (1/20pt),
                    // AUTO w:line is 240ths of a line (240 = single).
                    val paraLeading = when {
                        spacingRule == "EXACT" && spacingLineVal > 0 ->
                            (spacingLineVal / 20f).toFloat()
                        spacingRule == "AT_LEAST" && spacingLineVal > 0 ->
                            maxOf(maxRunSize * 1.15f, (spacingLineVal / 20f).toFloat())
                        spacingLineVal > 0 ->
                            (maxRunSize * (spacingLineVal / 240f)).toFloat()
                        else -> maxRunSize * 1.15f
                    }
                    // Gap after the paragraph honors spacing-after (twips);
                    // headings keep a readable minimum.
                    val afterPts = try { paragraph.spacingAfter } catch (_: Exception) { -1 }
                    val paraGap = when {
                        afterPts > 0 -> afterPts / 20f
                        isHeading -> 8f
                        else -> 2f
                    }
                    val beforePts = try { paragraph.spacingBefore } catch (_: Exception) { -1 }
                    if (beforePts > 0 && yPosition < pageBounds.height - marginTop - 0.5f) {
                        yPosition -= beforePts / 20f
                    }
                    // List bullet / number label (hanging indent keeps wrapped
                    // lines aligned with the first line's text)
                    var bulletLabel: String? = null
                    var textStartX = marginLeft + leftIndentPts
                    try {
                        val numId = paragraph.numID
                        if (numId != null) {
                            val fmt = try { paragraph.numFmt } catch (_: Exception) { null } ?: "bullet"
                            bulletLabel = if (fmt.contains("bullet", ignoreCase = true)) {
                                "•"
                            } else {
                                val key = numId.toString()
                                val n = (listCounters[key] ?: 0) + 1
                                listCounters[key] = n
                                "$n."
                            }
                            textStartX = marginLeft + leftIndentPts + 18f
                        }
                    } catch (_: Exception) { bulletLabel = null }

                    class PenWord(
                        val text: String,
                        val font: PDType1Font,
                        val size: Float,
                        val colorHex: String?,
                        val width: Float,
                        val isSpace: Boolean
                    )
                    val lineBuf = mutableListOf<PenWord>()
                    var isFirstLine = true
                    var lineStartX = textStartX + firstLineExtraPts
                    xCursor = lineStartX

                    fun newPage() {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - marginTop
                    }

                    fun drawWordAt(text: String, font: PDType1Font, size: Float, colorHex: String?, x: Float, y: Float) {
                        contentStream?.beginText()
                        if (colorHex != null && colorHex.length == 6) {
                            try {
                                contentStream?.setNonStrokingColor(
                                    colorHex.substring(0, 2).toInt(16),
                                    colorHex.substring(2, 4).toInt(16),
                                    colorHex.substring(4, 6).toInt(16)
                                )
                            } catch (_: Exception) {
                                contentStream?.setNonStrokingColor(0, 0, 0)
                            }
                        } else {
                            contentStream?.setNonStrokingColor(0, 0, 0)
                        }
                        contentStream?.setFont(font, size)
                        contentStream?.newLineAtOffset(x, y)
                        safeShowText(contentStream, text, font)
                        contentStream?.endText()
                    }

                    fun emitLine(lastOfPara: Boolean) {
                        // Drop edge whitespace for measuring/drawing
                        var start = 0
                        var end = lineBuf.size
                        while (start < end && lineBuf[start].isSpace) start++
                        while (end > start && lineBuf[end - 1].isSpace) end--
                        if (start >= end) {
                            lineBuf.clear()
                            lineStartX = textStartX
                            xCursor = textStartX
                            isFirstLine = false
                            return
                        }
                        val words = lineBuf.subList(start, end)
                        val lineWidth = words.sumOf { it.width.toDouble() }.toFloat()
                        var x = when {
                            isCenter -> lineStartX + ((rightEdge - lineStartX - lineWidth) / 2f).coerceAtLeast(0f)
                            isRight -> (rightEdge - lineWidth).coerceAtLeast(lineStartX)
                            else -> lineStartX
                        }
                        var extraPerGap = 0f
                        if (isJustify && !lastOfPara) {
                            val gaps = words.count { it.isSpace }
                            if (gaps > 0) {
                                extraPerGap = ((rightEdge - lineStartX - lineWidth) / gaps.toFloat()).coerceAtLeast(0f)
                            }
                        }
                        if (yPosition - paraLeading < marginBottom) {
                            newPage()
                        }
                        if (isFirstLine && bulletLabel != null) {
                            val bFont = words.firstOrNull()?.font ?: PDType1Font.HELVETICA
                            val bSize = words.firstOrNull()?.size ?: paraLeading
                            drawWordAt(bulletLabel!!, bFont, bSize, null, lineStartX - 13f, yPosition)
                        }
                        for (w in words) {
                            if (!w.isSpace) {
                                drawWordAt(w.text, w.font, w.size, w.colorHex, x, yPosition)
                            }
                            x += w.width + if (w.isSpace) extraPerGap else 0f
                        }
                        yPosition -= paraLeading
                        lineBuf.clear()
                        isFirstLine = false
                        lineStartX = textStartX
                        xCursor = textStartX
                    }

                    for (run in paragraph.runs) {
                        // Explicit Word page break inside a run (<w:br w:type="page"/>):
                        // flush the current line and continue on a fresh page.
                        val runHasPageBreak = try {
                            run.ctr?.brList?.any {
                                it.type?.toString()?.equals("page", ignoreCase = true) == true
                            } == true
                        } catch (_: Exception) { false }
                        if (runHasPageBreak) {
                            if (lineBuf.isNotEmpty()) {
                                emitLine(false)
                            }
                            newPage()
                        }
                        // Check for images
                        val pictures = run.embeddedPictures
                        if (pictures.isNotEmpty()) {
                            for (pic in pictures) {
                                try {
                                    val picData = pic.pictureData.data
                                    val decoded = BitmapFactory.decodeByteArray(picData, 0, picData.size)
                                    if (decoded != null) {
                                        val originalWidth = decoded.width.toFloat()
                                        val originalHeight = decoded.height.toFloat()
                                        var targetWidth = Math.min(printableWidth, originalWidth)
                                        var targetHeight = (targetWidth / originalWidth) * originalHeight
                                        // Clamp images taller than one page
                                        val maxImageHeight = pageBounds.height - marginTop - marginBottom
                                        if (targetHeight > maxImageHeight) {
                                            targetWidth = (targetWidth * maxImageHeight / targetHeight)
                                            targetHeight = maxImageHeight
                                        }
                                        // Downscale huge photos to print size before embedding
                                        // (keeps PDF small and conversion fast)
                                        val bitmap = if (originalWidth > targetWidth * 1.5f) {
                                            val scaled = Bitmap.createScaledBitmap(
                                                decoded,
                                                targetWidth.toInt().coerceAtLeast(1),
                                                targetHeight.toInt().coerceAtLeast(1),
                                                true
                                            )
                                            decoded.recycle()
                                            scaled
                                        } else {
                                            decoded
                                        }

                                        if (lineBuf.isNotEmpty()) {
                                            emitLine(false)
                                        }

                                        if (yPosition - targetHeight < marginBottom) {
                                            newPage()
                                        }

                                        val pdImage = LosslessFactory.createFromImage(pdf, bitmap)
                                        contentStream?.drawImage(pdImage, marginLeft, yPosition - targetHeight, targetWidth, targetHeight)
                                        yPosition -= targetHeight
                                        bitmap.recycle()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        // Check for text
                        val runText = mapBulletChars(run.getText(0) ?: "")
                        if (runText.isNotEmpty()) {
                            val fontSizeHalfPoints = run.fontSize
                            // getFontSize() already returns whole points
                            val actualFontSize = if (fontSizeHalfPoints > 0) (fontSizeHalfPoints.toFloat()) else (if (isHeading) fontSizeHeading else fontSizeNormal)
                            // Word single spacing ~= 1.15; 1.2 keeps converter
                            // pagination aligned with the viewer/print layout
                            val leading = actualFontSize * 1.2f
                            val font = when {
                                run.isBold && run.isItalic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                                run.isBold -> PDType1Font.HELVETICA_BOLD
                                run.isItalic -> PDType1Font.HELVETICA_OBLIQUE
                                else -> PDType1Font.HELVETICA
                            }

                            // Split runText into words and buffer them; lines are
                            // emitted with alignment/justification applied
                            val words = runText.split(Regex("(?<=\\s)|(?=\\s)"))
                            for (word in words) {
                                if (word.isEmpty()) continue
                                val isSpace = word.isBlank()
                                val sanitizedWord = sanitizeText(word)
                                val emitText = if (isSpace) " " else sanitizedWord
                                if (!isSpace && emitText.isEmpty()) continue
                                val wordWidth = try {
                                    font.getStringWidth(emitText) / 1000f * actualFontSize
                                } catch (e: Exception) {
                                    0f
                                }

                                if (!isSpace && xCursor + wordWidth > rightEdge && lineBuf.any { !it.isSpace }) {
                                    emitLine(false)
                                }
                                lineBuf.add(PenWord(emitText, font, actualFontSize, run.color, wordWidth, isSpace))
                                xCursor += wordWidth
                            }
                        }
                    }

                if (lineBuf.isNotEmpty()) {
                    emitLine(true)
                }
                yPosition -= paraGap
                    // Next paragraph always starts at the left margin (prevents
                    // mid-line continuation that wasted vertical space / pages)
                    xCursor = marginLeft
                }
            }

            contentStream?.close()
            contentStream = null

            publishOfficePdf(pdf, pdfFile)

        } finally {
            try {
                contentStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                pdf?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                docx?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                docxStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Converts a XLSX spreadsheet sheet-by-sheet to PDF using a three-level export architecture:
     * - Level 1 (Natural-size): Single continuous page when width and height are <= 14,400 pt.
     * - Level 2 (Extended-size via /UserUnit): Single page with /UserUnit scaling for dimensions > 14,400 pt up to 75,000 pt.
     * - Level 3 (Lossless 2D Tiling): Row-major two-dimensional panel tiling with continuation banners and optional frozen columns.
     * Uses OpenXmlDocumentParser to execute offline on Android ART with zero Apache POI crashes.
     */
    suspend fun convertXlsxToPdf(xlsxFile: File, pdfFile: File, context: android.content.Context,
        savedPrintLayout: Boolean = false) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)

        val workbookData = OpenXmlDocumentParser.parseXlsx(xlsxFile)
        if (workbookData.sheets.isEmpty()) {
            throw IllegalStateException("Spreadsheet contains no readable sheets or is corrupted")
        }

        var pdf: PDDocument? = null

        try {
            pdf = PDDocument()

            val fontNormal = PDType1Font.HELVETICA
            val fontBold = PDType1Font.HELVETICA_BOLD
            if (savedPrintLayout) pdf.documentInformation.subject = "Approximate saved Excel print layout; out-of-area charts omitted, unplaceable drawings rejected. No printer-exact guarantee."
            val limits = LayoutLimits(
                minPageWidth = 842f,
                minPageHeight = 595f,
                maxStandardCoord = 14400f,
                maxExtendedCoord = 75000f,
                maxTileWidth = 14400f,
                maxTileHeight = 14400f,
                maxTileWidthOverride = maxPdfPageWidthOverride,
                maxTileHeightOverride = maxPdfPageHeightOverride,
                forceStrategy = forceExportStrategy,
                repeatFrozenColumnsInTiling = repeatFrozenColumnsInTiling
            )

            val renderContext = currentCoroutineContext()
            val checkpoint: () -> Unit = { renderContext.ensureActive() }
            for ((sheetIdx, sheet) in workbookData.sheets.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (savedPrintLayout && !sheet.visible) continue
                if (savedPrintLayout && (sheet.charts.any { it.printsWithSheet } || sheet.unsupportedDrawings > 0)) {
                    require(sheet.unsupportedDrawings == 0) {
                        "Saved print layout contains unplaceable drawing objects; use full worksheet export"
                    }
                    // Reconstructed appendix charts would leak cached data outside
                    // a saved print range and add pages Excel never requested.
                    require(sheet.printSettings.area != null) {
                        "Saved print layout cannot locate charts without an explicit print area; use full worksheet export"
                    }
                    val areas = XlsxSavedPrintLayout.areas(
                        sheet.name,sheet.printSettings,sheet.rows,sheet.mergedRegions)
                    for (chart in sheet.charts.filter { it.printsWithSheet }) {
                        require(chart.printAreaAnchorTrusted &&
                            areas.none { XlsxSavedPrintLayout.chartMayIntersectPrintArea(chart.sourceAnchor,it) }) {
                            "A saved chart intersects the print area or lacks a verifiable anchor; use full worksheet export"
                        }
                    }
                }
                val maxCol = sheet.rows.maxOfOrNull { it.size } ?: 0
                if (maxCol == 0 && sheet.mergedRegions.isEmpty() && sheet.charts.isEmpty() && sheet.unsupportedDrawings == 0 &&
                    !(savedPrintLayout && sheet.printSettings.area != null)) continue // Empty sheet

                val notes = buildList {
                    if (sheet.cachedFormulaCount > 0) add("cached formulas; not recalculated")
                    if (sheet.unsupportedConditionalRules > 0) add("conditional formatting partial")
                    if (sheet.charts.isNotEmpty()) add("cached charts follow")
                    if (sheet.unsupportedDrawings > 0) add("some drawing objects unsupported")
                }
                val displayedSheet = if (notes.isEmpty()) sheet else sheet.copy(name = sheet.name + " [" + notes.joinToString("; ") + "]")
                if (savedPrintLayout && (maxCol > 0 || sheet.mergedRegions.isNotEmpty() || sheet.printSettings.area != null)) {
                    renderSavedPrintSheet(pdf, sheet, sheetIdx, fontNormal, limits, checkpoint)
                } else if (maxCol > 0 || sheet.mergedRegions.isNotEmpty()) {
                val plan = SpreadsheetLayoutEngine.createExportPlan(displayedSheet, sheetIdx, fontNormal, limits)
                require(pdf.numberOfPages.toLong() + maxOf(1, plan.tiles.size) <= SpreadsheetGeometry.MAX_PAGES) {
                    "Workbook exceeds PDF page budget"
                }

                when (plan.strategy) {
                    ExportStrategy.NATURAL_SIZE -> {
                        renderNaturalOrExtendedSheet(
                            pdf = pdf,
                            plan = plan,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            limits = limits,
                            isExtended = false,
                            checkpoint = checkpoint
                        )
                    }
                    ExportStrategy.EXTENDED_SIZE -> {
                        renderNaturalOrExtendedSheet(
                            pdf = pdf,
                            plan = plan,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            limits = limits,
                            isExtended = true,
                            checkpoint = checkpoint
                        )
                    }
                    ExportStrategy.TILED -> {
                        renderTiledSheet(
                            pdf = pdf,
                            plan = plan,
                            fontNormal = fontNormal,
                            fontBold = fontBold,
                            limits = limits,
                            checkpoint = checkpoint
                        )
                    }
                }
                }
                if (!savedPrintLayout) for (chart in sheet.charts)
                    renderCachedSpreadsheetChart(pdf, sheet.name, chart, checkpoint)
                if (sheet.unsupportedDrawings > 0 && maxCol == 0 && sheet.mergedRegions.isEmpty()) {
                    require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
                    val page = PDPage(PDRectangle(842f, 595f))
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.beginText(); stream.setFont(fontBold, 12f); stream.newLineAtOffset(30f, 550f)
                        safeShowText(stream, sheet.name + ": unsupported drawing objects", fontBold); stream.endText()
                    }
                }
            }

            require(pdf.numberOfPages > 0) {
                if (savedPrintLayout) "No visible printable worksheets in saved print layout"
                else "Workbook contains no printable worksheet content"
            }
            require(pdf.numberOfPages <= SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            publishOfficePdf(pdf, pdfFile)
        } finally {
            try { pdf?.close() } catch (_: Exception) {}
        }
    }

    private fun renderSavedPrintSheet(pdf: PDDocument, sheet: OpenXmlDocumentParser.XlsxSheetData, index: Int,
        font: PDType1Font, limits: LayoutLimits, checkpoint: () -> Unit) {
        val settings = sheet.printSettings
        val areas = XlsxSavedPrintLayout.areas(sheet.name, settings, sheet.rows, sheet.mergedRegions)
        val titles = XlsxSavedPrintLayout.titles(sheet.name, settings)
        val requiredRows = maxOf(sheet.rows.size, areas.maxOf { it.lastRow } + 1, (titles.rows?.last ?: -1) + 1)
        val requiredCols = maxOf(sheet.rows.maxOfOrNull { it.size } ?: 0, areas.maxOf { it.lastCol } + 1,
            (titles.columns?.last ?: -1) + 1)
        require(requiredRows.toLong() * requiredCols <= SpreadsheetGeometry.MAX_GRID_CELLS) { "Print area exceeds layout budget" }
        val rows = List(requiredRows) { r ->
            val existing = sheet.rows.getOrNull(r).orEmpty()
            if (r == requiredRows - 1 && existing.size < requiredCols)
                existing + List(requiredCols - existing.size) { "" } else existing
        }
        val layout = SpreadsheetLayoutEngine.buildSheetLayout(sheet.copy(rows = rows,
            styles = sheet.styles.copy(showGridLines = settings.gridLines)), index, font, limits)
        val plans = areas.map { XlsxSavedPrintLayout.plan(layout, it, settings, titles) }
        require(plans.sumOf { it.tiles.size.toLong() } + pdf.numberOfPages <= SpreadsheetGeometry.MAX_PAGES) {
            "Saved print areas exceed PDF page budget"
        }
        val merges = SpreadsheetMergeIndex(layout.rowCount, layout.colCount, layout.mergedRegions)
        val sheetPages = plans.sumOf { it.tiles.size }
        var sheetPageNumber = 0
        for (plan in plans) for (tile in plan.tiles) {
            sheetPageNumber++
            checkpoint()
            require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            val rowRange = tile.first
            val colRange = tile.second
            val tableW = colRange.sumOf { layout.colWidths[it].toDouble() }.toFloat()
            val tableH = rowRange.sumOf { layout.rowHeights[it].toDouble() }.toFloat()
            val left = plan.left + if (settings.centerHorizontal) (plan.contentWidth - tableW * plan.scale) / 2f else 0f
            val top = plan.height - plan.top - if (settings.centerVertical) (plan.contentHeight - tableH * plan.scale) / 2f else 0f
            val page = PDPage(PDRectangle(plan.width, plan.height))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.saveGraphicsState()
                try {
                    stream.transform(Matrix(plan.scale, 0f, 0f, plan.scale, left, top))
                    val xs = mutableMapOf<Int,Float>()
                    val ys = mutableMapOf<Int,Float>()
                    var runningX = 0f; var runningY = 0f
                    for (c in colRange) { xs[c] = runningX; runningX += layout.colWidths[c] }
                    for (r in rowRange) { ys[r] = -runningY; runningY += layout.rowHeights[r] }
                    fun x(c: Int) = xs.getValue(c)
                    fun y(r: Int) = ys.getValue(r)
                    for (r in rowRange) {
                        checkpoint()
                        for (c in colRange) {
                            if (merges.at(r,c) >= 0) continue
                            drawCellChunk(stream, x(c), y(r), layout.colWidths[c], layout.rowHeights[r],
                                layout.rowWrappedLines[r][c], font, limits.fontSizeCell, limits.lineHeight, layout.cellStyles.at(r,c))
                        }
                    }
                    for (geom in layout.mergedGeometries) {
                        if (rowRange.none { it in geom.firstRow..geom.lastRow } ||
                            colRange.none { it in geom.firstCol..geom.lastCol }) continue
                        require((geom.firstRow..geom.lastRow).all { it in ys } &&
                            (geom.firstCol..geom.lastCol).all { it in xs }) {
                            "Saved print boundary cuts through a merged cell; use content-preserving export"
                        }
                        drawCellChunk(stream, x(geom.firstCol), y(geom.firstRow), geom.totalWidth, geom.totalHeight,
                            geom.wrappedLines, font, limits.fontSizeCell, limits.lineHeight,
                            layout.cellStyles.at(geom.firstRow,geom.firstCol))
                    }
                    renderSpreadsheetEdges(stream, layout, merges, rowRange, colRange, { y(it) },
                        { y(it) - layout.rowHeights[it] }, { x(it) }, true, true, checkpoint)
                    if (settings.printHeadings) {
                        // Inverse font scaling keeps heading glyphs 8 pt even
                        // when cells use an explicitly saved 10-400% print scale.
                        val titleSize = 8f / plan.scale
                        val topY = 6f / plan.scale
                        stream.setNonStrokingColor(65,70,76)
                        for (c in colRange) {
                            checkpoint()
                            val label = SpreadsheetLayoutEngine.colIndexToRef(c)
                            val width = font.getStringWidth(label) * titleSize / 1000f
                            val cx = x(c) + (layout.colWidths[c] - width)/2f
                            stream.beginText(); stream.setFont(font, titleSize)
                            stream.newLineAtOffset(cx, topY)
                            safeShowText(stream,label,font); stream.endText()
                        }
                        for (r in rowRange) {
                            checkpoint()
                            val label = (r+1).toString()
                            val width = font.getStringWidth(label) * titleSize / 1000f
                            val rightEdge = -5f/plan.scale
                            val baseline = y(r) - layout.rowHeights[r]/2f - titleSize/3f
                            stream.beginText(); stream.setFont(font, titleSize)
                            stream.newLineAtOffset(rightEdge-width,baseline)
                            safeShowText(stream,label,font); stream.endText()
                        }
                    }
                } finally { stream.restoreGraphicsState() }
                drawSavedPrintHeaderFooter(stream, settings, plan, sheet.name, sheetPageNumber, sheetPages)
                if (plan.bottom >= 12f) {
                    stream.beginText(); stream.setFont(font, 8f); stream.newLineAtOffset(plan.left, 5f)
                    val note = if (settings.unsupportedFeatures || sheet.unsupportedConditionalRules > 0 ||
                        sheet.unsupportedDrawings > 0 || sheet.charts.isNotEmpty()) " - some features reconstructed or omitted" else ""
                    safeShowText(stream, "Approximate saved print layout" + note, font); stream.endText()
                }
            }
        }
    }

    private fun drawSavedPrintHeaderFooter(stream: PDPageContentStream, settings: XlsxSavedPrintSettings,
        plan: XlsxSavedPrintLayout.Plan, sheet: String, page: Int, pages: Int) {
        val print = settings.headerFooter
        val numbered = when (settings.setup["useFirstPageNumber"]) {
            null, "", "0", "false" -> false
            "1", "true" -> true
            else -> throw IllegalArgumentException("Invalid Excel first-page-number flag")
        }
        val firstPage = if (numbered) settings.setup["firstPageNumber"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing Excel first page number") else 1
        require(firstPage in 1..999_999 && firstPage.toLong() + pages - 1 <= 999_999L) {
            "Excel printed page number exceeds supported budget"
        }
        val printedPage = firstPage + page - 1
        val font = PDType1Font.HELVETICA
        fun draw(raw: String, header: Boolean) {
            if (raw.isEmpty()) return
            val sections = XlsxPrintHeaderFooter.expand(raw, sheet, printedPage, pages)
            val edge = if (header) "header" else "footer"
            val marginInches = settings.margins[edge]?.toFloatOrNull() ?: 0.3f
            require(marginInches.isFinite() && marginInches in 0f..20f) { "Invalid saved $edge margin" }
            val y = if (header) plan.height - marginInches*72f - 9f else marginInches*72f
            require(if (header) y >= plan.height - (plan.top - plan.headingTop) + 4f
                    else y + 11f <= plan.bottom) {
                "Saved $edge collides with worksheet content; use full worksheet export"
            }
            val zone = plan.contentWidth / 3f
            for ((i,value) in listOf(sections.left, sections.center, sections.right).withIndex()) {
                if (value.isEmpty()) continue
                val width = try {
                    // Do not size sanitized text and then emit a silently changed
                    // header/footer. The exact expanded value must be encodable.
                    font.encode(value)
                    font.getStringWidth(value) * 9f / 1000f
                } catch (_: Exception) { throw IllegalArgumentException("Unsupported saved $edge glyphs") }
                require(width <= zone - 8f) { "Saved $edge text exceeds its page section" }
                val x = plan.left + i*zone + when (i) {
                    0 -> 4f
                    1 -> (zone - width) / 2f
                    else -> zone - width - 4f
                }
                stream.setNonStrokingColor(0,0,0)
                stream.beginText()
                stream.setFont(font,9f)
                stream.newLineAtOffset(x,y)
                stream.showText(value)
                stream.endText()
            }
        }
        draw(print.headerFor(page, printedPage), true)
        draw(print.footerFor(page, printedPage), false)
    }

    /** Cached-data appendix: preserve worksheet cells and never claim the original chart appearance. */
    private fun renderCachedSpreadsheetChart(pdf: PDDocument, sheet: String, chart: XlsxCachedChart, checkpoint: () -> Unit) {
        if (chart.kind == "pie") {
            renderCachedPieChart(pdf, sheet, chart, checkpoint)
            return
        }
        if (chart.kind == "scatter") {
            renderCachedScatterChart(pdf, sheet, chart, checkpoint)
            return
        }
        val palette = intArrayOf(0x2463A0, 0xD15A36, 0x36845B, 0x8354AA, 0xA47715, 0x247D82)
        val combo = chart.kind == "combo"
        val comboBarCount = if (combo) chart.series.count { it.chartKind == "column" } else 0
        val stacked = chart.kind in setOf("bar", "column") && chart.grouping in setOf("stacked", "percentStacked")
        val percentStacked = stacked && chart.grouping == "percentStacked"
        val maxAbs = chart.series.flatMap { it.values }.filterNotNull().maxOfOrNull { kotlin.math.abs(it) }
            ?.takeIf { it > 0.0 } ?: 1.0
        val stacks = if (stacked) XlsxStackedChart.segments(chart, maxAbs, percentStacked) else emptyList()
        val bounds = if (stacked) stacks.flatten().filterNotNull().flatMap { listOf(it.start, it.end) }
            else chart.series.flatMap { it.values }.filterNotNull().map { it / maxAbs }
        val min = minOf(0.0, bounds.minOrNull() ?: 0.0)
        val max = maxOf(0.0, bounds.maxOrNull() ?: 0.0)
        val high = if (max == min) min + 1.0 else max
        fun normalizedFraction(v: Double) = ((v - min) / (high - min)).toFloat().coerceIn(0f, 1f)
        fun fraction(v: Double) = normalizedFraction(v / maxAbs)
        for ((panel, indices) in chart.categories.indices.chunked(12).withIndex()) {
            checkpoint()
            require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            val page = PDPage(PDRectangle(842f, 595f))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                val font = PDType1Font.HELVETICA
                fun label(value: String, x: Float, y: Float, size: Float = 9f, bold: Boolean = false) {
                    val f = if (bold) PDType1Font.HELVETICA_BOLD else font
                    stream.setNonStrokingColor(0, 0, 0)
                    stream.beginText(); stream.setFont(f, size); stream.newLineAtOffset(x, y)
                    safeShowText(stream, value, f); stream.endText()
                }
                fun color(rgb: Int) { stream.setNonStrokingColor(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255) }
                fun stroke(rgb: Int) { stream.setStrokingColor(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255) }
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                    stream.moveTo(x1,y1); stream.lineTo(x2,y2); stream.stroke()
                }
                label(sheet.take(70) + " / Cached chart " + (panel + 1), 30f, 565f, 11f, true)
                label(chart.title.take(80) + if (combo) " [column+line]" else
                    if (stacked) (if (percentStacked) " [100% stacked]" else " [stacked]") else "", 30f, 545f, 13f, true)
                label("Saved chart cache; reconstructed appearance, not recalculated" +
                    (chart.sourceAnchor?.let { " / cells $it" } ?: ""), 30f, 529f)
                val left = 100f; val bottom = 270f; val width = 680f; val height = 235f
                val horizontal = chart.kind == "bar"
                val slot = (if (horizontal) height else width) / indices.size
                val zero = if (horizontal) left + normalizedFraction(0.0)*width else bottom + normalizedFraction(0.0)*height
                stroke(0xB0B0B0); stream.setLineWidth(0.5f)
                if (horizontal) line(zero,bottom,zero,bottom+height) else line(left,zero,left+width,zero)
                for (tick in 0..4) {
                    val f = tick / 4f
                    val normalized = min + (high - min)*f
                    val v = normalized * maxAbs
                    val value = if (percentStacked) String.format(java.util.Locale.US, "%.0f%%", normalized*100)
                        else if (v.isFinite()) String.format(java.util.Locale.US, "%.4g", v)
                        else String.format(java.util.Locale.US, "%.4g x %.4g", normalized, maxAbs)
                    stroke(0xE0E0E0)
                    if (horizontal) { val x = left + f*width; line(x,bottom,x,bottom+height); label(value,x-10f,bottom-25f,8f) }
                    else { val y = bottom + f*height; line(left,y,left+width,y); label(value,30f,y-3f,8f) }
                }
                for ((seriesIndex, series) in chart.series.withIndex()) {
                    checkpoint()
                    var previous: Pair<Float,Float>? = null
                    val bar = slot * 0.8f / (if (stacked) 1 else if (combo) comboBarCount else chart.series.size)
                    for ((local, index) in indices.withIndex()) {
                        val v = series.values[index]
                        if (v == null) { previous = null; continue }
                        val position = if (horizontal) bottom + height - slot*(local+0.5f) else left + slot*(local+0.5f)
                        if (chart.kind == "line" || (combo && series.chartKind == "line")) {
                            val y = bottom + fraction(v)*height
                            stroke(series.rgb ?: palette[seriesIndex]); stream.setLineWidth(series.strokeWidthPt ?: 1.4f)
                            previous?.let { line(it.first,it.second,position,y) }
                            color(series.rgb ?: palette[seriesIndex]); stream.addRect(position-2f,y-2f,4f,4f); stream.fill()
                            previous = position to y
                        } else {
                            color(series.rgb ?: palette[seriesIndex])
                            val ordinal = if (combo) chart.series.take(seriesIndex).count { it.chartKind == "column" } else seriesIndex
                            val offset = position - slot*0.4f + (if (stacked) 0 else ordinal)*bar
                            val segment = if (stacked) stacks[seriesIndex][index] ?: continue else
                                XlsxStackedChart.Segment(0.0, v / maxAbs)
                            val start = normalizedFraction(segment.start)
                            val end = normalizedFraction(segment.end)
                            if (horizontal) {
                                val x1 = left + start*width; val x2 = left + end*width
                                stream.addRect(minOf(x1,x2),offset,kotlin.math.abs(x2-x1),bar*0.9f)
                            } else {
                                val y1 = bottom + start*height; val y2 = bottom + end*height
                                stream.addRect(offset,minOf(y1,y2),bar*0.9f,kotlin.math.abs(y2-y1))
                            }
                            stream.fill()
                        }
                    }
                }
                for ((local,index) in indices.withIndex()) {
                    if (horizontal) label((index+1).toString(),80f,bottom+height-slot*(local+0.5f)-3f,8f)
                    else label((index+1).toString(),left+slot*(local+0.5f)-3f,bottom-12f,8f)
                }
                // Index labels and a numeric cache table make small bars, missing values and
                // category order auditable independently from the simplified chart graphics.
                val column = 570f / chart.series.size
                for ((i,series) in chart.series.withIndex()) {
                    color(series.rgb ?: palette[i]); stream.addRect(230f+i*column,231f,8f,8f); stream.fill()
                    label(series.name.take(14),241f+i*column,231f,8f)
                }
                for ((local,index) in indices.withIndex()) {
                    val y = 213f-local*14f
                    label((index+1).toString() + " " + (chart.categories[index] ?: "(missing category)").take(30),30f,y,8f)
                    for ((i,series) in chart.series.withIndex())
                        label(series.values[index]?.let { String.format(java.util.Locale.US,"%.7g",it) } ?: "(missing)",230f+i*column,y,8f)
                }
                label("Labels and numbers abbreviated for display; absent cache points are not inferred.", 30f, 28f, 8f)
            }
        }
    }

    /** Saved numerical pairs are drawn on actual XY axes; no categorical spacing. */
    private fun renderCachedScatterChart(pdf: PDDocument, sheet: String, chart: XlsxCachedChart,
        checkpoint: () -> Unit) {
        val palette = intArrayOf(0x2463A0,0xD15A36,0x36845B,0x8354AA,0xA47715)
        require(chart.series.size in 1..6 && chart.scatterStyle in setOf("marker","line","lineMarker"))
        val pairs = chart.series.flatMap { series ->
            series.xValues.zip(series.values).mapNotNull { (x, y) ->
                if (x == null || y == null) null else x to y
            }
        }
        // Only drawable complete (x,y) pairs determine axes. An unmatched
        // large value must not flatten the actually plotted data.
        val xAxis = XlsxScatterGeometry.axis(pairs.map { it.first })
        val yAxis = XlsxScatterGeometry.axis(pairs.map { it.second })
        val segments = chart.categories.indices.chunked(12)
        for ((panel, indices) in segments.withIndex()) {
            checkpoint()
            require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            val page = PDPage(PDRectangle(842f,595f))
            pdf.addPage(page)
            PDPageContentStream(pdf,page).use { stream ->
                val font = PDType1Font.HELVETICA
                fun textAt(s: String, x: Float, y: Float, size: Float = 9f) {
                    stream.setNonStrokingColor(0,0,0)
                    stream.beginText(); stream.setFont(font,size); stream.newLineAtOffset(x,y)
                    safeShowText(stream,s,font); stream.endText()
                }
                textAt(sheet.take(70) + " / XY scatter (cached) " + (panel+1),30f,565f,11f)
                textAt(chart.title.take(95),30f,545f,13f)
                textAt("Numeric XY coordinates; cached data only, not recalculated" +
                    (chart.sourceAnchor?.let { " / cells $it" } ?: ""),30f,529f)
                val left = 98f; val bottom = 272f; val width = 672f; val height = 234f
                stream.setStrokingColor(210,210,210); stream.setLineWidth(0.6f)
                for (tick in 0..4) {
                    val f = tick/4f
                    val x = left+f*width; val y = bottom+f*height
                    stream.moveTo(x,bottom); stream.lineTo(x,bottom+height); stream.stroke()
                    stream.moveTo(left,y); stream.lineTo(left+width,y); stream.stroke()
                    textAt(xAxis.label(f),x-12f,bottom-20f,8f)
                    textAt(yAxis.label(f),30f,y-3f,8f)
                }
                for ((seriesIndex,series) in chart.series.withIndex()) {
                    checkpoint()
                    require(series.xValues.size == series.values.size)
                    val rgb = series.rgb ?: palette[seriesIndex%palette.size]
                    stream.setStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
                    stream.setNonStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
                    stream.setLineWidth(series.strokeWidthPt ?: 1.3f)
                    var prev: Pair<Float,Float>? = null
                    for (index in indices) {
                        checkpoint()
                        val x = series.xValues.getOrNull(index)
                        val y = series.values.getOrNull(index)
                        if (x == null || y == null) { prev = null; continue }
                        val px = left+xAxis.fraction(x)*width
                        val py = bottom+yAxis.fraction(y)*height
                        if (chart.scatterStyle != "marker") prev?.let {
                            stream.moveTo(it.first,it.second); stream.lineTo(px,py); stream.stroke()
                        }
                        if (chart.scatterStyle != "line") {
                            stream.addRect(px-2f,py-2f,4f,4f); stream.fill()
                        }
                        prev = px to py
                    }
                    textAt(series.name.take(15),30f+seriesIndex*125f,238f,8f)
                }
                for ((local,index) in indices.withIndex()) {
                    val y = 215f - local*14f
                    textAt("Point " + (index+1),30f,y,8f)
                    for ((j,series) in chart.series.withIndex()) {
                        val x = series.xValues.getOrNull(index)
                        val v = series.values.getOrNull(index)
                        val pair = if (x == null || v == null) "(missing)" else
                            String.format(java.util.Locale.US,"%.3g, %.3g",x,v)
                        textAt(pair,150f+j*110f,y,8f)
                    }
                }
                textAt("Paired coordinates; gaps are not interpolated. Source axes and styling may differ.",30f,28f,8f)
            }
        }
    }

    /** Render a single complete pie; splitting the denominator across pages would mislead. */
    private fun renderCachedPieChart(pdf: PDDocument, sheet: String, chart: XlsxCachedChart, checkpoint: () -> Unit) {
        checkpoint()
        val values = chart.series.single().values
        require(values.size in 1..12 && values.all { it == null || (it.isFinite() && it >= 0.0) })
        val max = values.filterNotNull().maxOrNull() ?: 0.0
        require(max > 0.0)
        val total = values.sumOf { (it ?: 0.0) / max }
        val colors = intArrayOf(0x2463A0, 0xD15A36, 0x36845B, 0x8354AA, 0xA47715, 0x247D82,
            0xAA4167, 0x6D7C35, 0x5264B4, 0x9B653C, 0x557D83, 0x777777)
        require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
        val page = PDPage(PDRectangle(842f, 595f))
        pdf.addPage(page)
        PDPageContentStream(pdf, page).use { stream ->
            val font = PDType1Font.HELVETICA
            fun label(text: String, x: Float, y: Float, size: Float = 10f) {
                stream.setNonStrokingColor(0,0,0)
                stream.beginText(); stream.setFont(font,size); stream.newLineAtOffset(x,y)
                safeShowText(stream,text,font); stream.endText()
            }
            fun color(rgb: Int) = stream.setNonStrokingColor(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
            label(sheet.take(70) + " / Cached pie chart", 30f,565f,12f)
            label(chart.title.take(85),30f,543f,14f)
            label("Saved chart cache; reconstructed appearance, not recalculated",30f,523f)
            val cx = 225f; val cy = 300f; val radius = 170f
            var angle = Math.PI / 2
            for ((i,v) in values.withIndex()) {
                checkpoint()
                val share = if (v == null) 0.0 else (v / max) / total
                if (share > 0.0) {
                    val sweep = -2 * Math.PI * share
                    val segments = kotlin.math.ceil(kotlin.math.abs(sweep) / (Math.PI / 2)).toInt().coerceAtLeast(1)
                    val delta = sweep / segments
                    color(colors[i])
                    stream.moveTo(cx,cy)
                    stream.lineTo(cx + radius * kotlin.math.cos(angle).toFloat(), cy + radius * kotlin.math.sin(angle).toFloat())
                    repeat(segments) {
                        val end = angle + delta
                        val k = (4.0 / 3.0 * kotlin.math.tan(delta / 4.0)).toFloat()
                        val x0 = kotlin.math.cos(angle).toFloat(); val y0 = kotlin.math.sin(angle).toFloat()
                        val x1 = kotlin.math.cos(end).toFloat(); val y1 = kotlin.math.sin(end).toFloat()
                        stream.curveTo(cx + radius*(x0-k*y0), cy + radius*(y0+k*x0),
                            cx + radius*(x1+k*y1), cy + radius*(y1-k*x1),
                            cx + radius*x1, cy + radius*y1)
                        angle = end
                    }
                    stream.closePath(); stream.fill()
                }
                val y = 481f - i * 26f
                color(colors[i]); stream.addRect(430f,y-3f,10f,10f); stream.fill()
                label((i+1).toString() + " " + (chart.categories[i] ?: "(missing category)").take(27),447f,y)
                label(v?.let { String.format(java.util.Locale.US,"%.7g",it) } ?: "(missing)",650f,y,9f)
                label(if (v == null) "-" else String.format(java.util.Locale.US,"%.2f%%",share*100),750f,y,9f)
            }
            label("Shares are relative to nonnegative saved values; missing values are excluded.",30f,60f,9f)
            label("Category labels and values abbreviated for display.",30f,44f,9f)
        }
    }

    private fun renderNaturalOrExtendedSheet(
        pdf: PDDocument,
        plan: ExportPlan,
        fontNormal: PDType1Font,
        fontBold: PDType1Font,
        limits: LayoutLimits,
        isExtended: Boolean,
        checkpoint: () -> Unit
    ) {
        val layout = plan.sheetLayout
        val mergeIndex = SpreadsheetMergeIndex(layout.rowCount, layout.colCount,
            layout.mergedGeometries.map { it.range })
        fun addPage(page: PDPage) {
            checkpoint()
            require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            pdf.addPage(page)
        }
        val page = PDPage(PDRectangle(plan.pageWidth, plan.pageHeight))
        if (isExtended && plan.userUnit > 1f) page.setUserUnit(plan.userUnit)
        addPage(page)
        PDPageContentStream(pdf, page).use { stream ->
            if (isExtended && plan.userUnit > 1f) {
                stream.transform(Matrix.getScaleInstance(1f / plan.userUnit, 1f / plan.userUnit))
            }
            val naturalHeight = layout.naturalPageHeight
            stream.beginText()
            stream.setFont(fontBold, 12f)
            stream.newLineAtOffset(limits.margin, naturalHeight - limits.margin + 12f)
            safeShowText(stream, "Spreadsheet: ${layout.name}", fontBold)
            stream.endText()
            val tableTop = naturalHeight - limits.margin - 20f
            fun rowTop(row: Int) = tableTop - layout.rowY[row]
            for (r in 0 until layout.rowCount) {
                checkpoint()
                val top = rowTop(r)
                val height = layout.rowHeights[r]
                require(top - height >= limits.margin - 0.1f) { "Natural worksheet exceeds page geometry" }
                for (c in 0 until layout.colCount) {
                    if (mergeIndex.at(r, c) >= 0) continue
                    drawCellChunk(stream, layout.colX[c], top, layout.colWidths[c], height,
                        layout.rowWrappedLines[r][c], fontNormal, limits.fontSizeCell, limits.lineHeight,
                        layout.cellStyles.at(r, c))
                }
            }
            for (geom in layout.mergedGeometries) {
                checkpoint()
                drawCellChunk(stream, layout.colX[geom.firstCol], rowTop(geom.firstRow),
                    geom.totalWidth, geom.totalHeight, geom.wrappedLines,
                    fontNormal, limits.fontSizeCell, limits.lineHeight,
                    layout.cellStyles.at(geom.firstRow, geom.firstCol))
            }
            renderSpreadsheetEdges(stream, layout, mergeIndex, 0 until layout.rowCount,
                (0 until layout.colCount).toList(), { rowTop(it) },
                { rowTop(it) - layout.rowHeights[it] }, { layout.colX[it] },
                true, true, checkpoint)
        }
    }

    private fun renderTiledSheet(
        pdf: PDDocument,
        plan: ExportPlan,
        fontNormal: PDType1Font,
        fontBold: PDType1Font,
        limits: LayoutLimits,
        checkpoint: () -> Unit
    ) {
        val layout = plan.sheetLayout
        val mergeIndex = SpreadsheetMergeIndex(layout.rowCount, layout.colCount,
            layout.mergedGeometries.map { it.range })
        val margin = limits.margin

        for (tile in plan.tiles) {
            checkpoint()
            require(pdf.numberOfPages < SpreadsheetGeometry.MAX_PAGES) { "Workbook exceeds PDF page budget" }
            val pageBounds = PDRectangle(tile.tilePageWidth, tile.tilePageHeight)
            val page = PDPage(pageBounds)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { contentStream ->

                val pageW = tile.tilePageWidth
                val pageH = tile.tilePageHeight

                // 1. Title Banner
                val startColRef = SpreadsheetLayoutEngine.colIndexToRef(tile.colGroup.startCol)
                val endColRef = SpreadsheetLayoutEngine.colIndexToRef(tile.colGroup.endCol)
                val startRowDisplay = tile.rowGroup.startRow + 1
                val endRowDisplay = tile.rowGroup.endRow + 1
                val panelNum = tile.colGroup.groupIndex + 1
                val totalPanels = tile.colGroup.totalGroups

                val headerText = "Spreadsheet: ${layout.name} | Rows $startRowDisplay–$endRowDisplay | Columns $startColRef–$endColRef | Panel $panelNum of $totalPanels"

                contentStream.beginText()
                contentStream.setFont(fontBold, 11f)
                contentStream.newLineAtOffset(margin, pageH - margin + 10f)
                safeShowText(contentStream, headerText, fontBold)
                contentStream.endText()

                // 2. Horizontal Continuation Banners
                val topOffset = margin + 14f
                if (tile.colGroup.hasLeftContinuation) {
                    val prevColRef = SpreadsheetLayoutEngine.colIndexToRef(tile.colGroup.startCol - 1)
                    contentStream.beginText()
                    contentStream.setFont(fontBold, 9f)
                    contentStream.newLineAtOffset(margin, pageH - topOffset)
                    safeShowText(contentStream, "Continued from $prevColRef <-", fontBold)
                    contentStream.endText()
                }
                if (tile.colGroup.hasRightContinuation) {
                    val nextColRef = SpreadsheetLayoutEngine.colIndexToRef(tile.colGroup.endCol + 1)
                    contentStream.beginText()
                    contentStream.setFont(fontBold, 9f)
                    val rightText = "Continues right -> $nextColRef"
                    val textW = try { fontBold.getStringWidth(rightText) / 1000f * 9f } catch (_: Exception) { rightText.length * 5f }
                    contentStream.newLineAtOffset(pageW - margin - textW, pageH - topOffset)
                    safeShowText(contentStream, rightText, fontBold)
                    contentStream.endText()
                }

                // 3. Down Continuation Banner
                if (tile.rowGroup.hasDownContinuation) {
                    val nextRowDisplay = tile.rowGroup.endRow + 2
                    contentStream.beginText()
                    contentStream.setFont(fontBold, 9f)
                    contentStream.newLineAtOffset(margin, margin - 14f)
                    safeShowText(contentStream, "Continues down v Row $nextRowDisplay", fontBold)
                    contentStream.endText()
                }

                // 4. Calculate Column Positions on this tile
                val colsToDraw = mutableListOf<Int>()
                if (tile.colGroup.frozenCols.isNotEmpty()) {
                    colsToDraw.addAll(tile.colGroup.frozenCols)
                }
                colsToDraw.addAll(tile.colGroup.activeCols)

                val tileColX = mutableMapOf<Int, Float>()
                var runningX = margin
                for (c in colsToDraw) {
                    tileColX[c] = runningX
                    runningX += layout.colWidths[c]
                }

                // 5. Calculate row vertical positions on this tile
                val rowsOnTile = (tile.rowGroup.startRow..tile.rowGroup.endRow).toList()
                val rowTopMap = mutableMapOf<Int, Float>()
                val rowBottomMap = mutableMapOf<Int, Float>()
                val rowHeightMap = mutableMapOf<Int, Float>()
                var yPosition = pageH - margin - 22f

                for (rIdx in rowsOnTile) {
                    val rowHeight = if (tile.rowGroup.linesInStartRow != -1) {
                        tile.rowGroup.groupTableHeight
                    } else {
                        layout.rowHeights[rIdx]
                    }
                    rowTopMap[rIdx] = yPosition
                    rowBottomMap[rIdx] = yPosition - rowHeight
                    rowHeightMap[rIdx] = rowHeight
                    yPosition -= rowHeight
                }

                // 6. Draw unmerged cells
                for (rIdx in rowsOnTile) {
                    checkpoint()
                    val cellTop = rowTopMap[rIdx]!!
                    val cellH = rowHeightMap[rIdx]!!
                    val rowLines = layout.rowWrappedLines[rIdx]

                    for (colIndex in colsToDraw) {
                        // Skip cells that belong to any merged region
                        if (mergeIndex.at(rIdx, colIndex) >= 0) {
                            continue
                        }

                        val cellLeft = tileColX[colIndex] ?: margin
                        val cellW = layout.colWidths[colIndex]
                        val fullLines = rowLines[colIndex]
                        val chunkLines = if (tile.rowGroup.linesInStartRow != -1) {
                            if (fullLines.isNotEmpty() && tile.rowGroup.startLineOfStartRow < fullLines.size) {
                                fullLines.subList(
                                    tile.rowGroup.startLineOfStartRow,
                                    minOf(fullLines.size, tile.rowGroup.startLineOfStartRow + tile.rowGroup.linesInStartRow)
                                )
                            } else {
                                emptyList()
                            }
                        } else {
                            fullLines
                        }

                        drawCellChunk(
                            contentStream = contentStream,
                            cellLeft = cellLeft,
                            cellTop = cellTop,
                            cellW = cellW,
                            cellH = cellH,
                            chunkLines = chunkLines,
                            fontNormal = fontNormal,
                            fontSizeCell = limits.fontSizeCell,
                            lineHeight = limits.lineHeight,
                            style = layout.cellStyles.at(rIdx, colIndex),
                            completeCell = tile.rowGroup.linesInStartRow == -1
                        )
                    }
                }

                // 7. Draw merged cell geometries that intersect this tile
                for (geom in layout.mergedGeometries) {
                    checkpoint()
                    if (geom.lastRow < tile.rowGroup.startRow || geom.firstRow > tile.rowGroup.endRow) continue
                    val visCols = colsToDraw.filter { it in geom.firstCol..geom.lastCol }
                    val visRows = rowsOnTile.filter { it in geom.firstRow..geom.lastRow }
                    if (visCols.isEmpty() || visRows.isEmpty()) continue

                    val sliceLeft = tileColX[visCols.first()]!!
                    val sliceRight = tileColX[visCols.last()]!! + layout.colWidths[visCols.last()]
                    val sliceWidth = sliceRight - sliceLeft
                    val sliceTop = rowTopMap[visRows.first()]!!
                    val sliceBottom = rowBottomMap[visRows.last()]!!
                    val sliceHeight = sliceTop - sliceBottom
                    val style = layout.cellStyles.at(geom.firstRow, geom.firstCol)
                    val font = style.font(fontNormal)
                    val cellSize = style.fontSizePt ?: limits.fontSizeCell
                    val cellLeading = limits.lineHeight * cellSize / limits.fontSizeCell
                    fillSpreadsheetCell(contentStream, sliceLeft, sliceTop, sliceWidth, sliceHeight, style)

                    val isLeftTrueEdge = (visCols.first() == geom.firstCol)
                    val isTopTrueEdge = (visRows.first() == geom.firstRow && tile.rowGroup.startLineOfStartRow == 0)

                    // Text rendering: only on anchor slice to prevent duplicate text across panels
                    val isAnchor = (isLeftTrueEdge && isTopTrueEdge && geom.firstCol in tile.colGroup.activeCols)
                    if (isAnchor && geom.wrappedLines.isNotEmpty() && style.textRotation != 0) {
                        drawCellChunk(contentStream, sliceLeft, sliceTop, sliceWidth, sliceHeight,
                            geom.wrappedLines, fontNormal, limits.fontSizeCell, limits.lineHeight, style)
                    } else if (isAnchor && geom.wrappedLines.isNotEmpty()) {
                        // Wrapping across the entire merge is invalid on a narrower
                        // anchor tile. Rewrap inside its actual visible rectangle.
                        val anchorLines = SpreadsheetLayoutEngine.wrapCellText(geom.text,
                            font, cellSize, (sliceWidth - 8f - (if (style.icon != null) 13f else 0f)).coerceAtLeast(1f))
                        val requiredHeight = 4f + cellSize +
                            (anchorLines.size - 1).coerceAtLeast(0) * cellLeading
                        require(requiredHeight <= sliceHeight - 1f) {
                            "Merged cell text does not fit its anchor tile; increase tile dimensions"
                        }
                        contentStream.saveGraphicsState()
                        contentStream.addRect(
                            sliceLeft + 1f,
                            sliceBottom + 1f,
                            (sliceWidth - 2f).coerceAtLeast(1f),
                            (sliceHeight - 2f).coerceAtLeast(1f)
                        )
                        contentStream.clip()

                        setSpreadsheetTextColor(contentStream, style)
                        val completeMerge = visRows.size == geom.lastRow - geom.firstRow + 1 &&
                            visCols.size == geom.lastCol - geom.firstCol + 1 && tile.rowGroup.linesInStartRow == -1
                        var textY = spreadsheetFirstBaseline(sliceTop, sliceHeight, cellSize,
                            cellLeading, anchorLines.size, if (completeMerge) style.vertical else "top")
                        for (lineText in anchorLines) {
                            contentStream.beginText()
                            contentStream.setFont(font, cellSize)
                            contentStream.newLineAtOffset(spreadsheetTextX(lineText, font, cellSize, sliceLeft, sliceWidth, style), textY)
                            safeShowText(contentStream, lineText, font)
                            contentStream.endText()
                            textY -= cellLeading
                        }
                        contentStream.restoreGraphicsState()
                    }
                }

                val endsRow = tile.rowGroup.linesInStartRow == -1 ||
                    tile.rowGroup.startLineOfStartRow + tile.rowGroup.linesInStartRow >=
                    maxOf(1, layout.rowWrappedLines[tile.rowGroup.endRow].maxOfOrNull { it.size } ?: 1)
                renderSpreadsheetEdges(contentStream, layout, mergeIndex,
                    tile.rowGroup.startRow..tile.rowGroup.endRow, colsToDraw,
                    { rowTopMap.getValue(it) }, { rowBottomMap.getValue(it) },
                    { tileColX.getValue(it) }, tile.rowGroup.startLineOfStartRow == 0,
                    endsRow, checkpoint)
            }
        }
    }

    private fun drawCellChunk(
        contentStream: PDPageContentStream?,
        cellLeft: Float,
        cellTop: Float,
        cellW: Float,
        cellH: Float,
        chunkLines: List<String>,
        fontNormal: PDFont,
        fontSizeCell: Float,
        lineHeight: Float,
        style: XlsxCellStyle,
        completeCell: Boolean = true
    ) {
        if (contentStream == null) return
        val font = style.font(fontNormal)
        val cellSize = style.fontSizePt ?: fontSizeCell
        val cellLeading = lineHeight * cellSize / fontSizeCell
        fillSpreadsheetCell(contentStream, cellLeft, cellTop, cellW, cellH, style)

        if (style.dataBar?.showValue == false || style.icon?.showValue == false) return
        if (style.textRotation != 0 && chunkLines.isNotEmpty()) {
            require(completeCell) { "Cannot split rotated cell text across row fragments" }
            drawRotatedSpreadsheetText(contentStream, cellLeft, cellTop, cellW, cellH,
                chunkLines, font, cellSize, cellLeading, style)
            return
        }

        // 2. Draw cell string values safely clipped within cell boundaries
        if (chunkLines.isNotEmpty()) {
            contentStream.saveGraphicsState()
            contentStream.addRect(
                cellLeft + 1f,
                cellTop - cellH + 1f,
                (cellW - 2f).coerceAtLeast(1f),
                (cellH - 2f).coerceAtLeast(1f)
            )
            contentStream.clip()

            setSpreadsheetTextColor(contentStream, style)
            var textY = spreadsheetFirstBaseline(cellTop, cellH, cellSize, cellLeading,
                chunkLines.size, if (completeCell) style.vertical else "top")
            for (lineText in chunkLines) {
                contentStream.beginText()
                contentStream.setFont(font, cellSize)
                contentStream.newLineAtOffset(spreadsheetTextX(lineText, font, cellSize, cellLeft, cellW, style), textY)
                safeShowText(contentStream, lineText, font)
                contentStream.endText()
                textY -= cellLeading
            }
            contentStream.restoreGraphicsState()
        }
    }

    private fun drawRotatedSpreadsheetText(stream: PDPageContentStream, left: Float, top: Float,
        width: Float, height: Float, lines: List<String>, font: PDFont, size: Float,
        lineHeight: Float, style: XlsxCellStyle) {
        val block = XlsxRotatedText.measureLines(lines, style.textRotation, font, size, lineHeight)
        require(block.width <= width - 8f + 0.05f && block.height <= height - 8f + 0.05f) {
            "Rotated cell text does not fit its visible rectangle; increase tile dimensions"
        }
        val xSpare = (width - 8f - block.width).coerceAtLeast(0f)
        val ySpare = (height - 8f - block.height).coerceAtLeast(0f)
        val x = left + 4f + when (style.horizontal) { "center" -> xSpare / 2f; "right" -> xSpare; else -> 0f }
        val y = top - 4f - block.height - when (style.vertical) { "center" -> ySpare / 2f; "bottom" -> ySpare; else -> 0f }
        stream.saveGraphicsState()
        try {
            stream.transform(Matrix(block.cosine, block.sine, -block.sine, block.cosine,
                x - block.minX, y - block.minY))
            setSpreadsheetTextColor(stream, style)
            var baseline = block.unrotatedHeight - size
            for (line in lines) {
                stream.beginText()
                stream.setFont(font, size)
                stream.newLineAtOffset(spreadsheetTextX(line, font, size, -4f, block.unrotatedWidth + 8f, style), baseline)
                safeShowText(stream, line, font)
                stream.endText()
                baseline -= lineHeight
            }
        } finally { stream.restoreGraphicsState() }
    }

    /** Overlay shared edges only after all fills/text for this page are complete. */
    private fun renderSpreadsheetEdges(
        stream: PDPageContentStream, layout: SheetLayout, mergeIndex: SpreadsheetMergeIndex,
        rows: Iterable<Int>, cols: List<Int>, top: (Int) -> Float, bottom: (Int) -> Float,
        left: (Int) -> Float, startsRow: Boolean, endsRow: Boolean, checkpoint: () -> Unit
    ) {
        val visibleRows = rows.toList()
        if (visibleRows.isEmpty() || cols.isEmpty()) return
        val resolver = SpreadsheetBorderResolver(layout, mergeIndex)
        var previous: XlsxBorderLine? = null
        stream.saveGraphicsState()
        try {
            fun segment(line: XlsxBorderLine?, x1: Float, y1: Float, x2: Float, y2: Float) {
                if (line == null) return
                if (previous != line) {
                    stream.setStrokingColor(line.rgb ushr 16 and 255, line.rgb ushr 8 and 255, line.rgb and 255)
                    stream.setLineWidth(if (line.kind == "double") 0.75f else line.width)
                    stream.setLineDashPattern(line.dash(), 0f)
                    previous = line
                }
                if (line.kind == "double") {
                    val dx = if (x1 == x2) 0.85f else 0f
                    val dy = if (y1 == y2) 0.85f else 0f
                    stream.moveTo(x1 - dx, y1 - dy); stream.lineTo(x2 - dx, y2 - dy)
                    stream.moveTo(x1 + dx, y1 + dy); stream.lineTo(x2 + dx, y2 + dy)
                } else {
                    stream.moveTo(x1, y1); stream.lineTo(x2, y2)
                }
                stream.stroke()
            }
            // Each horizontal boundary is emitted once (top of each row;
            // bottom of the final row). Split text-row fragments have no fake edges.
            for ((rowIndex,r) in visibleRows.withIndex()) {
                checkpoint()
                if (rowIndex != 0 || startsRow) for (c in cols) {
                    val prior = visibleRows.getOrNull(rowIndex - 1) ?: (r - 1)
                    segment(resolver.horizontalBetween(prior, r, c), left(c), top(r),
                        left(c) + layout.colWidths[c], top(r))
                }
                if (rowIndex == visibleRows.lastIndex && endsRow) for (c in cols) {
                    segment(resolver.horizontal(r + 1, c), left(c), bottom(r),
                        left(c) + layout.colWidths[c], bottom(r))
                }
                for ((i, c) in cols.withIndex()) {
                    val prior = cols.getOrNull(i - 1) ?: (c - 1).takeIf { it >= 0 }
                    segment(resolver.vertical(r, prior, c), left(c), top(r), left(c), bottom(r))
                }
                val final = cols.last()
                val x = left(final) + layout.colWidths[final]
                segment(resolver.vertical(r, final, (final + 1).takeIf { it < layout.colCount }),
                    x, top(r), x, bottom(r))
            }
        } finally { stream.restoreGraphicsState() }
    }

    private fun fillSpreadsheetCell(stream: PDPageContentStream?, left: Float, top: Float,
                                    width: Float, height: Float, style: XlsxCellStyle) {
        if (stream == null) return
        style.fillRgb?.let { rgb ->
            stream.saveGraphicsState()
            try {
                stream.setNonStrokingColor(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
                stream.addRect(left, top - height, width, height)
                stream.fill()
            } finally { stream.restoreGraphicsState() }
        }
        style.dataBar?.let { bar ->
            val available = (width - 8f).coerceAtLeast(0f)
            val x1 = left + 4f + minOf(bar.start,bar.end)*available
            val x2 = left + 4f + maxOf(bar.start,bar.end)*available
            if (x2 > x1 && height > 6f) {
                stream.saveGraphicsState()
                try {
                    stream.setNonStrokingColor(bar.rgb ushr 16 and 255,bar.rgb ushr 8 and 255,bar.rgb and 255)
                    stream.addRect(x1, top-height+3f, x2-x1, height-6f)
                    stream.fill()
                } finally { stream.restoreGraphicsState() }
            }
        }
        style.icon?.let { icon ->
            if (width < 14f || height < 12f) return@let
            val cx = left + 8f; val cy = top - height/2f; val radius = 4f
            val k = radius * 0.55228475f
            stream.saveGraphicsState()
            try {
                stream.setNonStrokingColor(icon.rgb ushr 16 and 255,icon.rgb ushr 8 and 255,icon.rgb and 255)
                when (icon.shape) {
                    "up" -> {
                        stream.moveTo(cx-2f,cy-4f); stream.lineTo(cx+2f,cy-4f)
                        stream.lineTo(cx+2f,cy); stream.lineTo(cx+4f,cy)
                        stream.lineTo(cx,cy+4f); stream.lineTo(cx-4f,cy)
                        stream.lineTo(cx-2f,cy)
                    }
                    "down" -> {
                        stream.moveTo(cx-2f,cy+4f); stream.lineTo(cx+2f,cy+4f)
                        stream.lineTo(cx+2f,cy); stream.lineTo(cx+4f,cy)
                        stream.lineTo(cx,cy-4f); stream.lineTo(cx-4f,cy)
                        stream.lineTo(cx-2f,cy)
                    }
                    "right" -> {
                        stream.moveTo(cx-4f,cy-2f); stream.lineTo(cx,cy-2f)
                        stream.lineTo(cx,cy-4f); stream.lineTo(cx+4f,cy)
                        stream.lineTo(cx,cy+4f); stream.lineTo(cx,cy+2f)
                        stream.lineTo(cx-4f,cy+2f)
                    }
                    else -> {
                        stream.moveTo(cx+radius,cy)
                        stream.curveTo(cx+radius,cy+k,cx+k,cy+radius,cx,cy+radius)
                        stream.curveTo(cx-k,cy+radius,cx-radius,cy+k,cx-radius,cy)
                        stream.curveTo(cx-radius,cy-k,cx-k,cy-radius,cx,cy-radius)
                        stream.curveTo(cx+k,cy-radius,cx+radius,cy-k,cx+radius,cy)
                    }
                }
                stream.closePath(); stream.fill()
            } finally { stream.restoreGraphicsState() }
        }
    }

    private fun setSpreadsheetTextColor(stream: PDPageContentStream?, style: XlsxCellStyle) {
        val rgb = style.textRgb ?: 0
        stream?.setNonStrokingColor(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
    }

    // Lay out the complete wrapped text block, preserving symmetric padding.
    // Fragmented rows/merges deliberately remain top-aligned on each panel.
    private fun spreadsheetFirstBaseline(top: Float, height: Float, size: Float,
                                         lineHeight: Float, lines: Int, vertical: String): Float {
        val textHeight = size + (lines - 1).coerceAtLeast(0) * lineHeight
        val spare = (height - 8f - textHeight).coerceAtLeast(0f)
        val offset = when (vertical) {
            "center" -> spare / 2f
            "bottom" -> spare
            else -> 0f
        }
        return top - 4f - size - offset
    }

    private fun spreadsheetTextX(text: String, font: PDFont, size: Float, left: Float,
                                 width: Float, style: XlsxCellStyle): Float {
        if (style.horizontal == "left") return left + if (style.icon != null) 17f else 4f
        val textWidth = try { font.getStringWidth(text) / 1000f * size }
            catch (_: Exception) { return left + 4f }
        val offset = if (style.horizontal == "center") (width - textWidth) / 2f
            else width - textWidth - 4f
        return left + offset.coerceAtLeast(4f)
    }

    data class PicCrop(val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f)

    private fun extractBlipCrop(xml: Any): PicCrop? {
        val xmlStr = try { xml.toString() } catch (_: Throwable) { "" }
        if (xmlStr.isBlank()) return null
        val match = Regex("""<[^>]*srcRect[^>]*>""", RegexOption.IGNORE_CASE).find(xmlStr)
        if (match != null) {
            val tag = match.value
            fun getAttr(attr: String): Float {
                val m = Regex("""$attr=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(tag)
                val v = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return (v.toFloat() / 100000f).coerceIn(0f, 0.99f)
            }
            val l = getAttr("l")
            val t = getAttr("t")
            val r = getAttr("r")
            val b = getAttr("b")
            if (l > 0f || t > 0f || r > 0f || b > 0f) {
                return PicCrop(l, t, r, b)
            }
        }
        return null
    }

    private fun extractBlipEmbedId(xml: Any): String? {
        val xmlStr = try { xml.toString() } catch (_: Throwable) { "" }
        if (xmlStr.isNotBlank()) {
            val svgMatch = Regex("""(?:asvg:svgBlip|svgBlip)[^>]*(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (svgMatch != null) return svgMatch.groupValues[1]
        }
        try {
            var blipFill: Any? = null
            try { blipFill = xml.javaClass.getMethod("getBlipFill").invoke(xml) } catch (_: Throwable) {}
            if (blipFill == null) {
                try {
                    val spPr = xml.javaClass.getMethod("getSpPr").invoke(xml)
                    if (spPr != null) blipFill = spPr.javaClass.getMethod("getBlipFill").invoke(spPr)
                } catch (_: Throwable) {}
            }
            if (blipFill != null) {
                val blip = try { blipFill.javaClass.getMethod("getBlip").invoke(blipFill) } catch (_: Throwable) { null }
                if (blip != null) {
                    val blipXmlStr = try { blip.toString() } catch (_: Throwable) { "" }
                    if (blipXmlStr.isNotBlank()) {
                        val svgMatch = Regex("""(?:asvg:svgBlip|svgBlip)[^>]*(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(blipXmlStr)
                        if (svgMatch != null) return svgMatch.groupValues[1]
                    }
                    val embed = try { blip.javaClass.getMethod("getEmbed").invoke(blip) as? String } catch (_: Throwable) { null }
                    if (!embed.isNullOrBlank()) return embed
                }
            }
        } catch (_: Throwable) { }
        if (xmlStr.isNotBlank()) {
            val match = Regex("""(?:r:embed|embed|r:link|link)=["'](rId\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun resolvePictureBytesFromBlipId(slide: Any, blipId: String): Pair<ByteArray, String?>? {
        if (slide !is XSLFSlide) return null
        try {
            val relDoc = slide.getRelationById(blipId)
            if (relDoc is XSLFPictureData) {
                return Pair(relDoc.data, relDoc.contentType)
            }
        } catch (_: Throwable) { }
        try {
            val sheetPart = slide.packagePart
            val rel = sheetPart?.getRelationship(blipId)
            if (rel != null) {
                val part = sheetPart.getRelatedPart(rel) ?: sheetPart.getPackage()?.getPart(rel)
                val bytes = part?.inputStream?.use { stream -> stream.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    return Pair(bytes, part.contentType)
                }
            }
        } catch (_: Throwable) { }
        try {
            val slideShow = slide.slideShow
            for (pd in slideShow.pictureData) {
                if (pd.packagePart?.partName?.name?.contains(blipId, ignoreCase = true) == true) {
                    return Pair(pd.data, pd.contentType)
                }
            }
        } catch (_: Throwable) { }
        return null
    }

    private fun extractPictureDataFromShape(shape: Any, slide: Any): Triple<ByteArray, String?, PicCrop?>? {
        try {
            val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) {
                try {
                    val m = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    m.isAccessible = true
                    m.invoke(shape)
                } catch (_: Throwable) { null }
            }
            if (xml != null) {
                val crop = extractBlipCrop(xml)
                val blipId = extractBlipEmbedId(xml)
                if (!blipId.isNullOrBlank()) {
                    val resolved = resolvePictureBytesFromBlipId(slide, blipId)
                    if (resolved != null) return Triple(resolved.first, resolved.second, crop)
                }
            }
        } catch (_: Throwable) { }

        if (shape is XSLFPictureShape) {
            try {
                val pd = shape.pictureData
                val data = pd?.data
                if (data != null && data.isNotEmpty()) {
                    val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
                    val crop = if (xml != null) extractBlipCrop(xml) else null
                    return Triple(data, pd.contentType, crop)
                }
            } catch (_: Throwable) { }
        }

        try {
            val method = shape.javaClass.getMethod("getPictureData")
            val pd = method.invoke(shape)
            if (pd != null) {
                val data = pd.javaClass.getMethod("getData").invoke(pd) as? ByteArray
                if (data != null && data.isNotEmpty()) {
                    val ct = try { pd.javaClass.getMethod("getContentType").invoke(pd) as? String } catch (_: Throwable) { null }
                    val xml = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
                    val crop = if (xml != null) extractBlipCrop(xml) else null
                    return Triple(data, ct, crop)
                }
            }
        } catch (_: Throwable) { }

        return null
    }

    private fun extractThemeColorScheme(slide: Any?): Map<String, String> {
        val colorMap = mutableMapOf<String, String>()
        if (slide !is XSLFSlide) return colorMap
        try {
            val theme = slide.theme
            if (theme != null) {
                val ctTheme = try { theme.javaClass.getMethod("getXmlObject").invoke(theme) } catch (_: Throwable) { null }
                val xmlStr = ctTheme?.toString() ?: ""
                val clrSchemeMatch = Regex("""<[^>]*clrScheme[^>]*name=["']([^"']*)["'][^>]*>(.*?)</[^>]*clrScheme>""", RegexOption.DOT_MATCHES_ALL).find(xmlStr)
                val schemeContent = clrSchemeMatch?.groupValues?.get(2) ?: xmlStr

                val schemeColorTags = listOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
                for (tag in schemeColorTags) {
                    val tagMatch = Regex("""<a:$tag>\s*<a:srgbClr\s+val=["']([0-9A-Fa-f]{6})["']""", RegexOption.IGNORE_CASE).find(schemeContent)
                    if (tagMatch != null) {
                        colorMap[tag] = "#${tagMatch.groupValues[1]}"
                    } else {
                        val sysMatch = Regex("""<a:$tag>\s*<a:sysClr\s+[^>]*lastClr=["']([0-9A-Fa-f]{6})["']""", RegexOption.IGNORE_CASE).find(schemeContent)
                        if (sysMatch != null) {
                            colorMap[tag] = "#${sysMatch.groupValues[1]}"
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
        return colorMap
    }

    private fun extractRunColor(run: Any?, defaultColor: Int): Int {
        if (run == null) return defaultColor
        try {
            val xmlRun = try {
                run.javaClass.getMethod("getXmlObject").invoke(run)
            } catch (t: Throwable) {
                try {
                    val m = run.javaClass.getDeclaredMethod("fetchXmlObject")
                    m.isAccessible = true
                    m.invoke(run)
                } catch (t2: Throwable) { null }
            }
            if (xmlRun != null) {
                val rPr = try { xmlRun.javaClass.getMethod("getRPr").invoke(xmlRun) } catch (t: Throwable) { null }
                if (rPr != null) {
                    val solidFill = try { rPr.javaClass.getMethod("getSolidFill").invoke(rPr) } catch (t: Throwable) { null }
                    if (solidFill != null) {
                        val srgb = try { solidFill.javaClass.getMethod("getSrgbClr").invoke(solidFill) } catch (t: Throwable) { null }
                        if (srgb != null) {
                            val hexBytes = try { srgb.javaClass.getMethod("getVal").invoke(srgb) as? ByteArray } catch (t: Throwable) { null }
                            if (hexBytes != null && hexBytes.size >= 3) {
                                return android.graphics.Color.rgb(
                                    hexBytes[0].toInt() and 0xFF,
                                    hexBytes[1].toInt() and 0xFF,
                                    hexBytes[2].toInt() and 0xFF
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) { }
        return defaultColor
    }

    private fun cropAndDrawBitmap(canvas: android.graphics.Canvas, dataBytes: ByteArray, crop: PicCrop?, destRect: android.graphics.Rect) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(dataBytes, 0, dataBytes.size) ?: return
            val origW = bitmap.width
            val origH = bitmap.height
            val finalBmp = if (crop != null && (crop.l > 0f || crop.t > 0f || crop.r > 0f || crop.b > 0f)) {
                val startX = (crop.l * origW).toInt().coerceIn(0, origW - 1)
                val startY = (crop.t * origH).toInt().coerceIn(0, origH - 1)
                val cropW = ((1f - crop.l - crop.r) * origW).toInt().coerceIn(1, origW - startX)
                val cropH = ((1f - crop.t - crop.b) * origH).toInt().coerceIn(1, origH - startY)
                val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else {
                bitmap
            }
            canvas.drawBitmap(finalBmp, null, destRect, null)
            finalBmp.recycle()
        } catch (_: Throwable) { }
    }

    /**
     * Renders a PowerPoint PPTX file to high-resolution bitmaps with full layout, image, and typography fidelity.
     * Returns a list of Bitmaps, one per slide.
     */
    suspend fun renderPptxToBitmaps(pptxFile: File, targetWidth: Int = 1920): List<Bitmap> =
        renderPptxSlides(pptxFile, targetWidth, null)

    // The conversion path consumes each owned bitmap synchronously and never retains a slide-sized batch.
    private suspend fun renderPptxSlides(
        pptxFile: File,
        targetWidth: Int,
        consume: ((Bitmap) -> Unit)?
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        var pptxStream: FileInputStream? = null
        var ppt: XMLSlideShow? = null
        val bitmaps = mutableListOf<Bitmap>()
        var pending: Bitmap? = null

        try {
            require(targetWidth in 1..4096) { "Invalid slide raster width" }
            pptxStream = FileInputStream(pptxFile)
            ppt = XMLSlideShow(pptxStream)

            val slideDimEmu = getSlideDimensionsEmu(ppt)
            val slideWidthEmu = slideDimEmu.first
            val slideHeightEmu = slideDimEmu.second

            // Calculate target height maintaining exact aspect ratio
            val targetHeightLong = if (slideWidthEmu > 0 && slideHeightEmu > 0)
                targetWidth.toDouble() * slideHeightEmu / slideWidthEmu else targetWidth * 9.0 / 16
            require(targetHeightLong.isFinite() && targetHeightLong in 1.0..8192.0) { "Slide dimensions exceed raster limits" }
            val targetHeight = targetHeightLong.toInt()
            val pixels = targetWidth.toLong() * targetHeight
            require(pixels <= 16_777_216L) { "Slide raster exceeds pixel budget" }
            require(ppt.slides.isNotEmpty()) { "Presentation contains no slides" }
            if (consume == null) {
                require(ppt.slides.size.toLong() * pixels <= 32_000_000L) {
                    "Slide batch exceeds bitmap memory budget; export PDF instead"
                }
            }
            val slideWidthPt = if (slideWidthEmu > 0) (slideWidthEmu / 12700f) else 720f
            val fontScale = targetWidth.toFloat() / slideWidthPt

            val themeColors = if (ppt.slides.isNotEmpty()) extractThemeColorScheme(ppt.slides[0]) else emptyMap()

            fun resolveColor(schemeOrHex: String?, fallback: Int): Int {
                if (schemeOrHex == null) return fallback
                if (schemeOrHex.startsWith("#")) {
                    return try { android.graphics.Color.parseColor(schemeOrHex) } catch (_: Throwable) { fallback }
                }
                val mapped = themeColors[schemeOrHex]
                if (mapped != null) {
                    return try { android.graphics.Color.parseColor(mapped) } catch (_: Throwable) { fallback }
                }
                return when (schemeOrHex.lowercase()) {
                    "tx1", "dk1" -> android.graphics.Color.rgb(15, 23, 42)
                    "tx2", "dk2" -> android.graphics.Color.rgb(30, 41, 59)
                    "bg1", "lt1" -> android.graphics.Color.WHITE
                    "bg2", "lt2" -> android.graphics.Color.rgb(248, 250, 252)
                    "accent1" -> android.graphics.Color.rgb(37, 99, 235)
                    else -> fallback
                }
            }

            for ((slideIndex, slide) in ppt.slides.withIndex()) {
                currentCoroutineContext().ensureActive()
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                pending = bitmap
                val canvas = android.graphics.Canvas(bitmap) // isRecycled checked above
                canvas.drawColor(android.graphics.Color.WHITE)

                // 1. Slide Background Color
                val bgColorHex = getSlideBgColor(slide)
                if (bgColorHex != null) {
                    try {
                        canvas.drawColor(android.graphics.Color.parseColor(bgColorHex))
                    } catch (_: Throwable) { }
                }

                // Background picture if present
                try {
                    val ctSlide = try { slide.javaClass.getMethod("getXmlObject").invoke(slide) } catch (_: Throwable) { null }
                    if (ctSlide != null) {
                        val cSld = try { ctSlide.javaClass.getMethod("getCSld").invoke(ctSlide) } catch (_: Throwable) { null }
                        val bg = try { cSld?.javaClass?.getMethod("getBg")?.invoke(cSld) } catch (_: Throwable) { null }
                        if (bg != null) {
                            val bgBlipId = extractBlipEmbedId(bg)
                            if (!bgBlipId.isNullOrBlank()) {
                                val resolved = resolvePictureBytesFromBlipId(slide, bgBlipId)
                                if (resolved != null && resolved.first.isNotEmpty()) {
                                    cropAndDrawBitmap(canvas, resolved.first, null, android.graphics.Rect(0, 0, targetWidth, targetHeight))
                                }
                            }
                        }
                    }
                } catch (_: Throwable) { }

                // 2. Flatten all shapes (including nested group children and Slide Master / Layout background shapes)
                val allSlideShapes = mutableListOf<org.apache.poi.xslf.usermodel.XSLFShape>()
                fun collectBitmapShapes(shapes: List<org.apache.poi.xslf.usermodel.XSLFShape>, isMasterOrLayout: Boolean = false) {
                    for (s in shapes) {
                        if (isMasterOrLayout && s is XSLFSimpleShape && s.isPlaceholder) {
                            // Skip master/layout text placeholders so placeholder prompt text doesn't render
                            continue
                        }
                        if (s is XSLFGroupShape) {
                            try { collectBitmapShapes(s.shapes, isMasterOrLayout) } catch (_: Throwable) { }
                        } else {
                            allSlideShapes.add(s)
                        }
                    }
                }
                val showMaster = try {
                    (slide.javaClass.getMethod("getDisplayMasterShapes").invoke(slide) as? Boolean) ?: true
                } catch (_: Throwable) { true }
                if (showMaster) {
                    try {
                        val masterShapes = slide.slideLayout?.slideMaster?.shapes ?: emptyList()
                        collectBitmapShapes(masterShapes, isMasterOrLayout = true)
                    } catch (_: Throwable) { }
                    try {
                        val layoutShapes = slide.slideLayout?.shapes ?: emptyList()
                        collectBitmapShapes(layoutShapes, isMasterOrLayout = true)
                    } catch (_: Throwable) { }
                }
                try { collectBitmapShapes(slide.shapes, isMasterOrLayout = false) } catch (_: Throwable) { }

                // 3. Draw all shapes in z-order
                for (shape in allSlideShapes) {
                    val normBounds = getShapeNormalizedBounds(shape, slide, slideWidthEmu, slideHeightEmu)

                    // Priority: PictureShape / blipFill
                    val picTriple = extractPictureDataFromShape(shape, slide)
                    if (picTriple != null && picTriple.first.isNotEmpty()) {
                        val fb = normBounds ?: floatArrayOf(0.05f, 0.3f, 0.6f, 0.4f)
                        val destRect = android.graphics.Rect(
                            (fb[0] * targetWidth).toInt(), (fb[1] * targetHeight).toInt(),
                            ((fb[0] + fb[2]) * targetWidth).toInt(), ((fb[1] + fb[3]) * targetHeight).toInt()
                        )
                        cropAndDrawBitmap(canvas, picTriple.first, picTriple.third, destRect)
                        continue
                    }

                    if (normBounds == null) continue
                    val px = normBounds[0] * targetWidth.toFloat()
                    val py = normBounds[1] * targetHeight.toFloat()
                    val pw = normBounds[2] * targetWidth.toFloat()
                    val ph = normBounds[3] * targetHeight.toFloat()

                    val geomType = getShapeGeometryType(shape)

                    // Draw geometry fill & stroke
                    if (shape is XSLFSimpleShape) {
                        val fillColor = getShapeFillColor(shape, themeColors)
                        if (fillColor != null) {
                            val fillPaint = Paint().apply { color = fillColor; style = Paint.Style.FILL; isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, fillPaint)
                        }
                        val lineColor = getShapeLineColor(shape, themeColors)
                        if (lineColor != null) {
                            val strokePaint = Paint().apply { color = lineColor; style = Paint.Style.STROKE; strokeWidth = 2f * (targetWidth / 960f); isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, strokePaint)
                        } else if (geomType == ShapeGeom.ELLIPSE) {
                            val strokePaint = Paint().apply { color = android.graphics.Color.rgb(30, 41, 59); style = Paint.Style.STROKE; strokeWidth = 1.5f * (targetWidth / 960f); isAntiAlias = true }
                            drawShapeGeometry(canvas, geomType, px, py, pw, ph, strokePaint)
                        }
                    }

                    // Draw text content
                    if (shape is XSLFTextShape) {
                        val paragraphs = try { shape.textParagraphs } catch (t: Throwable) { emptyList() }
                        val isTitle = try {
                            shape.isPlaceholder && (shape.textType == Placeholder.TITLE || shape.textType == Placeholder.CENTERED_TITLE)
                        } catch (_: Throwable) { shape.shapeName.contains("title", ignoreCase = true) }

                        val defaultTextColor = resolveColor(if (isTitle) "accent1" else "tx1", if (isTitle) android.graphics.Color.rgb(30, 58, 138) else android.graphics.Color.rgb(15, 23, 42))

                        if (paragraphs.isNotEmpty()) {
                            var curY = py
                            for (p in paragraphs) {
                                val pRuns = try { p.textRuns } catch (_: Throwable) { emptyList() }
                                val pText = try { pRuns.joinToString("") { it.rawText ?: "" } } catch (_: Throwable) { "" }
                                if (pText.isBlank()) continue

                                val isEllipseGeom = (geomType == ShapeGeom.ELLIPSE)
                                val maxFontPt = pRuns.mapNotNull {
                                    val fs = try { it.fontSize } catch (_: Throwable) { null }
                                    if (fs != null && fs > 0) fs.toFloat() else null
                                }.maxOrNull() ?: (if (isTitle) 26f else (if (isEllipseGeom) 12f else 14f))

                                val scaledFontSize = maxFontPt * fontScale
                                val runColor = extractRunColor(pRuns.firstOrNull(), defaultTextColor)

                                val textPaint = Paint().apply {
                                    color = runColor
                                    textSize = scaledFontSize
                                    isAntiAlias = true
                                    isFakeBoldText = pRuns.any { try { it.isBold } catch (_: Throwable) { false } } || isTitle
                                }

                                curY += textPaint.textSize + (4f * fontScale)

                                val bulletLevel = try { p.indentLevel } catch (_: Throwable) { 0 }
                                val hasBullet = bulletLevel > 0 || (paragraphs.size > 1 && !isTitle && !isEllipseGeom)
                                val bulletPrefix = if (hasBullet) "• " else ""
                                val indentOffset = (bulletLevel * 16f * fontScale)
                                val fullLine = bulletPrefix + pText.trim()
                                val maxLineWidth = (pw - 12f * fontScale - indentOffset).coerceAtLeast(50f)
                                val lines = wrapTextForCanvas(fullLine, textPaint, maxLineWidth)

                                for (line in lines) {
                                    if (curY <= py + ph + (20f * fontScale)) {
                                        val drawX = if (isEllipseGeom || (isTitle && pw >= targetWidth * 0.4f)) {
                                            (px + (pw - textPaint.measureText(line)) / 2f)
                                        } else {
                                            (px + 6f * fontScale + indentOffset)
                                        }
                                        canvas.drawText(line, drawX, curY, textPaint)
                                        curY += textPaint.textSize * 1.35f
                                    }
                                }
                                curY += 2f * fontScale
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (consume == null) {
                    bitmaps.add(bitmap)
                } else {
                    consume(bitmap)
                    bitmap.recycle()
                }
                pending = null
            }
        } catch (failure: Throwable) {
            pending?.takeUnless { it.isRecycled }?.recycle()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        } finally {
            try { ppt?.close() } catch (e: Exception) {}
            try { pptxStream?.close() } catch (e: Exception) {}
        }
        return@withContext bitmaps
    }

    /**
     * Converts a PPTX presentation file slide-by-slide to A4 PDF with high visual fidelity.
     */
    suspend fun convertPptxToPdf(pptxFile: File, pdfFile: File, renderMode: String, context: android.content.Context) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)

        var pdf: PDDocument? = null
        try {
            pdf = PDDocument()
            val document = pdf
            renderPptxSlides(pptxFile, targetWidth = 1920) { bitmap ->
                // Calculate page bounds matching the slide aspect ratio
                val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else (16f / 9f)
                val pageWidth = 842f // A4 landscape width points
                val pageHeight = (pageWidth / aspect)
                val pageBounds = PDRectangle(pageWidth, pageHeight)

                val page = PDPage(pageBounds)
                document.addPage(page)

                PDPageContentStream(document, page).use { contentStream ->
                    val pdImage = LosslessFactory.createFromImage(document, bitmap)
                    contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                }
            }

            publishOfficePdf(pdf, pdfFile)
        } finally {
            try { pdf?.close() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /**
     * Gets slide dimensions in EMUs without using java.awt.
     */
    private fun getSlideDimensionsEmu(ppt: XMLSlideShow): Pair<Long, Long> {
        return try {
            val ctPresentation = try {
                ppt.javaClass.getMethod("getCTPresentation").invoke(ppt)
            } catch (t: Throwable) { null }
            if (ctPresentation != null) {
                val sldSz = ctPresentation.javaClass.getMethod("getSldSz").invoke(ctPresentation)
                if (sldSz != null) {
                    val cx = (sldSz.javaClass.getMethod("getCx").invoke(sldSz) as? Number)?.toLong() ?: 9144000L
                    val cy = (sldSz.javaClass.getMethod("getCy").invoke(sldSz) as? Number)?.toLong() ?: 5143500L
                    return Pair(cx, cy)
                }
            }
            Pair(9144000L, 5143500L)
        } catch (t: Throwable) {
            Pair(9144000L, 5143500L)
        }
    }

    /**
     * Extracts shape bounds in EMU units directly from XML, avoiding java.awt dependencies.
     */
    private fun getShapeBoundsEmu(shape: Any): FloatArray? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (t: Throwable) {
                try {
                    val method = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    method.isAccessible = true
                    method.invoke(shape)
                } catch (t2: Throwable) { null }
            } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (t: Throwable) { null } ?: return null

            val xfrm = try {
                spPr.javaClass.getMethod("getXfrm").invoke(spPr)
            } catch (t: Throwable) { null } ?: return null

            val off = try { xfrm.javaClass.getMethod("getOff").invoke(xfrm) } catch (t: Throwable) { null }
            val ext = try { xfrm.javaClass.getMethod("getExt").invoke(xfrm) } catch (t: Throwable) { null }

            val x = (try { off?.javaClass?.getMethod("getX")?.invoke(off) as? Number } catch (t: Throwable) { null })?.toFloat()
            val y = (try { off?.javaClass?.getMethod("getY")?.invoke(off) as? Number } catch (t: Throwable) { null })?.toFloat()
            val cx = (try { ext?.javaClass?.getMethod("getCx")?.invoke(ext) as? Number } catch (t: Throwable) { null })?.toFloat()
            val cy = (try { ext?.javaClass?.getMethod("getCy")?.invoke(ext) as? Number } catch (t: Throwable) { null })?.toFloat()

            if (x != null && y != null && cx != null && cy != null) {
                floatArrayOf(x, y, cx, cy)
            } else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Extracts normalized shape bounds (left, top, width, height: 0.0f..1.0f)
     * using getAnchor() reflection with fallback to XMLBeans without java.awt compile dependencies.
     */
    private fun extractLongValueOC(obj: Any?): Long? {
        if (obj == null) return null
        if (obj is Number) return obj.toLong()
        try {
            val longValMethod = obj.javaClass.getMethod("getLongValue")
            val v = longValMethod.invoke(obj)
            if (v is Number) return v.toLong()
        } catch (_: Throwable) { }
        try {
            val str = obj.toString().trim()
            val num = str.toLongOrNull()
            if (num != null) return num
            val doubleNum = str.toDoubleOrNull()
            if (doubleNum != null) return doubleNum.toLong()
        } catch (_: Throwable) { }
        return null
    }

    private fun getShapeNormalizedBounds(
        shape: Any,
        slide: Any?,
        slideWidthEmu: Long,
        slideHeightEmu: Long
    ): FloatArray? {
        // Strategy 1: XMLBeans direct EMU extraction
        val directXml = getXmlShapeBoundsNormalized(shape, slideWidthEmu, slideHeightEmu)
        if (directXml != null) return directXml

        // Strategy 2: If placeholder, resolve from Slide Layout / Master
        if (slide is XSLFSlide && shape is XSLFShape) {
            try {
                val phDetails = try { shape.placeholderDetails } catch (_: Throwable) { null }
                val phType = phDetails?.placeholder
                if (phType != null) {
                    val layoutShapes = try { slide.slideLayout?.shapes } catch (_: Throwable) { emptyList() } ?: emptyList()
                    for (lShape in layoutShapes) {
                        if (lShape.placeholderDetails?.placeholder == phType) {
                            val lBounds = getXmlShapeBoundsNormalized(lShape, slideWidthEmu, slideHeightEmu)
                            if (lBounds != null) return lBounds
                        }
                    }
                    val masterShapes = try { slide.slideLayout?.slideMaster?.shapes } catch (_: Throwable) { emptyList() } ?: emptyList()
                    for (mShape in masterShapes) {
                        if (mShape.placeholderDetails?.placeholder == phType) {
                            val mBounds = getXmlShapeBoundsNormalized(mShape, slideWidthEmu, slideHeightEmu)
                            if (mBounds != null) return mBounds
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        // Strategy 3: Try getAnchor() via reflection
        try {
            val anchor = shape.javaClass.getMethod("getAnchor").invoke(shape)
            if (anchor != null) {
                val x = (anchor.javaClass.getMethod("getX").invoke(anchor) as? Number)?.toDouble()
                val y = (anchor.javaClass.getMethod("getY").invoke(anchor) as? Number)?.toDouble()
                val w = (anchor.javaClass.getMethod("getWidth").invoke(anchor) as? Number)?.toDouble()
                val h = (anchor.javaClass.getMethod("getHeight").invoke(anchor) as? Number)?.toDouble()

                val slideWPt = if (slideWidthEmu > 0) slideWidthEmu / 12700.0 else 720.0
                val slideHPt = if (slideHeightEmu > 0) slideHeightEmu / 12700.0 else 540.0

                if (x != null && y != null && w != null && h != null && w > 0 && h > 0) {
                    return floatArrayOf(
                        (x / slideWPt).toFloat().coerceIn(0f, 1f),
                        (y / slideHPt).toFloat().coerceIn(0f, 1f),
                        (w / slideWPt).toFloat().coerceIn(0.01f, 1f),
                        (h / slideHPt).toFloat().coerceIn(0.01f, 1f)
                    )
                }
            }
        } catch (_: Throwable) { }

        return null
    }

    private fun getXmlShapeBoundsNormalized(
        shape: Any,
        slideWidthEmu: Long,
        slideHeightEmu: Long
    ): FloatArray? {
        if (slideWidthEmu <= 0 || slideHeightEmu <= 0) return null
        try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (t: Throwable) {
                try {
                    val method = shape.javaClass.getDeclaredMethod("fetchXmlObject")
                    method.isAccessible = true
                    method.invoke(shape)
                } catch (t2: Throwable) { null }
            } ?: return null

            var xfrm: Any? = null
            xfrm = tryGetXfrmOC(xmlObj, "getSpPr")
            if (xfrm == null) xfrm = tryGetXfrmOC(xmlObj, "getGrpSpPr")
            if (xfrm == null) {
                for (methodName in listOf("getCxnSpPr", "getNvSpPr", "getNvPicPr", "getNvCxnSpPr")) {
                    xfrm = tryGetXfrmOC(xmlObj, methodName)
                    if (xfrm != null) break
                }
            }
            if (xfrm == null) {
                xfrm = try { xmlObj.javaClass.getMethod("getXfrm").invoke(xmlObj) } catch (_: Throwable) { null }
            }

            if (xfrm == null) return null

            val off = try { xfrm.javaClass.getMethod("getOff").invoke(xfrm) } catch (_: Throwable) { null }
            val ext = try { xfrm.javaClass.getMethod("getExt").invoke(xfrm) } catch (_: Throwable) { null }

            val rawX = try { off?.javaClass?.getMethod("getX")?.invoke(off) } catch (_: Throwable) { null }
            val rawY = try { off?.javaClass?.getMethod("getY")?.invoke(off) } catch (_: Throwable) { null }
            val rawCx = try { ext?.javaClass?.getMethod("getCx")?.invoke(ext) } catch (_: Throwable) { null }
            val rawCy = try { ext?.javaClass?.getMethod("getCy")?.invoke(ext) } catch (_: Throwable) { null }

            val rawXVal = extractLongValueOC(rawX)
            val rawYVal = extractLongValueOC(rawY)
            val rawCxVal = extractLongValueOC(rawCx)
            val rawCyVal = extractLongValueOC(rawCy)

            if (rawXVal != null && rawYVal != null && rawCxVal != null && rawCyVal != null && rawCxVal > 0 && rawCyVal > 0) {
                var curX: Long = rawXVal
                var curY: Long = rawYVal
                var curCx: Long = rawCxVal
                var curCy: Long = rawCyVal

                // Apply parent group transforms recursively if shape is nested in a group shape
                var parentShape = (shape as? XSLFShape)?.parent
                while (parentShape is XSLFGroupShape) {
                    val groupXml = try { parentShape.javaClass.getMethod("getXmlObject").invoke(parentShape) } catch (_: Throwable) { null }
                    if (groupXml != null) {
                        val grpXfrm = tryGetXfrmOC(groupXml, "getGrpSpPr")
                            ?: try { groupXml.javaClass.getMethod("getXfrm").invoke(groupXml) } catch (_: Throwable) { null }
                        if (grpXfrm != null) {
                            val gOff = try { grpXfrm.javaClass.getMethod("getOff").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gExt = try { grpXfrm.javaClass.getMethod("getExt").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gChOff = try { grpXfrm.javaClass.getMethod("getChOff").invoke(grpXfrm) } catch (_: Throwable) { null }
                            val gChExt = try { grpXfrm.javaClass.getMethod("getChExt").invoke(grpXfrm) } catch (_: Throwable) { null }

                            val gX = extractLongValueOC(try { gOff?.javaClass?.getMethod("getX")?.invoke(gOff) } catch (_: Throwable) { null }) ?: 0L
                            val gY = extractLongValueOC(try { gOff?.javaClass?.getMethod("getY")?.invoke(gOff) } catch (_: Throwable) { null }) ?: 0L
                            val gCx = extractLongValueOC(try { gExt?.javaClass?.getMethod("getCx")?.invoke(gExt) } catch (_: Throwable) { null }) ?: slideWidthEmu
                            val gCy = extractLongValueOC(try { gExt?.javaClass?.getMethod("getCy")?.invoke(gExt) } catch (_: Throwable) { null }) ?: slideHeightEmu

                            val chX = extractLongValueOC(try { gChOff?.javaClass?.getMethod("getX")?.invoke(gChOff) } catch (_: Throwable) { null }) ?: gX
                            val chY = extractLongValueOC(try { gChOff?.javaClass?.getMethod("getY")?.invoke(gChOff) } catch (_: Throwable) { null }) ?: gY
                            val chCx = extractLongValueOC(try { gChExt?.javaClass?.getMethod("getCx")?.invoke(gChExt) } catch (_: Throwable) { null }) ?: gCx
                            val chCy = extractLongValueOC(try { gChExt?.javaClass?.getMethod("getCy")?.invoke(gChExt) } catch (_: Throwable) { null }) ?: gCy

                            val scaleX = if (chCx > 0) gCx.toDouble() / chCx.toDouble() else 1.0
                            val scaleY = if (chCy > 0) gCy.toDouble() / chCy.toDouble() else 1.0

                            curX = (gX + (curX - chX) * scaleX).toLong()
                            curY = (gY + (curY - chY) * scaleY).toLong()
                            curCx = (curCx * scaleX).toLong()
                            curCy = (curCy * scaleY).toLong()
                        }
                    }
                    parentShape = parentShape.parent
                }

                return floatArrayOf(
                    (curX.toFloat() / slideWidthEmu.toFloat()).coerceIn(0f, 1f),
                    (curY.toFloat() / slideHeightEmu.toFloat()).coerceIn(0f, 1f),
                    (curCx.toFloat() / slideWidthEmu.toFloat()).coerceIn(0.001f, 1f),
                    (curCy.toFloat() / slideHeightEmu.toFloat()).coerceIn(0.001f, 1f)
                )
            }
        } catch (_: Throwable) { }
        return null
    }

    /** Helper: tries parentObj.getMethodName().getXfrm() via reflection */
    private fun tryGetXfrmOC(parentObj: Any, prMethodName: String): Any? {
        return try {
            val pr = parentObj.javaClass.getMethod(prMethodName).invoke(parentObj) ?: return null
            pr.javaClass.getMethod("getXfrm").invoke(pr)
        } catch (_: Throwable) { null }
    }

    private enum class ShapeGeom { RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, HEXAGON, TRIANGLE, DIAMOND, FREEFORM }

    private fun getShapeGeometryType(shape: Any): ShapeGeom {
        try {
            if (shape is org.apache.poi.sl.usermodel.SimpleShape<*, *>) {
                val st = try { shape.shapeType } catch (_: Throwable) { null }
                if (st != null) {
                    val stName = st.name.lowercase()
                    when {
                        stName.contains("hexagon") -> return ShapeGeom.HEXAGON
                        stName.contains("ellipse") || stName.contains("oval") || stName.contains("circle") -> return ShapeGeom.ELLIPSE
                        stName.contains("round") && stName.contains("rect") -> return ShapeGeom.ROUNDED_RECTANGLE
                        stName.contains("triangle") -> return ShapeGeom.TRIANGLE
                        stName.contains("diamond") -> return ShapeGeom.DIAMOND
                    }
                }
            }
            val xmlObj = try { shape.javaClass.getMethod("getXmlObject").invoke(shape) } catch (_: Throwable) { null }
            if (xmlObj != null) {
                val spPr = try { xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj) } catch (_: Throwable) { null }
                if (spPr != null) {
                    val prstGeom = try { spPr.javaClass.getMethod("getPrstGeom").invoke(spPr) } catch (_: Throwable) { null }
                    if (prstGeom != null) {
                        val prst = try { prstGeom.javaClass.getMethod("getPrst").invoke(prstGeom)?.toString()?.lowercase() } catch (_: Throwable) { null }
                        if (prst != null) {
                            when {
                                prst.contains("hexagon") -> return ShapeGeom.HEXAGON
                                prst.contains("ellipse") || prst.contains("oval") || prst.contains("circle") -> return ShapeGeom.ELLIPSE
                                prst.contains("roundrect") -> return ShapeGeom.ROUNDED_RECTANGLE
                                prst.contains("triangle") -> return ShapeGeom.TRIANGLE
                                prst.contains("diamond") -> return ShapeGeom.DIAMOND
                            }
                        }
                    }
                    val custGeom = try { spPr.javaClass.getMethod("getCustGeom").invoke(spPr) } catch (_: Throwable) { null }
                    if (custGeom != null) return ShapeGeom.FREEFORM
                }
            }
        } catch (_: Throwable) {}
        return ShapeGeom.RECTANGLE
    }

    private fun drawShapeGeometry(
        canvas: android.graphics.Canvas,
        geom: ShapeGeom,
        px: Float, py: Float, pw: Float, ph: Float,
        paint: Paint
    ) {
        when (geom) {
            ShapeGeom.ELLIPSE -> {
                canvas.drawOval(px, py, px + pw, py + ph, paint)
            }
            ShapeGeom.ROUNDED_RECTANGLE -> {
                val rx = pw * 0.15f
                val ry = ph * 0.15f
                canvas.drawRoundRect(px, py, px + pw, py + ph, rx, ry, paint)
            }
            ShapeGeom.HEXAGON -> {
                val path = android.graphics.Path().apply {
                    val insetW = pw * 0.25f
                    moveTo(px + insetW, py)
                    lineTo(px + pw - insetW, py)
                    lineTo(px + pw, py + ph * 0.5f)
                    lineTo(px + pw - insetW, py + ph)
                    lineTo(px + insetW, py + ph)
                    lineTo(px, py + ph * 0.5f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.TRIANGLE -> {
                val path = android.graphics.Path().apply {
                    moveTo(px + pw * 0.5f, py)
                    lineTo(px + pw, py + ph)
                    lineTo(px, py + ph)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.DIAMOND -> {
                val path = android.graphics.Path().apply {
                    moveTo(px + pw * 0.5f, py)
                    lineTo(px + pw, py + ph * 0.5f)
                    lineTo(px + pw * 0.5f, py + ph)
                    lineTo(px, py + ph * 0.5f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeGeom.FREEFORM, ShapeGeom.RECTANGLE -> {
                canvas.drawRect(px, py, px + pw, py + ph, paint)
            }
        }
    }

    private fun extractAlphaFromColorObj(colorObj: Any?): Long? {
        if (colorObj == null) return null
        try {
            val alphaList = try { colorObj.javaClass.getMethod("getAlphaList").invoke(colorObj) as? List<*> } catch (_: Throwable) { null }
            if (!alphaList.isNullOrEmpty()) {
                val firstAlpha = alphaList[0]
                val valObj = try { firstAlpha?.javaClass?.getMethod("getVal")?.invoke(firstAlpha) } catch (_: Throwable) { null }
                val l = extractLongValueOC(valObj)
                if (l != null) return l
            }
        } catch (_: Throwable) {}
        try {
            val alphaArray = try { colorObj.javaClass.getMethod("getAlphaArray").invoke(colorObj) as? Array<*> } catch (_: Throwable) { null }
            if (!alphaArray.isNullOrEmpty()) {
                val firstAlpha = alphaArray[0]
                val valObj = try { firstAlpha?.javaClass?.getMethod("getVal")?.invoke(firstAlpha) } catch (_: Throwable) { null }
                val l = extractLongValueOC(valObj)
                if (l != null) return l
            }
        } catch (_: Throwable) {}
        try {
            val xmlStr = colorObj.toString()
            val match = Regex("""(?:<a:alpha|alpha)[^>]*val=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            if (match != null) {
                return match.groupValues[1].toLongOrNull()
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun resolveSchemeColorName(schemeName: String, themeColors: Map<String, String>): String? {
        val s = schemeName.lowercase()
        for ((key, hex) in themeColors) {
            if (s.contains(key)) return hex
        }
        return when {
            s.contains("accent1") -> "#1E40AF"
            s.contains("accent2") -> "#EA580C"
            s.contains("accent3") -> "#0D9488"
            s.contains("accent4") -> "#7C3AED"
            s.contains("accent5") -> "#16A34A"
            s.contains("accent6") -> "#E11D48"
            s.contains("tx1") || s.contains("dk1") -> "#0F172A"
            s.contains("tx2") || s.contains("dk2") -> "#334155"
            s.contains("bg1") || s.contains("lt1") -> "#FFFFFF"
            s.contains("bg2") || s.contains("lt2") -> "#F8FAFC"
            s.contains("hlink") -> "#2563EB"
            else -> null
        }
    }

    private fun extractColorAndAlphaFromFill(fillObj: Any?, themeColors: Map<String, String>): Int? {
        if (fillObj == null) return null
        try {
            var hexColor: String? = null
            var alphaVal: Long? = null

            var solidFill: Any? = fillObj
            try {
                val sf = fillObj.javaClass.getMethod("getSolidFill").invoke(fillObj)
                if (sf != null) solidFill = sf
            } catch (_: Throwable) {}

            val srgb = try { solidFill?.javaClass?.getMethod("getSrgbClr")?.invoke(solidFill) } catch (_: Throwable) { null }
            if (srgb != null) {
                val bytes = try { srgb.javaClass.getMethod("getVal").invoke(srgb) as? ByteArray } catch (_: Throwable) { null }
                if (bytes != null && bytes.size >= 3) {
                    hexColor = String.format("#%02X%02X%02X", bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
                }
                alphaVal = extractAlphaFromColorObj(srgb)
            }

            if (hexColor == null) {
                val schemeClr = try { solidFill?.javaClass?.getMethod("getSchemeClr")?.invoke(solidFill) } catch (_: Throwable) { null }
                if (schemeClr != null) {
                    val valObj = try { schemeClr.javaClass.getMethod("getVal").invoke(schemeClr) } catch (_: Throwable) { null }
                    hexColor = resolveSchemeColorName(valObj?.toString() ?: "", themeColors)
                    alphaVal = extractAlphaFromColorObj(schemeClr)
                }
            }

            if (hexColor == null) {
                val prstClr = try { solidFill?.javaClass?.getMethod("getPrstClr")?.invoke(solidFill) } catch (_: Throwable) { null }
                if (prstClr != null) {
                    val valObj = try { prstClr.javaClass.getMethod("getVal").invoke(prstClr) } catch (_: Throwable) { null }
                    hexColor = when (valObj?.toString()?.lowercase()) {
                        "black" -> "#000000"; "white" -> "#FFFFFF"; "red" -> "#FF0000"; "green" -> "#008000"; "blue" -> "#0000FF"; else -> null
                    }
                    alphaVal = extractAlphaFromColorObj(prstClr)
                }
            }

            if (hexColor == null) {
                val gradFill = try { fillObj.javaClass.getMethod("getGradFill").invoke(fillObj) } catch (_: Throwable) { null }
                if (gradFill != null) {
                    val gsLst = try { gradFill.javaClass.getMethod("getGsLst").invoke(gradFill) } catch (_: Throwable) { null }
                    if (gsLst != null) {
                        val gsArray = try {
                            val m = gsLst.javaClass.getMethod("getGsArray")
                            m.invoke(gsLst) as? Array<*>
                        } catch (_: Throwable) { null }
                        if (!gsArray.isNullOrEmpty() && gsArray[0] != null) {
                            return extractColorAndAlphaFromFill(gsArray[0], themeColors)
                        }
                    }
                }
            }

            if (hexColor == null) return null

            val rgbInt = android.graphics.Color.parseColor(hexColor)
            val alphaInt = if (alphaVal != null && alphaVal >= 0) {
                val alphaFloat = when {
                    alphaVal > 100L -> (alphaVal.toFloat() / 100000f).coerceIn(0f, 1f)
                    else -> (alphaVal.toFloat() / 100f).coerceIn(0f, 1f)
                }
                (alphaFloat * 255f).toInt().coerceIn(0, 255)
            } else {
                val a = (rgbInt shr 24) and 0xFF
                if (a == 0) 255 else a
            }

            return android.graphics.Color.argb(alphaInt, (rgbInt shr 16) and 0xFF, (rgbInt shr 8) and 0xFF, rgbInt and 0xFF)
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Extracts shape fill color as Android Color int, or null if none.
     */
    private fun getShapeFillColor(shape: XSLFSimpleShape, themeColors: Map<String, String>): Int? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (_: Throwable) { null } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (_: Throwable) { null } ?: return null

            extractColorAndAlphaFromFill(spPr, themeColors)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts shape line/border color as Android Color int, or null if none.
     */
    private fun getShapeLineColor(shape: XSLFSimpleShape, themeColors: Map<String, String>): Int? {
        return try {
            val xmlObj = try {
                shape.javaClass.getMethod("getXmlObject").invoke(shape)
            } catch (_: Throwable) { null } ?: return null

            val spPr = try {
                xmlObj.javaClass.getMethod("getSpPr").invoke(xmlObj)
            } catch (_: Throwable) { null } ?: return null

            val ln = try {
                spPr.javaClass.getMethod("getLn").invoke(spPr)
            } catch (_: Throwable) { null } ?: return null

            extractColorAndAlphaFromFill(ln, themeColors)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts slide background color as hex string, or null if none.
     */
    private fun getSlideBgColor(slide: org.apache.poi.sl.usermodel.Slide<*, *>): String? {
        if (slide !is org.apache.poi.xslf.usermodel.XSLFSlide) return null
        return try {
            val ctSlide = try {
                slide.javaClass.getMethod("getXmlObject").invoke(slide)
            } catch (t: Throwable) { null } ?: return null

            val cSld = try {
                ctSlide.javaClass.getMethod("getCSld").invoke(ctSlide)
            } catch (t: Throwable) { null } ?: return null

            val bg = try {
                cSld.javaClass.getMethod("getBg").invoke(cSld)
            } catch (t: Throwable) { null } ?: return null

            val bgPr = try {
                bg.javaClass.getMethod("getBgPr").invoke(bg)
            } catch (t: Throwable) { null } ?: return null

            val solidFill = try {
                bgPr.javaClass.getMethod("getSolidFill").invoke(bgPr)
            } catch (t: Throwable) { null } ?: return null

            val srgbClr = try {
                solidFill.javaClass.getMethod("getSrgbClr").invoke(solidFill)
            } catch (t: Throwable) { null } ?: return null

            val hexBytes = try {
                srgbClr.javaClass.getMethod("getVal").invoke(srgbClr) as? ByteArray
            } catch (t: Throwable) { null } ?: return null

            val hex = hexBytes.joinToString("") { String.format("%02X", it) }
            if (hex.isNotBlank()) "#$hex" else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Greedy word wrapping routine checking exact horizontal width budget constraint.
     * Preserves explicit newlines and breaks long unbroken tokens to prevent horizontal cell overflow.
     */
    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val rawParagraphs = text.split(Regex("\r\n|\r|\n"))
        val lines = mutableListOf<String>()

        for (paragraph in rawParagraphs) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            val words = paragraph.split(Regex("[ \t]+"))
            var currentLine = StringBuilder()

            for (word in words) {
                if (word.isEmpty()) continue
                // If a single word exceeds maxWidth, break it into chunks so it doesn't overflow
                val cleanWord = sanitizeText(word)
                val wordWidth = try { font.getStringWidth(cleanWord) / 1000f * fontSize } catch (_: Exception) { cleanWord.length * 5f }
                if (wordWidth > maxWidth && maxWidth > 10f) {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                        currentLine = StringBuilder()
                    }
                    var remaining = word
                    while (remaining.isNotEmpty()) {
                        var chunkLen = remaining.length
                        while (chunkLen > 1) {
                            val candidate = sanitizeText(remaining.substring(0, chunkLen))
                            val w = try { font.getStringWidth(candidate) / 1000f * fontSize } catch (_: Exception) { candidate.length * 5f }
                            if (w <= maxWidth) break
                            chunkLen--
                        }
                        lines.add(remaining.substring(0, chunkLen))
                        remaining = remaining.substring(chunkLen)
                    }
                    continue
                }

                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                val cleanTestLine = sanitizeText(testLine)
                val width = try { font.getStringWidth(cleanTestLine) / 1000f * fontSize } catch (_: Exception) { cleanTestLine.length * 5f }
                if (width <= maxWidth) {
                    if (currentLine.isNotEmpty()) currentLine.append(" ")
                    currentLine.append(word)
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
        }
        return if (lines.isEmpty()) listOf("") else lines
    }

    /**
     * Truncates text greedily with an ellipsis fallback to guarantee matrix visual alignment.
     */
    private fun truncateToWidth(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): String {
        try {
            var width = (font.getStringWidth(text) / 1000f * fontSize)
            if (width <= maxWidth) return text

            var truncated = text
            while (truncated.isNotEmpty() && width > maxWidth) {
                truncated = truncated.dropLast(1)
                width = (font.getStringWidth("$truncated...") / 1000f * fontSize)
            }
            return if (truncated.isEmpty()) "" else "$truncated..."
        } catch (e: Exception) {
            return text
        }
    }

    private fun wrapTextForCanvas(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (word.isEmpty()) continue
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            val width = paint.measureText(testLine)
            if (width <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    /**
     * Reads a Word on/off flag (w:pageBreakBefore, w:keepNext, ...).
     * Absent w:val means ON per OOXML; 0/false/off/none mean OFF.
     */
    private fun isOnOff(onOff: CTOnOff?): Boolean {
        if (onOff == null) return false
        return try {
            if (!onOff.isSetVal()) return true
            val v = onOff.`val`?.toString()?.lowercase() ?: return true
            v != "0" && v != "false" && v != "off" && v != "none"
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Maps Wingdings/Symbol/private-use bullet glyphs to WinAnsi-safe
     * markers so list symbols survive PDFBox sanitization instead of
     * being blanked to spaces.
     */
    private fun mapBulletChars(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            val mapped = when {
                code in 0xF000..0xF0FF -> "•"
                code in 0x2022..0x2024 -> "•"
                code == 0x25AA || code == 0x25AB -> "·"
                code == 0x25BA || code == 0x25B6 -> ">"
                code == 0x25CA || code == 0x25C6 -> "•"
                else -> ch.toString()
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    /**
     * Filters glyphs outside WinAnsiEncoding to avoid PDFBox rendering exceptions.
     * WinAnsi natively supports curly quotes, en/em dashes and the bullet,
     * so those pass through instead of being ASCII-folded.
     */
    private fun sanitizeText(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            val code = char.code
            if (code in 32..126 || code in 160..255 ||
                char == '‘' || char == '’' || char == '“' || char == '”' ||
                char == '–' || char == '—' || char == '•'
            ) {
                sb.append(char)
            } else if (char == '\n' || char == '\r' || char == '\t') {
                sb.append(' ')
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    private fun safeShowText(
        stream: PDPageContentStream?,
        text: String,
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont
    ) {
        if (stream == null || text.isEmpty()) return
        val sb = StringBuilder()
        for (char in text) {
            val encodable = try {
                font.encode(char.toString())
                true
            } catch (_: Exception) {
                false
            }
            if (encodable) {
                sb.append(char)
            } else {
                when (char) {
                    '‘', '’' -> sb.append('\'')
                    '“', '”' -> sb.append('"')
                    '–', '—' -> sb.append('-')
                    '•' -> sb.append('*')
                    '…' -> sb.append("...")
                    '→' -> sb.append("->")
                    '←' -> sb.append("<-")
                    '↓' -> sb.append("v")
                    '\t', '\n', '\r' -> sb.append(' ')
                    else -> {
                        if (char.code in 32..126) sb.append(char) else sb.append(' ')
                    }
                }
            }
        }
        val out = sb.toString()
        try {
            stream.showText(out)
        } catch (_: Exception) {
            val asciiOnly = out.filter { it.code in 32..126 }
            try {
                stream.showText(asciiOnly)
            } catch (_: Exception) {}
        }
    }

    /**
     * Converts a plain text or RTF/markdown file line-by-line to an A4 PDF with automatic word wrap and pagination.
     */
    suspend fun convertTextToPdf(textFile: File, pdfFile: File, context: android.content.Context) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var pdf: PDDocument? = null
        var contentStream: PDPageContentStream? = null

        try {
            pdf = PDDocument()
            val pageBounds = PDRectangle.A4
            val margin = 50f
            val printableWidth = pageBounds.width - (2 * margin)

            val font = PDType1Font.HELVETICA
            val fontSize = 11f
            val leading = fontSize * 1.3f

            var currentPage = PDPage(pageBounds)
            pdf.addPage(currentPage)
            contentStream = PDPageContentStream(pdf, currentPage)

            var yPosition = pageBounds.height - margin

            textFile.forEachLine(Charsets.UTF_8) { rawLine ->
                val line = rawLine.replace('\t', ' ')
                val words = line.split(' ')
                val currentLine = StringBuilder()

                for (word in words) {
                    val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                    val testW = try {
                        font.getStringWidth(candidate.filter { it.code in 32..126 }) / 1000f * fontSize
                    } catch (_: Exception) {
                        candidate.length * fontSize * 0.5f
                    }

                    if (testW > printableWidth && currentLine.isNotEmpty()) {
                        if (yPosition < margin + leading) {
                            contentStream?.close()
                            currentPage = PDPage(pageBounds)
                            pdf.addPage(currentPage)
                            contentStream = PDPageContentStream(pdf, currentPage)
                            yPosition = pageBounds.height - margin
                        }

                        contentStream?.beginText()
                        contentStream?.setFont(font, fontSize)
                        contentStream?.newLineAtOffset(margin, yPosition)
                        safeShowText(contentStream, currentLine.toString(), font)
                        contentStream?.endText()
                        yPosition -= leading
                        currentLine.clear().append(word)
                    } else {
                        currentLine.clear().append(candidate)
                    }
                }

                if (currentLine.isNotEmpty()) {
                    if (yPosition < margin + leading) {
                        contentStream?.close()
                        currentPage = PDPage(pageBounds)
                        pdf.addPage(currentPage)
                        contentStream = PDPageContentStream(pdf, currentPage)
                        yPosition = pageBounds.height - margin
                    }

                    contentStream?.beginText()
                    contentStream?.setFont(font, fontSize)
                    contentStream?.newLineAtOffset(margin, yPosition)
                    safeShowText(contentStream, currentLine.toString(), font)
                    contentStream?.endText()
                    yPosition -= leading
                }
            }

            contentStream?.close()
            contentStream = null
            publishOfficePdf(pdf, pdfFile)
        } finally {
            try { contentStream?.close() } catch (_: Exception) {}
            try { pdf?.close() } catch (_: Exception) {}
        }
    }
}
