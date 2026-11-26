package com.veilframe.app.pdf.operations

/** Last-saved chart cache, not recalculated worksheet data. */
data class XlsxCachedChart(val title: String, val kind: String, val categories: List<String?>,
    val series: List<Series>, val grouping: String = "clustered",
    val scatterStyle: String = "marker", val sourceAnchor: String? = null,
    val printAreaAnchorTrusted: Boolean = false,
    val printsWithSheet: Boolean = true,
    val drawingOffsets: DrawingOffsets? = null,
    val blankDisplay: BlankDisplay = BlankDisplay.GAP,
    val valueAxes: List<ValueAxis> = emptyList()) {
    /** Applies to drawing categorical lines only; cached worksheet values remain unchanged. */
    enum class BlankDisplay {
        GAP, ZERO, SPAN;
        fun lineValue(raw: Double?): Double? = raw ?: if (this == ZERO) 0.0 else null
        fun breakOnMissing(): Boolean = this != SPAN
    }

    /** Saved value-axis identity and linear scaling, shared by both renderers. */
    data class ValueAxis(val id: String,val position: String,val minimum: Double?=null,
        val maximum: Double?=null,val reversed: Boolean=false,val numberFormat: String?=null)

    /** Source DrawingML coordinates, not worksheet or PDF point units. */
    data class DrawingOffsets(val fromColEmu: Long, val fromRowEmu: Long,
        val toColEmu: Long, val toRowEmu: Long) {
    }
    data class Series(val name: String, val values: List<Double?>,
        val xValues: List<Double?> = emptyList(), val chartKind: String? = null,
        val rgb: Int? = null, val strokeWidthPt: Float? = null,
        val markerSymbol: String? = null, val markerSizePt: Float? = null,
        val valueAxisId: String? = null)
}
