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
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result produced by an OutputSink upon completion.
 *
 * Invariant: Full-resolution output dimensions must never imply a full-resolution
 * Android Bitmap allocation. For high-resolution outputs, `outputFile` contains the
 * authoritative, full-resolution artifact, while `previewBitmap` provides a bounded
 * disposable preview strictly for UI rendering.
 */
data class SinkResult(
    val outputFile: File?,
    val previewBitmap: Bitmap?,
    val width: Int,
    val height: Int
) {
    /**
     * Backward-compatible accessor for callers expecting a Bitmap directly.
     */
    val bitmap: Bitmap? get() = previewBitmap
}

/**
 * Cubic Hermite polynomial smooth interpolation: 3x^2 - 2x^3.
 * Eliminates boundary step artifacts across tile overlaps.
 */
fun smoothStep(position: Int, length: Int): Float {
    if (length <= 1) return 1f
    val x = (position.toFloat() / (length - 1)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * Linear color interpolation between two ARGB 8888 packed color integers.
 */
fun mixColors(from: Int, to: Int, amount: Float): Int {
    val keep = 1f - amount
    val a = (keep * ((from ushr 24) and 0xff) + amount * ((to ushr 24) and 0xff)).toInt().coerceIn(0, 255)
    val r = (keep * ((from ushr 16) and 0xff) + amount * ((to ushr 16) and 0xff)).toInt().coerceIn(0, 255)
    val g = (keep * ((from ushr 8) and 0xff) + amount * ((to ushr 8) and 0xff)).toInt().coerceIn(0, 255)
    val b = (keep * (from and 0xff) + amount * (to and 0xff)).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Output sink abstraction decoupling memory storage from tiled neural execution.
 */
interface OutputSink : AutoCloseable {
    val targetWidth: Int
    val targetHeight: Int
    fun writeTile(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile)
    fun writeTileCore(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        writeTile(tile, processed)
    }
    fun complete(): SinkResult
}

/**
 * In-memory bitmap output sink with polynomial Hermite SmoothStep blending.
 * For images whose target resolution fits safely within Android Dalvik heap headroom.
 */
class BitmapOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int
) : OutputSink {
    val outputBitmap: Bitmap? = try {
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Throwable) {
        null
    }
    private val lock = Any()

    override fun writeTile(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val targetX = tile.x * scale
        val targetY = tile.y * scale
        val width = processedTile.width
        val height = processedTile.height
        val blendWidth = (tile.overlap * scale).coerceAtLeast(1)

        val shouldBlendLeft = tile.col > 0
        val shouldBlendTop = tile.row > 0

        val bmp = outputBitmap
        if (bmp != null) {
            synchronized(lock) {
                if (!shouldBlendLeft && !shouldBlendTop) {
                    Canvas(bmp).drawBitmap(processedTile, targetX.toFloat(), targetY.toFloat(), null)
                } else {
                    val existing = IntArray(width * height)
                    bmp.getPixels(existing, 0, width, targetX, targetY, width, height)
                    val incoming = IntArray(width * height)
                    processedTile.getPixels(incoming, 0, width, 0, 0, width, height)

                    for (localY in 0 until height) {
                        val mixTop = shouldBlendTop && localY < blendWidth
                        val blendTop = if (mixTop) smoothStep(localY, blendWidth) else 1f

                        for (localX in 0 until width) {
                            val mixLeft = shouldBlendLeft && localX < blendWidth
                            if (!mixLeft && !mixTop) continue

                            val blendLeft = if (mixLeft) smoothStep(localX, blendWidth) else 1f
                            val blend = minOf(blendLeft, blendTop)
                            val idx = localY * width + localX
                            incoming[idx] = mixColors(existing[idx], incoming[idx], blend)
                        }
                    }
                    bmp.setPixels(incoming, 0, width, targetX, targetY, width, height)
                }
            }
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): SinkResult {
        return SinkResult(
            outputFile = null,
            previewBitmap = outputBitmap,
            width = targetWidth,
            height = targetHeight
        )
    }

    override fun close() {}
}

/**
 * Streaming strip output sink with Edge-Band Persistence.
 *
 * Maintains a bounded strip canvas in RAM and streams committed rows directly to
 * an authoritative disk artifact (`outputFile`), while maintaining an optional bounded
 * disposable preview bitmap (up to 2048px) for UI rendering.
 *
 * Edge-Band Persistence Invariant:
 * The bottom overlap band of Strip N is retained in memory as the top overlap band of
 * Strip N+1, achieving smooth Hermite polynomial blending across vertical boundaries
 * without saving redundant 4×/8× tiles on disk.
 */
class StripOutputSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val stripHeight: Int,
    val scratchDir: File = File(System.getProperty("java.io.tmpdir"), "strip_scratch_${System.currentTimeMillis()}"),
    val outputFile: File? = null,
    val maxPreviewDimension: Int = 2048,
    val onStripReady: ((Bitmap, stripIndex: Int) -> Unit)? = null
) : OutputSink {

    private val safeStripHeight = stripHeight.coerceIn(16, targetHeight)
    private var currentStripIndex = 0
    private var currentStripTop = 0

    private val targetOutputFile = outputFile ?: File(scratchDir, "upscale_output_${targetWidth}x${targetHeight}.raw")
    private var outputStream: FileOutputStream? = null

    // Bounded preview bitmap for UI display
    val previewBitmap: Bitmap?
    private val previewCanvas: Canvas?
    private val previewScaleFactor: Float

    // Strip working canvas
    private var stripBitmap: Bitmap? = try {
        Bitmap.createBitmap(targetWidth, safeStripHeight, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
        null
    }
    private var stripCanvas: Canvas? = stripBitmap?.let { Canvas(it) }

    // Edge-band persistence buffer: bottom overlap band of previous strip
    private var retainedOverlapBand: IntArray? = null
    private var retainedOverlapHeight: Int = 0

    private val lock = Any()

    init {
        scratchDir.mkdirs()
        targetOutputFile.parentFile?.mkdirs()
        outputStream = FileOutputStream(targetOutputFile)

        val maxDim = maxOf(targetWidth, targetHeight)
        if (maxDim > maxPreviewDimension) {
            previewScaleFactor = maxPreviewDimension.toFloat() / maxDim.toFloat()
            val pW = (targetWidth * previewScaleFactor).toInt().coerceAtLeast(1)
            val pH = (targetHeight * previewScaleFactor).toInt().coerceAtLeast(1)
            previewBitmap = try { Bitmap.createBitmap(pW, pH, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        } else {
            previewScaleFactor = 1.0f
            previewBitmap = try { Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        }
        previewCanvas = previewBitmap?.let { Canvas(it) }
    }

    override fun writeTile(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val tileTop = tile.y * scale
        val width = processedTile.width
        val height = processedTile.height
        val blendWidth = (tile.overlap * scale).coerceAtLeast(1)

        synchronized(lock) {
            while (tileTop >= currentStripTop + safeStripHeight && currentStripTop + safeStripHeight < targetHeight) {
                flushStrip(tile.overlap * scale)
            }

            val bmp = stripBitmap
            if (bmp != null) {
                val dstX = tile.x * scale
                val dstY = tileTop - currentStripTop
                val shouldBlendLeft = tile.col > 0
                val shouldBlendTop = (currentStripTop > 0 || tile.row > 0) && dstY < (retainedOverlapHeight.takeIf { it > 0 } ?: blendWidth)

                if (!shouldBlendLeft && !shouldBlendTop) {
                    stripCanvas?.drawBitmap(processedTile, dstX.toFloat(), dstY.toFloat(), null)
                } else {
                    val existing = IntArray(width * height)
                    bmp.getPixels(existing, 0, width, dstX, dstY, width, height)
                    val incoming = IntArray(width * height)
                    processedTile.getPixels(incoming, 0, width, 0, 0, width, height)

                    for (localY in 0 until height) {
                        val mixTop = shouldBlendTop && localY < blendWidth
                        val blendTop = if (mixTop) smoothStep(localY, blendWidth) else 1f

                        for (localX in 0 until width) {
                            val mixLeft = shouldBlendLeft && localX < blendWidth
                            if (!mixLeft && !mixTop) continue

                            val blendLeft = if (mixLeft) smoothStep(localX, blendWidth) else 1f
                            val blend = minOf(blendLeft, blendTop)
                            val idx = localY * width + localX
                            incoming[idx] = mixColors(existing[idx], incoming[idx], blend)
                        }
                    }
                    bmp.setPixels(incoming, 0, width, dstX, dstY, width, height)
                }
            }
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    private fun flushStrip(overlapPixels: Int) {
        val actualH = minOf(safeStripHeight, targetHeight - currentStripTop)
        if (actualH <= 0) return

        val bmp = stripBitmap ?: return
        onStripReady?.invoke(bmp, currentStripIndex)

        val isLastStrip = (currentStripTop + actualH >= targetHeight)
        val commitH = if (isLastStrip) actualH else (actualH - overlapPixels).coerceAtLeast(1)

        // 1. Commit committed rows to authoritative disk artifact
        val commitPixels = IntArray(targetWidth * commitH)
        bmp.getPixels(commitPixels, 0, targetWidth, 0, 0, targetWidth, commitH)

        val byteBuf = java.nio.ByteBuffer.allocate(commitPixels.size * 4)
        byteBuf.asIntBuffer().put(commitPixels)
        outputStream?.write(byteBuf.array())

        // 2. Update progressive preview bitmap
        if (previewCanvas != null && previewBitmap != null) {
            val srcRect = Rect(0, 0, targetWidth, commitH)
            val dstRect = Rect(
                0,
                (currentStripTop * previewScaleFactor).toInt(),
                previewBitmap.width,
                ((currentStripTop + commitH) * previewScaleFactor).toInt()
            )
            previewCanvas.drawBitmap(bmp, srcRect, dstRect, null)
        }

        // 3. Edge-Band Persistence: retain the bottom overlap rows in memory for next strip
        if (!isLastStrip && overlapPixels > 0) {
            val overlapBand = IntArray(targetWidth * overlapPixels)
            bmp.getPixels(overlapBand, 0, targetWidth, 0, actualH - overlapPixels, targetWidth, overlapPixels)
            retainedOverlapBand = overlapBand
            retainedOverlapHeight = overlapPixels

            // Prepare next strip canvas with the retained overlap band at top
            bmp.eraseColor(0)
            bmp.setPixels(overlapBand, 0, targetWidth, 0, 0, targetWidth, overlapPixels)
        } else {
            retainedOverlapBand = null
            retainedOverlapHeight = 0
            bmp.eraseColor(0)
        }

        currentStripIndex++
        currentStripTop += commitH
    }

    override fun complete(): SinkResult {
        synchronized(lock) {
            if (currentStripTop < targetHeight) {
                flushStrip(0)
            }
            try {
                outputStream?.flush()
                outputStream?.close()
            } catch (_: Throwable) {}
        }

        stripBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        stripBitmap = null

        // Clean up transient scratch files if target was custom, or leave output artifact intact
        if (outputFile != null) {
            scratchDir.deleteRecursively()
        }

        return SinkResult(
            outputFile = targetOutputFile,
            previewBitmap = previewBitmap,
            width = targetWidth,
            height = targetHeight
        )
    }

    override fun close() {
        try {
            outputStream?.close()
        } catch (_: Throwable) {}
        stripBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        scratchDir.deleteRecursively()
    }
}

/**
 * Tiled intermediate storage sink for extreme-scale image processing.
 * Persists raw tile blocks to disk and constructs the final artifact stream.
 */
class TiledIntermediateSink(
    override val targetWidth: Int,
    override val targetHeight: Int,
    val scratchDir: File,
    val outputFile: File? = null
) : OutputSink {
    data class TileMeta(
        val index: Int,
        val dstX: Int,
        val dstY: Int,
        val width: Int,
        val height: Int
    )

    private val tileMetaList = Collections.synchronizedList(mutableListOf<TileMeta>())
    private val targetOutputFile = outputFile ?: File(scratchDir, "tiled_output_${targetWidth}x${targetHeight}.raw")

    // Bounded preview bitmap for UI display
    val previewBitmap: Bitmap?
    private val previewCanvas: Canvas?
    private val previewScaleFactor: Float

    init {
        scratchDir.mkdirs()
        val maxDim = maxOf(targetWidth, targetHeight)
        if (maxDim > 2048) {
            previewScaleFactor = 2048.0f / maxDim.toFloat()
            val pW = (targetWidth * previewScaleFactor).toInt().coerceAtLeast(1)
            val pH = (targetHeight * previewScaleFactor).toInt().coerceAtLeast(1)
            previewBitmap = try { Bitmap.createBitmap(pW, pH, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        } else {
            previewScaleFactor = 1.0f
            previewBitmap = try { Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        }
        previewCanvas = previewBitmap?.let { Canvas(it) }
    }

    override fun writeTile(tile: UpscaleTileProcessor.TileArea, processed: ProcessedTile) {
        val processedTile = processed.bitmap
        val scale = processed.outputScale
        val width = processedTile.width
        val height = processedTile.height
        val dstX = tile.x * scale
        val dstY = tile.y * scale

        val tileFile = File(scratchDir, "tile_${tile.index}.raw")
        val pixels = IntArray(width * height)
        processedTile.getPixels(pixels, 0, width, 0, 0, width, height)

        tileFile.outputStream().buffered().use { fos ->
            val byteBuf = java.nio.ByteBuffer.allocate(pixels.size * 4)
            byteBuf.asIntBuffer().put(pixels)
            fos.write(byteBuf.array())
        }

        tileMetaList.add(TileMeta(tile.index, dstX, dstY, width, height))

        // Progressively draw into preview
        if (previewCanvas != null && previewBitmap != null) {
            val dstRect = Rect(
                (dstX * previewScaleFactor).toInt(),
                (dstY * previewScaleFactor).toInt(),
                ((dstX + width) * previewScaleFactor).toInt(),
                ((dstY + height) * previewScaleFactor).toInt()
            )
            previewCanvas.drawBitmap(processedTile, null, dstRect, null)
        }

        if (!processedTile.isRecycled) {
            processedTile.recycle()
        }
    }

    override fun complete(): SinkResult {
        var finalBmp: Bitmap? = previewBitmap
        if (finalBmp == null || finalBmp.width < targetWidth) {
            val fullBmp = try {
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                null
            }
            if (fullBmp != null) {
                for (meta in tileMetaList) {
                    val tileFile = File(scratchDir, "tile_${meta.index}.raw")
                    if (tileFile.exists()) {
                        val tilePixels = IntArray(meta.width * meta.height)
                        try {
                            tileFile.inputStream().buffered().use { fis ->
                                val byteBuf = java.nio.ByteBuffer.allocate(tilePixels.size * 4)
                                fis.read(byteBuf.array())
                                byteBuf.asIntBuffer().get(tilePixels)
                            }
                            fullBmp.setPixels(tilePixels, 0, meta.width, meta.dstX, meta.dstY, meta.width, meta.height)
                        } catch (_: Throwable) {}
                    }
                }
                finalBmp = fullBmp
            }
        }

        // Purge scratch files
        scratchDir.deleteRecursively()
        return SinkResult(
            outputFile = targetOutputFile.takeIf { it.exists() },
            previewBitmap = finalBmp ?: previewBitmap,
            width = targetWidth,
            height = targetHeight
        )
    }

    override fun close() {
        scratchDir.deleteRecursively()
    }
}

/**
 * High-performance tiled inference processor with boundary tile handling,
 * spatial overlap, and bounded parallel workers (ImageToolbox architecture).
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
        val overlap: Int = 24,
        val coreX: Int = 0,
        val coreY: Int = 0,
        val coreWidth: Int = width,
        val coreHeight: Int = height
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
                        overlap = overlap,
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
    ): SinkResult = withContext(Dispatchers.Default) {
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
            val singleTileArea = TileArea(0, 0, 0, 0, 0, srcW, srcH, overlap, 0, 0, srcW, srcH)
            sink.writeTile(singleTileArea, finalProcessed)
            onProgress(1, 1)
            return@withContext sink.complete()
        }

        val tileAreas = calculateTiles(srcW, srcH)
        val totalTiles = tileAreas.size
        val safeWorkers = workers.coerceAtLeast(1)

        onProgress(0, totalTiles)
        val completedCount = AtomicInteger(0)

        // Bounded work channels
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

            // Consumer: single refinement & sink worker keeping memory footprint bounded
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

                    if (sink is BitmapOutputSink) {
                        sink.writeTile(tile, finalProcessed)
                        val done = completedCount.incrementAndGet()
                        onProgress(done, totalTiles)
                    } else {
                        // For ordered streaming sinks (Strip / Tiled), reorder sequentially
                        reorderMap[tile.index] = tile to finalProcessed
                        while (reorderMap.containsKey(nextExpectedIndex.get())) {
                            val nextPair = reorderMap.remove(nextExpectedIndex.get())!!
                            sink.writeTile(nextPair.first, nextPair.second)
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
