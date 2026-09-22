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

    private val chunksDir: File
        get() = File(context.cacheDir, "processing_chunks").apply(File::mkdirs)

    suspend fun processImage(
        session: OrtSession,
        inputBitmap: Bitmap,
        modelName: String,
        scaleFactor: Int? = null,
        chunkSize: Int = 512,
        overlap: Int = 16,
        disableChunking: Boolean = false,
        parallelWorkers: Int = 0,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val info = ModelInfo(
            session = session,
            modelName = modelName,
            explicitScale = scaleFactor,
            chunkSize = chunkSize,
            overlap = overlap,
            disableChunking = disableChunking
        )

        try {
            renderBitmap(
                session = session,
                source = inputBitmap,
                info = info,
                parallelWorkers = parallelWorkers,
                onProgress = onProgress,
                onStatus = onStatus
            )
        } finally {
            chunksDir.deleteRecursively()
        }
    }

    private suspend fun renderBitmap(
        session: OrtSession,
        source: Bitmap,
        info: ModelInfo,
        parallelWorkers: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        ensureActive()

        val tileLimit = info.tileLimit

        if (source.width > tileLimit || source.height > tileLimit) {
            renderByTiles(
                session = session,
                source = source,
                info = info,
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
        source: Bitmap,
        info: ModelInfo
    ): Bitmap = withContext(Dispatchers.Default) {
        val workingCopy = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            runModelOnBitmap(
                session = session,
                bitmap = workingCopy,
                info = info
            )
        } finally {
            workingCopy.recycle()
        }
    }

    private suspend fun renderByTiles(
        session: OrtSession,
        source: Bitmap,
        info: ModelInfo,
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
            "Tile mode ${source.width}x${source.height}, limit=$tileLimit, step=${grid.step}, grid=${grid.columns}x${grid.rows}, overlap=${info.overlap}"
        )

        val tiles = grid.tiles { index ->
            TileFiles(
                input = File(chunksDir, "ai_tile_$index.png"),
                output = File(chunksDir, "ai_tile_${index}_out.png")
            )
        }

        try {
            writeSourceTiles(source, tiles)
            Log.d(TAG, "Stored ${tiles.size} tile(s) for AI processing")

            if (tiles.size > 1) {
                onProgress?.invoke(0, tiles.size)
            }

            processTiles(
                session = session,
                tiles = tiles,
                info = info,
                requestedWorkers = requestedWorkers,
                onProgress = onProgress,
                onStatus = onStatus
            )

            composeTiles(
                tiles = tiles,
                imageSize = TensorSize(source.width, source.height),
                overlap = info.overlap,
                scaleFactor = info.scaleFactor
            )
        } finally {
            chunksDir.deleteRecursively()
        }
    }

    private suspend fun processTiles(
        session: OrtSession,
        tiles: List<Tile>,
        info: ModelInfo,
        requestedWorkers: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ) = coroutineScope {
        val workers = resolveParallelWorkers(
            requestedWorkers = requestedWorkers,
            tileCount = tiles.size
        )

        Log.d(TAG, "Processing ${tiles.size} tile(s), workers=$workers, requested=$requestedWorkers")

        val completedTiles = AtomicInteger(0)

        suspend fun processTile(tile: Tile) {
            ensureActive()
            transformStoredTile(
                session = session,
                tile = tile,
                info = info
            )

            val done = completedTiles.incrementAndGet()
            if (tiles.size > 1) {
                onProgress?.invoke(done, tiles.size)
            }
            onStatus?.invoke("Tile $done of ${tiles.size}")
        }

        if (workers <= 1) {
            tiles.forEach { tile ->
                processTile(tile)
            }
        } else {
            val gate = Semaphore(workers)

            tiles.forEach { tile ->
                launch {
                    gate.withPermit {
                        processTile(tile)
                    }
                }
            }
        }
    }

    private suspend fun writeSourceTiles(
        source: Bitmap,
        tiles: List<Tile>
    ) = coroutineScope {
        tiles.forEach { tile ->
            ensureActive()
            val extracted = Bitmap.createBitmap(
                source,
                tile.area.x,
                tile.area.y,
                tile.area.width,
                tile.area.height
            )

            try {
                savePng(
                    bitmap = extracted,
                    file = tile.files.input
                )
            } finally {
                extracted.recycle()
            }
        }
    }

    private suspend fun transformStoredTile(
        session: OrtSession,
        tile: Tile,
        info: ModelInfo
    ) {
        val tileBitmap = loadBitmap(
            file = tile.files.input,
            label = "tile ${tile.index}"
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
            withContext(Dispatchers.IO) {
                tile.files.input.delete()
            }
        } finally {
            transformed.recycle()
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
                val (outputFloats, actualChannels) = withContext(Dispatchers.Default) {
                    extractOutputArray(
                        outputValue = result[0].value,
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

        for (index in pixels.indices) {
            ensureActive()
            val alphaValue = alpha?.let {
                val sourceY = ((index / outputSize.width) / scaleFactor)
                    .coerceIn(0, sourceSize.height - 1)
                val sourceX = ((index % outputSize.width) / scaleFactor)
                    .coerceIn(0, sourceSize.width - 1)
                clamp255(it[sourceY * sourceSize.width + sourceX] * AiExtensions.OPAQUE)
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
        val targetX = tile.area.x * scaleFactor
        val targetY = tile.area.y * scaleFactor
        val blendWidth = overlap * scaleFactor
        val shouldBlendLeft = tile.position.column > 0
        val shouldBlendTop = tile.position.row > 0

        if (!shouldBlendLeft && !shouldBlendTop) {
            Canvas(result).drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return@withContext
        }

        val width = tileBitmap.width
        val height = tileBitmap.height
        val existing = IntArray(width * height)
        val incoming = IntArray(width * height)

        try {
            result.getPixels(existing, 0, width, targetX, targetY, width, height)
        } catch (_: Throwable) {
            Canvas(result).drawBitmap(tileBitmap, targetX.toFloat(), targetY.toFloat(), null)
            return@withContext
        }

        tileBitmap.getPixels(incoming, 0, width, 0, 0, width, height)

        for (localY in 0 until height) {
            for (localX in 0 until width) {
                ensureActive()

                val mixLeft = shouldBlendLeft && localX < blendWidth
                val mixTop = shouldBlendTop && localY < blendWidth
                if (!mixLeft && !mixTop) continue

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
    } ?: error("Could not read $label")
}
