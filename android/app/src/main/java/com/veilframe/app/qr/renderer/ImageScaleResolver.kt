package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.veilframe.app.qr.model.ImageScaleMode

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
 * Pre-scaled pixel data source with Skia bilinear filtering and explicit padding bounds.
 * Matches canonical pre-rendered 3N x 3N raster context sampling.
 */
class PreScaledPixelSource(
    val bitmap: Bitmap? = null,
    val pixels: IntArray? = null,
    val targetWidth: Int = bitmap?.width ?: 0,
    val targetHeight: Int = bitmap?.height ?: 0,
    val contentBounds: RectF? = null
) : PixelSource {
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
 *   Areas outside the fitted image are strictly defined as solid white (RGBA = 255, 255, 255, 255)
 *   and flagged with `isPadding = true` so stochastic dither emission is unconditionally suppressed.
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

        val a = (topA * (1f - wy) + botA * wy).toInt().coerceIn(0, 255)
        val r = (topR * (1f - wy) + botR * wy).toInt().coerceIn(0, 255)
        val g = (topG * (1f - wy) + botG * wy).toInt().coerceIn(0, 255)
        val b = (topB * (1f - wy) + botB * wy).toInt().coerceIn(0, 255)

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
     * Resolves source crop rect and destination drawing rect.
     */
    fun resolveSrcDst(
        srcWidth: Int,
        srcHeight: Int,
        dstBounds: RectF,
        mode: ImageScaleMode
    ): Pair<Rect, RectF> {
        val bw = srcWidth.coerceAtLeast(1)
        val bh = srcHeight.coerceAtLeast(1)
        val dw = (dstBounds.right - dstBounds.left).coerceAtLeast(1f)
        val dh = (dstBounds.bottom - dstBounds.top).coerceAtLeast(1f)

        val srcRatio = bw.toFloat() / bh.toFloat()
        val dstRatio = dw / dh

        return when (mode) {
            ImageScaleMode.STRETCH -> {
                Pair(Rect(0, 0, bw, bh), RectF(dstBounds))
            }

            ImageScaleMode.CENTER_CROP -> {
                // Crop max rectangle from center of source having dstRatio
                val srcRect = if (srcRatio > dstRatio) {
                    val cropW = (bh * dstRatio).toInt().coerceIn(1, bw)
                    val sx = (bw - cropW) / 2
                    Rect(sx, 0, sx + cropW, bh)
                } else {
                    val cropH = (bw / dstRatio).toInt().coerceIn(1, bh)
                    val sy = (bh - cropH) / 2
                    Rect(0, sy, bw, sy + cropH)
                }
                Pair(srcRect, RectF(dstBounds))
            }

            ImageScaleMode.ASPECT_FILL -> {
                // Scale until destination is completely covered, crop overflow
                val srcRect = if (srcRatio > dstRatio) {
                    val visibleW = (bh * dstRatio).toInt().coerceIn(1, bw)
                    val sx = (bw - visibleW) / 2
                    Rect(sx, 0, sx + visibleW, bh)
                } else {
                    val visibleH = (bw / dstRatio).toInt().coerceIn(1, bh)
                    val sy = (bh - visibleH) / 2
                    Rect(0, sy, bw, sy + visibleH)
                }
                Pair(srcRect, RectF(dstBounds))
            }

            ImageScaleMode.ASPECT_FIT -> {
                // Fit entire source inside destination bounds, letterbox/pillarbox
                val finalDst = if (srcRatio > dstRatio) {
                    val fitH = dw / srcRatio
                    val offsetY = (dh - fitH) / 2f
                    RectF(dstBounds.left, dstBounds.top + offsetY, dstBounds.right, dstBounds.top + offsetY + fitH)
                } else {
                    val fitW = dh * srcRatio
                    val offsetX = (dw - fitW) / 2f
                    RectF(dstBounds.left + offsetX, dstBounds.top, dstBounds.left + offsetX + fitW, dstBounds.bottom)
                }
                Pair(Rect(0, 0, bw, bh), finalDst)
            }
        }
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

        val dstBounds = RectF(0f, 0f, tw.toFloat(), th.toFloat())
        val (srcRect, dstRect) = resolveSrcDst(source.width, source.height, dstBounds, mode)

        if (mode == ImageScaleMode.ASPECT_FIT) {
            // Fill padding with pure white
            canvas.drawColor(Color.WHITE)
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val dstIntRect = Rect(
            dstRect.left.toInt(),
            dstRect.top.toInt(),
            dstRect.right.toInt().coerceAtMost(tw),
            dstRect.bottom.toInt().coerceAtMost(th)
        )
        canvas.drawBitmap(source, srcRect, dstIntRect, paint)

        return output
    }

    /**
     * Pre-scales [source] into a [PreScaledPixelSource] of dimensions [targetWidth] x [targetHeight]
     * matching canonical 3N x 3N pre-scaled raster sampling.
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

        val dstBounds = RectF(0f, 0f, tw.toFloat(), th.toFloat())
        val (srcRect, dstRect) = resolveSrcDst(source.width, source.height, dstBounds, mode)

        if (mode == ImageScaleMode.ASPECT_FIT) {
            canvas.drawColor(Color.WHITE)
        }

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, srcRect, dstRect, paint)

        val contentBounds = if (mode == ImageScaleMode.ASPECT_FIT) dstRect else null
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
        val (_, dstRect) = resolveSrcDst(source.width, source.height, RectF(0f, 0f, tw.toFloat(), th.toFloat()), mode)
        val contentBounds = if (mode == ImageScaleMode.ASPECT_FIT) dstRect else null
        return PreScaledPixelSource(bitmap = null, pixels = pixels, targetWidth = tw, targetHeight = th, contentBounds = contentBounds)
    }

    /**
     * Draws [bitmap] scaled according to [mode] within [dstBounds] onto [canvas] with [alpha].
     */
    fun drawScaledBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        dstBounds: RectF,
        mode: ImageScaleMode,
        alpha: Float = 1.0f
    ) {
        val (srcRect, dstRect) = resolveSrcDst(bitmap.width, bitmap.height, dstBounds, mode)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        }
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}
