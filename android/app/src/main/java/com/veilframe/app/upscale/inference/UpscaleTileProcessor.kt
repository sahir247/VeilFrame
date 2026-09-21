package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Output sink abstraction decoupling memory storage from tiled neural execution.
 */
interface OutputSink : AutoCloseable {
    val targetWidth: Int
    val targetHeight: Int
    fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile)
    fun complete(): Any?
}

/**
 * In-memory bitmap output sink. Blits tile cores directly to a destination Canvas.
 * Synchronously consumes pixels and immediately recycles the processed tile bitmap.
 */
class BitmapOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int
) : OutputSink {
    val outputBitmap: Bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(outputBitmap)
    private val canvasLock = Any()

    override fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale

        val srcCoreX = tile.coreX * scale
        val srcCoreY = tile.coreY * scale
        val srcCoreW = tile.coreWidth * scale
        val srcCoreH = tile.coreHeight * scale

        val dstX = (tile.x + tile.coreX) * scale
        val dstY = (tile.y + tile.coreY) * scale

        val srcRect = Rect(srcCoreX, srcCoreY, srcCoreX + srcCoreW, srcCoreY + srcCoreH)
        val dstRect = Rect(dstX, dstY, dstX + srcCoreW, dstY + srcCoreH)

        synchronized(canvasLock) {
            canvas.drawBitmap(processedTile, srcRect, dstRect, null)
        }

        // Strict ownership: sink has copied pixels, immediately recycle processed tile
        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): Bitmap = outputBitmap
    override fun close() {}
}

/**
 * Streaming strip output sink. Maintains a bounded strip canvas in RAM and emits
 * completed vertical strips to a streaming consumer.
 */
class StripOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val stripHeight: Int,
    val onStripReady: (Bitmap, stripIndex: Int) -> Unit
) : OutputSink {
    private var currentStripIndex = 0
    private var currentStripTop = 0
    private var stripBitmap: Bitmap = Bitmap.createBitmap(targetWidth, stripHeight, Bitmap.Config.ARGB_8888)
    private var stripCanvas = Canvas(stripBitmap)
    private val lock = Any()

    override fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val tileTop = (tile.y + tile.coreY) * scale

        synchronized(lock) {
            while (tileTop >= currentStripTop + stripHeight && currentStripTop + stripHeight < targetHeight) {
                flushStrip()
            }

            val srcRect = Rect(
                tile.coreX * scale,
                tile.coreY * scale,
                (tile.coreX + tile.coreWidth) * scale,
                (tile.coreY + tile.coreHeight) * scale
            )
            val dstY = tileTop - currentStripTop
            val dstX = (tile.x + tile.coreX) * scale
            val dstRect = Rect(dstX, dstY, dstX + (tile.coreWidth * scale), dstY + (tile.coreHeight * scale))
            stripCanvas.drawBitmap(processedTile, srcRect, dstRect, null)
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    private fun flushStrip() {
        onStripReady(stripBitmap, currentStripIndex++)
        currentStripTop += stripHeight
        val nextH = minOf(stripHeight, targetHeight - currentStripTop)
        if (nextH > 0) {
            stripBitmap.eraseColor(0)
        }
    }

    override fun complete(): Any? {
        synchronized(lock) {
            if (currentStripTop < targetHeight) {
                flushStrip()
            }
        }
        return null
    }

    override fun close() {
        if (!stripBitmap.isRecycled) {
            stripBitmap.recycle()
        }
    }
}

/**
 * Tiled intermediate storage sink. Persists raw tile core pixel blocks directly
 * to a scratch directory on disk for ultra-high-resolution images without RAM exhaustion.
 */
class TiledIntermediateSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val scratchDir: File
) : OutputSink {
    init {
        scratchDir.mkdirs()
    }

    override fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val coreW = tile.coreWidth * scale
        val coreH = tile.coreHeight * scale
        val tileFile = File(scratchDir, "tile_${tile.index}.raw")

        val pixels = IntArray(coreW * coreH)
        processedTile.getPixels(
            pixels, 0, coreW,
            tile.coreX * scale, tile.coreY * scale, coreW, coreH
        )

        tileFile.outputStream().buffered().use { fos ->
            val byteBuf = java.nio.ByteBuffer.allocate(pixels.size * 4)
            byteBuf.asIntBuffer().put(pixels)
            fos.write(byteBuf.array())
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): File = scratchDir
    override fun close() {}
}

/**
 * High-performance tiled inference processor with boundary tile handling,
 * spatial overlap, and bounded parallel workers (ImageToolbox architecture).
 *
 * Implements:
 * - Bounded work queue with exactly W workers (Channel<TileArea>).
 * - Decoupled neural stage and refinement stage (AI worker pool -> bounded intermediate queue -> refinement worker -> sink).
 * - Reorder backpressure bounding concurrent pending tiles and byte volume.
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
        sink: OutputSink = BitmapOutputSink(source.width * scale, source.height * scale),
        onTileInfer: suspend (tile: Bitmap, row: Int, col: Int, index: Int, total: Int) -> ProcessedTile,
        onTileRefine: (suspend (baseProcessed: ProcessedTile, row: Int, col: Int, index: Int, total: Int) -> ProcessedTile)? = null,
        onProgress: (current: Int, total: Int) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // If source fits entirely in a single tile without splitting
        if (srcW <= tileSize && srcH <= tileSize) {
            onProgress(0, 1)
            val baseProcessed = onTileInfer(source, 0, 0, 0, 1)
            val finalProcessed = if (onTileRefine != null) {
                onTileRefine(baseProcessed, 0, 0, 0, 1)
            } else {
                baseProcessed
            }
            val singleTileArea = TileArea(0, 0, 0, 0, 0, srcW, srcH, 0, 0, srcW, srcH)
            sink.writeTileCore(singleTileArea, finalProcessed)
            onProgress(1, 1)
            return@withContext (sink.complete() as? Bitmap)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
        }

        val tileAreas = calculateTiles(srcW, srcH)
        val totalTiles = tileAreas.size
        val safeWorkers = workers.coerceAtLeast(1)

        onProgress(0, totalTiles)
        val completedCount = AtomicInteger(0)

        // Bounded queues
        val tileChannel = Channel<TileArea>(capacity = safeWorkers * 2)
        val intermediateChannel = Channel<Pair<TileArea, ProcessedTile>>(capacity = minOf(safeWorkers * 2, 8))

        coroutineScope {
            // Producer: feeds tile jobs into bounded channel
            launch {
                try {
                    for (tile in tileAreas) {
                        tileChannel.send(tile)
                    }
                } finally {
                    tileChannel.close()
                }
            }

            // AI Workers: exactly `safeWorkers` coroutines processing neural inference
            repeat(safeWorkers) {
                launch {
                    for (tile in tileChannel) {
                        ensureActive()
                        val srcTile = Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
                        val baseProcessed = try {
                            onTileInfer(srcTile, tile.row, tile.col, tile.index, totalTiles)
                        } finally {
                            if (srcTile != source && !srcTile.isRecycled) {
                                srcTile.recycle()
                            }
                        }
                        intermediateChannel.send(tile to baseProcessed)
                    }
                }
            }

            // Consumer: single refinement & sink worker to keep 8x memory footprint minimal (max 1 8x tile at a time)
            launch {
                val reorderMap = ConcurrentHashMap<Int, Pair<TileArea, ProcessedTile>>()
                val nextExpectedIndex = AtomicInteger(0)

                for (received in intermediateChannel) {
                    ensureActive()
                    val (tile, baseProcessed) = received
                    val finalProcessed = if (onTileRefine != null) {
                        onTileRefine(baseProcessed, tile.row, tile.col, tile.index, totalTiles)
                    } else {
                        baseProcessed
                    }

                    // For BitmapOutputSink, writing disjoint tile cores can proceed directly
                    if (sink is BitmapOutputSink) {
                        sink.writeTileCore(tile, finalProcessed)
                        val done = completedCount.incrementAndGet()
                        onProgress(done, totalTiles)
                    } else {
                        // For ordered sinks (Strip / Tiled), reorder sequentially
                        reorderMap[tile.index] = tile to finalProcessed
                        while (reorderMap.containsKey(nextExpectedIndex.get())) {
                            val nextPair = reorderMap.remove(nextExpectedIndex.get())!!
                            sink.writeTileCore(nextPair.first, nextPair.second)
                            nextExpectedIndex.incrementAndGet()
                            val done = completedCount.incrementAndGet()
                            onProgress(done, totalTiles)
                        }
                    }

                    if (completedCount.get() >= totalTiles) {
                        break
                    }
                }
            }
        }

        (sink.complete() as? Bitmap) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
    }
}
