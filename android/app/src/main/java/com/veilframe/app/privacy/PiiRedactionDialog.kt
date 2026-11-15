package com.veilframe.app.privacy

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * Granular redactable item representation allowing individual target selection
 * (e.g., hiding one specific email or phone number or face while leaving others visible).
 */
data class PiiRedactableItem(
    val id: String,
    val piiClass: PiiClass,
    val label: String,
    val subtext: String = "",
    val defaultChecked: Boolean = (piiClass != PiiClass.PERSON)
)

/**
 * Shared Material 3 selection dialog for both Image Studio and Video Studio PII auto-redaction.
 * Provides fine-grained item-level checkboxes so users can inspect and toggle individual
 * sensitive items (e.g. selectively hiding one phone number or face) or toggle entire categories.
 */
object PiiRedactionDialog {

    fun showItemSelectionDialog(
        context: Context,
        title: String,
        subtitle: String?,
        items: List<PiiRedactableItem>,
        availableStyles: List<PiiRedactionStyle>,
        initialStyle: PiiRedactionStyle,
        hasActiveRedactions: Boolean,
        onApply: (Set<String>, PiiRedactionStyle) -> Unit,
        onClear: (() -> Unit)? = null
    ) {
        val density = context.resources.displayMetrics.density
        val padH = (22 * density).toInt()
        val padTop = (12 * density).toInt()
        val gapSm = (8 * density).toInt()
        val gapMd = (14 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, gapSm)
        }

        if (!subtitle.isNullOrBlank()) {
            root.addView(TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                alpha = 0.78f
                setPadding(0, 0, 0, gapSm)
            })
        }

        val itemCheckBoxes = linkedMapOf<String, MaterialCheckBox>()
        val categoryMasterBoxes = linkedMapOf<PiiClass, MaterialCheckBox>()

        // Quick action row: Select All / Deselect All
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, gapSm)
        }
        val btnSelectAll = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Select All"
            textSize = 12f
            setPadding(0, 0, (12 * density).toInt(), 0)
            setOnClickListener {
                for (cb in itemCheckBoxes.values) cb.isChecked = true
                for (cb in categoryMasterBoxes.values) cb.isChecked = true
            }
        }
        val btnDeselectAll = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Deselect All"
            textSize = 12f
            setOnClickListener {
                for (cb in itemCheckBoxes.values) cb.isChecked = false
                for (cb in categoryMasterBoxes.values) cb.isChecked = false
            }
        }
        actionRow.addView(btnSelectAll)
        actionRow.addView(btnDeselectAll)
        root.addView(actionRow)

        root.addView(TextView(context).apply {
            text = "Detected Targets (${items.size})"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, gapSm / 2, 0, gapSm)
        })

        val grouped = items.groupBy { it.piiClass }
        val orderedClasses = grouped.keys.sortedBy { it.ordinal }

        for (piiClass in orderedClasses) {
            val classItems = grouped[piiClass] ?: continue

            val groupCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0x0CFFFFFF.toInt())
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, gapSm)
                }
            }

            // Master category checkbox
            val categoryMaster = MaterialCheckBox(context).apply {
                text = "${piiClass.label} (${classItems.size})"
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                isChecked = classItems.all { it.defaultChecked }
            }
            categoryMasterBoxes[piiClass] = categoryMaster
            groupCard.addView(categoryMaster)

            // Child item checkboxes
            val childBoxes = mutableListOf<MaterialCheckBox>()
            var isUpdatingFromMaster = false

            for (item in classItems) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((24 * density).toInt(), 0, 0, (4 * density).toInt())
                }
                val cb = MaterialCheckBox(context).apply {
                    text = item.label
                    textSize = 12.5f
                    isChecked = item.defaultChecked
                }
                itemCheckBoxes[item.id] = cb
                childBoxes.add(cb)
                itemRow.addView(cb)

                if (item.subtext.isNotBlank()) {
                    val subTv = TextView(context).apply {
                        text = item.subtext
                        textSize = 11.5f
                        alpha = 0.72f
                        setPadding((32 * density).toInt(), 0, 0, (4 * density).toInt())
                    }
                    itemRow.addView(subTv)
                }
                groupCard.addView(itemRow)

                cb.setOnCheckedChangeListener { _, _ ->
                    if (!isUpdatingFromMaster) {
                        val allChecked = childBoxes.all { it.isChecked }
                        categoryMaster.isChecked = allChecked
                    }
                }
            }

            categoryMaster.setOnCheckedChangeListener { _, isChecked ->
                isUpdatingFromMaster = true
                for (child in childBoxes) {
                    child.isChecked = isChecked
                }
                isUpdatingFromMaster = false
            }

            root.addView(groupCard)
        }

        root.addView(TextView(context).apply {
            text = "Redaction Mode"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, gapMd, 0, gapSm)
        })

        var chosenStyle = availableStyles.firstOrNull { it == initialStyle } ?: availableStyles.first()
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val styleIds = linkedMapOf<Int, PiiRedactionStyle>()
        for (style in availableStyles) {
            val id = View.generateViewId()
            styleIds[id] = style
            val rb = MaterialRadioButton(context).apply {
                this.id = id
                text = style.label
                isChecked = (style == chosenStyle)
            }
            radioGroup.addView(rb)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            chosenStyle = styleIds[checkedId] ?: chosenStyle
        }
        root.addView(radioGroup)

        val scrollView = ScrollView(context).apply {
            addView(root)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Redact Selected") { _, _ ->
                val selectedIds = itemCheckBoxes.filterValues { it.isChecked }.keys
                if (selectedIds.isEmpty()) {
                    Toast.makeText(context, "Select at least one PII target to redact", Toast.LENGTH_SHORT).show()
                } else {
                    onApply(selectedIds, chosenStyle)
                }
            }
            .setNegativeButton("Cancel", null)

        if (hasActiveRedactions && onClear != null) {
            builder.setNeutralButton("Clear Active") { _, _ ->
                onClear()
            }
        }

        builder.show()
    }

    /**
     * Legacy category-level adapter for backward compatibility.
     */
    fun showSelectionDialog(
        context: Context,
        title: String,
        subtitle: String?,
        countsByClass: Map<PiiClass, Int>,
        detailExamples: Map<PiiClass, String> = emptyMap(),
        availableStyles: List<PiiRedactionStyle>,
        initialStyle: PiiRedactionStyle,
        hasActiveRedactions: Boolean,
        onApply: (Set<PiiClass>, PiiRedactionStyle) -> Unit,
        onClear: (() -> Unit)? = null
    ) {
        val syntheticItems = mutableListOf<PiiRedactableItem>()
        for ((piiClass, count) in countsByClass) {
            val detail = detailExamples[piiClass].orEmpty()
            for (i in 0 until count) {
                syntheticItems += PiiRedactableItem(
                    id = "${piiClass.name}_$i",
                    piiClass = piiClass,
                    label = if (count == 1) piiClass.label else "${piiClass.label} #${i + 1}",
                    subtext = detail
                )
            }
        }

        showItemSelectionDialog(
            context = context,
            title = title,
            subtitle = subtitle,
            items = syntheticItems,
            availableStyles = availableStyles,
            initialStyle = initialStyle,
            hasActiveRedactions = hasActiveRedactions,
            onApply = { selectedIds, chosenStyle ->
                val chosenClasses = syntheticItems.filter { it.id in selectedIds }.map { it.piiClass }.toSet()
                onApply(chosenClasses, chosenStyle)
            },
            onClear = onClear
        )
    }
}
