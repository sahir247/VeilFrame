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
                )
            )
            val renderedBitmap = QrGenerator.generateBitmap(matrix, frameDesign, geometry)
            if (renderedBitmap != null) {
                renderedFrames.add(QrFrame(bitmap = renderedBitmap, durationMs = frame.durationMs))
            }
        }
        return renderedFrames
    }

    /**
     * Checks if the given [QrDesign] has an animated image source.
     */
    fun isDesignAnimated(design: QrDesign): Boolean {
        return design.imageSource.isAnimated || (design.imageSource.animatedFrames?.isNotEmpty() == true)
    }

    /**
     * Extracts a list of [QrFrame] items from the given [QrDesign]'s animated image source.
     */
    fun extractSourceFrames(design: QrDesign): List<QrFrame> {
        val frames = design.imageSource.animatedFrames ?: return emptyList()
        val delays = design.imageSource.frameDelaysMs ?: emptyList()
        return frames.mapIndexed { idx, bmp ->
            val delay = delays.getOrElse(idx) { delays.lastOrNull() ?: 100 }
            QrFrame(bitmap = bmp, durationMs = delay)
        }
    }

    /**
     * Renders each frame of an animated [QrDesign] into an animated sequence of QR code bitmaps.
     */
    fun renderDesign(
        matrix: QrMatrix,
        design: QrDesign,
        outputSize: Int = 512
    ): List<QrFrame> {
        val sourceFrames = extractSourceFrames(design)
        if (sourceFrames.isEmpty()) {
            val single = design.imageSource.bitmap
            return if (single != null) listOf(QrFrame(single, 100)) else emptyList()
        }
        return renderFrames(matrix, design, sourceFrames, outputSize)
    }

    /**
     * Generates an animated SVG document directly from an animated [QrDesign].
     */
    fun generateAnimatedSvg(
        matrix: QrMatrix,
        design: QrDesign
    ): String {
        val sourceFrames = extractSourceFrames(design)
        if (sourceFrames.isEmpty()) {
            return SvgExporter.generateSvg(matrix, design)
        }
        return generateAnimatedSvg(matrix, design, sourceFrames)
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

        // For EF parity with IMAGE_RESAMPLE: delegate to layer-isolated native generation
        // where only the resampled dots layer is animated while backdrop, finders, timing,
        // alignment, and icon remain completely static outside <animate>.
        if (baseDesign.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE) {
            val animatedDesign = baseDesign.copy(
                imageSource = baseDesign.imageSource.copy(
                    source = com.veilframe.app.qr.model.ImageSource.Animated(
                        sourceFrames.map { it.bitmap },
                        sourceFrames.map { it.durationMs }
                    )
                )
            )
            return SvgExporter.generateSvg(matrix, animatedDesign)
        }

        val totalDurationMs = maxOf(1, sourceFrames.sumOf { it.durationMs })
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
                )
            )

            val fullSvg = SvgExporter.generateSvg(matrix, frameDesign)

            // Extract SVG header and inner body
            val svgTagStart = fullSvg.indexOf("<svg")
            val svgOpenEnd = if (svgTagStart >= 0) fullSvg.indexOf('>', svgTagStart) else fullSvg.indexOf('>')
            val svgCloseStart = fullSvg.lastIndexOf("</svg>")

            if (idx == 0 && svgOpenEnd > 0) {
                baseSvgHeader = fullSvg.substring(0, svgOpenEnd + 1)
            }

            val innerContent = if (svgOpenEnd > 0 && svgCloseStart > svgOpenEnd) {
                fullSvg.substring(svgOpenEnd + 1, svgCloseStart).trim()
            } else {
                fullSvg
            }

            val scopedContent = scopeSvgIds(innerContent, "f$idx")

            frameDefs.append("    <g id=\"$frameId\">\n")
            frameDefs.append(scopedContent).append("\n")
            frameDefs.append("    </g>\n")

            accumulatedMs += frame.durationMs
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
     * Frame-scopes SVG element IDs and their references (url(#id), href="#id", xlink:href="#id")
     * with [prefix] (e.g. "f0", "f1") to prevent document-global ID collisions in animated SVGs.
     */
    fun scopeSvgIds(svgContent: String, prefix: String): String {
        val idRegex = Regex("""\bid=["']([^"']+)["']""")
        val ids = idRegex.findAll(svgContent).map { it.groupValues[1] }.toSet()
        if (ids.isEmpty()) return svgContent

        var result = svgContent
        for (id in ids) {
            val scopedId = "${prefix}_$id"
            // Replace definition: id="id" or id='id'
            result = result.replace(Regex("""\bid=(["'])${Regex.escape(id)}\1"""), "id=$1$scopedId$1")
            // Replace url(#id)
            result = result.replace(Regex("""url\(\s*#${Regex.escape(id)}\s*\)"""), "url(#$scopedId)")
            // Replace href="#id" and xlink:href="#id"
            result = result.replace(Regex("""\b(xlink:href|href)=(["'])#${Regex.escape(id)}\2"""), "$1=$2#$scopedId$2")
            // Replace clip-path, mask, fill, filter direct references if any format uses #id
            result = result.replace(Regex("""(["'])#${Regex.escape(id)}\1"""), "$1#$scopedId$1")
        }
        return result
    }

    /**
     * Encodes rendered QR frames into an animated GIF byte array.
     * Pure Kotlin [GifEncoder] is authoritative for all GIF outputs, ensuring
     * exact centisecond Graphic Control Extension delay bytes and zero FPS drift.
     */
    fun encodeToGif(
        renderedFrames: List<QrFrame>,
        width: Int = renderedFrames.firstOrNull()?.bitmap?.width ?: 512,
        height: Int = renderedFrames.firstOrNull()?.bitmap?.height ?: 512,
        loops: Int = 0
    ): ByteArray {
        require(renderedFrames.isNotEmpty()) { "renderedFrames cannot be empty" }
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
     * @param fps Frame rate in frames per second (defaults to 15, overridden if variable timing).
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

            val durations = renderedFrames.map { it.durationMs.coerceAtLeast(1) }
            val isVariableTiming = durations.distinct().size > 1

            val cmd = if (isVariableTiming) {
                val concatFile = File(tempDir, "input.txt")
                val sb = StringBuilder()
                sb.append("ffconcat version 1.0\n")
                for ((idx, frame) in renderedFrames.withIndex()) {
                    val durSec = String.format(Locale.US, "%.4f", frame.durationMs.coerceAtLeast(1) / 1000.0)
                    sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", idx)).append("'\n")
                    sb.append("duration ").append(durSec).append("\n")
                }
                sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", renderedFrames.size - 1)).append("'\n")
                concatFile.writeText(sb.toString())
                "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -vsync vfr -c:v libx264 -pix_fmt yuv420p -movflags +faststart \"${outputFile.absolutePath}\""
            } else {
                val frameDurMs = durations.firstOrNull() ?: 66
                val effectiveFps = if (fps > 0) fps else kotlin.math.round(1000.0 / frameDurMs).toInt().coerceIn(1, 120)
                val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
                "-y -framerate $effectiveFps -i \"$inputPattern\" -c:v libx264 -pix_fmt yuv420p -movflags +faststart \"${outputFile.absolutePath}\""
            }

            val session = FFmpegKit.execute(cmd)
            return ReturnCode.isSuccess(session.returnCode)
        } catch (_: Throwable) {
            return false
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
