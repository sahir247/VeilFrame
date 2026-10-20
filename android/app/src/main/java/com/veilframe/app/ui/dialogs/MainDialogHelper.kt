package com.veilframe.app.ui.dialogs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.logging.ConsoleLogController
import com.veilframe.app.runtime.ThermalGovernor
import com.veilframe.app.storage.StorageGovernor

/**
 * Helper for top-level shell dialogs (System Diagnostics, Technical Console, About, Markdown Studio entry).
 */
object MainDialogHelper {

    fun showDiagnosticsDialog(activity: Activity, consoleLogController: ConsoleLogController) {
        val cvStatus = if (CvRuntime.isNativeAvailable) "Available (Hardware Accelerated)" else "Fallback (Java/Emulated)"
        val thermalStatus = ThermalGovernor.status
        val storageUsedMb = StorageGovernor.calculateManagedStorageBytes(activity) / (1024 * 1024)
        val storageMaxGb = StorageGovernor.getMaxStorageGb(activity)
        val logLength = consoleLogController.getLogs().length

        val msg = """
            • OpenCV Engine: $cvStatus
            • Thermal Status: Level $thermalStatus
            • Managed Storage: ${storageUsedMb}MB / ${storageMaxGb}GB Limit
            • Diagnostic Log Buffer: $logLength characters
            • Security Model: Zero-Network Offline Sandbox
        """.trimIndent()

        MaterialAlertDialogBuilder(activity)
            .setTitle("VeilFrame System Diagnostics")
            .setMessage(msg)
            .setIcon(R.drawable.ic_settings)
            .setPositiveButton("View Live Console") { _, _ ->
                showFullConsoleLogDialog(activity, consoleLogController)
            }
            .setNeutralButton("Clear Logs") { _, _ ->
                consoleLogController.clear()
                Toast.makeText(activity, "Diagnostic logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun showFullConsoleLogDialog(activity: Activity, consoleLogController: ConsoleLogController) {
        val currentLogs = consoleLogController.getLogs().ifBlank { "[SYS] No telemetry events logged yet." }
        val scrollView = ScrollView(activity)
        val textView = TextView(activity).apply {
            text = currentLogs
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(activity)
            .setTitle("Live Technical Console")
            .setView(scrollView)
            .setPositiveButton("Copy All") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VeilFrame Logs", currentLogs))
                Toast.makeText(activity, "Console logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    fun showMarkdownStudioEntryDialog(
        activity: Activity,
        onOpenExisting: () -> Unit,
        onCreateNew: () -> Unit
    ) {
        val options = arrayOf<CharSequence>("Open Markdown (.md)", "Create Markdown Document")
        MaterialAlertDialogBuilder(activity)
            .setTitle("Markdown Studio")
            .setItems(options) { _, which ->
                if (which == 0) {
                    onOpenExisting()
                } else {
                    onCreateNew()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAboutDialog(activity: Activity) {
        val version = com.veilframe.app.BuildConfig.VERSION_NAME
        MaterialAlertDialogBuilder(activity)
            .setTitle("About VeilFrame")
            .setMessage(
                """
                VeilFrame v$version
                Privacy Forensics & AI Bundler
                
                • Local Processing: 100% on-device execution
                • Privacy First: Heuristic secret & credential masking
                • Forensic Analysis: PRNU defense, bitstream repacking & SHA-256
                • Multi-Format Export: .aibundle, HTML, JSON, Markdown, CSV, and media
                • In-App Updates: Monotonic versionCode & SHA-256 verification
                
                Engine Runtime: Native Kotlin + Media3 + FFmpegKit Full
                Open Source (Apache 2.0 / MIT)
                """.trimIndent()
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
