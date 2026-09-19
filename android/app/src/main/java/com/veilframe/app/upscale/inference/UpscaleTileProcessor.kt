package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * High-performance tiled inference processor with boundary tile handling,
 * spatial overlap, and zero-allocation core-region extraction.
 *
 * Rather than blending overlapping tiles in Kotlin CPU loops, each tile is extracted
 * with overlap margin to prevent neural boundary distortion, and only the pristine
 * central "core" is blitted into the output canvas via native Skia drawBitmap.
 */
class UpscaleTileProcessor(
    val tileSize: Int = 384,
    val overlap: Int = 24
) {

    data class TileArea(
        val col: Int,
        val row: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val coreX: Int,
        val coreY: Int,
        val coreWidth: Int,
        val coreHeight: Int
    )

    fun calculateTiles(imageWidth: Int, imageHeight: Int): List<TileArea> {
        val tiles = mutableListOf<TileArea>()
        val halfOverlap = overlap / 2
        val step = (tileSize - overlap).coerceAtLeast(1)

        var row = 0
        var y = 0
        while (y < imageHeight) {
            val tileH = minOf(tileSize, imageHeight - y)
            val isFirstRow = (row == 0)
            val isLastRow = (y + tileH >= imageHeight)

            val coreLocalY = if (isFirstRow) 0 else halfOverlap
            val coreBottom = if (isLastRow) tileH else (tileH - halfOverlap)
            val coreH = (coreBottom - coreLocalY).coerceAtLeast(0)

            var col = 0
            var x = 0
            while (x < imageWidth) {
                val tileW = minOf(tileSize, imageWidth - x)
                val isFirstCol = (col == 0)
                val isLastCol = (x + tileW >= imageWidth)

                val coreLocalX = if (isFirstCol) 0 else halfOverlap
                val coreRight = if (isLastCol) tileW else (tileW - halfOverlap)
                val coreW = (coreRight - coreLocalX).coerceAtLeast(0)

                tiles.add(
                    TileArea(
                        col = col,
                        row = row,
                        x = x,
                        y = y,
                        width = tileW,
                        height = tileH,
                        coreX = coreLocalX,
                        coreY = coreLocalY,
                        coreWidth = coreW,
                        coreHeight = coreH
                    )
                )

                if (x + tileW >= imageWidth) break
                x += step
                col++
            }
            if (y + tileH >= imageHeight) break
            y += step
            row++
        }
        return tiles
    }

    suspend fun processTiles(
        source: Bitmap,
        scale: Int,
        onTileInfer: suspend (tile: Bitmap, row: Int, col: Int) -> Bitmap,
        onProgress: (current: Int, total: Int) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // If source fits entirely in a single tile without splitting
        if (srcW <= tileSize && srcH <= tileSize) {
            onProgress(0, 1)
            val result = onTileInfer(source, 0, 0)
            onProgress(1, 1)
            return@withContext result
        }

        val tileAreas = calculateTiles(srcW, srcH)
        val totalTiles = tileAreas.size

        val outW = srcW * scale
        val outH = srcH * scale
        val outputBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val srcRect = Rect()
        val dstRect = Rect()

        for ((index, tile) in tileAreas.withIndex()) {
            ensureActive()
            onProgress(index, totalTiles)

            // Extract tile from source image
            val srcTile = Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
            val processedTile = try {
                onTileInfer(srcTile, tile.row, tile.col)
            } finally {
                if (srcTile != source) srcTile.recycle()
            }

            // Copy valid core directly into aggregated output canvas via native drawBitmap
            val actualScaleX = processedTile.width / tile.width
            val actualScaleY = processedTile.height / tile.height

            val srcCoreX = tile.coreX * actualScaleX
            val srcCoreY = tile.coreY * actualScaleY
            val srcCoreW = tile.coreWidth * actualScaleX
            val srcCoreH = tile.coreHeight * actualScaleY

            val dstX = (tile.x + tile.coreX) * actualScaleX
            val dstY = (tile.y + tile.coreY) * actualScaleY

            srcRect.set(srcCoreX, srcCoreY, srcCoreX + srcCoreW, srcCoreY + srcCoreH)
            dstRect.set(dstX, dstY, dstX + srcCoreW, dstY + srcCoreH)

            canvas.drawBitmap(processedTile, srcRect, dstRect, null)
            processedTile.recycle()
            onProgress(index + 1, totalTiles)
        }

        outputBitmap
    }
}
