package com.veilframe.app.upscale.inference

data class TensorSize(
    val width: Int,
    val height: Int
) {
    val pixelCount: Int
        get() = width * height

    fun scaledBy(scale: Int): TensorSize = TensorSize(
        width = width * scale,
        height = height * scale
    )
}
