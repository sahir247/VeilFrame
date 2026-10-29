package com.veilframe.app.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.veilframe.app.MainActivity
import java.util.Locale

/**
 * Handles incoming launch and share intents (ACTION_SEND, ACTION_VIEW, extra destinations)
 * and dispatches them to the appropriate studio or workspace.
 */
object MainActivityIntentHandler {

    fun handle(activity: MainActivity, intent: Intent?) {
        if (intent == null) return
        val dest = intent.getStringExtra("extra_navigate_destination")
        if (!dest.isNullOrEmpty()) {
            when (dest) {
                "IMAGE_UPSCALER" -> activity.navigationController.showImageUpscalerScreen()
                "MOTION_LAB" -> {
                    activity.navigationController.showMotionLabScreen()
                    val motionUri = intent.data ?: intent.getStringExtra("extra_video_uri")?.let { Uri.parse(it) }
                    if (motionUri != null) {
                        activity.setMotionLabSource(motionUri)
                    }
                }
                "VIDEO_STUDIO" -> activity.navigationController.showVideoStudioScreen()
            }
            return
        }
        val action = intent.action
        val type = intent.type ?: ""
        val uri: Uri? = if (action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.data
        } else if (action == Intent.ACTION_VIEW) {
            intent.data
        } else {
            null
        }

        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            val displayName = DocumentFile.fromSingleUri(activity, uri)?.name ?: uri.lastPathSegment ?: ""
            val nameLower = displayName.lowercase(Locale.ROOT)
            val isMarkdown = nameLower.endsWith(".md") ||
                    nameLower.endsWith(".markdown") ||
                    nameLower.endsWith(".mdown") ||
                    nameLower.endsWith(".mkdn") ||
                    type == "text/markdown" ||
                    (type == "text/plain" && (nameLower.endsWith(".md") || nameLower.endsWith(".markdown")))

            if (isMarkdown) {
                activity.openMarkdownViewer(uri, displayName)
            } else if (type.startsWith("video/") || nameLower.endsWith(".mp4") || nameLower.endsWith(".webm") || nameLower.endsWith(".mkv") || nameLower.endsWith(".mov") || nameLower.endsWith(".avi")) {
                activity.openVideoStudio()
                activity.videoStudioController.handleVideoSelected(uri)
            } else if (type.startsWith("image/") || nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".webp") || nameLower.endsWith(".heic") || nameLower.endsWith(".heif") || nameLower.endsWith(".avif") || nameLower.endsWith(".bmp") || nameLower.endsWith(".tiff") || nameLower.endsWith(".tif") || nameLower.endsWith(".gif")) {
                activity.openImageStudio()
                activity.imageStudioController.handleImageSelected(uri)
            }
        }
    }
}
