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
    val exp32 = (bits ushr 23) and 0xFF
    val mant32 = bits and 0x7FFFFF

    if (exp32 == 0xFF) {
        // NaN or Infinity
        return if (mant32 != 0) {
            // Quiet NaN with preserved sign
            (sign or 0x7E00 or (mant32 ushr 13).coerceAtLeast(1)).toShort()
        } else {
            (sign or 0x7C00).toShort()
        }
    }

    if (exp32 == 0 && mant32 == 0) {
        return sign.toShort()
    }

    val exp = exp32 - 127 + 15
    if (exp >= 31) {
        // Overflow to infinity
        return (sign or 0x7C00).toShort()
    }

    if (exp <= 0) {
        // Subnormal in FP16 or zero
        val shift = 1 - exp
        val totalShift = shift + 13
        if (totalShift > 25) {
            return sign.toShort()
        }
        val fullMant = mant32 or 0x800000
        val half = 1 shl (totalShift - 1)
        val roundBit = (fullMant and half) != 0
        val stickyBits = (fullMant and (half - 1)) != 0
        val lsb = (fullMant and (half shl 1)) != 0
        val add = if (roundBit && (stickyBits || lsb)) 1 else 0
        val m10 = (fullMant ushr totalShift) + add
        return (sign or m10).toShort()
    }

    // Normal FP16 number: round-to-nearest-even on dropped 13 bits
    val roundBit = (mant32 and 0x1000) != 0
    val stickyBits = (mant32 and 0x0FFF) != 0
    val lsb = (mant32 and 0x2000) != 0
    val add = if (roundBit && (stickyBits || lsb)) 1 else 0
    var m10 = (mant32 ushr 13) + add
    var e = exp
    if (m10 == 0x400) {
        m10 = 0
        e++
        if (e >= 31) {
            return (sign or 0x7C00).toShort()
        }
    }
    return (sign or (e shl 10) or m10).toShort()
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

fun smoothStep(
    position: Int,
    length: Int
): Float {
    val x = (position.toFloat() / (length - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

fun mixColors(
    from: Int,
    to: Int,
    amount: Float
): Int {
    val keep = 1f - amount
    val aFrom = (from ushr 24) and 0xff
    val rFrom = (from ushr 16) and 0xff
    val gFrom = (from ushr 8) and 0xff
    val bFrom = from and 0xff

    val aTo = (to ushr 24) and 0xff
    val rTo = (to ushr 16) and 0xff
    val gTo = (to ushr 8) and 0xff
    val bTo = to and 0xff

    val a = (keep * aFrom + amount * aTo).toInt().coerceIn(0, 255)
    val r = (keep * rFrom + amount * rTo).toInt().coerceIn(0, 255)
    val g = (keep * gFrom + amount * gTo).toInt().coerceIn(0, 255)
    val b = (keep * bFrom + amount * bTo).toInt().coerceIn(0, 255)

    return (a shl 24) or (r shl 16) or (g shl 8) or b
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

    val storage = ByteBuffer
        .allocateDirect(data.size * Short.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    val shortBuffer = storage.asShortBuffer()
    for (i in data.indices) {
        shortBuffer.put(floatToFloat16(data[i]))
    }
    storage.rewind()
    return OnnxTensor.createTensor(env, storage, shape, OnnxJavaType.FLOAT16)
}

private val UNIVERSAL_RECOGNIZED_STRENGTH_NAMES = setOf(
    "strength", "qf", "q_factor", "qfactor", "quality", "cond", "condition"
)

private val FBCNN_RECOGNIZED_NAMES = setOf(
    "qf", "qf_input", "qfactor", "q_factor", "quality", "strength", "cond"
)

private val SCUNET_RECOGNIZED_NAMES = setOf(
    "strength", "noise_level", "noise", "cond"
)

fun isRecognizedStrengthInput(
    name: String,
    modelName: String,
    tensorType: OnnxJavaType? = null,
    shape: LongArray? = null
): Boolean {
    val lower = name.lowercase()

    // 1. Validate tensor type if provided: must be 32-bit or 16-bit float
    if (tensorType != null && tensorType != OnnxJavaType.FLOAT && tensorType != OnnxJavaType.FLOAT16) {
        return false
    }

    // 2. Validate shape if provided: must be scalar [1, 1]
    if (shape != null && !shape.contentEquals(longArrayOf(1L, 1L))) {
        return false
    }

    // 3. Exact metadata check against model family
    return when {
        modelName.startsWith("fbcnn_", ignoreCase = true) -> {
            FBCNN_RECOGNIZED_NAMES.contains(lower)
        }
        modelName.startsWith("scunet_", ignoreCase = true) -> {
            SCUNET_RECOGNIZED_NAMES.contains(lower)
        }
        else -> {
            UNIVERSAL_RECOGNIZED_STRENGTH_NAMES.contains(lower)
        }
    }
}

fun appendControlInputs(
    destination: MutableMap<String, OnnxTensor>,
    info: ModelInfo
) {
    val env = OrtEnvironment.getEnvironment()

    info.inputInfoMap.forEach { (name, nodeInfo) ->
        if (name == info.inputName) return@forEach

        val tensorInfo = nodeInfo.info as? TensorInfo ?: return@forEach
        val shape = tensorInfo.shape.withResolvedUnknowns()

        if (shape.contentEquals(longArrayOf(1L, 1L))) {
            if (isRecognizedStrengthInput(name, info.modelName, tensorInfo.type, shape)) {
                destination[name] = createStrengthTensor(
                    env = env,
                    strength = info.strength / 100f,
                    shape = shape,
                    type = tensorInfo.type
                )
            } else {
                Log.w(
                    AiExtensions.LOG_TAG,
                    "Model '${info.modelName}' contains unrecognized scalar input '$name' [1,1] (${tensorInfo.type}). " +
                        "Skipping strength injection to prevent tensor semantic corruption."
                )
            }
        } else {
            Log.w(
                AiExtensions.LOG_TAG,
                "Model '${info.modelName}' contains non-scalar extra input '$name' with shape ${shape.contentToString()}."
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
            // Audit fix: cancellation check every 4096 px, not every pixel
            // (millions of coroutine checks per tile were pure overhead).
            if ((index and 4095) == 0) ensureActive()
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
