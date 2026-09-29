package com.veilframe.app.qr.geometry

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.LogoBackgroundMode
import com.veilframe.app.qr.model.LogoShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrVisualGeometry
import com.veilframe.app.qr.renderer.RenderContext
import com.veilframe.app.qr.renderer.VeilPositionPatternGeometry
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single authoritative implementation of the EFQRCode 7.0.3 icon contract (EFStyleParamIcon).
 *
 * Implements:
 * - Universal sizing: hard-capped at min(percentage, 0.33f) across all QR styles
 * - Placement: centered within QR matrix bounds
 * - Geometric 2.4% padding expansion: iconOffset = iconXY * 0.024f
 * - Border: SQ25 squircle path with bdColor, bdAlpha, and strokeWidth = 100.0 / iconSize (or custom borderWidth)
 * - Masking: SVG <defs> path with <mask id="icon{mark}"> clipping the icon image
 * - Preprocessing: per-frame EfImagePreprocessor scaling across all frames
 * - Dynamic unique ID generation: atomic counter mirroring EF Anchor.uniqueMark (NO static prefixes)
 * - Authoritative multi-target rendering: IR nodes (appendIconNodes), SVG strings (appendIconSvg), and Canvas (drawLogo)
 */
object VeilIconPipeline {

    private val uniqueMark = AtomicInteger(0)

    fun nextUniqueMark(): Int = uniqueMark.getAndIncrement()

    fun resetMarkForTesting(value: Int = 0) {
        uniqueMark.set(value)
    }

    /**
     * Appends icon geometry nodes and SVG defs strictly matching EFQRCode 7.0.3 EFStyleParamIcon contract.
     *
     * @param nodes Target IR node list to receive PathNode, ImageNode, or AnimatedImageNode.
     * @param defs Target SVG defs list to receive mask and path definitions.
     * @param design The QR design containing logo style and palette.
     * @param ox Left offset in pixels (e.g. quiet zone offset).
     * @param oy Top offset in pixels (e.g. quiet zone offset).
     * @param qrPixelSize Dimension of the QR matrix in pixels (e.g. matrixSize * moduleSize).
     */
    fun appendIconNodes(
        nodes: MutableList<QrGeometryNode>,
        defs: MutableList<String>,
        design: QrDesign,
        ox: Float,
        oy: Float,
        qrPixelSize: Float
    ) {
        val logo = design.logo ?: return
        val logoBmp = logo.effectiveBitmap ?: return

        // 1. Sizing: hard-capped at 0.33 per EFQRCodeStyle.swift:218
        val scale = minOf(maxOf(0f, logo.scaleFraction), 0.33f)
        val iconSize = qrPixelSize * scale
        val iconXY = (qrPixelSize - iconSize) / 2f

        // 2. Exact 2.4% geometric offset per EFQRCodeStyle.swift:222-225
        val iconOffset = iconXY * 0.024f
        val rectXY = iconXY - iconOffset
        val length = iconSize + 2f * iconOffset

        val markDefs = nextUniqueMark()
        val randomIdDefs = "icon$markDefs"
        val markClips = nextUniqueMark()
        val randomIdClips = "icon$markClips"

        // 3. Border per EFQRCodeStyle.swift:232 (SQ25 squircle or circle)
        val bdColor = logo.borderColor ?: if (logo.backgroundMode != LogoBackgroundMode.NONE) design.palette.background else null
        val borderStroke = if (logo.borderWidth > 0f) logo.borderWidth else (100f / iconSize)
        if (bdColor != null) {
            val bdAlpha = ((bdColor ushr 24) and 0xFF) / 255f
            if (logo.shape == LogoShape.CIRCLE) {
                nodes.add(
                    CircleNode(
                        cx = ox + iconXY + iconSize / 2f,
                        cy = oy + iconXY + iconSize / 2f,
                        radius = iconSize / 2f,
                        fill = bdColor,
                        stroke = bdColor,
                        strokeWidth = borderStroke,
                        opacity = bdAlpha
                    )
                )
            } else {
                nodes.add(
                    PathNode(
                        svgPathData = VeilPositionPatternGeometry.SQ25_PATH,
                        fill = bdColor,
                        stroke = bdColor,
                        strokeWidth = borderStroke,
                        opacity = bdAlpha,
                        transform = String.format(
                            Locale.US,
                            "translate(%.4f, %.4f) scale(%.6f, %.6f)",
                            ox + iconXY,
                            oy + iconXY,
                            iconSize / 100f,
                            iconSize / 100f
                        )
                    )
                )
            }
        }

        // 4. SVG Mask in Defs per EFQRCodeStyle.swift:241-245
        if (logo.shape == LogoShape.CIRCLE) {
            val cx = ox + iconXY + iconSize / 2f
            val cy = oy + iconXY + iconSize / 2f
            val r = iconSize / 2f
            defs.add("""<mask id="$randomIdClips"><circle cx="$cx" cy="$cy" r="$r" fill="#ffffff"/></mask>""")
        } else {
            val maskTransform = String.format(
                Locale.US,
                "translate(%.4f, %.4f) scale(%.6f, %.6f)",
                ox + iconXY,
                oy + iconXY,
                iconSize / 100f,
                iconSize / 100f
            )
            defs.add("""<path id="$randomIdDefs" d="${VeilPositionPatternGeometry.SQ25_PATH}"/>""")
            defs.add("""<mask id="$randomIdClips"><use xlink:href="#$randomIdDefs" overflow="visible" fill="#ffffff" transform="$maskTransform"/></mask>""")
        }

        // 5. Preprocessing & Embedding per EFQRCodeStyle.swift:247-250 (dynamic framePrefix)
        val iconOpacity = logo.alpha.coerceIn(0f, 1f)
        val isAnimated = logo.isAnimated && !logo.animatedFrames.isNullOrEmpty()

        if (isAnimated) {
            val logoFrames = logo.animatedFrames ?: emptyList()
            val logoDelays = logo.frameDelaysMs ?: emptyList()
            val preprocessedFrames = logoFrames.map { EfImagePreprocessor.preprocess(it, length, length, logo.scaleMode) }
            val framePrefix = "${nextUniqueMark()}fm"
            nodes.add(
                AnimatedImageNode(
                    x = ox + rectXY,
                    y = oy + rectXY,
                    width = length,
                    height = length,
                    frames = preprocessedFrames,
                    base64Frames = preprocessedFrames.map { IrSvgRenderer.bitmapToBase64(it) },
                    frameDelaysMs = logoDelays,
                    opacity = iconOpacity,
                    preserveAspectRatio = "",
                    maskId = randomIdClips,
                    framePrefix = framePrefix
                )
            )
        } else {
            val preprocessedBmp = EfImagePreprocessor.preprocess(logoBmp, length, length, logo.scaleMode)
            nodes.add(
                ImageNode(
                    x = ox + rectXY,
                    y = oy + rectXY,
                    width = length,
                    height = length,
                    bitmap = preprocessedBmp,
                    base64Data = IrSvgRenderer.bitmapToBase64(preprocessedBmp),
                    opacity = iconOpacity,
                    preserveAspectRatio = "",
                    maskId = randomIdClips
                )
            )
        }
    }

    /**
     * Appends icon SVG XML directly to a StringBuilder for direct-string SVG exporters (EFQRCodeStyle.swift:214-253).
     */
    fun appendIconSvg(
        sb: StringBuilder,
        design: QrDesign,
        ox: Double,
        oy: Double,
        qrPixelSize: Double,
        bgHex: String
    ) {
        val logo = design.logo ?: return
        val logoBmp = logo.effectiveBitmap ?: return

        // 1. Sizing: hard-capped at min(percentage, 0.33) per EFQRCodeStyle.swift:218
        val scale = minOf(maxOf(0.0, logo.scaleFraction.toDouble()), 0.33)
        val iconSize = qrPixelSize * scale
        val iconXY = (qrPixelSize - iconSize) / 2.0

        // 2. Exact 2.4% geometric offset per EFQRCodeStyle.swift:222-225
        val iconOffset = iconXY * 0.024
        val rectXY = iconXY - iconOffset
        val length = iconSize + 2.0 * iconOffset

        val bdColor = logo.borderColor ?: if (logo.backgroundMode != LogoBackgroundMode.NONE) design.palette.background else null
        val bdColorHex = if (bdColor != null) String.format(Locale.US, "#%06X", 0xFFFFFF and bdColor) else bgHex
        val bdAlpha = if (bdColor != null) (((bdColor ushr 24) and 0xFF) / 255.0) else 1.0

        val markDefs = nextUniqueMark()
        val randomIdDefs = "icon$markDefs"
        val markClips = nextUniqueMark()
        val randomIdClips = "icon$markClips"

        // 3. SQ25 Squircle or Circle Border per EFQRCodeStyle.swift:232
        val borderStroke = if (logo.borderWidth > 0f) logo.borderWidth.toDouble() else (100.0 / iconSize)
        val strokeStr = String.format(Locale.US, "%.3f", borderStroke)
        val alphaStr = String.format(Locale.US, "%.2f", bdAlpha)

        if (logo.shape == LogoShape.CIRCLE) {
            val cx = String.format(Locale.US, "%.4f", ox + iconXY + iconSize / 2.0)
            val cy = String.format(Locale.US, "%.4f", oy + iconXY + iconSize / 2.0)
            val r = String.format(Locale.US, "%.4f", iconSize / 2.0)
            sb.append("""  <circle cx="$cx" cy="$cy" r="$r" fill="$bdColorHex" stroke="$bdColorHex" stroke-width="$strokeStr" opacity="$alphaStr" />""").append("\n")
            sb.append("  <defs>\n")
            sb.append("""    <mask id="$randomIdClips">""").append("\n")
            sb.append("""      <circle cx="$cx" cy="$cy" r="$r" fill="#ffffff" />""").append("\n")
            sb.append("""    </mask>""").append("\n")
        } else {
            val tx = String.format(Locale.US, "%.4f", ox + iconXY)
            val ty = String.format(Locale.US, "%.4f", oy + iconXY)
            val s = String.format(Locale.US, "%.6f", iconSize / 100.0)
            sb.append("""  <path opacity="$alphaStr" d="${VeilPositionPatternGeometry.SQ25_PATH}" stroke="$bdColorHex" stroke-width="$strokeStr" fill="$bdColorHex" transform="translate($tx,$ty) scale($s,$s)"/>""").append("\n")
            sb.append("  <defs>\n")
            sb.append("""    <path id="$randomIdDefs" d="${VeilPositionPatternGeometry.SQ25_PATH}"/>""").append("\n")
            sb.append("""    <mask id="$randomIdClips">""").append("\n")
            sb.append("""      <use xlink:href="#$randomIdDefs" overflow="visible" fill="#ffffff" transform="translate($tx,$ty) scale($s,$s)"/>""").append("\n")
            sb.append("""    </mask>""").append("\n")
        }

        val imgX = String.format(Locale.US, "%.4f", ox + rectXY)
        val imgY = String.format(Locale.US, "%.4f", oy + rectXY)
        val imgLen = String.format(Locale.US, "%.4f", length)
        val iconOpacity = String.format(Locale.US, "%.2f", logo.alpha.coerceIn(0f, 1f))

        val isAnimated = logo.isAnimated && !logo.animatedFrames.isNullOrEmpty()
        if (isAnimated) {
            val logoFrames = logo.animatedFrames ?: emptyList()
            val rawDelays = logo.frameDelaysMs
            val logoDelays = if (!rawDelays.isNullOrEmpty()) rawDelays else List(logoFrames.size) { 100 }
            val totalDurationMs = maxOf(1, logoDelays.sum())
            val totalDurationSec = totalDurationMs / 1000.0
            val preprocessedFrames = logoFrames.map { EfImagePreprocessor.preprocess(it, length.toFloat(), length.toFloat(), logo.scaleMode) }
            val base64Frames = preprocessedFrames.map { IrSvgRenderer.bitmapToBase64(it) }
            val framePrefix = "${nextUniqueMark()}fm"

            for ((idx, b64) in base64Frames.withIndex()) {
                sb.append("""    <image id="$framePrefix$idx" xlink:href="data:image/png;base64,$b64" width="$imgLen" height="$imgLen" x="$imgX" y="$imgY" opacity="$iconOpacity"/>""").append("\n")
            }
            sb.append("  </defs>\n")

            var accumulatedMs = 0
            val keyTimes = mutableListOf<String>()
            for (delay in logoDelays) {
                val fraction = accumulatedMs.toDouble() / totalDurationMs
                keyTimes.add(String.format(Locale.US, "%.3f", fraction))
                accumulatedMs += delay
            }
            val valuesStr = base64Frames.indices.joinToString(";") { "#$framePrefix$it" }
            val keyTimesStr = keyTimes.joinToString(";")
            val durStr = String.format(Locale.US, "%.3f", totalDurationSec)

            sb.append("""  <g mask="url(#$randomIdClips)">""").append("\n")
            sb.append("""    <use xlink:href="#${framePrefix}0">""").append("\n")
            sb.append("      <animate\n")
            sb.append("""        attributeName="xlink:href"""").append("\n")
            sb.append("""        values="$valuesStr"""").append("\n")
            sb.append("""        keyTimes="$keyTimesStr"""").append("\n")
            sb.append("""        dur="${durStr}s"""").append("\n")
            sb.append("""        repeatCount="indefinite"""").append("\n")
            sb.append("""        calcMode="discrete"""").append("\n")
            sb.append("      />\n")
            sb.append("    </use>\n")
            sb.append("  </g>\n")
        } else {
            val preprocessedBmp = EfImagePreprocessor.preprocess(logoBmp, length.toFloat(), length.toFloat(), logo.scaleMode)
            val base64 = IrSvgRenderer.bitmapToBase64(preprocessedBmp)
            sb.append("  </defs>\n")
            if (base64.isNotEmpty()) {
                sb.append("""  <g mask="url(#$randomIdClips)">""").append("\n")
                sb.append("""    <image xlink:href="data:image/png;base64,$base64" width="$imgLen" height="$imgLen" x="$imgX" y="$imgY" opacity="$iconOpacity"/>""").append("\n")
                sb.append("  </g>\n")
            }
        }
    }

    /**
     * Resolves the target bitmap for a logo given an animation [frameIndex].
     * For static logos, returns [LogoStyle.effectiveBitmap].
     * For animated logos, returns the frame at [frameIndex] % frames.size.
     */
    fun resolveLogoBitmap(logo: com.veilframe.app.qr.model.LogoStyle, frameIndex: Int = 0): android.graphics.Bitmap? {
        val isAnimated = logo.isAnimated && !logo.animatedFrames.isNullOrEmpty()
        return if (isAnimated) {
            val frames = logo.animatedFrames!!
            val safeIdx = if (frameIndex >= 0) frameIndex % frames.size else 0
            frames.getOrNull(safeIdx) ?: logo.effectiveBitmap
        } else {
            logo.effectiveBitmap
        }
    }

    /**
     * Authoritative Canvas drawing implementation for Android Canvas renderers.
     * Shares exact 0.33 clamp, SQ25 path, 2.4% offset, and EfImagePreprocessor scaling.
     * Supports animated logos via [frameIndex] (defaulting to [context.frameIndex] ?: 0).
     */
    fun drawLogo(
        canvas: Canvas,
        design: QrDesign,
        ox: Float,
        oy: Float,
        qrPixelSize: Float,
        context: RenderContext? = null,
        frameIndex: Int = context?.frameIndex ?: 0
    ) {
        val logo = design.logo ?: return
        val logoBmp = resolveLogoBitmap(logo, frameIndex) ?: return

        // 1. Sizing: universal 0.33 hard cap
        val scale = minOf(maxOf(0f, logo.scaleFraction), 0.33f)
        val iconSize = qrPixelSize * scale
        val iconXY = (qrPixelSize - iconSize) / 2f

        // 2. Exact 2.4% geometric offset
        val iconOffset = iconXY * 0.024f
        val rectXY = iconXY - iconOffset
        val length = iconSize + 2f * iconOffset

        val borderRect = RectF(ox + iconXY, oy + iconXY, ox + iconXY + iconSize, oy + iconXY + iconSize)
        val imageRect = RectF(ox + rectXY, oy + rectXY, ox + rectXY + length, oy + rectXY + length)

        // 3. Path for border and clipping (SQ25 Squircle or Circle)
        val clipPath = if (logo.shape == LogoShape.CIRCLE) {
            Path().apply {
                addCircle(borderRect.centerX(), borderRect.centerY(), borderRect.width() / 2f, Path.Direction.CW)
            }
        } else {
            QrVisualGeometry.createSquirclePath(borderRect)
        }

        // 4. Background and/or Border
        val bdColor = logo.borderColor ?: if (logo.backgroundMode != LogoBackgroundMode.NONE) design.palette.background else null
        val borderStroke = if (logo.borderWidth > 0f) logo.borderWidth else (100f / iconSize)
        if (bdColor != null) {
            if (logo.backgroundMode != LogoBackgroundMode.NONE) {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bdColor
                    style = Paint.Style.FILL
                }
                canvas.drawPath(clipPath, fillPaint)
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bdColor
                style = Paint.Style.STROKE
                this.strokeWidth = borderStroke
            }
            canvas.drawPath(clipPath, strokePaint)
        }

        // 5. Preprocessed Image with clipping (per-frame preprocessing)
        val preprocessed = EfImagePreprocessor.preprocess(logoBmp, length, length, logo.scaleMode)
        val iconAlpha = (logo.alpha.coerceIn(0f, 1f) * 255).toInt()
        val imgPaint = if (iconAlpha < 255) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = iconAlpha }
        } else null

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(preprocessed, null, imageRect, imgPaint)
        canvas.restore()
    }
}
