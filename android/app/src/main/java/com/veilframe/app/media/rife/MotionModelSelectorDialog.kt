package com.veilframe.app.media.rife

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogMotionModelSelectorBinding
import com.veilframe.app.databinding.ItemMotionModelCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Material 3 Expressive Model Selector & Manager for Motion Lab RIFE models (v4.0 - v4.26).
 * Handles live model switching, on-demand downloading with wavy progress, and cache eviction.
 */
class MotionModelSelectorDialog(
    private val activity: Activity,
    private val coroutineScope: CoroutineScope,
    private val rifeManager: RifeModelManager,
    private val onModelSelected: (RifeModelSpec) -> Unit
) {

    private var bottomSheetDialog: BottomSheetDialog? = null
    private var downloadJob: Job? = null
    private var downloadingModelId: String? = null

    fun show() {
        val binding = DialogMotionModelSelectorBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(binding.root)
        bottomSheetDialog = dialog

        // Subtitle / Hardware status
        val isEmu = RifeNcnnNative.isEmulator()
        val hwText = if (isEmu) {
            "CPU Mode (Android Emulator Detected)"
        } else {
            val gpu = RifeNcnnNative.getGpuDeviceName()
            if (gpu.isNotBlank() && gpu != "Vulkan GPU") {
                "Vulkan GPU Accelerated • $gpu"
            } else {
                "Vulkan GPU Accelerated • Cross-Platform Compute"
            }
        }
        binding.tvMotionHardwareSubtitle.text = hwText

        lateinit var adapter: ModelAdapter

        fun updateStorageSummary() {
            val totalBytes = rifeManager.getTotalStorageUsedBytes()
            val totalMb = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
            binding.tvMotionModelStorageSummary.text = "Installed Models: $totalMb MB used"
            val active = rifeManager.getSelectedModel()
            val activeStatus = if (rifeManager.isModelDownloaded(active)) "Active: ${active.displayName}" else "No active model downloaded"
            binding.tvMotionModelActiveInfo.text = activeStatus
        }

        updateStorageSummary()

        binding.btnMotionModelClose.setOnClickListener {
            dialog.dismiss()
        }

        adapter = ModelAdapter(
            models = RifeModelRegistry.ALL_MODELS,
            rifeManager = rifeManager,
            onSelect = { spec ->
                rifeManager.setSelectedModel(spec)
                updateStorageSummary()
                onModelSelected(spec)
                dialog.dismiss()
            },
            onDownload = { spec, itemBinding ->
                startDownload(spec, itemBinding, adapter, {
                    updateStorageSummary()
                    onModelSelected(spec)
                })
            },
            onDelete = { spec, itemBinding ->
                confirmDelete(spec, itemBinding, adapter, {
                    updateStorageSummary()
                })
            }
        )

        binding.rvMotionModels.layoutManager = LinearLayoutManager(activity)
        binding.rvMotionModels.adapter = adapter

        dialog.setOnDismissListener {
            downloadJob?.cancel()
            downloadingModelId = null
        }

        dialog.show()
    }

    private fun startDownload(
        spec: RifeModelSpec,
        itemBinding: ItemMotionModelCardBinding,
        adapter: ModelAdapter,
        onSuccess: () -> Unit
    ) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(activity, "A download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        downloadingModelId = spec.id
        itemBinding.progressMotionModel.visibility = View.VISIBLE
        itemBinding.progressMotionModel.isIndeterminate = true
        itemBinding.tvMotionModelProgressMsg.visibility = View.VISIBLE
        itemBinding.tvMotionModelProgressMsg.text = "Connecting to download server…"
        itemBinding.btnMotionModelDownload.isEnabled = false
        itemBinding.btnMotionModelSelect.isEnabled = false
        itemBinding.btnMotionModelDelete.isEnabled = false

        downloadJob = coroutineScope.launch(Dispatchers.IO) {
            val result = rifeManager.downloadModel(spec, forceRedownload = true) { pct, msg ->
                coroutineScope.launch(Dispatchers.Main) {
                    if (bottomSheetDialog?.isShowing == true && downloadingModelId == spec.id) {
                        itemBinding.progressMotionModel.isIndeterminate = false
                        itemBinding.progressMotionModel.progress = pct
                        itemBinding.tvMotionModelProgressMsg.text = msg
                    }
                }
            }

            withContext(Dispatchers.Main) {
                downloadingModelId = null
                itemBinding.progressMotionModel.visibility = View.GONE
                itemBinding.tvMotionModelProgressMsg.visibility = View.GONE
                itemBinding.btnMotionModelDownload.isEnabled = true
                itemBinding.btnMotionModelSelect.isEnabled = true
                itemBinding.btnMotionModelDelete.isEnabled = true

                if (result.isSuccess) {
                    Toast.makeText(activity, "${spec.displayName} downloaded & activated!", Toast.LENGTH_SHORT).show()
                    rifeManager.setSelectedModel(spec)
                    adapter.notifyDataSetChanged()
                    onSuccess()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Download failed"
                    Toast.makeText(activity, "Download failed: $err", Toast.LENGTH_LONG).show()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun confirmDelete(
        spec: RifeModelSpec,
        itemBinding: ItemMotionModelCardBinding,
        adapter: ModelAdapter,
        onDeleted: () -> Unit
    ) {
        val isSelected = rifeManager.getSelectedModel().id == spec.id
        val confirmMsg = if (isSelected) {
            "Delete ${spec.displayName} (${spec.sizeFormatted})? This is currently your active model. You can redownload it anytime."
        } else {
            "Remove downloaded weights for ${spec.displayName} (${spec.sizeFormatted})? You can redownload them anytime."
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete Model?")
            .setMessage(confirmMsg)
            .setPositiveButton("Delete") { _, _ ->
                rifeManager.deleteModel(spec)
                Toast.makeText(activity, "Deleted ${spec.displayName}", Toast.LENGTH_SHORT).show()
                val current = rifeManager.getSelectedModel()
                onModelSelected(current)
                adapter.notifyDataSetChanged()
                onDeleted()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class ModelAdapter(
        private val models: List<RifeModelSpec>,
        private val rifeManager: RifeModelManager,
        private val onSelect: (RifeModelSpec) -> Unit,
        private val onDownload: (RifeModelSpec, ItemMotionModelCardBinding) -> Unit,
        private val onDelete: (RifeModelSpec, ItemMotionModelCardBinding) -> Unit
    ) : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMotionModelCardBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemMotionModelCardBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = models.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val spec = models[position]
            val binding = holder.binding
            val isDownloaded = rifeManager.isModelDownloaded(spec)
            val isSelected = rifeManager.getSelectedModel().id == spec.id

            binding.tvMotionModelTitle.text = spec.displayName
            binding.tvMotionModelBadge.text = spec.shortBadge
            binding.tvMotionModelDescription.text = spec.description
            binding.tvMotionModelMetrics.text = "${spec.sizeFormatted} • ${spec.requiredPadding}px padding • ${spec.benchmarkNote}"

            binding.radioMotionModelSelect.isChecked = isSelected

            if (isSelected) {
                binding.cardMotionModel.strokeWidth = 2
                binding.cardMotionModel.setStrokeColor(binding.root.context.getColor(R.color.vf_primary))
                binding.tvMotionModelStatus.text = "ACTIVE"
                binding.tvMotionModelStatus.setTextColor(binding.root.context.getColor(R.color.vf_primary))
            } else if (isDownloaded) {
                binding.cardMotionModel.strokeWidth = 1
                binding.cardMotionModel.setStrokeColor(binding.root.context.getColor(R.color.vf_surface_variant))
                binding.tvMotionModelStatus.text = "DOWNLOADED"
                binding.tvMotionModelStatus.setTextColor(binding.root.context.getColor(R.color.vf_accent_green))
            } else {
                binding.cardMotionModel.strokeWidth = 1
                binding.cardMotionModel.setStrokeColor(binding.root.context.getColor(R.color.vf_surface_variant))
                binding.tvMotionModelStatus.text = "AVAILABLE"
                binding.tvMotionModelStatus.setTextColor(binding.root.context.getColor(R.color.vf_text_muted))
            }

            if (isDownloaded) {
                binding.btnMotionModelDownload.visibility = View.GONE
                binding.btnMotionModelSelect.visibility = View.VISIBLE
                binding.btnMotionModelSelect.isEnabled = !isSelected && (downloadingModelId == null)
                binding.btnMotionModelSelect.text = if (isSelected) "Active Model" else "Select Model"
                binding.btnMotionModelDelete.visibility = View.VISIBLE
                binding.btnMotionModelDelete.isEnabled = (downloadingModelId == null)
            } else {
                binding.btnMotionModelDownload.visibility = View.VISIBLE
                binding.btnMotionModelDownload.isEnabled = (downloadingModelId == null)
                binding.btnMotionModelDownload.text = "Download (${spec.sizeFormatted})"
                binding.btnMotionModelSelect.visibility = View.GONE
                binding.btnMotionModelDelete.visibility = View.GONE
            }

            binding.btnMotionModelSelect.setOnClickListener {
                if (isDownloaded) {
                    onSelect(spec)
                }
            }

            binding.cardMotionModel.setOnClickListener {
                if (isDownloaded) {
                    onSelect(spec)
                } else {
                    onDownload(spec, binding)
                }
            }

            binding.btnMotionModelDownload.setOnClickListener {
                onDownload(spec, binding)
            }

            binding.btnMotionModelDelete.setOnClickListener {
                onDelete(spec, binding)
            }
        }
    }
}
