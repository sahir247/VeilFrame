package com.veilframe.app.upscale.inference

import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

suspend fun extractOutputArray(
    outputValue: Any,
    channels: Int,
    h: Int,
    w: Int
): Pair<FloatArray, Int> = coroutineScope {
    ensureActive()
    Log.d(AiExtensions.LOG_TAG, "ONNX output payload: ${outputValue.javaClass.name}")

    when (outputValue) {
        is FloatArray -> outputValue to channels
        is ShortArray -> FloatArray(outputValue.size) { index ->
            float16ToFloat(outputValue[index])
        } to channels

        is Array<*> -> readNestedOutput(
            outputValue = outputValue,
            channels = channels,
            height = h,
            width = w
        )

        else -> throw IllegalArgumentException(
            "Unsupported ONNX output: ${outputValue.javaClass}"
        )
    }
}

@Suppress("UNCHECKED_CAST")
private suspend fun readNestedOutput(
    outputValue: Array<*>,
    channels: Int,
    height: Int,
    width: Int
): Pair<FloatArray, Int> {
    val firstError = runCatching {
        copyNestedFloatOutput(
            tensor = outputValue as Array<Array<Array<FloatArray>>>,
            requestedChannels = channels,
            height = height,
            width = width
        )
    }

    if (firstError.isSuccess) {
        return firstError.getOrThrow()
    }

    return runCatching {
        copyNestedHalfOutput(
            tensor = outputValue as Array<Array<Array<ShortArray>>>,
            requestedChannels = channels,
            height = height,
            width = width
        )
    }.getOrElse { secondError ->
        throw IllegalStateException(
            "Unable to unpack ONNX result: " +
                    "${firstError.exceptionOrNull()?.message}, ${secondError.message}"
        )
    }
}

private suspend fun copyNestedFloatOutput(
    tensor: Array<Array<Array<FloatArray>>>,
    requestedChannels: Int,
    height: Int,
    width: Int
): Pair<FloatArray, Int> = coroutineScope {
    val batchCount = tensor.size
    val actualChannels = tensor[0].size
    val actualHeight = tensor[0][0].size
    val actualWidth = tensor[0][0][0].size
    val channelsToCopy = maxOf(requestedChannels, actualChannels)

    Log.d(AiExtensions.LOG_TAG, "Nested FP32 output shape: [$batchCount, $actualChannels, $actualHeight, $actualWidth], reading $channelsToCopy channel(s)")

    val flattened = FloatArray(channelsToCopy * height * width)
    for (channel in 0 until channelsToCopy) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                ensureActive()
                flattened[channel * height * width + y * width + x] = tensor[0][channel][y][x]
            }
        }
    }

    flattened to channelsToCopy
}

private suspend fun copyNestedHalfOutput(
    tensor: Array<Array<Array<ShortArray>>>,
    requestedChannels: Int,
    height: Int,
    width: Int
): Pair<FloatArray, Int> = coroutineScope {
    val batchCount = tensor.size
    val actualChannels = tensor[0].size
    val actualHeight = tensor[0][0].size
    val actualWidth = tensor[0][0][0].size
    val channelsToCopy = maxOf(requestedChannels, actualChannels)

    Log.d(AiExtensions.LOG_TAG, "Nested FP16 output shape: [$batchCount, $actualChannels, $actualHeight, $actualWidth], reading $channelsToCopy channel(s)")

    val flattened = FloatArray(channelsToCopy * height * width)
    for (channel in 0 until channelsToCopy) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                ensureActive()
                flattened[channel * height * width + y * width + x] =
                    float16ToFloat(tensor[0][channel][y][x])
            }
        }
    }

    flattened to channelsToCopy
}
