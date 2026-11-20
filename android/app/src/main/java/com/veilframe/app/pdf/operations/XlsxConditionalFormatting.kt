package com.veilframe.app.pdf.operations

import java.math.BigDecimal

internal object XlsxConditionalFormatting {
    data class Delta(val bold: Boolean? = null, val italic: Boolean? = null,
        val textRgb: Int? = null, val fillRgb: Int? = null)
    data class Rule(val priority: Int, val stop: Boolean,
        val ranges: List<OpenXmlDocumentParser.CellRangeAddress>, val operator: String,
        val operands: List<BigDecimal>, val delta: Delta?, val literalText: String? = null,
        val scale: ColorScale? = null)
    data class ScaleStop(val type: String, val value: BigDecimal?, val rgb: Int)
    data class NumericExtent(val minimum: BigDecimal, val maximum: BigDecimal) {
        fun include(value: BigDecimal) = NumericExtent(minOf(minimum, value), maxOf(maximum, value))
    }
    data class ColorScale(val stops: List<ScaleStop>) {
        fun color(raw: BigDecimal, extent: NumericExtent): Int? {
            val span = extent.maximum.subtract(extent.minimum)
            val thresholds = stops.map { stop ->
                when (stop.type) {
                    "min" -> extent.minimum
                    "max" -> extent.maximum
                    "num" -> stop.value ?: return null
                    "percent" -> extent.minimum.add(span.multiply(stop.value ?: return null).movePointLeft(2))
                    else -> return null
                }
            }
            if (thresholds.zipWithNext().any { (a,b) -> a > b }) return null
            if (thresholds.first().compareTo(thresholds.last()) == 0) return stops.last().rgb
            if (raw <= thresholds.first()) return stops.first().rgb
            if (raw >= thresholds.last()) return stops.last().rgb
            val i = (0 until thresholds.lastIndex).first { raw <= thresholds[it+1] }
            val width = thresholds[i+1].subtract(thresholds[i])
            val ratio = if (width.signum() == 0) 1.0 else raw.subtract(thresholds[i])
                .divide(width, java.math.MathContext.DECIMAL64).toDouble().coerceIn(0.0,1.0)
            val from = stops[i].rgb; val to = stops[i+1].rgb
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 255
                val b = (to shr shift) and 255
                return kotlin.math.round(a + (b-a)*ratio).toInt().coerceIn(0,255)
            }
            return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }

    val operators = setOf("equal", "notEqual", "greaterThan", "greaterThanOrEqual",
        "lessThan", "lessThanOrEqual", "between", "notBetween")
    val textOperators = setOf("containsText", "notContainsText", "beginsWith", "endsWith")
    fun number(value: String): BigDecimal? = if (value.length <= 128)
        value.toBigDecimalOrNull()?.takeIf { it.scale() in -400..400 && it.precision() <= 128 } else null

    fun apply(base: XlsxCellStyle, row: Int, col: Int, raw: BigDecimal?, rules: List<Rule>,
        textValue: String? = null, scaleExtents: Map<Int, NumericExtent> = emptyMap()): XlsxCellStyle {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var text: Int? = null
        var fill: Int? = null
        for (rule in rules) {
            if (rule.ranges.none { it.contains(row, col) }) continue
            val delta = rule.delta ?: break // unknown higher-priority condition may suppress lower rules
            val scale = rule.scale
            if (scale != null) {
                if (raw == null) continue
                val extent = scaleExtents[rule.priority] ?: continue
                val color = scale.color(raw, extent) ?: break
                if (fill == null) fill = color
                if (rule.stop) break
                continue
            }
            val literal = rule.literalText
            val matches = if (literal != null) {
                if (textValue == null) continue
                when (rule.operator) {
                    "containsText" -> textValue.contains(literal, ignoreCase = true)
                    "notContainsText" -> !textValue.contains(literal, ignoreCase = true)
                    "beginsWith" -> textValue.startsWith(literal, ignoreCase = true)
                    "endsWith" -> textValue.endsWith(literal, ignoreCase = true)
                    else -> false
                }
            } else {
                if (raw == null) continue
                val cmp = raw.compareTo(rule.operands[0])
                when (rule.operator) {
                "equal" -> cmp == 0
                "notEqual" -> cmp != 0
                "greaterThan" -> cmp > 0
                "greaterThanOrEqual" -> cmp >= 0
                "lessThan" -> cmp < 0
                "lessThanOrEqual" -> cmp <= 0
                "between" -> cmp >= 0 && raw <= rule.operands[1]
                "notBetween" -> cmp < 0 || raw > rule.operands[1]
                    else -> false
                }
            }
            if (!matches) continue
            if (bold == null) bold = delta.bold
            if (italic == null) italic = delta.italic
            if (text == null) text = delta.textRgb
            if (fill == null) fill = delta.fillRgb
            if (rule.stop) break
        }
        return base.copy(bold = bold ?: base.bold, italic = italic ?: base.italic,
            textRgb = text ?: base.textRgb, fillRgb = fill ?: base.fillRgb)
    }
}
