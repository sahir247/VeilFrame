package com.veilframe.app.pdf.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.veilframe.app.R
import com.veilframe.app.databinding.ItemPdfPageThumbnailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * High-performance RecyclerView adapter rendering visual page previews for PDF extraction,
 * split ranges, reordering, and page deletion.
 */
class PdfPageThumbnailAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val thumbnailLoader: PdfThumbnailLoader,
    private val onPageSelectionChanged: (selectedPages: Set<Int>) -> Unit
) : RecyclerView.Adapter<PdfPageThumbnailAdapter.ThumbnailViewHolder>() {

    private var pdfUri: Uri? = null
    private var totalPages: Int = 0
    private val pageOrder = mutableListOf<Int>()
    private val selectedPages = mutableSetOf<Int>()
    private val deletedPages = mutableSetOf<Int>()
    private val pageRotations = mutableMapOf<Int, Int>()

    var onPageOrderChanged: ((List<Int>) -> Unit)? = null
    var onPagePreviewRequested: ((Int) -> Unit)? = null

    var isDeleteMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun setDocument(uri: Uri, count: Int, initialSelectedPages: Set<Int> = emptySet()) {
        pdfUri = uri
        totalPages = count
        pageOrder.clear()
        for (i in 1..count) pageOrder.add(i)
        selectedPages.clear()
        selectedPages.addAll(initialSelectedPages)
        deletedPages.clear()
        pageRotations.clear()
        notifyDataSetChanged()
        onPageSelectionChanged(selectedPages)
        onPageOrderChanged?.invoke(pageOrder.toList())
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= pageOrder.size || toPosition < 0 || toPosition >= pageOrder.size) return
        val item = pageOrder.removeAt(fromPosition)
        pageOrder.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        val start = minOf(fromPosition, toPosition)
        val count = kotlin.math.abs(fromPosition - toPosition) + 1
        notifyItemRangeChanged(start, count)
        onPageOrderChanged?.invoke(pageOrder.toList())
    }

    fun getPageOrder(): List<Int> = pageOrder.toList()

    fun setPageOrder(newOrder: List<Int>) {
        pageOrder.clear()
        pageOrder.addAll(newOrder.filter { it in 1..totalPages })
        if (pageOrder.size < totalPages) {
            val remaining = (1..totalPages).filter { it !in pageOrder }
            pageOrder.addAll(remaining)
        }
        notifyDataSetChanged()
        onPageOrderChanged?.invoke(pageOrder.toList())
    }

    fun resetOrder() {
        pageOrder.clear()
        for (i in 1..totalPages) pageOrder.add(i)
        notifyDataSetChanged()
        onPageOrderChanged?.invoke(pageOrder.toList())
    }

    fun selectAll() {
        if (totalPages <= 0) return
        selectedPages.clear()
        for (i in 1..totalPages) selectedPages.add(i)
        notifyDataSetChanged()
        onPageSelectionChanged(selectedPages)
    }

    fun deselectAll() {
        selectedPages.clear()
        notifyDataSetChanged()
        onPageSelectionChanged(selectedPages)
    }

    fun invertSelection() {
        if (totalPages <= 0) return
        val newSet = mutableSetOf<Int>()
        for (i in 1..totalPages) {
            if (i !in selectedPages) newSet.add(i)
        }
        selectedPages.clear()
        selectedPages.addAll(newSet)
        notifyDataSetChanged()
        onPageSelectionChanged(selectedPages)
    }

    fun selectPages(pages: Set<Int>) {
        selectedPages.clear()
        selectedPages.addAll(pages.filter { it in 1..totalPages })
        notifyDataSetChanged()
        onPageSelectionChanged(selectedPages)
    }

    fun getSelectedPages(): Set<Int> = selectedPages.toSet()
    fun getDeletedPages(): Set<Int> = deletedPages.toSet()

    override fun getItemCount(): Int = pageOrder.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemPdfPageThumbnailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        val pageNumber = if (position in pageOrder.indices) pageOrder[position] else position + 1
        holder.bind(pageNumber)
    }

    inner class ThumbnailViewHolder(
        private val binding: ItemPdfPageThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null

        fun bind(pageNumber: Int) {
            renderJob?.cancel()
            val uri = pdfUri ?: return

            val currentPos = bindingAdapterPosition
            val isReordered = pageOrder != (1..totalPages).toList()
            val isMoved = isReordered && currentPos >= 0 && (currentPos + 1 != pageNumber)

            binding.tvPageNumber.text = if (isReordered && currentPos >= 0) {
                if (isMoved) "#${currentPos + 1} (was p.$pageNumber)" else "Page $pageNumber"
            } else {
                "Page $pageNumber"
            }

            if (isMoved) {
                binding.tvSequenceBadge.visibility = View.VISIBLE
                binding.tvSequenceBadge.text = "#${currentPos + 1}"
            } else {
                binding.tvSequenceBadge.visibility = View.GONE
            }

            val isSelected = pageNumber in selectedPages
            val isDeleted = pageNumber in deletedPages
            val rotation = pageRotations[pageNumber] ?: 0

            binding.cbPageSelected.isChecked = isSelected

            if (isSelected) {
                binding.cardPageThumbnail.strokeColor = ContextCompat.getColor(context, R.color.vf_primary)
                binding.cardPageThumbnail.setCardBackgroundColor(ContextCompat.getColor(context, R.color.vf_container_high))
            } else {
                binding.cardPageThumbnail.strokeColor = ContextCompat.getColor(context, R.color.vf_surface_variant)
                binding.cardPageThumbnail.setCardBackgroundColor(ContextCompat.getColor(context, R.color.vf_container))
            }

            // Deleted overlay
            binding.overlayDeleted.visibility = if (isDeleted) View.VISIBLE else View.GONE

            // Rotation badge
            if (rotation > 0) {
                binding.tvRotationBadge.visibility = View.VISIBLE
                binding.tvRotationBadge.text = "$rotation°"
            } else {
                binding.tvRotationBadge.visibility = View.GONE
            }

            // Fullscreen preview button
            binding.btnPageFullscreen.setOnClickListener {
                onPagePreviewRequested?.invoke(pageNumber)
            }

            // Card click listener
            binding.cardPageThumbnail.setOnClickListener {
                if (isDeleteMode) {
                    if (pageNumber in deletedPages) {
                        deletedPages.remove(pageNumber)
                    } else {
                        deletedPages.add(pageNumber)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                } else {
                    if (pageNumber in selectedPages) {
                        selectedPages.remove(pageNumber)
                    } else {
                        selectedPages.add(pageNumber)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                    onPageSelectionChanged(selectedPages)
                }
            }

            // Checkbox click listener
            binding.cbPageSelected.setOnClickListener {
                if (pageNumber in selectedPages) {
                    selectedPages.remove(pageNumber)
                } else {
                    selectedPages.add(pageNumber)
                }
                notifyItemChanged(bindingAdapterPosition)
                onPageSelectionChanged(selectedPages)
            }

            // Asynchronously load thumbnail bitmap
            binding.pbThumbLoading.visibility = View.VISIBLE
            binding.ivPageThumbnail.setImageDrawable(null)

            renderJob = scope.launch(Dispatchers.IO) {
                val bmp = thumbnailLoader.loadPageThumbnail(uri, pageNumber - 1)
                withContext(Dispatchers.Main) {
                    binding.pbThumbLoading.visibility = View.GONE
                    if (bmp != null) {
                        binding.ivPageThumbnail.setImageBitmap(bmp)
                    } else {
                        binding.ivPageThumbnail.setImageResource(R.drawable.ic_pdf_file)
                    }
                }
            }
        }
    }
}
