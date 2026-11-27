package com.veilframe.app.pdf

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.R
import com.veilframe.app.pdf.ui.PdfThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread request identity, independent of the current document identity. */
internal class PdfPreviewRequestGate {
    private var generation = 0L
    fun next(): Long = ++generation
    fun isCurrent(request: Long): Boolean = request == generation
}

/** Owns quick-tool preview requests and dialog lifetime; views never own cached bitmaps. */
internal class PdfQuickPagePreviews(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val thumbnails: PdfThumbnailLoader
) {
    private val signatureGate = PdfPreviewRequestGate()
    private var signatureJob: Job? = null
    private var dialogJob: Job? = null
    private var dialog: AlertDialog? = null

    fun invalidate() {
        signatureGate.next()
        signatureJob?.cancel()
        signatureJob = null
        dismissDialog()
    }

    fun clearSignature(image: ImageView, loading: ProgressBar) {
        signatureGate.next()
        signatureJob?.cancel()
        signatureJob = null
        loading.visibility = View.GONE
        image.setImageResource(R.drawable.ic_pdf_file)
    }

    fun showSignature(uri: Uri, pageIndex: Int, image: ImageView, loading: ProgressBar) {
        val request = signatureGate.next()
        signatureJob?.cancel()
        image.setImageDrawable(null)
        loading.visibility = View.VISIBLE
        signatureJob = scope.launch(Dispatchers.Main) {
            val bitmap = thumbnails.loadPageThumbnail(uri,pageIndex,600,useCache = false)
            if (!signatureGate.isCurrent(request)) return@launch
            loading.visibility = View.GONE
            if (bitmap != null) image.setImageBitmap(bitmap)
            else image.setImageResource(R.drawable.ic_pdf_file)
        }
    }

    private fun dismissDialog() {
        dialogJob?.cancel()
        dialogJob = null
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    fun showDialog(uri: Uri, pageIndex: Int) {
        dismissDialog()
        if (activity.isFinishing || activity.isDestroyed) return
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_pdf_page_preview,null)
        val current = AlertDialog.Builder(activity).setView(content).create()
        val image = content.findViewById<ImageView>(R.id.ivPreviewFullPage)
        val loading = content.findViewById<ProgressBar>(R.id.pbPreviewLoading)
        content.findViewById<TextView>(R.id.tvPreviewPageTitle).text = "Page ${pageIndex + 1} Preview"
        content.findViewById<View>(R.id.btnPreviewClose).setOnClickListener { current.dismiss() }
        current.setOnDismissListener {
            if (dialog === current) {
                dialog = null
                dialogJob?.cancel()
                dialogJob = null
            }
            image.setImageDrawable(null)
        }
        dialog = current
        loading.visibility = View.VISIBLE
        current.show()
        current.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogJob = scope.launch(Dispatchers.Main) {
            val bitmap = thumbnails.loadPageThumbnail(uri,pageIndex,1080,useCache = false)
            if (dialog !== current || !current.isShowing) return@launch
            loading.visibility = View.GONE
            if (bitmap != null) image.setImageBitmap(bitmap)
            else image.setImageResource(R.drawable.ic_pdf_file)
        }
    }
}
