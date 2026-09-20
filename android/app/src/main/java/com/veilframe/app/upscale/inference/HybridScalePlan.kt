package com.veilframe.app.upscale.inference

/**
 * Stage type in a hybrid super-resolution pipeline.
 */
enum class ScaleStageType {
    AI_INFERENCE,
    ALGORITHMIC_REFINEMENT
}

/**
 * Concrete processing stage executing either neural upscaling or mathematical refinement.
 */
data class ScaleStage(
    val type: ScaleStageType,
    val scale: Int
)

/**
 * Explicit mathematical contract for hybrid neural upscaling and algorithmic refinement.
 * Enforces that targetScale is an exact multiple of nativeScale, avoiding arithmetic rounding ambiguity.
 *
 * Pipeline contracts:
 * - 2× model: 2× AI → 2× refinement → 4× final (or 2× AI → 2× AI → 2× refinement → 8× final)
 * - 4× model: 4× AI → 2× refinement → 8× final
 * Note: Two sequential 4× passes would yield 16× (4 × 4 = 16), not 8×.
 */
data class HybridScalePlan(
    val stages: List<ScaleStage>,
    val finalScale: Int
) {
    val aiScale: Int
        get() = stages.filter { it.type == ScaleStageType.AI_INFERENCE }.fold(1) { acc, s -> acc * s.scale }

    val refinementScale: Int
        get() = stages.filter { it.type == ScaleStageType.ALGORITHMIC_REFINEMENT }.fold(1) { acc, s -> acc * s.scale }

    val requiresRefinement: Boolean
        get() = refinementScale > 1

    constructor(aiScale: Int, refinementScale: Int, finalScale: Int) : this(
        stages = buildList {
            add(ScaleStage(ScaleStageType.AI_INFERENCE, aiScale))
            if (refinementScale > 1) {
                add(ScaleStage(ScaleStageType.ALGORITHMIC_REFINEMENT, refinementScale))
            }
        },
        finalScale = finalScale
    )

    companion object {
        fun create(targetScale: Int, nativeScale: Int): HybridScalePlan {
            require(nativeScale > 0) { "Native scale must be positive: $nativeScale" }
            require(targetScale > 0) { "Target scale must be positive: $targetScale" }
            require(targetScale == nativeScale || targetScale % nativeScale == 0) {
                "Target scale ($targetScale×) must be an exact multiple of model native scale ($nativeScale×)"
            }

            val stages = mutableListOf<ScaleStage>()
            // Primary AI neural stage
            stages.add(ScaleStage(ScaleStageType.AI_INFERENCE, nativeScale))

            // Algorithmic refinement stage if target exceeds native model scale
            val extra = targetScale / nativeScale
            if (extra > 1) {
                stages.add(ScaleStage(ScaleStageType.ALGORITHMIC_REFINEMENT, extra))
            }

            return HybridScalePlan(
                stages = stages,
                finalScale = targetScale
            )
        }

        /**
         * Multi-pass neural scaling pipeline.
         * Example: 8× from 2× model: 2× AI → 2× AI → 2× Refinement = 8× (2 × 2 × 2 = 8).
         * Example: 8× from 4× model: 4× AI → 2× Refinement = 8× (4 × 2 = 8).
         */
        fun createMultiPass(targetScale: Int, nativeScale: Int, neuralPasses: Int = 1): HybridScalePlan {
            require(nativeScale > 0) { "Native scale must be positive: $nativeScale" }
            require(targetScale > 0) { "Target scale must be positive: $targetScale" }

            val stages = mutableListOf<ScaleStage>()
            var currentScale = 1
            for (i in 0 until neuralPasses) {
                if (currentScale * nativeScale <= targetScale) {
                    stages.add(ScaleStage(ScaleStageType.AI_INFERENCE, nativeScale))
                    currentScale *= nativeScale
                } else {
                    break
                }
            }

            if (targetScale > currentScale) {
                require(targetScale % currentScale == 0) {
                    "Target scale ($targetScale×) must be divisible by accumulated AI scale ($currentScale×)"
                }
                stages.add(ScaleStage(ScaleStageType.ALGORITHMIC_REFINEMENT, targetScale / currentScale))
            }

            return HybridScalePlan(
                stages = stages,
                finalScale = targetScale
            )
        }
    }
}
