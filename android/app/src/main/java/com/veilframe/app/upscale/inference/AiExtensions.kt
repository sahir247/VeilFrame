package com.veilframe.app.upscale.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.lang.Float.floatToIntBits
import java.lang.Float.intBitsToFloat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object AiExtensions {
    const val LOG_TAG = "AiProcessor"
    const val MODEL_ALIGNMENT = 8
    const val OPAQUE = 255
}

fun LongArray.withResolvedUnknowns(): LongArray = clone().also { shape ->
    for (index in shape.indices) {
        if (shape[index] == -1L) shape[index] = 1L
    }
}

fun Int.roundedUpTo(block: Int): Int = ((this + block - 1) / block) * block

fun clamp255(value: Float): Int = value.toInt().coerceIn(0, AiExtensions.OPAQUE)

fun floatToFloat16(value: Float): Short {
    val bits = floatToIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
    var mantissa = bits and 0x7FFFFF

    if (exponent <= 0) {
        if (exponent < -10) {
            return sign.toShort()
        }
        mantissa = mantissa or 0x800000
        mantissa = mantissa shr (1 - exponent)
        return (sign or (mantissa shr 13)).toShort()
    } else if (exponent >= 0x1F) {
        return (sign or 0x7C00).toShort()
    }

    return (sign or (exponent shl 10) or (mantissa shr 13)).toShort()
}

fun float16ToFloat(fp16: Short): Float {
    val bits = fp16.toInt() and 0xFFFF
    val sign = (bits and 0x8000) shl 16
    val exponent = (bits and 0x7C00) ushr 10
    val mantissa = bits and 0x3FF
    if (exponent == 0) {
        if (mantissa == 0) {
            return intBitsToFloat(sign)
        }
        var normalizedExponent = -14
        var normalizedMantissa = mantissa
        while ((normalizedMantissa and 0x400) == 0) {
            normalizedMantissa = normalizedMantissa shl 1
            normalizedExponent--
        }
        normalizedMantissa = normalizedMantissa and 0x3FF
        return intBitsToFloat(
            sign or ((normalizedExponent + 127) shl 23) or (normalizedMantissa shl 13)
        )
    } else if (exponent == 0x1F) {
        return intBitsToFloat(sign or 0x7F800000 or (mantissa shl 13))
    }

    return intBitsToFloat(sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
}

fun createStrengthTensor(
    env: OrtEnvironment,
    strength: Float,
    shape: LongArray,
    type: OnnxJavaType
): OnnxTensor {
    if (type == OnnxJavaType.FLOAT16) {
        val storage = ByteBuffer
            .allocateDirect(Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        storage.asShortBuffer().put(floatToFloat16(strength))
        storage.rewind()
        return OnnxTensor.createTensor(env, storage, shape, OnnxJavaType.FLOAT16)
    }

    return OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(floatArrayOf(strength)),
        shape
    )
}

fun createInputTensor(
    data: FloatArray,
    shape: LongArray,
    fp16: Boolean
): OnnxTensor {
    val env = OrtEnvironment.getEnvironment()

    if (!fp16) {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    val halfPrecision = ShortArray(data.size) { index ->
        floatToFloat16(data[index])
    }
    val storage = ByteBuffer
        .allocateDirect(halfPrecision.size * Short.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    storage.asShortBuffer().put(halfPrecision)
    storage.rewind()
    return OnnxTensor.createTensor(env, storage, shape, OnnxJavaType.FLOAT16)
}

fun appendControlInputs(
    destination: MutableMap<String, OnnxTensor>,
    info: ModelInfo
) {
    val env = OrtEnvironment.getEnvironment()

    info.inputInfoMap.forEach { (name, nodeInfo) ->
        if (name == info.inputName) return@forEach

        val tensorInfo = nodeInfo.info as? TensorInfo ?: return@forEach
        if (tensorInfo.type != OnnxJavaType.FLOAT && tensorInfo.type != OnnxJavaType.FLOAT16) {
            return@forEach
        }

        val shape = tensorInfo.shape.withResolvedUnknowns()
        if (shape.contentEquals(longArrayOf(1L, 1L))) {
            destination[name] = createStrengthTensor(
                env = env,
                strength = info.strength / 100f,
                shape = shape,
                type = tensorInfo.type
            )
        }
    }
}

suspend fun Bitmap.fitToTensorSize(
    target: TensorSize
): PreparedBitmap = coroutineScope {
    if (width == target.width && height == target.height) {
        return@coroutineScope PreparedBitmap(
            bitmap = this@fitToTensorSize,
            recycleAfterUse = false
        )
    }

    Log.d(AiExtensions.LOG_TAG, "Extending bitmap edges from ${width}x$height to ${target.width}x${target.height}")
    val padded = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(padded)
    canvas.drawBitmap(this@fitToTensorSize, 0f, 0f, null)

    if (target.width > width) {
        val edge = Bitmap.createBitmap(this@fitToTensorSize, width - 1, 0, 1, height)
        try {
            for (x in width until target.width) {
                ensureActive()
                canvas.drawBitmap(edge, x.toFloat(), 0f, null)
            }
        } finally {
            edge.recycle()
        }
    }

    if (target.height > height) {
        val edge = Bitmap.createBitmap(padded, 0, height - 1, target.width, 1)
        try {
            for (y in height until target.height) {
                ensureActive()
                canvas.drawBitmap(edge, 0f, y.toFloat(), null)
            }
        } finally {
            edge.recycle()
        }
    }

    PreparedBitmap(
        bitmap = padded,
        recycleAfterUse = true
    )
}

suspend fun Bitmap.readModelInput(
    channels: Int,
    alpha: FloatArray?
): FloatArray = coroutineScope {
    val size = TensorSize(width, height)
    val pixels = IntArray(size.pixelCount)
    getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)

    FloatArray(channels * size.pixelCount).also { output ->
        for (index in pixels.indices) {
            ensureActive()
            val color = pixels[index]
            if (channels == 1) {
                output[index] = (
                    Color.red(color) + Color.green(color) + Color.blue(color)
                ) / 3f / 255f
            } else {
                output[index] = Color.red(color) / 255f
                output[size.pixelCount + index] = Color.green(color) / 255f
                output[size.pixelCount * 2 + index] = Color.blue(color) / 255f
            }

            alpha?.set(index, Color.alpha(color) / 255f)
        }
    }
}
