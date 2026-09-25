package com.veilframe.app.qr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputLayout
import com.veilframe.app.R
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import com.veilframe.app.qr.model.ImageScaleMode
import com.veilframe.app.qr.model.QrPresetFormatter
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
            val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
                uriStr.endsWith(".mp4") || uriStr.endsWith(".mov") || uriStr.endsWith(".webm")

            if (isGif || isVideo) {
                val frames = com.veilframe.app.qr.AnimatedMediaHelper.extractFrames(ctx, uri)
                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            vm.updateAnimatedFrames(frames)
                            val label = if (isGif) "GIF" else "video"
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

        val previewImage       = view.findViewById<ImageView>(R.id.qr_preview_image)
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

        // Generator Config
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

        val saveBtn            = view.findViewById<MaterialButton>(R.id.qr_save_btn)
        val saveSvgBtn         = view.findViewById<MaterialButton>(R.id.qr_save_svg_btn)
        val saveGifBtn         = view.findViewById<MaterialButton>(R.id.qr_save_gif_btn)
        val saveVideoBtn       = view.findViewById<MaterialButton>(R.id.qr_save_video_btn)
        val shareBtn           = view.findViewById<MaterialButton>(R.id.qr_share_btn)

        // Setup Source Scale Spinner
        val scaleOptions = arrayOf("Aspect Fill", "Aspect Fit", "Center Crop", "Stretch")
        val scaleEnums = arrayOf(
            ImageScaleMode.ASPECT_FILL,
            ImageScaleMode.ASPECT_FIT,
            ImageScaleMode.CENTER_CROP,
            ImageScaleMode.STRETCH
        )
        sourceScaleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, scaleOptions)
        sourceScaleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateSourceImageScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Helper to compile active preset into payload
        fun compileActivePreset() {
            val checkedId = presetChipGroup.checkedChipId
            val payload = when (checkedId) {
                R.id.chip_preset_wifi -> {
                    val ssid = wifiSsid.text.toString()
                    val pass = wifiPassword.text.toString()
                    val sec = secCodes[wifiSecurity.selectedItemPosition.coerceIn(0, secCodes.size - 1)]
                    val hidden = wifiHidden.isChecked
                    if (ssid.isNotBlank()) QrPresetFormatter.formatWifi(ssid, pass, sec, hidden) else ""
                }
                R.id.chip_preset_vcard -> {
                    val first = vcardFirst.text.toString()
                    val last = vcardLast.text.toString()
                    val phone = vcardPhone.text.toString()
                    val email = vcardEmail.text.toString()
                    val org = vcardOrg.text.toString()
                    if (first.isNotBlank() || last.isNotBlank() || phone.isNotBlank()) {
                        QrPresetFormatter.formatVCard(first, last, phone, email, org)
                    } else ""
                }
                R.id.chip_preset_email -> {
                    val recipient = emailRecipient.text.toString()
                    val subject = emailSubject.text.toString()
                    val body = emailBody.text.toString()
                    if (recipient.isNotBlank()) QrPresetFormatter.formatEmail(recipient, subject, body) else ""
                }
                R.id.chip_preset_sms -> {
                    val phone = smsPhone.text.toString()
                    val body = smsBody.text.toString()
                    if (phone.isNotBlank()) QrPresetFormatter.formatSms(phone, body) else ""
                }
                R.id.chip_preset_upi -> {
                    val vpa = upiVpa.text.toString().trim()
                    val amount = upiAmount.text.toString().trim()
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

        // 1. Style Spinner (12 Modes)
        val styles = QrStyle.values()
        val styleNames = styles.map {
            it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        styleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, styleNames)
        styleSpinner.setSelection(styles.indexOf(vm.state.value.style).coerceAtLeast(0))
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (vm.state.value.style != styles[pos]) {
                    vm.updateStyle(styles[pos])
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
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
        autoRepairBtn.setOnClickListener { vm.autoRepair() }
        saveBtn.setOnClickListener      { vm.saveToGallery() }
        saveSvgBtn.setOnClickListener   { vm.saveSvg() }
        saveGifBtn?.setOnClickListener   { vm.saveGif() }
        saveVideoBtn?.setOnClickListener { vm.saveVideo() }
        shareBtn.setOnClickListener     { vm.share() }
        logoBtn.setOnClickListener      { logoPickerLauncher.launch("image/*") }
        sourceImgBtn.setOnClickListener { sourceImagePickerLauncher.launch("*/*") }
        bgImageBtn.setOnClickListener   { bgImagePickerLauncher.launch("image/*") }

        fgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = true) }
        bgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = false) }

        // 6. Observe ViewModel State
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    state.bitmap?.let { previewImage.setImageBitmap(it) }

                    // Dynamic Background Image Card
                    if (state.backgroundImage != null) {
                        cardBgControls.visibility = View.VISIBLE
                        val opacityPct = (state.backgroundImageAlpha * 100f).toInt().coerceIn(5, 100)
                        bgOpacitySlider.value = opacityPct.toFloat()
                        bgOpacityLabel.text = "Opacity: $opacityPct%"
                    } else {
                        cardBgControls.visibility = View.GONE
                    }

                    // Dynamic Source Image (QR Photo) Card
                    if (state.sourceImage != null) {
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

                        if (resampleBackdropSwitch.isChecked != state.resampleUseSourceAsBackdrop) {
                            resampleBackdropSwitch.isChecked = state.resampleUseSourceAsBackdrop
                        }
                        containerBackdropOpacity.visibility = if (state.resampleUseSourceAsBackdrop) View.VISIBLE else View.GONE
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

                    // Update Live Verification Card
                    state.scanabilityReport?.let { report ->
                        if (report.isScanReady) {
                            scanabilityCard?.setCardBackgroundColor(0x1816A34A)
                            scanabilityCard?.strokeColor = 0x4016A34A
                            scanabilityIcon?.setImageResource(R.drawable.ic_check_circle)
                            scanabilityIcon?.setColorFilter(0xFF16A34A.toInt())
                            scanabilityStatus.text = "QR Verified"
                            scanabilityStatus.setTextColor(0xFF16A34A.toInt())
                            scanabilityDetails.text = "Ready to scan with standard camera and payment apps"
                            val engine = if (report.decodeResult.decoderId.contains("ML Kit", ignoreCase = true) || report.decodeResult.decoderId.contains("mlkit", ignoreCase = true)) "Google ML Kit" else "ZXing"
                            scanabilityTechText?.text = "Engine: $engine | Latency: ${report.decodeResult.latencyMs}ms | Quiet zone: 4 modules"
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
                        Toast.makeText(requireContext(), "Auto-Repair: $notice", Toast.LENGTH_SHORT).show()
                    }

                    state.saveResult?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        vm.clearSaveResult()
                    }
                    state.errorMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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

    private var previewView: PreviewView? = null
    private var resultCard: MaterialCardView? = null
    private var resultType: TextView? = null
    private var resultText: TextView? = null
    private var resultActionBtn: Button? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: androidx.camera.core.Camera? = null
    private var activeQrScanner: QrScanner? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission required for QR scanning", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), "No QR code found in image", Toast.LENGTH_SHORT).show()
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

        previewView     = view.findViewById(R.id.qr_camera_preview)
        resultCard      = view.findViewById(R.id.qr_result_card)
        resultType      = view.findViewById(R.id.qr_result_type)
        resultText      = view.findViewById(R.id.qr_result_text)
        resultActionBtn = view.findViewById(R.id.qr_result_action_btn)

        view.findViewById<Button>(R.id.qr_scan_gallery_btn).setOnClickListener {
            qrDecodePickerLauncher.launch("image/*")
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
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
                if (isAdded) handleScanResult(raw)
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
        } catch (_: Exception) {}
    }

    private fun handleScanResult(raw: String) {
        val action = PayloadParser.parse(raw)
        resultCard?.visibility = View.VISIBLE
        resultType?.text = "Detected: ${action::class.simpleName ?: "QR Code"}"
        resultText?.text = formatActionSummary(action)

        resultActionBtn?.setOnClickListener {
            executeAction(action)
        }
    }

    private fun formatActionSummary(action: QrAction): String = when (action) {
        is QrAction.Url -> action.uri
        is QrAction.Wifi -> "SSID: ${action.ssid} (Type: ${action.type})"
        is QrAction.Contact -> "${action.name ?: "Contact"}: ${action.phones.firstOrNull() ?: action.emails.firstOrNull() ?: ""}"
        is QrAction.UpiPayment -> "UPI: ${action.payeeAddress} (${action.amount ?: "No amount"})"
        is QrAction.Phone -> "Phone: ${action.number}"
        is QrAction.Sms -> "SMS: ${action.number}"
        is QrAction.Email -> "Email: ${action.address}"
        is QrAction.Geo -> "Coordinates: ${action.lat}, ${action.lon}"
        is QrAction.CalendarEvent -> "Event: ${action.title ?: "Calendar entry"}"
        is QrAction.OtpAuth -> "OTP Auth: ${action.account ?: action.issuer ?: ""}"
        is QrAction.Raw -> action.text
    }

    private fun executeAction(action: QrAction) {
        try {
            QrActionExecutor.execute(requireContext(), action)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to execute action: ${e.message}", Toast.LENGTH_SHORT).show()
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
