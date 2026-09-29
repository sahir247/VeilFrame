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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputLayout
import com.veilframe.app.R
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import com.veilframe.app.qr.model.ImageScaleMode
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.LineVariant
import com.veilframe.app.qr.model.QrPresetFormatter
import com.veilframe.app.qr.model.VeilFunctionType
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.QrScanner
import com.veilframe.app.qr.scanner.action.QrActionExecutor
import kotlinx.coroutines.Dispatchers
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

        // Observe loading state to drive the top-level progress bar
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    loadingProgress?.visibility = if (state.isLoading) View.VISIBLE else View.GONE
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

    private val logoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
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
                if (isAdded && bmp != null) {
                    vm.updateLogo(bmp)
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
                if (isAdded && bmp != null) {
                    vm.updateBackgroundImage(bmp)
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
                if (isAdded && bmp != null) {
                    vm.updateSourceImage(bmp)
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

        var isTechDetailsExpanded = false
        scanabilityDetailsToggle?.setOnClickListener {
            isTechDetailsExpanded = !isTechDetailsExpanded
            scanabilityTechContainer?.visibility = if (isTechDetailsExpanded) View.VISIBLE else View.GONE
            scanabilityDetailsToggle.text = if (isTechDetailsExpanded) "Hide" else "Details"
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

        emailRecipient.setText(initState.emailRecipient)
        emailSubject.setText(initState.emailSubject)
        emailBody.setText(initState.emailBody)

        smsPhone.setText(initState.smsPhone)
        smsBody.setText(initState.smsBody)

        upiVpa.setText(initState.upiVpa)
        upiAmount.setText(initState.upiAmount)

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
        val styleSpinner       = view.findViewById<Spinner>(R.id.qr_style_spinner)
        val resSpinner         = view.findViewById<Spinner>(R.id.qr_resolution_spinner)
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
        val saveGifBtn         = view.findViewById<MaterialButton>(R.id.qr_save_gif_btn)
        val saveVideoBtn       = view.findViewById<MaterialButton>(R.id.qr_save_video_btn)
        val saveAnimatedSvgBtn = view.findViewById<MaterialButton>(R.id.qr_save_animated_svg_btn)
        val shareBtn           = view.findViewById<MaterialButton>(R.id.qr_share_btn)

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
                    vm.updateVcardForm(first, last, phone, email, org)
                    if (first.isNotBlank() || last.isNotBlank() || phone.isNotBlank()) {
                        QrPresetFormatter.formatVCard(first, last, phone, email, org)
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
                    vm.updateUpiForm(vpa, amount)
                    if (vpa.isNotBlank()) QrPresetFormatter.formatUpi(vpa = vpa, amount = amount) else ""
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

        emailRecipient.addTextChangedListener(liveWatcher)
        emailSubject.addTextChangedListener(liveWatcher)
        emailBody.addTextChangedListener(liveWatcher)

        smsPhone.addTextChangedListener(liveWatcher)
        smsBody.addTextChangedListener(liveWatcher)

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
                    if (extractedPa.isNotBlank()) {
                        isSelfEditing = true
                        upiVpa.setText(extractedPa)
                        upiVpa.setSelection(extractedPa.length)
                        isSelfEditing = false
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

        // 1. Visual Style Selector & Gallery Chips + Spinner Two-Way Sync
        val styles = QrStyle.values()
        val styleNames = styles.map { QrStyleRegistry.get(it).displayName }
        styleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, styleNames)
        styleSpinner.setSelection(styles.indexOf(vm.state.value.style).coerceAtLeast(0))

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
                styleSpinner.setSelection(styles.indexOf(selectedStyle).coerceAtLeast(0))
            }
        }

        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedStyle = styles[pos]
                if (vm.state.value.style != selectedStyle) {
                    vm.updateStyle(selectedStyle)
                    styleToChipId[selectedStyle]?.let { chipId ->
                        if (styleChipGroup?.checkedChipId != chipId) {
                            styleChipGroup?.check(chipId)
                        }
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
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

        randomRectSeedBtn?.setOnClickListener {
            vm.randomizeRandomRectSeed()
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

        val funcChipId = if (vm.state.value.veilFunctionType == VeilFunctionType.CIRCLE) R.id.chip_func_circle else R.id.chip_func_fade
        functionTypeGroup?.check(funcChipId)
        functionTypeGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chip_func_fade
            val fType = if (id == R.id.chip_func_circle) VeilFunctionType.CIRCLE else VeilFunctionType.FADE
            vm.updateVeilFunction(type = fType, dataStyle = vm.state.value.veilFunctionDataStyle)
        }

        chooseBgRequiredBtn?.setOnClickListener {
            sourceImagePickerLauncher.launch("*/*")
        }

        // 2. Resolution Spinner
        val resLabels = arrayOf("512 x 512", "1024 x 1024", "2048 x 2048")
        val resValues = intArrayOf(512, 1024, 2048)
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

        // 5. Actions & Buttons
        autoRepairBtn.setOnClickListener      { vm.autoRepair() }
        saveBtn.setOnClickListener            { vm.saveToGallery() }
        saveSvgBtn.setOnClickListener         { vm.saveSvg() }
        saveGifBtn?.setOnClickListener         { vm.saveGif() }
        saveVideoBtn?.setOnClickListener       { vm.saveVideo() }
        saveAnimatedSvgBtn?.setOnClickListener { vm.saveAnimatedSvg() }
        shareBtn.setOnClickListener           { vm.share() }
        logoBtn.setOnClickListener            { logoPickerLauncher.launch("image/*") }
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
                    // Empty-state vs preview bitmap
                    if (state.bitmap != null) {
                        previewImage.setImageBitmap(state.bitmap)
                        previewImage.visibility = View.VISIBLE
                        previewEmptyState?.visibility = View.GONE
                    } else {
                        previewImage.setImageBitmap(null)
                        previewImage.visibility = View.GONE
                        previewEmptyState?.visibility = View.VISIBLE
                    }

                    previewProgress?.visibility = if (state.isRenderingPreview) View.VISIBLE else View.GONE

                    // Export buttons enabled/disabled state: content must be non-blank and not actively busy
                    val canExport = state.content.isNotBlank() && !state.isRenderingPreview && !state.isExporting
                    saveBtn.isEnabled = canExport
                    saveSvgBtn.isEnabled = canExport
                    saveGifBtn?.isEnabled = canExport
                    saveVideoBtn?.isEnabled = canExport
                    saveAnimatedSvgBtn?.isEnabled = canExport
                    shareBtn.isEnabled = canExport

                    // Contextual photo button label and alpha
                    val usesSourceImage = (state.style == QrStyle.IMAGE || state.style == QrStyle.IMAGE_FILL || state.style == QrStyle.IMAGE_RESAMPLE)
                    sourceImgBtn.alpha = if (usesSourceImage) 1.0f else 0.55f
                    sourceImgBtn.text = if (usesSourceImage) "Source Photo" else "Photo (Image Styles)"

                    // Clarify animated export helper label based on input
                    if (state.animatedFrames.isNotEmpty()) {
                        animatedExportLabel?.text = "Animation source: ${state.animatedFrames.size} frames"
                    } else {
                        animatedExportLabel?.text = "Generate looping animation from current QR artwork"
                    }

                    // Contextual Style Settings Card
                    val showD25 = (state.style == QrStyle.D25)
                    val showLine = (state.style == QrStyle.LINE)
                    val showDsj = (state.style == QrStyle.DSJ)
                    val showRandomRect = (state.style == QrStyle.RANDOM_RECTANGLE)
                    val showBubble = (state.style == QrStyle.BUBBLE)
                    val showFunction = (state.style == QrStyle.FUNCTION)
                    val showPhotoRequired = vm.isSourcePhotoRequired(state.style, state.sourceImage)

                    containerD25?.visibility = if (showD25) View.VISIBLE else View.GONE
                    containerLine?.visibility = if (showLine) View.VISIBLE else View.GONE
                    containerDsj?.visibility = if (showDsj) View.VISIBLE else View.GONE
                    containerRandomRect?.visibility = if (showRandomRect) View.VISIBLE else View.GONE
                    containerBubble?.visibility = if (showBubble) View.VISIBLE else View.GONE
                    containerFunction?.visibility = if (showFunction) View.VISIBLE else View.GONE
                    containerImageBackdropRequired?.visibility = if (showPhotoRequired) View.VISIBLE else View.GONE

                    val hasStyleControls = showD25 || showLine || showDsj || showRandomRect || showBubble || showFunction || showPhotoRequired
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
                    if (state.logo != null) {
                        cardLogoControls.visibility = View.VISIBLE
                        val sizePct = (state.logoFraction * 100f).toInt().coerceIn(10, 35)
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
                        view?.let { v -> Snackbar.make(v, "Auto-Repair: $notice", Snackbar.LENGTH_LONG).show() }
                        vm.clearRepairNotice()
                    }

                    state.saveResult?.let { msg ->
                        view?.let { v -> Snackbar.make(v, msg, Snackbar.LENGTH_SHORT).show() }
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
}

/**
 * Scan tab fragment using CameraX and ZXing for live viewfinder scanning + gallery import.
 */
class QrScanTabFragment : Fragment() {

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
    private var resultCard: MaterialCardView? = null
    private var resultType: TextView? = null
    private var resultText: TextView? = null
    private var resultActionBtn: Button? = null
    private var scanAnotherBtn: Button? = null

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
    private var hasRequestedPermissionOnce: Boolean = false
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
        resultCard                = view.findViewById(R.id.qr_result_card)
        resultType                = view.findViewById(R.id.qr_result_type)
        resultText                = view.findViewById(R.id.qr_result_text)
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
        resultCard?.visibility = if (isResult) View.VISIBLE else View.GONE
        containerPermissionDenied?.visibility = if (isPerm) View.VISIBLE else View.GONE
        containerCameraError?.visibility = if (isError) View.VISIBLE else View.GONE

        // Mutually exclusive: Bottom controls only visible when camera is ready or showing result
        containerBottomControls?.visibility = if (isCameraReady || isResult) View.VISIBLE else View.GONE
        scanGalleryBtn?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
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
                permTitle?.text = "Camera Permission Disabled"
                permDesc?.text = "Camera access is disabled. Enable camera access from Android Settings to scan QR codes."
                allowCameraBtn?.visibility = View.GONE
                openSettingsBtn?.visibility = View.VISIBLE
                updateCameraUiState(CameraUiState.PERMISSION_DENIED)
            } else {
                permTitle?.text = "Camera access is disabled"
                permDesc?.text = "VeilFrame needs camera access to scan QR codes with your device."
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
        resultType?.text = "Detected: ${action::class.simpleName ?: "QR Code"}"
        resultText?.text = formatActionSummary(action)
        resultActionBtn?.text = formatActionCta(action)

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

    private fun formatActionSummary(action: QrAction): String = when (action) {
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
                append("\nPassword: ${action.password}")
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
