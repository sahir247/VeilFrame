package com.veilframe.app.upscale.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.TensorInfo
import kotlin.math.abs

data class ImageTensor(
    val name: String,
    val channels: Int,
    val isFloat16: Boolean,
    val height: Int?,
    val width: Int?,
    private val rawChannels: Long
) {
    fun outputChannels(keepSignedValue: Boolean): Int {
        val channelsValue = if (keepSignedValue) rawChannels else abs(rawChannels)
        return if (channelsValue == 1L) 1 else 3
    }

    companion object {
        fun Map<String, NodeInfo>.firstImageTensor(): ImageTensor? {
            fun TensorInfo.isImageFloatTensor(shape: LongArray): Boolean {
                val isFloatTensor = type == OnnxJavaType.FLOAT || type == OnnxJavaType.FLOAT16
                return isFloatTensor && shape.size == 4
            }

            fun Long.positiveDimension(): Int? = takeIf { it > 0L }?.toInt()

            for ((name, nodeInfo) in this) {
                val tensorInfo = nodeInfo.info as? TensorInfo ?: continue
                val shape = tensorInfo.shape
                if (!tensorInfo.isImageFloatTensor(shape)) continue

                return ImageTensor(
                    name = name,
                    channels = if (shape[1] == 1L) 1 else 3,
                    isFloat16 = tensorInfo.type == OnnxJavaType.FLOAT16,
                    height = shape[2].positiveDimension(),
                    width = shape[3].positiveDimension(),
                    rawChannels = shape[1]
                )
            }

            return null
        }
    }
}
