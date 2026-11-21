package com.veilframe.app.pdf.operations

/** Last-saved chart cache, not recalculated worksheet data. */
data class XlsxCachedChart(val title: String, val kind: String, val categories: List<String?>,
    val series: List<Series>, val grouping: String = "clustered",
    val scatterStyle: String = "marker", val sourceAnchor: String? = null,
    val printAreaAnchorTrusted: Boolean = false,
    val printsWithSheet: Boolean = true) {
    data class Series(val name: String, val values: List<Double?>,
        val xValues: List<Double?> = emptyList(), val chartKind: String? = null,
        val rgb: Int? = null, val strokeWidthPt: Float? = null)
}
