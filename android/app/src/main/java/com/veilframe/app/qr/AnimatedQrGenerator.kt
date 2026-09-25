package com.veilframe.app.qr

import android.graphics.Bitmap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.qr.exporter.GifEncoder
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Multi-frame Animated QR Code engine for VeilFrame.
 *
 * Implements complete parity with reference animated QR features:
 * 1. Multi-frame QR rasterization: Renders each frame of a GIF/sequence through the QR engine.
 * 2. Animated SVG Export: Multi-frame vector SVG using native <animate> discrete frame switching.
 * 3. Native GIF Export: Pure-Kotlin GIF89a encoding with frame delays and infinite loop.
 * 4. Video QR Export: Encodes rendered QR frames into MP4/MOV using FFmpegKit.
 */
object AnimatedQrGenerator {

    /**
     * Renders each frame in [sourceFrames] into a styled, scan-ready QR code bitmap.
     */
    fun renderFrames(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputSize: Int = 512
    ): List<QrFrame> {
        require(sourceFrames.isNotEmpty()) { "sourceFrames cannot be empty" }
        val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, baseDesign)
        val renderedFrames = ArrayList<QrFrame>(sourceFrames.size)

        for (frame in sourceFrames) {
            val frameDesign = baseDesign.copy(
                outputSize = outputSize,
                imageSource = baseDesign.imageSource.copy(
                    source = com.veilframe.app.qr.model.ImageSource.Memory(frame.bitmap)
                ),
                backgroundImage = frame.bitmap
            )
            val renderedBitmap = QrGenerator.generateBitmap(matrix, frameDesign, geometry)
            if (renderedBitmap != null) {
                renderedFrames.add(QrFrame(bitmap = renderedBitmap, durationMs = frame.durationMs))
            }
        }
        return renderedFrames
    }

    /**
     * Generates a fully animated vector SVG document matching reference animated SVG format.
     *
     * Frames are encapsulated within <defs><g id="frame_N"> elements, and cycled via
     * SVG native <animate attributeName="xlink:href" calcMode="discrete">.
     */
    fun generateAnimatedSvg(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>
    ): String {
        require(sourceFrames.isNotEmpty()) { "sourceFrames cannot be empty" }

        val totalDurationMs = sourceFrames.sumOf { it.durationMs.coerceAtLeast(10) }
        val totalDurationSec = totalDurationMs / 1000.0

        val frameDefs = StringBuilder()
        val frameIds = ArrayList<String>(sourceFrames.size)
        val keyTimes = ArrayList<String>(sourceFrames.size + 1)

        var accumulatedMs = 0
        keyTimes.add("0.000")

        var baseSvgHeader = ""
        var baseSvgFooter = "</svg>"

        for ((idx, frame) in sourceFrames.withIndex()) {
            val frameId = "qr_frame_$idx"
            frameIds.add("#$frameId")

            val frameDesign = baseDesign.copy(
                imageSource = baseDesign.imageSource.copy(
                    source = com.veilframe.app.qr.model.ImageSource.Memory(frame.bitmap)
                ),
                backgroundImage = frame.bitmap
            )

            val fullSvg = SvgExporter.generateSvg(matrix, frameDesign)

            // Extract SVG header and inner body
            val svgOpenEnd = fullSvg.indexOf('>')
            val svgCloseStart = fullSvg.lastIndexOf("</svg>")

            if (idx == 0 && svgOpenEnd > 0) {
                baseSvgHeader = fullSvg.substring(0, svgOpenEnd + 1)
            }

            val innerContent = if (svgOpenEnd > 0 && svgCloseStart > svgOpenEnd) {
                fullSvg.substring(svgOpenEnd + 1, svgCloseStart).trim()
            } else {
                fullSvg
            }

            frameDefs.append("    <g id=\"$frameId\">\n")
            frameDefs.append(innerContent).append("\n")
            frameDefs.append("    </g>\n")

            accumulatedMs += frame.durationMs.coerceAtLeast(10)
            if (idx < sourceFrames.size - 1) {
                val fraction = accumulatedMs.toDouble() / totalDurationMs
                keyTimes.add(String.format(Locale.US, "%.3f", fraction))
            }
        }
        keyTimes.add("1.000")

        val valuesStr = frameIds.joinToString(";") + ";" + frameIds.first()
        val keyTimesStr = keyTimes.joinToString(";")
        val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

        val sb = StringBuilder()
        if (baseSvgHeader.isNotEmpty()) {
            sb.append(baseSvgHeader).append("\n")
        } else {
            val n = matrix.size
            val qz = baseDesign.quietZoneModules
            val totalSize = n + 2 * qz
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 $totalSize $totalSize\" width=\"100%\" height=\"100%\">\n")
        }

        sb.append("  <defs>\n")
        sb.append(frameDefs)
        sb.append("  </defs>\n")

        sb.append("""  <use xlink:href="${frameIds.first()}">""").append("\n")
        sb.append("""    <animate""").append("\n")
        sb.append("""      attributeName="xlink:href"""").append("\n")
        sb.append("""      values="$valuesStr"""").append("\n")
        sb.append("""      keyTimes="$keyTimesStr"""").append("\n")
        sb.append("""      dur="${durStr}s"""").append("\n")
        sb.append("""      repeatCount="indefinite"""").append("\n")
        sb.append("""      calcMode="discrete"""").append("\n")
        sb.append("""    />""").append("\n")
        sb.append("  </use>\n")
        sb.append(baseSvgFooter)

        return sb.toString()
    }

    /**
     * Encodes rendered QR frames into an animated GIF byte array.
     */
    fun encodeToGif(
        renderedFrames: List<QrFrame>,
        width: Int = renderedFrames.firstOrNull()?.bitmap?.width ?: 512,
        height: Int = renderedFrames.firstOrNull()?.bitmap?.height ?: 512,
        loops: Int = 0
    ): ByteArray {
        require(renderedFrames.isNotEmpty()) { "renderedFrames cannot be empty" }
        // Try FFmpegKit first for industry-standard animated GIF encoding
        try {
            val tempDir = File.createTempFile("qr_gif_", "_dir")
            tempDir.delete()
            tempDir.mkdirs()
            val outFile = File(tempDir, "output.gif")
            val avgDurationMs = renderedFrames.map { it.durationMs.coerceAtLeast(20) }.average().toInt().coerceIn(20, 1000)
            val fps = (1000 / avgDurationMs).coerceIn(1, 50)

            for ((idx, frame) in renderedFrames.withIndex()) {
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                FileOutputStream(frameFile).use { out ->
                    frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
            val cmd = "-y -framerate $fps -i \"$inputPattern\" -vf \"split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3\" -loop $loops \"${outFile.absolutePath}\""
            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0) {
                val bytes = outFile.readBytes()
                tempDir.deleteRecursively()
                return bytes
            }
            tempDir.deleteRecursively()
        } catch (_: Throwable) {
        }
        return GifEncoder.encode(renderedFrames, width, height, loops)
    }

    /**
     * Encodes rendered QR frames into an animated GIF written to [outputStream].
     */
    fun encodeToGif(
        renderedFrames: List<QrFrame>,
        outputStream: OutputStream,
        width: Int = renderedFrames.firstOrNull()?.bitmap?.width ?: 512,
        height: Int = renderedFrames.firstOrNull()?.bitmap?.height ?: 512,
        loops: Int = 0
    ) {
        val bytes = encodeToGif(renderedFrames, width, height, loops)
        outputStream.write(bytes)
    }

    /**
     * Encodes rendered QR frames into an MP4 or MOV video file using FFmpegKit.
     *
     * @param renderedFrames The sequence of rendered QR frames.
     * @param outputFile Target video file (.mp4 or .mov).
     * @param fps Frame rate in frames per second (defaults to 15).
     * @return true if video encoding succeeded.
     */
    fun encodeToVideo(
        renderedFrames: List<QrFrame>,
        outputFile: File,
        fps: Int = 15
    ): Boolean {
        if (renderedFrames.isEmpty()) return false
        val tempDir = File(outputFile.parentFile, "qr_vid_tmp_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // Write frames to temporary PNG sequence
            for ((idx, frame) in renderedFrames.withIndex()) {
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                FileOutputStream(frameFile).use { out ->
                    frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
            val cmd = "-y -framerate $fps -i \"$inputPattern\" -c:v libx264 -pix_fmt yuv420p -movflags +faststart \"${outputFile.absolutePath}\""

            val session = FFmpegKit.execute(cmd)
            return ReturnCode.isSuccess(session.returnCode)
        } catch (_: Throwable) {
            return false
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
