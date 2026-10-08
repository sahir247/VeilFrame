package com.veilframe.app.navigation

import androidx.annotation.DrawableRes
import com.veilframe.app.R

/**
 * Functional category organizing VeilFrame workspaces.
 */
enum class WorkspaceCategory(val title: String) {
    CREATE_EDIT("Create & Edit"),
    PRIVACY("Privacy"),
    ANALYZE("Analyze"),
    DEVELOPER("Developer")
}

/**
 * Maturity and stability classification for user-facing workspaces.
 */
enum class WorkspaceStatus(val label: String) {
    STABLE("Stable"),
    BETA("Beta"),
    ALPHA("Alpha")
}

/**
 * Authoritative catalogue of user-facing workspaces across VeilFrame Mobile.
 * Decoupled from legacy batch-execution [com.veilframe.app.tools.ToolMode].
 */
enum class WorkspaceRoute(
    val id: String,
    val title: String,
    val description: String,
    val category: WorkspaceCategory,
    val status: WorkspaceStatus,
    val aliases: List<String>,
    @DrawableRes val iconRes: Int
) {
    IMAGE_STUDIO(
        id = "image_studio",
        title = "Image Studio",
        description = "Advanced editing, cropping, color adjustment, and compression",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("photo", "picture", "edit", "crop", "color", "denoise", "filter", "passport"),
        iconRes = R.drawable.ic_tab_image
    ),
    VIDEO_STUDIO(
        id = "video_studio",
        title = "Video Studio",
        description = "Trimming, speed adjustment, rotation, audio stripping, and WhatsApp compression",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("video", "clip", "trim", "compress", "whatsapp", "movie", "audio"),
        iconRes = R.drawable.ic_tab_video
    ),
    QR_STUDIO(
        id = "qr_studio",
        title = "QR Code Studio",
        description = "Artistic QR generator with 11 styles, CameraX scanner, and payload parser",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("qr", "barcode", "scan", "generator", "scanner", "wifi", "upi"),
        iconRes = R.drawable.ic_tool_qr
    ),
    DOCUMENT_SCANNER(
        id = "document_scanner",
        title = "Document Scanner",
        description = "Multi-page scan, automatic corner detection, perspective fix, and PDF export",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.BETA,
        aliases = listOf("pdf", "document", "scan", "page", "book", "receipt", "contract", "paper"),
        iconRes = R.drawable.ic_camera
    ),
    BACKGROUND_REMOVER(
        id = "background_remover",
        title = "Background Remover",
        description = "Offline subject segmentation, transparent cutout, and interactive compare",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.ALPHA,
        aliases = listOf("remove bg", "background", "cutout", "transparent", "segmentation", "matting", "isolate"),
        iconRes = R.drawable.ic_crop
    ),
    IMAGE_UPSCALER(
        id = "image_upscaler",
        title = "AI Image Upscaler",
        description = "Offline AI super-resolution and restoration",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("upscale", "super-res", "enhance", "ai", "hd", "resolution", "clarity", "restore"),
        iconRes = R.drawable.ic_resize
    ),
    IMAGE_CLEANER(
        id = "image_cleaner",
        title = "Image Cleaner",
        description = "Strip EXIF, GPS coordinates, camera serials, and thumbnail traces",
        category = WorkspaceCategory.PRIVACY,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("metadata", "exif", "gps", "privacy", "sanitize", "clean", "scrub"),
        iconRes = R.drawable.ic_exif
    ),
    VIDEO_CLEANER(
        id = "video_cleaner",
        title = "Video Cleaner",
        description = "Purge container metadata, chapters, encoder tags, and PRNU noise traces",
        category = WorkspaceCategory.PRIVACY,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("video privacy", "metadata", "prnu", "sanitize", "clean", "scrub"),
        iconRes = R.drawable.ic_action_clear
    ),
    IMAGE_QUALITY(
        id = "image_quality",
        title = "Image Quality",
        description = "Evaluate sharpness, blur, sensor noise, dynamic range, and compression",
        category = WorkspaceCategory.ANALYZE,
        status = WorkspaceStatus.BETA,
        aliases = listOf("sharpness", "blur", "quality", "noise", "exposure", "contrast", "analyze"),
        iconRes = R.drawable.ic_visibility
    ),
    FOLDER_SCANNER(
        id = "folder_scanner",
        title = "Folder Scanner",
        description = "Recursive directory inspection, duplicate detection, and file type auditing",
        category = WorkspaceCategory.ANALYZE,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("folder", "directory", "storage", "duplicates", "audit", "disk"),
        iconRes = R.drawable.ic_tab_folder
    ),
    AI_BUNDLE(
        id = "ai_bundle",
        title = "AI Context Bundler",
        description = "Compile codebases and docs into secure, token-budgeted .aibundle archives",
        category = WorkspaceCategory.DEVELOPER,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("bundle", "ai", "llm", "context", "prompt", "token", "source"),
        iconRes = R.drawable.ic_tab_bundle
    ),
    MARKDOWN_STUDIO(
        id = "markdown_studio",
        title = "Markdown Studio",
        description = "Offline markdown previewer, report viewer, and forensic manifest reader",
        category = WorkspaceCategory.DEVELOPER,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("markdown", "md", "preview", "viewer", "report", "docs"),
        iconRes = R.drawable.ic_toc
    ),
    PROVENANCE(
        id = "provenance",
        title = "Provenance & Verify",
        description = "Ed25519 digital signature validation, SHA-256 integrity, and audit manifests",
        category = WorkspaceCategory.DEVELOPER,
        status = WorkspaceStatus.STABLE,
        aliases = listOf("provenance", "verify", "signature", "ed25519", "sha256", "hash", "cert"),
        iconRes = R.drawable.ic_check_circle
    ),
    MOTION_LAB(
        id = "motion_lab",
        title = "Motion Lab",
        description = "Optical-flow frame interpolation — render 2×/4× smoother motion from any video",
        category = WorkspaceCategory.CREATE_EDIT,
        status = WorkspaceStatus.ALPHA,
        aliases = listOf("optical flow", "motion", "interpolation", "slow motion", "frame rate", "fps", "frames", "smooth"),
        iconRes = R.drawable.ic_speed
    );

    companion object {
        /**
         * Search workspaces by title, description, or keyword synonyms.
         */
        fun search(query: String): List<WorkspaceRoute> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return values().toList()
            return values().filter { route ->
                route.title.lowercase().contains(q) ||
                route.description.lowercase().contains(q) ||
                route.aliases.any { it.contains(q) }
            }
        }

        fun forCategory(category: WorkspaceCategory): List<WorkspaceRoute> {
            return values().filter { it.category == category }
        }
    }
}
