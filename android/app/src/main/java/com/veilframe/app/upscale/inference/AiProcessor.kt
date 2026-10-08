package com.veilframe.app.upscale.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class AiProcessor(
    private val context: Context
) {
    companion object {
        private const val TAG = "VeilFrame.AiProcessor"
    }

    /**
     * F5: per-job working directory. The old shared `processing_chunks` dir was
     * deleted wholesale in `finally` — concurrent jobs corrupted each other and
     * null tile reads crashed with a bare error(). Each job now owns a UUID dir.
     */
    private fun newJobDir(): File =
        File(context.cacheDir, "processing_chunks/${java.util.UUID.randomUUID()}").apply(File::mkdirs)

    suspend fun processImage(
        session: OrtSession,
        inputBitmap: Bitmap,
        modelName: String,
        scaleFactor: Int? = null,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): UpscaleOutput = processImage(
        session = session,
        source = BitmapSource(inputBitmap) as SourceAccess,
        modelName = modelName,
        scaleFactor = scaleFactor,
        params = params,
        onProgress = onProgress,
        onStatus = onStatus
    )

    /**
     * Primary entry point (F1): the source is a [SourceAccess], so huge images
     * stream tiles from disk instead of living in the Java heap.
     */
    suspend fun processImage(
        session: OrtSession,
        source: SourceAccess,
        modelName: String,
        scaleFactor: Int? = null,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        val info = ModelInfo(
            session = session,
            modelName = modelName,
            explicitScale = scaleFactor,
            chunkSize = params.chunkSize,
            overlap = params.overlap,
            strength = params.strength,
            disableChunking = !params.enableChunking
        )

        val jobDir = newJobDir()
        try {
            renderBitmap(
                session = session,
                source = source,
                info = info,
                jobDir = jobDir,
                parallelWorkers = params.parallelWorkers,
                onProgress = onProgress,
                onStatus = onStatus
            )
        } finally {
            jobDir.deleteRecursively()
        }
    }

    private suspend fun renderBitmap(
        session: OrtSession,
        source: SourceAccess,
        info: ModelInfo,
        jobDir: File,
        parallelWorkers: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        ensureActive()

        val tileLimit = info.tileLimit

        if (source.width > tileLimit || source.height > tileLimit) {
            renderByTiles(
                session = session,
                source = source,
                info = info,
                jobDir = jobDir,
                tileLimit = tileLimit,
                requestedWorkers = parallelWorkers,
                onProgress = onProgress,
                onStatus = onStatus
            )
        } else {
            renderSingleBitmap(
                session = session,
                source = source,
                info = info
            )
        }
    }

    private suspend fun renderSingleBitmap(
        session: OrtSession,
        source: SourceAccess,
        info: ModelInfo
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        // runModelOnBitmap never mutates its input, so the old defensive
        // full-size copy (up to 192 MB on 48 MP sources) is dropped.
        UpscaleOutput.InMemory(
            runModelOnBitmap(
                session = session,
                bitmap = source.full(),
                info = info
            )
        )
    }

    private suspend fun renderByTiles(
        session: OrtSession,
        source: SourceAccess,
        info: ModelInfo,
        jobDir: File,
        tileLimit: Int,
        requestedWorkers: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val grid = TileGrid.from(
            imageWidth = source.width,
            imageHeight = source.height,
            tileLimit = tileLimit,
            overlap = info.overlap
        )

        Log.d(
            TAG,
            "Tile mode ${source.width}x${source.height}, limit=$tileLimit, step=${grid.step}, grid=${grid.columns}x${grid.rows}, overlap=${info.overlap}, jobDir=${jobDir.name}"
        )

        // F5: refuse up-front when the cache cannot hold the streamed tile outputs.
        val outputPixels = source.width.toLong() * info.scaleFactor *
            (source.height.toLong() * info.scaleFactor)
        ensureDiskBudget(jobDir, outputPixels)

        val tiles = grid.tiles { index ->
            TileFiles(
                // Input PNGs are gone (F9-partial): tiles are region-decoded
                // straight from the source. Path kept for struct compatibility.
                input = File(jobDir, "ai_tile_$index.src"),
                output = File(jobDir, "ai_tile_${index}_out.png")
            )
        }

        if (tiles.size > 1) {
            onProgress?.invoke(0, tiles.size)
        }

        processTiles(
            session = session,
            source = source,
            tiles = tiles,
            info = info,
            requestedWorkers = requestedWorkers,
            onProgress = onProgress,
            onStatus = onStatus
        )

        val imageSize = TensorSize(source.width, source.height)
        val outputBytes = imageSize.width.toLong() * info.scaleFactor *
            (imageSize.height.toLong() * info.scaleFactor) * 4L

        if (outputBytes <= inRamOutputBudget()) {
            UpscaleOutput.InMemory(
                composeTiles(
                    tiles = tiles,
                    imageSize = imageSize,
                    overlap = info.overlap,
                    scaleFactor = info.scaleFactor
                )
            )
        } else {
            // F2: band-streaming compose — the full-size output bitmap never exists.
            Log.i(TAG, "Output ${outputBytes / (1024 * 1024)} MB exceeds RAM budget — streaming to PNG")
            onStatus?.invoke("Composing output (streaming to disk)...")
            val outDir = File(context.cacheDir, "upscale_outputs").apply { mkdirs() }
            val outFile = File(outDir, "upscale_${System.currentTimeMillis()}.png")
            composeTilesStreaming(
                tiles = tiles,
                imageSize = imageSize,
                overlap = info.overlap,
                scaleFactor = info.scaleFactor,
                outFile = outFile
            )
        }
    }

    /**
     * Dynamic in-RAM output budget (user policy: 6–16 GB devices with
     * fluctuating availability — neither too conservative nor too aggressive).
     * min(45% of app heap, 220 MB, half of CURRENTLY available system memory).
     */
    internal fun inRamOutputBudget(): Long {
        val heap = Runtime.getRuntime().maxMemory()
        val avail = try {
            com.veilframe.app.cv.core.MemInfoMemoryProbe().availableMemoryBytes()
        } catch (_: Throwable) {
            0L
        }
        var budget = minOf(heap * 45 / 100, 220L * 1024L * 1024L)
        if (avail > 0L) {
            budget = minOf(budget, avail / 2)
        }
        return budget.coerceAtLeast(48L * 1024L * 1024L)
    }

    /** F5: typed, honest disk-space refusal instead of mid-job write failures. */
    private fun ensureDiskBudget(jobDir: File, outputPixels: Long) {
        val estimateBytes = (outputPixels * 4.0 * 0.6).toLong() + 64L * 1024L * 1024L
        val usableBytes = android.os.StatFs(jobDir.absolutePath).usableBytes
        if (usableBytes < estimateBytes) {
            throw InsufficientDiskSpaceException(
                "Need ~${estimateBytes / (1024 * 1024)} MB of cache storage for tiled inference; " +
                    "only ${usableBytes / (1024 * 1024)} MB free"
            )
        }
    }

    private suspend fun processTiles(
        session: OrtSession,
        source: SourceAccess,
        tiles: List<Tile>,
        info: ModelInfo,
        requestedWorkers: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ) {
        val workers = resolveParallelWorkers(
            requestedWorkers = requestedWorkers,
            tileCount = tiles.size
        )

        Log.d(TAG, "Processing ${tiles.size} tile(s), workers=$workers, requested=$requestedWorkers")

        val completedTiles = AtomicInteger(0)

        suspend fun processTile(tile: Tile) {
            ensureActive()
            // F11: cooperative abort before the OS hard-throttles the whole device.
            if (com.veilframe.app.runtime.ThermalGovernor.isCritical) {
                throw ThermalShutdownException(
                    "Device thermal status CRITICAL — job stopped to protect the device"
                )
            }
            // F1/F9-partial: region-decode the tile input (streams from Uri for
            // huge sources), run inference, stream the OUTPUT tile to the job dir.
            val tileBitmap = source.region(
                tile.area.x,
                tile.area.y,
                tile.area.width,
                tile.area.height
            )
            val transformed = try {
                runModelOnBitmap(
                    session = session,
                    bitmap = tileBitmap,
                    info = info
                )
            } finally {
                tileBitmap.recycle()
            }
            try {
                savePng(
                    bitmap = transformed,
                    file = tile.files.output
                )
            } finally {
                transformed.recycle()
            }

            val done = completedTiles.incrementAndGet()
            if (tiles.size > 1) {
                onProgress?.invoke(done, tiles.size)
            }
            onStatus?.invoke("Tile $done of ${tiles.size}")
        }

        // F8: inference runs on a DEDICATED executor whose threads carry
        // THREAD_PRIORITY_BACKGROUND — the UI thread and system compositor keep
        // the fast cores, so the phone stays responsive during upscaling.
        // (Previously tiles ran on the shared Dispatchers.Default pool at
        // default priority, saturating every core.)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workers) { runnable ->
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }.apply {
                name = "vf-ort-tile"
                isDaemon = true
            }
        }
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            // coroutineScope suspends until ALL tile children complete, so the
            // executor is only shut down after every dispatch has run.
            coroutineScope {
                if (workers <= 1) {
                    withContext(dispatcher) {
                        tiles.forEach { tile ->
                            processTile(tile)
                        }
                    }
                } else {
                    val gate = Semaphore(workers)
                    tiles.forEach { tile ->
                        launch(dispatcher) {
                            gate.withPermit {
                                processTile(tile)
                            }
                        }
                    }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    private suspend fun composeTiles(
        tiles: List<Tile>,
        imageSize: TensorSize,
        overlap: Int,
        scaleFactor: Int
    ): Bitmap = withContext(Dispatchers.Default) {
        val outputSize = imageSize.scaledBy(scaleFactor)
        val result = Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888)

        tiles.forEach { tile ->
            ensureActive()
            val tileBitmap = loadBitmap(
                file = tile.files.output,
                label = "processed tile ${tile.index}"
            )

            try {
                drawTile(
                    result = result,
                    tileBitmap = tileBitmap,
                    tile = tile,
                    overlap = overlap,
                    scaleFactor = scaleFactor
                )
            } finally {
                tileBitmap.recycle()
            }
        }

        result
    }

    private suspend fun runModelOnBitmap(
        session: OrtSession,
        bitmap: Bitmap,
        info: ModelInfo
    ): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()

        val sourceSize = TensorSize(bitmap.width, bitmap.height)
        val tensorSize = info.tensorSizeFor(sourceSize)
        val tensorBitmap = bitmap.fitToTensorSize(target = tensorSize)
        val hasAlpha = bitmap.hasAlpha()
        val alpha = if (hasAlpha) FloatArray(tensorSize.pixelCount) else null
        val inputFloats = tensorBitmap.bitmap.readModelInput(
            channels = info.inputChannels,
            alpha = alpha
        )
        val inputShape = longArrayOf(
            1,
            info.inputChannels.toLong(),
            tensorSize.height.toLong(),
            tensorSize.width.toLong()
        )
        val tensors = linkedMapOf<String, OnnxTensor>()

        try {
            tensors[info.inputName] = createInputTensor(
                data = inputFloats,
                shape = inputShape,
                fp16 = info.isFp16
            )
            appendControlInputs(
                destination = tensors,
                info = info
            )

            session.runCancellable(tensors).use { result ->
                val modelOutputSize = tensorSize.scaledBy(info.scaleFactor)
                // Audit fix (perf): for fp32 outputs prefer the flat FloatBuffer
                // (bulk copy) over getValue()'s nested float[][][][] boxing.
                val outputTensor = result[0] as? OnnxTensor
                val outputPayload: Any = if (!info.isFp16 && outputTensor != null) {
                    runCatching { outputTensor.floatBuffer }.getOrNull() ?: result[0].value
                } else {
                    result[0].value
                }
                val (outputFloats, actualChannels) = withContext(Dispatchers.Default) {
                    extractOutputArray(
                        outputValue = outputPayload,
                        channels = info.outputChannels,
                        h = modelOutputSize.height,
                        w = modelOutputSize.width
                    )
                }

                val rendered = renderModelOutput(
                    values = outputFloats,
                    channels = actualChannels,
                    outputSize = modelOutputSize,
                    sourceSize = tensorSize,
                    alpha = alpha,
                    scaleFactor = info.scaleFactor
                )

                if (tensorSize == sourceSize) {
                    rendered
                } else {
                    val cropSize = sourceSize.scaledBy(info.scaleFactor)
                    Bitmap.createBitmap(
                        rendered,
                        0,
                        0,
                        cropSize.width,
                        cropSize.height
                    ).also {
                        rendered.recycle()
                    }
                }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
            if (tensorBitmap.recycleAfterUse) {
                tensorBitmap.bitmap.recycle()
            }
        }
    }

    private suspend fun renderModelOutput(
        values: FloatArray,
        channels: Int,
        outputSize: TensorSize,
        sourceSize: TensorSize,
        alpha: FloatArray?,
        scaleFactor: Int
    ): Bitmap = coroutineScope {
        val pixels = IntArray(outputSize.pixelCount)
        val planeSize = outputSize.pixelCount

        // F10-lite: nested loops with per-ROW ensureActive (was per-PIXEL —
        // millions of coroutine checks per tile) and direct y/x alpha indexing
        // (was a division + modulo per pixel).
        for (y in 0 until outputSize.height) {
            ensureActive()
            val rowBase = y * outputSize.width
            val alphaSourceY = if (alpha != null) (y / scaleFactor).coerceIn(0, sourceSize.height - 1) else 0
            for (x in 0 until outputSize.width) {
                val index = rowBase + x
                val alphaValue = alpha?.let {
                    val sourceX = (x / scaleFactor).coerceIn(0, sourceSize.width - 1)
                    clamp255(it[alphaSourceY * sourceSize.width + sourceX] * AiExtensions.OPAQUE)
                } ?: AiExtensions.OPAQUE

                pixels[index] = if (channels == 1) {
                    val gray = clamp255(values[index] * AiExtensions.OPAQUE)
                    Color.argb(alphaValue, gray, gray, gray)
                } else {
                    Color.argb(
                        alphaValue,
                        clamp255(values[index] * AiExtensions.OPAQUE),
                        clamp255(values[planeSize + index] * AiExtensions.OPAQUE),
                        clamp255(values[planeSize * 2 + index] * AiExtensions.OPAQUE)
                    )
                }
            }
        }

        Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, outputSize.width, 0, 0, outputSize.width, outputSize.height)
        }
    }

    private suspend fun drawTile(
        result: Bitmap,
        tileBitmap: Bitmap,
        tile: Tile,
        overlap: Int,
        scaleFactor: Int
    ) = withContext(Dispatchers.Default) {
        blendTileInto(
            result = result,
            tileBitmap = tileBitmap,
            targetX = tile.area.x * scaleFactor,
            targetY = tile.area.y * scaleFactor,
            blendWidth = overlap * scaleFactor,
            shouldBlendLeft = tile.position.column > 0,
            shouldBlendTop = tile.position.row > 0
        )
    }

    /** Draws [tileBitmap] at explicit target coords with smoothstep overlap blending. */
    private suspend fun blendTileInto(
        result: Bitmap,
        tileBitmap: Bitmap,
        targetX: Int,
        targetY: Int,
        blendWidth: Int,
        shouldBlendLeft: Boolean,
        shouldBlendTop: Boolean
    ) {
        if (!shouldBlendLeft && !shouldBlendTop) {
            Canvas(result).drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return
        }

        val width = tileBitmap.width
        val height = tileBitmap.height
        val existing = IntArray(width * height)
        val incoming = IntArray(width * height)

        try {
            result.getPixels(existing, 0, width, targetX, targetY, width, height)
        } catch (_: Throwable) {
            Canvas(result).drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return
        }

        tileBitmap.getPixels(incoming, 0, width, 0, 0, width, height)

        // F10-lite: iterate only the overlap strips (top rows fully, left
        // columns for the remaining rows) instead of every pixel with continue.
        val topRows = if (shouldBlendTop) minOf(blendWidth, height) else 0
        val leftCols = if (shouldBlendLeft) minOf(blendWidth, width) else 0
        for (localY in 0 until height) {
            ensureActive()
            val mixTop = localY < topRows
            val xEnd = if (mixTop) width else leftCols
            for (localX in 0 until xEnd) {
                val mixLeft = localX < leftCols
                if (!mixTop && !mixLeft) continue

                val blend = minOf(
                    if (mixLeft) smoothStep(localX, blendWidth) else 1f,
                    if (mixTop) smoothStep(localY, blendWidth) else 1f
                )
                val index = localY * width + localX
                incoming[index] = mixColors(
                    from = existing[index],
                    to = incoming[index],
                    amount = blend
                )
            }
        }

        result.setPixels(incoming, 0, width, targetX, targetY, width, height)
    }

    /**
     * F2: band-streaming compose. Tiles are composed one tile-ROW band at a
     * time; each band is deflated straight into [outFile] via
     * [StreamingPngWriter] and recycled, so peak RAM is one band
     * (outWidth x (tile+overlap) x scale x 4 bytes) instead of the whole
     * output (which reached 768 MB for 48 MP at 4x and OOM-killed the app).
     *
     * Vertical overlap blending stays pixel-identical to the in-RAM path:
     * each band carries the previous band's tail rows ([prevTail]) so the
     * smoothstep blend reads the same "existing" content.
     */
    private suspend fun composeTilesStreaming(
        tiles: List<Tile>,
        imageSize: TensorSize,
        overlap: Int,
        scaleFactor: Int,
        outFile: File
    ): UpscaleOutput.Streamed = withContext(Dispatchers.Default) {
        val outW = imageSize.width * scaleFactor
        val outH = imageSize.height * scaleFactor
        val blendPx = overlap * scaleFactor

        val rowsByY = tiles.groupBy { it.position.row }.toSortedMap()
        val maxTileH = rowsByY.values.maxOf { row -> row.maxOf { it.area.height } } * scaleFactor
        val bandBytes = outW.toLong() * (maxTileH + blendPx) * 4L
        val heap = Runtime.getRuntime().maxMemory()
        if (bandBytes > heap * 45 / 100) {
            throw UpscaleMemoryException(
                "Output too wide for band-streaming: one band needs ~${bandBytes / (1024 * 1024)} MB " +
                    "on a ${heap / (1024 * 1024)} MB heap. Use a smaller scale or crop the source."
            )
        }

        val writer = StreamingPngWriter(outFile, outW, outH)
        val rowPixels = IntArray(outW)
        var prevTail: IntArray? = null
        var rowsWritten = 0
        try {
            val rowKeys = rowsByY.keys.toList()
            for ((rowNo, key) in rowKeys.withIndex()) {
                ensureActive()
                val rowTiles = rowsByY.getValue(key).sortedBy { it.position.column }
                val isLast = rowNo == rowKeys.lastIndex
                val rowTopGlobal = rowTiles.first().area.y * scaleFactor
                val areaH = rowTiles.maxOf { it.area.height } * scaleFactor
                val topSkip = if (rowNo > 0 && blendPx > 0) blendPx else 0
                val bandH = topSkip + areaH

                val band = Bitmap.createBitmap(outW, bandH, Bitmap.Config.ARGB_8888)
                try {
                    // The carried rows are the PREVIOUS band's deferred zone —
                    // global rows [rowTop(this), rowTop(this)+blendPx) — so they
                    // belong at y_local [topSkip, topSkip+blendPx), exactly where
                    // this band's tiles start and read their blend "existing"
                    // content. (Placing them at [0, topSkip) shifted every seam
                    // by one overlap — caught in the round-2 self-audit.)
                    if (topSkip > 0) {
                        prevTail?.let { band.setPixels(it, 0, outW, 0, topSkip, outW, topSkip) }
                    }
                    for (tile in rowTiles) {
                        ensureActive()
                        val tb = loadBitmap(tile.files.output, "processed tile ${tile.index}")
                        try {
                            blendTileInto(
                                result = band,
                                tileBitmap = tb,
                                targetX = tile.area.x * scaleFactor,
                                targetY = tile.area.y * scaleFactor - rowTopGlobal + topSkip,
                                blendWidth = blendPx,
                                shouldBlendLeft = tile.position.column > 0,
                                shouldBlendTop = rowNo > 0
                            )
                        } finally {
                            tb.recycle()
                        }
                    }

                    val bottomSkip = if (isLast || blendPx <= 0) 0 else blendPx
                    for (y in topSkip until bandH - bottomSkip) {
                        band.getPixels(rowPixels, 0, outW, 0, y, outW, 1)
                        writer.writeRow(rowPixels)
                        rowsWritten++
                    }
                    prevTail = if (!isLast && blendPx > 0) {
                        IntArray(outW * blendPx).also {
                            band.getPixels(it, 0, outW, 0, bandH - blendPx, outW, blendPx)
                        }
                    } else {
                        null
                    }
                } finally {
                    band.recycle()
                }
            }
            if (rowsWritten != outH) {
                throw TileIOException("Streaming compose wrote $rowsWritten of $outH scanlines")
            }
            writer.close()
        } catch (t: Throwable) {
            writer.abort()
            throw t
        }

        val preview = com.veilframe.app.media.MediaDecoder.decodeSampledFile(outFile)
            ?: throw TileIOException("Streamed output could not be re-decoded for preview: ${outFile.name}")
        UpscaleOutput.Streamed(file = outFile, preview = preview, width = outW, height = outH)
    }

    private fun resolveParallelWorkers(
        requestedWorkers: Int,
        tileCount: Int
    ): Int {
        val detectedCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workers = if (requestedWorkers <= 0) {
            when {
                detectedCores >= 8 -> 4
                detectedCores >= 6 -> 2
                else -> 1
            }
        } else {
            requestedWorkers
        }

        return workers.coerceIn(1, minOf(detectedCores, tileCount.coerceAtLeast(1)))
    }

    private suspend fun savePng(
        bitmap: Bitmap,
        file: File
    ) = withContext(Dispatchers.IO) {
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private suspend fun loadBitmap(
        file: File,
        label: String
    ): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath)
    } ?: throw TileIOException("Could not read $label from ${file.name}")
}
