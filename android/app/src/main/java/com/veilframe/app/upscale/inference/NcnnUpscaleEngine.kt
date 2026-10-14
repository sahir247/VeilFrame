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
            if (!file.isFile || expectedW <= 0 || expectedH <= 0 || file.length() < 57L) {
                throw ImageValidationException(
                    "Output PNG is missing, truncated, or has invalid expected dimensions (${expectedW}x${expectedH})"
                )
            }

            val expectedSig = byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
            )
            val crc = java.util.zip.CRC32()
            val inflater = java.util.zip.Inflater(false)
            val compressedBuffer = ByteArray(8192)
            val decodedBuffer = ByteArray(8192)
            val fileLength = file.length()
            var decodedBytes = 0L
            var scanlineLength = 0L
            var expectedDecodedBytes = 0L
            var idatPayloadBytes = 0L
            var idatClosed = false
            var hasIhdr = false
            var hasIdat = false
            var hasIend = false
            var chunksSeen = 0
            var channels = 0

            fun readInt(input: java.io.DataInputStream): Int {
                val bytes = ByteArray(4)
                input.readFully(bytes)
                return ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)
            }

            fun acceptDecoded(count: Int) {
                if (count <= 0) return
                decodedBytes = Math.addExact(decodedBytes, count.toLong())
                if (decodedBytes > expectedDecodedBytes) {
                    throw ImageValidationException("PNG zlib stream expands beyond the expected pixel data size")
                }
                for (i in 0 until count) {
                    val rowOffset = (decodedBytes - count + i) % scanlineLength
                    if (rowOffset == 0L && decodedBuffer[i].toInt() != 0) {
                        throw ImageValidationException("Unsupported PNG scanline filter; expected filter type 0")
                    }
                }
            }

            fun inflateCompressed(bytesRead: Int) {
                if (bytesRead <= 0) return
                if (inflater.finished()) {
                    throw ImageValidationException("Unexpected compressed bytes after the PNG zlib stream ended")
                }
                inflater.setInput(compressedBuffer, 0, bytesRead)
                while (true) {
                    val produced = try {
                        inflater.inflate(decodedBuffer)
                    } catch (e: java.util.zip.DataFormatException) {
                        throw ImageValidationException("Invalid PNG zlib/DEFLATE stream: ${e.message}")
                    }
                    if (produced > 0) {
                        acceptDecoded(produced)
                        continue
                    }
                    if (inflater.finished()) break
                    if (inflater.needsDictionary()) {
                        throw ImageValidationException("PNG zlib stream unexpectedly requires a preset dictionary")
                    }
                    if (inflater.needsInput()) break
                    throw ImageValidationException("PNG zlib decoder made no progress")
                }
                if (inflater.finished() && inflater.remaining != 0) {
                    throw ImageValidationException("Trailing compressed bytes found after PNG zlib stream")
                }
            }

            try {
                java.io.DataInputStream(file.inputStream().buffered(65536)).use { input ->
                    val signature = ByteArray(8)
                    input.readFully(signature)
                    if (!signature.contentEquals(expectedSig)) {
                        throw ImageValidationException("Invalid PNG signature")
                    }
                    var consumed = 8L

                    while (!hasIend) {
                        if (consumed + 12L > fileLength) {
                            throw ImageValidationException("Truncated PNG: missing complete chunk header or CRC")
                        }
                        val length = readInt(input)
                        val typeBytes = ByteArray(4)
                        input.readFully(typeBytes)
                        consumed += 8L
                        if (length < 0) throw ImageValidationException("Invalid PNG chunk length: $length")
                        if (length.toLong() + 4L > fileLength - consumed) {
                            throw ImageValidationException("Truncated PNG chunk payload")
                        }
                        val type = String(typeBytes, Charsets.US_ASCII)
                        crc.reset()
                        crc.update(typeBytes)
                        chunksSeen++

                        when (type) {
                            "IHDR" -> {
                                if (chunksSeen != 1 || hasIhdr || length != 13) {
                                    throw ImageValidationException("PNG must begin with exactly one 13-byte IHDR chunk")
                                }
                                val ihdr = ByteArray(13)
                                input.readFully(ihdr)
                                consumed += 13L
                                crc.update(ihdr)
                                val actualW = readBeInt(ihdr, 0)
                                val actualH = readBeInt(ihdr, 4)
                                if (actualW != expectedW || actualH != expectedH) {
                                    throw ImageValidationException(
                                        "PNG dimension mismatch: expected ${expectedW}x${expectedH}, found ${actualW}x${actualH}"
                                    )
                                }
                                if ((ihdr[8].toInt() and 0xFF) != 8) {
                                    throw ImageValidationException("Only 8-bit PNG output is supported")
                                }
                                channels = when (ihdr[9].toInt() and 0xFF) {
                                    2 -> 3
                                    6 -> 4
                                    else -> throw ImageValidationException("Only RGB/RGBA PNG output is supported")
                                }
                                if (ihdr[10].toInt() != 0 || ihdr[11].toInt() != 0 || ihdr[12].toInt() != 0) {
                                    throw ImageValidationException("Unsupported PNG compression, filter, or interlace method")
                                }
                                scanlineLength = Math.addExact(Math.multiplyExact(expectedW.toLong(), channels.toLong()), 1L)
                                expectedDecodedBytes = Math.multiplyExact(scanlineLength, expectedH.toLong())
                                hasIhdr = true
                            }
                            "IDAT" -> {
                                if (!hasIhdr || idatClosed || inflater.finished()) {
                                    throw ImageValidationException("PNG IDAT chunks must be contiguous and follow IHDR")
                                }
                                hasIdat = true
                                var remaining = length
                                while (remaining > 0) {
                                    val n = minOf(remaining, compressedBuffer.size)
                                    input.readFully(compressedBuffer, 0, n)
                                    consumed += n.toLong()
                                    crc.update(compressedBuffer, 0, n)
                                    idatPayloadBytes = Math.addExact(idatPayloadBytes, n.toLong())
                                    inflateCompressed(n)
                                    remaining -= n
                                }
                            }
                            "IEND" -> {
                                if (!hasIhdr || !hasIdat || length != 0) {
                                    throw ImageValidationException("Invalid PNG IEND placement or length")
                                }
                                if (!inflater.finished() || decodedBytes != expectedDecodedBytes) {
                                    throw ImageValidationException(
                                        "PNG zlib stream is incomplete: decoded $decodedBytes of $expectedDecodedBytes expected bytes"
                                    )
                                }
                                hasIend = true
                            }
                            else -> throw ImageValidationException("Unexpected PNG chunk '$type'; only IHDR, contiguous IDAT, and IEND are supported")
                        }

                        val actualCrc = crc.value
                        val expectedCrc = readInt(input).toLong() and 0xFFFFFFFFL
                        consumed += 4L
                        if (actualCrc != expectedCrc) {
                            throw ImageValidationException("PNG CRC mismatch in $type chunk")
                        }

                        if (type == "IEND") {
                            if (input.read() != -1) {
                                throw ImageValidationException("Extraneous trailing bytes found after PNG IEND")
                            }
                            if (consumed != fileLength) {
                                throw ImageValidationException("PNG length accounting mismatch")
                            }
                        } else if (type != "IDAT" && hasIdat) {
                            idatClosed = true
                        }
                    }

                    if (!hasIhdr || !hasIdat || !hasIend || idatPayloadBytes == 0L) {
                        throw ImageValidationException("PNG is missing required image chunks")
                    }
                }
            } catch (e: ImageValidationException) {
                throw e
            } catch (e: ArithmeticException) {
                throw ImageValidationException("PNG dimensions overflow the validation bounds")
            } catch (e: java.io.EOFException) {
                throw ImageValidationException("Unexpected EOF while validating PNG")
            } finally {
                inflater.end()
            }
        }

        private fun readBeInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        /**
         * Atomically publishes [tempFile] to [finalFile] using a same-directory staging swap.
         * Ensures [finalFile] is never visible in a partial or corrupt state.
         */
        internal fun publishFileAtomically(tempFile: File, finalFile: File) {
            val parent = finalFile.parentFile
                ?: throw ImageValidationException("Final PNG has no parent directory")
            if (!parent.isDirectory || !parent.canWrite()) {
                throw ImageValidationException("Output directory is missing or not writable: ${parent.absolutePath}")
            }
            if (tempFile.parentFile?.canonicalFile != parent.canonicalFile) {
                throw ImageValidationException("Atomic publication requires source and destination in the same directory")
            }
            if (!tempFile.isFile || tempFile.length() <= 0L) {
                throw ImageValidationException("Validated temporary PNG is missing or empty")
            }
            if (finalFile.exists()) {
                throw ImageValidationException("Refusing to replace an existing output file: ${finalFile.absolutePath}")
            }

            try {
                // Do not silently downgrade to copy/rename: those paths can expose partial output.
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    finalFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                throw ImageValidationException("Filesystem does not support atomic PNG publication")
            } catch (e: java.io.IOException) {
                throw ImageValidationException("Atomic PNG publication failed: ${e.message}")
            }

            if (!finalFile.isFile || finalFile.length() <= 0L || tempFile.exists()) {
                throw ImageValidationException("Atomic PNG publication postcondition failed")
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

        val outDir = File(context.cacheDir, "upscale_streaming")
        if ((!outDir.exists() && !outDir.mkdirs()) || !outDir.isDirectory || !outDir.canWrite()) {
            throw InsufficientDiskSpaceException("Upscale cache output directory is unavailable or not writable: ${outDir.absolutePath}")
        }
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
            val preferredGpuid = if (gpuCount > 0) 0 else -1
            val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val backendAttempts = if (preferredGpuid >= 0) listOf(preferredGpuid, -1) else listOf(-1)
            var inferenceSucceeded = false
            var lastGpuFailure: DeviceInferenceException? = null

            for ((attemptIndex, attemptGpuid) in backendAttempts.withIndex()) {
                if (attemptIndex > 0) {
                    // A failed GPU run may already have written rows. It must never be
                    // resumed into the same PNG stream; discard it and start from row zero.
                    writer?.abort()
                    if (tempFile.exists() && !tempFile.delete()) {
                        throw ImageValidationException("Could not discard partial Vulkan output before CPU retry")
                    }
                    writer = StreamingPngWriter(tempFile, outW, outH, colorType = colorType)
                }
                val attemptWriter = writer
                    ?: throw ImageValidationException("PNG writer was not initialized for inference")

                try {
                    executionMutex.withLock {
                        val handle = NcnnNative.createInstance(
                            gpuid = attemptGpuid,
                            ttaMode = false,
                            numThreads = numThreads
                        )
                        if (handle == 0L) {
                            throw DeviceInferenceException(
                                -1,
                                "Failed to instantiate native RealSR engine",
                                if (attemptGpuid >= 0) "Vulkan" else "CPU"
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
                            when (loadRet) {
                                0 -> Unit
                                NcnnNative.STATUS_VULKAN_FAILURE -> throw DeviceInferenceException(
                                    NcnnNative.STATUS_VULKAN_FAILURE,
                                    "Vulkan/NCNN pipeline initialization failed; retrying with a clean CPU instance may succeed",
                                    "Vulkan"
                                )
                                NcnnNative.STATUS_CPU_FAILURE -> throw DeviceInferenceException(
                                    NcnnNative.STATUS_CPU_FAILURE,
                                    "NCNN CPU pipeline initialization failed",
                                    "CPU"
                                )
                                NcnnNative.STATUS_OUT_OF_MEMORY -> throw ResourceLimitExceededException(
                                    "NCNN native memory allocation failed while loading the model"
                                )
                                NcnnNative.STATUS_INVALID_ARGUMENT -> throw IllegalArgumentException(
                                    "Invalid arguments provided to NCNN model loading"
                                )
                                else -> throw NcnnModelLoadException(
                                    loadRet,
                                    "Failed to load or initialize NCNN model ${paramFile.name}"
                                )
                            }

                            val backendName = if (attemptGpuid >= 0) "Vulkan GPU" else "CPU"
                            listener?.onStatus("Running ${model.name} via NCNN ($backendName)...")
                            listener?.onStage("Neural Inference (NCNN)", 0, 1)

                            val pixelRow = IntArray(source.width)
                            val stripRowBytes = ByteArray(Math.multiplyExact(source.width, channels))
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
                                        attemptWriter.writeRow(rowBuffer)
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
                                0 -> Unit
                                -10 -> throw UpscaleCancellationException("NCNN super-resolution was cancelled")
                                -11 -> throw ResourceLimitExceededException("NCNN native/device tensor allocation failed")
                                -12 -> throw DeviceInferenceException(-12, "JNI callback exception during NCNN processing")
                                -2 -> throw DeviceInferenceException(
                                    -2,
                                    "NCNN Vulkan execution failed; a clean CPU retry may succeed",
                                    "Vulkan"
                                )
                                -3 -> throw IllegalArgumentException("Invalid arguments provided to NCNN native processing")
                                -4 -> throw DeviceInferenceException(
                                    -4,
                                    "NCNN CPU inference failed",
                                    "CPU"
                                )
                                else -> {
                                    if (!coroutineContext.isActive) {
                                        throw UpscaleCancellationException("NCNN super-resolution cancelled during execution")
                                    } else {
                                        throw DeviceInferenceException(
                                            execRet,
                                            "NCNN inference failed with normalized error code $execRet",
                                            if (attemptGpuid >= 0) "Vulkan" else "CPU"
                                        )
                                    }
                                }
                            }

                            if (writtenRows != outH) {
                                throw ImageValidationException(
                                    "Row count mismatch: expected $outH rows, but received $writtenRows"
                                )
                            }
                            attemptWriter.close()
                        } finally {
                            NcnnNative.destroyInstance(handle)
                        }
                    }
                    inferenceSucceeded = true
                    break
                } catch (e: DeviceInferenceException) {
                    val mayRetryOnCpu = attemptGpuid >= 0 &&
                        (e.code == -1 || e.code == -2) &&
                        e.backend == "Vulkan" &&
                        coroutineContext.isActive
                    if (!mayRetryOnCpu) throw e
                    lastGpuFailure = e
                    Log.w(TAG, "Vulkan upscaling failed (code ${e.code}); discarding partial output and retrying once on CPU", e)
                }
            }

            if (!inferenceSucceeded) {
                throw lastGpuFailure ?: DeviceInferenceException(-4, "NCNN inference did not complete on any backend", "CPU")
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
