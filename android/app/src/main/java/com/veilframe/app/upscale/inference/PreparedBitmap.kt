package com.veilframe.app.upscale.inference

import android.graphics.Bitmap

data class PreparedBitmap(
    val bitmap: Bitmap,
    val recycleAfterUse: Boolean
)
