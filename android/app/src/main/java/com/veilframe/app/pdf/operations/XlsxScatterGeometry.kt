package com.veilframe.app.pdf.operations

/** Finite, overflow-safe axis normalization for cached XY chart reconstructions. */
internal object XlsxScatterGeometry {
    class Axis(val minimum: Double, val maximum: Double, private val divisor: Double) {
        private val span = maximum-minimum
        fun fraction(value: Double): Float =
            ((value/divisor-minimum)/span).toFloat().coerceIn(0f,1f)
        fun label(position: Float): String {
            val normalized = minimum + span*position
            val absolute = normalized*divisor
            return if (absolute.isFinite()) String.format(java.util.Locale.US,"%.3g",absolute)
                else String.format(java.util.Locale.US,"%.3g x %.3g",normalized,divisor)
        }
    }
    fun axis(values: List<Double?>): Axis {
        val finite = values.filterNotNull()
        require(finite.isNotEmpty() && finite.all { it.isFinite() }) { "XY chart has no finite axis values" }
        val divisor = finite.maxOf { kotlin.math.abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val minimum = finite.minOf { it/divisor }
        val maximum = finite.maxOf { it/divisor }
        val low = if (minimum == maximum) minimum-0.5 else minimum
        val high = if (minimum == maximum) maximum+0.5 else maximum
        return Axis(low,high,divisor)
    }
}
