package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Output-to-source coordinates for the eight TIFF/EXIF orientation values. */
internal class ExifRasterGeometry(private val rawWidth: Int, private val rawHeight: Int, orientation: Int) {
    private val o = if (orientation in 1..8) orientation else 1
    val width = if (o in 5..8) rawHeight else rawWidth
    val height = if (o in 5..8) rawWidth else rawHeight

    fun rawX(x: Int, y: Int): Int = when (o) {
        2, 3 -> rawWidth - 1 - x
        5, 6 -> y
        7, 8 -> rawWidth - 1 - y
        else -> x
    }
    fun rawY(x: Int, y: Int): Int = when (o) {
        3, 4 -> rawHeight - 1 - y
        5, 8 -> x
        6, 7 -> rawHeight - 1 - x
        else -> y
    }

    fun strip(y: Int, rows: Int): Rect {
        require(y >= 0 && rows > 0 && y + rows <= height)
        val last = y + rows - 1
        val xs = intArrayOf(rawX(0, y), rawX(width - 1, y), rawX(0, last), rawX(width - 1, last))
        val ys = intArrayOf(rawY(0, y), rawY(width - 1, y), rawY(0, last), rawY(width - 1, last))
        return Rect(xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    }
}

/** Bounded original-color region decoding and row-wise transparent PNG encoding. */
internal object OriginalResolutionCutout {
    private const val MAX_PIXELS = 128_000_000L
    private const val MAX_EDGE = 32768
    private const val STRIP_PIXELS = 512 * 1024
    private const val FREE_SPACE_RESERVE = 128L * 1024 * 1024

    fun supports(info: RembgSourceInfo): Boolean =
        info.format in setOf("image/jpeg", "image/png") &&
            info.width in 1..MAX_EDGE && info.height in 1..MAX_EDGE &&
            info.width.toLong() * info.height <= MAX_PIXELS

    @Suppress("DEPRECATION")
    fun export(source: RembgImageSource.Staged, info: RembgSourceInfo, matte: RembgAlphaMatte,
               destination: File, trim: Boolean = false, backdrop: CutoutBackdrop = CutoutBackdrop.TRANSPARENT,
               checkpoint: () -> Unit = {}) {
        require(supports(info)) { "Original-resolution PNG is limited to admitted JPEG/PNG sources" }
        require(source.file.canonicalFile != destination.canonicalFile)
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val rawBytes = info.width.toLong() * info.height * 4 + info.height
        // Conservative allowance for incompressible rows, chunk framing and zlib overhead.
        check(parent.usableSpace >= rawBytes + rawBytes / 100 + 1024 * 1024 + FREE_SPACE_RESERVE) {
            "Insufficient storage for original-resolution PNG"
        }
        val stage = File.createTempFile("cutout_stage_", ".png", parent)
        try {
            checkpoint()
            val decoder = BitmapRegionDecoder.newInstance(source.file.absolutePath, false)
                ?: throw IOException("Source codec does not support region decoding")
            try {
                val geometry = ExifRasterGeometry(decoder.width, decoder.height, RembgImageUtils.readOrientation(source))
                check(geometry.width == info.width && geometry.height == info.height) { "Source geometry changed" }
                val width = geometry.width
                val height = geometry.height
                val bandHeight = (STRIP_PIXELS / width).coerceAtLeast(1)
                val tilePixels = IntArray(width * minOf(bandHeight, height))
                val sampler = matte.sampler(width, height)
                var tile = Rect()
                var bandStart = -1
                var bandEnd = 0
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                    inScaled = false
                }
                fun readCompositeRow(y: Int, row: IntArray) {
                    check(row.size == width && y in 0 until height)
                    if (y < bandStart || y >= bandEnd) {
                        checkpoint()
                        val rows = minOf(bandHeight, height - y)
                        tile = geometry.strip(y, rows)
                        val decoded = decoder.decodeRegion(tile, options)
                            ?: throw IOException("Source region could not be decoded")
                        try {
                            check(decoded.width == tile.width() && decoded.height == tile.height()) {
                                "Source decoder returned a different region size"
                            }
                            decoded.getPixels(tilePixels, 0, tile.width(), 0, 0, tile.width(), tile.height())
                        } finally { decoded.recycle() }
                        bandStart = y
                        bandEnd = y + rows
                    }
                    val stride = tile.width()
                    var index = (geometry.rawY(0, y) - tile.top) * stride + geometry.rawX(0, y) - tile.left
                    val step = if (width == 1) 0 else
                        (geometry.rawY(1, y) - geometry.rawY(0, y)) * stride +
                            geometry.rawX(1, y) - geometry.rawX(0, y)
                    for (x in 0 until width) {
                        row[x] = tilePixels[index]
                        index += step
                    }
                    sampler.apply(row, y)
                }
                // The final multiplied alpha determines the exact visible bounds.
                // In particular, the source might already be transparent.
                val crop = if (trim) CutoutBounds.find(width, height, checkpoint, ::readCompositeRow)
                    else CutoutBounds(0, 0, width, height)
                val fullRow = if (crop.left != 0 || crop.width != width) IntArray(width) else null
                stage.outputStream().buffered().use { output ->
                    StreamingRgbaPng.write(output, crop.width, crop.height, checkpoint) { y, row ->
                        val srcY = crop.top + y
                        if (fullRow == null) readCompositeRow(srcY, row)
                        else {
                            readCompositeRow(srcY, fullRow)
                            fullRow.copyInto(row, 0, crop.left, crop.left + crop.width)
                        }
                        backdrop.apply(row)
                    }
                }
                checkpoint()
                // Validate actual bytes before publishing: dimensions alone do not
                // verify IDAT CRCs, zlib completion or complete decoded scanlines.
                stage.inputStream().buffered().use { input ->
                    StreamingPngVerifier.verify(input, crop.width, crop.height, checkpoint)
                }
            } finally { decoder.recycle() }
            checkpoint()
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }
}
