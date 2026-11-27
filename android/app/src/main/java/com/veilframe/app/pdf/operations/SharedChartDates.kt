package com.veilframe.app.pdf.operations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Date-axis line charts use elapsed days, not category index. The admitted
 * subset is whole-day serials and day-based ticks, with explicit calendar format. */
internal object SharedChartDates {
    private val patterns=mapOf("yyyy-mm-dd" to "uuuu-MM-dd","m/d/yy" to "M/d/uu",
        "mm/dd/yyyy" to "MM/dd/uuuu","m/d/yyyy" to "M/d/uuuu","dd-mmm-yy" to "dd-MMM-uu",
        "d-mmm" to "d-MMM","mmm-yy" to "MMM-uu","yyyy" to "uuuu")
    fun supportedFormat(format: String)=format.lowercase(Locale.ROOT) in patterns
    fun serial(raw: String?): Long? {
        val number=raw?.toDoubleOrNull() ?: return null
        if(!number.isFinite() || number<0 || number>2958465 || number!=kotlin.math.floor(number)) return null
        return number.toLong()
    }
    fun label(day: Long,date1904: Boolean,format: String): String {
        require(day in 0..2958465 && (date1904 || day!=60L))
        val date=if(date1904) LocalDate.of(1904,1,1).plusDays(day)
            else LocalDate.of(1899,12,31).plusDays(if(day>60) day-1 else day)
        require(date.year in 1..9999)
        return date.format(DateTimeFormatter.ofPattern(requireNotNull(patterns[format.lowercase(Locale.ROOT)]),Locale.US))
    }
    class Scale(val axis: XlsxCachedChart.DateAxis,val low: Long,val high: Long,val ticks: List<Long>) {
        fun fraction(day: Long): Float {
            val raw=(day-low).toDouble()/(high-low)
            return (if(axis.reversed) 1-raw else raw).toFloat()
        }
        fun fractionAt(index: Int)=fraction(axis.serials[index])
        fun label(day: Long)=SharedChartDates.label(day,axis.date1904,axis.format)
    }
    fun build(chart: XlsxCachedChart): Scale? {
        val a=chart.dateAxis ?: return null
        require(chart.kind=="line" && a.serials.size==chart.categories.size && a.serials.isNotEmpty())
        require(a.serials.zipWithNext().all { (x,y)->x<=y })
        val low=a.minimum ?: a.serials.first()
        val high=a.maximum ?: a.serials.last()
        require(high>low && (a.date1904 || !(low<=60 && high>=60)))
        (a.serials+listOf(low,high)).forEach { label(it,a.date1904,a.format) }
        val step=a.majorDays ?: ceil((high-low)/4.0).toLong().coerceAtLeast(1)
        require(step>0 && (high-low)/step<=9) { "Date-axis tick count exceeds visible budget" }
        val ticks=generateSequence(low) { previous -> (previous+step).takeIf { it<=high } }.toList()
        return Scale(a,low,high,ticks)
    }
}
