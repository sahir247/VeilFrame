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
    private fun date(day: Long,date1904: Boolean): LocalDate {
        require(day in 0..2958465 && (date1904 || day!=60L))
        val date=if(date1904) LocalDate.of(1904,1,1).plusDays(day)
            else LocalDate.of(1899,12,31).plusDays(if(day>60) day-1 else day)
        require(date.year in 1..9999)
        return date
    }
    private fun serial(date: LocalDate,date1904: Boolean): Long {
        val epoch=if(date1904) LocalDate.of(1904,1,1) else LocalDate.of(1899,12,31)
        val days=java.time.temporal.ChronoUnit.DAYS.between(epoch,date)
        return days+if(!date1904 && date>=LocalDate.of(1900,3,1)) 1 else 0
    }
    fun label(day: Long,date1904: Boolean,format: String): String {
        return date(day,date1904).format(DateTimeFormatter.ofPattern(requireNotNull(patterns[format.lowercase(Locale.ROOT)]),Locale.US))
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
        require(a.majorUnit in setOf("days","months","years"))
        val start=date(low,a.date1904)
        val end=date(high,a.date1904)
        val range=when(a.majorUnit) {
            "months" -> java.time.temporal.ChronoUnit.MONTHS.between(start,end)
            "years" -> java.time.temporal.ChronoUnit.YEARS.between(start,end)
            else -> high-low
        }
        val step=a.majorDays ?: ceil(range/4.0).toLong().coerceAtLeast(1)
        require(step in 1..2958465)
        val ticks=buildList {
            // Compute each calendar tick from the anchor, not the previous
            // tick: Jan 31 -> Feb 28 -> Mar 31 rather than permanent drift.
            for(i in 0..10) {
                val offset=i*step
                val day=when(a.majorUnit) {
                    "months" -> serial(start.plusMonths(offset),a.date1904)
                    "years" -> serial(start.plusYears(offset),a.date1904)
                    else -> low+offset
                }
                if(day>high) break
                require(i<10) { "Date-axis tick count exceeds visible budget" }
                add(day)
            }
        }
        return Scale(a,low,high,ticks)
    }
}
