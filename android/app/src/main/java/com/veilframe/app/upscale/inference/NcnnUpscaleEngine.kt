package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.media.MediaDecoder
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dedicated in-process NCNN neural inference engine.
 * Executes super-resolution using Tencent NCNN with Vulkan compute acceleration
 * and multi-threaded CPU fallback.
 *
 * Implements true dual-ended bounded-memory streaming:
 * - Input: Horizontal strips decoded on-demand via [SourceAccess.region], eliminating full-source bitmap heap allocations.
 * - Output: Horizontal bands streamed scanline-by-scanline directly into [StreamingPngWriter].
 */
class NcnnUpscaleEngine(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {
    companion object {
        private const val TAG = "VeilFrame.NcnnEngine"
        private val executionMutex = Mutex()

        // Upper safety limits to guard against arithmetic overflow or device exhaustion
        const val MAX_SOURCE_DIMENSION = 16384L
        const val MAX_OUTPUT_DIMENSION = 32768L
        const val MAX_SAFE_TOTAL_PIXELS = 100_000_000L // 100 Megapixels
    }

    suspend fun upscale(
        source: SourceAccess,
        model: UpscaleModel,
        targetScale: Int,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        listener: UpscaleInferenceEngine.InferenceProgressListener? = null
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        // 1. Model type check
        require(model.type == ModelType.AI_NCNN) {
            "NcnnUpscaleEngine only processes AI_NCNN models, received ${model.type} for ${model.name}."
        }

        // 2. Strict scale agreement guard
        require(targetScale == model.nativeScale) {
            "NCNN model '${model.name}' natively outputs ${model.nativeScale}x, but requested targetScale was ${targetScale}x. " +
                "Arbitrary or multi-stage scaling must be planned via HybridScalePlan."
        }

        // 3. Overflow-safe input and output dimension validation
        val srcW = source.width
        val srcH = source.height
        require(srcW > 0 && srcH > 0) { "Invalid source dimensions: ${srcW}x${srcH}" }

        require(srcW.toLong() <= MAX_SOURCE_DIMENSION && srcH.toLong() <= MAX_SOURCE_DIMENSION) {
            "Source dimensions (${srcW}x${srcH}) exceed maximum supported dimension ($MAX_SOURCE_DIMENSION px)."
        }

        val outWLong = srcW.toLong() * targetScale.toLong()
        val outHLong = srcH.toLong() * targetScale.toLong()
        require(outWLong <= MAX_OUTPUT_DIMENSION && outHLong <= MAX_OUTPUT_DIMENSION) {
            "Upscaled dimensions (${outWLong}x${outHLong}) exceed maximum safe output dimension ($MAX_OUTPUT_DIMENSION px)."
        }

        val totalPixels = outWLong * outHLong
        require(totalPixels <= MAX_SAFE_TOTAL_PIXELS) {
            "Upscaled image size ($totalPixels pixels) exceeds maximum safe pixel budget ($MAX_SAFE_TOTAL_PIXELS pixels)."
        }

        // 4. Model installation check
        if (!repository.isModelInstalled(model)) {
            throw IllegalStateException("Model ${model.name} (${model.id}) is not installed on this device.")
        }

        // 5. Native library availability check
        if (!NcnnNative.isAvailable) {
            throw IllegalStateException(
                "NCNN native library (libveilframe_ncnn.so) is not available on this device or runtime environment."
            )
        }

        val outW = outWLong.toInt()
        val outH = outHLong.toInt()

        val paramFile = repository.getNcnnParamFile(model.id)
        val binFile = repository.getNcnnBinFile(model.id)
        check(paramFile.exists() && binFile.exists()) {
            "Required NCNN model files missing for ${model.name}: param=${paramFile.exists()}, bin=${binFile.exists()}"
        }

        val outDir = File(context.cacheDir, "upscale_streaming").apply { if (!exists()) mkdirs() }
        val outFile = File(outDir, "ncnn_${System.currentTimeMillis()}_${model.id}.png")

        val gpuCount = NcnnNative.getGpuCount()
        val gpuid = if (gpuCount > 0) 0 else -1
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

        listener?.onStatus(
            if (gpuid >= 0) "Initializing NCNN Vulkan GPU (${NcnnNative.getGpuDeviceName(gpuid)})..."
            else "Initializing NCNN CPU fallback ($numThreads threads)..."
        )
        listener?.onStage("Loading Model", 0, 1)

        val hasAlpha = source.hasAlpha
        val channels = if (hasAlpha) 4 else 3
        val colorType = if (channels == 3) StreamingPngWriter.ColorType.RGB else StreamingPngWriter.ColorType.RGBA

        val writer = StreamingPngWriter(outFile, outW, outH, colorType = colorType)

        executionMutex.withLock {
            val handle = NcnnNative.createInstance(gpuid = gpuid, ttaMode = false, numThreads = numThreads)
            if (handle == 0L) {
                writer.abort()
                throw IllegalStateException("Failed to instantiate native RealSR engine")
            }

            try {
                val tileSize = params.chunkSize.coerceIn(64, 384)
                val prepadding = 10
                val loadRet = NcnnNative.loadModel(
                    handle = handle,
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    scale = model.nativeScale,
                    tilesize = tileSize,
                    prepadding = prepadding
                )
                if (loadRet != 0) {
                    writer.abort()
                    throw IllegalStateException("Failed to load NCNN model weights (code $loadRet)")
                }

                listener?.onStatus("Running ${model.name} via NCNN (${if (gpuid >= 0) "Vulkan GPU" else "CPU"})...")
                listener?.onStage("Neural Inference (NCNN)", 0, 1)

                // Bounded-memory input: allocate buffer for ONLY ONE horizontal strip
                // Maximum height is (tileSize + 2 * prepadding)
                // For 4K (3840x2160), strip buffer is only ~2.7 MB instead of 32 MB!
                // For 8K (7680x4320), strip buffer is only ~5.5 MB instead of 126 MB!
                val maxStripHeight = tileSize + 2 * prepadding
                val stripDirectBuffer = ByteBuffer.allocateDirect(srcW * maxStripHeight * channels).order(ByteOrder.nativeOrder())
                val pixelRow = IntArray(srcW)
                val rgbRow = if (channels == 3) ByteArray(srcW * 3) else null

                val execRet = NcnnNative.processStripStreaming(
                    handle = handle,
                    width = srcW,
                    height = srcH,
                    channels = channels,
                    stripCallback = { buffer, y0, stripH ->
                        if (!coroutineContext.isActive) {
                            return@processStripStreaming 1
                        }
                        buffer.clear()
                        val stripBitmap = source.region(0, y0, srcW, stripH)
                        try {
                            if (channels == 3) {
                                for (y in 0 until stripH) {
                                    stripBitmap.getPixels(pixelRow, 0, srcW, 0, y, srcW, 1)
                                    var ri = 0
                                    for (x in 0 until srcW) {
                                        val p = pixelRow[x]
                                        rgbRow!![ri++] = ((p shr 16) and 0xFF).toByte()
                                        rgbRow[ri++] = ((p shr 8) and 0xFF).toByte()
                                        rgbRow[ri++] = (p and 0xFF).toByte()
                                    }
                                    buffer.put(rgbRow!!)
                                }
                            } else {
                                stripBitmap.copyPixelsToBuffer(buffer)
                            }
                            buffer.position(0)
                            0
                        } finally {
                            stripBitmap.recycle()
                        }
                    },
                    rowCallback = { rowBuffer, _, _, _ ->
                        if (!coroutineContext.isActive) {
                            1 // Cancel native processing
                        } else {
                            writer.writeRow(rowBuffer)
                            0
                        }
                    },
                    progressCallback = { current, total, pct ->
                        listener?.onProgress(current, total, pct)
                        listener?.onStage("Neural Inference (NCNN)", current, total)
                    }
                )

                if (execRet != 0) {
                    writer.abort()
                    throw TileIOException("NCNN super-resolution was cancelled or aborted (code $execRet)")
                }

                writer.close()
            } catch (t: Throwable) {
                writer.abort()
                throw t
            } finally {
                NcnnNative.destroyInstance(handle)
            }
        }

        val preview = MediaDecoder.decodeSampledFile(outFile)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        UpscaleOutput.Streamed(file = outFile, preview = preview, width = outW, height = outH)
    }
}
