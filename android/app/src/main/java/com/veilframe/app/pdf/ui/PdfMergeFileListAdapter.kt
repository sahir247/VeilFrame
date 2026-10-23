package com.veilframe.app.pdf.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.veilframe.app.R
import com.veilframe.app.databinding.ItemPdfMergeFileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * RecyclerView adapter managing multi-file list items for Merge PDF and Image to PDF compilation.
 * Supports direct touch handle drag-to-arrange, elevation animations, sequence updates,
 * and quick presets (Reverse, Sort A-Z).
 */
class PdfMergeFileListAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val thumbnailLoader: PdfThumbnailLoader
) : RecyclerView.Adapter<PdfMergeFileListAdapter.MergeFileViewHolder>() {

    private val uris = mutableListOf<Uri>()

    var onOrderChanged: ((List<Uri>) -> Unit)? = null
    var onItemRemoved: ((Int) -> Unit)? = null
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun setUris(newUris: List<Uri>) {
        uris.clear()
        uris.addAll(newUris)
        notifyDataSetChanged()
        onOrderChanged?.invoke(uris.toList())
    }

    fun getUris(): List<Uri> = uris.toList()

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= uris.size || toPosition < 0 || toPosition >= uris.size) return
        Collections.swap(uris, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        val start = minOf(fromPosition, toPosition)
        val count = kotlin.math.abs(fromPosition - toPosition) + 1
        notifyItemRangeChanged(start, count)
        onOrderChanged?.invoke(uris.toList())
    }

    fun reverseOrder() {
        if (uris.size <= 1) return
        uris.reverse()
        notifyDataSetChanged()
        onOrderChanged?.invoke(uris.toList())
    }

    fun sortByName() {
        if (uris.size <= 1) return
        uris.sortBy { queryDocumentName(it).lowercase() }
        notifyDataSetChanged()
        onOrderChanged?.invoke(uris.toList())
    }

    fun clearAll() {
        uris.clear()
        notifyDataSetChanged()
        onOrderChanged?.invoke(emptyList())
    }

    override fun getItemCount(): Int = uris.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MergeFileViewHolder {
        val binding = ItemPdfMergeFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MergeFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MergeFileViewHolder, position: Int) {
        val uri = uris[position]
        holder.bind(uri, position)
    }

    inner class MergeFileViewHolder(
        val binding: ItemPdfMergeFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null

        fun bind(uri: Uri, position: Int) {
            renderJob?.cancel()
            val name = queryDocumentName(uri)

            binding.tvMergeSequence.text = "#${position + 1}"
            binding.tvMergeFileName.text = name
            binding.tvMergeFileMeta.text = "Loading..."

            // Touch handle starts drag immediately
            binding.btnMergeDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }

            // Move Up button
            binding.btnMergeMoveUp.isEnabled = (position > 0)
            binding.btnMergeMoveUp.alpha = if (position > 0) 1.0f else 0.35f
            binding.btnMergeMoveUp.setOnClickListener {
                if (position > 0) {
                    moveItem(position, position - 1)
                }
            }

            // Move Down button
            binding.btnMergeMoveDown.isEnabled = (position < uris.size - 1)
            binding.btnMergeMoveDown.alpha = if (position < uris.size - 1) 1.0f else 0.35f
            binding.btnMergeMoveDown.setOnClickListener {
                if (position < uris.size - 1) {
                    moveItem(position, position + 1)
                }
            }

            // Remove button
            binding.btnMergeRemove.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos in uris.indices) {
                    uris.removeAt(currentPos)
                    notifyItemRemoved(currentPos)
                    notifyItemRangeChanged(currentPos, uris.size - currentPos)
                    onItemRemoved?.invoke(currentPos)
                    onOrderChanged?.invoke(uris.toList())
                }
            }

            // Load first page thumbnail & page count
            binding.pbMergeThumb.visibility = View.VISIBLE
            renderJob = scope.launch(Dispatchers.IO) {
                val bmp = thumbnailLoader.loadPageThumbnail(uri, 0, 160)
                val mime = context.contentResolver.getType(uri) ?: ""
                val isImg = mime.startsWith("image/") ||
                    uri.toString().endsWith(".jpg", true) ||
                    uri.toString().endsWith(".jpeg", true) ||
                    uri.toString().endsWith(".png", true) ||
                    uri.toString().endsWith(".webp", true)

                var pageCount = 1
                if (!isImg) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            PDDocument.load(stream).use { doc ->
                                pageCount = doc.numberOfPages
                            }
                        }
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    binding.pbMergeThumb.visibility = View.GONE
                    if (bmp != null) {
                        binding.ivMergeThumb.setImageBitmap(bmp)
                    } else {
                        binding.ivMergeThumb.setImageResource(if (isImg) R.drawable.ic_tab_image else R.drawable.ic_pdf_file)
                    }
                    binding.tvMergeFileMeta.text = if (isImg) "Image" else "$pageCount ${if (pageCount == 1) "page" else "pages"}"
                }
            }
        }
    }

    private fun queryDocumentName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            } ?: uri.lastPathSegment ?: "document.pdf"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "document.pdf"
        }
    }
}
