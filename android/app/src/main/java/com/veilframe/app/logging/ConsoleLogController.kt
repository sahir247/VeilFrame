package com.veilframe.app.logging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.veilframe.app.R

/**
 * Controller managing the embedded forensic telemetry console log view.
 * Handles thread-safe message appending, autoscrolling, copying to clipboard,
 * clearing buffer, and expanding/collapsing height.
 */
class ConsoleLogController(
    private val context: Context,
    private val tvConsoleLog: TextView,
    private val scrollConsole: NestedScrollView,
    private val btnCopyLogs: View?,
    private val btnClearLogs: View?,
    private val btnExpandLogs: MaterialButton?,
    private val onLogUpdated: ((String) -> Unit)? = null
) {
    private var isLogsExpanded: Boolean = false

    fun init() {
        btnCopyLogs?.setOnClickListener {
            val text = tvConsoleLog.text.toString()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("VeilFrame Console Logs", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Telemetry logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs?.setOnClickListener {
            val cleared = "[SYS] Telemetry buffer cleared.\n[SYS] Ready."
            tvConsoleLog.text = cleared
            onLogUpdated?.invoke(cleared)
            Toast.makeText(context, "Console logs cleared", Toast.LENGTH_SHORT).show()
        }

        btnExpandLogs?.let { btn ->
            btn.setOnClickListener {
                isLogsExpanded = !isLogsExpanded
                val targetHeightDp = if (isLogsExpanded) 320 else 140
                val density = context.resources.displayMetrics.density
                scrollConsole.layoutParams.height = (targetHeightDp * density).toInt()
                scrollConsole.requestLayout()
                btn.setIconResource(if (isLogsExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
                log(if (isLogsExpanded) "[UI] Telemetry console expanded." else "[UI] Telemetry console compact.")
            }
        }
    }

    fun log(message: String) {
        val current = tvConsoleLog.text.toString()
        val newLog = if (current.isEmpty()) message else "$current\n$message"
        tvConsoleLog.text = newLog
        onLogUpdated?.invoke(newLog)
        scrollConsole.post {
            scrollConsole.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun setLogs(logs: String) {
        tvConsoleLog.text = logs
        scrollConsole.post {
            scrollConsole.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun getLogs(): String = tvConsoleLog.text.toString()
}
