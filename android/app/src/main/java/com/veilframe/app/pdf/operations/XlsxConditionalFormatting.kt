package com.veilframe.app.pdf.operations

import java.math.BigDecimal

internal object XlsxConditionalFormatting {
    data class Delta(val bold: Boolean? = null, val italic: Boolean? = null,
        val textRgb: Int? = null, val fillRgb: Int? = null)
    data class Rule(val priority: Int, val stop: Boolean,
        val ranges: List<OpenXmlDocumentParser.CellRangeAddress>, val operator: String,
        val operands: List<BigDecimal>, val delta: Delta?, val literalText: String? = null,
        val scale: ColorScale? = null,
        val bar: DataBar? = null, val icons: IconSet? = null,
        val expression: Expression? = null)
    data class ScaleStop(val type: String, val value: BigDecimal?, val rgb: Int,
        val inclusive: Boolean = true)
    data class NumericExtent(val minimum: BigDecimal, val maximum: BigDecimal,
        val percentileSamples: List<BigDecimal> = emptyList()) {
        fun include(value: BigDecimal) = NumericExtent(minOf(minimum, value), maxOf(maximum, value))
        fun withPercentiles(values: List<BigDecimal>): NumericExtent =
            copy(percentileSamples = values.sorted())

        /** Inclusive rank interpolation, with duplicate saved numbers retained. */
        fun percentile(percent: BigDecimal): BigDecimal? {
            if (percentileSamples.isEmpty() || percent < BigDecimal.ZERO ||
                percent > BigDecimal("100")) return null
            if (percentileSamples.size == 1) return percentileSamples.first()
            val position = BigDecimal(percentileSamples.lastIndex).multiply(percent)
                .divide(BigDecimal("100"))
            val low = position.toInt()
            val high = (low + 1).coerceAtMost(percentileSamples.lastIndex)
            val portion = position.subtract(BigDecimal(low))
            val first = percentileSamples[low]
            return first.add(percentileSamples[high].subtract(first).multiply(portion))
        }
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
                    "percentile" -> extent.percentile(stop.value ?: return null) ?: return null
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
            "percentile" -> extent.percentile(value ?: return null)
            else -> null
        }
    }

    data class DataBar(val low: ScaleStop, val high: ScaleStop, val showValue: Boolean,
        val minimumLength: Int, val maximumLength: Int, val rgb: Int,
        val appearance: SolidBarAppearance? = null) {
        fun visual(raw: BigDecimal, extent: NumericExtent): XlsxDataBarVisual? {
            val lower = threshold(low.type, low.value, extent) ?: return null
            val upper = threshold(high.type, high.value, extent) ?: return null
            if (lower > upper) return null
            appearance?.let { return it.visual(raw,lower,upper,rgb,showValue,minimumLength,maximumLength) }
            if (lower == upper) return XlsxDataBarVisual(0f, maximumLength / 100f, rgb, showValue)
            val span = upper.subtract(lower)
            val bounded = raw.max(lower).min(upper)
            fun ratio(value: BigDecimal): Float = value.divide(span,java.math.MathContext.DECIMAL64)
                .toFloat().coerceIn(0f,1f)
            val start: Float
            val end: Float
            if (lower >= BigDecimal.ZERO || upper <= BigDecimal.ZERO) {
                val fraction = if (upper <= BigDecimal.ZERO) ratio(upper.subtract(bounded))
                    else ratio(bounded.subtract(lower))
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

    /** x14 data bars: signed axes (0–100%) or no-axis bars with explicit length limits. */
    data class SolidBarAppearance(val negativeRgb: Int, val borderRgb: Int?, val negativeBorderRgb: Int?,
        val axisRgb: Int, val rtl: Boolean, val gradient: Boolean = false,
        val midpoint: Boolean = false, val noAxis: Boolean = false) {
        fun visual(raw: BigDecimal, lower: BigDecimal, upper: BigDecimal, rgb: Int, show: Boolean,
            minimumLength: Int = 0, maximumLength: Int = 100): XlsxDataBarVisual {
            if (noAxis) {
                // Without a zero axis, numeric order (not absolute magnitude) sets length.
                val fraction = if (lower == upper) 1f else raw.max(lower).min(upper).subtract(lower)
                    .divide(upper.subtract(lower),java.math.MathContext.DECIMAL64).toFloat().coerceIn(0f,1f)
                val length = (minimumLength + (maximumLength-minimumLength)*fraction)/100f
                val negative = raw.signum() < 0
                return XlsxDataBarVisual(if (rtl) 1f else 0f,if (rtl) 1f-length else length,
                    if (negative) negativeRgb else rgb,show,
                    if (negative) negativeBorderRgb else borderRgb,null,axisRgb,gradient)
            }
            val lo = minOf(lower,BigDecimal.ZERO)
            val hi = maxOf(upper,BigDecimal.ZERO)
            val span = hi.subtract(lo)
            val value = raw.max(lo).min(hi)
            val signed = lo.signum() < 0 && hi.signum() > 0
            fun position(x: BigDecimal): Float {
                if (midpoint && signed) {
                    val limit = if (x.signum() < 0) lo.negate() else hi
                    return (0.5 + 0.5*x.divide(limit,java.math.MathContext.DECIMAL64).toDouble()).toFloat().coerceIn(0f,1f)
                }
                return if (span.signum() == 0) 0f else
                    x.subtract(lo).divide(span,java.math.MathContext.DECIMAL64).toFloat().coerceIn(0f,1f)
            }
            fun orient(x: Float) = if (rtl) 1f-x else x
            val axis = orient(position(BigDecimal.ZERO))
            val negative = raw.signum() < 0
            return XlsxDataBarVisual(axis,orient(position(value)),if (negative) negativeRgb else rgb,show,
                if (negative) negativeBorderRgb else borderRgb,
                if (signed) axis else null,axisRgb,gradient)
        }
    }

    data class IconSet(val stops: List<ScaleStop>, val reverse: Boolean, val showValue: Boolean,
        val kind: String = "3TrafficLights1") {
        fun visual(raw: BigDecimal, extent: NumericExtent): XlsxIconVisual? {
            val count = if (kind.startsWith("4")) 4 else if (kind.startsWith("5")) 5 else 3
            if (stops.size != count) return null
            val values = stops.map { threshold(it.type,it.value,extent) ?: return null }
            if (values.zipWithNext().any { (left,right) -> left > right }) return null
            fun passed(index: Int): Boolean = if (stops[index].inclusive) raw >= values[index]
                else raw > values[index]
            var tier = 0
            for (index in 1 until count) if (passed(index)) tier = index
            val selected = if (reverse) count - 1 - tier else tier
            val palette = when (count) {
                4 -> intArrayOf(0xD94A3A,0xE0B63C,0xE0B63C,0x48A65E)
                5 -> intArrayOf(0xD94A3A,0xE28A44,0xE0B63C,0x98C45C,0x48A65E)
                else -> intArrayOf(0xD94A3A,0xE0B63C,0x48A65E)
            }
            val arrows = "Arrows" in kind
            val shape = when (count) {
                4 -> listOf("down","right","up-right","up")[selected]
                5 -> listOf("down","down-right","right","up-right","up")[selected]
                else -> listOf("down","right","up")[selected]
            }
            return XlsxIconVisual(if (kind.endsWith("Gray")) 0x858585 else palette[selected],
                showValue, if (arrows) shape else "circle")
        }
    }

    sealed class Expression {
        abstract fun matches(row: Int, column: Int, raw: BigDecimal?): Boolean
        data class Modulo(val rows: Boolean, val divisor: Int, val remainder: Int,
            val inverted: Boolean) : Expression() {
            override fun matches(row: Int, column: Int, raw: BigDecimal?): Boolean {
                val value = if (rows) row + 1 else column + 1
                val matches = value % divisor == remainder
                return if (inverted) !matches else matches
            }
        }
        data class BooleanChain(val all: Boolean, val terms: List<Expression>) : Expression() {
            override fun matches(row: Int, column: Int, raw: BigDecimal?): Boolean =
                if (all) terms.all { it.matches(row, column, raw) }
                else terms.any { it.matches(row, column, raw) }
        }
        data class CurrentCell(val operator: String, val operand: BigDecimal) : Expression() {
            override fun matches(row: Int, column: Int, raw: BigDecimal?): Boolean {
                val compare = raw?.compareTo(operand) ?: return false
                return when (operator) {
                    "=" -> compare == 0
                    "<>" -> compare != 0
                    ">" -> compare > 0
                    ">=" -> compare >= 0
                    "<" -> compare < 0
                    "<=" -> compare <= 0
                    else -> false
                }
            }
        }
    }

    /**
     * Deliberately no general formula evaluator, external references, volatile
     * functions, or absolute references. Expressions cover only explicit cells.
     */
    fun expression(source: String, ranges: List<OpenXmlDocumentParser.CellRangeAddress>): Expression? {
        if (source.length > 128 || ranges.size != 1) return null
        val text = source.trim().removePrefix("=").replace(Regex("\\s+"),"")
        // Only bounded AND/OR over already supported, independently validated
        // same-cell comparisons and deterministic ROW/COLUMN bands. No cross-
        // cell lookup, general Excel evaluator or short-circuit side effects.
        val conjunction = when {
            text.startsWith("AND(", ignoreCase = true) && text.endsWith(")") -> true
            text.startsWith("OR(", ignoreCase = true) && text.endsWith(")") -> false
            else -> null
        }
        if (conjunction != null) {
            val body = text.substring(if (conjunction) 4 else 3, text.length - 1)
            val terms = mutableListOf<String>()
            var depth = 0
            var start = 0
            for ((index, char) in body.withIndex()) {
                when (char) {
                    '(' -> depth++
                    ')' -> if (--depth < 0) return null
                    ',' -> if (depth == 0) {
                        terms.add(body.substring(start,index))
                        start = index + 1
                        if (terms.size > 3) return null
                    }
                }
            }
            if (depth != 0) return null
            terms.add(body.substring(start))
            if (terms.size !in 2..4 || terms.any { it.isBlank() }) return null
            val atoms = terms.map { term ->
                val atom = expression(term, ranges) ?: return null
                if (atom is Expression.BooleanChain) return null // no nested Boolean grammar
                atom
            }
            return Expression.BooleanChain(conjunction, atoms)
        }
        val modulo = Regex("""(?i)MOD\((ROW|COLUMN)\(\),([1-9][0-9]{0,2})\)(=|<>)([0-9]{1,3})""")
            .matchEntire(text)
        if (modulo != null) {
            val divisor = modulo.groupValues[2].toInt()
            val remainder = modulo.groupValues[4].toInt()
            if (remainder >= divisor) return null
            return Expression.Modulo(modulo.groupValues[1].equals("ROW",true),divisor,remainder,
                modulo.groupValues[3] == "<>")
        }
        val comparison = Regex("""(?i)([A-Z]{1,3})([1-9][0-9]{0,6})(>=|<=|<>|>|<|=)(-?[0-9]+(?:\.[0-9]+)?)""")
            .matchEntire(text) ?: return null
        val row = comparison.groupValues[2].toIntOrNull() ?: return null
        if (row != ranges[0].firstRow + 1) return null
        val col = runCatching { OpenXmlDocumentParser.colRefToIndex(comparison.groupValues[1] + "1") }.getOrNull()
            ?: return null
        if (col != ranges[0].firstCol) return null
        val operand = number(comparison.groupValues[4]) ?: return null
        return Expression.CurrentCell(comparison.groupValues[3], operand)
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
            val expression = rule.expression
            if (expression != null) {
                if (!expression.matches(row,col,raw)) continue
                if (bold == null) bold = delta.bold
                if (italic == null) italic = delta.italic
                if (text == null) text = delta.textRgb
                if (fill == null) fill = delta.fillRgb
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
