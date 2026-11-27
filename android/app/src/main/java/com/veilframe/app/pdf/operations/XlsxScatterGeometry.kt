package com.veilframe.app.pdf.operations

/** Finite, overflow-safe axis normalization for cached XY chart reconstructions. */
internal object XlsxScatterGeometry {
    class Axis(val minimum: Double, val maximum: Double, private val divisor: Double,
        private val explicit: SharedChartValueAxes.Scale?=null) {
        private val span = maximum-minimum
        fun fraction(value: Double): Float =
            explicit?.fraction(value) ?: ((value/divisor-minimum)/span).toFloat().coerceIn(0f,1f)
        fun label(position: Float): String {
            explicit?.let { return it.tick(position) }
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

    fun build(chart: XlsxCachedChart): Pair<Axis,Axis> {
        require(chart.kind=="scatter")
        val pairs=chart.series.flatMap { series ->
            require(series.xValues.size==series.values.size)
            series.xValues.zip(series.values).mapNotNull { (x,y) -> if(x==null || y==null) null else x to y }
        }
        require(pairs.isNotEmpty())
        val spec=chart.scatterAxes
        return axis(pairs.map { it.first },spec?.x) to axis(pairs.map { it.second },spec?.y)
    }
    private fun axis(values: List<Double>,spec: XlsxCachedChart.ValueAxis?): Axis {
        if(spec==null) return axis(values)
        require(values.isNotEmpty() && (values+listOfNotNull(spec.minimum,spec.maximum)).all { it.isFinite() })
        require(SharedChartValueAxes.supportedFormat(spec.numberFormat))
        val log=spec.logBase
        val divisor: Double
        val low: Double
        val high: Double
        if(log!=null) {
            require(log.isFinite() && log in 2.0..1000.0 &&
                (values+listOfNotNull(spec.minimum,spec.maximum)).all { it>0.0 })
            fun transform(v: Double)=kotlin.math.ln(v)/kotlin.math.ln(log)
            divisor=1.0
            low=spec.minimum?.let(::transform) ?: kotlin.math.floor(transform(values.minOrNull()!!))
            high=spec.maximum?.let(::transform) ?: kotlin.math.ceil(transform(values.maxOrNull()!!))
        } else {
            divisor=(values+listOfNotNull(spec.minimum,spec.maximum)).maxOf { kotlin.math.abs(it) }.takeIf { it>0.0 } ?: 1.0
            low=spec.minimum?.div(divisor) ?: values.minOrNull()!!/divisor
            high=spec.maximum?.div(divisor) ?: values.maxOrNull()!!/divisor
        }
        var a=low;var b=high
        if(a==b) {
            if(spec.minimum==null) a-=0.5
            if(spec.maximum==null) b+=0.5
        }
        require(a.isFinite() && b.isFinite() && b>a) { "Invalid explicit XY axis bounds" }
        if(log!=null) require(Math.pow(log,a)>0 && Math.pow(log,b).isFinite())
        val scale=SharedChartValueAxes.Scale(spec,divisor,a,b)
        values.forEach { scale.fraction(it) }
        return Axis(a,b,divisor,scale)
    }
}
