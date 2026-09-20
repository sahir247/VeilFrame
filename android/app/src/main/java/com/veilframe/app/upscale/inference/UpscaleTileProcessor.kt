package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance tiled inference processor with boundary tile handling,
 * spatial overlap, and bounded parallel workers (ImageToolbox architecture).
 *
 * Each tile is extracted with an overlap margin to prevent edge distortion,
 * and only the pristine central "core" is blitted into the output canvas via native Skia.
 */
class UpscaleTileProcessor(
    val tileSize: Int = 384,
    val overlap: Int = 24
) {

    data class TileArea(
        val index: Int,
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

        var index = 0
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
                        index = index++,
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
        workers: Int = 1,
        onTileInfer: suspend (tile: Bitmap, row: Int, col: Int, index: Int, total: Int) -> ProcessedTile,
        onProgress: (current: Int, total: Int) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // If source fits entirely in a single tile without splitting
        if (srcW <= tileSize && srcH <= tileSize) {
            onProgress(0, 1)
            val processed = onTileInfer(source, 0, 0, 0, 1)
            onProgress(1, 1)
            return@withContext processed.bitmap
        }

        val tileAreas = calculateTiles(srcW, srcH)
        val totalTiles = tileAreas.size

        val targetWLong = srcW.toLong() * scale
        val targetHLong = srcH.toLong() * scale
        require(targetWLong <= Int.MAX_VALUE && targetHLong <= Int.MAX_VALUE) {
            "Target resolution (${targetWLong}x${targetHLong}) exceeds integer limit."
        }
        val outW = targetWLong.toInt()
        val outH = targetHLong.toInt()

        val outputBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val canvasLock = Any()

        onProgress(0, totalTiles)
        val completedCount = AtomicInteger(0)

        fun drawProcessedTileCore(tile: TileArea, processed: ProcessedTile) {
            val processedTile = processed.bitmap
            val actualScaleX = processed.outputScale
            val actualScaleY = processed.outputScale

            val srcCoreX = tile.coreX * actualScaleX
            val srcCoreY = tile.coreY * actualScaleY
            val srcCoreW = tile.coreWidth * actualScaleX
            val srcCoreH = tile.coreHeight * actualScaleY

            val dstX = (tile.x + tile.coreX) * actualScaleX
            val dstY = (tile.y + tile.coreY) * actualScaleY

            val srcRect = Rect(srcCoreX, srcCoreY, srcCoreX + srcCoreW, srcCoreY + srcCoreH)
            val dstRect = Rect(dstX, dstY, dstX + srcCoreW, dstY + srcCoreH)

            synchronized(canvasLock) {
                canvas.drawBitmap(processedTile, srcRect, dstRect, null)
            }
            if (processedTile != source) {
                processedTile.recycle()
            }
        }

        if (workers <= 1) {
            for (tile in tileAreas) {
                ensureActive()
                val srcTile = Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
                val processedTile = try {
                    onTileInfer(srcTile, tile.row, tile.col, tile.index, totalTiles)
                } finally {
                    if (srcTile != source) srcTile.recycle()
                }

                drawProcessedTileCore(tile, processedTile)
                val done = completedCount.incrementAndGet()
                onProgress(done, totalTiles)
            }
        } else {
            val gate = Semaphore(workers)
            coroutineScope {
                for (tile in tileAreas) {
                    launch {
                        gate.withPermit {
                            ensureActive()
                            val srcTile = Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
                            val processedTile = try {
                                onTileInfer(srcTile, tile.row, tile.col, tile.index, totalTiles)
                            } finally {
                                if (srcTile != source) srcTile.recycle()
                            }

                            drawProcessedTileCore(tile, processedTile)
                            val done = completedCount.incrementAndGet()
                            onProgress(done, totalTiles)
                        }
                    }
                }
            }
        }

        outputBitmap
    }
}
