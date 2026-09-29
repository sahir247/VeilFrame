package com.veilframe.app.qr.geometry

import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.LogoBackgroundMode
import com.veilframe.app.qr.model.QrDesign
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
 * - Border: SQ25 squircle path with bdColor, bdAlpha, and strokeWidth = 100.0 / iconSize
 * - Masking: SVG <defs> path with <mask id="icon{mark}"> clipping the icon image
 * - Preprocessing: per-frame EfImagePreprocessor scaling across all frames
 * - Dynamic unique ID generation: atomic counter mirroring EF Anchor.uniqueMark
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
     * @param preferredFramePrefix Optional frame prefix for animated icons (e.g. "logofm" or dynamic).
     */
    fun appendIconNodes(
        nodes: MutableList<QrGeometryNode>,
        defs: MutableList<String>,
        design: QrDesign,
        ox: Float,
        oy: Float,
        qrPixelSize: Float,
        preferredFramePrefix: String? = null
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

        // 3. SQ25 Squircle Border per EFQRCodeStyle.swift:232
        val bdColor = logo.borderColor ?: if (logo.backgroundMode != LogoBackgroundMode.NONE) design.palette.background else null
        if (bdColor != null) {
            val bdAlpha = ((bdColor ushr 24) and 0xFF) / 255f
            val borderStroke = 100f / iconSize
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

        // 4. SVG Mask in Defs per EFQRCodeStyle.swift:241-245
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

        // 5. Preprocessing & Embedding per EFQRCodeStyle.swift:247-250
        val iconOpacity = logo.alpha.coerceIn(0f, 1f)
        val isAnimated = logo.isAnimated && !logo.animatedFrames.isNullOrEmpty()

        if (isAnimated) {
            val logoFrames = logo.animatedFrames ?: emptyList()
            val logoDelays = logo.frameDelaysMs ?: emptyList()
            val preprocessedFrames = logoFrames.map { EfImagePreprocessor.preprocess(it, length, length, logo.scaleMode) }
            val framePrefix = preferredFramePrefix ?: "${nextUniqueMark()}fm"
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
}
