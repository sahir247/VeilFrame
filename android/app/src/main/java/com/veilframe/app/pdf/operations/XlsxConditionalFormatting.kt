package com.veilframe.app.pdf.operations

import java.math.BigDecimal

internal object XlsxConditionalFormatting {
    data class Delta(val bold: Boolean? = null, val italic: Boolean? = null,
        val textRgb: Int? = null, val fillRgb: Int? = null)
    data class Rule(val priority: Int, val stop: Boolean,
        val ranges: List<OpenXmlDocumentParser.CellRangeAddress>, val operator: String,
        val operands: List<BigDecimal>, val delta: Delta?, val literalText: String? = null,
        val scale: ColorScale? = null,
        val bar: DataBar? = null, val icons: IconSet? = null)
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

    private fun threshold(type: String, value: BigDecimal?, extent: NumericExtent): BigDecimal? {
        return when (type) {
            "min" -> extent.minimum
            "max" -> extent.maximum
            "num" -> value
            "percent" -> extent.minimum.add(extent.maximum.subtract(extent.minimum)
                .multiply(value ?: return null).movePointLeft(2))
            else -> null
        }
    }

    data class DataBar(val low: ScaleStop, val high: ScaleStop, val showValue: Boolean,
        val minimumLength: Int, val maximumLength: Int, val rgb: Int) {
        fun visual(raw: BigDecimal, extent: NumericExtent): XlsxDataBarVisual? {
            val lower = threshold(low.type, low.value, extent) ?: return null
            val upper = threshold(high.type, high.value, extent) ?: return null
            if (lower > upper) return null
            if (lower == upper) return XlsxDataBarVisual(0f, maximumLength / 100f, rgb, showValue)
            val span = upper.subtract(lower)
            val bounded = raw.max(lower).min(upper)
            fun ratio(value: BigDecimal): Float = value.divide(span,java.math.MathContext.DECIMAL64)
                .toFloat().coerceIn(0f,1f)
            val start: Float
            val end: Float
            if (lower >= BigDecimal.ZERO || upper <= BigDecimal.ZERO) {
                val fraction = ratio(bounded.subtract(lower))
                val length = (minimumLength + (maximumLength - minimumLength)*fraction) / 100f
                start = if (upper <= BigDecimal.ZERO) 1f else 0f
                end = if (upper <= BigDecimal.ZERO) 1f-length else length
            } else {
                // Signed bars use a real zero baseline. This differs from some
                // proprietary x14 appearance extensions, which are not inferred.
                start = ratio(lower.negate())
                end = ratio(bounded.subtract(lower))
            }
            return XlsxDataBarVisual(start,end,rgb,showValue)
        }
    }

    data class IconSet(val stops: List<ScaleStop>, val reverse: Boolean, val showValue: Boolean) {
        fun visual(raw: BigDecimal, extent: NumericExtent): XlsxIconVisual? {
            if (stops.size != 3) return null
            val values = stops.map { threshold(it.type,it.value,extent) ?: return null }
            if (values.zipWithNext().any { (left,right) -> left > right }) return null
            val tier = when { raw >= values[2] -> 2; raw >= values[1] -> 1; else -> 0 }
            val selected = if (reverse) 2-tier else tier
            val palette = intArrayOf(0xD94A3A,0xE0B63C,0x48A65E)
            return XlsxIconVisual(palette[selected],showValue)
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
        var barVisual: XlsxDataBarVisual? = null
        var iconVisual: XlsxIconVisual? = null
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
            if (rule.bar != null || rule.icons != null) {
                if (raw == null) continue
                val extent = scaleExtents[rule.priority] ?: continue
                if (rule.bar != null && barVisual == null) {
                    barVisual = rule.bar.visual(raw,extent) ?: break
                }
                if (rule.icons != null && iconVisual == null) {
                    iconVisual = rule.icons.visual(raw,extent) ?: break
                }
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
            textRgb = text ?: base.textRgb, fillRgb = fill ?: base.fillRgb,
            dataBar = barVisual ?: base.dataBar, icon = iconVisual ?: base.icon)
    }
}
