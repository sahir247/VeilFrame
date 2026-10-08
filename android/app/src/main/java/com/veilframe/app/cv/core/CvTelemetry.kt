package com.veilframe.app.cv.core

import java.io.File

/**
 * CvTelemetry — private, on-device job telemetry (Phase B / B2).
 *
 * Append-only JSONL sink (cache/cv_runs.jsonl). NO network, ever — this is a
 * privacy product; the data feeds the in-app diagnostics panels, bug reports
 * the user chooses to copy, and regression analysis.
 *
 * Pure-JVM (File only) so the cv.core package stays unit-testable; the sink is
 * injected by [OpenCVInitProvider] on device and stays null in JVM tests
 * (record becomes a no-op).
 */
object CvTelemetry {

    @Volatile
    private var sink: File? = null

    private val lock = Any()

    /** Idempotent. Parent dirs are created lazily. */
    fun init(file: File) {
        sink = file
    }

    fun isRecording(): Boolean = sink != null

    /** Best-effort append; telemetry must never break a job. */
    fun record(entry: Map<String, Any?>) {
        val file = sink ?: return
        try {
            val line = toJson(entry)
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                // Bound the file: rotate at ~512 KB (keeps roughly the last few
                // thousand jobs; diagnostics only needs recent history).
                if (file.length() > 512 * 1024) {
                    val lines = file.readLines()
                    file.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
                }
            }
        } catch (_: Throwable) {
            // Telemetry is best-effort by contract.
        }
    }

    /** Last N records for diagnostics panels (newest last). */
    fun recent(n: Int = 20): List<String> {
        val file = sink ?: return emptyList()
        return try {
            file.readLines().filter { it.isNotBlank() }.takeLast(n)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun toJson(entry: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in entry) {
            if (!first) append(',')
            first = false
            append('"').append(escape(k)).append('"').append(':')
            when (v) {
                null -> append("null")
                is Number, is Boolean -> append(v.toString())
                else -> append('"').append(escape(v.toString())).append('"')
            }
        }
        append('}')
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
}
