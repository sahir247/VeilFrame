package com.veilframe.app.qr.model

import android.graphics.Bitmap

/**
 * Represents an individual frame in an animated QR sequence.
 *
 * @param bitmap The frame bitmap (source image or rendered QR frame).
 * @param durationMs Duration of this frame in milliseconds (defaults to 100ms / 10 FPS).
 */
data class QrFrame(
    val bitmap: Bitmap,
    val durationMs: Int = 100
)
