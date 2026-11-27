package com.veilframe.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.veilframe.app.pdf.operations.PdfSigner
import com.veilframe.app.pdf.operations.SavedSignature
import com.veilframe.app.pdf.ui.SignaturePadView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SignatureLibraryWindow(val page: Int,val pages: Int,val start: Int,val endExclusive: Int) {
    companion object {
        const val PAGE_SIZE = 16
        fun at(count: Int,requestedPage: Int): SignatureLibraryWindow {
            require(count in 0..2048) { "Signature library exceeds supported size" }
            val pages = maxOf(1,(count+PAGE_SIZE-1)/PAGE_SIZE)
            val page = requestedPage.coerceIn(0,pages-1)
            val start = page*PAGE_SIZE
            return SignatureLibraryWindow(page,pages,start,minOf(count,start+PAGE_SIZE))
        }
    }
}

/** All quick-sign dialogs and IO jobs belong to one main-thread lifecycle. */
internal class PdfQuickSignatureDialogs(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val signer: PdfSigner
) {
    private var current: AlertDialog? = null
    private var generation = 0L
    private var job: Job? = null
    private var busy = false
    private fun dp(value: Int) = (value*activity.resources.displayMetrics.density).toInt()
    private fun notify(message: String) = Toast.makeText(activity,message,Toast.LENGTH_SHORT).show()

    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        busy = false
        val old = current
        current = null
        old?.dismiss()
    }

    private fun present(dialog: AlertDialog): Boolean {
        invalidate()
        if (activity.isFinishing || activity.isDestroyed) return false
        current = dialog
        dialog.setOnDismissListener {
            if (current === dialog) {
                generation++
                current = null
                job?.cancel()
                job = null
                busy = false
            }
        }
        dialog.show()
        return true
    }

    private fun owns(dialog: AlertDialog,request: Long) =
        current === dialog && generation == request && dialog.isShowing && !activity.isDestroyed

    private fun execute(dialog: AlertDialog,block: suspend (Long)->Unit) {
        if (current !== dialog || busy) return
        val request = ++generation
        job?.cancel()
        busy = true
        job = scope.launch(Dispatchers.Main) {
            try {
                block(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(dialog,request)) notify(failure.message ?: "Signature library operation failed")
            } finally {
                if (owns(dialog,request)) busy = false
            }
        }
    }

    private fun column() = LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(16),dp(8),dp(16),dp(8))
    }

    fun draw(color: Int,thickness: Float,onThickness: (Float)->Unit,onSignature: (Bitmap)->Unit) {
        val body = column()
        val pad = SignaturePadView(activity).apply {
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(200))
            setBackgroundColor(Color.parseColor("#18181C"))
            setInkColor(color)
            setStrokeThicknessDp(thickness)
        }
        val label = TextView(activity).apply { text="Stroke thickness: $thickness dp" }
        val slider = Slider(activity).apply {
            valueFrom=1f; valueTo=12f; stepSize=0.5f; value=thickness.coerceIn(1f,12f)
            addOnChangeListener { _,value,_ ->
                label.text="Stroke thickness: $value dp"
                pad.setStrokeThicknessDp(value)
                onThickness(value)
            }
        }
        body.addView(pad); body.addView(label); body.addView(slider)
        body.addView(MaterialButton(activity).apply { text="Clear ink"; setOnClickListener { pad.clear() } })
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Draw New Signature").setView(body)
            .setPositiveButton("Add to PDF",null).setNegativeButton("Cancel",null).create()
        if (!present(dialog)) return
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (current !== dialog) return@setOnClickListener
            val bitmap=pad.getSignatureBitmap(targetColor=color)
            if (bitmap==null) notify("Signature is empty")
            else { onSignature(bitmap); dialog.dismiss() }
        }
    }

    fun save(bitmap: Bitmap) {
        nameDialog("Save Signature to Library","",true,null) { name ->
            signer.saveSignatureBitmap(bitmap,name)
        }
    }

    private fun nameDialog(title: String,initial: String,allowEmpty: Boolean,back: (()->Unit)?,
        action: suspend (String)->Unit) {
        val body=column()
        val input=TextInputEditText(activity).apply { setText(initial); selectAll() }
        val field=TextInputLayout(activity).apply {
            hint=if (allowEmpty) "Signature name (optional)" else "Signature name"
            addView(input)
        }
        body.addView(field)
        val dialog=MaterialAlertDialogBuilder(activity).setTitle(title).setView(body)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        if (!present(dialog)) return
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (!busy) { dialog.dismiss(); back?.invoke() }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name=input.text?.toString()?.trim().orEmpty()
            if (name.length>256 || (!allowEmpty && name.isEmpty())) {
                field.error="Enter a valid name (maximum 256 characters)"
                return@setOnClickListener
            }
            field.error=null
            execute(dialog) { request ->
                val positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive.isEnabled=false
                input.isEnabled=false
                try {
                    action(name)
                    if (owns(dialog,request)) {
                        notify("Signature saved")
                        dialog.dismiss()
                        back?.invoke()
                    }
                } finally {
                    if (owns(dialog,request)) { positive.isEnabled=true; input.isEnabled=true }
                }
            }
        }
    }

    private data class Row(val signature: SavedSignature,val thumbnail: Bitmap?)
    private data class Page(val window: SignatureLibraryWindow,val count: Int,val rows: List<Row>)

    fun reuse(onSignature: (Bitmap)->Unit) = library(false,0,onSignature)
    fun manage() = library(true,0,{})
    private fun library(manage: Boolean,requested: Int,onSignature: (Bitmap)->Unit) {
        val body=column()
        val status=TextView(activity).apply { text="Loading signatures…" }
        val rows=column().apply { setPadding(0,0,0,0) }
        val scroll=ScrollView(activity).apply {
            addView(rows)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(320))
        }
        val nav=LinearLayout(activity).apply { gravity=Gravity.CENTER }
        val previous=MaterialButton(activity).apply { text="Previous"; isEnabled=false }
        val next=MaterialButton(activity).apply { text="Next"; isEnabled=false }
        nav.addView(previous); nav.addView(next)
        body.addView(status); body.addView(scroll); body.addView(nav)
        val dialog=MaterialAlertDialogBuilder(activity)
            .setTitle(if(manage) "Manage Saved Signatures" else "Reuse Saved Signature")
            .setView(body).setNegativeButton(if(manage) "Done" else "Cancel",null).create()
        if (!present(dialog)) return

        fun load(requestedPage: Int) {
            execute(dialog) { request ->
                previous.isEnabled=false; next.isEnabled=false
                status.text="Loading signatures…"
                rows.removeAllViews()
                val page=try {
                    withContext(Dispatchers.IO) {
                        val all=signer.getSavedSignatures()
                        val window=SignatureLibraryWindow.at(all.size,requestedPage)
                        val pageRows=all.subList(window.start,window.endExclusive).map { signature ->
                            ensureActive()
                            Row(signature,signer.loadSavedSignatureBitmap(signature.bitmapPath,thumbnail=true))
                        }
                        Page(window,all.size,pageRows)
                    }
                } catch (failure: Exception) {
                    if (owns(dialog,request)) status.text="Cannot load signatures"
                    throw failure
                }
                if (!owns(dialog,request)) return@execute
                status.text=if(page.count==0) "No saved signatures" else
                    "Page ${page.window.page+1} of ${page.window.pages} · ${page.count} signatures"
                previous.isEnabled=page.window.page>0
                next.isEnabled=page.window.page+1<page.window.pages
                previous.setOnClickListener { load(page.window.page-1) }
                next.setOnClickListener { load(page.window.page+1) }
                for (row in page.rows) {
                    val signature=row.signature
                    val item=LinearLayout(activity).apply {
                        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                        setPadding(0,dp(6),0,dp(6))
                    }
                    val thumb=ImageView(activity).apply {
                        layoutParams=LinearLayout.LayoutParams(dp(64),dp(48)).apply { marginEnd=dp(8) }
                        scaleType=ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(row.thumbnail)
                    }
                    val text=TextView(activity).apply {
                        this.text=signature.name
                        maxLines=2
                        layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                    }
                    item.addView(thumb); item.addView(text)
                    if (manage) {
                        val back={ library(true,page.window.page,onSignature) }
                        val rename=MaterialButton(activity).apply {
                            this.text="Rename"
                            setOnClickListener {
                                if (!busy) nameDialog("Rename Signature",signature.name,false,back) { name ->
                                    check(withContext(Dispatchers.IO) { signer.renameSignature(signature.id,name) }) {
                                        "Signature rename failed"
                                    }
                                }
                            }
                        }
                        val delete=MaterialButton(activity).apply {
                            this.text="Delete"
                            setOnClickListener { if(!busy) confirmDelete(signature,back) }
                        }
                        item.addView(rename); item.addView(delete)
                    } else {
                        item.isClickable=true
                        item.setOnClickListener {
                            execute(dialog) { selectedRequest ->
                                val bitmap=withContext(Dispatchers.IO) {
                                    signer.loadSavedSignatureBitmap(signature.bitmapPath)
                                } ?: error("Cannot read saved signature")
                                if (owns(dialog,selectedRequest)) {
                                    onSignature(bitmap)
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                    rows.addView(item)
                }
            }
        }
        load(requested)
    }

    private fun confirmDelete(signature: SavedSignature,back: ()->Unit) {
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("Delete saved signature?")
            .setMessage(signature.name).setPositiveButton("Delete",null).setNegativeButton("Cancel",null).create()
        if (!present(dialog)) return
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if(!busy) { dialog.dismiss(); back() }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            execute(dialog) { request ->
                check(withContext(Dispatchers.IO) { signer.deleteSignatureById(signature.id) }) {
                    "Signature deletion failed"
                }
                if (owns(dialog,request)) { notify("Signature deleted"); dialog.dismiss(); back() }
            }
        }
    }
}
