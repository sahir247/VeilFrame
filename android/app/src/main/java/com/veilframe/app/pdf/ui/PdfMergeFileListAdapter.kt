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
import java.util.concurrent.ConcurrentHashMap

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
    private val documentNameCache = ConcurrentHashMap<Uri, String>()
    private var orderRevision = 0L

    private companion object {
        const val SEQUENCE_PAYLOAD = "sequence_only"
    }

    var onOrderChanged: ((List<Uri>) -> Unit)? = null
    var onItemRemoved: ((Int) -> Unit)? = null
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun setUris(newUris: List<Uri>) {
        orderRevision++
        documentNameCache.clear()
        uris.clear()
        uris.addAll(newUris)
        notifyDataSetChanged()
        onOrderChanged?.invoke(uris.toList())
    }

    fun getUris(): List<Uri> = uris.toList()

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= uris.size || toPosition < 0 || toPosition >= uris.size) return
        if (fromPosition == toPosition) return
        val item = uris.removeAt(fromPosition)
        uris.add(toPosition, item)
        orderRevision++
        notifyItemMoved(fromPosition, toPosition)
        val start = minOf(fromPosition, toPosition)
        val count = kotlin.math.abs(fromPosition - toPosition) + 1
        notifyItemRangeChanged(start, count, SEQUENCE_PAYLOAD)
        onOrderChanged?.invoke(uris.toList())
    }

    fun reverseOrder() {
        if (uris.size <= 1) return
        uris.reverse()
        orderRevision++
        notifyDataSetChanged()
        onOrderChanged?.invoke(uris.toList())
    }

    fun sortByName() {
        if (uris.size <= 1) return
        val snapshot = uris.toList()
        val revision = orderRevision
        scope.launch(Dispatchers.IO) {
            // ContentResolver.query can block for cloud document providers.
            // Resolve names and sort off Main; reject the result after any drag/removal.
            val byName = snapshot.associateWith { uri ->
                documentNameCache[uri] ?: queryDocumentName(uri).also {
                    documentNameCache[uri] = it
                }
            }
            val sorted = snapshot.sortedWith(
                compareBy<Uri> { byName[it]?.lowercase(java.util.Locale.ROOT).orEmpty() }
            )
            withContext(Dispatchers.Main) {
                if (revision != orderRevision || uris != snapshot) return@withContext
                uris.clear()
                uris.addAll(sorted)
                orderRevision++
                notifyDataSetChanged()
                onOrderChanged?.invoke(uris.toList())
            }
        }
    }

    fun clearAll() {
        orderRevision++
        documentNameCache.clear()
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
        holder.bind(uris[position], position)
    }

    override fun onBindViewHolder(
        holder: MergeFileViewHolder, position: Int, payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == SEQUENCE_PAYLOAD }) {
            holder.updateSequence(position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class MergeFileViewHolder(
        val binding: ItemPdfMergeFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null
        private var boundUri: Uri? = null

        fun bind(uri: Uri, position: Int) {
            renderJob?.cancel()
            boundUri = uri
            binding.ivMergeThumb.setImageDrawable(null)
            binding.tvMergeFileName.text = documentNameCache[uri]
                ?: uri.lastPathSegment ?: "document.pdf"
            updateSequence(position)
            binding.tvMergeFileMeta.text = "Loading..."

            // Touch handle starts drag immediately
            binding.btnMergeDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }

            // Move Up button
             binding.btnMergeMoveUp.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition > 0) moveItem(currentPosition, currentPosition - 1)
            }

            // Move Down button
             binding.btnMergeMoveDown.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition in 0 until uris.lastIndex) moveItem(currentPosition, currentPosition + 1)
            }

            // Remove button
            binding.btnMergeRemove.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos in uris.indices) {
                    uris.removeAt(currentPos)
                    orderRevision++
                    notifyItemRemoved(currentPos)
                    notifyItemRangeChanged(currentPos, uris.size - currentPos, SEQUENCE_PAYLOAD)
                    onItemRemoved?.invoke(currentPos)
                    onOrderChanged?.invoke(uris.toList())
                }
            }

            // Load first page thumbnail & page count
            binding.pbMergeThumb.visibility = View.VISIBLE
            renderJob = scope.launch(Dispatchers.IO) {
                val fullName = documentNameCache[uri] ?: queryDocumentName(uri).also {
                    documentNameCache[uri] = it
                }
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
                val isImg = mime.startsWith("image/") ||
                    uri.toString().endsWith(".jpg", true) ||
                    uri.toString().endsWith(".jpeg", true) ||
                    uri.toString().endsWith(".png", true) ||
                    uri.toString().endsWith(".webp", true)

                // Do not try to parse gallery images as PDFs. Reuse the renderer's page
                // count (recorded during thumbnail generation) instead of opening each
                // PDF through PDFBox on every RecyclerView bind.
                val bmp = if (isImg) null else thumbnailLoader.loadPageThumbnail(uri, 0, 160)
                val pageCount = if (isImg) 1 else thumbnailLoader.loadPageCount(uri) ?: try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream).use { it.numberOfPages }
                    } ?: 1
                } catch (_: Exception) {
                    1
                }

                withContext(Dispatchers.Main) {
                    if (boundUri != uri || bindingAdapterPosition == RecyclerView.NO_POSITION ||
                        uris.getOrNull(bindingAdapterPosition) != uri
                    ) return@withContext
                    binding.pbMergeThumb.visibility = View.GONE
                    binding.tvMergeFileName.text = fullName
                    if (bmp != null) {
                        binding.ivMergeThumb.setImageBitmap(bmp)
                    } else {
                        binding.ivMergeThumb.setImageResource(if (isImg) R.drawable.ic_tab_image else R.drawable.ic_pdf_file)
                    }
                    binding.tvMergeFileMeta.text = if (isImg) "Image" else "$pageCount ${if (pageCount == 1) "page" else "pages"}"
                }
            }
        }

        fun updateSequence(position: Int) {
            binding.tvMergeSequence.text = "#${position + 1}"
            binding.btnMergeMoveUp.isEnabled = position > 0
            binding.btnMergeMoveUp.alpha = if (position > 0) 1.0f else 0.35f
            binding.btnMergeMoveDown.isEnabled = position < uris.lastIndex
            binding.btnMergeMoveDown.alpha = if (position < uris.lastIndex) 1.0f else 0.35f
        }

        fun cancelRender() {
            renderJob?.cancel()
            renderJob = null
            boundUri = null
        }
    }

    override fun onViewRecycled(holder: MergeFileViewHolder) {
        holder.cancelRender()
        super.onViewRecycled(holder)
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
