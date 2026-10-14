package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.media.MediaDecoder
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
 * and multi-threaded CPU fallback, streaming scanlines directly into [StreamingPngWriter].
 */
class NcnnUpscaleEngine(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {
    companion object {
        private const val TAG = "VeilFrame.NcnnEngine"
        private val executionMutex = Mutex()
    }

    suspend fun upscale(
        source: SourceAccess,
        model: UpscaleModel,
        targetScale: Int,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        listener: UpscaleInferenceEngine.InferenceProgressListener? = null
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        if (!repository.isModelInstalled(model)) {
            throw IllegalStateException("Model ${model.name} (${model.id}) is not installed on this device.")
        }

        if (!NcnnNative.isAvailable) {
            throw IllegalStateException(
                "NCNN native library (libveilframe_ncnn.so) is not available on this device or runtime environment."
            )
        }

        val paramFile = repository.getNcnnParamFile(model.id)
        val binFile = repository.getNcnnBinFile(model.id)
        check(paramFile.exists() && binFile.exists()) {
            "Required NCNN model files missing for ${model.name}: param=${paramFile.exists()}, bin=${binFile.exists()}"
        }

        val srcW = source.width
        val srcH = source.height
        val outW = srcW * targetScale
        val outH = srcH * targetScale

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

        val fullBitmap = source.full()
        val hasAlpha = fullBitmap.hasAlpha()
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
                val loadRet = NcnnNative.loadModel(
                    handle = handle,
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    scale = model.nativeScale,
                    tilesize = tileSize,
                    prepadding = 10
                )
                if (loadRet != 0) {
                    writer.abort()
                    throw IllegalStateException("Failed to load NCNN model weights (code $loadRet)")
                }

                listener?.onStatus("Running ${model.name} via NCNN (${if (gpuid >= 0) "Vulkan GPU" else "CPU"})...")
                listener?.onStage("Neural Inference (NCNN)", 0, 1)

                val inputBuffer = ByteBuffer.allocateDirect(srcW * srcH * channels).order(ByteOrder.nativeOrder())
                if (channels == 3) {
                    val row = IntArray(srcW)
                    val rgbRow = ByteArray(srcW * 3)
                    for (y in 0 until srcH) {
                        fullBitmap.getPixels(row, 0, srcW, 0, y, srcW, 1)
                        var ri = 0
                        for (x in 0 until srcW) {
                            val p = row[x]
                            rgbRow[ri++] = ((p shr 16) and 0xFF).toByte()
                            rgbRow[ri++] = ((p shr 8) and 0xFF).toByte()
                            rgbRow[ri++] = (p and 0xFF).toByte()
                        }
                        inputBuffer.put(rgbRow)
                    }
                } else {
                    fullBitmap.copyPixelsToBuffer(inputBuffer)
                }
                inputBuffer.position(0)

                val execRet = NcnnNative.processStreaming(
                    handle = handle,
                    inputDirectBuffer = inputBuffer,
                    width = srcW,
                    height = srcH,
                    channels = channels,
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
            ?: Bitmap.createScaledBitmap(fullBitmap, fullBitmap.width.coerceAtMost(1024), fullBitmap.height.coerceAtMost(1024), true)

        UpscaleOutput.Streamed(file = outFile, preview = preview, width = outW, height = outH)
    }
}
