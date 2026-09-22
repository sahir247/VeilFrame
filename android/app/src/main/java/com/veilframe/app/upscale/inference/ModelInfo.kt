package com.veilframe.app.upscale.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.veilframe.app.upscale.inference.ImageTensor.Companion.firstImageTensor

class ModelInfo(
    val strength: Float = 65f,
    val overlap: Int = 16,
    chunkSize: Int = 512,
    disableChunking: Boolean = false,
    session: OrtSession,
    val modelName: String,
    explicitScale: Int? = null
) {
    val inputInfoMap: Map<String, NodeInfo> = session.inputInfo

    private val inputTensor = inputInfoMap.firstImageTensor()
        ?: error("ONNX session does not expose an image input tensor")

    val inputName: String = inputTensor.name
    val inputChannels: Int = inputTensor.channels
    val isScuNet = modelName.startsWith("scunet_")
    val outputChannels: Int = session.outputInfo
        .firstImageTensor()
        ?.outputChannels(keepSignedValue = isScuNet)
        ?: 3
    val isFp16: Boolean = inputTensor.isFloat16
    val expectedWidth: Int? = inputTensor.width
    val expectedHeight: Int? = inputTensor.height
    val isScuNetColor = modelName.startsWith("scunet_color")
    val isNonChunkable = disableChunking

    val supportsStrength: Boolean = inputInfoMap.any { (name, nodeInfo) ->
        name != inputName && (nodeInfo.info as? TensorInfo)?.shape?.withResolvedUnknowns()?.contentEquals(longArrayOf(1L, 1L)) == true
    }

    val minSpatialSize = spatialSizeMap.entries.find {
        modelName.contains(it.key, ignoreCase = true)
    }?.value ?: 256

    val scaleFactor: Int = explicitScale ?: scaleMap.entries.find {
        modelName.contains(it.key, ignoreCase = true)
    }?.value ?: 1

    val tileLimit: Int = run {
        if (isNonChunkable) return@run Int.MAX_VALUE

        val size = if (isScuNet) {
            minOf(chunkSize, 256)
        } else {
            chunkSize
        }

        if (expectedWidth != null && expectedHeight != null) {
            minOf(size, expectedWidth, expectedHeight)
        } else {
            size
        }
    }

    fun tensorSizeFor(source: TensorSize): TensorSize {
        val useMinimumSide = isScuNetColor || minSpatialSize > 256
        val width = expectedWidth?.takeIf { it > 0 } ?: run {
            val aligned = source.width.roundedUpTo(AiExtensions.MODEL_ALIGNMENT)
            if (useMinimumSide) maxOf(aligned, minSpatialSize) else aligned
        }
        val height = expectedHeight?.takeIf { it > 0 } ?: run {
            val aligned = source.height.roundedUpTo(AiExtensions.MODEL_ALIGNMENT)
            if (useMinimumSide) maxOf(aligned, minSpatialSize) else aligned
        }
        return TensorSize(width, height)
    }

    init {
        Log.d("ModelInfo", "Model chunk=$chunkSize, overlap=$overlap, scale=$scaleFactor")
        val inputType = if (isFp16) "FP16" else "FP32"
        val widthText = expectedWidth ?: "dynamic"
        val heightText = expectedHeight ?: "dynamic"
        val tensorText = "Tensor input=$inputType/$inputChannels channel(s), " +
                "output=$outputChannels channel(s), " +
                "size=${widthText}x$heightText"
        Log.d("ModelInfo", tensorText)
    }
}

private val scaleMap = buildMap {
    repeat(16) {
        val scale = it + 1
        put("x$scale", scale)
        put("${scale}x", scale)
    }
}

private val spatialSizeMap = mapOf(
    "nafnet" to 512
)
