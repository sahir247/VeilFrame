package com.veilframe.app.qr.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.model.ImageScaleMode
import com.veilframe.app.qr.raster.EfRasterBackend
import com.veilframe.app.qr.raster.SkiaEfRasterBackend

/**
 * EF parity image pre-processor: reproduces EFImageMode.imageForContent(ofImage:inCanvasOfRatio:)
 * from EFQRCode 7.0.3 (scratch/EFQRCode/Source/Type/EFImageMode.swift, lines 123–170)
 * and CGImage.clipAndExpandingTransparencyWith(rect:) / CGImage.resize(to:)
 * (scratch/EFQRCode/Source/Extension/CGImage+EFQRCode.swift, lines 151–218).
 *
 * PARITY CONTRACT (M1 — Milestone 1):
 *
 * EF does NOT stretch source images directly to the final canvas dimensions.
 * It always produces an intermediate bitmap at integer-truncated dimensions
 * matching the canvas aspect ratio, then embeds that pre-scaled bitmap into the SVG.
 *
 * Three modes mirror EFImageMode exactly:
 *
 * 1. [ImageScaleMode.STRETCH] -> EF `scaleToFill`
 *    Computes intermediate size matching canvas ratio; integer-truncates both axes.
 *    Creates CGContext(premultipliedLast) and draws source into it at (0,0,newW,newH).
 *    Android equiv: draw source Bitmap into a new ARGB_8888 Bitmap with bilinear Paint.
 *
 * 2. [ImageScaleMode.ASPECT_FIT] -> EF `scaleAspectFit`
 *    Computes canvas-ratio size keeping the opposite axis equal to the source.
 *    The resulting rect may extend OUTSIDE the source bounds (letterbox/pillarbox).
 *    Creates a transparent ARGB_8888 Bitmap; clears to 0x00000000 (matches context.clear()).
 *    Draws source at an offset so it is centered, with transparent margins.
 *
 * 3. [ImageScaleMode.ASPECT_FILL] / [ImageScaleMode.CENTER_CROP] -> EF `scaleAspectFill`
 *    Computes canvas-ratio size keeping the opposite axis equal to the source.
 *    The resulting rect is entirely WITHIN the source bounds (crop, no margin).
 *    Calls self.cropping(to: rect): zero-interpolation exact pixel slice, no resize.
 *    Android equiv: Bitmap.createBitmap(source, x, y, w, h) - exact pixel copy.
 *
 * IMPORTANT: Integer truncation is applied via .toInt() (Kotlin truncation for positive values),
 * matching Swift Int(CGFloat) which also truncates toward zero.
 *
 * Evidence status: UNVERIFIED (implementation written against source; awaiting Layer B oracle execution).
 */
object EfImagePreprocessor {

    /**
     * Active rasterization backend for pixel operations.
     * Defaults to [SkiaEfRasterBackend] for Android runtime execution.
     */
    var backend: EfRasterBackend = SkiaEfRasterBackend

    /**
     * Pre-processes [source] to the aspect ratio described by [canvasWidth] x [canvasHeight],
     * using the mode specified by [mode], reproducing EFImageMode.imageForContent().
     *
     * Returns a new Bitmap at intermediate integer dimensions, ready for base64 embedding.
     * The returned Bitmap may be the same instance as [source] when the ratio already matches.
     *
     * @param source       Source bitmap (not recycled by this function).
     * @param canvasWidth  Width of the QR canvas (typically nCount modules).
     * @param canvasHeight Height of the QR canvas (typically nCount modules).
     * @param mode         VeilFrame scale mode (mapped to EF EFImageMode).
     */
    fun preprocess(
        source: Bitmap,
        canvasWidth: Double,
        canvasHeight: Double,
        mode: ImageScaleMode
    ): Bitmap {
        val imageWidth = source.width.toDouble()
        val imageHeight = source.height.toDouble()
        val canvasW = canvasWidth
        val canvasH = canvasHeight

        if (imageWidth <= 0.0 || imageHeight <= 0.0 || canvasW <= 0.0 || canvasH <= 0.0) {
            return source
        }

        // EF early-exit: if ratio already matches, return source unchanged.
        // Exact floating-point comparison mirrors EFImageMode.swift line 127.
        if (imageWidth / imageHeight == canvasW / canvasH) {
            return source
        }

        val widthRatio = imageWidth / canvasW
        val heightRatio = imageHeight / canvasH

        return when (mode) {
            // scaleToFill (EFImageMode.swift:132-140)
            ImageScaleMode.STRETCH -> {
                val (newWidthD, newHeightD) = scaleToFillSizeD(
                    imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio
                )
                resizeBitmap(source, newWidthD.toInt(), newHeightD.toInt())
            }

            // scaleAspectFit (EFImageMode.swift:141-154)
            // Computes 64-bit Double newSize and origin, then truncates destination canvas dimensions.
            ImageScaleMode.ASPECT_FIT -> {
                val (newWidthD, newHeightD) = scaleAspectFitSizeD(
                    imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio
                )
                val originX = -(imageWidth - newWidthD) / 2.0
                val originY = -(imageHeight - newHeightD) / 2.0
                clipAndExpandTransparency(
                    source = source,
                    rectX = originX,
                    rectY = originY,
                    rectW = newWidthD.toInt(),
                    rectH = newHeightD.toInt()
                )
            }

            // scaleAspectFill (EFImageMode.swift:155-168)
            // Uses EF's clipAndExpandingTransparencyWith(rect: rect) path with 64-bit Double rect
            ImageScaleMode.ASPECT_FILL, ImageScaleMode.CENTER_CROP -> {
                val (newWidthD, newHeightD) = scaleAspectFillSizeD(
                    imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio
                )
                val originX = -(imageWidth - newWidthD) / 2.0
                val originY = -(imageHeight - newHeightD) / 2.0
                clipAndExpandTransparency(
                    source = source,
                    rectX = originX,
                    rectY = originY,
                    rectW = newWidthD.toInt(),
                    rectH = newHeightD.toInt()
                )
            }
        }
    }

    /**
     * Overload for integral canvas dimensions, preventing Float intermediate precision loss.
     */
    fun preprocess(
        source: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int,
        mode: ImageScaleMode
    ): Bitmap = preprocess(source, canvasWidth.toDouble(), canvasHeight.toDouble(), mode)

    /**
     * Overload for legacy/Float canvas dimensions.
     */
    fun preprocess(
        source: Bitmap,
        canvasWidth: Float,
        canvasHeight: Float,
        mode: ImageScaleMode
    ): Bitmap = preprocess(source, canvasWidth.toDouble(), canvasHeight.toDouble(), mode)

    // -------------------------------------------------------------------------
    // Intermediate size computations - exact translations of EFImageMode.swift
    // -------------------------------------------------------------------------

    /**
     * EF scaleToFill newSize as 64-bit CGFloat / Double CGSize (EFImageMode.swift:133-139).
     */
    internal fun scaleToFillSizeD(
        imageWidth: Double, imageHeight: Double,
        canvasW: Double, canvasH: Double,
        widthRatio: Double, heightRatio: Double
    ): Pair<Double, Double> {
        return if (widthRatio > heightRatio) {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        } else {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        }
    }

    /**
     * EF scaleToFill newSize as floating-point CGSize (EFImageMode.swift:133-139).
     */
    internal fun scaleToFillSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        val (w, h) = scaleToFillSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toFloat(), h.toFloat())
    }

    /**
     * EF scaleToFill newSize (EFImageMode.swift:133-139).
     * Returns (Int-truncated newWidth, Int-truncated newHeight).
     */
    internal fun scaleToFillSize(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Int, Int> {
        val (w, h) = scaleToFillSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toInt(), h.toInt())  // Swift: Int(newSize.width) - truncation toward zero
    }

    /**
     * EF scaleAspectFit newSize as 64-bit CGFloat / Double CGSize (EFImageMode.swift:142-148).
     */
    internal fun scaleAspectFitSizeD(
        imageWidth: Double, imageHeight: Double,
        canvasW: Double, canvasH: Double,
        widthRatio: Double, heightRatio: Double
    ): Pair<Double, Double> {
        return if (widthRatio > heightRatio) {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        } else {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        }
    }

    /**
     * EF scaleAspectFit newSize as floating-point CGSize (EFImageMode.swift:142-148).
     */
    internal fun scaleAspectFitSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        val (w, h) = scaleAspectFitSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toFloat(), h.toFloat())
    }

    /**
     * EF scaleAspectFit newSize (EFImageMode.swift:142-148).
     * Returns (Int-truncated newWidth, Int-truncated newHeight).
     */
    internal fun scaleAspectFitSize(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Int, Int> {
        val (w, h) = scaleAspectFitSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toInt(), h.toInt())
    }

    /**
     * EF scaleAspectFill newSize as 64-bit CGFloat / Double CGSize (EFImageMode.swift:156-162).
     */
    internal fun scaleAspectFillSizeD(
        imageWidth: Double, imageHeight: Double,
        canvasW: Double, canvasH: Double,
        widthRatio: Double, heightRatio: Double
    ): Pair<Double, Double> {
        return if (widthRatio < heightRatio) {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        } else {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        }
    }

    /**
     * EF scaleAspectFill newSize as floating-point CGSize (EFImageMode.swift:156-162).
     */
    internal fun scaleAspectFillSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        val (w, h) = scaleAspectFillSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toFloat(), h.toFloat())
    }

    /**
     * EF scaleAspectFill newSize (EFImageMode.swift:156-162).
     * Returns (Int-truncated newWidth, Int-truncated newHeight).
     * NOTE: Condition is widthRatio < heightRatio - REVERSED vs. scaleToFill and scaleAspectFit.
     */
    internal fun scaleAspectFillSize(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Int, Int> {
        val (w, h) = scaleAspectFillSizeD(imageWidth.toDouble(), imageHeight.toDouble(), canvasW.toDouble(), canvasH.toDouble(), widthRatio.toDouble(), heightRatio.toDouble())
        return Pair(w.toInt(), h.toInt())
    }

    // -------------------------------------------------------------------------
    // Pixel operations - Delegated to EfRasterBackend (Skia / Oracle)
    // -------------------------------------------------------------------------

    /**
     * Equivalent to CGImage.resize(to:) (CGImage+EFQRCode.swift:197-218).
     *
     * Creates a new ARGB_8888 Bitmap at (newWidth x newHeight) and draws the source
     * into the full rectangle using a bilinear-filtered Paint without dither.
     */
    internal fun resizeBitmap(source: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        return backend.resize(source, newWidth, newHeight)
    }

    /**
     * Equivalent to CGImage.clipAndExpandingTransparencyWith(rect:) (CGImage+EFQRCode.swift:151-185).
     *
     * In CoreGraphics, coordinates are 64-bit CGFloat. Destination bitmap dimensions are Int(rect.width/height).
     * The single unavoidable Double -> Float conversion happens strictly at Skia RectF construction.
     */
    internal fun clipAndExpandTransparency(
        source: Bitmap,
        rectX: Double, rectY: Double,
        rectW: Int, rectH: Int
    ): Bitmap {
        val imageWidth = source.width.toDouble()
        val imageHeight = source.height.toDouble()

        // Fast path: rect exactly covers source unchanged (CGImage+EFQRCode line 155-157)
        if (rectX == 0.0 && rectY == 0.0 && rectW == source.width && rectH == source.height) {
            return source
        }

        // Fast path: rect is entirely within source -> pure crop with CGRectIntegral (CGImage+EFQRCode lines 158-162)
        if (rectX >= 0.0 && rectY >= 0.0 &&
            (rectX + rectW) <= imageWidth && (rectY + rectH) <= imageHeight
        ) {
            return backend.crop(source, rectX, rectY, rectW.toDouble(), rectH.toDouble())
        }

        // General case: transparent canvas with offset draw (CGImage+EFQRCode lines 165-184)
        // EF drawRect: CGRect(x: rect.origin.x, y: rect.origin.y, width: imageWidth, height: imageHeight)
        // Single unavoidable Double -> Float conversion right at the Skia RectF boundary
        val dstRectF = RectF(
            rectX.toFloat(),
            rectY.toFloat(),
            (rectX + imageWidth).toFloat(),
            (rectY + imageHeight).toFloat()
        )
        return backend.drawInto(source, rectW, rectH, dstRectF)
    }

    internal fun clipAndExpandTransparency(
        source: Bitmap,
        rectX: Float, rectY: Float,
        rectW: Int, rectH: Int
    ): Bitmap = clipAndExpandTransparency(source, rectX.toDouble(), rectY.toDouble(), rectW, rectH)

    /**
     * Zero-interpolation pixel crop with CoreGraphics CGRectIntegral - equivalent to CGImage.cropping(to:).
     */
    internal fun cropBitmap(source: Bitmap, x: Double, y: Double, width: Double, height: Double): Bitmap {
        return backend.crop(source, x, y, width, height)
    }

    internal fun cropBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        return backend.crop(source, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }
}
