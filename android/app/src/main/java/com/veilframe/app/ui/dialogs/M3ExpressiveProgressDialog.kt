package com.veilframe.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import com.veilframe.app.databinding.DialogM3ExpressiveProgressBinding

/**
 * Material 3 Expressive circular progress dialog featuring a prominent animated
 * circular indicator with centered percentage display and live phase subtitles.
 */
class M3ExpressiveProgressDialog(private val context: Context) {
    private var dialog: Dialog? = null
    private var binding: DialogM3ExpressiveProgressBinding? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun show(
        title: String,
        initialStatus: String,
        isIndeterminate: Boolean = true,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null
    ) {
        dismiss()
        val inflater = LayoutInflater.from(context)
        val b = DialogM3ExpressiveProgressBinding.inflate(inflater)
        binding = b

        b.tvProgressTitle.text = title
        b.tvProgressSubtitle.text = initialStatus
        b.progressCircular.isIndeterminate = isIndeterminate
        if (!isIndeterminate) {
            b.progressCircular.progress = 0
            b.tvProgressPercent.text = "0%"
        } else {
            b.tvProgressPercent.text = ""
        }

        if (onCancel != null) {
            b.btnProgressCancel.visibility = View.VISIBLE
            b.btnProgressCancel.setOnClickListener {
                onCancel()
                dismiss()
            }
        } else {
            b.btnProgressCancel.visibility = View.GONE
        }

        val d = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(b.root)
            setCancelable(cancelable)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Fix dialog width so it stays completely steady without layout jitter
            val dm = context.resources.displayMetrics
            val dialogWidth = (dm.widthPixels * 0.85f).toInt().coerceAtMost((340 * dm.density).toInt())
            window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog = d
        d.show()
    }

    fun updateProgress(percent: Int, statusText: String? = null) {
        val b = binding ?: return
        if (b.progressCircular.isIndeterminate) {
            b.progressCircular.isIndeterminate = false
        }
        val safe = percent.coerceIn(0, 100)
        b.progressCircular.progress = safe
        b.tvProgressPercent.text = "$safe%"
        if (!statusText.isNullOrBlank()) {
            b.tvProgressSubtitle.text = statusText
        }
    }

    fun updateStatus(statusText: String) {
        binding?.tvProgressSubtitle?.text = statusText
    }

    fun dismiss() {
        try {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
            }
        } catch (_: Throwable) {}
        dialog = null
        binding = null
    }
}
