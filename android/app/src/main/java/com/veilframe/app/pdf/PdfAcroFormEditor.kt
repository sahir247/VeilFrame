package com.veilframe.app.pdf

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.pdf.operations.FormField
import com.veilframe.app.pdf.operations.FieldValue

/** Owns AcroForm editing widgets and dialog lifetime, never source or export jobs. */
internal class PdfAcroFormEditor(private val activity: AppCompatActivity) {
    private var dialog: AlertDialog? = null
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun show(
        fields: List<FormField>,
        isCurrent: () -> Boolean,
        onApply: (Map<String,FieldValue>,Boolean,() -> Unit) -> Unit
    ) {
        dismiss()
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        val scroll = ScrollView(activity).apply {
            addView(content)
        }
        val readers = linkedMapOf<String, () -> com.veilframe.app.pdf.operations.FieldValue>()
        val requiredChecks = mutableListOf<Pair<String, () -> Boolean>>()

        fun addLabel(text: String) {
            content.addView(TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(activity.getColor(R.color.vf_text_primary))
                setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
            })
        }

        for (field in fields) {
            if (field is com.veilframe.app.pdf.operations.FormField.UnknownField) {
                addLabel("${field.name} — unsupported field (${field.fieldType})")
                continue
            }
            addLabel(field.name + if (field.isRequired) " *" else "")
            when (field) {
                is com.veilframe.app.pdf.operations.FormField.TextField -> {
                    val edit = com.google.android.material.textfield.TextInputEditText(activity).apply {
                        setText(field.value)
                        isEnabled = !field.isReadOnly
                        isSingleLine = !field.isMultiline
                        if (field.isMultiline) {
                            minLines = 2
                            maxLines = 5
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        }
                        field.maxLength?.let {
                            filters = arrayOf(android.text.InputFilter.LengthFilter(it))
                        }
                    }
                    content.addView(edit, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                    if (!field.isReadOnly) {
                        readers[field.name] = { com.veilframe.app.pdf.operations.FieldValue.Text(edit.text?.toString().orEmpty()) }
                        if (field.isRequired) requiredChecks.add(field.name to { !edit.text.isNullOrBlank() })
                    }
                }
                is com.veilframe.app.pdf.operations.FormField.CheckBoxField -> {
                    val check = android.widget.CheckBox(activity).apply {
                        text = "Checked"
                        isChecked = field.isChecked
                        isEnabled = !field.isReadOnly
                    }
                    content.addView(check)
                    if (!field.isReadOnly) {
                        readers[field.name] = { com.veilframe.app.pdf.operations.FieldValue.CheckBox(check.isChecked) }
                        if (field.isRequired) requiredChecks.add(field.name to { check.isChecked })
                    }
                }
                is com.veilframe.app.pdf.operations.FormField.RadioButtonField -> {
                    val spinner = android.widget.Spinner(activity)
                    val options = field.options
                    spinner.adapter = android.widget.ArrayAdapter(
                        activity, android.R.layout.simple_spinner_item, options
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    val selected = field.selectedOption?.let { options.indexOf(it) } ?: -1
                    if (selected >= 0) spinner.setSelection(selected)
                    spinner.isEnabled = !field.isReadOnly && options.isNotEmpty()
                    content.addView(spinner)
                    if (!field.isReadOnly) {
                        readers[field.name] = {
                            com.veilframe.app.pdf.operations.FieldValue.Radio(
                                options.getOrNull(spinner.selectedItemPosition).orEmpty()
                            )
                        }
                        if (field.isRequired) requiredChecks.add(field.name to { spinner.selectedItemPosition >= 0 && options.isNotEmpty() })
                    }
                }
                is com.veilframe.app.pdf.operations.FormField.ComboBoxField -> {
                    if (field.isEditable) {
                        val edit = com.google.android.material.textfield.TextInputEditText(activity).apply {
                            setText(field.selectedValue.orEmpty())
                            isEnabled = !field.isReadOnly
                            isSingleLine = true
                        }
                        content.addView(edit, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ))
                        if (!field.isReadOnly) {
                            readers[field.name] = {
                                com.veilframe.app.pdf.operations.FieldValue.Combo(edit.text?.toString().orEmpty())
                            }
                            if (field.isRequired) requiredChecks.add(field.name to { !edit.text.isNullOrBlank() })
                        }
                    } else {
                        val spinner = android.widget.Spinner(activity)
                        val options = field.options
                        spinner.adapter = android.widget.ArrayAdapter(
                            activity, android.R.layout.simple_spinner_item, options
                        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                        val selected = field.selectedValue?.let { options.indexOf(it) } ?: -1
                        if (selected >= 0) spinner.setSelection(selected)
                        spinner.isEnabled = !field.isReadOnly && options.isNotEmpty()
                        content.addView(spinner)
                        if (!field.isReadOnly) {
                            readers[field.name] = {
                                com.veilframe.app.pdf.operations.FieldValue.Combo(
                                    options.getOrNull(spinner.selectedItemPosition) ?: field.selectedValue.orEmpty()
                                )
                            }
                            if (field.isRequired) requiredChecks.add(field.name to { spinner.selectedItemPosition >= 0 && options.isNotEmpty() })
                        }
                    }
                }
                is com.veilframe.app.pdf.operations.FormField.ListBoxField -> {
                    if (field.isMultiSelect) {
                        val checks = field.options.map { option ->
                            android.widget.CheckBox(activity).apply {
                                text = option
                                isChecked = option in field.selectedValues
                                isEnabled = !field.isReadOnly
                                content.addView(this)
                            }
                        }
                        if (!field.isReadOnly) {
                            readers[field.name] = {
                                com.veilframe.app.pdf.operations.FieldValue.ListValue(
                                    checks.filter { it.isChecked }.map { it.text.toString() }
                                )
                            }
                            if (field.isRequired) requiredChecks.add(field.name to { checks.any { it.isChecked } })
                        }
                    } else {
                        val spinner = android.widget.Spinner(activity)
                        spinner.adapter = android.widget.ArrayAdapter(
                            activity, android.R.layout.simple_spinner_item, field.options
                        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                        val selected = field.selectedValues.firstOrNull()?.let { field.options.indexOf(it) } ?: -1
                        if (selected >= 0) spinner.setSelection(selected)
                        spinner.isEnabled = !field.isReadOnly && field.options.isNotEmpty()
                        content.addView(spinner)
                        if (!field.isReadOnly) {
                            readers[field.name] = {
                                com.veilframe.app.pdf.operations.FieldValue.ListValue(
                                    listOfNotNull(field.options.getOrNull(spinner.selectedItemPosition))
                                )
                            }
                            if (field.isRequired) requiredChecks.add(field.name to {
                                spinner.selectedItemPosition >= 0 && field.options.isNotEmpty()
                            })
                        }
                    }
                }
                is com.veilframe.app.pdf.operations.FormField.UnknownField -> Unit
            }
            if (field.isReadOnly) {
                content.addView(TextView(activity).apply {
                    text = "Read-only"
                    textSize = 11f
                    setTextColor(activity.getColor(R.color.vf_text_secondary))
                })
            }
        }

        val flatten = android.widget.CheckBox(activity).apply {
            text = "Flatten form after filling (fields become non-editable)"
            isChecked = false
        }
        content.addView(flatten)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Fill PDF Form")
            .setMessage("${readers.size} editable field(s) • * required")
            .setView(scroll)
            .setPositiveButton("Apply & Export", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val missing = requiredChecks.firstOrNull { !it.second.invoke() }
                if (missing != null) {
                    Toast.makeText(activity, "Complete required field: ${missing.first}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isCurrent()) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                onApply(readers.mapValues { it.value.invoke() },flatten.isChecked) {
                    if (this.dialog === dialog) dismiss()
                }
            }
        }
        this.dialog = dialog
        dialog.setOnDismissListener { if (this.dialog === dialog) this.dialog = null }
        dialog.show()
    }
}
