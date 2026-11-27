package com.veilframe.app.cv.segmentation.rembg

import com.veilframe.app.media.refine.CutoutStroke
import com.veilframe.app.media.refine.BrushReplayJournal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * Rasterize the recorded brush operations onto bounded neural-alpha strips.
 * Source RGB and alpha are applied only after replay, never reconstructed from
 * preview pixels. Canvas uses the same CLEAR/SRC and round-cap brush semantics
 * as the editor, in the actual original raster's coordinate system.
 */
internal class OriginalBrushReplay(
    private val width: Int, private val height: Int, private val bandHeight: Int,
    private val sampler: RembgAlphaMatte.Sampler, strokes: List<CutoutStroke>
) : AutoCloseable {
    init {
        require(width > 0 && height > 0 && bandHeight in 1..height)
        require(strokes.size <= BrushReplayJournal.MAX_SEGMENTS)
        require(width.toLong() * bandHeight <= 512L * 1024)
    }
    private val bandIndex = com.veilframe.app.media.refine.BrushBandIndex(strokes, width, height)
    private val pixels = IntArray(width * bandHeight)
    private val maskRow = IntArray(width)
    private val mask = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(mask)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erase = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val restore = PorterDuffXfermode(PorterDuff.Mode.SRC)
    private var start = -1
    private var end = -1

    fun apply(sourceRow: IntArray, y: Int, checkpoint: () -> Unit) {
        require(y in 0 until height && sourceRow.size == width)
        if (y < start || y >= end) {
            val rows = minOf(bandHeight, height - y)
            for (r in 0 until rows) {
                checkpoint()
                sampler.fillMaskRow(maskRow, y + r)
                maskRow.copyInto(pixels, r * width)
            }
            mask.setPixels(pixels, 0, width, 0, 0, width, rows)
            val clip = canvas.save()
            try {
                canvas.clipRect(0, 0, width, rows)
                for (prepared in bandIndex.intersecting(y, rows)) {
                    checkpoint()
                    val stroke = prepared.stroke
                    val saved = canvas.save()
                    try {
                        canvas.translate(0f, -y.toFloat())
                        canvas.scale(prepared.scaleX, prepared.scaleY)
                        paint.xfermode = if (stroke.restore) restore else erase
                        if (stroke.x1 == stroke.x2 && stroke.y1 == stroke.y2) {
                            paint.style = Paint.Style.FILL
                            canvas.drawCircle(stroke.x1, stroke.y1, stroke.radius, paint)
                        } else {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = stroke.radius * 2f
                            canvas.drawLine(stroke.x1, stroke.y1, stroke.x2, stroke.y2, paint)
                        }
                    } finally { canvas.restoreToCount(saved) }
                }
            } finally { canvas.restoreToCount(clip) }
            mask.getPixels(pixels, 0, width, 0, 0, width, rows)
            start = y
            end = y + rows
        }
        val offset = (y - start) * width
        for (x in sourceRow.indices) {
            val p = sourceRow[x]
            val alpha = ((p ushr 24) * (pixels[offset + x] ushr 24) + 127) / 255
            sourceRow[x] = if (alpha == 0) 0 else (p and 0x00FFFFFF) or (alpha shl 24)
        }
    }

    override fun close() { mask.recycle() }
}
