package com.veilframe.app.pdf.operations

/** Last-saved chart cache, not recalculated worksheet data. */
data class XlsxCachedChart(val title: String, val kind: String, val categories: List<String?>,
    val series: List<Series>, val grouping: String = "clustered",
    val scatterStyle: String = "marker", val sourceAnchor: String? = null,
    val printAreaAnchorTrusted: Boolean = false,
    val printsWithSheet: Boolean = true,
    val drawingOffsets: DrawingOffsets? = null,
    val blankDisplay: BlankDisplay = BlankDisplay.GAP,
    val valueAxes: List<ValueAxis> = emptyList(),
    val firstSliceAngle: Int = 0,
    val scatterAxes: ScatterAxes? = null,
    val dateAxis: DateAxis? = null) {
    /** Applies to drawing categorical lines only; cached worksheet values remain unchanged. */
    enum class BlankDisplay {
        GAP, ZERO, SPAN;
        fun lineValue(raw: Double?): Double? = raw ?: if (this == ZERO) 0.0 else null
        fun breakOnMissing(): Boolean = this != SPAN
    }

    /** Saved value-axis identity and linear scaling, shared by both renderers. */
    data class ValueAxis(val id: String,val position: String,val minimum: Double?=null,
        val maximum: Double?=null,val reversed: Boolean=false,val numberFormat: String?=null,val logBase: Double?=null)

    /** Numeric XY axes are distinct from categorical value-axis assignments. */
    data class ScatterAxes(val x: ValueAxis,val y: ValueAxis)

    data class DateAxis(val serials: List<Long>,val date1904: Boolean,val minimum: Long?,
        val maximum: Long?,val reversed: Boolean,val majorDays: Long?,val format: String)

    /** Source DrawingML coordinates, not worksheet or PDF point units. */
    data class DrawingOffsets(val fromColEmu: Long, val fromRowEmu: Long,
        val toColEmu: Long, val toRowEmu: Long) {
    }
    /** Null Ink means unspecified; Ink(null) means explicitly no paint. */
    data class Ink(val rgb: Int?,val alpha: Int=255) { init { require(alpha in 0..255) } }
    data class Stroke(val ink: Ink?=null,val widthPt: Float?=null,
        val dashRatios: List<Float> = emptyList(),val cap: String="flat",
        val join: String="miter",val miterLimit: Float=4f)
    data class Series(val name: String, val values: List<Double?>,
        val xValues: List<Double?> = emptyList(), val chartKind: String? = null,
        val rgb: Int? = null, val strokeWidthPt: Float? = null,
        val markerSymbol: String? = null, val markerSizePt: Float? = null,
        val valueAxisId: String? = null,
        val fill: Ink? = null,val stroke: Stroke? = null,val markerFill: Ink? = null,
        val markerStroke: Stroke? = null,val pointFills: Map<Int,Ink> = emptyMap(),
        val pointStrokes: Map<Int,Stroke> = emptyMap())
}
