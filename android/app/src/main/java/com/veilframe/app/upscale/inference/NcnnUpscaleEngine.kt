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
         * Validates that the written file is a complete, well-formed PNG file:
         * 1. Standard 8-byte PNG signature.
         * 2. Sequential chunk verification: reads every chunk header (Length, Type), computes and
         *    verifies CRC32 over chunk type + chunk data.
         * 3. IHDR verification: dimensions match expected [expectedW] and [expectedH].
         * 4. IDAT verification: at least one IDAT chunk exists with compressed image data.
         * 5. IEND verification: terminates with an intact IEND chunk and zero trailing garbage.
         * 6. Validates without loading uncompressed full-size pixel arrays into RAM.
         */
        internal fun validatePngFile(file: File, expectedW: Int, expectedH: Int) {
            if (!file.exists() || file.length() < 33) {
                throw ImageValidationException(
                    "Output PNG file is missing or truncated (${if (file.exists()) file.length() else 0} bytes)"
                )
            }

            val expectedSig = byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
            )

            file.inputStream().buffered(65536).use { stream ->
                // 1. Signature check
                val sig = ByteArray(8)
                val sigRead = stream.read(sig)
                if (sigRead != 8 || !sig.contentEquals(expectedSig)) {
                    throw ImageValidationException("Invalid PNG signature")
                }

                val buffer4 = ByteArray(4)
                fun readInt(): Int {
                    val r = stream.read(buffer4)
                    if (r != 4) throw ImageValidationException("Unexpected EOF while reading PNG chunk header")
                    return ((buffer4[0].toInt() and 0xFF) shl 24) or
                        ((buffer4[1].toInt() and 0xFF) shl 16) or
                        ((buffer4[2].toInt() and 0xFF) shl 8) or
                        (buffer4[3].toInt() and 0xFF)
                }

                var hasIhdr = false
                var hasIdat = false
                var hasIend = false
                val crc = java.util.zip.CRC32()
                val chunkSkipBuf = ByteArray(8192)

                while (true) {
                    val lengthBytesRead = stream.read(buffer4)
                    if (lengthBytesRead == -1) {
                        break // End of stream
                    }
                    if (lengthBytesRead != 4) {
                        throw ImageValidationException("Truncated PNG: incomplete chunk length")
                    }
                    val length = ((buffer4[0].toInt() and 0xFF) shl 24) or
                        ((buffer4[1].toInt() and 0xFF) shl 16) or
                        ((buffer4[2].toInt() and 0xFF) shl 8) or
                        (buffer4[3].toInt() and 0xFF)
                    if (length < 0) {
                        throw ImageValidationException("Invalid negative PNG chunk length: $length")
                    }

                    val typeBytes = ByteArray(4)
                    if (stream.read(typeBytes) != 4) {
                        throw ImageValidationException("Truncated PNG: incomplete chunk type")
                    }
                    val typeStr = String(typeBytes, Charsets.US_ASCII)

                    crc.reset()
                    crc.update(typeBytes)

                    if (typeStr == "IHDR") {
                        if (hasIhdr) throw ImageValidationException("Duplicate IHDR chunk found")
                        if (length != 13) throw ImageValidationException("Invalid IHDR chunk length: $length")
                        val ihdrData = ByteArray(13)
                        if (stream.read(ihdrData) != 13) throw ImageValidationException("Truncated IHDR data")
                        crc.update(ihdrData)

                        val actualW = ((ihdrData[0].toInt() and 0xFF) shl 24) or
                            ((ihdrData[1].toInt() and 0xFF) shl 16) or
                            ((ihdrData[2].toInt() and 0xFF) shl 8) or
                            (ihdrData[3].toInt() and 0xFF)
                        val actualH = ((ihdrData[4].toInt() and 0xFF) shl 24) or
                            ((ihdrData[5].toInt() and 0xFF) shl 16) or
                            ((ihdrData[6].toInt() and 0xFF) shl 8) or
                            (ihdrData[7].toInt() and 0xFF)

                        if (actualW != expectedW || actualH != expectedH) {
                            throw ImageValidationException(
                                "PNG IHDR dimension mismatch: expected ${expectedW}x${expectedH}, found ${actualW}x${actualH}"
                            )
                        }
                        hasIhdr = true
                    } else {
                        if (!hasIhdr) {
                            throw ImageValidationException("First chunk in PNG must be IHDR, found $typeStr")
                        }
                        if (typeStr == "IDAT") {
                            hasIdat = true
                        } else if (typeStr == "IEND") {
                            hasIend = true
                        }
                        // Read chunk data and feed to CRC
                        var remaining = length
                        while (remaining > 0) {
                            val toRead = minOf(remaining, chunkSkipBuf.size)
                            val read = stream.read(chunkSkipBuf, 0, toRead)
                            if (read == -1) throw ImageValidationException("Truncated data in chunk $typeStr")
                            crc.update(chunkSkipBuf, 0, read)
                            remaining -= read
                        }
                    }

                    // Read 4-byte CRC
                    val expectedCrc = readInt().toLong() and 0xFFFFFFFFL
                    val actualCrc = crc.value
                    if (actualCrc != expectedCrc) {
                        throw ImageValidationException("PNG CRC mismatch in chunk $typeStr: expected $expectedCrc, got $actualCrc")
                    }

                    if (typeStr == "IEND") {
                        // After IEND, no trailing data is allowed
                        val trailing = stream.read()
                        if (trailing != -1) {
                            throw ImageValidationException("Extraneous trailing bytes found after PNG IEND chunk")
                        }
                        break
                    }
                }

                if (!hasIhdr) throw ImageValidationException("PNG missing IHDR chunk")
                if (!hasIdat) throw ImageValidationException("PNG missing IDAT image data chunks")
                if (!hasIend) throw ImageValidationException("PNG missing IEND termination chunk")
            }
        }

        /**
         * Atomically publishes [tempFile] to [finalFile] using a same-directory staging swap.
         * Ensures [finalFile] is never visible in a partial or corrupt state.
         */
        internal fun publishFileAtomically(tempFile: File, finalFile: File) {
            val parentDir = finalFile.parentFile ?: tempFile.parentFile ?: File(".")
            val stagingFile = File(parentDir, "${finalFile.name}.staging_${UUID.randomUUID()}")

            try {
                // First attempt direct atomic move
                val movedDirect = try {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        finalFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                } catch (e: Exception) {
                    false
                }

                if (!movedDirect) {
                    // Staging fallback: copy to stagingFile first, then atomic rename
                    if (!tempFile.renameTo(stagingFile)) {
                        tempFile.copyTo(stagingFile, overwrite = true)
                        tempFile.delete()
                    }
                    if (!stagingFile.renameTo(finalFile)) {
                        java.nio.file.Files.move(
                            stagingFile.toPath(),
                            finalFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                }

                if (!finalFile.exists() || finalFile.length() == 0L) {
                    throw ImageValidationException("Failed to atomically publish validated PNG to ${finalFile.absolutePath}")
                }
            } finally {
                if (stagingFile.exists()) {
                    stagingFile.delete()
                }
                if (tempFile.exists()) {
                    tempFile.delete()
                }
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
