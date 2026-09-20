package com.veilframe.app.ui.motion

/**
 * State definitions for tactile components and morphing dialog windows.
 */
enum class MotionState {
    IDLE,
    PRESSED,
    OPENING,
    OPEN,
    CLOSING,
    COMPLETING,
    PROCESSING,
    COMPLETED
}

/**
 * Explicit state machine for dialog opening and closing lifecycle.
 * Prevents double-dismiss, race conditions, and animation clipping.
 */
enum class DialogMotionState {
    CLOSED,
    OPENING,
    OPEN,
    CLOSING,
    DISMISSED
}
