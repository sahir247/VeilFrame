package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Port of rembg BaseSession.normalize() from Cel-Android — scales pixels by max channel value,
 * then applies per-channel mean/std normalization in CHW (channels-first) layout.
 */
object ImagePreprocessor {

    fun prepareInput(
        source: Bitmap,
        model: RembgModel,
    ): FloatArray {
        val rgb = if (source.width == model.inputWidth && source.height == model.inputHeight) {
            source
        } else {
            Bitmap.createScaledBitmap(source, model.inputWidth, model.inputHeight, true)
        }
        val pixels = IntArray(model.inputWidth * model.inputHeight)
        rgb.getPixels(pixels, 0, model.inputWidth, 0, 0, model.inputWidth, model.inputHeight)
        if (rgb !== source) {
            rgb.recycle()
        }

        var maxVal = 1f
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            maxVal = max(maxVal, max(max(r, g), b).toFloat())
        }
        maxVal = max(maxVal, 1e-6f)

        val channels = 3
        val size = model.inputWidth * model.inputHeight
        val tensor = FloatArray(channels * size)

        for (y in 0 until model.inputHeight) {
            for (x in 0 until model.inputWidth) {
                val pixel = pixels[y * model.inputWidth + x]
                val r = ((pixel shr 16) and 0xFF) / maxVal
                val g = ((pixel shr 8) and 0xFF) / maxVal
                val b = (pixel and 0xFF) / maxVal
                val idx = y * model.inputWidth + x
                tensor[idx] = (r - model.mean[0]) / model.std[0]
                tensor[size + idx] = (g - model.mean[1]) / model.std[1]
                tensor[2 * size + idx] = (b - model.mean[2]) / model.std[2]
            }
        }
        return tensor
    }

    fun toFloatBuffer(tensor: FloatArray): FloatBuffer =
        FloatBuffer.wrap(tensor)
}
