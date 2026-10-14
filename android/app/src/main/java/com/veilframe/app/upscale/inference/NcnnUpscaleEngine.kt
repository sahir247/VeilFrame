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
import java.util.UUID
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
 * Implements hardened dual-ended streaming, resource admission, transactional PNG output,
 * and robust cancellation lifecycle (P0 reliability program):
 * - Input: Horizontal strips decoded on-demand via [SourceAccess.region], eliminating full-source bitmap heap allocations.
 * - Output: Horizontal bands streamed scanline-by-scanline directly into [StreamingPngWriter].
 * - Admission: [UpscaleResourceEstimator] bounds memory and adapts tile dimensions before allocating.
 * - Transactions: Outputs are streamed to a `.part` temporary file and validated before atomic publication.
 */
class NcnnUpscaleEngine(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {
    companion object {
        private const val TAG = "VeilFrame.NcnnEngine"
        private val executionMutex = Mutex()

        // Upper safety limits to guard against arithmetic overflow or device exhaustion
        const val MAX_SOURCE_DIMENSION = UpscaleResourceEstimator.MAX_SOURCE_DIMENSION
        const val MAX_OUTPUT_DIMENSION = UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION
        const val MAX_SAFE_TOTAL_PIXELS = UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS

        /**
         * Validates that the written file is a valid PNG with the standard 8-byte signature
         * and matching IHDR dimensions. Throws [ImageValidationException] on corruption or truncation.
         */
        internal fun validatePngFile(file: File, expectedW: Int, expectedH: Int) {
            if (!file.exists() || file.length() < 33) {
                throw ImageValidationException(
                    "Output PNG file is missing or truncated (${if (file.exists()) file.length() else 0} bytes)"
                )
            }
            val header = ByteArray(24)
            file.inputStream().use { stream ->
                val read = stream.read(header)
                if (read < 24) {
                    throw ImageValidationException("Cannot read PNG header: expected 24 bytes, read $read")
                }
            }
            // Check standard PNG signature: 89 50 4E 47 0D 0A 1A 0A
            val expectedSig = byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
            )
            for (i in 0 until 8) {
                if (header[i] != expectedSig[i]) {
                    throw ImageValidationException(
                        "Invalid PNG signature at byte $i: 0x${(header[i].toInt() and 0xFF).toString(16)}"
                    )
                }
            }
            // Check IHDR chunk header (ASCII "IHDR")
            val isIhdr = header[12] == 'I'.code.toByte() &&
                header[13] == 'H'.code.toByte() &&
                header[14] == 'D'.code.toByte() &&
                header[15] == 'R'.code.toByte()
            if (!isIhdr) {
                throw ImageValidationException("PNG missing IHDR chunk header")
            }
            val actualW = ((header[16].toInt() and 0xFF) shl 24) or
                ((header[17].toInt() and 0xFF) shl 16) or
                ((header[18].toInt() and 0xFF) shl 8) or
                (header[19].toInt() and 0xFF)
            val actualH = ((header[20].toInt() and 0xFF) shl 24) or
                ((header[21].toInt() and 0xFF) shl 16) or
                ((header[22].toInt() and 0xFF) shl 8) or
                (header[23].toInt() and 0xFF)

            if (actualW != expectedW || actualH != expectedH) {
                throw ImageValidationException(
                    "PNG IHDR dimension mismatch: expected ${expectedW}x${expectedH}, found ${actualW}x${actualH}"
                )
            }
        }

        /**
         * Atomically publishes [tempFile] to [finalFile].
         */
        internal fun publishFileAtomically(tempFile: File, finalFile: File) {
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                try {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        finalFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
            }
            if (!finalFile.exists() || finalFile.length() == 0L) {
                throw ImageValidationException("Failed to atomically publish validated PNG to ${finalFile.absolutePath}")
            }
        }
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

        // 3. Checked resource admission and adaptive tile derivation (P0.2)
        val hasAlpha = source.hasAlpha
        val channels = if (hasAlpha) 4 else 3
        val plan = UpscaleResourceEstimator.estimateAndPlan(
            srcW = source.width,
            srcH = source.height,
            scale = targetScale,
            channels = channels,
            requestedTileSize = params.chunkSize,
            cacheDir = context.cacheDir
        )

        val outW = plan.outW
        val outH = plan.outH

        // 4. Model installation check
        if (!repository.isModelInstalled(model)) {
            throw ModelNotInstalledException(model.id, model.name)
        }

        // 5. Native library availability check
        if (!NcnnNative.isAvailable) {
            throw IllegalStateException(
                "NCNN native library (libveilframe_ncnn.so) is not available on this device or runtime environment."
            )
        }

        val paramFile = repository.getNcnnParamFile(model.id)
        val binFile = repository.getNcnnBinFile(model.id)
        if (!paramFile.exists() || !binFile.exists()) {
            throw ModelNotInstalledException(model.id, model.name)
        }

        val outDir = File(context.cacheDir, "upscale_streaming").apply { if (!exists()) mkdirs() }
        val jobId = UUID.randomUUID().toString()
        val tempFile = File(outDir, "ncnn_${jobId}.part")
        val finalFile = File(outDir, "ncnn_${jobId}.png")

        val colorType = if (channels == 3) StreamingPngWriter.ColorType.RGB else StreamingPngWriter.ColorType.RGBA
        var writer: StreamingPngWriter? = null
        var isPublished = false

        // Outer lifecycle guard: guarantees writer abortion and temporary file deletion on any error or cancellation (P0.4)
        try {
            writer = StreamingPngWriter(tempFile, outW, outH, colorType = colorType)

            val gpuCount = NcnnNative.getGpuCount()
            val gpuid = if (gpuCount > 0) 0 else -1
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

            listener?.onStatus(
                if (gpuid >= 0) "Initializing NCNN Vulkan GPU (${NcnnNative.getGpuDeviceName(gpuid)})..."
                else "Initializing NCNN CPU fallback ($numThreads threads)..."
            )
            listener?.onStage("Loading Model", 0, 1)

            executionMutex.withLock {
                val handle = NcnnNative.createInstance(gpuid = gpuid, ttaMode = false, numThreads = numThreads)
                if (handle == 0L) {
                    throw DeviceInferenceException(
                        -1,
                        "Failed to instantiate native RealSR engine",
                        if (gpuid >= 0) "Vulkan" else "CPU"
                    )
                }

                try {
                    val loadRet = NcnnNative.loadModel(
                        handle = handle,
                        paramPath = paramFile.absolutePath,
                        binPath = binFile.absolutePath,
                        scale = model.nativeScale,
                        tilesize = plan.tileSize,
                        prepadding = plan.prepadding
                    )
                    if (loadRet != 0) {
                        throw NcnnModelLoadException(loadRet, "Failed to load NCNN model weights from ${paramFile.name}")
                    }

                    listener?.onStatus("Running ${model.name} via NCNN (${if (gpuid >= 0) "Vulkan GPU" else "CPU"})...")
                    listener?.onStage("Neural Inference (NCNN)", 0, 1)

                    val pixelRow = IntArray(source.width)
                    val stripRowBytes = ByteArray(source.width * channels)
                    var writtenRows = 0

                    val execRet = NcnnNative.processStripStreaming(
                        handle = handle,
                        width = source.width,
                        height = source.height,
                        channels = channels,
                        stripCallback = { buffer, y0, stripH ->
                            if (!coroutineContext.isActive) {
                                return@processStripStreaming 1
                            }
                            buffer.clear()
                            val stripBitmap = source.region(0, y0, source.width, stripH)
                            try {
                                for (y in 0 until stripH) {
                                    stripBitmap.getPixels(pixelRow, 0, source.width, 0, y, source.width, 1)
                                    var ri = 0
                                    if (channels == 3) {
                                        for (x in 0 until source.width) {
                                            val p = pixelRow[x]
                                            stripRowBytes[ri++] = ((p ushr 16) and 0xFF).toByte()
                                            stripRowBytes[ri++] = ((p ushr 8) and 0xFF).toByte()
                                            stripRowBytes[ri++] = (p and 0xFF).toByte()
                                        }
                                    } else {
                                        for (x in 0 until source.width) {
                                            val p = pixelRow[x]
                                            stripRowBytes[ri++] = ((p ushr 16) and 0xFF).toByte()
                                            stripRowBytes[ri++] = ((p ushr 8) and 0xFF).toByte()
                                            stripRowBytes[ri++] = (p and 0xFF).toByte()
                                            stripRowBytes[ri++] = ((p ushr 24) and 0xFF).toByte()
                                        }
                                    }
                                    buffer.put(stripRowBytes)
                                }
                                buffer.position(0)
                                0
                            } finally {
                                stripBitmap.recycle()
                            }
                        },
                        rowCallback = { rowBuffer, _, _, _ ->
                            if (!coroutineContext.isActive) {
                                1
                            } else {
                                writer.writeRow(rowBuffer)
                                writtenRows++
                                0
                            }
                        },
                        progressCallback = { current, total, pct ->
                            listener?.onProgress(current, total, pct)
                            listener?.onStage("Neural Inference (NCNN)", current, total)
                        }
                    )

                    when (execRet) {
                        0 -> { /* Success */ }
                        -10 -> throw UpscaleCancellationException("NCNN super-resolution was cancelled")
                        -11 -> throw ResourceLimitExceededException("NCNN native memory allocation failed (bad_alloc)")
                        -12 -> throw DeviceInferenceException(-12, "JNI callback exception during NCNN processing")
                        -2 -> throw DeviceInferenceException(-2, "Vulkan command submission or GPU allocation failed", "Vulkan")
                        -3 -> throw IllegalArgumentException("Invalid arguments provided to NCNN native processing")
                        else -> {
                            if (!coroutineContext.isActive) {
                                throw UpscaleCancellationException("NCNN super-resolution cancelled during execution")
                            } else {
                                throw DeviceInferenceException(execRet, "NCNN super-resolution failed with error code $execRet")
                            }
                        }
                    }

                    if (writtenRows != outH) {
                        throw ImageValidationException("Row count mismatch: expected $outH rows, but received $writtenRows")
                    }

                    writer.close()
                } finally {
                    NcnnNative.destroyInstance(handle)
                }
            }

            // Transactional validation & atomic publication (P0.4)
            validatePngFile(tempFile, outW, outH)
            publishFileAtomically(tempFile, finalFile)
            isPublished = true

            val preview = try {
                MediaDecoder.decodeSampledFile(finalFile)
            } catch (t: Throwable) {
                Log.w(TAG, "Sampled preview generation failed for ${finalFile.name}: ${t.message}")
                null
            }

            UpscaleOutput.Streamed(file = finalFile, preview = preview, width = outW, height = outH)
        } finally {
            if (!isPublished) {
                try {
                    writer?.abort()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to cleanly abort StreamingPngWriter", t)
                }
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }
}
