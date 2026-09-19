package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Tiled inference processor with overlap and smooth-step seam blending.
 * Directly implements ImageToolbox's TileGrid overlap and cubic Hermite feathering math.
 */
class UpscaleTileProcessor(
    private val tileSize: Int = 512,
    private val overlap: Int = 32
) {

    data class TileArea(
        val col: Int,
        val row: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    fun calculateTiles(imageWidth: Int, imageHeight: Int): List<TileArea> {
        val tiles = mutableListOf<TileArea>()
        val step = (tileSize - overlap).coerceAtLeast(1)

        var row = 0
        var y = 0
        while (y < imageHeight) {
            val tileH = minOf(tileSize, imageHeight - y)
            var col = 0
            var x = 0
            while (x < imageWidth) {
                val tileW = minOf(tileSize, imageWidth - x)
                tiles.add(TileArea(col, row, x, y, tileW, tileH))
                x += step
                col++
            }
            y += step
            row++
        }
        return tiles
    }

    suspend fun processTiles(
        source: Bitmap,
        scale: Int,
        onTileInfer: suspend (tile: Bitmap) -> Bitmap,
        onProgress: (current: Int, total: Int) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // If source fits in a single tile without splitting
        if (srcW <= tileSize && srcH <= tileSize) {
            onProgress(0, 1)
            val result = onTileInfer(source)
            onProgress(1, 1)
            return@withContext result
        }

        val tileAreas = calculateTiles(srcW, srcH)
        val totalTiles = tileAreas.size

        val outW = srcW * scale
        val outH = srcH * scale
        val outputBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        for ((index, tile) in tileAreas.withIndex()) {
            ensureActive()
            onProgress(index, totalTiles)

            // Extract tile
            val srcTile = Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
            val processedTile = try {
                onTileInfer(srcTile)
            } finally {
                if (srcTile != source) srcTile.recycle()
            }

            // Draw & blend tile into output
            blendTileIntoOutput(
                output = outputBitmap,
                tileBitmap = processedTile,
                tile = tile,
                overlap = overlap,
                scale = scale
            )
            processedTile.recycle()
            onProgress(index + 1, totalTiles)
        }

        outputBitmap
    }

    private fun blendTileIntoOutput(
        output: Bitmap,
        tileBitmap: Bitmap,
        tile: TileArea,
        overlap: Int,
        scale: Int
    ) {
        val targetX = tile.x * scale
        val targetY = tile.y * scale
        val blendWidth = overlap * scale
        val shouldBlendLeft = tile.col > 0
        val shouldBlendTop = tile.row > 0

        // Corner or non-overlapping first tile
        if (!shouldBlendLeft && !shouldBlendTop) {
            val canvas = Canvas(output)
            canvas.drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return
        }

        val w = tileBitmap.width
        val h = tileBitmap.height
        val existing = IntArray(w * h)
        val incoming = IntArray(w * h)

        try {
            output.getPixels(existing, 0, w, targetX, targetY, w, h)
        } catch (_: Throwable) {
            val canvas = Canvas(output)
            canvas.drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return
        }

        tileBitmap.getPixels(incoming, 0, w, 0, 0, w, h)

        for (localY in 0 until h) {
            for (localX in 0 until w) {
                val mixLeft = shouldBlendLeft && localX < blendWidth
                val mixTop = shouldBlendTop && localY < blendWidth
                if (!mixLeft && !mixTop) continue

                // Smooth-step cubic Hermite curve: t * t * (3 - 2t)
                val blendLeft = if (mixLeft) {
                    val t = (localX.toFloat() / blendWidth.toFloat()).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                } else 1f

                val blendTop = if (mixTop) {
                    val t = (localY.toFloat() / blendWidth.toFloat()).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                } else 1f

                val blend = minOf(blendLeft, blendTop)
                val idx = localY * w + localX

                incoming[idx] = mixColor(existing[idx], incoming[idx], blend)
            }
        }

        output.setPixels(incoming, 0, w, targetX, targetY, w, h)
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val inv = 1f - amount
        val a = (Color.alpha(from) * inv + Color.alpha(to) * amount).toInt().coerceIn(0, 255)
        val r = (Color.red(from) * inv + Color.red(to) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(from) * inv + Color.green(to) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(from) * inv + Color.blue(to) * amount).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}
