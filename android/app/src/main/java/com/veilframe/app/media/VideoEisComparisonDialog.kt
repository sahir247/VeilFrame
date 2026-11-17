package com.veilframe.app.media

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import java.io.File
import java.util.Locale

/**
 * Dedicated Before/After video comparison dialog for verified EIS stabilization review.
 * Allows instant A/B switching between the raw shaky original and the stabilized result
 * with frame-synchronized playback and parameter diagnostics before accepting.
 */
object VideoEisComparisonDialog {

    private const val TAG = "VeilFrame.EisCompare"

    fun show(
        context: Context,
        originalFile: File,
        stabilizedFile: File,
        config: VideoEisConfig,
        onAccept: () -> Unit,
        onDiscard: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padTop = (12 * density).toInt()
        val gapSm = (8 * density).toInt()
        val gapMd = (14 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, gapSm)
        }

        // Subtitle explanation
        val subtitleView = TextView(context).apply {
            text = "Compare the raw shaky original against the two-pass stabilized result before applying."
            textSize = 12.5f
            alpha = 0.78f
            setPadding(0, 0, 0, gapMd)
        }
        root.addView(subtitleView)

        // Comparison Video View container
        val playerFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (210 * density).toInt()
            ).apply {
                setMargins(0, 0, 0, gapSm)
            }
            setBackgroundColor(0xFF0F0F11.toInt())
        }

        val textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        playerFrame.addView(textureView)

        var exoPlayer: ExoPlayer? = null
        val originalUri = Uri.fromFile(originalFile)
        val stabilizedUri = Uri.fromFile(stabilizedFile)
        var isShowingStabilized = true

        try {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(stabilizedUri))
                setVideoTextureView(textureView)
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed initializing comparison player: ${t.message}")
        }

        // Status badge (STABILIZED vs RAW ORIGINAL)
        val statusBadge = TextView(context).apply {
            text = "STABILIZED (SMOOTH)"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x991E88E5.toInt()) // subtle blue badge
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                setMargins((10 * density).toInt(), (10 * density).toInt(), 0, 0)
            }
        }
        playerFrame.addView(statusBadge)

        // Play / Pause center toggle
        val btnPlayPause = MaterialButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (44 * density).toInt(),
                (44 * density).toInt(),
                Gravity.CENTER
            )
            setIconResource(R.drawable.ic_action_pause)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            cornerRadius = (22 * density).toInt()
            setBackgroundColor(0x77000000.toInt())
            setOnClickListener {
                exoPlayer?.let { p ->
                    if (p.isPlaying) {
                        p.pause()
                        setIconResource(R.drawable.ic_action_play)
                    } else {
                        p.play()
                        setIconResource(R.drawable.ic_action_pause)
                    }
                }
            }
        }
        playerFrame.addView(btnPlayPause)
        root.addView(playerFrame)

        // A/B Switch Chips: [ Stabilized Result ] | [ Raw Original ]
        val compareChipGroup = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, gapSm)
        }

        fun switchVideo(showStabilized: Boolean) {
            if (isShowingStabilized == showStabilized) return
            isShowingStabilized = showStabilized
            exoPlayer?.let { p ->
                val currentPos = p.currentPosition
                val isPlaying = p.isPlaying
                val targetUri = if (showStabilized) stabilizedUri else originalUri
                p.setMediaItem(MediaItem.fromUri(targetUri))
                p.prepare()
                p.seekTo(currentPos)
                p.playWhenReady = isPlaying
            }
            if (showStabilized) {
                statusBadge.text = "STABILIZED (SMOOTH)"
                statusBadge.setBackgroundColor(0x991E88E5.toInt())
            } else {
                statusBadge.text = "ORIGINAL (RAW SHAKY)"
                statusBadge.setBackgroundColor(0x99E53935.toInt())
            }
        }

        val chipStabilized = Chip(context).apply {
            text = "Stabilized (Smooth)"
            isCheckable = true
            isChecked = true
            setOnClickListener { switchVideo(true) }
        }
        val chipOriginal = Chip(context).apply {
            text = "Original (Shaky)"
            isCheckable = true
            isChecked = false
            setOnClickListener { switchVideo(false) }
        }
        compareChipGroup.addView(chipStabilized)
        compareChipGroup.addView(chipOriginal)
        root.addView(compareChipGroup)

        // Diagnostic summary card
        val summaryCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x11FFFFFF.toInt())
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, gapMd)
            }
        }

        val summaryTitle = TextView(context).apply {
            text = "Stabilization Summary"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.9f
        }
        val origKb = originalFile.length() / 1024
        val stabKb = stabilizedFile.length() / 1024
        val summaryDetails = TextView(context).apply {
            text = "Profile: ${config.presetProfile.label} • Smoothing: ${config.smoothing} frames\n" +
                    "Camera Path: ${config.cameraPathAlgorithm.label} • Zoom: ${config.zoomPercent}%\n" +
                    "Original: ${origKb} KB → Stabilized: ${stabKb} KB"
            textSize = 11.5f
            alpha = 0.72f
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        summaryCard.addView(summaryTitle)
        summaryCard.addView(summaryDetails)
        root.addView(summaryCard)

        var decisionHandled = false
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Stabilization Review")
            .setView(root)
            .setPositiveButton("Use Stabilized Video") { _, _ ->
                decisionHandled = true
                exoPlayer?.release()
                onAccept()
            }
            .setNegativeButton("Revert to Original") { _, _ ->
                decisionHandled = true
                exoPlayer?.release()
                onDiscard()
            }
            .setOnDismissListener {
                exoPlayer?.release()
                // Back/outside dismiss is also a discard: never orphan a large MP4.
                if (!decisionHandled) {
                    decisionHandled = true
                    onDiscard()
                }
            }
            .create()

        dialog.show()
    }
}

