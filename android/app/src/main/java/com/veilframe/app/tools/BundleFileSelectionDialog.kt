package com.veilframe.app.tools

import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Metadata-only selection; no provider reads are performed by the dialog. */
internal object BundleFileSelectionDialog {
    private const val PAGE_SIZE = 40
    private const val MAX_EXCLUSIONS = 2000

    fun show(activity: AppCompatActivity, files: List<String>, onGenerate: (Set<String>) -> Unit) {
        val selection = linkedSetOf<String>()
        var filtered = files
        var page = 0
        val density = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val description = TextView(activity).apply {
            text = "Check files to exclude before reading contents. Automatic filters still apply; excluded names may appear in the omission report."
        }
        root.addView(description)
        val search = EditText(activity).apply {
            hint = "Filter relative paths"
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        root.addView(search)
        val filter = Button(activity).apply { text = "Filter" }
        root.addView(filter)
        val status = TextView(activity)
        root.addView(status)
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * density).toInt()))
        val controls = LinearLayout(activity)
        val previous = Button(activity).apply { text = "Previous" }
        val next = Button(activity).apply { text = "Next" }
        controls.addView(previous, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        var visible = emptyList<String>()
        fun refreshStatus() {
            val pages = ((filtered.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
            status.text = "Excluded: " + selection.size + " / " + files.size + " • " +
                filtered.size + " matching • page " + (page + 1) + "/" + pages
            previous.isEnabled = page > 0
            next.isEnabled = page + 1 < pages
        }
        fun render() {
            visible = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
            list.clearChoices()
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice, visible)
            for ((index, path) in visible.withIndex()) list.setItemChecked(index, path in selection)
            refreshStatus()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val path = visible.getOrNull(position) ?: return@setOnItemClickListener
            if (list.isItemChecked(position)) {
                if (selection.size >= MAX_EXCLUSIONS) {
                    list.setItemChecked(position, false)
                    android.widget.Toast.makeText(activity, "Maximum 2,000 file exclusions. Select a narrower folder.", android.widget.Toast.LENGTH_LONG).show()
                } else selection.add(path)
            } else selection.remove(path)
            refreshStatus()
        }
        filter.setOnClickListener {
            val query = search.text.toString()
            filtered = if (query.isBlank()) files else files.filter { it.contains(query, ignoreCase = true) }
            page = 0
            render()
        }
        previous.setOnClickListener { if (page > 0) { page--; render() } }
        next.setOnClickListener { if ((page + 1) * PAGE_SIZE < filtered.size) { page++; render() } }
        render()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Exclude files from AI bundle")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Generate") { _, _ -> onGenerate(selection.toSet()) }
            .show()
    }
}
