package com.veilframe.app.pdf.operations

/** Integer-safe decode admission and aspect-preserving image PDF wrapper geometry. */
internal object OcrImageGeometry {
    fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        require(width > 0 && height > 0 && maxPixels > 0)
        var sample = 1
        while (true) {
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            if (w <= OcrDpiUtil.MAX_EDGE && h <= OcrDpiUtil.MAX_EDGE && w * h <= maxPixels) return sample
            check(sample <= Int.MAX_VALUE / 2) { "Image dimensions exceed OCR sampling budget" }
            sample *= 2
        }
    }

    fun requireDecodedDimensions(width: Int, height: Int, release: () -> Unit) {
        if (width <= 0 || height <= 0 || width > OcrDpiUtil.MAX_EDGE ||
            height > OcrDpiUtil.MAX_EDGE || width.toLong() * height > OcrDpiUtil.MAX_PIXELS) {
            release()
            throw java.io.IOException("Decoded image exceeds OCR raster budget")
        }
    }

    fun pdfPoints(width: Int, height: Int): Pair<Float, Float> {
        require(width > 0 && height > 0)
        val scale = minOf(72.0 / 200.0, 3000.0 / maxOf(width, height))
        return (width * scale).toFloat() to (height * scale).toFloat()
    }
}
