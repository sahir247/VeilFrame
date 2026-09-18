package com.veilframe.app.tools

import android.net.Uri
import java.io.File

/**
 * Dedicated independent tool workflows inside the VeilFrame shell.
 */
enum class ToolMode {
    AI_BUNDLE,
    VIDEO_CLEANER,
    IMAGE_CLEANER,
    FOLDER_SCANNER,
    IMAGE_COMPRESSOR,
    VIDEO_COMPRESSOR
}

/**
 * Formal telemetry and execution lifecycle state machine.
 */
enum class JobState {
    IDLE,
    PREPARING,
    SCANNING,
    PROCESSING,
    FINALIZING,
    COMPLETE,
    FAILED,
    CANCELLED
}

/**
 * Persistent per-tool session state across navigation.
 */
data class ToolSessionState(
    var selectedUri: Uri? = null,
    var selectedPathDisplay: String = "No file or folder selected",
    var isFolderSelected: Boolean = false,
    var targetFileCount: Int = 0,
    var targetTotalBytes: Long = 0L,
    var primaryOptionIndex: Int = 0,
    var formatOptionIndex: Int = 0,
    var switch1Checked: Boolean = false,
    var switch2Checked: Boolean = false,
    var switch3Checked: Boolean = false,
    var jobState: JobState = JobState.IDLE,
    var statusMessage: String = "Ready for execution.",
    var progressPercent: Int = 0,
    var progressDetailsText: String = "",
    var lastGeneratedFile: File? = null,
    var consoleLogs: String = "[SYS] Ready for execution."
)
