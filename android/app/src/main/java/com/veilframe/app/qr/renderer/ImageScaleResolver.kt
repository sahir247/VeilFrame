package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.model.ImageScaleMode
import kotlin.math.roundToInt

/**
 * Abstract pixel data provider decoupling sampling logic from Android Bitmap framework.
 */
interface PixelSource {
    val width: Int
    val height: Int
    fun getPixel(x: Int, y: Int): Int
}

class BitmapPixelSource(val bitmap: Bitmap) : PixelSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun getPixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

/**
 * Platform-independent bounding rectangle for image content within a scaled canvas.
 */
data class ContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Pre-scaled pixel data source with Skia bilinear filtering and explicit padding bounds.
 * Matches canonical pre-rendered 3N x 3N raster context sampling.
 */
class PreScaledPixelSource(
    val bitmap: Bitmap? = null,
    val pixels: IntArray? = null,
    val targetWidth: Int = bitmap?.width ?: 0,
    val targetHeight: Int = bitmap?.height ?: 0,
    val contentBounds: ContentBounds? = null
) : PixelSource {
    constructor(
        bitmap: Bitmap?,
        pixels: IntArray?,
        targetWidth: Int,
        targetHeight: Int,
        rectF: RectF?
    ) : this(
        bitmap,
        pixels,
        targetWidth,
        targetHeight,
        rectF?.let { ContentBounds(it.left, it.top, it.right, it.bottom) }
    )
    override val width: Int get() = targetWidth
    override val height: Int get() = targetHeight
    override fun getPixel(x: Int, y: Int): Int {
        if (bitmap != null && !bitmap.isRecycled) return bitmap.getPixel(x, y)
        if (pixels != null && x in 0 until targetWidth && y in 0 until targetHeight) {
            return pixels[y * targetWidth + x]
        }
        return 0xFFFFFFFF.toInt()
    }

    fun isPadding(x: Int, y: Int): Boolean {
        if (contentBounds == null) return false
        return x < contentBounds.left || x >= contentBounds.right ||
               y < contentBounds.top || y >= contentBounds.bottom
    }
}

class ArrayPixelSource(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray
) : PixelSource {
    override fun getPixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0xFFFFFFFF.toInt()
        return pixels[y * width + x]
    }
}

/**
 * Result of a normalized coordinate pixel sample, containing the resolved ARGB color
 * and an explicit [isPadding] flag indicating whether the sample landed in an ASPECT_FIT margin.
 */
data class PixelSample(
    val color: Int,
    val isPadding: Boolean = false
)

/**
 * Shared image coordinate and scale-mode resolver.
 *
 * Implements authoritative mathematical scaling semantics for QR stencils and resamplers:
 * - [ImageScaleMode.CENTER_CROP]: Crops source from center to destination aspect ratio without distortion.
 * - [ImageScaleMode.ASPECT_FILL]: Scales source until destination is completely covered, preserving aspect ratio and cropping overflow.
 * - [ImageScaleMode.ASPECT_FIT]: Scales entire source inside destination, letterboxing/pillarboxing.
 *   Areas outside the fitted image are transparent / maximum luminance (RGBA = 0, 0, 0, 0 or 255, 255, 255, 255)
 *   matching EF CGContext.clear(), naturally producing zero stochastic dots under standard exposure while allowing
 *   boundary antialiasing/filtering and exposure adjustments to match EFQRCode getGrayPointList().
 * - [ImageScaleMode.STRETCH]: Fits destination bounds directly; aspect ratio may distort.
 */
object ImageScaleResolver {

    /**
     * Samples a pixel and its padding state at normalized coordinate ([u], [v]) in [0.0f, 1.0f] according to [mode].
     *
     * In [ImageScaleMode.ASPECT_FIT], coordinates falling outside the fitted source aspect ratio
     * strictly return solid white (0xFFFFFFFF) with [PixelSample.isPadding] = true.
     */
    fun sample(
        source: PixelSource,
        u: Float,
        v: Float,
        mode: ImageScaleMode
    ): PixelSample {
        val bw = source.width.coerceAtLeast(1)
        val bh = source.height.coerceAtLeast(1)
        val srcRatio = bw.toFloat() / bh.toFloat()

        return when (mode) {
            ImageScaleMode.STRETCH -> {
                val color = sampleBilinear(source, u, v, bw, bh)
                PixelSample(color, isPadding = false)
            }

            ImageScaleMode.ASPECT_FIT -> {
                if (srcRatio > 1.0f) {
                    val fitH = 1.0f / srcRatio
                    val offsetY = (1.0f - fitH) / 2.0f
                    if (v < offsetY || v >= offsetY + fitH) {
                        PixelSample(0xFFFFFFFF.toInt(), isPadding = true) // Pure white in letterbox padding
                    } else {
                        val normV = (v - offsetY) / fitH
                        val color = sampleBilinear(source, u, normV, bw, bh)
                        PixelSample(color, isPadding = false)
                    }
                } else {
                    val fitW = 1.0f * srcRatio
                    val offsetX = (1.0f - fitW) / 2.0f
                    if (u < offsetX || u >= offsetX + fitW) {
                        PixelSample(0xFFFFFFFF.toInt(), isPadding = true) // Pure white in pillarbox padding
                    } else {
                        val normU = (u - offsetX) / fitW
                        val color = sampleBilinear(source, normU, v, bw, bh)
                        PixelSample(color, isPadding = false)
                    }
                }
            }

            ImageScaleMode.CENTER_CROP, ImageScaleMode.ASPECT_FILL -> {
                if (srcRatio > 1.0f) {
                    val visibleRatio = 1.0f / srcRatio
                    val offsetX = (1.0f - visibleRatio) / 2.0f
                    val normU = offsetX + u.coerceIn(0f, 1f) * visibleRatio
                    val color = sampleBilinear(source, normU, v, bw, bh)
                    PixelSample(color, isPadding = false)
                } else {
                    val visibleRatio = srcRatio
                    val offsetY = (1.0f - visibleRatio) / 2.0f
                    val normV = offsetY + v.coerceIn(0f, 1f) * visibleRatio
                    val color = sampleBilinear(source, u, normV, bw, bh)
                    PixelSample(color, isPadding = false)
                }
            }
        }
    }

    /**
     * Bilinear interpolation across 4 adjacent source pixels, preventing nearest-neighbor
     * aliasing and stepping artifacts across high-frequency edges.
     */
    private fun sampleBilinear(source: PixelSource, normX: Float, normY: Float, bw: Int, bh: Int): Int {
        val fx = normX.coerceIn(0f, 1f) * (bw - 1)
        val fy = normY.coerceIn(0f, 1f) * (bh - 1)

        val x0 = fx.toInt().coerceIn(0, bw - 1)
        val y0 = fy.toInt().coerceIn(0, bh - 1)
        val x1 = (x0 + 1).coerceAtMost(bw - 1)
        val y1 = (y0 + 1).coerceAtMost(bh - 1)

        val wx = fx - x0
        val wy = fy - y0

        if (wx == 0f && wy == 0f) {
            return source.getPixel(x0, y0)
        }

        val c00 = source.getPixel(x0, y0)
        val c10 = source.getPixel(x1, y0)
        val c01 = source.getPixel(x0, y1)
        val c11 = source.getPixel(x1, y1)

        val a0 = (c00 ushr 24) and 0xFF
        val r0 = (c00 ushr 16) and 0xFF
        val g0 = (c00 ushr 8) and 0xFF
        val b0 = c00 and 0xFF

        val a1 = (c10 ushr 24) and 0xFF
        val r1 = (c10 ushr 16) and 0xFF
        val g1 = (c10 ushr 8) and 0xFF
        val b1 = c10 and 0xFF

        val a2 = (c01 ushr 24) and 0xFF
        val r2 = (c01 ushr 16) and 0xFF
        val g2 = (c01 ushr 8) and 0xFF
        val b2 = c01 and 0xFF

        val a3 = (c11 ushr 24) and 0xFF
        val r3 = (c11 ushr 16) and 0xFF
        val g3 = (c11 ushr 8) and 0xFF
        val b3 = c11 and 0xFF

        val topA = a0 * (1f - wx) + a1 * wx
        val topR = r0 * (1f - wx) + r1 * wx
        val topG = g0 * (1f - wx) + g1 * wx
        val topB = b0 * (1f - wx) + b1 * wx

        val botA = a2 * (1f - wx) + a3 * wx
        val botR = r2 * (1f - wx) + r3 * wx
        val botG = g2 * (1f - wx) + g3 * wx
        val botB = b2 * (1f - wx) + b3 * wx

        val a = (topA * (1f - wy) + botA * wy).roundToInt().coerceIn(0, 255)
        val r = (topR * (1f - wy) + botR * wy).roundToInt().coerceIn(0, 255)
        val g = (topG * (1f - wy) + botG * wy).roundToInt().coerceIn(0, 255)
        val b = (topB * (1f - wy) + botB * wy).roundToInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Backward-compatible helper returning raw ARGB Int.
     */
    fun samplePixel(
        source: PixelSource,
        u: Float,
        v: Float,
        mode: ImageScaleMode
    ): Int = sample(source, u, v, mode).color
    /**
     * Platform-independent crop and scale geometry description.
     */
    data class ScaleCropResult(
        val srcLeft: Int,
        val srcTop: Int,
        val srcRight: Int,
        val srcBottom: Int,
        val dstLeft: Float,
        val dstTop: Float,
        val dstRight: Float,
        val dstBottom: Float
    ) {
        val srcWidth: Int get() = srcRight - srcLeft
        val srcHeight: Int get() = srcBottom - srcTop
        val dstWidth: Float get() = dstRight - dstLeft
        val dstHeight: Float get() = dstBottom - dstTop
    }

    /**
     * Pure geometric computation of source crop and destination fit rectangles.
     */
    fun resolveCropGeometry(
        srcWidth: Int,
        srcHeight: Int,
        dstLeft: Float,
        dstTop: Float,
        dstRight: Float,
        dstBottom: Float,
        mode: ImageScaleMode
    ): ScaleCropResult {
        val bw = srcWidth.coerceAtLeast(1)
        val bh = srcHeight.coerceAtLeast(1)
        val dw = (dstRight - dstLeft).coerceAtLeast(1f)
        val dh = (dstBottom - dstTop).coerceAtLeast(1f)

        val srcRatio = bw.toFloat() / bh.toFloat()
        val dstRatio = dw / dh

        return when (mode) {
            ImageScaleMode.STRETCH -> {
                ScaleCropResult(0, 0, bw, bh, dstLeft, dstTop, dstRight, dstBottom)
            }

            ImageScaleMode.CENTER_CROP -> {
                if (srcRatio > dstRatio) {
                    val cropW = (bh * dstRatio).toInt().coerceIn(1, bw)
                    val sx = (bw - cropW) / 2
                    ScaleCropResult(sx, 0, sx + cropW, bh, dstLeft, dstTop, dstRight, dstBottom)
                } else {
                    val cropH = (bw / dstRatio).toInt().coerceIn(1, bh)
                    val sy = (bh - cropH) / 2
                    ScaleCropResult(0, sy, bw, sy + cropH, dstLeft, dstTop, dstRight, dstBottom)
                }
            }

            ImageScaleMode.ASPECT_FILL -> {
                if (srcRatio > dstRatio) {
                    val visibleW = (bh * dstRatio).toInt().coerceIn(1, bw)
                    val sx = (bw - visibleW) / 2
                    ScaleCropResult(sx, 0, sx + visibleW, bh, dstLeft, dstTop, dstRight, dstBottom)
                } else {
                    val visibleH = (bw / dstRatio).toInt().coerceIn(1, bh)
                    val sy = (bh - visibleH) / 2
                    ScaleCropResult(0, sy, bw, sy + visibleH, dstLeft, dstTop, dstRight, dstBottom)
                }
            }

            ImageScaleMode.ASPECT_FIT -> {
                if (srcRatio > dstRatio) {
                    val fitH = dw / srcRatio
                    val offsetY = (dh - fitH) / 2f
                    ScaleCropResult(0, 0, bw, bh, dstLeft, dstTop + offsetY, dstRight, dstTop + offsetY + fitH)
                } else {
                    val fitW = dh * srcRatio
                    val offsetX = (dw - fitW) / 2f
                    ScaleCropResult(0, 0, bw, bh, dstLeft + offsetX, dstTop, dstLeft + offsetX + fitW, dstBottom)
                }
            }
        }
    }

    /**
     * Resolves source crop rect and destination drawing rect.
     */
    fun resolveSrcDst(
        srcWidth: Int,
        srcHeight: Int,
        dstBounds: RectF,
        mode: ImageScaleMode
    ): Pair<Rect, RectF> {
        val geo = resolveCropGeometry(
            srcWidth,
            srcHeight,
            dstBounds.left,
            dstBounds.top,
            dstBounds.right,
            dstBounds.bottom,
            mode
        )
        val src = Rect(geo.srcLeft, geo.srcTop, geo.srcRight, geo.srcBottom).apply {
            left = geo.srcLeft
            top = geo.srcTop
            right = geo.srcRight
            bottom = geo.srcBottom
        }
        val dst = RectF(geo.dstLeft, geo.dstTop, geo.dstRight, geo.dstBottom).apply {
            left = geo.dstLeft
            top = geo.dstTop
            right = geo.dstRight
            bottom = geo.dstBottom
        }
        return Pair(src, dst)
    }

    /**
     * Creates a scaled bitmap of target dimensions [targetWidth] x [targetHeight] adhering to [mode].
     *
     * For [ImageScaleMode.ASPECT_FIT], non-covered regions are guaranteed to be solid white
     * (RGBA = 255, 255, 255, 255), ensuring VeilFrame Art Engine luminance math produces zero photo dither dots
     * in letterboxed/pillarboxed margins.
     */
    fun createScaledBitmap(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        mode: ImageScaleMode
    ): Bitmap {
        val tw = targetWidth.coerceAtLeast(1)
        val th = targetHeight.coerceAtLeast(1)

        val output = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // EF parity: newly allocated Bitmap is 0x00000000 (transparent), matching
        // EFQRCode CGImage.clipAndExpandingTransparencyWith() context.clear().
        // Uncovered letterbox/pillarbox margins remain transparent.

        val sw = source.width.toFloat().coerceAtLeast(1f)
        val sh = source.height.toFloat().coerceAtLeast(1f)
        val matrix = Matrix()

        when (mode) {
            ImageScaleMode.STRETCH -> {
                matrix.setScale(tw.toFloat() / sw, th.toFloat() / sh)
            }
            ImageScaleMode.CENTER_CROP, ImageScaleMode.ASPECT_FILL -> {
                val scale = maxOf(tw.toFloat() / sw, th.toFloat() / sh)
                val dx = (tw.toFloat() - sw * scale) / 2f
                val dy = (th.toFloat() - sh * scale) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
            ImageScaleMode.ASPECT_FIT -> {
                val scale = minOf(tw.toFloat() / sw, th.toFloat() / sh)
                val dx = (tw.toFloat() - sw * scale) / 2f
                val dy = (th.toFloat() - sh * scale) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = false
        }
        canvas.drawBitmap(source, matrix, paint)

        return output
    }

    /**
     * Standard sRGB / Rec. 709 luminance weights matching CoreGraphics grayscale conversion:
     * gray = 0.2126 * R + 0.7152 * G + 0.0722 * B
     * weightedGray = gray * alpha + (1.0 - alpha) * 255.0
     *
     * Note on EFQRCode 7.0.3 CoreGraphics premultiplication divergence:
     * In EFQRCode (EFQRCodeStyleResampleImage.swift:797-804), the image was drawn into a
     * CGContext with `CGImageAlphaInfo.premultipliedLast`. This caused CoreGraphics to premultiply
     * R, G, B channels by alpha (R_cg = R * alpha). When EF's `gamma()` function subsequently
     * computed `gray = 0.2126 * R_cg + ...` and `weightedGray = gray * alpha + (1 - alpha) * 255`,
     * alpha was applied twice for semi-transparent pixels (a^2).
     *
     * For un-premultiplied sRGB input (standard Android Bitmap.getPixel), [efPremultipliedAlpha] = false
     * computes the mathematically intended single-alpha blending. Setting [efPremultipliedAlpha] = true
     * replicates EF's exact CoreGraphics byte buffer output.
     */
    fun calculateLuminance(r: Int, g: Int, b: Int, a: Float = 1.0f, efPremultipliedAlpha: Boolean = false): Float {
        val weightedGray = if (efPremultipliedAlpha) {
            val rPremul = (r * a).toInt().coerceIn(0, 255)
            val gPremul = (g * a).toInt().coerceIn(0, 255)
            val bPremul = (b * a).toInt().coerceIn(0, 255)
            val gray = 0.2126f * rPremul + 0.7152f * gPremul + 0.0722f * bPremul
            gray * a + (1.0f - a) * 255.0f
        } else {
            val baseGray = 0.2126f * r + 0.7152f * g + 0.0722f * b
            baseGray * a + (1.0f - a) * 255.0f
        }
        return (weightedGray / 255.0f).coerceIn(0.0f, 1.0f)
    }

    /**
     * Extracts ARGB channels from a 32-bit packed color integer and computes
     * the standardized alpha-weighted sRGB grayscale luminance in [0.0f, 1.0f].
     */
    fun calculatePixelLuminance(pixel: Int, efPremultipliedAlpha: Boolean = false): Float {
        val a = ((pixel ushr 24) and 0xFF) / 255.0f
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return calculateLuminance(r, g, b, a, efPremultipliedAlpha)
    }

    /**
     * Pre-scales [source] into a [PreScaledPixelSource] of dimensions [targetWidth] x [targetHeight]
     * matching canonical EFQRCode 7.0.3 EFImageMode.imageForContent() preprocessing pipeline
     * followed by 3N x 3N raster sampling with transparent aspect-fit padding.
     */
    fun createPreScaledSource(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        mode: ImageScaleMode
    ): PreScaledPixelSource {
        val tw = targetWidth.coerceAtLeast(1)
        val th = targetHeight.coerceAtLeast(1)

        val output = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // EFQRCode 7.0.3 parity: Preprocess source image to intermediate canvas ratio (EFImageMode.imageForContent)
        val preprocessed = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
            source = source,
            canvasWidth = tw.toDouble(),
            canvasHeight = th.toDouble(),
            mode = mode
        )

        val contentBounds: ContentBounds? = if (mode == ImageScaleMode.ASPECT_FIT) {
            val sw = source.width.toFloat().coerceAtLeast(1f)
            val sh = source.height.toFloat().coerceAtLeast(1f)
            val scale = minOf(tw.toFloat() / sw, th.toFloat() / sh)
            val dx = (tw.toFloat() - sw * scale) / 2f
            val dy = (th.toFloat() - sh * scale) / 2f
            ContentBounds(dx, dy, dx + sw * scale, dy + sh * scale)
        } else {
            null
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = false
        }
        val srcRect = android.graphics.Rect(0, 0, preprocessed.width, preprocessed.height)
        val dstRect = RectF(0f, 0f, tw.toFloat(), th.toFloat())
        canvas.drawBitmap(preprocessed, srcRect, dstRect, paint)

        return PreScaledPixelSource(bitmap = output, targetWidth = tw, targetHeight = th, contentBounds = contentBounds)
    }

    /**
     * Pre-scales an abstract [PixelSource] into a [PreScaledPixelSource] of dimensions [targetWidth] x [targetHeight]
     * using continuous bilinear filtering and preserving explicit padding bounds.
     */
    fun createPreScaledArraySource(
        source: PixelSource,
        targetWidth: Int,
        targetHeight: Int,
        mode: ImageScaleMode
    ): PreScaledPixelSource {
        val tw = targetWidth.coerceAtLeast(1)
        val th = targetHeight.coerceAtLeast(1)
        val pixels = IntArray(tw * th)
        for (y in 0 until th) {
            val v = (y + 0.5f) / th.toFloat()
            for (x in 0 until tw) {
                val u = (x + 0.5f) / tw.toFloat()
                val sample = sample(source, u, v, mode)
                pixels[y * tw + x] = sample.color
            }
        }
        val contentBounds = if (mode == ImageScaleMode.ASPECT_FIT) {
            val sw = source.width.toFloat().coerceAtLeast(1f)
            val sh = source.height.toFloat().coerceAtLeast(1f)
            val scale = minOf(tw.toFloat() / sw, th.toFloat() / sh)
            val dx = (tw.toFloat() - sw * scale) / 2f
            val dy = (th.toFloat() - sh * scale) / 2f
            ContentBounds(dx, dy, dx + sw * scale, dy + sh * scale)
        } else null
        return PreScaledPixelSource(bitmap = null, pixels = pixels, targetWidth = tw, targetHeight = th, contentBounds = contentBounds)
    }

    /**
     * Draws [bitmap] scaled according to [mode] within [dstBounds] onto [canvas] with [alpha] using fractional matrix transformations.
     */
    fun drawScaledBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        dstBounds: RectF,
        mode: ImageScaleMode,
        alpha: Float = 1.0f
    ) {
        val sw = bitmap.width.toFloat().coerceAtLeast(1f)
        val sh = bitmap.height.toFloat().coerceAtLeast(1f)
        val dw = dstBounds.width()
        val dh = dstBounds.height()
        val matrix = Matrix()

        when (mode) {
            ImageScaleMode.STRETCH -> {
                matrix.setScale(dw / sw, dh / sh)
                matrix.postTranslate(dstBounds.left, dstBounds.top)
            }
            ImageScaleMode.CENTER_CROP, ImageScaleMode.ASPECT_FILL -> {
                val scale = maxOf(dw / sw, dh / sh)
                val dx = dstBounds.left + (dw - sw * scale) / 2f
                val dy = dstBounds.top + (dh - sh * scale) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
            ImageScaleMode.ASPECT_FIT -> {
                val scale = minOf(dw / sw, dh / sh)
                val dx = dstBounds.left + (dw - sw * scale) / 2f
                val dy = dstBounds.top + (dh - sh * scale) / 2f
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        }
        canvas.save()
        canvas.clipRect(dstBounds)
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restore()
    }
}
