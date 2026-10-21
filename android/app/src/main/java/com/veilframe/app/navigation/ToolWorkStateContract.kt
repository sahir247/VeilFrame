package com.veilframe.app.navigation

/**
 * Common state contract for VeilFrame tools and creative workspaces.
 * Governs back-navigation interception, honest confirmation dialogs,
 * instant resource deallocation on discard, and background processing transitions.
 */
interface ToolWorkStateContract {
    /** True if media has been loaded, picked, or generated in this tool */
    fun hasPickedMedia(): Boolean

    /** True if there are unexported or unsaved modifications */
    fun hasUnsavedWork(): Boolean

    /** True if actively running inference, encoding, or export */
    val isProcessing: Boolean

    /** True strictly for heavy offline tools: Image Upscale, Motion Lab, and Video Studio */
    val supportsBackgroundExecution: Boolean get() = false

    /**
     * User chose to leave without saving.
     * Immediately aborts any running processing, recycles bitmaps,
     * flushes temporary files from cache, and releases native/heap memory.
     */
    fun discardAndReleaseAllResources()

    /**
     * User chose to allow active processing to continue in background.
     * Transitions active job to an Android Foreground Service with notification.
     */
    fun moveToBackground() {}
}
