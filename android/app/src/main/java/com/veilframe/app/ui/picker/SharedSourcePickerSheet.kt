package com.veilframe.app.ui.picker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.veilframe.app.databinding.SheetSharedSourcePickerBinding

/**
 * Shared modal source picker offering Camera, Photos, and Files.
 * Uses Android's lifecycle-safe FragmentResult API to avoid retained callback leaks.
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

    enum class Mode {
        MEDIA,
        IMAGE,
        VIDEO,
        DOCUMENT_SCANNER
    }

    private var _binding: SheetSharedSourcePickerBinding? = null
    private val binding get() = _binding!!

    // Optional legacy callback for direct in-memory invocation
    private var onSourceSelected: ((SourceType) -> Unit)? = null

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

        val args = arguments
        val requestKey = args?.getString(ARG_REQUEST_KEY) ?: DEFAULT_REQUEST_KEY
        val modeName = args?.getString(ARG_MODE) ?: Mode.MEDIA.name
        val mode = try {
            Mode.valueOf(modeName)
        } catch (e: Exception) {
            Mode.MEDIA
        }
        val customTitle = args?.getString(ARG_TITLE)

        // Configure UI based on Mode
        when (mode) {
            Mode.DOCUMENT_SCANNER -> {
                binding.tvPickerTitle.text = customTitle ?: "Scan Document"
                binding.tvLabelCamera.text = "Take Photo"
                binding.tvLabelPhotos.text = "Choose Photos"
                binding.tvLabelFiles.text = "Import Files"
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.VISIBLE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
            Mode.IMAGE -> {
                binding.tvPickerTitle.text = customTitle ?: "Select Image"
                binding.tvLabelCamera.text = "Camera"
                binding.tvLabelPhotos.text = "Photos"
                binding.tvLabelFiles.text = "Files"
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.VISIBLE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
            Mode.VIDEO -> {
                binding.tvPickerTitle.text = customTitle ?: "Select Video"
                binding.tvLabelCamera.text = "Record"
                binding.tvLabelPhotos.text = "Videos"
                binding.tvLabelFiles.text = "Files"
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.VISIBLE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
            Mode.MEDIA -> {
                binding.tvPickerTitle.text = customTitle ?: "Choose Media Source"
                binding.tvLabelCamera.text = "Camera"
                binding.tvLabelPhotos.text = "Photos"
                binding.tvLabelFiles.text = "Files"
                binding.btnSourceCamera.visibility = View.VISIBLE
                binding.btnSourcePhotos.visibility = View.VISIBLE
                binding.btnSourceFiles.visibility = View.VISIBLE
            }
        }

        binding.btnSourceCamera.setOnClickListener {
            notifySelection(requestKey, SourceType.CAMERA)
        }

        binding.btnSourcePhotos.setOnClickListener {
            notifySelection(requestKey, SourceType.PHOTOS)
        }

        binding.btnSourceFiles.setOnClickListener {
            notifySelection(requestKey, SourceType.FILES)
        }

        binding.btnPickerCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun notifySelection(requestKey: String, sourceType: SourceType) {
        setFragmentResult(
            requestKey,
            bundleOf(EXTRA_SOURCE_TYPE to sourceType.name)
        )
        onSourceSelected?.invoke(sourceType)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SharedSourcePickerSheet"
        const val DEFAULT_REQUEST_KEY = "request_shared_source_picker"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val ARG_TITLE = "arg_title"
        const val ARG_MODE = "arg_mode"

        /**
         * Lifecycle-safe factory accepting a requestKey for [setFragmentResult].
         */
        fun newInstance(
            requestKey: String = DEFAULT_REQUEST_KEY,
            title: String? = null,
            mode: Mode = Mode.MEDIA
        ): SharedSourcePickerSheet {
            return SharedSourcePickerSheet().apply {
                arguments = bundleOf(
                    ARG_REQUEST_KEY to requestKey,
                    ARG_TITLE to title,
                    ARG_MODE to mode.name
                )
            }
        }

        /**
         * Convenience factory that also supports direct callback invocation.
         */
        fun newInstanceWithCallback(
            requestKey: String = DEFAULT_REQUEST_KEY,
            title: String? = null,
            mode: Mode = Mode.MEDIA,
            onSelected: (SourceType) -> Unit
        ): SharedSourcePickerSheet {
            return newInstance(requestKey, title, mode).apply {
                this.onSourceSelected = onSelected
            }
        }

        /**
         * Backwards-compatible factory accepting [MediaType] and direct lambda.
         */
        fun newInstance(
            title: String = "Choose Media Source",
            mediaType: MediaType = MediaType.IMAGE_AND_VIDEO,
            onSelected: (SourceType) -> Unit
        ): SharedSourcePickerSheet {
            val mode = when (mediaType) {
                MediaType.IMAGE_ONLY -> Mode.IMAGE
                MediaType.VIDEO_ONLY -> Mode.VIDEO
                MediaType.DOCUMENT_ONLY -> Mode.DOCUMENT_SCANNER
                MediaType.IMAGE_AND_VIDEO -> Mode.MEDIA
            }
            return newInstanceWithCallback(
                requestKey = DEFAULT_REQUEST_KEY,
                title = title,
                mode = mode,
                onSelected = onSelected
            )
        }
    }
}
