package com.veilframe.app.cv.motion

/** A false GPU initialization result is a failure, not successful backend selection. */
internal object MotionLabBackendSelection {
    fun initialize(preferGpu: Boolean, initialize: (Boolean) -> Boolean): Boolean =
        (preferGpu && initialize(true)) || initialize(false)
}
