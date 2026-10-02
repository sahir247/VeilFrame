package com.veilframe.app.qr.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.model.ImageScaleMode

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
        canvasWidth: Float,
        canvasHeight: Float,
        mode: ImageScaleMode
    ): Bitmap {
        val imageWidth = source.width.toFloat()
        val imageHeight = source.height.toFloat()

        if (imageWidth <= 0f || imageHeight <= 0f || canvasWidth <= 0f || canvasHeight <= 0f) {
            return source
        }

        // EF early-exit: if ratio already matches, return source unchanged.
        // Exact floating-point comparison mirrors EFImageMode.swift line 127.
        if (imageWidth / imageHeight == canvasWidth / canvasHeight) {
            return source
        }

        val widthRatio = imageWidth / canvasWidth
        val heightRatio = imageHeight / canvasHeight

        return when (mode) {
            // scaleToFill (EFImageMode.swift:132-140)
            ImageScaleMode.STRETCH -> {
                val (newWidthF, newHeightF) = scaleToFillSizeF(
                    imageWidth, imageHeight, canvasWidth, canvasHeight, widthRatio, heightRatio
                )
                resizeBitmap(source, newWidthF.toInt(), newHeightF.toInt())
            }

            // scaleAspectFit (EFImageMode.swift:141-154)
            // Computes floating-point newSize and origin, then truncates destination canvas dimensions.
            ImageScaleMode.ASPECT_FIT -> {
                val (newWidthF, newHeightF) = scaleAspectFitSizeF(
                    imageWidth, imageHeight, canvasWidth, canvasHeight, widthRatio, heightRatio
                )
                val originX = -(imageWidth - newWidthF) / 2.0
                val originY = -(imageHeight - newHeightF) / 2.0
                clipAndExpandTransparency(
                    source = source,
                    rectX = originX.toFloat(),
                    rectY = originY.toFloat(),
                    rectW = newWidthF.toInt(),
                    rectH = newHeightF.toInt()
                )
            }

            // scaleAspectFill (EFImageMode.swift:155-168)
            // Computes floating-point newSize and origin, then slices source within bounds.
            ImageScaleMode.ASPECT_FILL, ImageScaleMode.CENTER_CROP -> {
                val (newWidthF, newHeightF) = scaleAspectFillSizeF(
                    imageWidth, imageHeight, canvasWidth, canvasHeight, widthRatio, heightRatio
                )
                val originX = -(imageWidth - newWidthF) / 2.0
                val originY = -(imageHeight - newHeightF) / 2.0
                // rect is wholly inside source bounds -> zero-interpolation crop
                cropBitmap(
                    source = source,
                    x = (-originX).toInt(),
                    y = (-originY).toInt(),
                    width = newWidthF.toInt(),
                    height = newHeightF.toInt()
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Intermediate size computations - exact translations of EFImageMode.swift
    // -------------------------------------------------------------------------

    /**
     * EF scaleToFill newSize as floating-point CGSize (EFImageMode.swift:133-139).
     */
    internal fun scaleToFillSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        return if (widthRatio > heightRatio) {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        } else {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        }
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
        val (w, h) = scaleToFillSizeF(imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio)
        return Pair(w.toInt(), h.toInt())  // Swift: Int(newSize.width) - truncation toward zero
    }

    /**
     * EF scaleAspectFit newSize as floating-point CGSize (EFImageMode.swift:142-148).
     */
    internal fun scaleAspectFitSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        return if (widthRatio > heightRatio) {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        } else {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        }
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
        val (w, h) = scaleAspectFitSizeF(imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio)
        return Pair(w.toInt(), h.toInt())
    }

    /**
     * EF scaleAspectFill newSize as floating-point CGSize (EFImageMode.swift:156-162).
     */
    internal fun scaleAspectFillSizeF(
        imageWidth: Float, imageHeight: Float,
        canvasW: Float, canvasH: Float,
        widthRatio: Float, heightRatio: Float
    ): Pair<Float, Float> {
        return if (widthRatio < heightRatio) {
            Pair(imageWidth, imageWidth / canvasW * canvasH)
        } else {
            Pair(imageHeight / canvasH * canvasW, imageHeight)
        }
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
        val (w, h) = scaleAspectFillSizeF(imageWidth, imageHeight, canvasW, canvasH, widthRatio, heightRatio)
        return Pair(w.toInt(), h.toInt())
    }

    // -------------------------------------------------------------------------
    // Pixel operations - Android equivalents of CoreGraphics operations
    // -------------------------------------------------------------------------

    /**
     * Equivalent to CGImage.resize(to:) (CGImage+EFQRCode.swift:197-218).
     *
     * Creates a new ARGB_8888 Bitmap at (newWidth x newHeight) and draws the source
     * into the full rectangle using a bilinear-filtered Paint.
     */
    internal fun resizeBitmap(source: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        if (newWidth == source.width && newHeight == source.height) return source
        val w = newWidth.coerceAtLeast(1)
        val h = newHeight.coerceAtLeast(1)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        return dst
    }

    /**
     * Equivalent to CGImage.clipAndExpandingTransparencyWith(rect:) (CGImage+EFQRCode.swift:151-185).
     *
     * When rectX/rectY are negative (letterbox/pillarbox), the resulting canvas is
     * (rectW x rectH) with the source drawn at offset (-rectX, -rectY), and all
     * non-covered pixels remain fully transparent (RGBA 0,0,0,0) matching context.clear().
     *
     * When the rect is entirely within source bounds, falls through to [cropBitmap].
     */
    internal fun clipAndExpandTransparency(
        source: Bitmap,
        rectX: Float, rectY: Float,
        rectW: Int, rectH: Int
    ): Bitmap {
        val imageWidth = source.width
        val imageHeight = source.height

        // Fast path: rect exactly covers source unchanged (CGImage+EFQRCode line 155-157)
        if (rectX == 0f && rectY == 0f && rectW == imageWidth && rectH == imageHeight) {
            return source
        }

        // Fast path: rect is entirely within source -> pure crop (CGImage+EFQRCode lines 158-162)
        if (rectX >= 0f && rectY >= 0f &&
            (rectX + rectW) <= imageWidth && (rectY + rectH) <= imageHeight
        ) {
            return cropBitmap(source, rectX.toInt(), rectY.toInt(), rectW, rectH)
        }

        // General case: transparent canvas with offset draw (CGImage+EFQRCode lines 165-184)
        val w = rectW.coerceAtLeast(1)
        val h = rectH.coerceAtLeast(1)
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // createBitmap zeroes pixels to 0x00000000 = fully transparent, matching context.clear().

        val canvas = Canvas(dst)
        // EF drawRect: CGRect(x: rect.origin.x, y: rect.origin.y, width: imageWidth, height: imageHeight)
        // In destination space: source drawn at (-rectX, -rectY) with its natural pixel dimensions.
        val dstLeft = -rectX
        val dstTop = -rectY
        val srcRect = Rect(0, 0, imageWidth, imageHeight)
        val dstRectF = RectF(dstLeft, dstTop, dstLeft + imageWidth, dstTop + imageHeight)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, srcRect, dstRectF, paint)
        return dst
    }

    /**
     * Zero-interpolation pixel crop - equivalent to CGImage.cropping(to:).
     *
     * Uses Bitmap.createBitmap(source, x, y, width, height) which performs an exact
     * pixel-copy without any scaling or interpolation, matching CoreGraphics cropping().
     *
     * Coordinates are clamped to source bounds to prevent crashes on integer truncation edge cases.
     */
    internal fun cropBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val safeX = x.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val safeW = width.coerceIn(1, (source.width - safeX).coerceAtLeast(1))
        val safeH = height.coerceIn(1, (source.height - safeY).coerceAtLeast(1))
        return Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
    }
}
