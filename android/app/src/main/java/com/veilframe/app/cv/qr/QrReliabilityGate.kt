package com.veilframe.app.cv.qr

import org.opencv.core.Mat

/**
 * QrReliabilityGate — the QR release gate.
 *
 * "OpenCV decoded it" is NOT a PASS. A generated QR is judged against the full
 * [QrStressMatrix] using the SAME WeChatQRCode engine users will scan with, and
 * verdicts are graded, not binary:
 *
 *   PASS              every stress condition decodes on WeChatQRCode
 *   PASS_WITH_MARGIN  ≥90% on WeChatQRCode, at most one fallback to ML Kit
 *   WEAK              ≥60% overall coverage but unreliable on WeChatQRCode
 *   FAIL              coverage below 60%, or the pristine render itself fails
 *
 * WeChatQRCode is the authoritative engine; ML Kit results are recorded as
 * *fallback* coverage and can never upgrade a WEAK verdict to PASS.
 */
object QrReliabilityGate {

    enum class Level { PASS, PASS_WITH_MARGIN, WEAK, FAIL }

    data class ConditionOutcome(
        val condition: QrStressMatrix.StressCondition,
        val decodedBy: QrDecodeSource?,
        val payloadMatches: Boolean,
    ) {
        val wechatMatch: Boolean get() = decodedBy == QrDecodeSource.WECHAT && payloadMatches
        val fallbackMatch: Boolean get() = decodedBy == QrDecodeSource.ML_KIT && payloadMatches
    }

    data class Report(
        val level: Level,
        val conditionOutcomes: List<ConditionOutcome>,
        val wechatCoverage: String,
        val mlKitCoverage: String,
        val wechatMatchRate: Double,
        val totalMatchRate: Double,
        val releaseApproved: Boolean,
        val reasons: List<String>,
    )

    /** Decoding surface used by the gate — WeChat primary, ML Kit secondary. */
    fun interface DecoderProbe {
        fun decode(image: Mat): QrCvDetection?
    }

    /**
     * Probe wiring the intended stack: WeChatQRCode first, ML Kit fallback.
     * The diagnostic `cv::QRCodeDetector` is intentionally not part of the gate.
     */
    fun wechatThenMlKitProbe(mlKitDecode: (Mat) -> QrCvDetection?): DecoderProbe = DecoderProbe { image ->
        val wechat = WeChatQrEngine.get().decode(image).firstOrNull()
        if (wechat != null) wechat else mlKitDecode(image)
    }

    /** Runs the full stress matrix through [probe] and grades the results. */
    fun run(
        image: Mat,
        expectedPayload: String?,
        probe: DecoderProbe,
    ): Report {
        val outcomes = QrStressMatrix.ALL.map { condition ->
            val degraded = QrStressMatrix.apply(image, condition)
            try {
                val detection = probe.decode(degraded)
                ConditionOutcome(
                    condition = condition,
                    decodedBy = detection?.source,
                    payloadMatches = detection != null &&
                        (expectedPayload == null || detection.rawValue == expectedPayload),
                )
            } catch (_: Throwable) {
                ConditionOutcome(condition, null, false)
            } finally {
                degraded.release()
            }
        }
        return evaluate(outcomes, expectedPayload)
    }

    /** Pure grading policy — unit-tested on the JVM without OpenCV natives. */
    fun evaluate(outcomes: List<ConditionOutcome>, expectedPayload: String?): Report {
        require(outcomes.isNotEmpty()) { "no stress outcomes to evaluate" }
        val reasons = mutableListOf<String>()

        val wechatMatches = outcomes.count { it.wechatMatch }
        val fallbackMatches = outcomes.count { it.fallbackMatch }
        val totalMatches = wechatMatches + fallbackMatches
        val total = outcomes.size

        val wechatRate = wechatMatches.toDouble() / total
        val totalRate = totalMatches.toDouble() / total

        val original = outcomes.firstOrNull { it.condition == QrStressMatrix.StressCondition.ORIGINAL }
        val pristineFails = original != null && !original.wechatMatch && !original.fallbackMatch
        if (pristineFails) {
            reasons += "Pristine render failed to decode — release gate hard-fails."
        } else if (original != null && !original.wechatMatch) {
            reasons += "Pristine render decoded only via ML Kit fallback, not WeChatQRCode."
        }

        outcomes.filter { !it.wechatMatch && !it.fallbackMatch }.forEach {
            reasons += "Undecodable condition: ${it.condition.label}"
        }
        outcomes.filter { it.fallbackMatch && !it.wechatMatch }.forEach {
            reasons += "Condition ${it.condition.label} survived only via ML Kit fallback."
        }

        val level = when {
            pristineFails -> Level.FAIL
            wechatMatches == total -> Level.PASS
            wechatRate >= 0.9 && fallbackMatches <= 1 && totalRate >= 0.9 -> Level.PASS_WITH_MARGIN
            totalRate >= 0.6 -> Level.WEAK
            else -> Level.FAIL
        }

        return Report(
            level = level,
            conditionOutcomes = outcomes,
            wechatCoverage = "$wechatMatches/$total",
            mlKitCoverage = "$fallbackMatches/$total",
            wechatMatchRate = wechatRate,
            totalMatchRate = totalRate,
            releaseApproved = level == Level.PASS || level == Level.PASS_WITH_MARGIN,
            reasons = reasons,
        )
    }

    /** Renders the human-facing verdict block used by release tooling/tests. */
    fun format(report: Report): String = buildString {
        appendLine("QR RELIABILITY GATE")
        appendLine("──────────────────")
        report.conditionOutcomes.forEach { outcome ->
            val verdict = when {
                outcome.wechatMatch -> "PASS"
                outcome.fallbackMatch -> "PASS (ML Kit fallback)"
                else -> "FAIL"
            }
            appendLine("${outcome.condition.label.padEnd(18)} $verdict")
        }
        appendLine()
        appendLine("WeChat coverage    ${report.wechatCoverage}")
        appendLine("ML Kit coverage    ${report.mlKitCoverage}")
        appendLine("LEVEL              ${report.level}")
        appendLine("RELEASE            ${if (report.releaseApproved) "APPROVED" else "BLOCKED"}")
    }
}
