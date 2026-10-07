package com.veilframe.app.ui.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.veilframe.app.databinding.SheetSharedSourcePickerBinding

/**
 * Shared modal source picker offering Camera, Photos, and Files.
 * Unified across Image Studio, Video Studio, Document Scanner, Background Remover,
 * Image Quality, and Sanitizer workspaces.
 */
class SharedSourcePickerSheet : BottomSheetDialogFragment() {

    enum class SourceType {
        CAMERA,
        PHOTOS,
        FILES
    }

    enum class MediaType {
        IMAGE_ONLY,
        VIDEO_ONLY,
        IMAGE_AND_VIDEO,
        DOCUMENT_ONLY
    }

    private var _binding: SheetSharedSourcePickerBinding? = null
    private val binding get() = _binding!!

    private var onSourceSelected: ((SourceType) -> Unit)? = null
    private var mediaType: MediaType = MediaType.IMAGE_AND_VIDEO
    private var titleText: String = "Choose Media Source"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSharedSourcePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPickerTitle.text = titleText

        when (mediaType) {
            MediaType.DOCUMENT_ONLY -> {
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.GONE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
            else -> {
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.VISIBLE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
        }

        binding.btnSourceCamera.setOnClickListener {
            onSourceSelected?.invoke(SourceType.CAMERA)
            dismiss()
        }

        binding.btnSourcePhotos.setOnClickListener {
            onSourceSelected?.invoke(SourceType.PHOTOS)
            dismiss()
        }

        binding.btnSourceFiles.setOnClickListener {
            onSourceSelected?.invoke(SourceType.FILES)
            dismiss()
        }

        binding.btnPickerCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SharedSourcePickerSheet"

        fun newInstance(
            title: String = "Choose Media Source",
            mediaType: MediaType = MediaType.IMAGE_AND_VIDEO,
            onSelected: (SourceType) -> Unit
        ): SharedSourcePickerSheet {
            return SharedSourcePickerSheet().apply {
                this.titleText = title
                this.mediaType = mediaType
                this.onSourceSelected = onSelected
            }
        }
    }
}
