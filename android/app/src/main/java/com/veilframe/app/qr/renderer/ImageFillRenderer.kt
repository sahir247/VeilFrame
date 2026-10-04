package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.util.Locale
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.AnimatedImageNode
import com.veilframe.app.qr.geometry.GroupNode
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.geometry.IrSvgRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.QrMaskDefinition
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.image.EfImagePreprocessor
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

/**
 * Style 5 — IMAGE_FILL
 *
 * Implements continuous image masking:
 * `<mask id="hole">...<rect width="1.02" height="1.02" fill="white"/>...</mask>`
 * `<g mask="url(#hole)"><rect fill="backgroundColor"/><image .../><rect fill="maskColor"/></g>`
 *
 * The source image is continuous across the QR code area and revealed strictly through
 * the dark-module stencil mask, combined with a base [backgroundColor] and an overlay [maskColor] tint.
 */
class ImageFillRenderer : IrBackedQrRenderer {

    override val ownsBackdrop: Boolean
        get() = true

    override fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val width = geometry.outputWidthFloat
        val height = geometry.outputHeightFloat
        val nodes = mutableListOf<QrGeometryNode>()
        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()

        val sourceBmp = design.imageSource.bitmap
        val bgColor = design.imageFillBackgroundColor
        val maskColor = design.imageFillMaskColor
        val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)

        // 1. Base canvas background & Backdrop (EF generic backdrop contract)
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        val bgAlpha = (resolvedBackdropColor ushr 24) and 0xFF
        val bgAlphaFloat = bgAlpha / 255f
        val bgHex = String.format(Locale.US, "#%02X%02X%02X", (resolvedBackdropColor ushr 16) and 0xFF, (resolvedBackdropColor ushr 8) and 0xFF, resolvedBackdropColor and 0xFF)
        val hasCornerClip = design.backdropStyle.cornerRadius > 0f
        val crPx = if (hasCornerClip) design.backdropStyle.cornerRadius * mSize else 0f
        if (bgAlpha > 0) {
            nodes.add(
                RectNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    rx = crPx,
                    ry = crPx,
                    fill = resolvedBackdropColor,
                    fillString = bgHex,
                    opacity = bgAlphaFloat,
                    alwaysEmitOpacity = true
                )
            )
        }

        val backdropImg = design.backdropStyle.image
        if (backdropImg != null && !backdropImg.isRecycled) {
            val preprocessedBackdrop = EfImagePreprocessor.preprocess(
                source = backdropImg,
                canvasWidth = width,
                canvasHeight = height,
                mode = design.backdropStyle.imageScaleMode
            )
            val base64 = IrSvgRenderer.bitmapToBase64(preprocessedBackdrop)
            nodes.add(
                ImageNode(
                    x = 0f,
                    y = 0f,
                    width = width,
                    height = height,
                    bitmap = preprocessedBackdrop,
                    base64Data = base64,
                    opacity = design.backdropStyle.imageAlpha,
                    preserveAspectRatio = "",
                    key = "bi"
                )
            )
        }

        if (hasCornerClip) {
            val crStr = IrSvgRenderer.formatCoord(crPx)
            val twStr = IrSvgRenderer.formatCoord(width)
            val thStr = IrSvgRenderer.formatCoord(height)
            defs.add("""<clipPath id="rounded-corners"><rect width="$twStr" height="$thStr" rx="$crStr" ry="$crStr"/></clipPath>""")
            val cornerPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, width, height, crPx, crPx, android.graphics.Path.Direction.CW)
            }
            masks["rounded-corners"] = QrMaskDefinition("rounded-corners", clipPath = cornerPath)
        }

        val antiGap = 0.01f * mSize
        val w = mSize + 2 * antiGap
        val h = mSize + 2 * antiGap
        // 2. Continuous masked group with hole mask
        val maskDef = buildString {
            append("""<mask id="hole">""")
            append("""<rect x="0" y="0" width="$width" height="$height" fill="black"/>""")
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.isDark(col, row)) {
                        val left = ox + col * mSize - antiGap
                        val top = oy + row * mSize - antiGap
                        append("""<rect x="$left" y="$top" width="$w" height="$h" fill="white"/>""")
                    }
                }
            }
            append("""</mask>""")
        }
        defs.add(maskDef)

        val groupChildren = mutableListOf<QrGeometryNode>()
        // 2a. Background inside dark modules
        val fillBgAlphaFloat = ((bgColor ushr 24) and 0xFF) / 255f
        val fillBgHex = String.format(Locale.US, "#%02X%02X%02X", (bgColor ushr 16) and 0xFF, (bgColor ushr 8) and 0xFF, bgColor and 0xFF)
        groupChildren.add(
            RectNode(
                x = ox,
                y = oy,
                width = n * mSize,
                height = n * mSize,
                fill = bgColor,
                fillString = fillBgHex,
                opacity = fillBgAlphaFloat
            )
        )

        // 2b. Scaled source image (preprocessed via EfImagePreprocessor matching EFQRCodeStyle.swift:269)
        // EF parity: preprocessed image already matches canvas ratio; preserveAspectRatio is omitted/empty.
        val canvasW = n * mSize
        val canvasH = n * mSize
        val isAnimated = design.imageSource.isAnimated || (design.imageSource.animatedFrames?.isNotEmpty() == true)
        val animatedFrames = design.imageSource.animatedFrames
        val frameDelaysMs = design.imageSource.frameDelaysMs ?: emptyList()

        if (isAnimated && animatedFrames != null && animatedFrames.isNotEmpty()) {
            val preprocessedFrames = animatedFrames.map { frame ->
                EfImagePreprocessor.preprocess(
                    source = frame,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    mode = design.imageSource.scaleMode
                )
            }
            val base64Frames = preprocessedFrames.map { IrSvgRenderer.bitmapToBase64(it) }
            groupChildren.add(
                AnimatedImageNode(
                    x = ox,
                    y = oy,
                    width = canvasW,
                    height = canvasH,
                    frames = preprocessedFrames,
                    base64Frames = base64Frames,
                    frameDelaysMs = frameDelaysMs,
                    opacity = imageAlpha,
                    preserveAspectRatio = "",
                    framePrefix = "${com.veilframe.app.qr.geometry.VeilIconPipeline.nextUniqueMark()}fm"
                )
            )
        } else {
            val preprocessed = if (sourceBmp != null && !sourceBmp.isRecycled) {
                EfImagePreprocessor.preprocess(
                    source = sourceBmp,
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    mode = design.imageSource.scaleMode
                )
            } else null
            val base64 = if (preprocessed != null) IrSvgRenderer.bitmapToBase64(preprocessed) else ""

            if (preprocessed != null) {
                groupChildren.add(
                    ImageNode(
                        x = ox,
                        y = oy,
                        width = canvasW,
                        height = canvasH,
                        bitmap = preprocessed,
                        base64Data = base64,
                        opacity = imageAlpha,
                        preserveAspectRatio = ""
                    )
                )
            }
        }

        // 2c. Overlay mask tint
        val fillMaskAlphaFloat = ((maskColor ushr 24) and 0xFF) / 255f
        val fillMaskHex = String.format(Locale.US, "#%02X%02X%02X", (maskColor ushr 16) and 0xFF, (maskColor ushr 8) and 0xFF, maskColor and 0xFF)
        groupChildren.add(
            RectNode(
                x = ox,
                y = oy,
                width = canvasW,
                height = canvasH,
                fill = maskColor,
                fillString = fillMaskHex,
                opacity = fillMaskAlphaFloat
            )
        )

        val stencilPath = android.graphics.Path().apply {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.isDark(col, row)) {
                        val left = ox + col * mSize - antiGap
                        val top = oy + row * mSize - antiGap
                        val right = ox + (col + 1) * mSize + antiGap
                        val bottom = oy + (row + 1) * mSize + antiGap
                        addRect(left, top, right, bottom, android.graphics.Path.Direction.CW)
                    }
                }
            }
        }

        nodes.add(GroupNode(children = groupChildren, maskId = "hole", clipPath = stencilPath))

        masks["hole"] = QrMaskDefinition(id = "hole", clipPath = stencilPath)

        // 3. Center Logo (EFQRCodeStyleImageFill.swift:263-276 writeIcon parity)
        com.veilframe.app.qr.geometry.VeilIconPipeline.appendIconNodes(
            nodes = nodes,
            defs = defs,
            design = design,
            ox = ox,
            oy = oy,
            qrPixelSize = n * mSize,
            masks = masks
        )

        return QrGeometryIr(
            width = width,
            height = height,
            defs = defs,
            masks = masks,
            rootNodes = nodes
        )
    }

    override fun render(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry,
        context: RenderContext
    ) {
        val crPx = if (design.backdropStyle.cornerRadius > 0f) design.backdropStyle.cornerRadius * geometry.moduleSize else 0f
        val count = if (crPx > 0f) {
            val saveCount = canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, geometry.outputWidthFloat, geometry.outputHeightFloat, crPx, crPx, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            saveCount
        } else null

        try {
            val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
            val bgCanvasAlpha = (resolvedBackdropColor ushr 24) and 0xFF
            if (bgCanvasAlpha > 0) {
                val canvasBgPaint = context.obtainFill(resolvedBackdropColor)
                if (crPx > 0f) {
                    canvas.drawRoundRect(0f, 0f, geometry.outputWidthFloat, geometry.outputHeightFloat, crPx, crPx, canvasBgPaint)
                } else {
                    canvas.drawRect(0f, 0f, geometry.outputWidthFloat, geometry.outputHeightFloat, canvasBgPaint)
                }
            }

            // Draw backdrop image if configured
            val backdropImg = design.backdropStyle.image
            if (backdropImg != null && !backdropImg.isRecycled) {
                val preprocessedBackdrop = EfImagePreprocessor.preprocess(
                    source = backdropImg,
                    canvasWidth = geometry.outputWidthFloat,
                    canvasHeight = geometry.outputHeightFloat,
                    mode = design.backdropStyle.imageScaleMode
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    isDither = false
                    alpha = (design.backdropStyle.imageAlpha.coerceIn(0f, 1f) * 255).toInt()
                }
                // Preprocessed backdrop matches canvas aspect ratio; draw 1:1 without second crop/scale pass.
                val dstBounds = RectF(0f, 0f, geometry.outputWidthFloat, geometry.outputHeightFloat)
                canvas.drawBitmap(preprocessedBackdrop, null, dstBounds, paint)
            }

            val isAnimated = design.imageSource.isAnimated && !design.imageSource.animatedFrames.isNullOrEmpty()
            val sourceImage = if (isAnimated) {
                val frames = design.imageSource.animatedFrames!!
                val safeIdx = if (context.frameIndex >= 0) context.frameIndex % frames.size else 0
                frames.getOrNull(safeIdx) ?: design.imageSource.bitmap
            } else {
                design.imageSource.bitmap
            }

            val n = matrix.size
            val mSize = geometry.moduleSize
            val x0 = geometry.offsetX
            val y0 = geometry.offsetY
            val dataBounds = geometry.dataRegionBounds()

            val bgColor = design.imageFillBackgroundColor
            val maskColor = design.imageFillMaskColor
            val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)
            val imageMode = design.imageSource.scaleMode

            // 1. Offscreen layer for masked QR stencil
            val layerId = canvas.saveLayer(dataBounds, null)

            // 2. Draw solid stencil mask of all dark modules with anti-gap 1.02 expansion
            val maskPaint = context.obtainFill(Color.WHITE)
            val antiGap = 0.01f * mSize

            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.isDark(col, row)) {
                        val left = x0 + col * mSize - antiGap
                        val top = y0 + row * mSize - antiGap
                        val right = x0 + (col + 1) * mSize + antiGap
                        val bottom = y0 + (row + 1) * mSize + antiGap
                        canvas.drawRect(left, top, right, bottom, maskPaint)
                    }
                }
            }

            // 3. Composite continuous fill content using SRC_IN
            val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            val contentLayer = canvas.saveLayer(dataBounds, contentPaint)

            // 3a. Solid backgroundColor across QR area
            val bgPaint = context.obtainFill(bgColor)
            canvas.drawRect(dataBounds, bgPaint)

            // 3b. Continuous scaled image across QR area (preprocessed via EfImagePreprocessor for SVG/IR parity)
            if (sourceImage != null && !sourceImage.isRecycled) {
                val preprocessed = EfImagePreprocessor.preprocess(
                    source = sourceImage,
                    canvasWidth = dataBounds.width(),
                    canvasHeight = dataBounds.height(),
                    mode = imageMode
                )
                val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    isDither = false
                    alpha = (imageAlpha * 255).toInt().coerceIn(0, 255)
                }
                // Preprocessed image matches dataBounds aspect ratio; draw 1:1 without second crop/scale pass.
                canvas.drawBitmap(preprocessed, null, dataBounds, imgPaint)
            }

            // 3c. Solid maskColor tint overlay across QR area
            val tintPaint = context.obtainFill(maskColor)
            canvas.drawRect(dataBounds, tintPaint)

            canvas.restoreToCount(contentLayer)
            canvas.restoreToCount(layerId)

            // 4. Center Logo if present
            drawLogo(canvas, design, geometry, context)
        } finally {
            if (count != null) canvas.restoreToCount(count)
        }
    }

    override fun render(matrix: QrMatrix, params: QrStyleParams, canvas: Canvas, cellSize: Float) {
        val design = QrDesign.fromQrStyleParams(params)
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * cellSize).toInt(),
            outputHeight = (matrix.size * cellSize).toInt(),
            quietZoneModules = 0
        )
        val context = RenderContext()
        render(matrix, design, canvas, geometry, context)
    }
}
