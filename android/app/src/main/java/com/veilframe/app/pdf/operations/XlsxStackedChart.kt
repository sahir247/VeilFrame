package com.veilframe.app.pdf.operations

/** Signed stack geometry calculated in bounded normalized space to avoid overflow. */
internal object XlsxStackedChart {
    data class Segment(val start: Double, val end: Double)

    fun segments(chart: XlsxCachedChart, maxAbs: Double, percent: Boolean): List<List<Segment?>> {
        require(maxAbs.isFinite() && maxAbs > 0.0)
        val count = chart.categories.size
        require(chart.series.all { it.values.size == count })
        val result = List(chart.series.size) { MutableList<Segment?>(count) { null } }
        for (i in 0 until count) {
            // Percentage stacks have independent category denominators. A tiny
            // category beside a huge one must not underflow to all zero.
            val divisor = if (percent) chart.series.mapNotNull { it.values[i] }
                .maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0.0 } ?: 1.0 else maxAbs
            val values = chart.series.map { it.values[i]?.div(divisor) }
            // The percent denominator is the sum of absolute magnitudes, so
            // mixed signs are preserved and never cancelled against each other.
            val denominator = if (percent) values.sumOf { kotlin.math.abs(it ?: 0.0) } else 1.0
            var positive = 0.0; var negative = 0.0
            for ((j,value) in values.withIndex()) {
                if (value == null) continue
                val v = if (denominator == 0.0) 0.0 else value / denominator
                val start = if (v >= 0.0) positive else negative
                val end = start + v
                if (v >= 0.0) positive = end else negative = end
                result[j][i] = Segment(start,end)
            }
        }
        return result
    }
}
