package com.veilframe.app.media

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.motion.ReframeTarget
import com.veilframe.app.cv.motion.VideoReframeEngine

/**
 * Source-aware Material 3 target aspect chooser for Smart AI Auto-Reframe.
 *
 * Inspects source dimensions ([sourceWidth]x[sourceHeight]), disables redundant ratios that already
 * match the source aspect within 2.5% (e.g. prevents offering 9:16 reframe on a 9:16 portrait clip),
 * previews the exact cropped output resolution, and recommends meaningful targets.
 */
object VideoReframeDialog {

    fun show(
        context: Context,
        sourceWidth: Int,
        sourceHeight: Int,
        currentAspectLabel: String,
        hasActiveReframe: Boolean,
        onSelectTarget: (ReframeTarget) -> Unit,
        onClearReframe: () -> Unit
    ) {
        val safeW = sourceWidth.coerceAtLeast(2)
        val safeH = sourceHeight.coerceAtLeast(2)
        val options = VideoReframeEngine.availableTargetsForSource(safeW, safeH)
        val selectable = options.filterNot { it.isAlreadySourceAspect }

        if (selectable.isEmpty()) {
            Toast.makeText(context, "Source geometry has no alternate reframe ratios", Toast.LENGTH_SHORT).show()
            return
        }

        val density = context.resources.displayMetrics.density
        val padH = (22 * density).toInt()
        val padTop = (12 * density).toInt()
        val gapSm = (8 * density).toInt()
        val gapMd = (14 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, gapSm)
        }

        val sourceMatchesRatio = options.firstOrNull { it.isAlreadySourceAspect }?.target?.ratioLabel
        val trackingMode = if (CvRuntime.isNativeAvailable) "AI subject tracking active" else "Static geometry fallback"
        val sourceSummary = buildString {
            append("Source: ${safeW}×${safeH}")
            if (sourceMatchesRatio != null) {
                append(" ($sourceMatchesRatio)")
            }
            append(" • $trackingMode computes a smooth panning crop across frames.")
        }
        root.addView(TextView(context).apply {
            text = sourceSummary
            textSize = 12.5f
            alpha = 0.8f
            setPadding(0, 0, 0, gapMd)
        })

        val currentTarget = selectable.firstOrNull {
            currentAspectLabel.contains(it.target.ratioLabel)
        }?.target
        var chosenTarget = currentTarget
            ?: options.firstOrNull { it.isRecommended }?.target
            ?: selectable.first().target

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val idToTarget = linkedMapOf<Int, ReframeTarget>()

        for (opt in options) {
            val id = View.generateViewId()
            val rb = MaterialRadioButton(context).apply {
                this.id = id
                textSize = 13f
                setPadding(gapSm, gapSm / 2, 0, gapSm / 2)
                if (opt.isAlreadySourceAspect) {
                    isEnabled = false
                    isChecked = false
                    alpha = 0.45f
                    text = "${opt.target.displayTitle} (${safeW}×${safeH}) — Already source aspect (skipped)"
                } else {
                    val recTag = if (opt.isRecommended) " ★ Recommended" else ""
                    text = "${opt.target.displayTitle} (${opt.outputWidth}×${opt.outputHeight})$recTag\n${opt.target.subtitle}"
                    isChecked = (opt.target == chosenTarget)
                    idToTarget[id] = opt.target
                }
            }
            radioGroup.addView(rb)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            idToTarget[checkedId]?.let { chosenTarget = it }
        }
        root.addView(radioGroup)

        val scrollView = ScrollView(context).apply {
            addView(root)
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Smart AI Auto-Reframe Target")
            .setView(scrollView)
            .setPositiveButton("Reframe Video") { _, _ ->
                onSelectTarget(chosenTarget)
            }
            .setNegativeButton("Cancel", null)

        if (hasActiveReframe) {
            builder.setNeutralButton("Reset Original") { _, _ ->
                onClearReframe()
            }
        }
        builder.show()
    }
}

