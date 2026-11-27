package com.veilframe.app.qr.exporter

import android.content.Context
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.media.executeOwnedFfmpeg
import com.veilframe.app.qr.AnimatedQrGenerator.AnimatedScanabilityReport
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.validation.ScanabilityReport
import com.veilframe.app.qr.validation.ScanabilityValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Validate decoded output pixels, never regenerate a QR from those pixels. */
internal object AnimatedArtifactVerifier {
    private const val MAX_FRAMES = 600
    private const val DISK_BUDGET = 512L * 1024 * 1024

    internal fun frameLimit(size: Int): Int {
        require(size in 1..4096) { "Unsupported animation dimensions" }
        // Conservative PNG output bound, including per-file overhead. Reserve
        // one extra frame to distinguish a complete decode from a truncated one.
        val bytesPerFrame = size.toLong() * size * 5 + 64 * 1024
        return minOf(MAX_FRAMES.toLong(), DISK_BUDGET / bytesPerFrame - 1)
            .toInt().coerceAtLeast(0)
    }

    internal fun requireCompleteFrameCount(count: Int, limit: Int) {
        require(count in 1..limit) {
            if (count > limit) "Animation exceeds complete verification budget; shorten it or reduce resolution"
            else "Animation contained no decodable frames"
        }
    }

    suspend fun verify(context: Context, artifact: File, matrix: QrMatrix,
                       design: QrDesign, expectedContent: String): AnimatedScanabilityReport =
        withContext(Dispatchers.IO) {
            val reports = mutableListOf<ScanabilityReport>()
            val errors = mutableListOf<String>()
            var totalFrames = 0
            var directory: File? = null
            try {
                val limit = frameLimit(design.outputSize)
                require(limit > 0 && artifact.isFile && artifact.length() > 0) { "Invalid encoded animation" }
                val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "qr_verify_").toFile()
                directory = scratch
                val required = (limit + 1L) * (design.outputSize.toLong() * design.outputSize * 5 + 64 * 1024)
                require(scratch.usableSpace >= required) { "Not enough temporary storage for full animation verification" }
                currentCoroutineContext().ensureActive()
                // Preserve native frame rate, dimensions and order. Decode one
                // additional frame; hitting that sentinel blocks publication.
                val session = executeOwnedFfmpeg(arrayOf(
                    "-nostdin", "-y", "-xerror", "-err_detect", "explode",
                    "-max_pixels", (design.outputSize.toLong() * design.outputSize).toString(),
                    "-i", artifact.absolutePath, "-map", "0:v:0", "-an", "-sn", "-dn",
                    "-vsync", "0", "-frames:v", (limit + 1).toString(),
                    "-pix_fmt", "rgba", "-f", "image2", File(scratch, "frame_%06d.png").absolutePath
                ))
                if (!ReturnCode.isSuccess(session.returnCode)) throw IOException("Encoded animation could not be completely decoded")
                val frames = scratch.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.name }
                    ?: throw IOException("Cannot read decoded animation")
                totalFrames = frames.size
                requireCompleteFrameCount(totalFrames, limit)
                for ((index, file) in frames.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    require(file.name == String.format(java.util.Locale.US, "frame_%06d.png", index + 1)) {
                        "Missing decoded animation frame"
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    require(options.outWidth == design.outputSize && options.outHeight == design.outputSize) {
                        "Encoded animation frame dimensions changed"
                    }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IOException("Cannot read decoded frame ${index + 1}")
                    try {
                        val report = ScanabilityValidator.validateStrict(bitmap, design, matrix, expectedContent)
                        reports.add(report)
                        if (!QrExporter.isVerified(report, expectedContent)) {
                            errors.add("Encoded frame ${index + 1} failed strict verification")
                        }
                    } finally { bitmap.recycle(); file.delete() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errors.add(failure.message ?: "Animation verification failed")
            } finally {
                directory?.deleteRecursively()
            }
            val passed = reports.count { QrExporter.isVerified(it, expectedContent) }
            AnimatedScanabilityReport(
                isAllFramesScanReady = totalFrames > 0 && passed == totalFrames && errors.isEmpty(),
                totalFrames = totalFrames, passedFrames = passed, frameReports = reports,
                worstSeparation = reports.minOfOrNull { it.contrast.separation } ?: 0f,
                worstQuietZoneMargin = reports.minOfOrNull { it.quietZone.minMargin } ?: 0f,
                failureReasons = errors
            )
        }
}
