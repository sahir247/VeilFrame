package com.veilframe.app.pdf.operations

import java.math.BigDecimal

internal object XlsxConditionalFormatting {
    data class Delta(val bold: Boolean? = null, val italic: Boolean? = null,
        val textRgb: Int? = null, val fillRgb: Int? = null)
    data class Rule(val priority: Int, val stop: Boolean,
        val ranges: List<OpenXmlDocumentParser.CellRangeAddress>, val operator: String,
        val operands: List<BigDecimal>, val delta: Delta?)
    val operators = setOf("equal", "notEqual", "greaterThan", "greaterThanOrEqual",
        "lessThan", "lessThanOrEqual", "between", "notBetween")
    fun number(value: String): BigDecimal? = if (value.length <= 128)
        value.toBigDecimalOrNull()?.takeIf { it.scale() in -400..400 && it.precision() <= 128 } else null

    fun apply(base: XlsxCellStyle, row: Int, col: Int, raw: BigDecimal, rules: List<Rule>): XlsxCellStyle {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var text: Int? = null
        var fill: Int? = null
        for (rule in rules) {
            if (rule.ranges.none { it.contains(row, col) }) continue
            val delta = rule.delta ?: break // unknown higher-priority condition may suppress lower rules
            val cmp = raw.compareTo(rule.operands[0])
            val matches = when (rule.operator) {
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
