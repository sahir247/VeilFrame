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
        vm.terminateSession()
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
        val scanabilityStatus  = view.findViewById<TextView>(R.id.qr_scanability_status)
        val scanabilityDetails = view.findViewById<TextView>(R.id.qr_scanability_details)
        val autoRepairBtn      = view.findViewById<MaterialButton>(R.id.qr_auto_repair_btn)

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
        val bgImageBtn         = view.findViewById<MaterialButton>(R.id.qr_bg_image_btn)

        // Dynamic Customization Cards
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
        val shareBtn           = view.findViewById<MaterialButton>(R.id.qr_share_btn)

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

        upiVpa.addTextChangedListener(liveWatcher)

        // UPI Amount Slider & Text synchronization (Up to 1 Lakh INR)
        var isUpdatingUpiAmount = false
        val MAX_UPI_AMOUNT = 100000f

        fun syncUpiAmount(value: Float, updateText: Boolean, updateSlider: Boolean) {
            isUpdatingUpiAmount = true
            if (value <= 0f) {
                if (updateText) upiAmount.setText("")
                if (updateSlider) upiAmountSlider.value = 0f
                upiAmountStatus.text = "Optional (No Amount)"
                layoutUpiAmount?.error = null
            } else {
                val clamped = value.coerceAtMost(MAX_UPI_AMOUNT)
                val formatted = String.format(java.util.Locale.US, "%.2f", clamped)
                if (updateText) upiAmount.setText(formatted)
                if (updateSlider) upiAmountSlider.value = clamped.coerceIn(upiAmountSlider.valueFrom, upiAmountSlider.valueTo)
                upiAmountStatus.text = "Amount: ₹$formatted"
                if (value > MAX_UPI_AMOUNT) {
                    layoutUpiAmount?.error = "Max allowed amount is ₹1,00,000 (1 Lakh)"
                } else {
                    layoutUpiAmount?.error = null
                }
            }
            isUpdatingUpiAmount = false
            compileActivePreset()
        }

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
                            layoutUpiAmount?.error = "Max allowed amount is ₹1,00,000 (1 Lakh)"
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

        // 5. Actions & Buttons
        autoRepairBtn.setOnClickListener { vm.autoRepair() }
        saveBtn.setOnClickListener      { vm.saveToGallery() }
        saveSvgBtn.setOnClickListener   { vm.saveSvg() }
        shareBtn.setOnClickListener     { vm.share() }
        logoBtn.setOnClickListener      { logoPickerLauncher.launch("image/*") }
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

                    // Dynamic Logo Card
                    if (state.logo != null) {
                        cardLogoControls.visibility = View.VISIBLE
                        val sizePct = (state.logoFraction * 100f).toInt().coerceIn(10, 35)
                        logoSizeSlider.value = sizePct.toFloat()
                        logoSizeLabel.text = "Size: $sizePct%"
                    } else {
                        cardLogoControls.visibility = View.GONE
                    }

                    // Update Live Scanability Card
                    state.scanabilityReport?.let { report ->
                        if (report.isScanReady) {
                            scanabilityStatus.text = "[PASS] Scan-ready"
                            scanabilityStatus.setTextColor(0xFF16A34A.toInt()) // Green
                            scanabilityDetails.text = "ZXing verified in ${report.decodeResult.latencyMs}ms"
                            autoRepairBtn.visibility = View.GONE
                        } else {
                            scanabilityStatus.text = "[FAIL] Scan risk detected"
                            scanabilityStatus.setTextColor(0xFFDC2626.toInt()) // Red
                            val reason = report.decodeResult.error
                                ?: report.warnings.firstOrNull()
                                ?: "Decoder could not read image"
                            scanabilityDetails.text = reason
                            autoRepairBtn.visibility = if (report.repairSuggestions.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                    } ?: run {
                        scanabilityStatus.text = "[INFO] Validating..."
                        scanabilityStatus.setTextColor(0xFF6B7280.toInt())
                        scanabilityDetails.text = "Running ZXing deterministic decoder"
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
        val colorNames = arrayOf(
            "Black", "White", "Dark Slate", "Navy Blue", "Emerald Green", "Crimson Red", "Amber", "Purple"
        )
        val colorValues = intArrayOf(
            Color.BLACK,
            Color.WHITE,
            0xFF18181B.toInt(),
            0xFF1E3A8A.toInt(),
            0xFF065F46.toInt(),
            0xFF991B1B.toInt(),
            0xFFB45309.toInt(),
            0xFF581C87.toInt()
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isForeground) "Select Foreground Color" else "Select Background Color")
            .setItems(colorNames) { _, which ->
                val chosen = colorValues[which]
                if (isForeground) vm.updateForeground(chosen) else vm.updateBackground(chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        val scanner = QrScanner { raw ->
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
            provider.bindToLifecycle(
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
        activeQrScanner?.close()
        activeQrScanner = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
