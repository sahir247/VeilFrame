package com.veilframe.app.pdf.operations

import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/** Dual linear categorical axes shared by PPTX canvas and XLSX PDF rendering.
 * Normalize before subtraction to avoid overflow on large signed values.
 */
internal object SharedChartValueAxes {
    fun supportedFormat(code: String?): Boolean =
        code==null || code=="General" || code.matches(Regex("(?:0|#,##0)(?:\\.0{1,6})?%?"))
    data class Scale(val axis: XlsxCachedChart.ValueAxis,val unit: Double,val low: Double,val high: Double) {
        fun fraction(value: Double): Float {
            val coordinate=if(axis.logBase!=null) {
                require(value>0.0 && value.isFinite()) { "Nonpositive logarithmic chart value" }
                kotlin.math.ln(value)/kotlin.math.ln(axis.logBase)
            } else value/unit
            val raw=(coordinate-low)/(high-low)
            require(raw.isFinite() && abs(raw)<=100000) { "Chart axis coordinate exceeds budget" }
            return (if(axis.reversed) 1-raw else raw).toFloat()
        }
        fun zero(): Float {
            val raw=when { axis.logBase!=null -> 0.0; low>=0.0 -> 0.0; high<=0.0 -> 1.0; else -> -low/(high-low) }
            return (if(axis.reversed) 1-raw else raw).toFloat()
        }
        fun tick(ratio: Float): String {
            val normalized=low+(high-low)*(if(axis.reversed) 1-ratio else ratio)
            val v=axis.logBase?.let { Math.pow(it,normalized) } ?: (normalized*unit)
            if(!v.isFinite()) return String.format(Locale.US,"%.4g x %.4g",normalized,unit)
            val code=axis.numberFormat
            if(code==null || code=="General") return String.format(Locale.US,"%.4g",v)
            val percent=code.endsWith("%")
            val decimals=code.substringBefore('%').substringAfter('.', "").length
            val scaled=if(percent) v*100 else v
            if(!scaled.isFinite()) return String.format(Locale.US,"%.4g",v)
            val grouping=if(code.contains(',')) "," else ""
            return String.format(Locale.US,"%"+grouping+"."+decimals+"f",scaled)+(if(percent) "%" else "")
        }
    }
    fun build(chart: XlsxCachedChart): Map<String,Scale> {
        if(chart.valueAxes.isEmpty()) return emptyMap()
        require((chart.kind=="combo" && chart.valueAxes.size==2 &&
            chart.valueAxes.map { it.id }.distinct().size==2 &&
            chart.valueAxes.map { it.position }.toSet()==setOf("l","r")) ||
            (chart.kind in setOf("line","column","combo") && chart.valueAxes.size==1 &&
                chart.valueAxes.single().position=="l" && chart.grouping !in setOf("stacked","percentStacked")))
        require(chart.series.all { s -> chart.valueAxes.any { it.id==s.valueAxisId } })
        return chart.valueAxes.associate { a ->
            require(supportedFormat(a.numberFormat))
            val values=chart.series.filter { it.valueAxisId==a.id }.flatMap { it.values }.filterNotNull()
            if(values.isEmpty()) throw IOException("Empty value-axis cache")
            val unit: Double
            val low: Double
            val high: Double
            if(a.logBase!=null) {
                require(a.logBase.isFinite() && a.logBase in 2.0..1000.0 &&
                    (values+listOfNotNull(a.minimum,a.maximum)).all { it.isFinite() && it>0.0 })
                require(chart.blankDisplay!=XlsxCachedChart.BlankDisplay.ZERO ||
                    chart.series.filter { it.valueAxisId==a.id }.none { it.values.any { v -> v==null } })
                val divisor=kotlin.math.ln(a.logBase)
                fun log(v: Double)=kotlin.math.ln(v)/divisor
                unit=1.0
                low=a.minimum?.let(::log) ?: kotlin.math.floor(log(values.minOrNull()!!))
                high=a.maximum?.let(::log) ?: kotlin.math.ceil(log(values.maxOrNull()!!))
            } else {
                unit=(values+listOfNotNull(a.minimum,a.maximum)).maxOf { abs(it) }.takeIf { it>0 } ?: 1.0
                low=a.minimum?.div(unit) ?: minOf(0.0,values.minOrNull()!!/unit)
                high=a.maximum?.div(unit) ?: maxOf(0.0,values.maxOrNull()!!/unit)
            }
            val top=if(high==low && a.maximum==null) low+1.0 else high
            if(a.logBase!=null) require(Math.pow(a.logBase,low)>0 && Math.pow(a.logBase,top).isFinite())
            require(low.isFinite() && top.isFinite() && top>low) { "Invalid value-axis scale" }
            val scale=Scale(a,unit,low,top)
            values.forEach { scale.fraction(it) }
            scale.zero()
            a.id to scale
        }
    }
}
