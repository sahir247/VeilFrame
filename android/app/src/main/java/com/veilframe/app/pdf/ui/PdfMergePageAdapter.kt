package com.veilframe.app.pdf.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.veilframe.app.R
import com.veilframe.app.databinding.ItemPdfMergePageBinding
import com.veilframe.app.pdf.operations.MergePageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for granular page-level interleaving and reordering across multiple PDF documents.
 */
class PdfMergePageAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val thumbnailLoader: PdfThumbnailLoader
) : RecyclerView.Adapter<PdfMergePageAdapter.MergePageViewHolder>() {

    private val items = mutableListOf<MergePageItem>()

    var onPageOrderChanged: ((List<MergePageItem>) -> Unit)? = null
    var onPagePreviewRequested: ((Uri, Int) -> Unit)? = null

    fun setItems(newItems: List<MergePageItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
        onPageOrderChanged?.invoke(items.toList())
    }

    fun getItems(): List<MergePageItem> = items.toList()

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < 0 || fromPosition >= items.size || toPosition < 0 || toPosition >= items.size) return
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        val start = minOf(fromPosition, toPosition)
        val count = kotlin.math.abs(fromPosition - toPosition) + 1
        notifyItemRangeChanged(start, count)
        onPageOrderChanged?.invoke(items.toList())
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MergePageViewHolder {
        val binding = ItemPdfMergePageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MergePageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MergePageViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class MergePageViewHolder(
        private val binding: ItemPdfMergePageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null

        fun bind(item: MergePageItem, position: Int) {
            renderJob?.cancel()

            binding.tvMergePageSequence.text = "#${position + 1}"
            val docName = item.sourceItem.name
            val pageNum = item.sourcePageIndex + 1
            binding.tvMergePageDocLabel.text = "$docName (p. $pageNum)"

            binding.btnMergePageFullscreen.setOnClickListener {
                onPagePreviewRequested?.invoke(item.sourceItem.uri, item.sourcePageIndex)
            }

            binding.pbMergePageLoading.visibility = View.VISIBLE
            binding.ivMergePageThumb.setImageDrawable(null)

            renderJob = scope.launch(Dispatchers.IO) {
                val bmp = thumbnailLoader.loadPageThumbnail(item.sourceItem.uri, item.sourcePageIndex, 220)
                withContext(Dispatchers.Main) {
                    binding.pbMergePageLoading.visibility = View.GONE
                    if (bmp != null) {
                        binding.ivMergePageThumb.setImageBitmap(bmp)
                    } else {
                        binding.ivMergePageThumb.setImageResource(R.drawable.ic_pdf_file)
                    }
                }
            }
        }
    }
}
