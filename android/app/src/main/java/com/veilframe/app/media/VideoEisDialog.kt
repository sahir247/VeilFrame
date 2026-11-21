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
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.veilframe.app.R
import java.io.File
import java.util.Locale

/**
 * Full-control Material 3 dialog for two-pass `libvidstab` Electronic Image Stabilization (EIS).
 * Exposes all Analyze (`vidstabdetect`), Stabilize (`vidstabtransform`), Border Fill, Interpolation,
 * Sharpening (`unsharp`), Virtual Tripod, and Fast 540p controls with real-time preset synchronization.
 */
object VideoEisDialog {

    private const val TAG = "VeilFrame.VideoEisDialog"

    fun show(
        context: Context,
        initialConfig: VideoEisConfig,
        isCurrentlyEnabled: Boolean,
        videoFile: File? = null,
        onApply: (VideoEisConfig) -> Unit,
        onDisable: () -> Unit,
        canDisable: Boolean = true
    ) {
        val density = context.resources.displayMetrics.density
        val padH = (22 * density).toInt()
        val padTop = (12 * density).toInt()
        val gapSm = (8 * density).toInt()
        val gapMd = (14 * density).toInt()

        var draft = initialConfig.clamped()
        var syncingPreset = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, gapSm)
        }

        val subtitleView = TextView(context).apply {
            text = "Two-pass FFmpeg libvidstab estimates camera motion and smooths the path. Review the result: scene cuts, moving subjects and low-texture footage can reduce tracking quality." +
                if (!canDisable) " Stabilization is already baked into this video; another pass processes that result. Reopen the original to undo it." else ""
            textSize = 12.5f
            alpha = 0.78f
            setPadding(0, 0, 0, gapMd)
        }
        root.addView(subtitleView)

        // Live In-Dialog Video Preview (if videoFile exists)
        var previewPlayer: ExoPlayer? = null
        if (videoFile != null && videoFile.exists()) {
            val previewFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (180 * density).toInt()
                ).apply {
                    setMargins(0, 0, 0, gapMd)
                }
                setBackgroundColor(0xFF101012.toInt())
            }

            val textureView = TextureView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            previewFrame.addView(textureView)

            try {
                previewPlayer = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                    setVideoTextureView(textureView)
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    prepare()
                    playWhenReady = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed creating in-dialog video preview: ${t.message}")
            }

            // Play / Pause overlay button
            val btnPlay = MaterialButton(context).apply {
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
                    previewPlayer?.let { p ->
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
            previewFrame.addView(btnPlay)

            val badge = TextView(context).apply {
                text = "SOURCE PREVIEW"
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x88000000.toInt())
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins((8 * density).toInt(), (8 * density).toInt(), 0, 0)
                }
            }
            previewFrame.addView(badge)
            root.addView(previewFrame)
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Dynamic updaters registered so clicking a preset updates all downstream controls consistently
        val controlUpdaters = mutableListOf<(VideoEisConfig) -> Unit>()
        var syncPresetChip: ((EisPresetProfile) -> Unit)? = null

        fun markCustomIfManual(updated: VideoEisConfig) {
            if (syncingPreset) return
            draft = updated.copy(presetProfile = EisPresetProfile.CUSTOM).clamped()
            syncPresetChip?.invoke(EisPresetProfile.CUSTOM)
        }

        // 1. Stabilization Profiles
        val presetOptions = listOf(
            "Handheld Walk" to EisPresetProfile.HANDHELD_WALK,
            "Action / Run" to EisPresetProfile.ACTION_SPORT,
            "Cinema Pan" to EisPresetProfile.GENTLE_CINEMA,
            "Tripod Lock" to EisPresetProfile.TRIPOD_LOCK,
            "Fast 540p" to EisPresetProfile.FAST_540P,
            "Custom" to EisPresetProfile.CUSTOM
        )
        val presetDescription = TextView(context).apply {
            text = draft.presetProfile.description
            textSize = 12f
            alpha = 0.72f
            setPadding(0, 0, 0, gapMd)
        }
        val (_, presetUpdater) = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Stabilization Profile",
            options = presetOptions,
            selected = draft.presetProfile,
            gapSm = gapSm,
            gapMd = gapSm / 2
        ) { selectedProfile ->
            if (syncingPreset) return@addChoiceChips
            presetDescription.text = selectedProfile.description
            if (selectedProfile != EisPresetProfile.CUSTOM) {
                syncingPreset = true
                try {
                    draft = selectedProfile.createConfig().clamped()
                    controlUpdaters.forEach { it(draft) }
                } finally {
                    syncingPreset = false
                }
            } else {
                draft = draft.copy(presetProfile = EisPresetProfile.CUSTOM)
            }
        }
        syncPresetChip = { p ->
            presetDescription.text = p.description
            presetUpdater(p)
        }
        scrollContent.addView(presetDescription)

        // 2. Analyze Pass (vidstabdetect)
        addSectionHeading(context, scrollContent, "Analyze Settings (Pass 1 — Motion Vector Scan)", gapMd, gapSm)

        val shakinessPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Shakiness Sensitivity",
            options = listOf("Low (1)" to 1, "Average (5)" to 5, "High (10)" to 10),
            selected = closestValue(draft.shakiness, VideoEisConfig.SHAKINESS_PRESETS),
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(shakiness = it)) }
        controlUpdaters += { cfg -> shakinessPair.second(closestValue(cfg.shakiness, VideoEisConfig.SHAKINESS_PRESETS)) }

        val accuracyPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Detection Accuracy",
            options = listOf("Fast (1)" to 1, "Average (8)" to 8, "High (15)" to 15),
            selected = closestValue(draft.accuracy, VideoEisConfig.ACCURACY_PRESETS),
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(accuracy = it)) }
        controlUpdaters += { cfg -> accuracyPair.second(closestValue(cfg.accuracy, VideoEisConfig.ACCURACY_PRESETS)) }

        val stepPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Search Step Size (Fine ↔ Fast)",
            options = listOf("1px Ultra" to 1, "6px Fine" to 6, "12px Med" to 12, "24px Fast" to 24, "32px Speed" to 32),
            selected = closestValue(draft.stepSize, VideoEisConfig.STEP_SIZE_PRESETS),
            gapSm = gapSm,
            gapMd = gapMd
        ) { markCustomIfManual(draft.copy(stepSize = it)) }
        controlUpdaters += { cfg -> stepPair.second(closestValue(cfg.stepSize, VideoEisConfig.STEP_SIZE_PRESETS)) }

        // 3. Stabilize Pass (vidstabtransform)
        addSectionHeading(context, scrollContent, "Stabilize Settings (Pass 2 — Path & Framing)", gapMd, gapSm)

        val smoothingRow = addSliderRow(
            context = context,
            parent = scrollContent,
            title = "Camera Path Smoothing",
            from = 0f,
            to = 30f,
            step = 1f,
            initial = draft.smoothing.toFloat(),
            formatter = { v -> "${v.toInt()} frames" },
            gapMd = gapSm
        ) { valInt ->
            markCustomIfManual(draft.copy(smoothing = valInt.toInt()))
        }
        controlUpdaters += { cfg ->
            smoothingRow.first.isEnabled = !cfg.tripodMode
            smoothingRow.second(cfg.smoothing.toFloat())
        }

        val zoomRow = addSliderRow(
            context = context,
            parent = scrollContent,
            title = "Additional Static Zoom",
            from = -5f,
            to = 30f,
            step = 1f,
            initial = draft.zoomPercent.toFloat(),
            formatter = { v ->
                val iv = v.toInt()
                if (iv > 0) "+$iv%" else "$iv%"
            },
            gapMd = gapSm
        ) { valFloat ->
            markCustomIfManual(draft.copy(zoomPercent = valFloat.toInt()))
        }
        controlUpdaters += { cfg -> zoomRow.second(cfg.zoomPercent.toFloat()) }

        val optZoomPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Adaptive Edge Zoom (optzoom)",
            options = listOf(
                "Off (Manual)" to EisOptZoomMode.OFF,
                "Optimal Static" to EisOptZoomMode.OPTIMAL_STATIC,
                "Adaptive Dynamic" to EisOptZoomMode.ADAPTIVE_DYNAMIC
            ),
            selected = draft.optZoomMode,
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(optZoomMode = it)) }
        controlUpdaters += { cfg -> optZoomPair.second(cfg.optZoomMode) }

        val algoPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Camera Path Solver",
            options = listOf(
                "Gaussian (Natural)" to EisCameraPathAlgorithm.GAUSS,
                "Optimal L1 (Steady)" to EisCameraPathAlgorithm.OPT
            ),
            selected = draft.cameraPathAlgorithm,
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(cameraPathAlgorithm = it)) }
        controlUpdaters += { cfg -> algoPair.second(cfg.cameraPathAlgorithm) }

        val borderPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Border Edge Fill",
            options = listOf("Keep Prev / Edges" to false, "Crop Black" to true),
            selected = draft.cropBlackBorders,
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(cropBlackBorders = it)) }
        controlUpdaters += { cfg -> borderPair.second(cfg.cropBlackBorders) }

        val interpPair = addChoiceChips(
            context = context,
            parent = scrollContent,
            title = "Sub-Pixel Warping Interpolation",
            options = listOf("Bilinear" to EisInterpolationMode.BILINEAR, "Bicubic (Crisp)" to EisInterpolationMode.BICUBIC),
            selected = draft.interpolation,
            gapSm = gapSm,
            gapMd = gapSm
        ) { markCustomIfManual(draft.copy(interpolation = it)) }
        controlUpdaters += { cfg -> interpPair.second(cfg.interpolation) }

        val sharpenRow = addSliderRow(
            context = context,
            parent = scrollContent,
            title = "Post-Warp Sharpening (Unsharp)",
            from = 0f,
            to = 1.5f,
            step = 0.1f,
            initial = draft.sharpenStrength,
            formatter = { v -> if (v < 0.05f) "Off" else String.format(Locale.US, "%.1fx", v) },
            gapMd = gapSm
        ) { valFloat ->
            markCustomIfManual(draft.copy(sharpenStrength = valFloat))
        }
        controlUpdaters += { cfg -> sharpenRow.second(cfg.sharpenStrength) }

        val tripodSwitch = addSwitchRow(
            context = context,
            parent = scrollContent,
            title = "Virtual Tripod Lock",
            subtitle = "Locks scene relative to opening frame (great for static talks or locked shots)",
            checked = draft.tripodMode,
            gapSm = gapSm
        ) { checked ->
            smoothingRow.first.isEnabled = !checked
            markCustomIfManual(draft.copy(tripodMode = checked))
        }
        controlUpdaters += { cfg ->
            if (tripodSwitch.isChecked != cfg.tripodMode) tripodSwitch.isChecked = cfg.tripodMode
            smoothingRow.first.isEnabled = !cfg.tripodMode
        }

        val mode540Switch = addSwitchRow(
            context = context,
            parent = scrollContent,
            title = "Fast 540p Pipeline",
            subtitle = "Downscales analysis & transform to max 540p height for much faster rendering on 4K/60fps clips",
            checked = draft.downscaleTo540p,
            gapSm = gapSm
        ) { checked ->
            markCustomIfManual(draft.copy(downscaleTo540p = checked))
        }
        controlUpdaters += { cfg ->
            if (mode540Switch.isChecked != cfg.downscaleTo540p) mode540Switch.isChecked = cfg.downscaleTo540p
        }

        smoothingRow.first.isEnabled = !draft.tripodMode

        val scrollView = ScrollView(context).apply {
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(scrollView)

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Pro Stabilization (2-Pass EIS)")
            .setView(root)
            .setPositiveButton(if (isCurrentlyEnabled) "Re-stabilize Footage" else "Stabilize Footage") { _, _ ->
                previewPlayer?.release()
                onApply(draft.clamped())
            }
            .setNegativeButton("Cancel") { _, _ ->
                previewPlayer?.release()
            }
            .setOnDismissListener {
                previewPlayer?.release()
            }

        if (isCurrentlyEnabled && canDisable) {
            builder.setNeutralButton("Turn Off EIS") { _, _ ->
                previewPlayer?.release()
                onDisable()
            }
        }
        builder.show()
    }

    private fun addSectionHeading(
        context: Context,
        parent: LinearLayout,
        text: String,
        topPad: Int,
        bottomPad: Int
    ) {
        parent.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topPad, 0, bottomPad)
        })
    }

    private fun <T> addChoiceChips(
        context: Context,
        parent: LinearLayout,
        title: String,
        options: List<Pair<String, T>>,
        selected: T,
        gapSm: Int,
        gapMd: Int,
        onSelected: (T) -> Unit
    ): Pair<ChipGroup, (T) -> Unit> {
        parent.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, gapSm / 2)
        })

        val group = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, gapMd)
        }
        val chipMap = linkedMapOf<Int, T>()
        options.forEach { (label, value) ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = (value == selected)
            }
            chipMap[chip.id] = value
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val firstId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            chipMap[firstId]?.let(onSelected)
        }
        parent.addView(group)

        val updater: (T) -> Unit = { newValue ->
            val targetId = chipMap.entries.firstOrNull { it.value == newValue }?.key
            if (targetId != null && group.checkedChipId != targetId) {
                group.check(targetId)
            }
        }
        return group to updater
    }

    private fun addSliderRow(
        context: Context,
        parent: LinearLayout,
        title: String,
        from: Float,
        to: Float,
        step: Float,
        initial: Float,
        formatter: (Float) -> String,
        gapMd: Int,
        onChanged: (Float) -> Unit
    ): Pair<Slider, (Float) -> Unit> {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val titleTv = TextView(context).apply {
            text = title
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valTv = TextView(context).apply {
            text = formatter(initial)
            textSize = 12.5f
            alpha = 0.85f
        }
        header.addView(titleTv)
        header.addView(valTv)
        parent.addView(header)

        val clampedInitial = initial.coerceIn(from, to)
        val slider = Slider(context).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            value = clampedInitial
            setPadding(0, 0, 0, gapMd)
        }
        slider.addOnChangeListener { _, value, fromUser ->
            valTv.text = formatter(value)
            if (fromUser) onChanged(value)
        }
        parent.addView(slider)

        val updater: (Float) -> Unit = { newVal ->
            val safe = newVal.coerceIn(from, to)
            slider.value = safe
            valTv.text = formatter(safe)
        }
        return slider to updater
    }

    private fun addSwitchRow(
        context: Context,
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        gapSm: Int,
        onChanged: (Boolean) -> Unit
    ): MaterialSwitch {
        val switchView = MaterialSwitch(context).apply {
            text = "$title\n$subtitle"
            textSize = 12.5f
            isChecked = checked
            setPadding(0, gapSm / 2, 0, gapSm / 2)
        }
        switchView.setOnCheckedChangeListener { _, isChecked ->
            onChanged(isChecked)
        }
        parent.addView(switchView)
        return switchView
    }

    private fun closestValue(value: Int, presets: List<Int>): Int {
        return presets.minByOrNull { kotlin.math.abs(it - value) } ?: presets.first()
    }
}

