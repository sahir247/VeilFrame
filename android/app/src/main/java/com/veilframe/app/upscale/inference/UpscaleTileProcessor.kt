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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Output sink abstraction decoupling memory storage from tiled neural execution.
 */
interface OutputSink : AutoCloseable {
    val targetWidth: Int
    val targetHeight: Int
    fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile)
    fun complete(): Bitmap?
}

/**
 * In-memory bitmap output sink. Blits tile cores directly to a destination Canvas.
 * Synchronously consumes pixels and immediately recycles the processed tile bitmap.
 */
class BitmapOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int
) : OutputSink {
    val outputBitmap: Bitmap? = try {
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    } catch (oom: OutOfMemoryError) {
        val maxDim = maxOf(targetWidth, targetHeight)
        val factor = 4096.0f / maxDim
        val downsampledW = (targetWidth * factor).toInt().coerceAtLeast(1)
        val downsampledH = (targetHeight * factor).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(downsampledW, downsampledH, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        null
    }
    private val canvas = outputBitmap?.let { Canvas(it) }
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
            canvas?.drawBitmap(processedTile, srcRect, dstRect, null)
        }

        // Strict ownership: sink has copied pixels, immediately recycle processed tile
        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): Bitmap? = outputBitmap
    override fun close() {}
}

/**
 * Streaming strip output sink. Maintains a bounded strip canvas in RAM and emits
 * completed vertical strips to disk or streaming consumer.
 * Assembles the final output image sequentially upon completion without holding
 * the uncompressed full-resolution bitmap in memory during inference.
 */
class StripOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val stripHeight: Int,
    val scratchDir: File = File(System.getProperty("java.io.tmpdir"), "strip_scratch_${System.currentTimeMillis()}"),
    val onStripReady: ((Bitmap, stripIndex: Int) -> Unit)? = null
) : OutputSink {
    data class StripRecord(
        val index: Int,
        val top: Int,
        val height: Int
    )

    private val safeStripHeight = stripHeight.coerceIn(16, targetHeight)
    private var currentStripIndex = 0
    private var currentStripTop = 0
    private var stripBitmap: Bitmap? = try {
        Bitmap.createBitmap(targetWidth, safeStripHeight, Bitmap.Config.ARGB_8888)
    } catch (oom: OutOfMemoryError) {
        Bitmap.createBitmap(targetWidth.coerceAtMost(2048), safeStripHeight.coerceAtMost(256), Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        null
    }
    private var stripCanvas: Canvas? = stripBitmap?.let { Canvas(it) }
    private val lock = Any()
    private val stripRecords = Collections.synchronizedList(mutableListOf<StripRecord>())

    init {
        scratchDir.mkdirs()
    }

    override fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val tileTop = (tile.y + tile.coreY) * scale
        val coreH = tile.coreHeight * scale
        val coreW = tile.coreWidth * scale

        synchronized(lock) {
            while (tileTop >= currentStripTop + safeStripHeight && currentStripTop + safeStripHeight < targetHeight) {
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
            val dstRect = Rect(dstX, dstY, dstX + coreW, dstY + coreH)
            stripCanvas?.drawBitmap(processedTile, srcRect, dstRect, null)
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    private fun flushStrip() {
        val actualH = minOf(safeStripHeight, targetHeight - currentStripTop)
        if (actualH <= 0) return

        val stripIndex = currentStripIndex
        val stripTop = currentStripTop

        val bmp = stripBitmap
        if (bmp != null) {
            onStripReady?.invoke(bmp, stripIndex)

            val stripFile = File(scratchDir, "strip_${stripIndex}.raw")
            val pixels = IntArray(targetWidth * actualH)
            bmp.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, actualH)

            stripFile.outputStream().buffered().use { fos ->
                val byteBuf = java.nio.ByteBuffer.allocate(pixels.size * 4)
                byteBuf.asIntBuffer().put(pixels)
                fos.write(byteBuf.array())
            }

            stripRecords.add(StripRecord(stripIndex, stripTop, actualH))
        }

        currentStripIndex++
        currentStripTop += safeStripHeight
        val nextH = minOf(safeStripHeight, targetHeight - currentStripTop)
        if (nextH > 0) {
            stripBitmap?.eraseColor(0)
        }
    }

    override fun complete(): Bitmap? {
        synchronized(lock) {
            if (currentStripTop < targetHeight) {
                flushStrip()
            }
        }

        stripBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }

        val outputBitmap: Bitmap? = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            val maxDim = maxOf(targetWidth, targetHeight)
            val factor = 4096.0f / maxDim
            val downsampledW = (targetWidth * factor).toInt().coerceAtLeast(1)
            val downsampledH = (targetHeight * factor).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(downsampledW, downsampledH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            null
        }

        if (outputBitmap == null) {
            scratchDir.deleteRecursively()
            return null
        }

        val canvas = Canvas(outputBitmap)
        val isDownsampled = outputBitmap.width != targetWidth || outputBitmap.height != targetHeight
        val scaleFactor = if (isDownsampled) outputBitmap.width.toFloat() / targetWidth.toFloat() else 1.0f

        try {
            val sortedStrips = synchronized(stripRecords) { stripRecords.sortedBy { it.index } }
            for (rec in sortedStrips) {
                val stripFile = File(scratchDir, "strip_${rec.index}.raw")
                if (!stripFile.exists()) continue

                val numPixels = targetWidth * rec.height
                val bytes = stripFile.readBytes()
                val byteBuf = java.nio.ByteBuffer.wrap(bytes)
                val pixels = IntArray(numPixels)
                byteBuf.asIntBuffer().get(pixels)

                val stripBmp = Bitmap.createBitmap(targetWidth, rec.height, Bitmap.Config.ARGB_8888)
                stripBmp.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, rec.height)

                if (isDownsampled) {
                    val dstRect = Rect(
                        0,
                        (rec.top * scaleFactor).toInt(),
                        outputBitmap.width,
                        ((rec.top + rec.height) * scaleFactor).toInt()
                    )
                    canvas.drawBitmap(stripBmp, null, dstRect, null)
                } else {
                    canvas.drawBitmap(stripBmp, 0f, rec.top.toFloat(), null)
                }

                if (!stripBmp.isRecycled) {
                    stripBmp.recycle()
                }
                stripFile.delete()
            }
        } finally {
            scratchDir.deleteRecursively()
        }

        return outputBitmap
    }

    override fun close() {
        stripBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        scratchDir.deleteRecursively()
    }
}

/**
 * Tiled intermediate storage sink. Persists raw tile core pixel blocks directly
 * to a scratch directory on disk for ultra-high-resolution images without RAM exhaustion.
 * The complete() method executes the final compositor to stitch tiles into the final Bitmap.
 */
class TiledIntermediateSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val scratchDir: File
) : OutputSink {
    data class TileMeta(
        val index: Int,
        val dstX: Int,
        val dstY: Int,
        val coreW: Int,
        val coreH: Int
    )

    private val tileMetaList = Collections.synchronizedList(mutableListOf<TileMeta>())

    init {
        scratchDir.mkdirs()
    }

    override fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val coreW = tile.coreWidth * scale
        val coreH = tile.coreHeight * scale
        val dstX = (tile.x + tile.coreX) * scale
        val dstY = (tile.y + tile.coreY) * scale

        val tileFile = File(scratchDir, "tile_${tile.index}.raw")

        val pixels = IntArray(coreW * coreH)
        processedTile.getPixels(pixels, 0, coreW, tile.coreX * scale, tile.coreY * scale, coreW, coreH)

        tileFile.outputStream().buffered().use { fos ->
            val byteBuf = java.nio.ByteBuffer.allocate(pixels.size * 4)
            byteBuf.asIntBuffer().put(pixels)
            fos.write(byteBuf.array())
        }

        tileMetaList.add(TileMeta(tile.index, dstX, dstY, coreW, coreH))

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): Bitmap? {
        val outputBitmap: Bitmap? = try {
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            val maxDim = maxOf(targetWidth, targetHeight)
            val factor = 4096.0f / maxDim
            val downsampledW = (targetWidth * factor).toInt().coerceAtLeast(1)
            val downsampledH = (targetHeight * factor).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(downsampledW, downsampledH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            null
        }

        if (outputBitmap == null) {
            scratchDir.deleteRecursively()
            return null
        }

        val canvas = Canvas(outputBitmap)
        val isDownsampled = outputBitmap.width != targetWidth || outputBitmap.height != targetHeight
        val scaleFactor = if (isDownsampled) outputBitmap.width.toFloat() / targetWidth.toFloat() else 1.0f

        try {
            val sortedTiles = synchronized(tileMetaList) { tileMetaList.sortedBy { it.index } }
            for (meta in sortedTiles) {
                val tileFile = File(scratchDir, "tile_${meta.index}.raw")
                if (!tileFile.exists()) continue

                val numPixels = meta.coreW * meta.coreH
                val bytes = tileFile.readBytes()
                val byteBuf = java.nio.ByteBuffer.wrap(bytes)
                val pixels = IntArray(numPixels)
                byteBuf.asIntBuffer().get(pixels)

                val tileBmp = Bitmap.createBitmap(meta.coreW, meta.coreH, Bitmap.Config.ARGB_8888)
                tileBmp.setPixels(pixels, 0, meta.coreW, 0, 0, meta.coreW, meta.coreH)

                if (isDownsampled) {
                    val dstRect = Rect(
                        (meta.dstX * scaleFactor).toInt(),
                        (meta.dstY * scaleFactor).toInt(),
                        ((meta.dstX + meta.coreW) * scaleFactor).toInt(),
                        ((meta.dstY + meta.coreH) * scaleFactor).toInt()
                    )
                    canvas.drawBitmap(tileBmp, null, dstRect, null)
                } else {
                    canvas.drawBitmap(tileBmp, meta.dstX.toFloat(), meta.dstY.toFloat(), null)
                }

                if (!tileBmp.isRecycled) {
                    tileBmp.recycle()
                }
                tileFile.delete()
            }
        } finally {
            scratchDir.deleteRecursively()
        }

        return outputBitmap
    }

    override fun close() {
        scratchDir.deleteRecursively()
    }
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
    ): Bitmap? = withContext(Dispatchers.Default) {
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
            return@withContext sink.complete()
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

        sink.complete()
    }
}
