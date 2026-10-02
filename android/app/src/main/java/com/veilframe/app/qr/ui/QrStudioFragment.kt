package com.veilframe.app.qr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputLayout
import com.veilframe.app.R
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.RngMode
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.QrScanner
import com.veilframe.app.qr.scanner.action.QrActionExecutor
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * QR Studio Fragment — host fragment for the QR Studio tool.
 * Provides two tabs:
 * 1. Generate — create stylized QR codes with 12 artistic styles, Guided Presets, logos, and custom colors
 * 2. Scan — scan QR codes via CameraX or image gallery import with safe intent dispatch
 */
class QrStudioFragment : Fragment() {

    private val vm: QrStudioViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_studio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.qr_toolbar)
        val tabs = view.findViewById<TabLayout>(R.id.qr_tabs)
        val pager = view.findViewById<ViewPager2>(R.id.qr_pager)
        val loadingProgress = view.findViewById<ProgressBar>(R.id.qr_loading_progress)

        // Observe loading state to drive the top-level progress bar (only for final export tasks to avoid dual spinner noise)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    loadingProgress?.visibility = if (state.isExporting) View.VISIBLE else View.GONE
                }
            }
        }

        // Terminate session cleanly when back is pressed
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                vm.terminateSession()
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        toolbar.setNavigationOnClickListener {
            vm.terminateSession()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) QrGenerateTabFragment() else QrScanTabFragment()
            }
        }

        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = if (position == 0) "Generate" else "Scan"
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Do not call vm.terminateSession() here: an Activity-scoped ViewModel is designed
        // to survive Fragment configuration changes (like rotation) and view recreation.
        // Clean session termination happens via back navigation and ViewModel.onCleared().
    }
}

/**
 * Generate tab fragment allowing user input, style selection, guided presets, and real-time preview.
 */
class QrGenerateTabFragment : Fragment() {

    private val vm: QrStudioViewModel by activityViewModels()
    private var previewAnimJob: kotlinx.coroutines.Job? = null
    private var activePreviewFrames: List<com.veilframe.app.qr.model.QrFrame>? = null

    private val logoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val mimeType = ctx.contentResolver.getType(uri) ?: ""
            val uriStr = uri.toString().lowercase(java.util.Locale.ROOT)
            val isGif = mimeType.equals("image/gif", ignoreCase = true) || uriStr.endsWith(".gif")
            val isWebp = mimeType.equals("image/webp", ignoreCase = true) || uriStr.endsWith(".webp")
            val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
                uriStr.endsWith(".mp4") || uriStr.endsWith(".mov") || uriStr.endsWith(".webm") || uriStr.endsWith(".mkv")

            if (isGif || isWebp || isVideo) {
                val frames = com.veilframe.app.qr.AnimatedMediaHelper.extractFrames(ctx, uri)
                if (frames.size > 1) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            vm.updateLogoAnimatedFrames(frames.map { it.bitmap }, frames.map { it.durationMs })
                            val label = if (isGif) "GIF" else if (isWebp) "WebP" else "video"
                            Toast.makeText(requireContext(), "Imported animated logo $label (${frames.size} frames)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                } else if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            vm.updateLogo(frames[0].bitmap)
                        }
                    }
                    return@launch
                }
            }

            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    val sampleSize = calculateInSampleSize(options, 512, 512)
                    ctx.contentResolver.openInputStream(uri)?.use { s2 ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (bmp != null) {
                        vm.updateLogo(bmp)
                    } else {
                        Toast.makeText(requireContext(), "Could not load logo: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val bgImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    val sampleSize = calculateInSampleSize(options, 1024, 1024)
                    ctx.contentResolver.openInputStream(uri)?.use { s2 ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (bmp != null) {
                        vm.updateBackgroundImage(bmp)
                    } else {
                        Toast.makeText(requireContext(), "Could not load background: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val backdropImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    val sampleSize = calculateInSampleSize(options, 1024, 1024)
                    ctx.contentResolver.openInputStream(uri)?.use { s2 ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (bmp != null) {
                        vm.updateBackdropImage(bmp)
                    } else {
                        Toast.makeText(requireContext(), "Could not load backdrop: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val resampleBackdropPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    val sampleSize = calculateInSampleSize(options, 1024, 1024)
                    ctx.contentResolver.openInputStream(uri)?.use { s2 ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (bmp != null) {
                        vm.updateResampleBackdropImage(bmp)
                    } else {
                        Toast.makeText(requireContext(), "Could not load image: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val sourceImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val mimeType = ctx.contentResolver.getType(uri) ?: ""
            val uriStr = uri.toString().lowercase(java.util.Locale.ROOT)
            val isGif = mimeType.equals("image/gif", ignoreCase = true) || uriStr.endsWith(".gif")
            val isWebp = mimeType.equals("image/webp", ignoreCase = true) || uriStr.endsWith(".webp")
            val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
                uriStr.endsWith(".mp4") || uriStr.endsWith(".mov") || uriStr.endsWith(".webm")

            if (isGif || isWebp || isVideo) {
                val frames = com.veilframe.app.qr.AnimatedMediaHelper.extractFrames(ctx, uri)
                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            vm.updateAnimatedFrames(frames)
                            val label = if (isGif) "GIF" else if (isWebp) "WebP" else "video"
                            android.widget.Toast.makeText(requireContext(), "Imported $label (${frames.size} frames)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }
            }

            // Standard static bitmap fallback
            val bmp = try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    val sampleSize = calculateInSampleSize(options, 1024, 1024)
                    ctx.contentResolver.openInputStream(uri)?.use { s2 ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    if (bmp != null) {
                        vm.updateSourceImage(bmp)
                    } else {
                        Toast.makeText(requireContext(), "Could not load media: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_generate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val previewCard        = view.findViewById<MaterialCardView>(R.id.qr_preview_card)
        val previewImage       = view.findViewById<ImageView>(R.id.qr_preview_image)
        val previewEmptyState  = view.findViewById<View>(R.id.qr_preview_empty_state)
        val previewProgress    = view.findViewById<ProgressBar>(R.id.qr_preview_progress)

        // Adaptive preview size: min(screenWidth - 32dp, 360dp)
        val dm = resources.displayMetrics
        val targetSize = minOf(dm.widthPixels - (32 * dm.density).toInt(), (360 * dm.density).toInt())
        previewCard?.layoutParams?.let { lp ->
            lp.width = targetSize
            lp.height = targetSize
            previewCard.layoutParams = lp
        }

        val scanabilityCard    = view.findViewById<MaterialCardView>(R.id.qr_scanability_card)
        val scanabilityIcon    = view.findViewById<ImageView>(R.id.qr_scanability_icon)
        val scanabilityStatus  = view.findViewById<TextView>(R.id.qr_scanability_status)
        val scanabilityDetails = view.findViewById<TextView>(R.id.qr_scanability_details)
        val scanabilityDetailsToggle = view.findViewById<MaterialButton>(R.id.qr_scanability_details_toggle)
        val scanabilityTechContainer = view.findViewById<LinearLayout>(R.id.container_scanability_tech_details)
        val scanabilityTechText = view.findViewById<TextView>(R.id.qr_scanability_tech_text)
        val autoRepairBtn      = view.findViewById<MaterialButton>(R.id.qr_auto_repair_btn)
        val testWithCameraBtn  = view.findViewById<MaterialButton>(R.id.qr_test_with_camera_btn)
        val previewAnimationBadge = view.findViewById<View>(R.id.qr_preview_animation_badge)

        testWithCameraBtn?.setOnClickListener {
            val pager = parentFragment?.view?.findViewById<ViewPager2>(R.id.qr_pager)
                ?: requireActivity().findViewById<ViewPager2>(R.id.qr_pager)
            pager?.currentItem = 1
            Toast.makeText(requireContext(), "Camera scanner active — test your QR code", Toast.LENGTH_SHORT).show()
        }

        val headerAdvanced = view.findViewById<View>(R.id.qr_header_advanced_settings)
        val containerAdvanced = view.findViewById<LinearLayout>(R.id.container_advanced_settings_body)
        val iconToggleAdvanced = view.findViewById<ImageView>(R.id.qr_icon_toggle_advanced)
        headerAdvanced?.setOnClickListener {
            vm.toggleAdvancedExpanded()
        }

        // Animation section
        val animationStatusBanner = view.findViewById<TextView>(R.id.qr_animation_status_banner)
        val containerAnimatedExport = view.findViewById<LinearLayout>(R.id.container_animated_export)
        val pickAnimWatermarkBtn = view.findViewById<MaterialButton>(R.id.qr_btn_pick_animated_watermark)
        val pickAnimLogoBtn = view.findViewById<MaterialButton>(R.id.qr_btn_pick_animated_logo)
        val clearAnimBtn = view.findViewById<MaterialButton>(R.id.qr_btn_clear_animation)

        pickAnimWatermarkBtn?.setOnClickListener {
            sourceImagePickerLauncher.launch("*/*")
        }
        pickAnimLogoBtn?.setOnClickListener {
            logoPickerLauncher.launch("*/*")
        }
        clearAnimBtn?.setOnClickListener {
            vm.clearAnimation()
            Toast.makeText(requireContext(), "Animation cleared", Toast.LENGTH_SHORT).show()
        }

        scanabilityDetailsToggle?.setOnClickListener {
            vm.toggleScanDetailsExpanded()
        }

        // Presets Chips & Containers
        val presetChipGroup    = view.findViewById<ChipGroup>(R.id.qr_preset_chip_group)
        val containerText      = view.findViewById<LinearLayout>(R.id.container_preset_text)
        val containerWifi      = view.findViewById<LinearLayout>(R.id.container_preset_wifi)
        val containerVcard     = view.findViewById<LinearLayout>(R.id.container_preset_vcard)
        val containerEmail     = view.findViewById<LinearLayout>(R.id.container_preset_email)
        val containerSms       = view.findViewById<LinearLayout>(R.id.container_preset_sms)
        val containerUpi       = view.findViewById<LinearLayout>(R.id.container_preset_upi)

        // 1. URL / Text
        val contentInput       = view.findViewById<EditText>(R.id.qr_content_input)

        // 2. Wi-Fi
        val wifiSsid           = view.findViewById<EditText>(R.id.qr_wifi_ssid)
        val wifiPassword       = view.findViewById<EditText>(R.id.qr_wifi_password)
        val wifiSecurity       = view.findViewById<Spinner>(R.id.qr_wifi_security_spinner)
        val wifiHidden         = view.findViewById<CheckBox>(R.id.qr_wifi_hidden_check)

        val secOptions = arrayOf("WPA / WPA2 / WPA3", "WEP", "Open (None)")
        val secCodes = arrayOf("WPA", "WEP", "nopass")
        wifiSecurity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, secOptions)

        // 3. Contact (vCard)
        val vcardFirst         = view.findViewById<EditText>(R.id.qr_vcard_first_name)
        val vcardLast          = view.findViewById<EditText>(R.id.qr_vcard_last_name)
        val vcardPhone         = view.findViewById<EditText>(R.id.qr_vcard_phone)
        val vcardEmail         = view.findViewById<EditText>(R.id.qr_vcard_email)
        val vcardOrg           = view.findViewById<EditText>(R.id.qr_vcard_org)
        val vcardUrl           = view.findViewById<EditText>(R.id.qr_vcard_url)

        // 4. Email
        val emailRecipient     = view.findViewById<EditText>(R.id.qr_email_recipient)
        val emailSubject       = view.findViewById<EditText>(R.id.qr_email_subject)
        val emailBody          = view.findViewById<EditText>(R.id.qr_email_body)

        // 5. SMS
        val smsPhone           = view.findViewById<EditText>(R.id.qr_sms_phone)
        val smsBody            = view.findViewById<EditText>(R.id.qr_sms_body)

        // 6. UPI
        val layoutUpiAmount    = view.findViewById<TextInputLayout>(R.id.layout_upi_amount)
        val upiVpa             = view.findViewById<EditText>(R.id.qr_upi_vpa)
        val upiAmount          = view.findViewById<EditText>(R.id.qr_upi_amount)
        val upiPayeeName       = view.findViewById<EditText>(R.id.qr_upi_payee_name)
        val upiNote            = view.findViewById<EditText>(R.id.qr_upi_note)
        val upiAmountSlider    = view.findViewById<Slider>(R.id.qr_upi_amount_slider)
        val upiAmountStatus    = view.findViewById<TextView>(R.id.qr_upi_amount_status)
        val upiClearAmountBtn  = view.findViewById<MaterialButton>(R.id.qr_upi_clear_amount_btn)
        val upiChipNone        = view.findViewById<Chip>(R.id.chip_upi_none)
        val upiChip50          = view.findViewById<Chip>(R.id.chip_upi_50)
        val upiChip100         = view.findViewById<Chip>(R.id.chip_upi_100)
        val upiChip200         = view.findViewById<Chip>(R.id.chip_upi_200)
        val upiChip500         = view.findViewById<Chip>(R.id.chip_upi_500)
        val upiChip1000        = view.findViewById<Chip>(R.id.chip_upi_1000)
        val upiChip2000        = view.findViewById<Chip>(R.id.chip_upi_2000)
        val upiChip5000        = view.findViewById<Chip>(R.id.chip_upi_5000)
        val upiChip10000       = view.findViewById<Chip>(R.id.chip_upi_10000)
        val upiChip50000       = view.findViewById<Chip>(R.id.chip_upi_50000)
        val upiChip1lakh       = view.findViewById<Chip>(R.id.chip_upi_1lakh)

        // Complete Form State Hydration from ViewModel
        val initState = vm.state.value
        contentInput.setText(initState.content)
        wifiSsid.setText(initState.wifiSsid)
        wifiPassword.setText(initState.wifiPassword)
        wifiSecurity.setSelection(initState.wifiSecurityPos.coerceIn(0, secOptions.size - 1))
        wifiHidden.isChecked = initState.wifiHidden

        vcardFirst.setText(initState.vcardFirst)
        vcardLast.setText(initState.vcardLast)
        vcardPhone.setText(initState.vcardPhone)
        vcardEmail.setText(initState.vcardEmail)
        vcardOrg.setText(initState.vcardOrg)
        vcardUrl.setText(initState.vcardUrl)

        emailRecipient.setText(initState.emailRecipient)
        emailSubject.setText(initState.emailSubject)
        emailBody.setText(initState.emailBody)

        smsPhone.setText(initState.smsPhone)
        smsBody.setText(initState.smsBody)

        upiVpa.setText(initState.upiVpa)
        upiAmount.setText(initState.upiAmount)
        upiPayeeName.setText(initState.upiPayeeName)
        upiNote.setText(initState.upiNote)

        val checkedPresetId = initState.activePresetId
        presetChipGroup.check(checkedPresetId)
        containerText.visibility  = if (checkedPresetId == R.id.chip_preset_text) View.VISIBLE else View.GONE
        containerWifi.visibility  = if (checkedPresetId == R.id.chip_preset_wifi) View.VISIBLE else View.GONE
        containerVcard.visibility = if (checkedPresetId == R.id.chip_preset_vcard) View.VISIBLE else View.GONE
        containerEmail.visibility = if (checkedPresetId == R.id.chip_preset_email) View.VISIBLE else View.GONE
        containerSms.visibility   = if (checkedPresetId == R.id.chip_preset_sms) View.VISIBLE else View.GONE
        containerUpi.visibility   = if (checkedPresetId == R.id.chip_preset_upi) View.VISIBLE else View.GONE

        // Generator Config
        val styleChipGroup     = view.findViewById<ChipGroup>(R.id.qr_style_chip_group)
        val resSpinner         = view.findViewById<Spinner>(R.id.qr_resolution_spinner)
        val resHint            = view.findViewById<TextView>(R.id.qr_resolution_hint)
        val ecSpinner          = view.findViewById<Spinner>(R.id.qr_ec_spinner)

        val fgColorBtn         = view.findViewById<MaterialButton>(R.id.qr_fg_color_btn)
        val bgColorBtn         = view.findViewById<MaterialButton>(R.id.qr_bg_color_btn)
        val logoBtn            = view.findViewById<MaterialButton>(R.id.qr_logo_btn)
        val sourceImgBtn       = view.findViewById<MaterialButton>(R.id.qr_source_image_btn)
        val bgImageBtn         = view.findViewById<MaterialButton>(R.id.qr_bg_image_btn)

        // Dynamic Customization Cards
        val cardSourceControls = view.findViewById<MaterialCardView>(R.id.card_source_image_controls)
        val removeSourceBtn    = view.findViewById<MaterialButton>(R.id.qr_remove_source_img_btn)
        val sourceScaleSpinner = view.findViewById<Spinner>(R.id.qr_source_scale_spinner)
        val sourceContrastSlider = view.findViewById<Slider>(R.id.qr_source_contrast_slider)
        val sourceContrastLabel = view.findViewById<TextView>(R.id.qr_source_contrast_label)
        val sourceExposureSlider = view.findViewById<Slider>(R.id.qr_source_exposure_slider)
        val sourceExposureLabel = view.findViewById<TextView>(R.id.qr_source_exposure_label)
        val sourceOpacitySlider = view.findViewById<Slider>(R.id.qr_source_opacity_slider)
        val sourceOpacityLabel = view.findViewById<TextView>(R.id.qr_source_opacity_label)
        val resampleBackdropSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.qr_resample_backdrop_switch)
        val containerBackdropOpacity = view.findViewById<LinearLayout>(R.id.container_resample_backdrop_opacity)
        val resampleBackdropOpacitySlider = view.findViewById<Slider>(R.id.qr_resample_backdrop_opacity_slider)
        val resampleBackdropOpacityLabel = view.findViewById<TextView>(R.id.qr_resample_backdrop_opacity_label)
        val resampleSeedBtn = view.findViewById<MaterialButton>(R.id.qr_resample_seed_btn)

        val cardBgControls     = view.findViewById<MaterialCardView>(R.id.card_bg_image_controls)
        val removeBgBtn        = view.findViewById<MaterialButton>(R.id.qr_remove_bg_btn)
        val bgOpacitySlider    = view.findViewById<Slider>(R.id.qr_bg_opacity_slider)
        val bgOpacityLabel     = view.findViewById<TextView>(R.id.qr_bg_opacity_label)

        val cardLogoControls   = view.findViewById<MaterialCardView>(R.id.card_logo_controls)
        val removeLogoBtn      = view.findViewById<MaterialButton>(R.id.qr_remove_logo_btn)
        val logoSizeSlider     = view.findViewById<Slider>(R.id.qr_logo_size_slider)
        val logoSizeLabel      = view.findViewById<TextView>(R.id.qr_logo_size_label)

        val containerResampleControls = view.findViewById<LinearLayout>(R.id.container_resample_specific_controls)
        val containerImageControls    = view.findViewById<LinearLayout>(R.id.container_image_specific_controls)
        val imageAllowTransparentSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.qr_image_allow_transparent_switch)
        val imageDataScaleSlider      = view.findViewById<Slider>(R.id.qr_image_data_scale_slider)
        val imageDataScaleLabel       = view.findViewById<TextView>(R.id.qr_image_data_scale_label)
        val animatedExportLabel       = view.findViewById<TextView>(R.id.qr_animated_export_label)

        val saveBtn            = view.findViewById<MaterialButton>(R.id.qr_save_btn)
        val saveSvgBtn         = view.findViewById<MaterialButton>(R.id.qr_save_svg_btn)
        val saveJpegBtn        = view.findViewById<MaterialButton>(R.id.qr_save_jpeg_btn)
        val saveGifBtn         = view.findViewById<MaterialButton>(R.id.qr_save_gif_btn)
        val saveVideoBtn       = view.findViewById<MaterialButton>(R.id.qr_save_video_btn)
        val saveAnimatedSvgBtn = view.findViewById<MaterialButton>(R.id.qr_save_animated_svg_btn)
        val shareBtn           = view.findViewById<MaterialButton>(R.id.qr_share_btn)
        val exportStatusBanner = view.findViewById<TextView>(R.id.qr_export_status_banner)

        // Setup Source Scale Spinner & Restore State from ViewModel
        val scaleOptions = arrayOf("Aspect Fill", "Aspect Fit", "Center Crop", "Stretch")
        val scaleEnums = arrayOf(
            ImageScaleMode.ASPECT_FILL,
            ImageScaleMode.ASPECT_FIT,
            ImageScaleMode.CENTER_CROP,
            ImageScaleMode.STRETCH
        )
        sourceScaleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scaleOptions)
        val currentScaleIdx = scaleEnums.indexOf(vm.state.value.sourceImageScaleMode).coerceAtLeast(0)
        sourceScaleSpinner.setSelection(currentScaleIdx)
        sourceScaleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateSourceImageScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Helper to compile active preset into payload and update ViewModel form state
        fun compileActivePreset() {
            val checkedId = presetChipGroup.checkedChipId
            val payload = when (checkedId) {
                R.id.chip_preset_wifi -> {
                    val ssid = wifiSsid.text.toString()
                    val pass = wifiPassword.text.toString()
                    val pos = wifiSecurity.selectedItemPosition.coerceIn(0, secCodes.size - 1)
                    val sec = secCodes[pos]
                    val hidden = wifiHidden.isChecked
                    vm.updateWifiForm(ssid, pass, pos, hidden)
                    if (ssid.isNotBlank()) QrPresetFormatter.formatWifi(ssid, pass, sec, hidden) else ""
                }
                R.id.chip_preset_vcard -> {
                    val first = vcardFirst.text.toString()
                    val last = vcardLast.text.toString()
                    val phone = vcardPhone.text.toString()
                    val email = vcardEmail.text.toString()
                    val org = vcardOrg.text.toString()
                    val url = vcardUrl.text.toString()
                    vm.updateVcardForm(first, last, phone, email, org, url)
                    if (first.isNotBlank() || last.isNotBlank() || phone.isNotBlank()) {
                        QrPresetFormatter.formatVCard(first, last, phone, email, org, url = url)
                    } else ""
                }
                R.id.chip_preset_email -> {
                    val recipient = emailRecipient.text.toString()
                    val subject = emailSubject.text.toString()
                    val body = emailBody.text.toString()
                    vm.updateEmailForm(recipient, subject, body)
                    if (recipient.isNotBlank()) QrPresetFormatter.formatEmail(recipient, subject, body) else ""
                }
                R.id.chip_preset_sms -> {
                    val phone = smsPhone.text.toString()
                    val body = smsBody.text.toString()
                    vm.updateSmsForm(phone, body)
                    if (phone.isNotBlank()) QrPresetFormatter.formatSms(phone, body) else ""
                }
                R.id.chip_preset_upi -> {
                    val vpa = upiVpa.text.toString().trim()
                    val amount = upiAmount.text.toString().trim()
                    val payee = upiPayeeName.text.toString().trim()
                    val note = upiNote.text.toString().trim()
                    vm.updateUpiForm(vpa, amount, payee, note)
                    if (vpa.isNotBlank()) QrPresetFormatter.formatUpi(vpa = vpa, amount = amount, payeeName = payee, note = note) else ""
                }
                else -> {
                    contentInput.text.toString()
                }
            }
            vm.updateContent(payload)
        }

        // Preset Chip Switching
        presetChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_preset_text
            vm.updateActivePreset(checkedId)
            containerText.visibility  = if (checkedId == R.id.chip_preset_text) View.VISIBLE else View.GONE
            containerWifi.visibility  = if (checkedId == R.id.chip_preset_wifi) View.VISIBLE else View.GONE
            containerVcard.visibility = if (checkedId == R.id.chip_preset_vcard) View.VISIBLE else View.GONE
            containerEmail.visibility = if (checkedId == R.id.chip_preset_email) View.VISIBLE else View.GONE
            containerSms.visibility   = if (checkedId == R.id.chip_preset_sms) View.VISIBLE else View.GONE
            containerUpi.visibility   = if (checkedId == R.id.chip_preset_upi) View.VISIBLE else View.GONE

            compileActivePreset()
        }

        // Generic text watcher for live updates
        val liveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                compileActivePreset()
            }
        }

        contentInput.addTextChangedListener(liveWatcher)
        wifiSsid.addTextChangedListener(liveWatcher)
        wifiPassword.addTextChangedListener(liveWatcher)
        wifiSecurity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { compileActivePreset() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        wifiHidden.setOnCheckedChangeListener { _, _ -> compileActivePreset() }

        vcardFirst.addTextChangedListener(liveWatcher)
        vcardLast.addTextChangedListener(liveWatcher)
        vcardPhone.addTextChangedListener(liveWatcher)
        vcardEmail.addTextChangedListener(liveWatcher)
        vcardOrg.addTextChangedListener(liveWatcher)
        vcardUrl.addTextChangedListener(liveWatcher)

        emailRecipient.addTextChangedListener(liveWatcher)
        emailSubject.addTextChangedListener(liveWatcher)
        emailBody.addTextChangedListener(liveWatcher)

        smsPhone.addTextChangedListener(liveWatcher)
        smsBody.addTextChangedListener(liveWatcher)

        upiPayeeName.addTextChangedListener(liveWatcher)
        upiNote.addTextChangedListener(liveWatcher)

        // UPI Amount Slider & Text synchronization (Up to 1 Lakh INR)
        var isUpdatingUpiAmount = false
        val MAX_UPI_AMOUNT = 100000f

        fun syncUpiAmount(value: Float, updateText: Boolean, updateSlider: Boolean) {
            isUpdatingUpiAmount = true
            if (value <= 0f) {
                if (updateText) upiAmount.setText("")
                if (updateSlider) {
                    try {
                        upiAmountSlider.value = upiAmountSlider.valueFrom
                    } catch (e: Exception) {
                        android.util.Log.w("QrStudio", "Slider reset error", e)
                    }
                }
                upiAmountStatus.text = "Optional"
                layoutUpiAmount?.error = null
            } else {
                val clamped = value.coerceAtMost(MAX_UPI_AMOUNT)
                val formatted = if (clamped % 1f == 0f) {
                    clamped.toInt().toString()
                } else {
                    String.format(java.util.Locale.US, "%.2f", clamped)
                }
                if (updateText) upiAmount.setText(formatted)
                if (updateSlider) {
                    try {
                        val safeVal = clamped.coerceIn(upiAmountSlider.valueFrom, upiAmountSlider.valueTo)
                        upiAmountSlider.value = safeVal
                    } catch (e: Exception) {
                        android.util.Log.w("QrStudio", "Slider set value error: $clamped", e)
                    }
                }
                upiAmountStatus.text = "Amount: ₹$formatted"
                if (value > MAX_UPI_AMOUNT) {
                    layoutUpiAmount?.error = "Maximum ₹1,00,000"
                } else {
                    layoutUpiAmount?.error = null
                }
            }
            isUpdatingUpiAmount = false
            compileActivePreset()
        }

        // If amount was restored, sync slider
        if (initState.upiAmount.isNotBlank()) {
            initState.upiAmount.toFloatOrNull()?.let { num ->
                syncUpiAmount(num, updateText = false, updateSlider = true)
            }
        }

        upiVpa.addTextChangedListener(object : TextWatcher {
            private var isSelfEditing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isSelfEditing) return
                val raw = s?.toString()?.trim().orEmpty()
                if (raw.startsWith("upi://pay?", ignoreCase = true)) {
                    val parsed = QrPresetFormatter.parseUpiUri(raw)
                    val extractedPa = parsed["pa"].orEmpty()
                    val extractedAm = parsed["am"].orEmpty()
                    val extractedPn = parsed["pn"].orEmpty()
                    val extractedTn = parsed["tn"].orEmpty()
                    if (extractedPa.isNotBlank()) {
                        isSelfEditing = true
                        upiVpa.setText(extractedPa)
                        upiVpa.setSelection(extractedPa.length)
                        isSelfEditing = false
                    }
                    if (extractedPn.isNotBlank()) {
                        upiPayeeName.setText(extractedPn)
                    }
                    if (extractedTn.isNotBlank()) {
                        upiNote.setText(extractedTn)
                    }
                    if (extractedAm.isNotBlank()) {
                        val num = extractedAm.toFloatOrNull()
                        if (num != null) {
                            syncUpiAmount(num, updateText = true, updateSlider = true)
                        }
                    }
                }
                compileActivePreset()
            }
        })

        upiAmountSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingUpiAmount) {
                syncUpiAmount(value, updateText = true, updateSlider = false)
            }
        }

        upiAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUpiAmount) {
                    val text = s?.toString()?.trim().orEmpty()
                    val num = text.toFloatOrNull()
                    if (num != null && num > 0f) {
                        if (num > MAX_UPI_AMOUNT) {
                            layoutUpiAmount?.error = "Maximum ₹1,00,000"
                        } else {
                            layoutUpiAmount?.error = null
                        }
                        syncUpiAmount(num, updateText = false, updateSlider = (num <= upiAmountSlider.valueTo))
                    } else {
                        layoutUpiAmount?.error = null
                        syncUpiAmount(0f, updateText = false, updateSlider = true)
                    }
                }
            }
        })

        upiClearAmountBtn.setOnClickListener {
            syncUpiAmount(0f, updateText = true, updateSlider = true)
        }

        upiChipNone?.setOnClickListener  { syncUpiAmount(0f, updateText = true, updateSlider = true) }
        upiChip50?.setOnClickListener    { syncUpiAmount(50f, updateText = true, updateSlider = true) }
        upiChip100?.setOnClickListener   { syncUpiAmount(100f, updateText = true, updateSlider = true) }
        upiChip200?.setOnClickListener   { syncUpiAmount(200f, updateText = true, updateSlider = true) }
        upiChip500?.setOnClickListener   { syncUpiAmount(500f, updateText = true, updateSlider = true) }
        upiChip1000?.setOnClickListener  { syncUpiAmount(1000f, updateText = true, updateSlider = true) }
        upiChip2000?.setOnClickListener  { syncUpiAmount(2000f, updateText = true, updateSlider = true) }
        upiChip5000?.setOnClickListener  { syncUpiAmount(5000f, updateText = true, updateSlider = true) }
        upiChip10000?.setOnClickListener { syncUpiAmount(10000f, updateText = true, updateSlider = true) }
        upiChip50000?.setOnClickListener { syncUpiAmount(50000f, updateText = true, updateSlider = true) }
        upiChip1lakh?.setOnClickListener { syncUpiAmount(100000f, updateText = true, updateSlider = true) }

        // 1. Visual Style Selector & Gallery Chips (single source of truth)
        val styleToChipId = mapOf<QrStyle, Int>(
            QrStyle.BASIC to R.id.chip_style_basic,
            QrStyle.BUBBLE to R.id.chip_style_bubble,
            QrStyle.D25 to R.id.chip_style_d25,
            QrStyle.DSJ to R.id.chip_style_dsj,
            QrStyle.LINE to R.id.chip_style_line,
            QrStyle.RANDOM_RECTANGLE to R.id.chip_style_random_rect,
            QrStyle.IMAGE to R.id.chip_style_image,
            QrStyle.IMAGE_FILL to R.id.chip_style_image_fill,
            QrStyle.IMAGE_RESAMPLE to R.id.chip_style_image_resample,
            QrStyle.FUNCTION to R.id.chip_style_func,
            QrStyle.STYLE_FUNCTION to R.id.chip_style_style_func,
            QrStyle.CONNECTED_ORGANIC to R.id.chip_style_composite
        )
        val chipIdToStyle = styleToChipId.entries.associate { (k, v) -> v to k }

        styleToChipId[vm.state.value.style]?.let { chipId ->
            styleChipGroup?.check(chipId)
        }

        styleChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedStyle = chipIdToStyle[checkedId] ?: return@setOnCheckedStateChangeListener
            if (vm.state.value.style != selectedStyle) {
                vm.updateStyle(selectedStyle)
            }
        }

        // Contextual Style Settings Controls
        val cardStyleSettings = view.findViewById<MaterialCardView>(R.id.card_style_settings)
        val containerD25 = view.findViewById<LinearLayout>(R.id.container_style_d25)
        val d25DepthSlider = view.findViewById<Slider>(R.id.qr_d25_depth_slider)
        val d25DepthLabel = view.findViewById<TextView>(R.id.qr_d25_depth_label)
        val d25PosDepthSlider = view.findViewById<Slider>(R.id.qr_d25_pos_depth_slider)
        val d25PosDepthLabel = view.findViewById<TextView>(R.id.qr_d25_pos_depth_label)
        val d25AngleSlider = view.findViewById<Slider>(R.id.qr_d25_angle_slider)
        val d25AngleLabel = view.findViewById<TextView>(R.id.qr_d25_angle_label)

        val containerLine = view.findViewById<LinearLayout>(R.id.container_style_line)
        val lineDirGroup = view.findViewById<ChipGroup>(R.id.qr_line_direction_group)
        val lineVariantGroup = view.findViewById<ChipGroup>(R.id.qr_line_variant_group)
        val lineThicknessSlider = view.findViewById<Slider>(R.id.qr_line_thickness_slider)
        val lineThicknessLabel = view.findViewById<TextView>(R.id.qr_line_thickness_label)

        val containerDsj = view.findViewById<LinearLayout>(R.id.container_style_dsj)
        val dsjLineSlider = view.findViewById<Slider>(R.id.qr_dsj_line_slider)
        val dsjLineLabel = view.findViewById<TextView>(R.id.qr_dsj_line_label)
        val dsjXSlider = view.findViewById<Slider>(R.id.qr_dsj_x_slider)
        val dsjXLabel = view.findViewById<TextView>(R.id.qr_dsj_x_label)

        val containerRandomRect = view.findViewById<LinearLayout>(R.id.container_style_random_rect)
        val randomJitterScaleSlider = view.findViewById<Slider>(R.id.qr_random_jitter_scale_slider)
        val randomJitterScaleLabel = view.findViewById<TextView>(R.id.qr_random_jitter_scale_label)
        val randomJitterOffsetSlider = view.findViewById<Slider>(R.id.qr_random_jitter_offset_slider)
        val randomJitterOffsetLabel = view.findViewById<TextView>(R.id.qr_random_jitter_offset_label)
        val randomRectSeedBtn = view.findViewById<MaterialButton>(R.id.qr_random_rect_seed_btn)

        val containerBubble = view.findViewById<LinearLayout>(R.id.container_style_bubble)
        val bubbleAmbientSwitch = view.findViewById<MaterialSwitch>(R.id.qr_bubble_ambient_switch)
        val bubbleDensitySlider = view.findViewById<Slider>(R.id.qr_bubble_density_slider)
        val bubbleDensityLabel = view.findViewById<TextView>(R.id.qr_bubble_density_label)

        val containerFunction = view.findViewById<LinearLayout>(R.id.container_style_function)
        val functionTypeGroup = view.findViewById<ChipGroup>(R.id.qr_function_type_group)

        val containerImageBackdropRequired = view.findViewById<LinearLayout>(R.id.container_image_backdrop_required)
        val chooseBgRequiredBtn = view.findViewById<MaterialButton>(R.id.qr_choose_bg_required_btn)

        d25DepthSlider?.value = vm.state.value.d25Depth.coerceIn(0.2f, 2.0f)
        d25DepthLabel?.text = String.format(java.util.Locale.US, "Body 3D Depth: %.2fx", vm.state.value.d25Depth)
        d25DepthSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25Depth(value)
                d25DepthLabel?.text = String.format(java.util.Locale.US, "Body 3D Depth: %.2fx", value)
            }
        }

        d25PosDepthSlider?.value = vm.state.value.d25PositionDepth.coerceIn(0.2f, 2.0f)
        d25PosDepthLabel?.text = String.format(java.util.Locale.US, "Finder 3D Depth: %.2fx", vm.state.value.d25PositionDepth)
        d25PosDepthSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25PositionDepth(value)
                d25PosDepthLabel?.text = String.format(java.util.Locale.US, "Finder 3D Depth: %.2fx", value)
            }
        }

        d25AngleSlider?.value = vm.state.value.d25Angle.coerceIn(15.0f, 75.0f)
        d25AngleLabel?.text = String.format(java.util.Locale.US, "Projection Angle: %.0f°", vm.state.value.d25Angle)
        d25AngleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25Angle(value)
                d25AngleLabel?.text = String.format(java.util.Locale.US, "Projection Angle: %.0f°", value)
            }
        }

        val d25TopColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_top_color_btn)
        val d25LeftColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_left_color_btn)
        val d25RightColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_right_color_btn)
        d25TopColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25TopColor ?: vm.state.value.foreground) { vm.updateD25TopColor(it) }
        }
        d25LeftColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25LeftColor) { vm.updateD25Colors(leftColor = it, rightColor = vm.state.value.d25RightColor) }
        }
        d25RightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25RightColor) { vm.updateD25Colors(leftColor = vm.state.value.d25LeftColor, rightColor = it) }
        }

        // EFQRCode parity: lossless bidirectional mapping for all 7 line directions
        val dirChipId = when (vm.state.value.lineDirection) {
            LineDirection.HORIZONTAL -> R.id.chip_line_dir_x
            LineDirection.VERTICAL -> R.id.chip_line_dir_y
            LineDirection.CROSS -> R.id.chip_line_dir_cross
            LineDirection.LOOPBACK, LineDirection.LOOP -> R.id.chip_line_dir_loopback
            LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT, LineDirection.DIAGONAL_FORWARD -> R.id.chip_line_dir_tl_br
            LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT, LineDirection.DIAGONAL_BACKWARD -> R.id.chip_line_dir_tr_bl
            LineDirection.X -> R.id.chip_line_dir_x_pattern
        }
        lineDirGroup?.check(dirChipId)
        lineDirGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_line_dir_x
            val dir = when (id) {
                R.id.chip_line_dir_x -> LineDirection.HORIZONTAL
                R.id.chip_line_dir_y -> LineDirection.VERTICAL
                R.id.chip_line_dir_cross -> LineDirection.CROSS
                R.id.chip_line_dir_loopback -> LineDirection.LOOPBACK
                R.id.chip_line_dir_tl_br -> LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT
                R.id.chip_line_dir_tr_bl -> LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT
                R.id.chip_line_dir_x_pattern -> LineDirection.X
                else -> LineDirection.HORIZONTAL
            }
            vm.updateLineDirection(dir)
        }

        val variantChipId = if (vm.state.value.lineVariant == LineVariant.CIRCUIT) R.id.chip_line_variant_circuit else R.id.chip_line_variant_standard
        lineVariantGroup?.check(variantChipId)
        lineVariantGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_line_variant_standard
            val variant = if (id == R.id.chip_line_variant_circuit) LineVariant.CIRCUIT else LineVariant.EF
            vm.updateLineVariant(variant)
        }

        lineThicknessSlider?.value = vm.state.value.lineThickness.coerceIn(0.1f, 1.0f)
        lineThicknessLabel?.text = String.format(java.util.Locale.US, "Line Thickness: %.2f", vm.state.value.lineThickness)
        lineThicknessSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLineThickness(value)
                lineThicknessLabel?.text = String.format(java.util.Locale.US, "Line Thickness: %.2f", value)
            }
        }

        val lineColorBtn = view.findViewById<MaterialButton>(R.id.qr_line_color_btn)
        val lineHColorBtn = view.findViewById<MaterialButton>(R.id.qr_line_h_color_btn)
        val lineVColorBtn = view.findViewById<MaterialButton>(R.id.qr_line_v_color_btn)
        val lineAccentRingsSwitch = view.findViewById<MaterialSwitch>(R.id.qr_line_accent_rings_switch)
        val lineCircuitBridgesSwitch = view.findViewById<MaterialSwitch>(R.id.qr_line_circuit_bridges_switch)

        lineColorBtn?.setOnClickListener {
            pickColor(vm.state.value.lineColor ?: vm.state.value.foreground) {
                vm.updateLineColors(lineColor = it, hColor = vm.state.value.lineHorizontalColor, vColor = vm.state.value.lineVerticalColor)
            }
        }
        lineHColorBtn?.setOnClickListener {
            pickColor(vm.state.value.lineHorizontalColor ?: vm.state.value.foreground) {
                vm.updateLineColors(lineColor = vm.state.value.lineColor, hColor = it, vColor = vm.state.value.lineVerticalColor)
            }
        }
        lineVColorBtn?.setOnClickListener {
            pickColor(vm.state.value.lineVerticalColor ?: vm.state.value.foreground) {
                vm.updateLineColors(lineColor = vm.state.value.lineColor, hColor = vm.state.value.lineHorizontalColor, vColor = it)
            }
        }
        lineAccentRingsSwitch?.isChecked = vm.state.value.lineAccentRings
        lineAccentRingsSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateLineAccentRings(isChecked)
        }
        lineCircuitBridgesSwitch?.isChecked = vm.state.value.lineCircuitBridges
        lineCircuitBridgesSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateLineCircuitBridges(isChecked)
        }

        dsjLineSlider?.value = vm.state.value.dsjLineSize.coerceIn(0.3f, 1.0f)
        dsjLineLabel?.text = String.format(java.util.Locale.US, "Cross Arm Size: %.2f", vm.state.value.dsjLineSize)
        dsjLineSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateDsjLineSize(value)
                dsjLineLabel?.text = String.format(java.util.Locale.US, "Cross Arm Size: %.2f", value)
            }
        }

        dsjXSlider?.value = vm.state.value.dsjXSize.coerceIn(0.3f, 1.0f)
        dsjXLabel?.text = String.format(java.util.Locale.US, "Center X Size: %.2f", vm.state.value.dsjXSize)
        dsjXSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateDsjXSize(value)
                dsjXLabel?.text = String.format(java.util.Locale.US, "Center X Size: %.2f", value)
            }
        }

        val dsjHColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_h_color_btn)
        val dsjVColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_v_color_btn)
        val dsjXColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_x_color_btn)

        dsjHColorBtn?.setOnClickListener {
            pickColor(vm.state.value.dsjHorizontalColor) {
                vm.updateDsjColors(hColor = it, vColor = vm.state.value.dsjVerticalColor, xColor = vm.state.value.dsjXColor)
            }
        }
        dsjVColorBtn?.setOnClickListener {
            pickColor(vm.state.value.dsjVerticalColor) {
                vm.updateDsjColors(hColor = vm.state.value.dsjHorizontalColor, vColor = it, xColor = vm.state.value.dsjXColor)
            }
        }
        dsjXColorBtn?.setOnClickListener {
            pickColor(vm.state.value.dsjXColor) {
                vm.updateDsjColors(hColor = vm.state.value.dsjHorizontalColor, vColor = vm.state.value.dsjVerticalColor, xColor = it)
            }
        }

        randomJitterScaleSlider?.value = vm.state.value.randomJitterScale.coerceIn(0.0f, 0.5f)
        randomJitterScaleLabel?.text = String.format(java.util.Locale.US, "Jitter Scale: %.2f", vm.state.value.randomJitterScale)
        randomJitterScaleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateRandomJitter(scale = value, offset = vm.state.value.randomJitterOffset)
                randomJitterScaleLabel?.text = String.format(java.util.Locale.US, "Jitter Scale: %.2f", value)
            }
        }

        randomJitterOffsetSlider?.value = vm.state.value.randomJitterOffset.coerceIn(0.0f, 0.3f)
        randomJitterOffsetLabel?.text = String.format(java.util.Locale.US, "Jitter Offset: %.2f", vm.state.value.randomJitterOffset)
        randomJitterOffsetSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateRandomJitter(scale = vm.state.value.randomJitterScale, offset = value)
                randomJitterOffsetLabel?.text = String.format(java.util.Locale.US, "Jitter Offset: %.2f", value)
            }
        }

        val randomJitterColorLabel = view.findViewById<TextView>(R.id.qr_random_jitter_color_label)
        val randomJitterColorSlider = view.findViewById<Slider>(R.id.qr_random_jitter_color_slider)
        val randomRectColorBtn = view.findViewById<MaterialButton>(R.id.qr_random_rect_color_btn)
        val randomSeedInput = view.findViewById<EditText>(R.id.qr_random_seed_input)

        randomJitterColorSlider?.value = vm.state.value.randomJitterColor.coerceIn(0.0f, 1.0f)
        randomJitterColorLabel?.text = String.format(Locale.US, "Color Jitter: %d%%", (vm.state.value.randomJitterColor * 100).toInt())
        randomJitterColorSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateRandomJitterColor(value)
                randomJitterColorLabel?.text = String.format(Locale.US, "Color Jitter: %d%%", (value * 100).toInt())
            }
        }
        randomRectColorBtn?.setOnClickListener {
            pickColor(vm.state.value.randomRectColor ?: vm.state.value.foreground) {
                vm.updateRandomRectColor(it)
            }
        }
        randomSeedInput?.setText(vm.state.value.randomRectSeed.toString())
        randomSeedInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                text.toLongOrNull()?.let { seed ->
                    if (vm.state.value.randomRectSeed != seed) {
                        vm.updateRandomRectSeed(seed)
                    }
                }
            }
        })

        randomRectSeedBtn?.setOnClickListener {
            vm.randomizeRandomRectSeed()
            randomSeedInput?.setText(vm.state.value.randomRectSeed.toString())
            Toast.makeText(requireContext(), "Random pattern seed updated", Toast.LENGTH_SHORT).show()
        }

        bubbleAmbientSwitch?.isChecked = vm.state.value.bubbleAmbient
        bubbleAmbientSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateBubbleCluster(ambient = isChecked, density = vm.state.value.bubbleDensity)
        }
        bubbleDensitySlider?.value = vm.state.value.bubbleDensity.coerceIn(0.05f, 0.40f)
        bubbleDensityLabel?.text = String.format(java.util.Locale.US, "Ambient Density: %d%%", (vm.state.value.bubbleDensity * 100).toInt())
        bubbleDensitySlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBubbleCluster(ambient = vm.state.value.bubbleAmbient, density = value)
                bubbleDensityLabel?.text = String.format(java.util.Locale.US, "Ambient Density: %d%%", (value * 100).toInt())
            }
        }

        val bubbleOutlineColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_outline_color_btn)
        val bubbleCenterColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_center_color_btn)
        val bubblePosColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_pos_color_btn)

        bubbleOutlineColorBtn?.setOnClickListener {
            pickColor(vm.state.value.bubbleOutlineColor ?: vm.state.value.foreground) {
                vm.updateBubbleColors(outline = it, center = vm.state.value.bubbleCenterColor, position = vm.state.value.bubblePositionColor)
            }
        }
        bubbleCenterColorBtn?.setOnClickListener {
            pickColor(vm.state.value.bubbleCenterColor ?: vm.state.value.background) {
                vm.updateBubbleColors(outline = vm.state.value.bubbleOutlineColor, center = it, position = vm.state.value.bubblePositionColor)
            }
        }
        bubblePosColorBtn?.setOnClickListener {
            pickColor(vm.state.value.bubblePositionColor ?: vm.state.value.foreground) {
                vm.updateBubbleColors(outline = vm.state.value.bubbleOutlineColor, center = vm.state.value.bubbleCenterColor, position = it)
            }
        }

        val funcChipId = if (vm.state.value.veilFunctionType == VeilFunctionType.CIRCLE) R.id.chip_func_circle else R.id.chip_func_fade
        functionTypeGroup?.check(funcChipId)
        functionTypeGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_func_fade
            val fType = if (id == R.id.chip_func_circle) VeilFunctionType.CIRCLE else VeilFunctionType.FADE
            vm.updateVeilFunction(type = fType, dataStyle = vm.state.value.veilFunctionDataStyle)
        }

        val functionDataStyleGroup = view.findViewById<ChipGroup>(R.id.qr_function_data_style_group)
        val functionDataColorBtn = view.findViewById<MaterialButton>(R.id.qr_function_data_color_btn)
        val functionCircleColorBtn = view.findViewById<MaterialButton>(R.id.qr_function_circle_color_btn)

        val dataStyleChipId = if (vm.state.value.veilFunctionDataStyle == VeilFunctionDataStyle.RECTANGLE) R.id.chip_func_data_rect else R.id.chip_func_data_round
        functionDataStyleGroup?.check(dataStyleChipId)
        functionDataStyleGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_func_data_round
            val dStyle = if (id == R.id.chip_func_data_rect) VeilFunctionDataStyle.RECTANGLE else VeilFunctionDataStyle.ROUND
            vm.updateVeilFunctionDataStyle(dStyle)
        }
        functionDataColorBtn?.setOnClickListener {
            pickColor(vm.state.value.functionDataColor ?: vm.state.value.foreground) {
                vm.updateFunctionColors(dataColor = it, circleColor = vm.state.value.functionCircleColor)
            }
        }
        functionCircleColorBtn?.setOnClickListener {
            pickColor(vm.state.value.functionCircleColor ?: vm.state.value.foreground) {
                vm.updateFunctionColors(dataColor = vm.state.value.functionDataColor, circleColor = it)
            }
        }

        // STYLE_FUNCTION Controls
        val containerStyleFunction = view.findViewById<LinearLayout>(R.id.container_style_style_function)
        val styleFuncTypeSpinner = view.findViewById<Spinner>(R.id.qr_style_func_type_spinner)
        val styleFuncFreqLabel = view.findViewById<TextView>(R.id.qr_style_func_freq_label)
        val styleFuncFreqSlider = view.findViewById<Slider>(R.id.qr_style_func_freq_slider)
        val styleFuncAmpLabel = view.findViewById<TextView>(R.id.qr_style_func_amp_label)
        val styleFuncAmpSlider = view.findViewById<Slider>(R.id.qr_style_func_amp_slider)

        val funcTypes = FunctionType.values()
        val funcTypeNames = funcTypes.map { it.name.lowercase(Locale.US).replaceFirstChar { c -> c.uppercase() } }
        styleFuncTypeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, funcTypeNames)
        val currentFuncTypeIdx = funcTypes.indexOf(vm.state.value.paramFunctionType).coerceAtLeast(0)
        styleFuncTypeSpinner?.setSelection(currentFuncTypeIdx)
        styleFuncTypeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedType = funcTypes[pos]
                if (vm.state.value.paramFunctionType != selectedType) {
                    vm.updateStyleFunctionParams(type = selectedType, freq = vm.state.value.styleFunctionFrequency, amp = vm.state.value.styleFunctionAmplitude)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        styleFuncFreqSlider?.value = vm.state.value.styleFunctionFrequency.coerceIn(0.1f, 2.0f)
        styleFuncFreqLabel?.text = String.format(Locale.US, "Frequency: %.2f", vm.state.value.styleFunctionFrequency)
        styleFuncFreqSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateStyleFunctionParams(type = vm.state.value.paramFunctionType, freq = value, amp = vm.state.value.styleFunctionAmplitude)
                styleFuncFreqLabel?.text = String.format(Locale.US, "Frequency: %.2f", value)
            }
        }

        styleFuncAmpSlider?.value = vm.state.value.styleFunctionAmplitude.coerceIn(0.05f, 1.0f)
        styleFuncAmpLabel?.text = String.format(Locale.US, "Amplitude: %.2f", vm.state.value.styleFunctionAmplitude)
        styleFuncAmpSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateStyleFunctionParams(type = vm.state.value.paramFunctionType, freq = vm.state.value.styleFunctionFrequency, amp = value)
                styleFuncAmpLabel?.text = String.format(Locale.US, "Amplitude: %.2f", value)
            }
        }

        // CONNECTED_ORGANIC Controls
        val containerConnectedOrganic = view.findViewById<LinearLayout>(R.id.container_style_connected_organic)
        val connectedThicknessLabel = view.findViewById<TextView>(R.id.qr_connected_thickness_label)
        val connectedThicknessSlider = view.findViewById<Slider>(R.id.qr_connected_thickness_slider)

        connectedThicknessSlider?.value = vm.state.value.connectedLineThickness.coerceIn(0.05f, 1.0f)
        connectedThicknessLabel?.text = String.format(Locale.US, "Connected Line Thickness: %.2f", vm.state.value.connectedLineThickness)
        connectedThicknessSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateConnectedLineThickness(value)
                connectedThicknessLabel?.text = String.format(Locale.US, "Connected Line Thickness: %.2f", value)
            }
        }

        // IMAGE_FILL Controls
        val containerImageFill = view.findViewById<LinearLayout>(R.id.container_style_image_fill)
        val imageFillBgColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_fill_bg_color_btn)
        val imageFillMaskColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_fill_mask_color_btn)

        imageFillBgColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageFillBackgroundColor) {
                vm.updateImageFillParams(bgColor = it, maskColor = vm.state.value.imageFillMaskColor)
            }
        }
        imageFillMaskColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageFillMaskColor) {
                vm.updateImageFillParams(bgColor = vm.state.value.imageFillBackgroundColor, maskColor = it)
            }
        }

        chooseBgRequiredBtn?.setOnClickListener {
            sourceImagePickerLauncher.launch("*/*")
        }

        // 2. Resolution Spinner
        val resLabels = arrayOf(
            "256 × 256 — Fast / Small",
            "512 × 512 — Standard / Social",
            "1024 × 1024 — High Quality",
            "2048 × 2048 — Print Quality",
            "4096 × 4096 — Large Format"
        )
        val resValues = intArrayOf(256, 512, 1024, 2048, 4096)
        resSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, resLabels)
        val currentResIdx = resValues.indexOf(vm.state.value.outputSize).coerceAtLeast(0)
        resSpinner.setSelection(currentResIdx)
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (vm.state.value.outputSize != resValues[pos]) {
                    vm.updateOutputSize(resValues[pos])
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 3. Error Correction Spinner
        val ecOptions = listOf(
            "Auto" to ErrorCorrectionChoice.AUTO,
            "Low (7%)" to ErrorCorrectionChoice.L,
            "Medium (15%)" to ErrorCorrectionChoice.M,
            "Quartile (25%)" to ErrorCorrectionChoice.Q,
            "High (30%)" to ErrorCorrectionChoice.H
        )
        val ecLabels = ecOptions.map { it.first }
        ecSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, ecLabels)
        val currentEcIdx = ecOptions.indexOfFirst { it.second == vm.state.value.ecChoice }.coerceAtLeast(0)
        ecSpinner.setSelection(currentEcIdx)
        ecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedEc = ecOptions[pos].second
                if (vm.state.value.ecChoice != selectedEc) {
                    vm.updateErrorCorrection(selectedEc)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 4. Customization Controls: Remove BG / Logo & Sliders
        removeBgBtn.setOnClickListener {
            vm.removeBackgroundImage()
        }
        bgOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBackgroundImageAlpha(value / 100f)
                bgOpacityLabel.text = "Opacity: ${value.toInt()}%"
            }
        }

        removeLogoBtn.setOnClickListener {
            vm.removeLogo()
        }
        logoSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLogoFraction(value / 100f)
                logoSizeLabel.text = "Size: ${value.toInt()}%"
            }
        }

        removeSourceBtn.setOnClickListener {
            vm.removeSourceImage()
        }
        sourceContrastSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageContrast(value)
                sourceContrastLabel.text = String.format(java.util.Locale.US, "Contrast: %.2f", value)
            }
        }
        sourceExposureSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageExposure(value)
                sourceExposureLabel.text = String.format(java.util.Locale.US, "Exposure: %.2f", value)
            }
        }
        sourceOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageOpacity(value / 100f)
                sourceOpacityLabel.text = "Opacity: ${value.toInt()}%"
            }
        }
        imageAllowTransparentSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (vm.state.value.imageAllowTransparent != isChecked) {
                vm.updateImageAllowTransparent(isChecked)
            }
        }
        imageDataScaleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateImageDataScale(value / 100f)
                imageDataScaleLabel?.text = "Data Module Scale: ${value.toInt()}%"
            }
        }
        resampleBackdropSwitch.setOnCheckedChangeListener { _, isChecked ->
            containerBackdropOpacity.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (vm.state.value.resampleUseSourceAsBackdrop != isChecked) {
                vm.updateResampleUseSourceAsBackdrop(isChecked)
            }
        }
        resampleBackdropOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateResampleBackdropOpacity(value / 100f)
                resampleBackdropOpacityLabel.text = "Backdrop Opacity: ${value.toInt()}%"
            }
        }
        resampleSeedBtn.setOnClickListener {
            vm.randomizeResampleSeed()
            Toast.makeText(requireContext(), "Resample pattern randomized", Toast.LENGTH_SHORT).show()
        }

        // IMAGE Specific Controls (Colors & Component Sizes)
        val imageDataDarkColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_data_dark_color_btn)
        val imageDataLightColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_data_light_color_btn)

        val imagePosSizeLabel = view.findViewById<TextView>(R.id.qr_image_pos_size_label)
        val imagePosSizeSlider = view.findViewById<Slider>(R.id.qr_image_pos_size_slider)
        val imagePosDarkColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_pos_dark_color_btn)
        val imagePosLightColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_pos_light_color_btn)

        val imageTimingSizeLabel = view.findViewById<TextView>(R.id.qr_image_timing_size_label)
        val imageTimingSizeSlider = view.findViewById<Slider>(R.id.qr_image_timing_size_slider)
        val imageTimingDarkColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_timing_dark_color_btn)
        val imageTimingLightColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_timing_light_color_btn)

        val imageAlignSizeLabel = view.findViewById<TextView>(R.id.qr_image_align_size_label)
        val imageAlignSizeSlider = view.findViewById<Slider>(R.id.qr_image_align_size_slider)
        val imageAlignDarkColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_align_dark_color_btn)
        val imageAlignLightColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_align_light_color_btn)

        imageDataDarkColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageDataDarkColor) {
                vm.updateImageDataColors(darkColor = it, lightColor = vm.state.value.imageDataLightColor)
            }
        }
        imageDataLightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageDataLightColor) {
                vm.updateImageDataColors(darkColor = vm.state.value.imageDataDarkColor, lightColor = it)
            }
        }

        imagePosSizeSlider?.value = vm.state.value.imagePositionSize.coerceIn(0.5f, 2.0f)
        imagePosSizeLabel?.text = String.format(Locale.US, "Position / Finder Size: %.2fx", vm.state.value.imagePositionSize)
        imagePosSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateImagePositionParams(darkColor = vm.state.value.imagePositionDarkColor, lightColor = vm.state.value.imagePositionLightColor, size = value)
                imagePosSizeLabel?.text = String.format(Locale.US, "Position / Finder Size: %.2fx", value)
            }
        }
        imagePosDarkColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imagePositionDarkColor) {
                vm.updateImagePositionParams(darkColor = it, lightColor = vm.state.value.imagePositionLightColor, size = vm.state.value.imagePositionSize)
            }
        }
        imagePosLightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imagePositionLightColor) {
                vm.updateImagePositionParams(darkColor = vm.state.value.imagePositionDarkColor, lightColor = it, size = vm.state.value.imagePositionSize)
            }
        }

        imageTimingSizeSlider?.value = vm.state.value.imageTimingSize.coerceIn(0.5f, 2.0f)
        imageTimingSizeLabel?.text = String.format(Locale.US, "Timing Pattern Size: %.2fx", vm.state.value.imageTimingSize)
        imageTimingSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateImageTimingParams(darkColor = vm.state.value.imageTimingDarkColor, lightColor = vm.state.value.imageTimingLightColor, size = value)
                imageTimingSizeLabel?.text = String.format(Locale.US, "Timing Pattern Size: %.2fx", value)
            }
        }
        imageTimingDarkColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageTimingDarkColor) {
                vm.updateImageTimingParams(darkColor = it, lightColor = vm.state.value.imageTimingLightColor, size = vm.state.value.imageTimingSize)
            }
        }
        imageTimingLightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageTimingLightColor) {
                vm.updateImageTimingParams(darkColor = vm.state.value.imageTimingDarkColor, lightColor = it, size = vm.state.value.imageTimingSize)
            }
        }

        imageAlignSizeSlider?.value = vm.state.value.imageAlignSize.coerceIn(0.5f, 2.0f)
        imageAlignSizeLabel?.text = String.format(Locale.US, "Alignment Pattern Size: %.2fx", vm.state.value.imageAlignSize)
        imageAlignSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateImageAlignParams(darkColor = vm.state.value.imageAlignDarkColor, lightColor = vm.state.value.imageAlignLightColor, size = value)
                imageAlignSizeLabel?.text = String.format(Locale.US, "Alignment Pattern Size: %.2fx", value)
            }
        }
        imageAlignDarkColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageAlignDarkColor) {
                vm.updateImageAlignParams(darkColor = it, lightColor = vm.state.value.imageAlignLightColor, size = vm.state.value.imageAlignSize)
            }
        }
        imageAlignLightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.imageAlignLightColor) {
                vm.updateImageAlignParams(darkColor = vm.state.value.imageAlignDarkColor, lightColor = it, size = vm.state.value.imageAlignSize)
            }
        }

        // RESAMPLE Specific Controls
        val resampleBackdropImgBtn = view.findViewById<MaterialButton>(R.id.qr_resample_backdrop_img_btn)
        val resampleBackdropImgRemoveBtn = view.findViewById<MaterialButton>(R.id.qr_resample_backdrop_img_remove_btn)
        val resampleBackdropScaleSpinner = view.findViewById<Spinner>(R.id.qr_resample_backdrop_scale_spinner)
        val resampleBackdropTintBtn = view.findViewById<MaterialButton>(R.id.qr_resample_backdrop_tint_btn)
        val resampleBackdropRadiusLabel = view.findViewById<TextView>(R.id.qr_resample_backdrop_radius_label)
        val resampleBackdropRadiusSlider = view.findViewById<Slider>(R.id.qr_resample_backdrop_radius_slider)
        val resampleRngSwitch = view.findViewById<MaterialSwitch>(R.id.qr_resample_rng_switch)

        resampleBackdropImgBtn?.setOnClickListener {
            resampleBackdropPickerLauncher.launch("image/*")
        }
        resampleBackdropImgRemoveBtn?.setOnClickListener {
            vm.removeResampleBackdropImage()
        }

        resampleBackdropScaleSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scaleOptions)
        val currentResampleScaleIdx = scaleEnums.indexOf(vm.state.value.resampleBackdropScaleMode).coerceAtLeast(0)
        resampleBackdropScaleSpinner?.setSelection(currentResampleScaleIdx)
        resampleBackdropScaleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateResampleBackdropScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val resampleDataColorBtn = view.findViewById<MaterialButton>(R.id.qr_resample_data_color_btn)
        resampleDataColorBtn?.setOnClickListener {
            pickColor(vm.state.value.resampleDataColor ?: vm.state.value.foreground) {
                vm.updateResampleDataColor(it)
            }
        }

        resampleBackdropTintBtn?.setOnClickListener {
            pickColor(vm.state.value.resampleBackdropTint ?: Color.WHITE) {
                vm.updateResampleBackdropTint(it)
            }
        }

        resampleBackdropRadiusSlider?.value = vm.state.value.resampleBackdropCornerRadius.coerceIn(0.0f, 64.0f)
        resampleBackdropRadiusLabel?.text = String.format(Locale.US, "Backdrop Corner Radius: %.0fdp", vm.state.value.resampleBackdropCornerRadius)
        resampleBackdropRadiusSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateResampleBackdropRadius(value)
                resampleBackdropRadiusLabel?.text = String.format(Locale.US, "Backdrop Corner Radius: %.0fdp", value)
            }
        }

        resampleRngSwitch?.isChecked = (vm.state.value.resampleRngMode == RngMode.DETERMINISTIC)
        resampleRngSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateResampleRngMode(if (isChecked) RngMode.DETERMINISTIC else RngMode.SYSTEM_UNSEEDED)
        }

        // Core Geometry Card
        val dataShapeSpinner = view.findViewById<Spinner>(R.id.qr_data_shape_spinner)
        val dataScaleLabel = view.findViewById<TextView>(R.id.qr_data_scale_label)
        val dataScaleSlider = view.findViewById<Slider>(R.id.qr_data_scale_slider)
        val finderShapeSpinner = view.findViewById<Spinner>(R.id.qr_finder_shape_spinner)
        val positionSizeLabel = view.findViewById<TextView>(R.id.qr_position_size_label)
        val positionSizeSlider = view.findViewById<Slider>(R.id.qr_position_size_slider)
        val finderOuterColorBtn = view.findViewById<MaterialButton>(R.id.qr_finder_outer_color_btn)
        val finderInnerColorBtn = view.findViewById<MaterialButton>(R.id.qr_finder_inner_color_btn)
        val timingShapeSpinner = view.findViewById<Spinner>(R.id.qr_timing_shape_spinner)
        val timingSizeLabel = view.findViewById<TextView>(R.id.qr_timing_size_label)
        val timingSizeSlider = view.findViewById<Slider>(R.id.qr_timing_size_slider)
        val timingColorBtn = view.findViewById<MaterialButton>(R.id.qr_timing_color_btn)
        val timingOnlyWhiteSwitch = view.findViewById<MaterialSwitch>(R.id.qr_timing_only_white_switch)
        val alignShapeSpinner = view.findViewById<Spinner>(R.id.qr_align_shape_spinner)
        val alignSizeLabel = view.findViewById<TextView>(R.id.qr_align_size_label)
        val alignSizeSlider = view.findViewById<Slider>(R.id.qr_align_size_slider)
        val alignColorBtn = view.findViewById<MaterialButton>(R.id.qr_align_color_btn)
        val alignOnlyWhiteSwitch = view.findViewById<MaterialSwitch>(R.id.qr_align_only_white_switch)
        val quietZoneLabel = view.findViewById<TextView>(R.id.qr_quiet_zone_label)
        val quietZoneSlider = view.findViewById<Slider>(R.id.qr_quiet_zone_slider)
        val asymmetricQzSwitch = view.findViewById<MaterialSwitch>(R.id.qr_asymmetric_qz_switch)
        val containerAsymmetricQz = view.findViewById<LinearLayout>(R.id.container_asymmetric_qz)
        val qzLeftLabel = view.findViewById<TextView>(R.id.qr_qz_left_label)
        val qzLeftSlider = view.findViewById<Slider>(R.id.qr_qz_left_slider)
        val qzTopLabel = view.findViewById<TextView>(R.id.qr_qz_top_label)
        val qzTopSlider = view.findViewById<Slider>(R.id.qr_qz_top_slider)
        val qzRightLabel = view.findViewById<TextView>(R.id.qr_qz_right_label)
        val qzRightSlider = view.findViewById<Slider>(R.id.qr_qz_right_slider)
        val qzBottomLabel = view.findViewById<TextView>(R.id.qr_qz_bottom_label)
        val qzBottomSlider = view.findViewById<Slider>(R.id.qr_qz_bottom_slider)

        val supportedShapes = listOf(
            "Square" to ModuleShape.SQUARE,
            "Rounded" to ModuleShape.ROUNDED,
            "Circle" to ModuleShape.CIRCLE,
            "Dot" to ModuleShape.DOT,
            "Pill" to ModuleShape.PILL,
            "Diamond" to ModuleShape.DIAMOND,
            "Hexagon" to ModuleShape.HEX,
            "Squircle" to ModuleShape.SQUIRCLE,
            "Star" to ModuleShape.STAR
        )
        val shapeLabels = supportedShapes.map { it.first }

        dataShapeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, shapeLabels)
        val currentDataShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.dataShape }.coerceAtLeast(0)
        dataShapeSpinner?.setSelection(currentDataShapeIdx)
        dataShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val shape = supportedShapes[pos].second
                if (vm.state.value.dataShape != shape) {
                    vm.updateDataShape(shape)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        dataScaleSlider?.value = vm.state.value.dataScale.coerceIn(0.1f, 1.0f)
        dataScaleLabel?.text = String.format(Locale.US, "Data Module Scale: %d%%", (vm.state.value.dataScale * 100).toInt())
        dataScaleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateDataScale(value)
                dataScaleLabel?.text = String.format(Locale.US, "Data Module Scale: %d%%", (value * 100).toInt())
            }
        }

        val finderStyles = listOf(
            "Classic" to FinderStyle.CLASSIC,
            "Rounded" to FinderStyle.ROUNDED,
            "Circle" to FinderStyle.CIRCLE,
            "Soft" to FinderStyle.SOFT,
            "Frame" to FinderStyle.FRAME,
            "Planets" to FinderStyle.PLANETS,
            "DSJ" to FinderStyle.DSJ
        )
        val finderLabels = finderStyles.map { it.first }
        finderShapeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, finderLabels)
        val currentFinderIdx = finderStyles.indexOfFirst { it.second == vm.state.value.finderStyle }.coerceAtLeast(0)
        finderShapeSpinner?.setSelection(currentFinderIdx)
        finderShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val style = finderStyles[pos].second
                if (vm.state.value.finderStyle != style) {
                    vm.updateFinderStyle(style)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        positionSizeSlider?.value = vm.state.value.positionSize.coerceIn(0.5f, 1.5f)
        positionSizeLabel?.text = String.format(Locale.US, "Finder / Position Size: %d%%", (vm.state.value.positionSize * 100).toInt())
        positionSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updatePositionSize(value)
                positionSizeLabel?.text = String.format(Locale.US, "Finder / Position Size: %d%%", (value * 100).toInt())
            }
        }

        finderOuterColorBtn?.setOnClickListener {
            pickColor(vm.state.value.finderOuterColor ?: vm.state.value.foreground) {
                vm.updateFinderColors(outer = it, inner = vm.state.value.finderInnerColor)
            }
        }
        finderInnerColorBtn?.setOnClickListener {
            pickColor(vm.state.value.finderInnerColor ?: vm.state.value.foreground) {
                vm.updateFinderColors(outer = vm.state.value.finderOuterColor, inner = it)
            }
        }

        timingShapeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, shapeLabels)
        val currentTimingShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.timingShape }.coerceAtLeast(0)
        timingShapeSpinner?.setSelection(currentTimingShapeIdx)
        timingShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val shape = supportedShapes[pos].second
                if (vm.state.value.timingShape != shape) {
                    vm.updateTimingShape(shape)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        timingSizeSlider?.value = vm.state.value.timingSize.coerceIn(0.2f, 1.5f)
        timingSizeLabel?.text = String.format(Locale.US, "Timing Pattern Size: %d%%", (vm.state.value.timingSize * 100).toInt())
        timingSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateTimingSize(value)
                timingSizeLabel?.text = String.format(Locale.US, "Timing Pattern Size: %d%%", (value * 100).toInt())
            }
        }

        timingColorBtn?.setOnClickListener {
            pickColor(vm.state.value.timingColor ?: vm.state.value.foreground) {
                vm.updateTimingColor(it)
            }
        }
        timingOnlyWhiteSwitch?.isChecked = vm.state.value.timingOnlyWhite
        timingOnlyWhiteSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateTimingOnlyWhite(isChecked)
        }

        alignShapeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, shapeLabels)
        val currentAlignShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.alignShape }.coerceAtLeast(0)
        alignShapeSpinner?.setSelection(currentAlignShapeIdx)
        alignShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val shape = supportedShapes[pos].second
                if (vm.state.value.alignShape != shape) {
                    vm.updateAlignShape(shape)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        alignSizeSlider?.value = vm.state.value.alignSize.coerceIn(0.2f, 1.5f)
        alignSizeLabel?.text = String.format(Locale.US, "Alignment Pattern Size: %d%%", (vm.state.value.alignSize * 100).toInt())
        alignSizeSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateAlignSize(value)
                alignSizeLabel?.text = String.format(Locale.US, "Alignment Pattern Size: %d%%", (value * 100).toInt())
            }
        }

        alignColorBtn?.setOnClickListener {
            pickColor(vm.state.value.alignmentColor ?: vm.state.value.foreground) {
                vm.updateAlignmentColor(it)
            }
        }
        alignOnlyWhiteSwitch?.isChecked = vm.state.value.alignOnlyWhite
        alignOnlyWhiteSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateAlignOnlyWhite(isChecked)
        }

        val isCustomQuietZone = vm.state.value.quietZoneChoice != null
        val currentQuietZone = vm.resolveEffectiveQuietZone()
        quietZoneSlider?.value = currentQuietZone.toFloat().coerceIn(0.0f, 10.0f)
        quietZoneLabel?.text = if (isCustomQuietZone) "Quiet Zone Margin: $currentQuietZone modules (Custom)" else "Quiet Zone Margin: $currentQuietZone modules (Default)"
        quietZoneSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val qz = value.toInt()
                vm.updateQuietZone(qz)
                quietZoneLabel?.text = "Quiet Zone Margin: $qz modules (Custom)"
            }
        }

        asymmetricQzSwitch?.isChecked = vm.state.value.useAsymmetricQuietZone
        containerAsymmetricQz?.visibility = if (vm.state.value.useAsymmetricQuietZone) View.VISIBLE else View.GONE
        quietZoneSlider?.isEnabled = !vm.state.value.useAsymmetricQuietZone

        qzLeftSlider?.value = vm.state.value.quietZoneLeft.coerceIn(0.0f, 10.0f)
        qzLeftLabel?.text = String.format(Locale.US, "Left Margin: %.1f modules", vm.state.value.quietZoneLeft)
        qzTopSlider?.value = vm.state.value.quietZoneTop.coerceIn(0.0f, 10.0f)
        qzTopLabel?.text = String.format(Locale.US, "Top Margin: %.1f modules", vm.state.value.quietZoneTop)
        qzRightSlider?.value = vm.state.value.quietZoneRight.coerceIn(0.0f, 10.0f)
        qzRightLabel?.text = String.format(Locale.US, "Right Margin: %.1f modules", vm.state.value.quietZoneRight)
        qzBottomSlider?.value = vm.state.value.quietZoneBottom.coerceIn(0.0f, 10.0f)
        qzBottomLabel?.text = String.format(Locale.US, "Bottom Margin: %.1f modules", vm.state.value.quietZoneBottom)

        fun updateAsymmetricMargins() {
            val enabled = asymmetricQzSwitch?.isChecked ?: false
            val left = qzLeftSlider?.value ?: 4.0f
            val top = qzTopSlider?.value ?: 4.0f
            val right = qzRightSlider?.value ?: 4.0f
            val bottom = qzBottomSlider?.value ?: 4.0f
            vm.updateAsymmetricQuietZone(enabled, left, top, right, bottom)
        }

        asymmetricQzSwitch?.setOnCheckedChangeListener { _, isChecked ->
            containerAsymmetricQz?.visibility = if (isChecked) View.VISIBLE else View.GONE
            quietZoneSlider?.isEnabled = !isChecked
            updateAsymmetricMargins()
        }

        qzLeftSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                qzLeftLabel?.text = String.format(Locale.US, "Left Margin: %.1f modules", value)
                updateAsymmetricMargins()
            }
        }
        qzTopSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                qzTopLabel?.text = String.format(Locale.US, "Top Margin: %.1f modules", value)
                updateAsymmetricMargins()
            }
        }
        qzRightSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                qzRightLabel?.text = String.format(Locale.US, "Right Margin: %.1f modules", value)
                updateAsymmetricMargins()
            }
        }
        qzBottomSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                qzBottomLabel?.text = String.format(Locale.US, "Bottom Margin: %.1f modules", value)
                updateAsymmetricMargins()
            }
        }

        // Gradient Effects Card
        val gradientTypeSpinner = view.findViewById<Spinner>(R.id.qr_gradient_type_spinner)
        val gradientStartColorBtn = view.findViewById<MaterialButton>(R.id.qr_gradient_start_color_btn)
        val gradientEndColorBtn = view.findViewById<MaterialButton>(R.id.qr_gradient_end_color_btn)
        val gradientClearBtn = view.findViewById<MaterialButton>(R.id.qr_gradient_clear_btn)

        val gradientTypes = listOf(
            "None" to GradientType.NONE,
            "Linear" to GradientType.LINEAR,
            "Radial" to GradientType.RADIAL,
            "Sweep" to GradientType.SWEEP
        )
        val gradientLabels = gradientTypes.map { it.first }
        gradientTypeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, gradientLabels)
        val currentGradIdx = gradientTypes.indexOfFirst { it.second == vm.state.value.gradientType }.coerceAtLeast(0)
        gradientTypeSpinner?.setSelection(currentGradIdx)
        gradientTypeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val gType = gradientTypes[pos].second
                if (vm.state.value.gradientType != gType) {
                    vm.updateGradient(start = vm.state.value.gradientStart, end = vm.state.value.gradientEnd, type = gType)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        gradientStartColorBtn?.setOnClickListener {
            pickColor(vm.state.value.gradientStart ?: vm.state.value.foreground) {
                val gType = if (vm.state.value.gradientType == GradientType.NONE) GradientType.LINEAR else vm.state.value.gradientType
                vm.updateGradient(start = it, end = vm.state.value.gradientEnd ?: vm.state.value.foreground, type = gType)
            }
        }
        gradientEndColorBtn?.setOnClickListener {
            pickColor(vm.state.value.gradientEnd ?: vm.state.value.foreground) {
                val gType = if (vm.state.value.gradientType == GradientType.NONE) GradientType.LINEAR else vm.state.value.gradientType
                vm.updateGradient(start = vm.state.value.gradientStart ?: vm.state.value.foreground, end = it, type = gType)
            }
        }
        gradientClearBtn?.setOnClickListener {
            vm.updateGradient(start = null, end = null, type = GradientType.NONE)
            gradientTypeSpinner?.setSelection(0)
        }

        // Backdrop & Frame Controls Card
        val backdropColorBtn = view.findViewById<MaterialButton>(R.id.qr_backdrop_color_btn)
        val backdropImageBtn = view.findViewById<MaterialButton>(R.id.qr_backdrop_image_btn)
        val backdropImageRemoveBtn = view.findViewById<MaterialButton>(R.id.qr_backdrop_image_remove_btn)
        val backdropRadiusLabel = view.findViewById<TextView>(R.id.qr_backdrop_radius_label)
        val backdropRadiusSlider = view.findViewById<Slider>(R.id.qr_backdrop_radius_slider)
        val backdropImageAlphaLabel = view.findViewById<TextView>(R.id.qr_backdrop_image_alpha_label)
        val backdropImageAlphaSlider = view.findViewById<Slider>(R.id.qr_backdrop_image_alpha_slider)
        val backdropScaleSpinner = view.findViewById<Spinner>(R.id.qr_backdrop_scale_spinner)

        backdropColorBtn?.setOnClickListener {
            pickColor(vm.state.value.backdropColor ?: vm.state.value.background) {
                vm.updateBackdropColor(it)
            }
        }
        backdropImageBtn?.setOnClickListener {
            backdropImagePickerLauncher.launch("image/*")
        }
        backdropImageRemoveBtn?.setOnClickListener {
            vm.updateBackdropImage(null)
        }

        backdropRadiusSlider?.value = vm.state.value.backdropCornerRadius.coerceIn(0.0f, 64.0f)
        backdropRadiusLabel?.text = String.format(Locale.US, "Frame Corner Radius: %.0fdp", vm.state.value.backdropCornerRadius)
        backdropRadiusSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBackdropRadius(value)
                backdropRadiusLabel?.text = String.format(Locale.US, "Frame Corner Radius: %.0fdp", value)
            }
        }

        val currentAlphaPct = (vm.state.value.backdropImageAlpha * 100f).toInt().coerceIn(0, 100)
        backdropImageAlphaSlider?.value = currentAlphaPct.toFloat()
        backdropImageAlphaLabel?.text = "Backdrop Image Opacity: $currentAlphaPct%"
        backdropImageAlphaSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBackdropImageAlpha(value / 100f)
                backdropImageAlphaLabel?.text = "Backdrop Image Opacity: ${value.toInt()}%"
            }
        }

        backdropScaleSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scaleOptions)
        val currentBackdropScaleIdx = scaleEnums.indexOf(vm.state.value.backdropImageScaleMode).coerceAtLeast(0)
        backdropScaleSpinner?.setSelection(currentBackdropScaleIdx)
        backdropScaleSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateBackdropScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Expanded Logo Card Controls
        val logoShapeSpinner = view.findViewById<Spinner>(R.id.qr_logo_shape_spinner)
        val logoAlphaLabel = view.findViewById<TextView>(R.id.qr_logo_alpha_label)
        val logoAlphaSlider = view.findViewById<Slider>(R.id.qr_logo_alpha_slider)
        val logoBorderWidthLabel = view.findViewById<TextView>(R.id.qr_logo_border_width_label)
        val logoBorderWidthSlider = view.findViewById<Slider>(R.id.qr_logo_border_width_slider)
        val logoBorderColorBtn = view.findViewById<MaterialButton>(R.id.qr_logo_border_color_btn)
        val logoScaleModeSpinner = view.findViewById<Spinner>(R.id.qr_logo_scale_mode_spinner)

        val logoShapes = listOf(
            "Squircle" to LogoShape.SQUIRCLE,
            "Circle" to LogoShape.CIRCLE,
            "Square" to LogoShape.SQUARE
        )
        val logoShapeLabels = logoShapes.map { it.first }
        logoShapeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, logoShapeLabels)
        val currentLogoShapeIdx = logoShapes.indexOfFirst { it.second == vm.state.value.logoShape }.coerceAtLeast(0)
        logoShapeSpinner?.setSelection(currentLogoShapeIdx)
        logoShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val shape = logoShapes[pos].second
                if (vm.state.value.logoShape != shape) {
                    vm.updateLogoShape(shape)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val currentLogoAlphaPct = (vm.state.value.logoAlpha * 100f).toInt().coerceIn(10, 100)
        logoAlphaSlider?.value = currentLogoAlphaPct.toFloat()
        logoAlphaLabel?.text = "Logo Opacity: $currentLogoAlphaPct%"
        logoAlphaSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLogoAlpha(value / 100f)
                logoAlphaLabel?.text = "Logo Opacity: ${value.toInt()}%"
            }
        }

        logoBorderWidthSlider?.value = vm.state.value.logoBorderWidth.coerceIn(0.0f, 32.0f)
        logoBorderWidthLabel?.text = String.format(Locale.US, "Border Width: %.0fdp", vm.state.value.logoBorderWidth)
        logoBorderWidthSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLogoBorderWidth(value)
                logoBorderWidthLabel?.text = String.format(Locale.US, "Border Width: %.0fdp", value)
            }
        }

        logoBorderColorBtn?.setOnClickListener {
            pickColor(vm.state.value.logoBorderColor ?: Color.WHITE) {
                vm.updateLogoBorderColor(it)
            }
        }

        logoScaleModeSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scaleOptions)
        val currentLogoScaleIdx = scaleEnums.indexOf(vm.state.value.logoScaleMode).coerceAtLeast(0)
        logoScaleModeSpinner?.setSelection(currentLogoScaleIdx)
        logoScaleModeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateLogoScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 5. Actions & Buttons
        autoRepairBtn.setOnClickListener      { vm.autoRepair() }
        saveBtn.setOnClickListener            { vm.saveToGallery() }
        saveSvgBtn.setOnClickListener         { vm.saveSvg() }
        saveJpegBtn?.setOnClickListener        { vm.saveJpeg() }
        saveGifBtn?.setOnClickListener         { vm.saveGif() }
        saveVideoBtn?.setOnClickListener       { vm.saveVideo() }
        saveAnimatedSvgBtn?.setOnClickListener { vm.saveAnimatedSvg() }
        shareBtn.setOnClickListener           { vm.share() }
        logoBtn.setOnClickListener            { logoPickerLauncher.launch("*/*") }
        sourceImgBtn.setOnClickListener {
            val usesSource = (vm.state.value.style == QrStyle.IMAGE || vm.state.value.style == QrStyle.IMAGE_FILL || vm.state.value.style == QrStyle.IMAGE_RESAMPLE)
            if (!usesSource) {
                Toast.makeText(requireContext(), "Photo source is used by Image, Image Fill, and Image Resample styles", Toast.LENGTH_SHORT).show()
            }
            sourceImagePickerLauncher.launch("*/*")
        }
        bgImageBtn.setOnClickListener         { bgImagePickerLauncher.launch("image/*") }

        fgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = true) }
        bgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = false) }

        // 6. Observe ViewModel State
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    // Empty-state vs animated preview loop vs static preview bitmap
                    val animFrames = state.previewAnimatedFrames
                    if (animFrames.size > 1) {
                        previewAnimationBadge?.visibility = View.VISIBLE
                        previewImage.visibility = View.VISIBLE
                        previewEmptyState?.visibility = View.GONE
                        if (activePreviewFrames !== animFrames) {
                            activePreviewFrames = animFrames
                            previewAnimJob?.cancel()
                            previewAnimJob = viewLifecycleOwner.lifecycleScope.launch {
                                var idx = 0
                                while (isActive && animFrames.isNotEmpty()) {
                                    val f = animFrames[idx % animFrames.size]
                                    previewImage.setImageBitmap(f.bitmap)
                                    kotlinx.coroutines.delay(f.durationMs.toLong().coerceIn(30L, 1000L))
                                    idx++
                                }
                            }
                        }
                    } else {
                        activePreviewFrames = null
                        previewAnimJob?.cancel()
                        previewAnimJob = null
                        previewAnimationBadge?.visibility = View.GONE
                        if (state.bitmap != null) {
                            previewImage.setImageBitmap(state.bitmap)
                            previewImage.visibility = View.VISIBLE
                            previewEmptyState?.visibility = View.GONE
                        } else {
                            previewImage.setImageBitmap(null)
                            previewImage.visibility = View.GONE
                            previewEmptyState?.visibility = View.VISIBLE
                        }
                    }

                    previewProgress?.visibility = if (state.isRenderingPreview) View.VISIBLE else View.GONE

                    // Export buttons enabled/disabled state: content must be non-blank, verified scanable, and not actively busy
                    val hasContent = state.content.isNotBlank()
                    val isScanValid = state.scanabilityReport?.isScanReady == true
                    val isValidating = state.scanabilityReport == null && hasContent && state.errorMessage == null
                    val canExport = hasContent && !state.isRenderingPreview && !state.isExporting && isScanValid
                    val hasAnimationSource = state.animatedFrames.size > 1 || state.logoAnimatedFrames.size > 1

                    // State-aware 3-stage export discoverability: Validating… → Ready → Needs adjustment
                    if (!hasContent) {
                        exportStatusBanner?.visibility = View.GONE
                    } else if (state.isExporting) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Exporting file..."
                        exportStatusBanner?.setTextColor(0xFF6B7280.toInt())
                    } else if (state.errorMessage != null) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Render failed — ${state.errorMessage}"
                        exportStatusBanner?.setTextColor(0xFFDC2626.toInt())
                    } else if (isValidating || state.isRenderingPreview) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Validating… Checking scan reliability before export"
                        exportStatusBanner?.setTextColor(0xFF6B7280.toInt())
                    } else if (isScanValid) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Preview verified scanable · Final export verified at render time"
                        exportStatusBanner?.setTextColor(0xFF16A34A.toInt())
                    } else {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Needs adjustment — Adjust contrast or tap Auto-Repair to enable export"
                        exportStatusBanner?.setTextColor(0xFFD97706.toInt())
                    }

                    // Resolution transparency hint
                    resHint?.text = "Preview rendered at ≤512px · Final export generates at ${state.outputSize}px"

                    saveBtn.isEnabled = canExport
                    saveSvgBtn.isEnabled = canExport
                    saveJpegBtn?.isEnabled = canExport
                    saveGifBtn?.isEnabled = canExport && hasAnimationSource
                    saveVideoBtn?.isEnabled = canExport && hasAnimationSource
                    saveAnimatedSvgBtn?.isEnabled = canExport && hasAnimationSource
                    shareBtn.isEnabled = canExport
                    testWithCameraBtn?.isEnabled = hasContent && state.bitmap != null

                    // Contextual photo button label and alpha: Static short label to avoid wrapping
                    val usesSourceImage = (state.style == QrStyle.IMAGE || state.style == QrStyle.IMAGE_FILL || state.style == QrStyle.IMAGE_RESAMPLE)
                    sourceImgBtn.alpha = if (usesSourceImage) 1.0f else 0.55f
                    sourceImgBtn.text = "Source Media"

                    // Reveal animation export buttons only when animation source media is present
                    containerAnimatedExport?.visibility = if (hasAnimationSource) View.VISIBLE else View.GONE
                    animatedExportLabel?.visibility = if (hasAnimationSource) View.VISIBLE else View.GONE

                    if (hasAnimationSource) {
                        val frameCount = maxOf(state.animatedFrames.size, state.logoAnimatedFrames.size)
                        val previewCount = state.previewAnimatedFrames.size
                        animatedExportLabel?.text = "Animation source: $frameCount frames loaded"
                        animationStatusBanner?.text = "Animation active: $frameCount source frames loaded ($previewCount frames in live preview loop)"
                        animationStatusBanner?.setTextColor(0xFF16A34A.toInt())
                    } else {
                        animationStatusBanner?.text = "No animation loaded. Import a GIF, WebP, or video to preview and export animated QR codes."
                        animationStatusBanner?.setTextColor(0xFF6B7280.toInt())
                    }

                    // Advanced settings expansion state
                    containerAdvanced?.visibility = if (state.isAdvancedExpanded) View.VISIBLE else View.GONE
                    iconToggleAdvanced?.setImageResource(if (state.isAdvancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)

                    // Scanability details expansion state
                    scanabilityTechContainer?.visibility = if (state.isScanDetailsExpanded) View.VISIBLE else View.GONE
                    scanabilityDetailsToggle?.text = if (state.isScanDetailsExpanded) "Hide" else "Details"

                    // Update Quiet Zone label and slider if not custom
                    val isCustom = state.quietZoneChoice != null
                    val eff = vm.resolveEffectiveQuietZone()
                    quietZoneLabel?.text = if (isCustom) "Quiet Zone Margin: ${state.quietZoneChoice} modules (Custom)" else "Quiet Zone Margin: $eff modules (Default)"
                    if (!isCustom && quietZoneSlider?.value?.toInt() != eff) {
                        quietZoneSlider?.value = eff.toFloat().coerceIn(0.0f, 10.0f)
                    }

                    // Contextual Style Settings Card
                    val showD25 = (state.style == QrStyle.D25)
                    val showLine = (state.style == QrStyle.LINE)
                    val showDsj = (state.style == QrStyle.DSJ)
                    val showRandomRect = (state.style == QrStyle.RANDOM_RECTANGLE)
                    val showBubble = (state.style == QrStyle.BUBBLE)
                    val showFunction = (state.style == QrStyle.FUNCTION)
                    val showStyleFunction = (state.style == QrStyle.STYLE_FUNCTION)
                    val showConnectedOrganic = (state.style == QrStyle.CONNECTED_ORGANIC)
                    val showImageFill = (state.style == QrStyle.IMAGE_FILL)
                    val showPhotoRequired = vm.isSourcePhotoRequired(state.style, state.sourceImage)

                    containerD25?.visibility = if (showD25) View.VISIBLE else View.GONE
                    containerLine?.visibility = if (showLine) View.VISIBLE else View.GONE
                    containerDsj?.visibility = if (showDsj) View.VISIBLE else View.GONE
                    containerRandomRect?.visibility = if (showRandomRect) View.VISIBLE else View.GONE
                    containerBubble?.visibility = if (showBubble) View.VISIBLE else View.GONE
                    containerFunction?.visibility = if (showFunction) View.VISIBLE else View.GONE
                    containerStyleFunction?.visibility = if (showStyleFunction) View.VISIBLE else View.GONE
                    containerConnectedOrganic?.visibility = if (showConnectedOrganic) View.VISIBLE else View.GONE
                    containerImageFill?.visibility = if (showImageFill) View.VISIBLE else View.GONE
                    containerImageBackdropRequired?.visibility = if (showPhotoRequired) View.VISIBLE else View.GONE

                    val hasStyleControls = showD25 || showLine || showDsj || showRandomRect || showBubble || showFunction || showStyleFunction || showConnectedOrganic || showImageFill || showPhotoRequired
                    cardStyleSettings?.visibility = if (hasStyleControls) View.VISIBLE else View.GONE

                    // Dynamic Background Image Card
                    if (state.backgroundImage != null) {
                        cardBgControls.visibility = View.VISIBLE
                        val opacityPct = (state.backgroundImageAlpha * 100f).toInt().coerceIn(5, 100)
                        bgOpacitySlider.value = opacityPct.toFloat()
                        bgOpacityLabel.text = "Opacity: $opacityPct%"
                    } else {
                        cardBgControls.visibility = View.GONE
                    }

                    // Dynamic Source Image Card: only show when source image exists AND active style uses it
                    if (state.sourceImage != null && usesSourceImage) {
                        cardSourceControls.visibility = View.VISIBLE
                        sourceContrastSlider.value = state.sourceImageContrast
                        sourceContrastLabel.text = String.format(java.util.Locale.US, "Contrast: %.2f", state.sourceImageContrast)
                        sourceExposureSlider.value = state.sourceImageExposure
                        sourceExposureLabel.text = String.format(java.util.Locale.US, "Exposure: %.2f", state.sourceImageExposure)
                        val isResample = state.style == QrStyle.IMAGE_RESAMPLE
                        if (isResample) {
                            sourceOpacitySlider.visibility = View.GONE
                            sourceOpacityLabel.visibility = View.GONE
                        } else {
                            sourceOpacitySlider.visibility = View.VISIBLE
                            sourceOpacityLabel.visibility = View.VISIBLE
                            val opacityPct = (state.sourceImageOpacity * 100f).toInt().coerceIn(5, 100)
                            sourceOpacitySlider.value = opacityPct.toFloat()
                            sourceOpacityLabel.text = "Opacity: $opacityPct%"
                        }

                        // Contextual IMAGE controls: only show when IMAGE is active
                        val isImage = state.style == QrStyle.IMAGE
                        containerImageControls?.visibility = if (isImage) View.VISIBLE else View.GONE
                        if (imageAllowTransparentSwitch?.isChecked != state.imageAllowTransparent) {
                            imageAllowTransparentSwitch?.isChecked = state.imageAllowTransparent
                        }
                        val dataScalePct = (state.imageDataScale * 100f).toInt().coerceIn(5, 100)
                        imageDataScaleSlider?.value = dataScalePct.toFloat()
                        imageDataScaleLabel?.text = "Data Module Scale: $dataScalePct%"

                        // Contextual RESAMPLE controls: only show when IMAGE_RESAMPLE is active
                        containerResampleControls?.visibility = if (isResample) View.VISIBLE else View.GONE

                        if (resampleBackdropSwitch.isChecked != state.resampleUseSourceAsBackdrop) {
                            resampleBackdropSwitch.isChecked = state.resampleUseSourceAsBackdrop
                        }
                        containerBackdropOpacity.visibility = if (state.resampleUseSourceAsBackdrop && isResample) View.VISIBLE else View.GONE
                        val backdropPct = (state.resampleBackdropOpacity * 100f).toInt().coerceIn(0, 100)
                        resampleBackdropOpacitySlider.value = backdropPct.toFloat()
                        resampleBackdropOpacityLabel.text = "Backdrop Opacity: $backdropPct%"
                    } else {
                        cardSourceControls.visibility = View.GONE
                    }

                    // Dynamic Logo Card
                    if (state.logo != null || state.logoAnimatedFrames.isNotEmpty()) {
                        cardLogoControls.visibility = View.VISIBLE
                        val sizePct = (state.logoFraction * 100f).toInt().coerceIn(10, 33)
                        logoSizeSlider.value = sizePct.toFloat()
                        logoSizeLabel.text = "Size: $sizePct%"
                    } else {
                        cardLogoControls.visibility = View.GONE
                    }

                    // Update Live Verification Card with accurate quiet zone and calibrated text
                    state.scanabilityReport?.let { report ->
                        if (report.isScanReady) {
                            scanabilityCard?.setCardBackgroundColor(0x1816A34A)
                            scanabilityCard?.strokeColor = 0x4016A34A
                            scanabilityIcon?.setImageResource(R.drawable.ic_check_circle)
                            scanabilityIcon?.setColorFilter(0xFF16A34A.toInt())
                            scanabilityStatus.text = "Scanability Check Passed"
                            scanabilityStatus.setTextColor(0xFF16A34A.toInt())
                            scanabilityDetails.text = "Passed on-device QR validation"
                            val engine = if (report.decodeResult.decoderId.contains("ML Kit", ignoreCase = true) || report.decodeResult.decoderId.contains("mlkit", ignoreCase = true)) "Google ML Kit" else "ZXing"
                            val qz = state.design?.quietZoneModules ?: if (state.style == QrStyle.IMAGE || state.style == QrStyle.IMAGE_RESAMPLE || state.style == QrStyle.IMAGE_FILL) 1 else 4
                            scanabilityTechText?.text = "Engine: $engine | Latency: ${report.decodeResult.latencyMs}ms | Quiet zone: $qz module${if (qz == 1) "" else "s"}"
                            autoRepairBtn.visibility = View.GONE
                        } else {
                            scanabilityCard?.setCardBackgroundColor(0x18D97706)
                            scanabilityCard?.strokeColor = 0x50D97706
                            scanabilityIcon?.setImageResource(R.drawable.ic_info_outline)
                            scanabilityIcon?.setColorFilter(0xFFD97706.toInt())
                            scanabilityStatus.text = "QR Verification Notice"
                            scanabilityStatus.setTextColor(0xFFD97706.toInt())
                            scanabilityDetails.text = "We couldn't verify this QR code automatically. Try Auto-Repair or adjust contrast."
                            val errorMsg = report.decodeResult.error ?: "Decoder could not resolve patterns"
                            val warningText = if (report.warnings.isNotEmpty()) " | Warnings: " + report.warnings.joinToString("; ") else ""
                            scanabilityTechText?.text = "Diagnostic: $errorMsg$warningText"
                            autoRepairBtn.visibility = if (report.repairSuggestions.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                    } ?: run {
                        scanabilityCard?.setCardBackgroundColor(0x00000000)
                        scanabilityCard?.strokeColor = 0x20888888
                        scanabilityIcon?.setImageResource(R.drawable.ic_info_outline)
                        scanabilityIcon?.setColorFilter(0xFF6B7280.toInt())
                        scanabilityStatus.text = "Validating..."
                        scanabilityStatus.setTextColor(0xFF6B7280.toInt())
                        scanabilityDetails.text = "Running on-device barcode validator..."
                        scanabilityTechText?.text = "Validating barcode scanability on device..."
                        autoRepairBtn.visibility = View.GONE
                    }

                    state.repairNotice?.let { notice ->
                        view?.let { v ->
                            Snackbar.make(v, "Auto-Repair: $notice", Snackbar.LENGTH_LONG)
                                .setAction("Undo") {
                                    vm.undoAutoRepair()
                                }
                                .show()
                        }
                        vm.clearRepairNotice()
                    }

                    state.saveResult?.let { msg ->
                        view?.let { v ->
                            val snackbar = Snackbar.make(v, msg, Snackbar.LENGTH_LONG)
                            val uriToView = state.lastSavedUri
                            if (uriToView != null) {
                                snackbar.setAction("View") {
                                    try {
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uriToView, requireContext().contentResolver.getType(uriToView) ?: "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(viewIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(requireContext(), "No application found to view file", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            snackbar.show()
                        }
                        vm.clearSaveResult()
                    }
                    state.errorMessage?.let { msg ->
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    private fun showColorPaletteDialog(isForeground: Boolean) {
        val currentColor = if (isForeground) vm.state.value.foreground else vm.state.value.background
        val oppositeColor = if (isForeground) vm.state.value.background else vm.state.value.foreground
        QrColorPickerDialog(
            context = requireContext(),
            initialColor = currentColor,
            contrastAgainstColor = oppositeColor,
            isForeground = isForeground
        ) { selectedColor ->
            if (isForeground) {
                vm.updateForeground(selectedColor)
            } else {
                vm.updateBackground(selectedColor)
            }
        }.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewAnimJob?.cancel()
        previewAnimJob = null
        activePreviewFrames = null
    }

    private fun pickColor(
        initialColor: Int,
        contrastAgainstColor: Int = Color.WHITE,
        isForeground: Boolean = true,
        onColorPicked: (Int) -> Unit
    ) {
        QrColorPickerDialog(
            context = requireContext(),
            initialColor = initialColor,
            contrastAgainstColor = contrastAgainstColor,
            isForeground = isForeground,
            onColorSelected = onColorPicked
        ).show()
    }
}

/**
 * Scan tab fragment using CameraX and ZXing for live viewfinder scanning + gallery import.
 */
class QrScanTabFragment : Fragment() {

    private val vm: QrStudioViewModel by activityViewModels()

    private enum class CameraUiState {
        INITIALIZING,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        READY,
        ERROR,
        RESULT_PRESENTED
    }

    private var previewView: PreviewView? = null
    private var scanOverlay: View? = null
    private var guidanceText: TextView? = null
    private var torchBtn: FloatingActionButton? = null
    private var isTorchOn: Boolean = false

    private var resultCard: MaterialCardView? = null
    private var resultType: TextView? = null
    private var resultText: TextView? = null
    private var togglePwdBtn: MaterialButton? = null
    private var resultActionBtn: Button? = null
    private var scanAnotherBtn: Button? = null

    private var currentScannedAction: QrAction? = null
    private var isPasswordMasked: Boolean = true

    private var containerBottomControls: View? = null
    private var scanGalleryBtn: Button? = null

    private var containerPermissionDenied: View? = null
    private var permTitle: TextView? = null
    private var permDesc: TextView? = null
    private var allowCameraBtn: Button? = null
    private var openSettingsBtn: Button? = null

    private var containerCameraError: View? = null
    private var cameraErrorText: TextView? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: androidx.camera.core.Camera? = null
    private var activeQrScanner: QrScanner? = null
    private var isScanningPaused: Boolean = false
    private var hasRequestedPermissionOnce: Boolean
        get() = context?.getSharedPreferences("qr_scan_prefs", Context.MODE_PRIVATE)?.getBoolean("has_req_cam", false) ?: false
        set(value) {
            context?.getSharedPreferences("qr_scan_prefs", Context.MODE_PRIVATE)?.edit()?.putBoolean("has_req_cam", value)?.apply()
        }
    private var currentUiState: CameraUiState = CameraUiState.INITIALIZING

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRequestedPermissionOnce = true
        if (isGranted) {
            checkPermissionState()
        } else {
            checkPermissionState()
        }
    }

    private val qrDecodePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val raw = try {
                decodeGalleryUri(ctx, uri)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (raw != null) {
                    handleScanResult(raw)
                } else {
                    view?.let { v ->
                        Snackbar.make(v, "No QR code found in image", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun decodeGalleryUri(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val sampleSize = calculateInSampleSize(options, 1280, 1280)
        val downsampleOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, downsampleOptions) }
            ?: return null
        return try {
            QrScanner.decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_scan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView               = view.findViewById(R.id.qr_camera_preview)
        scanOverlay               = view.findViewById(R.id.qr_scan_overlay)
        guidanceText              = view.findViewById(R.id.qr_scan_guidance_text)
        torchBtn                  = view.findViewById(R.id.qr_torch_btn)

        resultCard                = view.findViewById(R.id.qr_result_card)
        resultType                = view.findViewById(R.id.qr_result_type)
        resultText                = view.findViewById(R.id.qr_result_text)
        togglePwdBtn              = view.findViewById(R.id.qr_result_toggle_pwd_btn)
        resultActionBtn           = view.findViewById(R.id.qr_result_action_btn)
        scanAnotherBtn            = view.findViewById(R.id.qr_scan_another_btn)

        containerBottomControls   = view.findViewById(R.id.container_scan_bottom_controls)
        scanGalleryBtn            = view.findViewById(R.id.qr_scan_gallery_btn)

        containerPermissionDenied = view.findViewById(R.id.container_camera_permission_denied)
        permTitle                 = view.findViewById(R.id.qr_camera_permission_title)
        permDesc                  = view.findViewById(R.id.qr_camera_permission_desc)
        allowCameraBtn            = view.findViewById(R.id.qr_allow_camera_btn)
        openSettingsBtn           = view.findViewById(R.id.qr_open_settings_btn)

        containerCameraError      = view.findViewById(R.id.container_camera_error)
        cameraErrorText           = view.findViewById(R.id.qr_camera_error_text)

        torchBtn?.setOnClickListener {
            toggleTorch()
        }

        togglePwdBtn?.setOnClickListener {
            isPasswordMasked = !isPasswordMasked
            togglePwdBtn?.text = if (isPasswordMasked) "Show" else "Hide"
            currentScannedAction?.let { action ->
                resultText?.text = formatActionSummary(action, isPasswordMasked)
            }
        }

        scanGalleryBtn?.setOnClickListener {
            qrDecodePickerLauncher.launch("image/*")
        }
        view.findViewById<Button>(R.id.qr_permission_import_gallery_btn)?.setOnClickListener {
            qrDecodePickerLauncher.launch("image/*")
        }
        view.findViewById<Button>(R.id.qr_error_import_gallery_btn)?.setOnClickListener {
            qrDecodePickerLauncher.launch("image/*")
        }
        allowCameraBtn?.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        openSettingsBtn?.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }
        view.findViewById<Button>(R.id.qr_camera_retry_btn)?.setOnClickListener {
            checkPermissionState()
        }

        scanAnotherBtn?.setOnClickListener {
            isScanningPaused = false
            guidanceText?.text = "Point camera at a QR code"
            activeQrScanner?.resumeAnalysis()
            updateCameraUiState(CameraUiState.READY)
        }

        checkPermissionState()
    }

    override fun onResume() {
        super.onResume()
        if (currentUiState == CameraUiState.PERMISSION_DENIED || currentUiState == CameraUiState.PERMISSION_REQUIRED) {
            checkPermissionState()
        }
    }

    private fun updateCameraUiState(state: CameraUiState) {
        currentUiState = state
        val isCameraReady = (state == CameraUiState.READY)
        val isResult = (state == CameraUiState.RESULT_PRESENTED)
        val isPerm = (state == CameraUiState.PERMISSION_REQUIRED || state == CameraUiState.PERMISSION_DENIED)
        val isError = (state == CameraUiState.ERROR)

        scanOverlay?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
        guidanceText?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
        torchBtn?.visibility = if (isCameraReady && activeCamera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
        resultCard?.visibility = if (isResult) View.VISIBLE else View.GONE
        containerPermissionDenied?.visibility = if (isPerm) View.VISIBLE else View.GONE
        containerCameraError?.visibility = if (isError) View.VISIBLE else View.GONE

        // Mutually exclusive: Bottom controls only visible when camera is ready or showing result
        containerBottomControls?.visibility = if (isCameraReady || isResult) View.VISIBLE else View.GONE
        scanGalleryBtn?.visibility = if (isCameraReady) View.VISIBLE else View.GONE

        if (!isCameraReady && isTorchOn) {
            setTorch(false)
        }
    }

    private fun toggleTorch() {
        val cam = activeCamera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            setTorch(!isTorchOn)
        }
    }

    private fun setTorch(on: Boolean) {
        val cam = activeCamera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = on
            cam.cameraControl.enableTorch(on)
            torchBtn?.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
    }

    private fun checkPermissionState() {
        val hasPerm = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            updateCameraUiState(CameraUiState.INITIALIZING)
            startCamera()
        } else {
            val isPermanent = hasRequestedPermissionOnce &&
                !ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.CAMERA)

            if (isPermanent) {
                permTitle?.text = "Camera Access Blocked"
                permDesc?.text = "Camera permission is blocked in system settings. Tap below to open App Info and enable Camera access for VeilFrame."
                allowCameraBtn?.visibility = View.GONE
                openSettingsBtn?.visibility = View.VISIBLE
                updateCameraUiState(CameraUiState.PERMISSION_DENIED)
            } else if (hasRequestedPermissionOnce) {
                permTitle?.text = "Camera Permission Needed"
                permDesc?.text = "Camera access was denied. To scan QR codes directly with your device, grant camera permission to continue."
                allowCameraBtn?.visibility = View.VISIBLE
                openSettingsBtn?.visibility = View.GONE
                updateCameraUiState(CameraUiState.PERMISSION_REQUIRED)
            } else {
                permTitle?.text = "Camera Access Required"
                permDesc?.text = "VeilFrame scans QR codes locally on your device. Camera access is used solely for live viewfinder scanning."
                allowCameraBtn?.visibility = View.VISIBLE
                openSettingsBtn?.visibility = View.GONE
                updateCameraUiState(CameraUiState.PERMISSION_REQUIRED)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                if (isAdded) {
                    cameraErrorText?.text = "Camera provider initialization failed: ${e.localizedMessage ?: "Unknown hardware error"}"
                    updateCameraUiState(CameraUiState.ERROR)
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView?.surfaceProvider)

        val scanner = QrScanner(
            onZoomSuggestion = { zoomMultiplier ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    guidanceText?.text = "Move closer or zoom in"
                    val cam = activeCamera ?: return@runOnUiThread
                    val zoomState = cam.cameraInfo.zoomState.value ?: return@runOnUiThread
                    val currentZoom = zoomState.zoomRatio
                    val maxZoom = zoomState.maxZoomRatio.coerceAtMost(5.0f)
                    val minZoom = zoomState.minZoomRatio
                    val targetZoom = (currentZoom * zoomMultiplier).coerceIn(minZoom, maxZoom)
                    if (targetZoom > currentZoom * 1.05f) {
                        cam.cameraControl.setZoomRatio(targetZoom)
                    }
                }
            },
            onLowLightDetected = { isLow ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    vm.updateLowLight(isLow)
                    if (isLow && !isTorchOn && !isScanningPaused) {
                        guidanceText?.text = "Too dark? Turn on flash"
                    } else if (!isScanningPaused && guidanceText?.text == "Too dark? Turn on flash") {
                        guidanceText?.text = "Point camera at a QR code"
                    }
                }
            }
        ) { raw ->
            activity?.runOnUiThread {
                if (isAdded && !isScanningPaused) {
                    handleScanResult(raw)
                }
            }
        }.also { activeQrScanner = it }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor, scanner)

        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
            torchBtn?.visibility = if (hasFlash) View.VISIBLE else View.GONE
            updateCameraUiState(CameraUiState.READY)
        } catch (e: Exception) {
            if (isAdded) {
                cameraErrorText?.text = "Camera hardware bind failed: ${e.localizedMessage ?: "Device busy or unsupported"}"
                updateCameraUiState(CameraUiState.ERROR)
            }
        }
    }

    private fun handleScanResult(raw: String) {
        isScanningPaused = true
        activeQrScanner?.pauseAnalysis()
        val action = PayloadParser.parse(raw)
        currentScannedAction = action
        isPasswordMasked = true

        resultType?.text = "Detected: ${action::class.simpleName ?: "QR Code"}"
        resultText?.text = formatActionSummary(action, isPasswordMasked)
        resultActionBtn?.text = formatActionCta(action)

        if (action is QrAction.Wifi && action.password.isNotBlank()) {
            togglePwdBtn?.visibility = View.VISIBLE
            togglePwdBtn?.text = "Show"
        } else {
            togglePwdBtn?.visibility = View.GONE
        }

        resultActionBtn?.setOnClickListener {
            executeAction(action)
        }
        updateCameraUiState(CameraUiState.RESULT_PRESENTED)
    }

    private fun formatActionCta(action: QrAction): String = when (action) {
        is QrAction.Url -> "Open Link"
        is QrAction.Wifi -> "Connect to Wi-Fi"
        is QrAction.Contact -> "Add Contact"
        is QrAction.UpiPayment -> "Pay with UPI"
        is QrAction.Phone -> "Call Phone"
        is QrAction.Sms -> "Send SMS"
        is QrAction.Email -> "Compose Email"
        is QrAction.Geo -> "Open Map"
        is QrAction.CalendarEvent -> "Add Event"
        is QrAction.OtpAuth -> "Add to Authenticator"
        is QrAction.Raw -> "Copy Content"
    }

    companion object {
        internal fun formatActionSummary(action: QrAction, maskWifiPassword: Boolean = true): String = when (action) {
            is QrAction.Url -> buildString {
                append(action.uri)
                if (!action.host.isNullOrBlank()) {
                    append("\nHost: ${action.host}")
                }
            }
            is QrAction.Wifi -> buildString {
                append("Network: ${action.ssid}")
                append("\nSecurity: ${action.type}")
                if (action.password.isNotBlank()) {
                    val pass = if (maskWifiPassword) "••••••••" else action.password
                    append("\nPassword: $pass")
                }
                if (action.hidden) {
                    append(" (Hidden)")
                }
            }
            is QrAction.Contact -> buildString {
                append(action.name ?: "Contact")
                if (!action.org.isNullOrBlank()) append("\nOrg: ${action.org}")
                if (action.phones.isNotEmpty()) append("\nPhone: ${action.phones.joinToString(", ")}")
                if (action.emails.isNotEmpty()) append("\nEmail: ${action.emails.joinToString(", ")}")
            }
            is QrAction.UpiPayment -> buildString {
                if (!action.payeeName.isNullOrBlank()) {
                    append("Payee: ${action.payeeName}\n")
                }
                append("VPA: ${action.payeeAddress}")
                if (action.amount != null) {
                    append("\nAmount: ₹${action.amount}")
                }
                if (!action.note.isNullOrBlank()) {
                    append("\nNote: ${action.note}")
                }
            }
            is QrAction.Phone -> "Phone: ${action.number}"
            is QrAction.Sms -> buildString {
                append("Recipient: ${action.number}")
                if (!action.message.isNullOrBlank()) append("\nMessage: ${action.message}")
            }
            is QrAction.Email -> buildString {
                append("To: ${action.address}")
                if (!action.subject.isNullOrBlank()) append("\nSubject: ${action.subject}")
                if (!action.body.isNullOrBlank()) append("\nBody: ${action.body}")
            }
            is QrAction.Geo -> buildString {
                append("Coordinates: ${action.lat}, ${action.lon}")
                if (!action.label.isNullOrBlank()) append("\nLabel: ${action.label}")
            }
            is QrAction.CalendarEvent -> buildString {
                append("Event: ${action.title ?: "Calendar entry"}")
                if (!action.location.isNullOrBlank()) append("\nLocation: ${action.location}")
                if (!action.dtStart.isNullOrBlank()) append("\nStarts: ${action.dtStart}")
                if (!action.description.isNullOrBlank()) append("\nDetails: ${action.description}")
            }
            is QrAction.OtpAuth -> buildString {
                val label = listOfNotNull(action.issuer, action.account).joinToString(": ")
                append("Service: ${if (label.isNotBlank()) label else "Authenticator"}")
                append("\nType: ${action.type.uppercase(java.util.Locale.ROOT)} (${action.digits} digits)")
            }
            is QrAction.Raw -> action.text
        }
    }

    private fun executeAction(action: QrAction) {
        try {
            QrActionExecutor.execute(requireContext(), action)
        } catch (e: Exception) {
            view?.let { v ->
                Snackbar.make(v, "Unable to execute action: ${e.message}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        setTorch(false)
        cameraProvider?.unbindAll()
        activeCamera = null
        activeQrScanner?.close()
        activeQrScanner = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
