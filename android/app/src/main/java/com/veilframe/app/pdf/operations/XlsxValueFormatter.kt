package com.veilframe.app.pdf.operations

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Deterministic supported Excel display subset. Keep raw General values, and
 * explicitly label unsupported formats rather than substitute guessed dates or
 * silently reinterpret the stored value. Never evaluates formulas.
 */
internal class XlsxValueFormatter(
    private val date1904: Boolean,
    private val styles: List<Int>,
    private val custom: Map<Int, String>
) {
    private val builtins = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        14 to "m/d/yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "m/d/yy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 49 to "@"
    )
    private val decimalPattern = Regex("""(?:0{1,32}|#,##0)(?:\.[0#]{1,16})?%?""")
    private val dates = mapOf(
        "m/d/yy" to "M/d/uu", "mm/dd/yyyy" to "MM/dd/uuuu", "m/d/yyyy" to "M/d/uuuu",
        "d-mmm-yy" to "d-MMM-uu", "d-mmm" to "d-MMM", "mmm-yy" to "MMM-uu",
        "yyyy-mm-dd" to "uuuu-MM-dd", "dd/mm/yyyy" to "dd/MM/uuuu",
        "dd-mm-yyyy" to "dd-MM-uuuu", "dd-mmm-yyyy" to "dd-MMM-uuuu",
        "m/d/yy h:mm" to "M/d/uu H:mm", "yyyy-mm-dd hh:mm:ss" to "uuuu-MM-dd HH:mm:ss"
    )
    private val times = mapOf("h:mm am/pm" to "h:mm a", "h:mm:ss am/pm" to "h:mm:ss a",
        "h:mm" to "H:mm", "hh:mm" to "HH:mm", "h:mm:ss" to "H:mm:ss",
        "hh:mm:ss" to "HH:mm:ss", "mm:ss" to "mm:ss")

    // Parser owns one formatter, confined to the calling thread. Bound the
    // cache independently of the workbook's number of custom styles.
    private val decimalFormats = object : LinkedHashMap<String, DecimalFormat>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecimalFormat>?): Boolean =
            size > 64
    }
    private val dateFormats = (dates.values + times.values).distinct().associateWith {
        DateTimeFormatter.ofPattern(it, Locale.US)
    }
    private val maxSerial = BigDecimal("2958465.999999999999")
    private val secondsPerDay = BigDecimal("86400")

    fun format(raw: String, type: String, style: Int): String {
        require(style in styles.indices) { "Unknown cell style index" }
        if (type == "b") return when (raw) {
            "0" -> "FALSE"
            "1" -> "TRUE"
            else -> throw IllegalArgumentException("Invalid cached Boolean value")
        }
        if (type != "n" && type.isNotEmpty()) return raw
        if (raw.isEmpty()) return raw
        val id = styles[style]
        val code = custom[id] ?: builtins[id]
        if (id == 0 || code.equals("General", ignoreCase = true) || code == "@") return raw
        fun unsupported() = raw + " [format " + id + " unsupported]"
        if (code == null) return unsupported()
        // Excel stores binary floating-point numbers, but use the exact XML
        // decimal for admitted display patterns so formatting never rounds twice.
        val number = if (raw.length <= 128) raw.toBigDecimalOrNull() else null
        if (number == null || number.scale() !in -400..400 || number.precision() > 128)
            return unsupported()
        val normalized = code.lowercase(Locale.ROOT)
        if (normalized in dates || normalized in times || normalized == "[h]:mm:ss") {
            if (number.signum() < 0 || number > maxSerial) return unsupported()
            val whole = number.setScale(0, RoundingMode.FLOOR).longValueExact()
            val seconds = number.subtract(BigDecimal.valueOf(whole)).multiply(secondsPerDay)
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
            val totalSeconds = whole * 86400 + seconds
            if (normalized == "[h]:mm:ss") return String.format(Locale.US, "%d:%02d:%02d",
                totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
            val time = LocalTime.ofSecondOfDay(totalSeconds % 86400)
            times[normalized]?.let { return time.format(dateFormats.getValue(it)) }
            // Excel 1900 compatibility includes a fictitious leap day.
            // Don't map serial 60 to a real but incorrect Gregorian date.
            val hasTime = normalized.contains("h:")
            val day = if (hasTime) totalSeconds / 86400 else whole
            if (!date1904 && day == 60L) return unsupported()
            val date = if (date1904) LocalDate.of(1904, 1, 1).plusDays(day)
                else LocalDate.of(1899, 12, 31).plusDays(if (day > 60) day - 1 else day)
            if (date.year > 9999) return unsupported()
            return date.atTime(time).format(dateFormats.getValue(dates.getValue(normalized)))
        }
        // Basic currency literals, number masks and percentages only. Complex
        // conditions, text sections, locales, fractions etc. stay explicit raw.
        val currency = code.firstOrNull()?.takeIf { it == '$' || it == '€' || it == '£' || it == '¥' || it == '₹' }
        val pattern = if (currency != null) code.substring(1) else code
        if (!decimalPattern.matches(pattern)) return unsupported()
        val format = decimalFormats.getOrPut(pattern) {
            DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).apply {
                roundingMode = RoundingMode.HALF_UP
            }
        }
        val display = format.format(number)
        return if (currency == null) display
            else if (display.startsWith("-")) "-" + currency + display.substring(1) else currency + display
    }
}
