package com.veilframe.app.qr

import android.graphics.Bitmap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.qr.exporter.GifEncoder
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrOutputResult
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

    data class ReconciledFrame(
        val imageBitmap: Bitmap?,
        val logoBitmap: Bitmap?,
        val durationMs: Int
    )

    /**
     * Reconciles two independent animation timelines (e.g. animated watermark/image source and animated logo)
     * into a synchronized sequence of frames by calculating the least common multiple (LCM) of total durations
     * and stepping through discrete time boundaries, matching reference multi-image animation synchronization.
     */
    fun reconcileAnimationTimelines(
        imageFrames: List<Bitmap>,
        imageDelaysMs: List<Int>,
        logoFrames: List<Bitmap>,
        logoDelaysMs: List<Int>,
        maxDurationMs: Long = 15000L,
        maxFrames: Int = 240
    ): List<ReconciledFrame> {
        val hasImg = imageFrames.isNotEmpty()
        val hasLogo = logoFrames.isNotEmpty()

        if (!hasImg && !hasLogo) return emptyList()

        if (hasImg && !hasLogo) {
            return imageFrames.mapIndexed { idx, bmp ->
                val delay = imageDelaysMs.getOrElse(idx) { imageDelaysMs.lastOrNull() ?: 100 }
                ReconciledFrame(bmp, null, delay)
            }
        }
        if (!hasImg && hasLogo) {
            return logoFrames.mapIndexed { idx, bmp ->
                val delay = logoDelaysMs.getOrElse(idx) { logoDelaysMs.lastOrNull() ?: 100 }
                ReconciledFrame(null, bmp, delay)
            }
        }

        val safeImgDelays = imageFrames.indices.map { idx ->
            maxOf(10, imageDelaysMs.getOrElse(idx) { imageDelaysMs.lastOrNull() ?: 100 })
        }
        val safeLogoDelays = logoFrames.indices.map { idx ->
            maxOf(10, logoDelaysMs.getOrElse(idx) { logoDelaysMs.lastOrNull() ?: 100 })
        }

        val totalImgDur = maxOf(1L, safeImgDelays.sumOf { it.toLong() })
        val totalLogoDur = maxOf(1L, safeLogoDelays.sumOf { it.toLong() })

        fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = y
                y = x % y
                x = t
            }
            return x
        }

        fun lcm(a: Long, b: Long): Long {
            if (a == 0L || b == 0L) return 0L
            return (a / gcd(a, b)) * b
        }

        val rawLcm = lcm(totalImgDur, totalLogoDur)
        val targetDuration = if (rawLcm in 1..maxDurationMs) rawLcm else minOf(maxOf(totalImgDur, totalLogoDur), maxDurationMs)

        val result = mutableListOf<ReconciledFrame>()
        var currentTime = 0L

        while (currentTime < targetDuration && result.size < maxFrames) {
            val tImg = currentTime % totalImgDur
            val tLogo = currentTime % totalLogoDur

            var accImg = 0L
            var imgFrameIdx = 0
            var remImg = safeImgDelays[0].toLong()
            for ((idx, d) in safeImgDelays.withIndex()) {
                if (tImg < accImg + d) {
                    imgFrameIdx = idx
                    remImg = (accImg + d) - tImg
                    break
                }
                accImg += d
            }

            var accLogo = 0L
            var logoFrameIdx = 0
            var remLogo = safeLogoDelays[0].toLong()
            for ((idx, d) in safeLogoDelays.withIndex()) {
                if (tLogo < accLogo + d) {
                    logoFrameIdx = idx
                    remLogo = (accLogo + d) - tLogo
                    break
                }
                accLogo += d
            }

            val stepDur = minOf(remImg, remLogo, targetDuration - currentTime)
            if (stepDur <= 0L) break

            result.add(
                ReconciledFrame(
                    imageBitmap = imageFrames[imgFrameIdx],
                    logoBitmap = logoFrames[logoFrameIdx],
                    durationMs = stepDur.toInt()
                )
            )

            currentTime += stepDur
        }

        return result
    }

    /**
     * Extracts reconciled frames across both animated image source and animated logo.
     */
    fun extractReconciledFrames(design: QrDesign): List<ReconciledFrame> {
        val imgFrames = design.imageSource.animatedFrames
        val logoFrames = design.logo?.animatedFrames
        val hasImgAnim = !imgFrames.isNullOrEmpty()
        val hasLogoAnim = !logoFrames.isNullOrEmpty()

        if (!hasImgAnim && !hasLogoAnim) {
            val singleImg = design.imageSource.bitmap
            val singleLogo = design.logo?.effectiveBitmap
            return if (singleImg != null || singleLogo != null) {
                listOf(ReconciledFrame(singleImg, singleLogo, 100))
            } else {
                emptyList()
            }
        }

        val imgDelays = design.imageSource.frameDelaysMs ?: emptyList()
        val logoDelays = design.logo?.frameDelaysMs ?: emptyList()

        if (hasImgAnim && hasLogoAnim) {
            return reconcileAnimationTimelines(
                imageFrames = imgFrames!!,
                imageDelaysMs = imgDelays,
                logoFrames = logoFrames!!,
                logoDelaysMs = logoDelays
            )
        }

        if (hasImgAnim) {
            val singleLogo = design.logo?.effectiveBitmap
            return imgFrames!!.mapIndexed { idx, bmp ->
                val delay = imgDelays.getOrElse(idx) { imgDelays.lastOrNull() ?: 100 }
                ReconciledFrame(bmp, singleLogo, delay)
            }
        }

        val singleImg = design.imageSource.bitmap
        return logoFrames!!.mapIndexed { idx, bmp ->
            val delay = logoDelays.getOrElse(idx) { logoDelays.lastOrNull() ?: 100 }
            ReconciledFrame(singleImg, bmp, delay)
        }
    }

    /**
     * Renders a single animation frame at the specified [accumulatedMs] point in the timeline.
     * Accurately selects animated logo and artwork frames for that point in time.
     */
    fun renderFrameAt(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrame: QrFrame,
        accumulatedMs: Long,
        outputSize: Int = 512,
        geometry: QrGeometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, baseDesign)
    ): QrFrame? {
        val hasAnimatedImg = baseDesign.imageSource.isAnimated || !baseDesign.imageSource.animatedFrames.isNullOrEmpty()
        val imgSource = if (hasAnimatedImg) {
            baseDesign.imageSource.copy(
                source = com.veilframe.app.qr.model.ImageSource.Memory(sourceFrame.bitmap)
            )
        } else {
            baseDesign.imageSource
        }

        val hasAnimatedLogo = baseDesign.logo?.isAnimated == true && !baseDesign.logo.animatedFrames.isNullOrEmpty()
        val logoSource = if (hasAnimatedLogo) {
            val lFrames = baseDesign.logo!!.animatedFrames!!
            val lDelays = baseDesign.logo.frameDelaysMs ?: emptyList()
            val safeLDelays = lFrames.indices.map { i ->
                maxOf(10, lDelays.getOrElse(i) { lDelays.lastOrNull() ?: 100 })
            }
            val totalLogoDur = maxOf(1L, safeLDelays.sumOf { it.toLong() })
            val tLogo = accumulatedMs % totalLogoDur
            var acc = 0L
            var targetFrame = lFrames[0]
            for ((lIdx, d) in safeLDelays.withIndex()) {
                if (tLogo < acc + d) {
                    targetFrame = lFrames[lIdx]
                    break
                }
                acc += d
            }
            baseDesign.logo.copy(
                bitmap = targetFrame,
                source = com.veilframe.app.qr.model.ImageSource.Memory(targetFrame)
            )
        } else {
            baseDesign.logo
        }

        val frameDesign = baseDesign.copy(
            outputSize = outputSize,
            imageSource = imgSource,
            logo = logoSource
        )
        val renderedResult = QrGenerator.generateBitmapResult(matrix, frameDesign, geometry)
        return when (renderedResult) {
            is QrGenerator.BitmapRenderResult.Success -> QrFrame(bitmap = renderedResult.bitmap, durationMs = sourceFrame.durationMs)
            is QrGenerator.BitmapRenderResult.Failure -> null
        }
    }

    /**
     * Streams rendered QR frames one-by-one to a consumer callback (P2.2).
     * Enables encoders to write each frame to disk or stream immediately and release/recycle its bitmap,
     * maintaining low memory consumption regardless of frame count or resolution.
     */
    inline fun renderFramesStreaming(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputSize: Int = 512,
        crossinline onFrameRendered: (index: Int, totalFrames: Int, frame: QrFrame) -> Unit
    ) {
        require(sourceFrames.isNotEmpty()) { "sourceFrames cannot be empty" }
        val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, baseDesign)
        var accumulatedMs = 0L
        for ((idx, frame) in sourceFrames.withIndex()) {
            val rendered = renderFrameAt(matrix, baseDesign, frame, accumulatedMs, outputSize, geometry)
            if (rendered != null) {
                onFrameRendered(idx, sourceFrames.size, rendered)
            }
            accumulatedMs += frame.durationMs
        }
    }

    /**
     * Renders each frame in [sourceFrames] into a styled, scan-ready QR code bitmap.
     * Evaluates logo frames based on cumulative timeline playback time rather than naive modulo index.
     */
    fun renderFrames(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputSize: Int = 512
    ): List<QrFrame> {
        val result = ArrayList<QrFrame>(sourceFrames.size)
        renderFramesStreaming(matrix, baseDesign, sourceFrames, outputSize) { _, _, frame ->
            result.add(frame)
        }
        return result
    }

    /**
     * Checks if the given [QrDesign] has an animated image source or animated logo.
     */
    fun isDesignAnimated(design: QrDesign): Boolean {
        return design.imageSource.isAnimated ||
            (design.imageSource.animatedFrames?.isNotEmpty() == true) ||
            (design.logo?.isAnimated == true) ||
            (design.logo?.animatedFrames?.isNotEmpty() == true)
    }

    /**
     * Extracts a list of [QrFrame] items from the given [QrDesign]'s animated image source or animated logo.
     * When both image source and logo are animated, returns the synchronized reconciled timeline sequence.
     */
    fun extractSourceFrames(design: QrDesign): List<QrFrame> {
        val reconciled = extractReconciledFrames(design)
        if (reconciled.isNotEmpty()) {
            return reconciled.map { rf ->
                QrFrame(
                    bitmap = rf.imageBitmap ?: rf.logoBitmap ?: design.imageSource.bitmap ?: design.logo?.effectiveBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    durationMs = rf.durationMs
                )
            }
        }
        return emptyList()
    }

    /**
     * Renders each frame of an animated [QrDesign] into an animated sequence of QR code bitmaps.
     * Synchronizes timelines when both watermark/image source and logo are animated.
     */
    fun renderDesign(
        matrix: QrMatrix,
        design: QrDesign,
        outputSize: Int = 512
    ): List<QrFrame> {
        val reconciled = extractReconciledFrames(design)
        if (reconciled.isEmpty()) {
            val single = design.imageSource.bitmap ?: design.logo?.effectiveBitmap
            return if (single != null) listOf(QrFrame(single, 100)) else emptyList()
        }

        val geometry = QrGeometry.fromDesign(matrix.size, outputSize, outputSize, design)
        val renderedFrames = ArrayList<QrFrame>(reconciled.size)

        for (rf in reconciled) {
            val imgSource = if (rf.imageBitmap != null) {
                design.imageSource.copy(
                    source = com.veilframe.app.qr.model.ImageSource.Memory(rf.imageBitmap)
                )
            } else {
                design.imageSource
            }

            val logoSource = if (rf.logoBitmap != null) {
                design.logo?.copy(
                    bitmap = rf.logoBitmap,
                    source = com.veilframe.app.qr.model.ImageSource.Memory(rf.logoBitmap)
                )
            } else {
                design.logo
            }

            val frameDesign = design.copy(
                outputSize = outputSize,
                imageSource = imgSource,
                logo = logoSource
            )
            when (val renderedResult = QrGenerator.generateBitmapResult(matrix, frameDesign, geometry)) {
                is QrGenerator.BitmapRenderResult.Success -> {
                    renderedFrames.add(QrFrame(bitmap = renderedResult.bitmap, durationMs = rf.durationMs))
                }
                is QrGenerator.BitmapRenderResult.Failure -> {
                    // Frame allocation/rendering failed
                }
            }
        }
        return renderedFrames
    }

    /**
     * Generates an animated SVG document directly from an animated [QrDesign].
     */
    fun generateAnimatedSvg(
        matrix: QrMatrix,
        design: QrDesign
    ): String {
        // Native EF animated styles: SvgExporter generates static QR structure with animated image source
        if (design.style == com.veilframe.app.qr.QrStyle.IMAGE ||
            design.style == com.veilframe.app.qr.QrStyle.IMAGE_FILL ||
            design.style == com.veilframe.app.qr.QrStyle.IMAGE_RESAMPLE
        ) {
            if (isDesignAnimated(design)) {
                return SvgExporter.generateSvg(matrix, design)
            }
        }
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
        val keyTimes = ArrayList<String>(sourceFrames.size)

        var accumulatedMs = 0

        var baseSvgHeader = ""
        var baseSvgFooter = "</svg>"

        for ((idx, frame) in sourceFrames.withIndex()) {
            val frameId = "qr_frame_$idx"
            frameIds.add("#$frameId")

            val fraction = accumulatedMs.toDouble() / totalDurationMs
            keyTimes.add(String.format(Locale.US, "%.3f", fraction))
            accumulatedMs += frame.durationMs

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
        }

        // EF parity (EFQRCodeStyle.swift:292): exactly N keyTimes corresponding to N values in discrete calcMode
        val valuesStr = frameIds.joinToString(";")
        val keyTimesStr = keyTimes.joinToString(";")
        val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

        val sb = StringBuilder()
        if (baseSvgHeader.isNotEmpty()) {
            sb.append(baseSvgHeader).append("\n")
        } else {
            val resolvedQz = com.veilframe.app.qr.model.QrGeometry.resolveQuietZone(baseDesign, matrix.size)
            val totalW = matrix.size + resolvedQz.left + resolvedQz.right
            val totalH = matrix.size + resolvedQz.top + resolvedQz.bottom
            val twStr = SvgExporter.formatCoord(totalW.toDouble())
            val thStr = SvgExporter.formatCoord(totalH.toDouble())
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 $twStr $thStr\" width=\"100%\" height=\"100%\">\n")
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
     * Typed GIF encoding pipeline returning [QrOutputResult].
     */
    fun encodeToGifResult(
        renderedFrames: List<QrFrame>,
        width: Int = renderedFrames.firstOrNull()?.bitmap?.width ?: 512,
        height: Int = renderedFrames.firstOrNull()?.bitmap?.height ?: 512,
        loops: Int = 0
    ): QrOutputResult<ByteArray> {
        if (renderedFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }
        return try {
            val bytes = GifEncoder.encode(renderedFrames, width, height, loops)
            QrOutputResult.Success(bytes)
        } catch (t: Throwable) {
            QrOutputResult.Failure(QrError.Output.GifEncodingFailed(t.message ?: "GIF encoding failed", t))
        }
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
        val res = encodeToGifResult(renderedFrames, width, height, loops)
        if (res is QrOutputResult.Failure) throw res.error
        return (res as QrOutputResult.Success).value
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
     * Strongly typed streaming GIF encoding pipeline (P2.2).
     * Renders frames one-by-one directly into [GifEncoder] and immediately recycles each bitmap,
     * maintaining peak memory at a single frame rather than buffering all full-resolution frames.
     */
    fun encodeToGifStreaming(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputSize: Int = 512,
        loops: Int = 0
    ): QrOutputResult<ByteArray> {
        if (sourceFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }
        val bos = ByteArrayOutputStream()
        val encoder = GifEncoder()
        encoder.start(bos, outputSize, outputSize, loops)
        var renderedCount = 0
        try {
            renderFramesStreaming(matrix, baseDesign, sourceFrames, outputSize) { _, _, frame ->
                renderedCount++
                try {
                    encoder.addFrame(frame.bitmap, frame.durationMs)
                } finally {
                    try {
                        if (!frame.bitmap.isRecycled) {
                            frame.bitmap.recycle()
                        }
                    } catch (_: Throwable) {}
                }
            }
            if (renderedCount == 0) {
                return QrOutputResult.Failure(QrError.Rendering.BitmapAllocationFailed(outputSize, outputSize))
            }
            encoder.finish()
            return QrOutputResult.Success(bos.toByteArray())
        } catch (t: Throwable) {
            return QrOutputResult.Failure(QrError.Output.GifEncodingFailed(t.message ?: "GIF encoding failed", t))
        }
    }

    /**
     * Strongly typed APNG encoding pipeline matching EFQRCode animated PNG creation contract.
     * Encodes rendered QR frames into an APNG file using FFmpegKit.
     */
    fun encodeToApngResult(
        renderedFrames: List<QrFrame>,
        outputFile: File,
        fps: Int = 15,
        loops: Int = 0
    ): QrOutputResult<File> {
        if (renderedFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }
        val parentDir = outputFile.parentFile ?: File(".")
        val tempDir = File(parentDir, "qr_apng_tmp_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return QrOutputResult.Failure(QrError.Platform.StorageFailed(tempDir.absolutePath))
        }

        try {
            for ((idx, frame) in renderedFrames.withIndex()) {
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                val writeSuccess = try {
                    FileOutputStream(frameFile).use { out ->
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    return QrOutputResult.Failure(QrError.Output.ApngEncodingFailed("Failed writing frame $idx", t))
                }
                if (!writeSuccess) {
                    return QrOutputResult.Failure(QrError.Output.ApngEncodingFailed("Bitmap compression failed for frame $idx"))
                }
            }

            val cmd = buildApngFfmpegCommand(renderedFrames, tempDir, outputFile, fps, loops)
            val session = FFmpegKit.execute(cmd)
            val returnCode = session.returnCode
            return if (ReturnCode.isSuccess(returnCode)) {
                QrOutputResult.Success(outputFile)
            } else {
                QrOutputResult.Failure(
                    QrError.Output.ApngEncodingFailed(
                        "FFmpeg APNG encoding failed with return code ${returnCode?.value}"
                    )
                )
            }
        } catch (t: Throwable) {
            return QrOutputResult.Failure(QrError.Output.ApngEncodingFailed(t.message ?: "APNG encoding failed", t))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Strongly typed streaming APNG encoding pipeline (P2.2).
     * Renders frames one-by-one to temporary PNG files and immediately recycles each bitmap,
     * keeping peak memory at a single frame bitmap before delegating to FFmpeg.
     */
    fun encodeToApngStreaming(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputFile: File,
        fps: Int = 15,
        loops: Int = 0,
        outputSize: Int = baseDesign.outputSize
    ): QrOutputResult<File> {
        if (sourceFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }
        val parentDir = outputFile.parentFile ?: File(".")
        val tempDir = File(parentDir, "qr_apng_tmp_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return QrOutputResult.Failure(QrError.Platform.StorageFailed(tempDir.absolutePath))
        }

        val frameDurations = mutableListOf<Int>()
        var renderedCount = 0

        try {
            renderFramesStreaming(matrix, baseDesign, sourceFrames, outputSize) { idx, _, frame ->
                renderedCount++
                frameDurations.add(frame.durationMs.coerceAtLeast(1))
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                val writeSuccess = try {
                    FileOutputStream(frameFile).use { out ->
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    throw IllegalStateException("Failed writing frame $idx: ${t.message}", t)
                } finally {
                    try {
                        if (!frame.bitmap.isRecycled) {
                            frame.bitmap.recycle()
                        }
                    } catch (_: Throwable) {}
                }
                if (!writeSuccess) {
                    throw IllegalStateException("Bitmap compression returned false for frame $idx")
                }
            }

            if (renderedCount == 0) {
                return QrOutputResult.Failure(QrError.Rendering.BitmapAllocationFailed(outputSize, outputSize))
            }

            val isVariableTiming = frameDurations.distinct().size > 1
            val cmd = if (isVariableTiming) {
                val concatFile = File(tempDir, "input.txt")
                val sb = StringBuilder()
                sb.append("ffconcat version 1.0\n")
                for ((idx, dur) in frameDurations.withIndex()) {
                    val durSec = String.format(Locale.US, "%.4f", dur / 1000.0)
                    sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", idx)).append("'\n")
                    sb.append("duration ").append(durSec).append("\n")
                }
                sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", frameDurations.size - 1)).append("'\n")
                concatFile.writeText(sb.toString())
                "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -plays $loops -f apng \"${outputFile.absolutePath}\""
            } else {
                val frameDurMs = frameDurations.firstOrNull() ?: (1000 / fps.coerceAtLeast(1))
                val effectiveFps = if (frameDurMs > 0 && frameDurMs != 1000 / fps) {
                    kotlin.math.round(1000.0 / frameDurMs).toInt().coerceIn(1, 120)
                } else fps
                val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
                "-y -framerate $effectiveFps -i \"$inputPattern\" -plays $loops -f apng \"${outputFile.absolutePath}\""
            }

            val session = FFmpegKit.execute(cmd)
            val returnCode = session.returnCode
            return if (ReturnCode.isSuccess(returnCode)) {
                QrOutputResult.Success(outputFile)
            } else {
                QrOutputResult.Failure(
                    QrError.Output.ApngEncodingFailed(
                        "FFmpeg APNG encoding failed with return code ${returnCode?.value}"
                    )
                )
            }
        } catch (t: Throwable) {
            return QrOutputResult.Failure(QrError.Output.ApngEncodingFailed(t.message ?: "APNG encoding failed", t))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Constructs the FFmpeg command for APNG encoding.
     * When frames have variable durations, constructs an ffconcat manifest script
     * with exact per-frame durations to prevent APNG timing distortion.
     */
    fun buildApngFfmpegCommand(
        renderedFrames: List<QrFrame>,
        tempDir: File,
        outputFile: File,
        fps: Int = 15,
        loops: Int = 0
    ): String {
        val durations = renderedFrames.map { it.durationMs.coerceAtLeast(1) }
        val isVariableTiming = durations.distinct().size > 1

        return if (isVariableTiming) {
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
            "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -plays $loops -f apng \"${outputFile.absolutePath}\""
        } else {
            val frameDurMs = durations.firstOrNull() ?: (1000 / fps.coerceAtLeast(1))
            val effectiveFps = if (frameDurMs > 0 && frameDurMs != 1000 / fps) {
                kotlin.math.round(1000.0 / frameDurMs).toInt().coerceIn(1, 120)
            } else fps
            val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
            "-y -framerate $effectiveFps -i \"$inputPattern\" -plays $loops -f apng \"${outputFile.absolutePath}\""
        }
    }

    enum class VideoStage {
        VALIDATION,
        FRAME_WRITE,
        CONCAT_MANIFEST,
        FFMPEG_EXECUTION,
        FINALIZATION
    }

    /**
     * Strongly typed video encoding pipeline matching EFQRCode video creation contract.
     * Encodes rendered QR frames into an MP4 or MOV video file using FFmpegKit.
     *
     * @return [QrOutputResult.Success] with [outputFile] or [QrOutputResult.Failure] with structured [QrError.Output.VideoEncodingFailed].
     */
    fun encodeToVideoResult(
        renderedFrames: List<QrFrame>,
        outputFile: File,
        fps: Int = 15
    ): QrOutputResult<File> {
        if (renderedFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }

        val parentDir = outputFile.parentFile ?: File(".")
        val tempDir = File(parentDir, "qr_vid_tmp_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return QrOutputResult.Failure(
                QrError.Platform.StorageFailed(tempDir.absolutePath)
            )
        }

        try {
            // Write frames to temporary PNG sequence
            for ((idx, frame) in renderedFrames.withIndex()) {
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                val writeSuccess = try {
                    FileOutputStream(frameFile).use { out ->
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    return QrOutputResult.Failure(
                        QrError.Output.VideoEncodingFailed(
                            stage = VideoStage.FRAME_WRITE.name,
                            cause = t
                        )
                    )
                }
                if (!writeSuccess) {
                    return QrOutputResult.Failure(
                        QrError.Output.VideoEncodingFailed(
                            stage = VideoStage.FRAME_WRITE.name,
                            cause = IllegalStateException("Bitmap compression returned false for frame $idx")
                        )
                    )
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
            val returnCode = session.returnCode
            return if (ReturnCode.isSuccess(returnCode)) {
                QrOutputResult.Success(outputFile)
            } else {
                QrOutputResult.Failure(
                    QrError.Output.VideoEncodingFailed(
                        stage = VideoStage.FFMPEG_EXECUTION.name,
                        exitCode = returnCode?.value
                    )
                )
            }
        } catch (t: Throwable) {
            return QrOutputResult.Failure(
                QrError.Output.VideoEncodingFailed(
                    stage = VideoStage.FFMPEG_EXECUTION.name,
                    cause = t
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Strongly typed streaming Video encoding pipeline (P2.2).
     * Renders frames one-by-one to temporary PNG files and immediately recycles each bitmap,
     * maintaining peak memory at a single frame bitmap before invoking FFmpeg.
     */
    fun encodeToVideoStreaming(
        matrix: QrMatrix,
        baseDesign: QrDesign,
        sourceFrames: List<QrFrame>,
        outputFile: File,
        fps: Int = 15,
        outputSize: Int = baseDesign.outputSize
    ): QrOutputResult<File> {
        if (sourceFrames.isEmpty()) {
            return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
        }
        val parentDir = outputFile.parentFile ?: File(".")
        val tempDir = File(parentDir, "qr_vid_tmp_${System.currentTimeMillis()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return QrOutputResult.Failure(
                QrError.Platform.StorageFailed(tempDir.absolutePath)
            )
        }

        val frameDurations = mutableListOf<Int>()
        var renderedCount = 0

        try {
            renderFramesStreaming(matrix, baseDesign, sourceFrames, outputSize) { idx, _, frame ->
                renderedCount++
                frameDurations.add(frame.durationMs.coerceAtLeast(1))
                val frameFile = File(tempDir, String.format(Locale.US, "frame_%04d.png", idx))
                val writeSuccess = try {
                    FileOutputStream(frameFile).use { out ->
                        frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    throw IllegalStateException("Failed writing frame $idx: ${t.message}", t)
                } finally {
                    try {
                        if (!frame.bitmap.isRecycled) {
                            frame.bitmap.recycle()
                        }
                    } catch (_: Throwable) {}
                }
                if (!writeSuccess) {
                    throw IllegalStateException("Bitmap compression returned false for frame $idx")
                }
            }

            if (renderedCount == 0) {
                return QrOutputResult.Failure(QrError.Rendering.BitmapAllocationFailed(outputSize, outputSize))
            }

            val isVariableTiming = frameDurations.distinct().size > 1
            val cmd = if (isVariableTiming) {
                val concatFile = File(tempDir, "input.txt")
                val sb = StringBuilder()
                sb.append("ffconcat version 1.0\n")
                for ((idx, dur) in frameDurations.withIndex()) {
                    val durSec = String.format(Locale.US, "%.4f", dur / 1000.0)
                    sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", idx)).append("'\n")
                    sb.append("duration ").append(durSec).append("\n")
                }
                sb.append("file '").append(String.format(Locale.US, "frame_%04d.png", frameDurations.size - 1)).append("'\n")
                concatFile.writeText(sb.toString())
                "-y -f concat -safe 0 -i \"${concatFile.absolutePath}\" -vsync vfr -c:v libx264 -pix_fmt yuv420p -movflags +faststart \"${outputFile.absolutePath}\""
            } else {
                val frameDurMs = frameDurations.firstOrNull() ?: 66
                val effectiveFps = if (fps > 0) fps else kotlin.math.round(1000.0 / frameDurMs).toInt().coerceIn(1, 120)
                val inputPattern = File(tempDir, "frame_%04d.png").absolutePath
                "-y -framerate $effectiveFps -i \"$inputPattern\" -c:v libx264 -pix_fmt yuv420p -movflags +faststart \"${outputFile.absolutePath}\""
            }

            val session = FFmpegKit.execute(cmd)
            val returnCode = session.returnCode
            return if (ReturnCode.isSuccess(returnCode)) {
                QrOutputResult.Success(outputFile)
            } else {
                QrOutputResult.Failure(
                    QrError.Output.VideoEncodingFailed(
                        stage = VideoStage.FFMPEG_EXECUTION.name,
                        exitCode = returnCode?.value
                    )
                )
            }
        } catch (t: Throwable) {
            return QrOutputResult.Failure(
                QrError.Output.VideoEncodingFailed(
                    stage = VideoStage.FRAME_WRITE.name,
                    cause = t
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Encodes rendered QR frames into an MP4 or MOV video file using FFmpegKit.
     * Backwards-compatible facade returning boolean success.
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
    ): Boolean = encodeToVideoResult(renderedFrames, outputFile, fps) is QrOutputResult.Success
}
