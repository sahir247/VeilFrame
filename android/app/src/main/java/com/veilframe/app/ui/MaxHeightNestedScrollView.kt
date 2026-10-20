package com.veilframe.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] subclass that enforces [android.R.attr.maxHeight] during measure passes.
 * Standard [NestedScrollView] does not support maxHeight, causing dialog layouts to size
 * unpredictably across Android OS versions.
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var maxHeight: Int = -1

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        try {
            maxHeight = a.getDimensionPixelSize(0, -1)
        } finally {
            a.recycle()
        }
    }

    fun setMaxHeight(heightPx: Int) {
        maxHeight = heightPx
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredHeightSpec = heightMeasureSpec
        if (maxHeight > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val targetSize = if (mode == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
            measuredHeightSpec = MeasureSpec.makeMeasureSpec(targetSize, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, measuredHeightSpec)
    }
}
