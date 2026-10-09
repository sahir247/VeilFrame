package com.veilframe.app.tools

import android.content.Context
import androidx.annotation.DrawableRes
import com.veilframe.app.R

/**
 * Tracks usage counts of top-level VeilFrame tools and dynamically recommends
 * the user's most frequently used tool for the home hero card.
 */
object ToolUsageTracker {

    private const val PREFS_NAME = "veilframe_tool_usage_prefs"
    private const val KEY_PREFIX = "tool_count_"

    enum class TrackedTool(
        val key: String,
        val category: String,
        val badge: String,
        val title: String,
        val description: String,
        val buttonText: String,
        @DrawableRes val iconRes: Int
    ) {
        IMAGE_UPSCALER(
            key = "tool_upscaler",
            category = "Offline AI enhancement",
            badge = "Private & Offline",
            title = "AI Image Upscaler",
            description = "Offline AI super-resolution & restoration (2x / 4x) without cloud data leakage.",
            buttonText = "Launch Upscaler",
            iconRes = R.drawable.ic_resize
        ),
        DOCUMENT_SCANNER(
            key = "tool_doc_scanner",
            category = "Multi-page intake",
            badge = "Edge AI & PDF",
            title = "Document Scanner",
            description = "Scan documents with live boundary detection, auto-crop, and offline PDF export.",
            buttonText = "Launch Scanner",
            iconRes = R.drawable.ic_camera
        ),
        QR_STUDIO(
            key = "tool_qr",
            category = "Matrix generator & scan",
            badge = "Artistic & Offline",
            title = "QR Studio",
            description = "Generate customized artistic QR codes and scan instantly with privacy guarantees.",
            buttonText = "Launch QR Studio",
            iconRes = R.drawable.ic_tool_qr
        ),
        VIDEO_STUDIO(
            key = "tool_video_studio",
            category = "Visual trimmer & cleaner",
            badge = "Hardware Accelerated",
            title = "Video Studio",
            description = "Trim, compress, and strip sensitive PRNU sensor noise and camera metadata locally.",
            buttonText = "Launch Video Studio",
            iconRes = R.drawable.ic_tab_video
        ),
        IMAGE_STUDIO(
            key = "tool_image_studio",
            category = "Photo optimizer",
            badge = "Zero Cloud Telemetry",
            title = "Image Studio",
            description = "Crop, resize, convert, and sanitize photos locally without network exposure.",
            buttonText = "Launch Image Studio",
            iconRes = R.drawable.ic_tab_image
        )
    }

    /**
     * Records an execution/launch of the given tool.
     */
    fun recordToolLaunch(context: Context, tool: TrackedTool) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt(KEY_PREFIX + tool.key, 0)
        prefs.edit().putInt(KEY_PREFIX + tool.key, currentCount + 1).apply()
    }

    /**
     * Returns the most used tool based on launch counts.
     * Defaults to IMAGE_UPSCALER if no tools have been used yet.
     */
    fun getMostUsedTool(context: Context): TrackedTool {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var maxCount = 0
        var topTool = TrackedTool.IMAGE_UPSCALER

        for (tool in TrackedTool.values()) {
            val count = prefs.getInt(KEY_PREFIX + tool.key, 0)
            if (count > maxCount) {
                maxCount = count
                topTool = tool
            }
        }
        return topTool
    }
}
