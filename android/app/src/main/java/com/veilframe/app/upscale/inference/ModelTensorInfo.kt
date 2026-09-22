package com.veilframe.app.upscale.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log

/**
 * Encapsulates dynamic ONNX graph tensor metadata discovered at session initialization.
 *
 * Implements dynamic tensor discovery:
 * - Identifies primary 4D image input tensor ([batch, channels, H, W])
 * - Detects FP16 vs FP32 precision
 * - Detects fixed vs dynamic spatial bounds
 * - Discovers image output tensor and channel cardinality
 * - Discovers auxiliary control inputs (e.g. [1, 1] strength tensor for FBCNN / style transfer)
 * - Validates convention candidate scale against actual ONNX graph dimensions
 */
data class ModelTensorInfo(
    val imageInputName: String,
    val inputType: OnnxJavaType,
    val inputChannels: Int,
    val isFloat16: Boolean,
    val fixedWidth: Int?,
    val fixedHeight: Int?,
    val imageOutputName: String,
    val outputType: OnnxJavaType,
    val outputChannels: Int,
    val auxiliaryInputs: Map<String, TensorInfo>,
    val candidateScale: Int,
    val verifiedGraphScale: Int?
) {
    val isDynamicSpatial: Boolean get() = fixedWidth == null || fixedHeight == null

    /**
     * Authoritative scale: prefers verified graph scale if present, falling back to candidate scale.
     */
    val effectiveScale: Int get() = verifiedGraphScale ?: candidateScale

    companion object {
        private const val TAG = "VeilFrame.ModelTensorInfo"

        fun inspect(session: OrtSession, modelName: String = ""): ModelTensorInfo {
            var imageInputName: String? = null
            var inputType: OnnxJavaType = OnnxJavaType.FLOAT
            var inputChannels = 3
            var isFloat16 = false
            var fixedW: Int? = null
            var fixedH: Int? = null
            val auxInputs = mutableMapOf<String, TensorInfo>()

            // 1. Iterate session inputs to find first 4D FLOAT/FLOAT16 image tensor
            for ((name, nodeInfo) in session.inputInfo) {
                val tensorInfo = nodeInfo.info as? TensorInfo ?: continue
                val shape = tensorInfo.shape
                val isFloat = (tensorInfo.type == OnnxJavaType.FLOAT || tensorInfo.type == OnnxJavaType.FLOAT16)

                if (isFloat && shape.size == 4 && imageInputName == null) {
                    imageInputName = name
                    inputType = tensorInfo.type
                    isFloat16 = (tensorInfo.type == OnnxJavaType.FLOAT16)
                    inputChannels = if (shape[1] == 1L) 1 else 3
                    fixedH = shape[2].takeIf { it > 0L }?.toInt()
                    fixedW = shape[3].takeIf { it > 0L }?.toInt()
                } else {
                    auxInputs[name] = tensorInfo
                }
            }

            requireNotNull(imageInputName) {
                "ONNX model $modelName does not expose a 4-dimensional FLOAT/FLOAT16 image input tensor."
            }

            // 2. Discover primary output tensor
            val outputEntry = session.outputInfo.entries.firstOrNull()
                ?: throw IllegalStateException("ONNX model $modelName exposes no output tensors.")
            val outputName = outputEntry.key
            val outputTensorInfo = outputEntry.value.info as? TensorInfo
                ?: throw IllegalStateException("ONNX output $outputName is not a TensorInfo.")

            val outType = outputTensorInfo.type
            val outShape = outputTensorInfo.shape
            val outChannels = if (outShape.size >= 2 && outShape[1] > 0L) {
                if (outShape[1] == 1L) 1 else 3
            } else {
                3
            }

            // 3. Extract candidate scale from convention
            val candidateScale = parseConventionScale(modelName)

            // 4. Validate graph-level scale if spatial dimensions are fixed in ONNX graph
            var verifiedScale: Int? = null
            if (fixedW != null && fixedH != null && outShape.size >= 4 && outShape[2] > 0L && outShape[3] > 0L) {
                val outH = outShape[2].toInt()
                val outW = outShape[3].toInt()
                val scaleX = outW / fixedW
                val scaleY = outH / fixedH
                if (scaleX == scaleY && scaleX > 0) {
                    verifiedScale = scaleX
                    if (candidateScale > 1 && candidateScale != verifiedScale) {
                        val mismatchMsg = "Model '$modelName' scale mismatch: filename indicates ${candidateScale}x, but ONNX graph defines ${verifiedScale}x (${fixedW}x${fixedH} -> ${outW}x${outH})."
                        Log.e(TAG, mismatchMsg)
                        throw IllegalArgumentException(mismatchMsg)
                    }
                }
            }

            Log.i(
                TAG,
                "Inspected $modelName: in=$imageInputName [${inputType}, c=$inputChannels, ${fixedW ?: "dyn"}x${fixedH ?: "dyn"}], out=$outputName [${outType}, c=$outChannels], candidateScale=${candidateScale}x, verifiedScale=${verifiedScale ?: "dyn"}x, auxInputs=${auxInputs.keys}"
            )

            return ModelTensorInfo(
                imageInputName = imageInputName,
                inputType = inputType,
                inputChannels = inputChannels,
                isFloat16 = isFloat16,
                fixedWidth = fixedW,
                fixedHeight = fixedH,
                imageOutputName = outputName,
                outputType = outType,
                outputChannels = outChannels,
                auxiliaryInputs = auxInputs,
                candidateScale = candidateScale,
                verifiedGraphScale = verifiedScale
            )
        }

        fun parseConventionScale(name: String): Int {
            val lower = name.lowercase()
            for (scale in 16 downTo 1) {
                if (lower.contains("x$scale") || lower.contains("${scale}x")) {
                    return scale
                }
            }
            return 1
        }
    }
}
