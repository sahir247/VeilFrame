package com.veilframe.app.pdf.operations

/** Last-saved chart cache, not recalculated worksheet data. */
data class XlsxCachedChart(val title: String, val kind: String, val categories: List<String?>,
    val series: List<Series>, val grouping: String = "clustered") {
    data class Series(val name: String, val values: List<Double?>)
}
