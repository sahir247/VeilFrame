package com.veilframe.app.ui.library

import android.app.Activity
import android.content.ContentUris
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutLibraryBinding
import com.veilframe.app.document.DocumentSession
import com.veilframe.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates a library media item with its optional MediaStore content URI for Scoped Storage compatibility.
 */
data class LibraryItem(
    val file: File,
    val contentUri: Uri? = null
)

/**
 * Coordinates querying, filtering, and dynamic UI card rendering for the Library screen,
 * including persistent document scanner sessions and exported media artifacts.
 */
class LibraryCoordinator(
    private val activity: Activity,
    private val binding: LayoutLibraryBinding,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onResumeSession: (sessionId: String) -> Unit,
    private val onShareFile: (file: File, mimeType: String, contentUri: Uri?) -> Unit,
    private val onExploreTools: () -> Unit
) {

    fun init() {
        binding.btnLibraryExploreTools.setOnClickListener {
            onExploreTools()
        }
        binding.chipGroupLibraryFilter.setOnCheckedStateChangeListener { _, _ ->
            refresh()
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val exportDirs = listOf(
                File(activity.filesDir, "exports"),
                File(activity.cacheDir, "exports"),
                File(activity.cacheDir, "studio_exports"),
                File(activity.cacheDir, "scanned_docs"),
                File(activity.filesDir, "cv/exports")
            )

            // Merge MediaStore saves (Pictures/Movies VeilFrame) with internal app exports, capturing Content URIs
            val mediaStoreItems = mutableListOf<LibraryItem>()
            try {
                activity.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA),
                    "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                    arrayOf("%/VeilFrame/%"),
                    null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val dataIdx = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    if (dataIdx >= 0) {
                        while (c.moveToNext()) {
                            val path = c.getString(dataIdx) ?: continue
                            val uri = if (idIdx >= 0) {
                                val id = c.getLong(idIdx)
                                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                            } else null
                            mediaStoreItems += LibraryItem(File(path), contentUri = uri)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("VeilFrame.Library", "MediaStore merge unavailable: ${e.message}")
            }

            val internalItems = exportDirs
                .filter { it.exists() && it.isDirectory }
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .map { LibraryItem(it, contentUri = null) }

            val allItems = (internalItems + mediaStoreItems)
                .filter { it.file.isFile && it.file.length() > 0 }
                .distinctBy { it.file.absolutePath }
                .sortedByDescending { it.file.lastModified() }

            val savedSessions = DocumentSession.listSavedSessions(activity)
            val checkedFilterId = binding.chipGroupLibraryFilter.checkedChipId
            val filteredItems = when (checkedFilterId) {
                R.id.chipLibraryImages -> allItems.filter { item ->
                    val ext = item.file.extension.lowercase()
                    ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
                }
                R.id.chipLibraryVideos -> allItems.filter { item ->
                    val ext = item.file.extension.lowercase()
                    ext in listOf("mp4", "mkv", "webm", "mov", "avi")
                }
                R.id.chipLibraryDocs -> allItems.filter { item ->
                    val ext = item.file.extension.lowercase()
                    ext in listOf("pdf", "md", "txt", "json", "aibundle")
                }
                R.id.chipLibraryQr -> allItems.filter { item ->
                    item.file.name.lowercase().contains("qr") || item.file.extension.lowercase() == "svg"
                }
                else -> allItems
            }

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                val density = activity.resources.displayMetrics.density
                val margin12 = (12 * density).toInt()

                // Theme-aware colors that adapt to Material You dynamic tone & palettes
                val colorSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, activity.getColor(R.color.vf_surface))
                val colorStroke = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutlineVariant, activity.getColor(R.color.vf_surface_variant))
                val colorPrimary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, activity.getColor(R.color.vf_primary))
                val colorTextPrimary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, activity.getColor(R.color.vf_text_primary))
                val colorTextSecondary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, activity.getColor(R.color.vf_text_secondary))

                // Render active document scanner sessions
                if (savedSessions.isNotEmpty()) {
                    binding.containerLibrarySessionsSection.visibility = View.VISIBLE
                    binding.containerLibrarySessions.removeAllViews()
                    for (session in savedSessions) {
                        val card = MaterialCardView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = margin12
                            }
                            radius = 16 * density
                            cardElevation = 1 * density
                            setCardBackgroundColor(colorSurface)
                            strokeColor = colorStroke
                            strokeWidth = (1 * density).toInt()
                        }

                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(margin12, margin12, margin12, margin12)
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val icon = ImageView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                                marginEnd = margin12
                            }
                            setImageResource(R.drawable.ic_camera)
                            setColorFilter(colorPrimary)
                        }

                        val infoCol = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val nameText = TextView(activity).apply {
                            text = session.title
                            setTextColor(colorTextPrimary)
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }

                        val metaText = TextView(activity).apply {
                            val timeAgo = DateUtils.getRelativeTimeSpanString(session.lastModifiedAt)
                            text = "${session.pageCount} pages • Edited $timeAgo"
                            setTextColor(colorTextSecondary)
                            textSize = 11f
                        }

                        infoCol.addView(nameText)
                        infoCol.addView(metaText)

                        val btnContinue = MaterialButton(
                            activity,
                            null,
                            androidx.appcompat.R.attr.borderlessButtonStyle
                        ).apply {
                            text = "Continue"
                            textSize = 12f
                            setTextColor(colorPrimary)
                            minHeight = (48 * density).toInt()
                            contentDescription = "Continue session ${session.title}"
                            setOnClickListener {
                                onResumeSession(session.sessionId)
                            }
                        }

                        val btnDelete = MaterialButton(
                            activity,
                            null,
                            androidx.appcompat.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                            setIconResource(R.drawable.ic_action_clear)
                            iconTint = ColorStateList.valueOf(colorTextSecondary)
                            contentDescription = "Delete session ${session.title}"
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                    .setTitle("Delete Document Session")
                                    .setMessage("Delete \"${session.title}\" and all scanned pages permanently? This action cannot be undone.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        DocumentSession.deleteSession(activity, session.sessionId)
                                        refresh()
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }

                        row.addView(icon)
                        row.addView(infoCol)
                        row.addView(btnContinue)
                        row.addView(btnDelete)
                        card.addView(row)
                        binding.containerLibrarySessions.addView(card)
                    }
                } else {
                    binding.containerLibrarySessionsSection.visibility = View.GONE
                }

                val hasAnyContent = savedSessions.isNotEmpty() || filteredItems.isNotEmpty()
                if (!hasAnyContent) {
                    binding.containerLibraryEmpty.visibility = View.VISIBLE
                    binding.containerLibraryItems.visibility = View.GONE
                } else {
                    binding.containerLibraryEmpty.visibility = View.GONE
                    binding.containerLibraryItems.visibility = if (filteredItems.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.containerLibraryItems.removeAllViews()

                    for (item in filteredItems) {
                        val file = item.file
                        val card = MaterialCardView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = margin12
                            }
                            radius = 16 * density
                            cardElevation = 1 * density
                            setCardBackgroundColor(colorSurface)
                            strokeColor = colorStroke
                            strokeWidth = (1 * density).toInt()
                            isClickable = true
                            isFocusable = true
                        }

                        val row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(margin12, margin12, margin12, margin12)
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val icon = ImageView(activity).apply {
                            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                                marginEnd = margin12
                            }
                            val iconRes = when (file.extension.lowercase()) {
                                "jpg", "jpeg", "png", "webp", "bmp" -> R.drawable.ic_tab_image
                                "mp4", "mkv", "webm", "mov" -> R.drawable.ic_tab_video
                                "pdf", "md", "txt" -> R.drawable.ic_toc
                                else -> if (file.name.contains("qr", ignoreCase = true)) R.drawable.ic_tool_qr else R.drawable.ic_check_circle
                            }
                            setImageResource(iconRes)
                            setColorFilter(colorPrimary)
                        }

                        val infoCol = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val nameText = TextView(activity).apply {
                            text = file.name
                            setTextColor(colorTextPrimary)
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }

                        val metaText = TextView(activity).apply {
                            val sizeStr = safStorageManager.formatBytes(file.length())
                            val dateStr = DateFormat.format("MMM d, yyyy HH:mm", file.lastModified())
                            text = "$sizeStr • $dateStr"
                            setTextColor(colorTextSecondary)
                            textSize = 11f
                        }

                        infoCol.addView(nameText)
                        infoCol.addView(metaText)

                        val btnShare = MaterialButton(
                            activity,
                            null,
                            androidx.appcompat.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                            setIconResource(R.drawable.ic_action_share)
                            iconTint = ColorStateList.valueOf(colorPrimary)
                            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                            contentDescription = "Share ${file.name}"
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                val mime = safStorageManager.getExportMimeType(file)
                                onShareFile(file, mime, item.contentUri)
                            }
                        }

                        val btnDelete = MaterialButton(
                            activity,
                            null,
                            androidx.appcompat.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                            setIconResource(R.drawable.ic_action_clear)
                            iconTint = ColorStateList.valueOf(colorTextSecondary)
                            contentDescription = "Delete ${file.name}"
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                    .setTitle("Delete File")
                                    .setMessage("Delete \"${file.name}\" permanently? This action cannot be undone.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                if (item.contentUri != null) {
                                                    activity.contentResolver.delete(item.contentUri, null, null)
                                                }
                                                if (file.exists()) {
                                                    file.delete()
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.w("VeilFrame.Library", "Delete failed: ${e.message}")
                                            }
                                            refresh()
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }

                        card.setOnClickListener {
                            val mime = safStorageManager.getExportMimeType(file)
                            onShareFile(file, mime, item.contentUri)
                        }

                        row.addView(icon)
                        row.addView(infoCol)
                        row.addView(btnShare)
                        row.addView(btnDelete)
                        card.addView(row)
                        binding.containerLibraryItems.addView(card)
                    }
                }
            }
        }
    }
}
