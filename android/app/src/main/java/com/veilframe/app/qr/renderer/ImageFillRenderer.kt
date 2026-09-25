package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.geometry.GroupNode
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.geometry.IrSvgRenderer
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.RectNode
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
class ImageFillRenderer : QrRenderer {

    fun generateGeometry(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry
    ): QrGeometryIr {
        val n = matrix.size
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val width = geometry.outputWidth.toFloat()
        val height = geometry.outputHeight.toFloat()
        val nodes = mutableListOf<QrGeometryNode>()

        val sourceBmp = design.imageSource.bitmap
        val bgColor = design.imageFillBackgroundColor
        val maskColor = design.imageFillMaskColor
        val imageAlpha = design.imageSource.opacity.coerceIn(0f, 1f)

        // 1. Base canvas background
        val bgAlpha = (design.palette.background ushr 24) and 0xFF
        if (bgAlpha > 0) {
            nodes.add(RectNode(x = 0f, y = 0f, width = width, height = height, fill = design.palette.background))
        }

        // 2. Continuous masked group with hole mask
        val maskDef = buildString {
            append("""<mask id="hole">""")
            append("""<rect x="0" y="0" width="$width" height="$height" fill="black"/>""")
            val antiGap = 0.01f * mSize
            for (col in 0 until n) {
                for (row in 0 until n) {
                    if (matrix.isDark(col, row)) {
                        val left = ox + col * mSize - antiGap
                        val top = oy + row * mSize - antiGap
                        val w = mSize + 2 * antiGap
                        val h = mSize + 2 * antiGap
                        append("""<rect x="$left" y="$top" width="$w" height="$h" fill="white"/>""")
                    }
                }
            }
            append("""</mask>""")
        }

        val groupChildren = mutableListOf<QrGeometryNode>()
        // 2a. Background inside dark modules
        groupChildren.add(RectNode(x = ox, y = oy, width = n * mSize, height = n * mSize, fill = bgColor))

        // 2b. Scaled source image
        if (sourceBmp != null && !sourceBmp.isRecycled) {
            val base64 = IrSvgRenderer.bitmapToBase64(sourceBmp)
            groupChildren.add(
                ImageNode(
                    x = ox,
                    y = oy,
                    width = n * mSize,
                    height = n * mSize,
                    bitmap = sourceBmp,
                    base64Data = base64,
                    opacity = imageAlpha,
                    preserveAspectRatio = "xMidYMid slice"
                )
            )
        }

        // 2c. Overlay mask tint
        groupChildren.add(RectNode(x = ox, y = oy, width = n * mSize, height = n * mSize, fill = maskColor))

        nodes.add(GroupNode(children = groupChildren, maskId = "hole"))

        return QrGeometryIr(
            width = width,
            height = height,
            defs = listOf(maskDef),
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
        val sourceImage = design.imageSource.bitmap

        if (sourceImage == null || sourceImage.isRecycled) {
            BasicRenderer().render(matrix, design, canvas, geometry, context)
            return
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

        // 3b. Continuous scaled image across QR area
        ImageScaleResolver.drawScaledBitmap(canvas, sourceImage, dataBounds, imageMode, imageAlpha)

        // 3c. Solid maskColor tint overlay across QR area
        val tintPaint = context.obtainFill(maskColor)
        canvas.drawRect(dataBounds, tintPaint)

        canvas.restoreToCount(contentLayer)
        canvas.restoreToCount(layerId)

        // 4. Center Logo if present
        drawLogo(canvas, design, geometry, context)
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
