package com.veilframe.app.qr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutQrStudioBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.qr.AnimatedMediaHelper
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.FunctionType
import com.veilframe.app.qr.model.GradientType
import com.veilframe.app.qr.model.ImageColorStrategy
import com.veilframe.app.qr.model.ImageScaleMode
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.LineVariant
import com.veilframe.app.qr.model.LogoShape
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrPresetFormatter
import com.veilframe.app.qr.renderer.RngMode
import com.veilframe.app.qr.model.VeilFunctionDataStyle
import com.veilframe.app.qr.model.VeilFunctionType
import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.QrScanner
import com.veilframe.app.qr.scanner.action.QrActionExecutor
import com.veilframe.app.ui.motion.VeilFrameInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Modern View-based secondary workspace controller for QR Studio.
 * Completely eliminates legacy Fragments and ViewPager2, while preserving 100% of the
 * artistic generator controls, customization parameters, animation capabilities, and camera scanner features.
 */
class QrStudioController(
    private val activity: AppCompatActivity,
    val binding: LayoutQrStudioBinding,
    private val vm: QrStudioViewModel,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    val root: View get() = binding.root

    enum class Mode {
        GENERATE,
        SCAN
    }

    private var currentMode: Mode = Mode.GENERATE

    // =========================================================================
    // 1. GENERATE STATE & JOBS
    // =========================================================================
    private var previewAnimJob: Job? = null
    private var activePreviewFrames: List<QrFrame>? = null
    private var lastAnimatedSourceUri: Uri? = null
    private var lastAnimatedLogoUri: Uri? = null
    private var isProgrammaticUiUpdate = false
    private var isUpdatingUpiAmount = false
    private val MAX_UPI_AMOUNT = 100000f
    private var lastAnnouncedScanReady: Boolean? = null

    // =========================================================================
    // 2. SCANNER STATE
    // =========================================================================
    private enum class CameraUiState {
        INITIALIZING,
        PERMISSION_REQUIRED,
        PERMISSION_DENIED,
        READY,
        ERROR,
        RESULT_PRESENTED
    }

    private var currentUiState: CameraUiState = CameraUiState.INITIALIZING
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var activeQrScanner: QrScanner? = null
    private var isTorchOn: Boolean = false
    private var isScanningPaused: Boolean = false
    private var currentScannedAction: QrAction? = null
    private var isPasswordMasked: Boolean = true
    private var scaleGestureDetector: ScaleGestureDetector? = null

    private var hasRequestedPermissionOnce: Boolean
        get() = activity.getSharedPreferences("qr_scan_prefs", Context.MODE_PRIVATE).getBoolean("has_req_cam", false)
        set(value) {
            activity.getSharedPreferences("qr_scan_prefs", Context.MODE_PRIVATE).edit().putBoolean("has_req_cam", value).apply()
        }

    // =========================================================================
    // 3. MEDIA & PERMISSION HANDLERS (Invoked by MainActivityLaunchers)
    // =========================================================================
    fun onCameraPermissionResult(isGranted: Boolean) {
        hasRequestedPermissionOnce = true
        checkPermissionState()
    }

    fun handleGalleryDecode(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val raw = try {
                decodeGalleryUri(activity, uri)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (raw != null) {
                    handleScanResult(raw)
                } else {
                    Snackbar.make(binding.root, "No QR code found in image", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleLogoSelected(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = activity
            val kind = AnimatedMediaHelper.detectMediaKind(ctx, uri)
            val isAnimatedCandidate = kind == AnimatedMediaHelper.MediaKind.GIF ||
                kind == AnimatedMediaHelper.MediaKind.WEBP ||
                kind == AnimatedMediaHelper.MediaKind.VIDEO

            if (isAnimatedCandidate) {
                val maxFrames = vm.state.value.maxAnimationFrames
                val frames = AnimatedMediaHelper.extractFrames(ctx, uri, maxFrames = maxFrames)
                if (frames.size > 1) {
                    withContext(Dispatchers.Main) {
                        lastAnimatedLogoUri = uri
                        vm.updateLogoAnimatedFrames(frames.map { it.bitmap }, frames.map { it.durationMs })
                        val label = when (kind) {
                            AnimatedMediaHelper.MediaKind.GIF -> "GIF"
                            AnimatedMediaHelper.MediaKind.WEBP -> "WebP"
                            else -> "video"
                        }
                        Toast.makeText(activity, "Imported animated logo $label (${frames.size} frames)", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } else if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        lastAnimatedLogoUri = null
                        vm.updateLogo(frames[0].bitmap)
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
                if (bmp != null) {
                    lastAnimatedLogoUri = null
                    vm.updateLogo(bmp)
                } else {
                    Toast.makeText(activity, "Could not load logo: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleBackdropSelected(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = activity
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
                if (bmp != null) {
                    vm.updateBackdropImage(bmp)
                } else {
                    Toast.makeText(activity, "Could not load backdrop: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleSourceSelected(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = activity
            val kind = AnimatedMediaHelper.detectMediaKind(ctx, uri)
            val isAnimatedCandidate = kind == AnimatedMediaHelper.MediaKind.GIF ||
                kind == AnimatedMediaHelper.MediaKind.WEBP ||
                kind == AnimatedMediaHelper.MediaKind.VIDEO

            if (isAnimatedCandidate) {
                val maxFrames = vm.state.value.maxAnimationFrames
                val frames = AnimatedMediaHelper.extractFrames(ctx, uri, maxFrames = maxFrames)
                if (frames.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        lastAnimatedSourceUri = uri
                        vm.updateAnimatedFrames(frames)
                        val label = when (kind) {
                            AnimatedMediaHelper.MediaKind.GIF -> "GIF"
                            AnimatedMediaHelper.MediaKind.WEBP -> "WebP"
                            else -> "video"
                        }
                        Toast.makeText(activity, "Imported $label (${frames.size} frames)", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
            }

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
                if (bmp != null) {
                    lastAnimatedSourceUri = null
                    vm.updateSourceImage(bmp)
                } else {
                    Toast.makeText(activity, "Could not load media: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleBgImageSelected(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = activity
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
                if (bmp != null) {
                    vm.updateBackgroundImage(bmp)
                } else {
                    Toast.makeText(activity, "Could not load background: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleResampleBackdropSelected(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = activity
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
                if (bmp != null) {
                    vm.updateResampleBackdropImage(bmp)
                } else {
                    Toast.makeText(activity, "Could not load image: unsupported or corrupt file", Toast.LENGTH_SHORT).show()
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

    init {
        VeilFrameInteraction.bindWorkspace(binding.root)
        bindHeaderAndModeSwitcher()
        bindGenerateUI()
        bindScannerUI()
        observeViewModel()
    }

    // =========================================================================
    // 4. HEADER & MODE SWITCHER
    // =========================================================================
    private fun bindHeaderAndModeSwitcher() {
        binding.toolbarQrStudio.setNavigationOnClickListener {
            onNavigateBack()
        }
        binding.btnQrReset.setOnClickListener {
            discardAndReleaseAllResources()
            Toast.makeText(activity, "Reset QR settings to default", Toast.LENGTH_SHORT).show()
        }

        binding.toggleQrMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeGenerate -> switchMode(Mode.GENERATE)
                    R.id.btnModeScan -> switchMode(Mode.SCAN)
                }
            }
        }
    }

    fun switchMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode
        if (mode == Mode.GENERATE) {
            binding.scrollQrGenerate.visibility = View.VISIBLE
            binding.containerQrScan.visibility = View.GONE
            binding.toggleQrMode.check(R.id.btnModeGenerate)
            stopCamera()
        } else {
            binding.scrollQrGenerate.visibility = View.GONE
            binding.containerQrScan.visibility = View.VISIBLE
            binding.toggleQrMode.check(R.id.btnModeScan)
            checkPermissionState()
        }
    }

    private fun createDropdownAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(activity, R.layout.item_qr_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_qr_spinner_dropdown)
        }

    private fun createDropdownAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(activity, R.layout.item_qr_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_qr_spinner_dropdown)
        }

    // =========================================================================
    // 5. GENERATE UI SETUP (Preserves 100% of legacy controls)
    // =========================================================================
    private fun bindGenerateUI() {
        val view = binding.scrollQrGenerate

        val previewCard = view.findViewById<MaterialCardView>(R.id.qr_preview_card)
        val dm = activity.resources.displayMetrics
        val targetSize = minOf(dm.widthPixels - (32 * dm.density).toInt(), (360 * dm.density).toInt())
        previewCard?.layoutParams?.let { lp ->
            lp.width = targetSize
            lp.height = targetSize
            previewCard.layoutParams = lp
        }

        val headerAdvanced = view.findViewById<View>(R.id.qr_header_advanced_settings)
        headerAdvanced?.setOnClickListener {
            vm.toggleAdvancedExpanded()
        }

        val scanabilityDetailsToggle = view.findViewById<MaterialButton>(R.id.qr_scanability_details_toggle)
        scanabilityDetailsToggle?.setOnClickListener {
            vm.toggleScanDetailsExpanded()
        }

        val autoRepairBtn = view.findViewById<MaterialButton>(R.id.qr_auto_repair_btn)
        autoRepairBtn?.setOnClickListener {
            vm.autoRepair()
        }

        // Animation section
        val pickAnimWatermarkBtn = view.findViewById<MaterialButton>(R.id.qr_btn_pick_animated_watermark)
        val pickAnimLogoBtn = view.findViewById<MaterialButton>(R.id.qr_btn_pick_animated_logo)
        val clearAnimBtn = view.findViewById<MaterialButton>(R.id.qr_btn_clear_animation)
        val maxFramesSlider = view.findViewById<Slider>(R.id.qr_anim_max_frames_slider)
        val maxFramesLabel = view.findViewById<TextView>(R.id.qr_anim_max_frames_label)

        maxFramesSlider.setSnappedValue(vm.state.value.maxAnimationFrames.toFloat())
        maxFramesLabel?.text = "${vm.state.value.maxAnimationFrames} frames"
        maxFramesSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isProgrammaticUiUpdate) {
                val limit = value.toInt().coerceIn(12, 360)
                maxFramesLabel?.text = "$limit frames"
                vm.updateMaxAnimationFrames(limit)
            }
        }
        maxFramesSlider?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val sourceUri = lastAnimatedSourceUri
                val logoUri = lastAnimatedLogoUri
                if (sourceUri != null) handleSourceSelected(sourceUri)
                if (logoUri != null) handleLogoSelected(logoUri)
            }
        })

        pickAnimWatermarkBtn?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrSourceImagePicker()
        }
        pickAnimLogoBtn?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrLogoPicker()
        }
        clearAnimBtn?.setOnClickListener {
            lastAnimatedSourceUri = null
            lastAnimatedLogoUri = null
            vm.clearAnimation()
            Toast.makeText(activity, "Animation cleared", Toast.LENGTH_SHORT).show()
        }

        bindInfoButtons(view)

        // Presets Chips & Containers
        val presetChipGroup = view.findViewById<ChipGroup>(R.id.qr_preset_chip_group)
        val containerText = view.findViewById<LinearLayout>(R.id.container_preset_text)
        val containerWifi = view.findViewById<LinearLayout>(R.id.container_preset_wifi)
        val containerVcard = view.findViewById<LinearLayout>(R.id.container_preset_vcard)
        val containerEmail = view.findViewById<LinearLayout>(R.id.container_preset_email)
        val containerSms = view.findViewById<LinearLayout>(R.id.container_preset_sms)
        val containerUpi = view.findViewById<LinearLayout>(R.id.container_preset_upi)

        // 1. URL / Text
        val contentInput = view.findViewById<EditText>(R.id.qr_content_input)

        // 2. Wi-Fi
        val wifiSsid = view.findViewById<EditText>(R.id.qr_wifi_ssid)
        val wifiPassword = view.findViewById<EditText>(R.id.qr_wifi_password)
        val wifiSecurity = view.findViewById<Spinner>(R.id.qr_wifi_security_spinner)
        val wifiHidden = view.findViewById<CheckBox>(R.id.qr_wifi_hidden_check)

        val secOptions = arrayOf("WPA / WPA2 / WPA3", "WEP", "Open (None)")
        val secCodes = arrayOf("WPA", "WEP", "nopass")
        wifiSecurity.adapter = createDropdownAdapter(secOptions)

        // 3. Contact (vCard)
        val vcardFirst = view.findViewById<EditText>(R.id.qr_vcard_first_name)
        val vcardLast = view.findViewById<EditText>(R.id.qr_vcard_last_name)
        val vcardPhone = view.findViewById<EditText>(R.id.qr_vcard_phone)
        val vcardEmail = view.findViewById<EditText>(R.id.qr_vcard_email)
        val vcardOrg = view.findViewById<EditText>(R.id.qr_vcard_org)
        val vcardUrl = view.findViewById<EditText>(R.id.qr_vcard_url)

        // 4. Email
        val emailRecipient = view.findViewById<EditText>(R.id.qr_email_recipient)
        val emailSubject = view.findViewById<EditText>(R.id.qr_email_subject)
        val emailBody = view.findViewById<EditText>(R.id.qr_email_body)

        // 5. SMS
        val smsPhone = view.findViewById<EditText>(R.id.qr_sms_phone)
        val smsBody = view.findViewById<EditText>(R.id.qr_sms_body)

        // 6. UPI
        val layoutUpiAmount = view.findViewById<TextInputLayout>(R.id.layout_upi_amount)
        val upiVpa = view.findViewById<EditText>(R.id.qr_upi_vpa)
        val upiAmount = view.findViewById<EditText>(R.id.qr_upi_amount)
        val upiPayeeName = view.findViewById<EditText>(R.id.qr_upi_payee_name)
        val upiNote = view.findViewById<EditText>(R.id.qr_upi_note)
        val upiAmountSlider = view.findViewById<Slider>(R.id.qr_upi_amount_slider)
        val upiAmountStatus = view.findViewById<TextView>(R.id.qr_upi_amount_status)
        val upiClearAmountBtn = view.findViewById<MaterialButton>(R.id.qr_upi_clear_amount_btn)
        val upiChipNone = view.findViewById<Chip>(R.id.chip_upi_none)
        val upiChip50 = view.findViewById<Chip>(R.id.chip_upi_50)
        val upiChip100 = view.findViewById<Chip>(R.id.chip_upi_100)
        val upiChip200 = view.findViewById<Chip>(R.id.chip_upi_200)
        val upiChip500 = view.findViewById<Chip>(R.id.chip_upi_500)
        val upiChip1000 = view.findViewById<Chip>(R.id.chip_upi_1000)
        val upiChip2000 = view.findViewById<Chip>(R.id.chip_upi_2000)
        val upiChip5000 = view.findViewById<Chip>(R.id.chip_upi_5000)
        val upiChip10000 = view.findViewById<Chip>(R.id.chip_upi_10000)
        val upiChip50000 = view.findViewById<Chip>(R.id.chip_upi_50000)
        val upiChip1lakh = view.findViewById<Chip>(R.id.chip_upi_1lakh)

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

        // Restore preset selection
        val presetId = if (initState.activePresetId != 0) initState.activePresetId else R.id.chip_preset_text
        presetChipGroup.check(presetId)
        containerText.visibility = if (presetId == R.id.chip_preset_text) View.VISIBLE else View.GONE
        containerWifi.visibility = if (presetId == R.id.chip_preset_wifi) View.VISIBLE else View.GONE
        containerVcard.visibility = if (presetId == R.id.chip_preset_vcard) View.VISIBLE else View.GONE
        containerEmail.visibility = if (presetId == R.id.chip_preset_email) View.VISIBLE else View.GONE
        containerSms.visibility = if (presetId == R.id.chip_preset_sms) View.VISIBLE else View.GONE
        containerUpi.visibility = if (presetId == R.id.chip_preset_upi) View.VISIBLE else View.GONE

        // Spinners & Buttons
        val styleChipGroup = view.findViewById<ChipGroup>(R.id.qr_style_chip_group)
        val fgColorBtn = view.findViewById<MaterialButton>(R.id.qr_fg_color_btn)
        val bgColorBtn = view.findViewById<MaterialButton>(R.id.qr_bg_color_btn)
        val ecSpinner = view.findViewById<Spinner>(R.id.qr_ec_spinner)
        val logoBtn = view.findViewById<MaterialButton>(R.id.qr_logo_btn)
        val sourceImgBtn = view.findViewById<MaterialButton>(R.id.qr_source_image_btn)
        val bgImageBtn = view.findViewById<MaterialButton>(R.id.qr_bg_image_btn)

        val resSpinner = view.findViewById<Spinner>(R.id.qr_resolution_spinner)
        val removeBgBtn = view.findViewById<MaterialButton>(R.id.qr_remove_bg_btn)
        val bgOpacitySlider = view.findViewById<Slider>(R.id.qr_bg_opacity_slider)
        val bgOpacityLabel = view.findViewById<TextView>(R.id.qr_bg_opacity_label)
        val removeLogoBtn = view.findViewById<MaterialButton>(R.id.qr_remove_logo_btn)
        val logoSizeSlider = view.findViewById<Slider>(R.id.qr_logo_size_slider)
        val logoSizeLabel = view.findViewById<TextView>(R.id.qr_logo_size_label)

        val removeSourceBtn = view.findViewById<MaterialButton>(R.id.qr_remove_source_img_btn)
        val sourceScaleSpinner = view.findViewById<Spinner>(R.id.qr_source_scale_spinner)
        val sourceContrastSlider = view.findViewById<Slider>(R.id.qr_source_contrast_slider)
        val sourceContrastLabel = view.findViewById<TextView>(R.id.qr_source_contrast_label)
        val sourceExposureSlider = view.findViewById<Slider>(R.id.qr_source_exposure_slider)
        val sourceExposureLabel = view.findViewById<TextView>(R.id.qr_source_exposure_label)
        val sourceOpacitySlider = view.findViewById<Slider>(R.id.qr_source_opacity_slider)
        val sourceOpacityLabel = view.findViewById<TextView>(R.id.qr_source_opacity_label)

        val resampleBackdropSwitch = view.findViewById<MaterialSwitch>(R.id.qr_resample_backdrop_switch)
        val resampleBackdropOpacitySlider = view.findViewById<Slider>(R.id.qr_resample_backdrop_opacity_slider)
        val resampleBackdropOpacityLabel = view.findViewById<TextView>(R.id.qr_resample_backdrop_opacity_label)
        val resampleSeedBtn = view.findViewById<MaterialButton>(R.id.qr_resample_seed_btn)

        val chooseBgRequiredBtn = view.findViewById<MaterialButton>(R.id.qr_choose_bg_required_btn)
        val imageAllowTransparentSwitch = view.findViewById<MaterialSwitch>(R.id.qr_image_allow_transparent_switch)
        val imageDataScaleSlider = view.findViewById<Slider>(R.id.qr_image_data_scale_slider)
        val imageDataScaleLabel = view.findViewById<TextView>(R.id.qr_image_data_scale_label)
        val imageDataLightColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_data_light_color_btn)
        val imageTransparentLightBtn = view.findViewById<MaterialButton>(R.id.qr_image_data_transparent_light_btn)
        val imageApplySamplePresetBtn = view.findViewById<MaterialButton>(R.id.qr_image_apply_sample_preset_btn)
        val imageApplyStandardEfBtn = view.findViewById<MaterialButton>(R.id.qr_image_apply_standard_ef_btn)
        val imageStrategyFixedBtn = view.findViewById<MaterialButton>(R.id.qr_image_strategy_fixed_btn)
        val imageStrategyPaletteBtn = view.findViewById<MaterialButton>(R.id.qr_image_strategy_palette_btn)
        val imageStrategyContrastBtn = view.findViewById<MaterialButton>(R.id.qr_image_strategy_contrast_btn)

        val saveBtn = view.findViewById<MaterialButton>(R.id.qr_save_btn)
        val saveSvgBtn = view.findViewById<MaterialButton>(R.id.qr_save_svg_btn)
        val saveJpegBtn = view.findViewById<MaterialButton>(R.id.qr_save_jpeg_btn)
        val saveGifBtn = view.findViewById<MaterialButton>(R.id.qr_save_gif_btn)
        val saveVideoBtn = view.findViewById<MaterialButton>(R.id.qr_save_video_btn)
        val saveAnimatedSvgBtn = view.findViewById<MaterialButton>(R.id.qr_save_animated_svg_btn)
        val shareBtn = view.findViewById<MaterialButton>(R.id.qr_share_btn)

        // Setup Source Scale Spinner
        val scaleOptions = arrayOf("Aspect Fill", "Aspect Fit", "Center Crop", "Stretch")
        val scaleEnums = arrayOf(
            ImageScaleMode.ASPECT_FILL,
            ImageScaleMode.ASPECT_FIT,
            ImageScaleMode.CENTER_CROP,
            ImageScaleMode.STRETCH
        )
        sourceScaleSpinner.adapter = createDropdownAdapter(scaleOptions)
        val currentScaleIdx = scaleEnums.indexOf(vm.state.value.sourceImageScaleMode).coerceAtLeast(0)
        sourceScaleSpinner.setSelection(currentScaleIdx)
        sourceScaleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateSourceImageScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        fun compileActivePreset() {
            if (isProgrammaticUiUpdate) return
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

        presetChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_preset_text
            vm.updateActivePreset(checkedId)
            containerText.visibility = if (checkedId == R.id.chip_preset_text) View.VISIBLE else View.GONE
            containerWifi.visibility = if (checkedId == R.id.chip_preset_wifi) View.VISIBLE else View.GONE
            containerVcard.visibility = if (checkedId == R.id.chip_preset_vcard) View.VISIBLE else View.GONE
            containerEmail.visibility = if (checkedId == R.id.chip_preset_email) View.VISIBLE else View.GONE
            containerSms.visibility = if (checkedId == R.id.chip_preset_sms) View.VISIBLE else View.GONE
            containerUpi.visibility = if (checkedId == R.id.chip_preset_upi) View.VISIBLE else View.GONE
            compileActivePreset()
        }

        val liveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { compileActivePreset() }
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

        fun syncUpiAmount(value: Float, updateText: Boolean, updateSlider: Boolean) {
            isUpdatingUpiAmount = true
            if (value <= 0f) {
                if (updateText) upiAmount.setText("")
                if (updateSlider) upiAmountSlider.value = 0f
                upiAmountStatus.text = "Amount: Not specified"
                layoutUpiAmount?.error = null
            } else {
                val clamped = value.coerceIn(0f, MAX_UPI_AMOUNT)
                val formatted = if (clamped % 1.0f == 0f) String.format(Locale.US, "%.0f", clamped) else String.format(Locale.US, "%.2f", clamped)
                if (updateText) upiAmount.setText(formatted)
                if (updateSlider) {
                    try {
                        val safeVal = clamped.coerceIn(upiAmountSlider.valueFrom, upiAmountSlider.valueTo)
                        upiAmountSlider.value = safeVal
                    } catch (e: Exception) {
                        android.util.Log.w("QrStudio", "Slider set value error: " + clamped, e)
                    }
                }
                upiAmountStatus.text = "Amount: ₹" + formatted
                if (value > MAX_UPI_AMOUNT) {
                    layoutUpiAmount?.error = "Maximum ₹1,00,000"
                } else {
                    layoutUpiAmount?.error = null
                }
            }
            isUpdatingUpiAmount = false
            compileActivePreset()
        }

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

        upiClearAmountBtn.setOnClickListener { syncUpiAmount(0f, updateText = true, updateSlider = true) }
        upiChipNone?.setOnClickListener { syncUpiAmount(0f, updateText = true, updateSlider = true) }
        upiChip50?.setOnClickListener { syncUpiAmount(50f, updateText = true, updateSlider = true) }
        upiChip100?.setOnClickListener { syncUpiAmount(100f, updateText = true, updateSlider = true) }
        upiChip200?.setOnClickListener { syncUpiAmount(200f, updateText = true, updateSlider = true) }
        upiChip500?.setOnClickListener { syncUpiAmount(500f, updateText = true, updateSlider = true) }
        upiChip1000?.setOnClickListener { syncUpiAmount(1000f, updateText = true, updateSlider = true) }
        upiChip2000?.setOnClickListener { syncUpiAmount(2000f, updateText = true, updateSlider = true) }
        upiChip5000?.setOnClickListener { syncUpiAmount(5000f, updateText = true, updateSlider = true) }
        upiChip10000?.setOnClickListener { syncUpiAmount(10000f, updateText = true, updateSlider = true) }
        upiChip50000?.setOnClickListener { syncUpiAmount(50000f, updateText = true, updateSlider = true) }
        upiChip1lakh?.setOnClickListener { syncUpiAmount(100000f, updateText = true, updateSlider = true) }

        // Style Selector Chips
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
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedStyle = chipIdToStyle[checkedId] ?: return@setOnCheckedStateChangeListener
            if (vm.state.value.style != selectedStyle) {
                vm.updateStyle(selectedStyle)
            }
        }

        // Style Settings Containers & Sliders
        val d25DepthSlider = view.findViewById<Slider>(R.id.qr_d25_depth_slider)
        val d25DepthLabel = view.findViewById<TextView>(R.id.qr_d25_depth_label)
        val d25PosDepthSlider = view.findViewById<Slider>(R.id.qr_d25_pos_depth_slider)
        val d25PosDepthLabel = view.findViewById<TextView>(R.id.qr_d25_pos_depth_label)
        val d25AngleSlider = view.findViewById<Slider>(R.id.qr_d25_angle_slider)
        val d25AngleLabel = view.findViewById<TextView>(R.id.qr_d25_angle_label)

        d25DepthSlider.setSnappedValue(vm.state.value.d25Depth)
        d25DepthLabel?.text = String.format(Locale.US, "Body 3D Depth: %.2fx", vm.state.value.d25Depth)
        d25DepthSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25Depth(value)
                d25DepthLabel?.text = String.format(Locale.US, "Body 3D Depth: %.2fx", value)
            }
        }

        d25PosDepthSlider.setSnappedValue(vm.state.value.d25PositionDepth)
        d25PosDepthLabel?.text = String.format(Locale.US, "Finder 3D Depth: %.2fx", vm.state.value.d25PositionDepth)
        d25PosDepthSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25PositionDepth(value)
                d25PosDepthLabel?.text = String.format(Locale.US, "Finder 3D Depth: %.2fx", value)
            }
        }

        d25AngleSlider.setSnappedValue(vm.state.value.d25Angle)
        d25AngleLabel?.text = String.format(Locale.US, "Projection Angle: %.0f°", vm.state.value.d25Angle)
        d25AngleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateD25Angle(value)
                d25AngleLabel?.text = String.format(Locale.US, "Projection Angle: %.0f°", value)
            }
        }

        val d25TopColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_top_color_btn)
        val d25LeftColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_left_color_btn)
        val d25RightColorBtn = view.findViewById<MaterialButton>(R.id.qr_d25_right_color_btn)

        d25TopColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25TopColor ?: vm.state.value.foreground) {
                vm.updateD25TopColor(it)
            }
        }
        d25LeftColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25LeftColor) {
                vm.updateD25Colors(leftColor = it, rightColor = vm.state.value.d25RightColor)
            }
        }
        d25RightColorBtn?.setOnClickListener {
            pickColor(vm.state.value.d25RightColor) {
                vm.updateD25Colors(leftColor = vm.state.value.d25LeftColor, rightColor = it)
            }
        }

        // LINE controls
        val lineDirGroup = view.findViewById<ChipGroup>(R.id.qr_line_direction_group)
        val lineVariantGroup = view.findViewById<ChipGroup>(R.id.qr_line_variant_group)
        val lineThicknessSlider = view.findViewById<Slider>(R.id.qr_line_thickness_slider)
        val lineThicknessLabel = view.findViewById<TextView>(R.id.qr_line_thickness_label)

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
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
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
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: R.id.chip_line_variant_standard
            val variant = if (id == R.id.chip_line_variant_circuit) LineVariant.CIRCUIT else LineVariant.EF
            vm.updateLineVariant(variant)
        }

        lineThicknessSlider.setSnappedValue(vm.state.value.lineThickness)
        lineThicknessLabel?.text = String.format(Locale.US, "Line Thickness: %.2f", vm.state.value.lineThickness)
        lineThicknessSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLineThickness(value)
                lineThicknessLabel?.text = String.format(Locale.US, "Line Thickness: %.2f", value)
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
            if (!isProgrammaticUiUpdate) vm.updateLineAccentRings(isChecked)
        }
        lineCircuitBridgesSwitch?.isChecked = vm.state.value.lineCircuitBridges
        lineCircuitBridgesSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (!isProgrammaticUiUpdate) vm.updateLineCircuitBridges(isChecked)
        }

        // DSJ controls
        val dsjLineSlider = view.findViewById<Slider>(R.id.qr_dsj_line_slider)
        val dsjLineLabel = view.findViewById<TextView>(R.id.qr_dsj_line_label)
        val dsjXSlider = view.findViewById<Slider>(R.id.qr_dsj_x_slider)
        val dsjXLabel = view.findViewById<TextView>(R.id.qr_dsj_x_label)
        val dsjHColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_h_color_btn)
        val dsjVColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_v_color_btn)
        val dsjXColorBtn = view.findViewById<MaterialButton>(R.id.qr_dsj_x_color_btn)

        dsjLineSlider?.value = vm.state.value.dsjLineSize.coerceIn(0.3f, 1.0f)
        dsjLineLabel?.text = String.format(Locale.US, "Cross Arm Size: %.2f", vm.state.value.dsjLineSize)
        dsjLineSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateDsjLineSize(value)
                dsjLineLabel?.text = String.format(Locale.US, "Cross Arm Size: %.2f", value)
            }
        }

        dsjXSlider?.value = vm.state.value.dsjXSize.coerceIn(0.3f, 1.0f)
        dsjXLabel?.text = String.format(Locale.US, "Center X Size: %.2f", vm.state.value.dsjXSize)
        dsjXSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateDsjXSize(value)
                dsjXLabel?.text = String.format(Locale.US, "Center X Size: %.2f", value)
            }
        }

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

        // RANDOM RECT controls
        val randomJitterScaleSlider = view.findViewById<Slider>(R.id.qr_random_jitter_scale_slider)
        val randomJitterScaleLabel = view.findViewById<TextView>(R.id.qr_random_jitter_scale_label)
        val randomJitterOffsetSlider = view.findViewById<Slider>(R.id.qr_random_jitter_offset_slider)
        val randomJitterOffsetLabel = view.findViewById<TextView>(R.id.qr_random_jitter_offset_label)
        val randomJitterColorSlider = view.findViewById<Slider>(R.id.qr_random_jitter_color_slider)
        val randomJitterColorLabel = view.findViewById<TextView>(R.id.qr_random_jitter_color_label)
        val randomRectColorBtn = view.findViewById<MaterialButton>(R.id.qr_random_rect_color_btn)
        val randomSeedInput = view.findViewById<EditText>(R.id.qr_random_seed_input)
        val randomRectSeedBtn = view.findViewById<MaterialButton>(R.id.qr_random_rect_seed_btn)

        randomJitterScaleSlider?.value = vm.state.value.randomJitterScale.coerceIn(0.0f, 0.5f)
        randomJitterScaleLabel?.text = String.format(Locale.US, "Jitter Scale: %.2f", vm.state.value.randomJitterScale)
        randomJitterScaleSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateRandomJitter(scale = value, offset = vm.state.value.randomJitterOffset)
                randomJitterScaleLabel?.text = String.format(Locale.US, "Jitter Scale: %.2f", value)
            }
        }

        randomJitterOffsetSlider?.value = vm.state.value.randomJitterOffset.coerceIn(0.0f, 0.3f)
        randomJitterOffsetLabel?.text = String.format(Locale.US, "Jitter Offset: %.2f", vm.state.value.randomJitterOffset)
        randomJitterOffsetSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateRandomJitter(scale = vm.state.value.randomJitterScale, offset = value)
                randomJitterOffsetLabel?.text = String.format(Locale.US, "Jitter Offset: %.2f", value)
            }
        }

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
            Toast.makeText(activity, "Randomized pattern seed", Toast.LENGTH_SHORT).show()
        }

        // BUBBLE controls
        val bubbleAmbientSwitch = view.findViewById<MaterialSwitch>(R.id.qr_bubble_ambient_switch)
        val bubbleDensitySlider = view.findViewById<Slider>(R.id.qr_bubble_density_slider)
        val bubbleDensityLabel = view.findViewById<TextView>(R.id.qr_bubble_density_label)
        val bubbleOutlineColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_outline_color_btn)
        val bubbleCenterColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_center_color_btn)
        val bubblePosColorBtn = view.findViewById<MaterialButton>(R.id.qr_bubble_pos_color_btn)

        bubbleAmbientSwitch?.isChecked = vm.state.value.bubbleAmbient
        bubbleAmbientSwitch?.setOnCheckedChangeListener { _, isChecked ->
            vm.updateBubbleCluster(ambient = isChecked, density = vm.state.value.bubbleDensity)
        }

        bubbleDensitySlider?.value = vm.state.value.bubbleDensity.coerceIn(0.05f, 0.40f)
        bubbleDensityLabel?.text = String.format(Locale.US, "Ambient Density: %d%%", (vm.state.value.bubbleDensity * 100).toInt())
        bubbleDensitySlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBubbleCluster(ambient = vm.state.value.bubbleAmbient, density = value)
                bubbleDensityLabel?.text = String.format(Locale.US, "Ambient Density: %d%%", (value * 100).toInt())
            }
        }

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

        // FUNCTION controls
        val functionTypeGroup = view.findViewById<ChipGroup>(R.id.qr_function_type_group)
        val functionDataStyleGroup = view.findViewById<ChipGroup>(R.id.qr_function_data_style_group)
        val functionDataColorBtn = view.findViewById<MaterialButton>(R.id.qr_function_data_color_btn)
        val functionCircleColorBtn = view.findViewById<MaterialButton>(R.id.qr_function_circle_color_btn)

        val funcChipId = if (vm.state.value.veilFunctionType == VeilFunctionType.CIRCLE) R.id.chip_func_circle else R.id.chip_func_fade
        functionTypeGroup?.check(funcChipId)
        functionTypeGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: R.id.chip_func_fade
            val fType = if (id == R.id.chip_func_circle) VeilFunctionType.CIRCLE else VeilFunctionType.FADE
            vm.updateVeilFunction(type = fType, dataStyle = vm.state.value.veilFunctionDataStyle)
        }

        val dataStyleChipId = if (vm.state.value.veilFunctionDataStyle == VeilFunctionDataStyle.RECTANGLE) R.id.chip_func_data_rect else R.id.chip_func_data_round
        functionDataStyleGroup?.check(dataStyleChipId)
        functionDataStyleGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isProgrammaticUiUpdate) return@setOnCheckedStateChangeListener
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

        // STYLE_FUNCTION controls
        val styleFuncTypeSpinner = view.findViewById<Spinner>(R.id.qr_style_func_type_spinner)
        val styleFuncFreqLabel = view.findViewById<TextView>(R.id.qr_style_func_freq_label)
        val styleFuncFreqSlider = view.findViewById<Slider>(R.id.qr_style_func_freq_slider)
        val styleFuncAmpLabel = view.findViewById<TextView>(R.id.qr_style_func_amp_label)
        val styleFuncAmpSlider = view.findViewById<Slider>(R.id.qr_style_func_amp_slider)

        val funcTypes = FunctionType.values()
        val funcTypeNames = funcTypes.map { it.name.lowercase(Locale.US).replaceFirstChar { c -> c.uppercase() } }
        styleFuncTypeSpinner?.adapter = createDropdownAdapter(funcTypeNames)
        val currentFuncTypeIdx = funcTypes.indexOf(vm.state.value.paramFunctionType).coerceAtLeast(0)
        styleFuncTypeSpinner?.setSelection(currentFuncTypeIdx)
        styleFuncTypeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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

        // CONNECTED_ORGANIC controls
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

        // IMAGE_FILL controls
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
            (activity as? MainActivity)?.launchers?.launchQrSourceImagePicker()
        }

        // Resolution Spinner
        val resLabels = arrayOf(
            "256 × 256 — Fast / Small",
            "512 × 512 — Standard / Social",
            "1024 × 1024 — High Quality",
            "2048 × 2048 — Print Quality",
            "4096 × 4096 — Large Format"
        )
        val resValues = intArrayOf(256, 512, 1024, 2048, 4096)
        resSpinner.adapter = createDropdownAdapter(resLabels)
        val currentResIdx = resValues.indexOf(vm.state.value.outputSize).coerceAtLeast(0)
        resSpinner.setSelection(currentResIdx)
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
                if (vm.state.value.outputSize != resValues[pos]) {
                    vm.updateOutputSize(resValues[pos])
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Error Correction Spinner
        val ecOptions = listOf(
            "Auto" to ErrorCorrectionChoice.AUTO,
            "Low (7%)" to ErrorCorrectionChoice.L,
            "Medium (15%)" to ErrorCorrectionChoice.M,
            "Quartile (25%)" to ErrorCorrectionChoice.Q,
            "High (30%)" to ErrorCorrectionChoice.H
        )
        val ecLabels = ecOptions.map { it.first }
        ecSpinner.adapter = createDropdownAdapter(ecLabels)
        val currentEcIdx = ecOptions.indexOfFirst { it.second == vm.state.value.ecChoice }.coerceAtLeast(0)
        ecSpinner.setSelection(currentEcIdx)
        ecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
                val selectedEc = ecOptions[pos].second
                if (vm.state.value.ecChoice != selectedEc) {
                    vm.updateErrorCorrection(selectedEc)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        removeBgBtn.setOnClickListener { vm.removeBackgroundImage() }
        bgOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBackgroundImageAlpha(value / 100f)
                bgOpacityLabel.text = "Opacity: " + value.toInt() + "%"
            }
        }

        removeLogoBtn.setOnClickListener { vm.removeLogo() }
        logoSizeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLogoFraction(value / 100f)
                logoSizeLabel.text = "Size: " + value.toInt() + "%"
            }
        }

        removeSourceBtn.setOnClickListener { vm.removeSourceImage() }
        sourceContrastSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageContrast(value)
                sourceContrastLabel.text = "Contrast: " + value.toInt() + "%"
            }
        }
        sourceExposureSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageExposure(value)
                sourceExposureLabel.text = "Exposure: " + value.toInt() + "%"
            }
        }
        sourceOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateSourceImageOpacity(value / 100f)
                sourceOpacityLabel.text = "Opacity: " + value.toInt() + "%"
            }
        }

        imageAllowTransparentSwitch.setOnCheckedChangeListener { _, isChecked ->
            vm.updateImageAllowTransparent(isChecked)
        }
        imageDataScaleSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateImageDataScale(value / 100f)
                imageDataScaleLabel.text = "Data Module Scale: " + value.toInt() + "%"
            }
        }

        resampleBackdropSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (vm.state.value.resampleUseSourceAsBackdrop != isChecked) {
                vm.updateResampleUseSourceAsBackdrop(isChecked)
            }
            view.findViewById<View>(R.id.container_resample_backdrop_opacity)?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        resampleBackdropOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateResampleBackdropOpacity(value / 100f)
                resampleBackdropOpacityLabel.text = "Backdrop Opacity: " + value.toInt() + "%"
            }
        }
        resampleSeedBtn.setOnClickListener {
            vm.randomizeResampleSeed()
            Toast.makeText(activity, "Resample pattern randomized", Toast.LENGTH_SHORT).show()
        }

        // IMAGE Specific Controls
        val imageDataDarkColorBtn = view.findViewById<MaterialButton>(R.id.qr_image_data_dark_color_btn)
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
        imageTransparentLightBtn?.setOnClickListener {
            val isCurrentlyTransparent = Color.alpha(vm.state.value.imageDataLightColor) == 0
            vm.updateImageDataLightTransparent(!isCurrentlyTransparent)
        }
        imageApplySamplePresetBtn?.setOnClickListener {
            vm.applyImageReferencePreset()
            Toast.makeText(activity, "Applied Image Sample Preset (Cyan + White, 35%)", Toast.LENGTH_SHORT).show()
        }
        imageApplyStandardEfBtn?.setOnClickListener {
            vm.applyImageStandardEfDefaults()
            Toast.makeText(activity, "Reset to EF Defaults (100% + Opaque White)", Toast.LENGTH_SHORT).show()
        }
        imageStrategyFixedBtn?.setOnClickListener {
            vm.updateImageColorStrategy(ImageColorStrategy.FIXED)
            Toast.makeText(activity, "Color Strategy: Fixed", Toast.LENGTH_SHORT).show()
        }
        imageStrategyPaletteBtn?.setOnClickListener {
            vm.updateImageColorStrategy(ImageColorStrategy.ADAPTIVE_PALETTE)
            Toast.makeText(activity, "Color Strategy: Adaptive Palette", Toast.LENGTH_SHORT).show()
        }
        imageStrategyContrastBtn?.setOnClickListener {
            vm.updateImageColorStrategy(ImageColorStrategy.ADAPTIVE_CONTRAST)
            Toast.makeText(activity, "Color Strategy: Adaptive Contrast", Toast.LENGTH_SHORT).show()
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
            (activity as? MainActivity)?.launchers?.launchQrResampleBackdropPicker()
        }
        resampleBackdropImgRemoveBtn?.setOnClickListener { vm.removeResampleBackdropImage() }
        resampleBackdropScaleSpinner?.adapter = createDropdownAdapter(scaleOptions)
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

        val resampleDitherSwitch = view.findViewById<MaterialSwitch>(R.id.qr_resample_dither_switch)
        val resampleMinVersionSlider = view.findViewById<Slider>(R.id.qr_resample_min_version_slider)
        val resampleMinVersionLabel = view.findViewById<TextView>(R.id.qr_resample_min_version_label)

        resampleDitherSwitch?.isChecked = (vm.state.value.resampleDitherMode == com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG)
        resampleDitherSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUiUpdate) return@setOnCheckedChangeListener
            vm.updateResampleDitherMode(
                if (isChecked) com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG
                else com.veilframe.app.qr.model.ResampleDitherMode.STOCHASTIC_EF
            )
        }

        val initMinVer = vm.state.value.resampleMinVersion.coerceIn(1, 10)
        resampleMinVersionSlider?.value = initMinVer.toFloat()
        val initMods = initMinVer * 4 + 17
        resampleMinVersionLabel?.text = "V$initMinVer (${initMods}x${initMods})"
        resampleMinVersionSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isProgrammaticUiUpdate) return@addOnChangeListener
            val v = value.toInt().coerceIn(1, 10)
            val mods = v * 4 + 17
            resampleMinVersionLabel?.text = "V$v (${mods}x${mods})"
            vm.updateResampleMinVersion(v)
        }

        // CORE GEOMETRY Controls
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

        dataShapeSpinner?.adapter = createDropdownAdapter(shapeLabels)
        val currentDataShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.dataShape }.coerceAtLeast(0)
        dataShapeSpinner?.setSelection(currentDataShapeIdx)
        dataShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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
        finderShapeSpinner?.adapter = createDropdownAdapter(finderLabels)
        val currentFinderIdx = finderStyles.indexOfFirst { it.second == vm.state.value.finderStyle }.coerceAtLeast(0)
        finderShapeSpinner?.setSelection(currentFinderIdx)
        finderShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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

        timingShapeSpinner?.adapter = createDropdownAdapter(shapeLabels)
        val currentTimingShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.timingShape }.coerceAtLeast(0)
        timingShapeSpinner?.setSelection(currentTimingShapeIdx)
        timingShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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
        timingOnlyWhiteSwitch?.setOnCheckedChangeListener { _, isChecked -> vm.updateTimingOnlyWhite(isChecked) }

        alignShapeSpinner?.adapter = createDropdownAdapter(shapeLabels)
        val currentAlignShapeIdx = supportedShapes.indexOfFirst { it.second == vm.state.value.alignShape }.coerceAtLeast(0)
        alignShapeSpinner?.setSelection(currentAlignShapeIdx)
        alignShapeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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
        alignOnlyWhiteSwitch?.setOnCheckedChangeListener { _, isChecked -> vm.updateAlignOnlyWhite(isChecked) }

        val isCustomQuietZone = vm.state.value.quietZoneChoice != null
        val currentQuietZone = vm.resolveEffectiveQuietZone()
        quietZoneSlider?.value = currentQuietZone.toFloat().coerceIn(0.0f, 10.0f)
        quietZoneLabel?.text = if (isCustomQuietZone) "Quiet Zone Margin: " + currentQuietZone + " modules (Custom)" else "Quiet Zone Margin: " + currentQuietZone + " modules (Default)"
        quietZoneSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val qz = value.toInt()
                vm.updateQuietZone(qz)
                quietZoneLabel?.text = "Quiet Zone Margin: " + qz + " modules (Custom)"
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
            val l = qzLeftSlider?.value ?: 4f
            val t = qzTopSlider?.value ?: 4f
            val r = qzRightSlider?.value ?: 4f
            val b = qzBottomSlider?.value ?: 4f
            vm.updateAsymmetricQuietZone(enabled, l, t, r, b)
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
        gradientTypeSpinner?.adapter = createDropdownAdapter(gradientLabels)
        val currentGradIdx = gradientTypes.indexOfFirst { it.second == vm.state.value.gradientType }.coerceAtLeast(0)
        gradientTypeSpinner?.setSelection(currentGradIdx)
        gradientTypeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isProgrammaticUiUpdate) return
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
            (activity as? MainActivity)?.launchers?.launchQrBackdropPicker()
        }
        backdropImageRemoveBtn?.setOnClickListener { vm.updateBackdropImage(null) }

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
        backdropImageAlphaLabel?.text = "Backdrop Image Opacity: " + currentAlphaPct + "%"
        backdropImageAlphaSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateBackdropImageAlpha(value / 100f)
                backdropImageAlphaLabel?.text = "Backdrop Image Opacity: " + value.toInt() + "%"
            }
        }

        backdropScaleSpinner?.adapter = createDropdownAdapter(scaleOptions)
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
        logoShapeSpinner?.adapter = createDropdownAdapter(logoShapeLabels)
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
        logoAlphaLabel?.text = "Logo Opacity: " + currentLogoAlphaPct + "%"
        logoAlphaSlider?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                vm.updateLogoAlpha(value / 100f)
                logoAlphaLabel?.text = "Logo Opacity: " + value.toInt() + "%"
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
            pickColor(vm.state.value.logoBorderColor ?: vm.state.value.background) {
                vm.updateLogoBorderColor(it)
            }
        }

        logoScaleModeSpinner?.adapter = createDropdownAdapter(scaleOptions)
        val currentLogoScaleIdx = scaleEnums.indexOf(vm.state.value.logoScaleMode).coerceAtLeast(0)
        logoScaleModeSpinner?.setSelection(currentLogoScaleIdx)
        logoScaleModeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateLogoScaleMode(scaleEnums[pos.coerceIn(0, scaleEnums.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Quick Customize Media & Colors
        fgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = true) }
        bgColorBtn.setOnClickListener { showColorPaletteDialog(isForeground = false) }
        logoBtn.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrLogoPicker()
        }
        sourceImgBtn.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrSourceImagePicker()
        }
        bgImageBtn.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrBgImagePicker()
        }

        // Export Buttons
        saveBtn.setOnClickListener { vm.saveToGallery() }
        saveSvgBtn.setOnClickListener { vm.saveSvg() }
        saveJpegBtn.setOnClickListener { vm.saveJpeg() }
        saveGifBtn.setOnClickListener { vm.saveGif() }
        saveVideoBtn.setOnClickListener { vm.saveVideo() }
        saveAnimatedSvgBtn.setOnClickListener { vm.saveAnimatedSvg() }
        shareBtn.setOnClickListener { vm.share() }
    }

    private fun showColorPaletteDialog(isForeground: Boolean) {
        val currentColor = if (isForeground) vm.state.value.foreground else vm.state.value.background
        val oppositeColor = if (isForeground) vm.state.value.background else vm.state.value.foreground
        QrColorPickerDialog(
            context = activity,
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

    private fun pickColor(
        initialColor: Int,
        contrastAgainstColor: Int = Color.WHITE,
        isForeground: Boolean = true,
        onColorPicked: (Int) -> Unit
    ) {
        QrColorPickerDialog(
            context = activity,
            initialColor = initialColor,
            contrastAgainstColor = contrastAgainstColor,
            isForeground = isForeground,
            onColorSelected = onColorPicked
        ).show()
    }

    // =========================================================================
    // 6. SCANNER UI SETUP (Preserves 100% of legacy scanner features)
    // =========================================================================
    private fun bindScannerUI() {
        val view = binding.containerQrScan

        val previewView = view.findViewById<PreviewView>(R.id.qr_camera_preview)
        val torchBtn = view.findViewById<FloatingActionButton>(R.id.qr_torch_btn)
        val btnZoom1x = view.findViewById<TextView>(R.id.btn_qr_zoom_1x)
        val btnZoom2x = view.findViewById<TextView>(R.id.btn_qr_zoom_2x)
        val btnZoom3x = view.findViewById<TextView>(R.id.btn_qr_zoom_3x)
        val togglePwdBtn = view.findViewById<MaterialButton>(R.id.qr_result_toggle_pwd_btn)
        val scanGalleryBtn = view.findViewById<Button>(R.id.qr_scan_gallery_btn)
        val allowCameraBtn = view.findViewById<Button>(R.id.qr_allow_camera_btn)
        val openSettingsBtn = view.findViewById<Button>(R.id.qr_open_settings_btn)
        val scanAnotherBtn = view.findViewById<Button>(R.id.qr_scan_another_btn)
        val resultActionBtn = view.findViewById<Button>(R.id.qr_result_action_btn)

        torchBtn?.setOnClickListener { toggleTorch() }
        btnZoom1x?.setOnClickListener { setCameraZoom(1.0f) }
        btnZoom2x?.setOnClickListener { setCameraZoom(2.0f) }
        btnZoom3x?.setOnClickListener { setCameraZoom(3.0f) }

        scaleGestureDetector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = activeCamera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false
                val nextZoom = (zoomState.zoomRatio * detector.scaleFactor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cam.cameraControl.setZoomRatio(nextZoom)
                updateZoomButtonsUi(nextZoom)
                return true
            }
        })

        previewView?.setOnTouchListener { v, event ->
            scaleGestureDetector?.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && scaleGestureDetector?.isInProgress != true) {
                v.performClick()
            }
            true
        }

        togglePwdBtn?.setOnClickListener {
            isPasswordMasked = !isPasswordMasked
            togglePwdBtn.text = if (isPasswordMasked) "Show" else "Hide"
            currentScannedAction?.let { action ->
                view.findViewById<TextView>(R.id.qr_result_text)?.text = formatActionSummary(action, isPasswordMasked)
            }
        }

        scanGalleryBtn?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrGalleryDecode()
        }
        view.findViewById<Button>(R.id.qr_permission_import_gallery_btn)?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrGalleryDecode()
        }
        view.findViewById<Button>(R.id.qr_error_import_gallery_btn)?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrGalleryDecode()
        }

        allowCameraBtn?.setOnClickListener {
            (activity as? MainActivity)?.launchers?.launchQrCameraPermission()
        }
        openSettingsBtn?.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
        }
        view.findViewById<Button>(R.id.qr_camera_retry_btn)?.setOnClickListener { checkPermissionState() }

        scanAnotherBtn?.setOnClickListener {
            isScanningPaused = false
            view.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text = "Point camera at a QR code"
            activeQrScanner?.resumeAnalysis()
            updateCameraUiState(CameraUiState.READY)
        }

        resultActionBtn?.setOnClickListener {
            currentScannedAction?.let { action -> executeAction(action) }
        }
    }

    private fun updateCameraUiState(state: CameraUiState) {
        currentUiState = state
        val isCameraReady = (state == CameraUiState.READY)
        val isResult = (state == CameraUiState.RESULT_PRESENTED)
        val isPerm = (state == CameraUiState.PERMISSION_REQUIRED || state == CameraUiState.PERMISSION_DENIED)
        val isError = (state == CameraUiState.ERROR)

        val view = binding.containerQrScan
        view.findViewById<View>(R.id.qr_scan_overlay)?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.qr_scan_guidance_text)?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.qr_torch_btn)?.visibility = if (isCameraReady && activeCamera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_qr_zoom_controls)?.visibility = if (isCameraReady) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.qr_result_card)?.visibility = if (isResult) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_camera_permission_denied)?.visibility = if (isPerm) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_camera_error)?.visibility = if (isError) View.VISIBLE else View.GONE

        view.findViewById<View>(R.id.container_scan_bottom_controls)?.visibility = if (isCameraReady || isResult) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.qr_scan_gallery_btn)?.visibility = if (isCameraReady) View.VISIBLE else View.GONE

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
            val torchBtn = binding.containerQrScan.findViewById<FloatingActionButton>(R.id.qr_torch_btn)
            torchBtn?.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
    }

    private fun setCameraZoom(ratio: Float) {
        val cam = activeCamera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val target = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(target)
        updateZoomButtonsUi(target)
    }

    private fun updateZoomButtonsUi(zoomRatio: Float) {
        val activeColor = 0xFFFFFFFF.toInt()
        val inactiveColor = 0xB3FFFFFF.toInt()
        val view = binding.containerQrScan
        view.findViewById<TextView>(R.id.btn_qr_zoom_1x)?.setTextColor(if (Math.abs(zoomRatio - 1.0f) < 0.35f) activeColor else inactiveColor)
        view.findViewById<TextView>(R.id.btn_qr_zoom_2x)?.setTextColor(if (Math.abs(zoomRatio - 2.0f) < 0.45f) activeColor else inactiveColor)
        view.findViewById<TextView>(R.id.btn_qr_zoom_3x)?.setTextColor(if (zoomRatio >= 2.65f) activeColor else inactiveColor)
    }

    private fun checkPermissionState() {
        val hasPerm = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPerm) {
            updateCameraUiState(CameraUiState.INITIALIZING)
            startCamera()
        } else {
            val isPermanent = hasRequestedPermissionOnce &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

            val view = binding.containerQrScan
            val permTitle = view.findViewById<TextView>(R.id.qr_camera_permission_title)
            val permDesc = view.findViewById<TextView>(R.id.qr_camera_permission_desc)
            val allowBtn = view.findViewById<Button>(R.id.qr_allow_camera_btn)
            val settingsBtn = view.findViewById<Button>(R.id.qr_open_settings_btn)

            if (isPermanent) {
                permTitle?.text = "Camera Permission Required"
                permDesc?.text = "Camera access was permanently denied. Please grant Camera permission in Settings to scan QR codes."
                allowBtn?.visibility = View.GONE
                settingsBtn?.visibility = View.VISIBLE
                updateCameraUiState(CameraUiState.PERMISSION_DENIED)
            } else {
                permTitle?.text = "Camera Permission Needed"
                permDesc?.text = "Allow camera access to aim at and scan QR codes in real time."
                allowBtn?.visibility = View.VISIBLE
                settingsBtn?.visibility = View.GONE
                updateCameraUiState(CameraUiState.PERMISSION_REQUIRED)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                binding.containerQrScan.findViewById<TextView>(R.id.qr_camera_error_text)?.text = "Camera failed to initialize. Try retrying or picking from gallery."
                updateCameraUiState(CameraUiState.ERROR)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val previewView = binding.containerQrScan.findViewById<PreviewView>(R.id.qr_camera_preview)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        val scanner = QrScanner(
            onZoomSuggestion = { zoomMultiplier ->
                activity.runOnUiThread {
                    binding.containerQrScan.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text = "Move closer or zoom in"
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
                activity.runOnUiThread {
                    if (isLow && !isTorchOn && !isScanningPaused) {
                        binding.containerQrScan.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text = "Too dark? Turn on flash"
                    } else if (!isScanningPaused && binding.containerQrScan.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text == "Too dark? Turn on flash") {
                        binding.containerQrScan.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text = "Point camera at a QR code"
                    }
                }
            }
        ) { raw ->
            activity.runOnUiThread {
                if (!isScanningPaused) {
                    handleScanResult(raw)
                }
            }
        }.also { activeQrScanner = it }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    scanner.analyze(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(
                activity,
                cameraSelector,
                preview,
                imageAnalysis
            )
            updateCameraUiState(CameraUiState.READY)
        } catch (e: Exception) {
            binding.containerQrScan.findViewById<TextView>(R.id.qr_camera_error_text)?.text = "Camera failed to initialize. Try retrying or picking from gallery."
            updateCameraUiState(CameraUiState.ERROR)
        }
    }

    private fun stopCamera() {
        setTorch(false)
        cameraProvider?.unbindAll()
        activeCamera = null
        activeQrScanner?.close()
        activeQrScanner = null
    }

    private fun handleScanResult(raw: String) {
        if (isScanningPaused) return
        isScanningPaused = true
        activeQrScanner?.pauseAnalysis()

        val action = PayloadParser.parse(raw)
        currentScannedAction = action
        isPasswordMasked = true

        val view = binding.containerQrScan
        val resultType = view.findViewById<TextView>(R.id.qr_result_type)
        val resultText = view.findViewById<TextView>(R.id.qr_result_text)
        val togglePwdBtn = view.findViewById<MaterialButton>(R.id.qr_result_toggle_pwd_btn)
        val resultActionBtn = view.findViewById<Button>(R.id.qr_result_action_btn)

        resultType?.text = action.javaClass.simpleName
        resultText?.text = formatActionSummary(action, maskWifiPassword = true)
        togglePwdBtn?.visibility = if (action is QrAction.Wifi && action.password.isNotBlank()) View.VISIBLE else View.GONE
        togglePwdBtn?.text = "Show"
        resultActionBtn?.text = formatActionCta(action)

        updateCameraUiState(CameraUiState.RESULT_PRESENTED)
    }

    private fun formatActionCta(action: QrAction): String = when (action) {
        is QrAction.Url -> "Open URL"
        is QrAction.Wifi -> "Connect to Wi-Fi"
        is QrAction.Contact -> "Add Contact"
        is QrAction.UpiPayment -> "Pay with UPI"
        is QrAction.Phone -> "Dial Phone"
        is QrAction.Sms -> "Send SMS"
        is QrAction.Email -> "Compose Email"
        is QrAction.Geo -> "Open Map"
        is QrAction.CalendarEvent -> "Add Event"
        is QrAction.OtpAuth -> "Add to Authenticator"
        is QrAction.Raw -> "Copy Content"
    }

    private fun executeAction(action: QrAction) {
        try {
            QrActionExecutor.execute(activity, action)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Unable to execute action: " + e.message, Snackbar.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 7. VIEWMODEL OBSERVATION
    // =========================================================================
    private fun observeViewModel() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    val view = binding.scrollQrGenerate

                    val previewImage = view.findViewById<ImageView>(R.id.qr_preview_image)
                    val previewEmptyState = view.findViewById<View>(R.id.qr_preview_empty_state)
                    val previewProgress = view.findViewById<View>(R.id.qr_preview_progress)
                    val previewAnimationBadge = view.findViewById<View>(R.id.qr_preview_animation_badge)
                    val animFrames = state.previewAnimatedFrames
                    val isScanValid = state.scanabilityReport?.isScanReady == true

                    if (animFrames.isNotEmpty()) {
                        previewAnimationBadge?.visibility = View.VISIBLE
                        previewImage.visibility = View.VISIBLE
                        previewEmptyState?.visibility = View.GONE
                        previewImage.contentDescription = "Animated QR code preview with " + animFrames.size + " frames for content: " + state.content + ". " + (if (isScanValid) "Verified scannable" else "Needs adjustment")
                        if (activePreviewFrames !== animFrames || previewAnimJob?.isActive != true) {
                            activePreviewFrames = animFrames
                            previewAnimJob?.cancel()
                            previewAnimJob = activity.lifecycleScope.launch {
                                var idx = 0
                                while (isActive && animFrames.isNotEmpty()) {
                                    val frame = animFrames[idx % animFrames.size]
                                    if (!frame.bitmap.isRecycled) {
                                        previewImage.setImageBitmap(frame.bitmap)
                                    }
                                    kotlinx.coroutines.delay(frame.durationMs.toLong().coerceIn(25L, 2000L))
                                    idx = (idx + 1) % animFrames.size
                                }
                            }
                        }
                    } else {
                        activePreviewFrames = null
                        previewAnimJob?.cancel()
                        previewAnimJob = null
                        previewAnimationBadge?.visibility = View.GONE
                        val staticBmp = state.bitmap
                        if (staticBmp != null && !staticBmp.isRecycled) {
                            previewImage.setImageBitmap(staticBmp)
                            previewImage.visibility = View.VISIBLE
                            previewEmptyState?.visibility = View.GONE
                            previewImage.contentDescription = "QR code preview for: " + state.content + ", style " + state.style.name + ". " + (if (isScanValid) "Verified scannable" else "Needs adjustment")
                        } else {
                            previewImage.setImageBitmap(null)
                            previewImage.visibility = View.GONE
                            previewEmptyState?.visibility = View.VISIBLE
                            previewImage.contentDescription = "Empty QR code preview. Enter content to generate."
                        }
                    }

                    previewProgress?.visibility = if (state.isRenderingPreview) View.VISIBLE else View.GONE

                    val hasContent = state.content.isNotBlank()
                    val isValidating = state.scanabilityReport == null && hasContent && state.errorMessage == null
                    val canExport = hasContent && !state.isRenderingPreview && !state.isExporting
                    val styleDef = com.veilframe.app.qr.registry.QrStyleRegistry.get(state.style)
                    val hasSupportedSourceAnim = styleDef.supportsAnimatedSource && state.animatedFrames.size > 1
                    val hasSupportedLogoAnim = styleDef.supportsAnimatedLogo && state.logoAnimatedFrames.size > 1
                    val hasAnimationSource = hasSupportedSourceAnim || hasSupportedLogoAnim

                    val exportStatusBanner = view.findViewById<TextView>(R.id.qr_export_status_banner)
                    val neutralBannerColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    if (!hasContent) {
                        exportStatusBanner?.visibility = View.GONE
                    } else if (state.isExporting) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Exporting file..."
                        exportStatusBanner?.setTextColor(neutralBannerColor)
                    } else if (state.errorMessage != null) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Render failed — " + state.errorMessage
                        exportStatusBanner?.setTextColor(0xFFDC2626.toInt())
                    } else if (isValidating || state.isRenderingPreview) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Validating… Checking scan reliability before export"
                        exportStatusBanner?.setTextColor(neutralBannerColor)
                    } else if (isScanValid) {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Preview verified scannable (≤512px) · High-resolution export verified at render time"
                        exportStatusBanner?.setTextColor(0xFF16A34A.toInt())
                    } else {
                        exportStatusBanner?.visibility = View.VISIBLE
                        exportStatusBanner?.text = "Needs adjustment — Preview unverified; adjust contrast or tap Auto-Repair before export"
                        exportStatusBanner?.setTextColor(0xFFD97706.toInt())
                    }

                    val resHint = view.findViewById<TextView>(R.id.qr_resolution_hint)
                    resHint?.text = "Preview rendered at ≤512px for responsive editing · Final export generates at " + state.outputSize + "px with strict decoder verification"

                    view.findViewById<MaterialButton>(R.id.qr_save_btn)?.isEnabled = canExport
                    view.findViewById<MaterialButton>(R.id.qr_save_svg_btn)?.isEnabled = canExport
                    view.findViewById<MaterialButton>(R.id.qr_save_jpeg_btn)?.isEnabled = canExport
                    view.findViewById<MaterialButton>(R.id.qr_save_gif_btn)?.isEnabled = canExport && hasAnimationSource
                    view.findViewById<MaterialButton>(R.id.qr_save_video_btn)?.isEnabled = canExport && hasAnimationSource
                    view.findViewById<MaterialButton>(R.id.qr_save_animated_svg_btn)?.isEnabled = canExport && hasAnimationSource
                    view.findViewById<MaterialButton>(R.id.qr_share_btn)?.isEnabled = canExport

                    val containerAnimatedExport = view.findViewById<LinearLayout>(R.id.container_animated_export)
                    val animatedExportLabel = view.findViewById<TextView>(R.id.qr_animated_export_label)
                    val animationStatusBanner = view.findViewById<TextView>(R.id.qr_animation_status_banner)

                    containerAnimatedExport?.visibility = if (hasAnimationSource) View.VISIBLE else View.GONE
                    animatedExportLabel?.visibility = if (hasAnimationSource) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_btn_clear_animation)?.visibility =
                        if (state.animatedFrames.size > 1 || state.logoAnimatedFrames.size > 1) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_remove_logo_btn)?.visibility = if (state.logo != null || state.logoAnimatedFrames.isNotEmpty()) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_remove_source_img_btn)?.visibility = if (state.sourceImage != null || state.animatedFrames.isNotEmpty()) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_remove_bg_btn)?.visibility = if (state.backgroundImage != null) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_backdrop_image_remove_btn)?.visibility = if (state.backdropImage != null) View.VISIBLE else View.GONE
                    view.findViewById<View>(R.id.qr_resample_backdrop_img_remove_btn)?.visibility = if (state.resampleBackdropImage != null) View.VISIBLE else View.GONE

                    if (hasAnimationSource) {
                        val frameCount = maxOf(
                            if (hasSupportedSourceAnim) state.animatedFrames.size else 0,
                            if (hasSupportedLogoAnim) state.logoAnimatedFrames.size else 0
                        )
                        val previewCount = state.previewAnimatedFrames.size
                        animatedExportLabel?.text = "Animation source: $frameCount frames loaded (max ${state.maxAnimationFrames})"
                        animationStatusBanner?.text = "Animation playing: $frameCount loaded frames ($previewCount frames in continuous loop)"
                        animationStatusBanner?.setTextColor(0xFF16A34A.toInt())
                    } else if (!styleDef.supportsAnimatedSource && state.animatedFrames.size > 1) {
                        animationStatusBanner?.text = "${styleDef.displayName} does not support animated Source/Backdrop video. Switch to Image Backdrop, Image Fill, or Image Resample — or animate your center Logo instead."
                        animationStatusBanner?.setTextColor(0xFFD97706.toInt())
                    } else if (!styleDef.supportsAnimatedSource) {
                        animationStatusBanner?.text = "${styleDef.displayName} only supports an animated center Logo (GIF/WebP/video). Choose Image Backdrop, Image Fill, or Image Resample for full-canvas video motion."
                        animationStatusBanner?.setTextColor(neutralBannerColor)
                    } else {
                        animationStatusBanner?.text = "No animation loaded. Import a GIF, WebP, or video (up to ${state.maxAnimationFrames} frames) for continuous preview & animated export."
                        animationStatusBanner?.setTextColor(neutralBannerColor)
                    }

                    view.findViewById<LinearLayout>(R.id.container_advanced_settings_body)?.visibility = if (state.isAdvancedExpanded) View.VISIBLE else View.GONE
                    view.findViewById<ImageView>(R.id.qr_icon_toggle_advanced)?.setImageResource(if (state.isAdvancedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)

                    view.findViewById<LinearLayout>(R.id.container_scanability_tech_details)?.visibility = if (state.isScanDetailsExpanded) View.VISIBLE else View.GONE
                    view.findViewById<MaterialButton>(R.id.qr_scanability_details_toggle)?.text = if (state.isScanDetailsExpanded) "Hide" else "Details"

                    val isCustom = state.quietZoneChoice != null
                    val eff = vm.resolveEffectiveQuietZone()
                    val quietZoneLabel = view.findViewById<TextView>(R.id.qr_quiet_zone_label)
                    val quietZoneSlider = view.findViewById<Slider>(R.id.qr_quiet_zone_slider)
                    quietZoneLabel?.text = if (isCustom) "Quiet Zone Margin: " + state.quietZoneChoice + " modules (Custom)" else "Quiet Zone Margin: " + eff + " modules (Default)"
                    if (!isCustom && quietZoneSlider?.value?.toInt() != eff) {
                        quietZoneSlider?.value = eff.toFloat().coerceIn(0.0f, 10.0f)
                    }

                    applyStyleCapabilities(view, state, styleDef)
                    syncWidgetsFromState(view, state)

                    // Scanability Card
                    val scanabilityCard = view.findViewById<MaterialCardView>(R.id.qr_scanability_card)
                    val scanabilityIcon = view.findViewById<ImageView>(R.id.qr_scanability_icon)
                    val scanabilityStatus = view.findViewById<TextView>(R.id.qr_scanability_status)
                    val scanabilityDetails = view.findViewById<TextView>(R.id.qr_scanability_details)
                    val scanabilityTechText = view.findViewById<TextView>(R.id.qr_scanability_tech_text)
                    val autoRepairBtn = view.findViewById<MaterialButton>(R.id.qr_auto_repair_btn)

                    state.scanabilityReport?.let { report ->
                        if (report.isScanReady) {
                            scanabilityCard?.setCardBackgroundColor(0x1816A34A)
                            scanabilityCard?.strokeColor = 0x4016A34A
                            scanabilityIcon?.setImageResource(R.drawable.ic_check_circle)
                            scanabilityIcon?.setColorFilter(0xFF16A34A.toInt())
                            scanabilityStatus.text = "Scanability: Verified"
                            scanabilityStatus.setTextColor(0xFF16A34A.toInt())
                            scanabilityDetails.text = ""
                            scanabilityDetails.visibility = View.GONE
                            val id = report.decodeResult.decoderId
                            val engine = when {
                                id.contains("wechat", ignoreCase = true) -> "WeChatQR"
                                id.contains("ml kit", ignoreCase = true) || id.contains("mlkit", ignoreCase = true) -> "Google ML Kit"
                                id.isNotBlank() -> id
                                else -> "WeChatQR"
                            }
                            val qz = state.design?.quietZoneModules ?: if (state.style == QrStyle.IMAGE || state.style == QrStyle.IMAGE_RESAMPLE || state.style == QrStyle.IMAGE_FILL) 1 else 4
                            val qzSuffix = if (qz == 1) "" else "s"
                            scanabilityTechText?.text = "Engine: " + engine + " | Latency: " + report.decodeResult.latencyMs + "ms | Quiet zone: " + qz + " module" + qzSuffix
                            autoRepairBtn.visibility = View.GONE
                            if (lastAnnouncedScanReady != true) {
                                lastAnnouncedScanReady = true
                                view.announceForAccessibility("QR code passed on-device scanability validation. Ready for export.")
                            }
                        } else {
                            scanabilityCard?.setCardBackgroundColor(0x18D97706)
                            scanabilityCard?.strokeColor = 0x50D97706
                            scanabilityIcon?.setImageResource(R.drawable.ic_info_outline)
                            scanabilityIcon?.setColorFilter(0xFFD97706.toInt())
                            scanabilityStatus.text = "Scanability: Unverified"
                            scanabilityStatus.setTextColor(0xFFD97706.toInt())
                            scanabilityDetails.visibility = View.VISIBLE
                            scanabilityDetails.text = "Preview could not be verified on-device. Export requires strict verification; adjust contrast or try Auto-Repair."
                            val errorMsg = report.decodeResult.error ?: "Decoder could not resolve patterns"
                            val warningText = if (report.warnings.isNotEmpty()) " | Warnings: " + report.warnings.joinToString("; ") else ""
                            scanabilityTechText?.text = "Diagnostic: " + errorMsg + warningText
                            autoRepairBtn.visibility = if (report.repairSuggestions.isNotEmpty()) View.VISIBLE else View.GONE
                            if (lastAnnouncedScanReady != false) {
                                lastAnnouncedScanReady = false
                                val warn = report.warnings.firstOrNull() ?: "Contrast or patterns unverified"
                                view.announceForAccessibility("QR code needs adjustment: " + warn + ". Auto-Repair is available.")
                            }
                        }
                        val detailsText = if (scanabilityDetails.text.isNullOrBlank()) "" else " " + scanabilityDetails.text
                        scanabilityCard?.contentDescription = "Scanability status: " + scanabilityStatus.text + "." + detailsText
                    } ?: run {
                        if (scanabilityCard != null) {
                            scanabilityCard.setCardBackgroundColor(
                                MaterialColors.getColor(scanabilityCard, com.google.android.material.R.attr.colorSurfaceContainer)
                            )
                            scanabilityCard.strokeColor = MaterialColors.getColor(
                                scanabilityCard,
                                com.google.android.material.R.attr.colorOutlineVariant
                            )
                        }
                        scanabilityIcon?.setImageResource(R.drawable.ic_info_outline)
                        scanabilityIcon?.setColorFilter(
                            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
                        )
                        if (hasContent) {
                            scanabilityStatus.text = "Validating..."
                            scanabilityDetails.visibility = View.VISIBLE
                            scanabilityDetails.text = "Running on-device barcode validator..."
                            scanabilityTechText?.text = "Validating barcode scanability on device..."
                        } else {
                            scanabilityStatus.text = "Ready to generate"
                            scanabilityDetails.visibility = View.GONE
                            scanabilityTechText?.text = "On-device scanability verification activates automatically once content is entered."
                        }
                        scanabilityStatus.setTextColor(
                            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
                        )
                        autoRepairBtn.visibility = View.GONE
                        scanabilityCard?.contentDescription = "Scanability status: " + scanabilityStatus.text
                    }

                    // Progress overlay
                    binding.qrLoadingProgress.visibility = if (state.isExporting) View.VISIBLE else View.GONE

                    state.repairNotice?.let { notice ->
                        Snackbar.make(binding.root, "Auto-Repair: " + notice, Snackbar.LENGTH_LONG)
                            .setAction("Undo") { vm.undoAutoRepair() }
                            .show()
                        vm.clearRepairNotice()
                    }

                    state.saveResult?.let { msg ->
                        val snackbar = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
                        val uriToView = state.lastSavedUri
                        if (uriToView != null) {
                            snackbar.setAction("View") {
                                try {
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uriToView, activity.contentResolver.getType(uriToView) ?: "image/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    activity.startActivity(viewIntent)
                                } catch (_: Exception) {
                                    Toast.makeText(activity, "No application found to view file", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        snackbar.show()
                        vm.clearSaveResult()
                    }

                    state.errorMessage?.let { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // =========================================================================
    // 8. TOOL WORK STATE CONTRACT IMPLEMENTATION
    // =========================================================================
    override fun hasPickedMedia(): Boolean {
        val s = vm.state.value
        return s.logo != null ||
            s.backgroundImage != null ||
            s.sourceImage != null ||
            s.backdropImage != null ||
            s.resampleBackdropImage != null ||
            s.animatedFrames.isNotEmpty() ||
            s.logoAnimatedFrames.isNotEmpty()
    }

    override fun hasUnsavedWork(): Boolean {
        val s = vm.state.value
        val hasCustom = s.content.isNotBlank() || hasPickedMedia()
        return hasCustom && s.saveResult == null
    }

    override val isProcessing: Boolean
        get() = vm.state.value.isExporting

    override fun discardAndReleaseAllResources() {
        previewAnimJob?.cancel()
        previewAnimJob = null
        activePreviewFrames = null
        lastAnimatedSourceUri = null
        lastAnimatedLogoUri = null
        lastAnnouncedScanReady = null
        binding.scrollQrGenerate.findViewById<ImageView>(R.id.qr_preview_image)?.setImageBitmap(null)
        vm.terminateSession()
        resetFormAndUiToCleanSession()
        stopCamera()
    }

    private fun resetFormAndUiToCleanSession() {
        val view = binding.scrollQrGenerate
        val previewImage = view.findViewById<ImageView>(R.id.qr_preview_image)
        val previewEmptyState = view.findViewById<View>(R.id.qr_preview_empty_state)
        isProgrammaticUiUpdate = true
        try {
            val editTextIds = intArrayOf(
                R.id.qr_content_input,
                R.id.qr_wifi_ssid,
                R.id.qr_wifi_password,
                R.id.qr_vcard_first_name,
                R.id.qr_vcard_last_name,
                R.id.qr_vcard_phone,
                R.id.qr_vcard_email,
                R.id.qr_vcard_org,
                R.id.qr_vcard_url,
                R.id.qr_email_recipient,
                R.id.qr_email_subject,
                R.id.qr_email_body,
                R.id.qr_sms_phone,
                R.id.qr_sms_body,
                R.id.qr_upi_vpa,
                R.id.qr_upi_amount,
                R.id.qr_upi_payee_name,
                R.id.qr_upi_note
            )
            for (id in editTextIds) {
                view.findViewById<EditText>(id)?.setText("")
            }
            view.findViewById<EditText>(R.id.qr_random_seed_input)?.setText("42")
            view.findViewById<Spinner>(R.id.qr_wifi_security_spinner)?.setSelection(0)
            view.findViewById<CheckBox>(R.id.qr_wifi_hidden_check)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_timing_only_white_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_align_only_white_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_asymmetric_qz_switch)?.isChecked = false
            view.findViewById<View>(R.id.container_asymmetric_qz)?.visibility = View.GONE
            view.findViewById<Slider>(R.id.qr_quiet_zone_slider)?.isEnabled = true
            view.findViewById<MaterialSwitch>(R.id.qr_bubble_ambient_switch)?.isChecked = true
            view.findViewById<MaterialSwitch>(R.id.qr_line_accent_rings_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_line_circuit_bridges_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_resample_backdrop_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_image_allow_transparent_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_resample_rng_switch)?.isChecked = false
            view.findViewById<MaterialSwitch>(R.id.qr_resample_dither_switch)?.isChecked = true
            view.findViewById<Slider>(R.id.qr_resample_min_version_slider)?.value = 6f
            view.findViewById<TextView>(R.id.qr_resample_min_version_label)?.text = "V6 (41x41)"
            view.findViewById<Slider>(R.id.qr_upi_amount_slider)?.value = 0f

            view.findViewById<ChipGroup>(R.id.qr_preset_chip_group)?.check(R.id.chip_preset_text)
            (view.findViewById<View>(R.id.qr_preset_chip_group)?.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
            (view.findViewById<View>(R.id.qr_style_chip_group)?.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
            (view.findViewById<View>(R.id.qr_line_direction_group)?.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
            binding.scrollQrGenerate.scrollTo(0, 0)
            view.findViewById<View>(R.id.container_preset_text)?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.container_preset_wifi)?.visibility = View.GONE
            view.findViewById<View>(R.id.container_preset_vcard)?.visibility = View.GONE
            view.findViewById<View>(R.id.container_preset_email)?.visibility = View.GONE
            view.findViewById<View>(R.id.container_preset_sms)?.visibility = View.GONE
            view.findViewById<View>(R.id.container_preset_upi)?.visibility = View.GONE

            previewImage?.setImageBitmap(null)
            previewImage?.visibility = View.GONE
            previewEmptyState?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.qr_preview_animation_badge)?.visibility = View.GONE
            view.scrollTo(0, 0)
            syncWidgetsFromState(view, vm.state.value)
        } finally {
            isProgrammaticUiUpdate = false
        }
        if (currentMode != Mode.GENERATE) {
            currentMode = Mode.GENERATE
            binding.scrollQrGenerate.visibility = View.VISIBLE
            binding.containerQrScan.visibility = View.GONE
            binding.toggleQrMode.check(R.id.btnModeGenerate)
        }
    }

    private fun applyStyleCapabilities(
        view: View,
        state: QrStudioViewModel.UiState,
        def: com.veilframe.app.qr.registry.QrStyleDefinition
    ) {
        val showD25 = (state.style == QrStyle.D25)
        val showLine = (state.style == QrStyle.LINE)
        val showDsj = (state.style == QrStyle.DSJ)
        val showRandomRect = (state.style == QrStyle.RANDOM_RECTANGLE)
        val showBubble = (state.style == QrStyle.BUBBLE)
        val showFunction = (state.style == QrStyle.FUNCTION)
        val showStyleFunction = (state.style == QrStyle.STYLE_FUNCTION)
        val showConnectedOrganic = (state.style == QrStyle.CONNECTED_ORGANIC)
        val showImageFill = (state.style == QrStyle.IMAGE_FILL)
        val showPhotoRequired = vm.isSourcePhotoRequired(state.style, state.sourceImage) ||
            (state.style == QrStyle.IMAGE_FILL && state.sourceImage == null && state.animatedFrames.isEmpty())

        view.findViewById<LinearLayout>(R.id.container_style_d25)?.visibility = if (showD25) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_line)?.visibility = if (showLine) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_dsj)?.visibility = if (showDsj) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_random_rect)?.visibility = if (showRandomRect) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_bubble)?.visibility = if (showBubble) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_function)?.visibility = if (showFunction) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_style_function)?.visibility = if (showStyleFunction) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_connected_organic)?.visibility = if (showConnectedOrganic) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_style_image_fill)?.visibility = if (showImageFill) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_image_backdrop_required)?.visibility = if (showPhotoRequired) View.VISIBLE else View.GONE

        val hasAnyStyleSetting = showD25 || showLine || showDsj || showRandomRect || showBubble || showFunction || showStyleFunction || showConnectedOrganic || showImageFill || showPhotoRequired
        view.findViewById<MaterialCardView>(R.id.card_style_settings)?.visibility = if (hasAnyStyleSetting) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.qr_style_settings_title)?.text = "${def.displayName} Parameters"

        // Hardened Section 4 Color & Media gating per active style
        view.findViewById<View>(R.id.qr_fg_color_btn)?.visibility = if (def.supportsForegroundColor) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.qr_bg_color_btn)?.visibility = if (def.supportsBackgroundColor) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_palette_colors_row)?.visibility =
            if (def.supportsForegroundColor || def.supportsBackgroundColor) View.VISIBLE else View.GONE

        val sourceImgBtn = view.findViewById<MaterialButton>(R.id.qr_source_image_btn)
        sourceImgBtn?.alpha = 1.0f
        sourceImgBtn?.text = "Source"
        view.findViewById<View>(R.id.container_media_tile_source)?.visibility = if (def.supportsSourceImage) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_media_tile_bg_photo)?.visibility = if (def.supportsBackgroundImage) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_media_tile_backdrop)?.visibility = if (def.supportsBackdropCard) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_media_grid_row2)?.visibility =
            if (def.supportsBackgroundImage || def.supportsBackdropCard) View.VISIBLE else View.GONE

        // Hardened Advanced Geometry gating per active style
        view.findViewById<View>(R.id.container_geom_data_shape)?.visibility = if (def.supportsDataShape) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_geom_data_scale)?.visibility = if (def.supportsDataScale) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_geom_finder)?.visibility = if (def.supportsFinderStyle) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_geom_finder_colors)?.visibility = if (def.supportsFinderColors) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_geom_timing)?.visibility = if (def.supportsTimingAndAlignment) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.container_geom_alignment)?.visibility = if (def.supportsTimingAndAlignment) View.VISIBLE else View.GONE

        // Hardened Advanced inspector sections per active style
        view.findViewById<View>(R.id.card_gradient_effects)?.visibility = if (def.supportsGradients) View.VISIBLE else View.GONE
        val showSourceCard = def.supportsSourceImage &&
            ((state.sourceImage != null) || (state.style == QrStyle.IMAGE) || (state.style == QrStyle.IMAGE_RESAMPLE))
        view.findViewById<View>(R.id.card_source_image_controls)?.visibility = if (showSourceCard) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_image_specific_controls)?.visibility =
            if (state.style == QrStyle.IMAGE) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.container_resample_specific_controls)?.visibility =
            if (state.style == QrStyle.IMAGE_RESAMPLE) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.card_bg_image_controls)?.visibility =
            if (def.supportsBackgroundImage && state.backgroundImage != null) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.card_backdrop_controls)?.visibility =
            if (def.supportsBackdropCard) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.card_logo_controls)?.visibility =
            if (state.logo != null || state.logoAnimatedFrames.isNotEmpty()) View.VISIBLE else View.GONE

        // Motion capabilities gating
        view.findViewById<View>(R.id.qr_btn_pick_animated_watermark)?.visibility =
            if (def.supportsAnimatedSource) View.VISIBLE else View.GONE
    }

    private fun syncWidgetsFromState(view: View, state: QrStudioViewModel.UiState) {
        if (isProgrammaticUiUpdate) {
            applySpinnerAndChipSync(view, state)
            return
        }
        isProgrammaticUiUpdate = true
        try {
            applySpinnerAndChipSync(view, state)
        } finally {
            isProgrammaticUiUpdate = false
        }
    }

    private fun applySpinnerAndChipSync(view: View, state: QrStudioViewModel.UiState) {
        val styleChipId = when (state.style) {
            QrStyle.BASIC -> R.id.chip_style_basic
            QrStyle.BUBBLE -> R.id.chip_style_bubble
            QrStyle.D25 -> R.id.chip_style_d25
            QrStyle.DSJ -> R.id.chip_style_dsj
            QrStyle.LINE -> R.id.chip_style_line
            QrStyle.RANDOM_RECTANGLE -> R.id.chip_style_random_rect
            QrStyle.IMAGE -> R.id.chip_style_image
            QrStyle.IMAGE_FILL -> R.id.chip_style_image_fill
            QrStyle.IMAGE_RESAMPLE -> R.id.chip_style_image_resample
            QrStyle.FUNCTION -> R.id.chip_style_func
            QrStyle.STYLE_FUNCTION -> R.id.chip_style_style_func
            QrStyle.CONNECTED_ORGANIC -> R.id.chip_style_composite
        }
        view.findViewById<ChipGroup>(R.id.qr_style_chip_group)?.let { group ->
            if (group.checkedChipId != styleChipId) group.check(styleChipId)
        }

        val finderStyles = listOf(
            FinderStyle.CLASSIC, FinderStyle.ROUNDED, FinderStyle.CIRCLE,
            FinderStyle.SOFT, FinderStyle.FRAME, FinderStyle.PLANETS, FinderStyle.DSJ
        )
        view.findViewById<Spinner>(R.id.qr_finder_shape_spinner)?.let { sp ->
            val targetIdx = finderStyles.indexOf(state.finderStyle).coerceAtLeast(0)
            if (sp.selectedItemPosition != targetIdx) sp.setSelection(targetIdx, false)
        }

        val supportedShapes = listOf(
            ModuleShape.SQUARE, ModuleShape.ROUNDED, ModuleShape.CIRCLE, ModuleShape.DOT,
            ModuleShape.PILL, ModuleShape.DIAMOND, ModuleShape.HEX, ModuleShape.SQUIRCLE, ModuleShape.STAR
        )
        val dataShapeIdx = supportedShapes.indexOf(state.dataShape).coerceAtLeast(0)
        view.findViewById<Spinner>(R.id.qr_data_shape_spinner)?.let { sp ->
            if (sp.selectedItemPosition != dataShapeIdx) sp.setSelection(dataShapeIdx, false)
        }
        val timingShapeIdx = supportedShapes.indexOf(state.timingShape).coerceAtLeast(0)
        view.findViewById<Spinner>(R.id.qr_timing_shape_spinner)?.let { sp ->
            if (sp.selectedItemPosition != timingShapeIdx) sp.setSelection(timingShapeIdx, false)
        }
        val alignShapeIdx = supportedShapes.indexOf(state.alignShape).coerceAtLeast(0)
        view.findViewById<Spinner>(R.id.qr_align_shape_spinner)?.let { sp ->
            if (sp.selectedItemPosition != alignShapeIdx) sp.setSelection(alignShapeIdx, false)
        }

        val gradTypes = listOf(GradientType.NONE, GradientType.LINEAR, GradientType.RADIAL, GradientType.SWEEP)
        view.findViewById<Spinner>(R.id.qr_gradient_type_spinner)?.let { sp ->
            val idx = gradTypes.indexOf(state.gradientType).coerceAtLeast(0)
            if (sp.selectedItemPosition != idx) sp.setSelection(idx, false)
        }

        val resValues = intArrayOf(256, 512, 1024, 2048, 4096)
        view.findViewById<Spinner>(R.id.qr_resolution_spinner)?.let { sp ->
            val idx = resValues.indexOf(state.outputSize).coerceAtLeast(0)
            if (sp.selectedItemPosition != idx) sp.setSelection(idx, false)
        }

        val ecValues = listOf(
            ErrorCorrectionChoice.AUTO, ErrorCorrectionChoice.L,
            ErrorCorrectionChoice.M, ErrorCorrectionChoice.Q, ErrorCorrectionChoice.H
        )
        view.findViewById<Spinner>(R.id.qr_ec_spinner)?.let { sp ->
            val idx = ecValues.indexOf(state.ecChoice).coerceAtLeast(0)
            if (sp.selectedItemPosition != idx) sp.setSelection(idx, false)
        }

        val funcTypes = FunctionType.values()
        view.findViewById<Spinner>(R.id.qr_style_func_type_spinner)?.let { sp ->
            val idx = funcTypes.indexOf(state.paramFunctionType).coerceAtLeast(0)
            if (sp.selectedItemPosition != idx) sp.setSelection(idx, false)
        }

        val lineDirChip = when (state.lineDirection) {
            LineDirection.HORIZONTAL -> R.id.chip_line_dir_x
            LineDirection.VERTICAL -> R.id.chip_line_dir_y
            LineDirection.CROSS -> R.id.chip_line_dir_cross
            LineDirection.LOOPBACK, LineDirection.LOOP -> R.id.chip_line_dir_loopback
            LineDirection.TOP_LEFT_TO_BOTTOM_RIGHT, LineDirection.DIAGONAL_FORWARD -> R.id.chip_line_dir_tl_br
            LineDirection.TOP_RIGHT_TO_BOTTOM_LEFT, LineDirection.DIAGONAL_BACKWARD -> R.id.chip_line_dir_tr_bl
            LineDirection.X -> R.id.chip_line_dir_x_pattern
        }
        view.findViewById<ChipGroup>(R.id.qr_line_direction_group)?.let { grp ->
            if (grp.checkedChipId != lineDirChip) grp.check(lineDirChip)
        }
        val lineVariantChip = if (state.lineVariant == LineVariant.CIRCUIT) R.id.chip_line_variant_circuit else R.id.chip_line_variant_standard
        view.findViewById<ChipGroup>(R.id.qr_line_variant_group)?.let { grp ->
            if (grp.checkedChipId != lineVariantChip) grp.check(lineVariantChip)
        }
        val funcTypeChip = if (state.veilFunctionType == VeilFunctionType.CIRCLE) R.id.chip_func_circle else R.id.chip_func_fade
        view.findViewById<ChipGroup>(R.id.qr_function_type_group)?.let { grp ->
            if (grp.checkedChipId != funcTypeChip) grp.check(funcTypeChip)
        }
        val funcDataChip = if (state.veilFunctionDataStyle == VeilFunctionDataStyle.RECTANGLE) R.id.chip_func_data_rect else R.id.chip_func_data_round
        view.findViewById<ChipGroup>(R.id.qr_function_data_style_group)?.let { grp ->
            if (grp.checkedChipId != funcDataChip) grp.check(funcDataChip)
        }

        view.findViewById<Slider>(R.id.qr_d25_depth_slider)?.setSnappedValue(state.d25Depth)
        view.findViewById<TextView>(R.id.qr_d25_depth_label)?.text = String.format(Locale.US, "Body 3D Depth: %.2fx", state.d25Depth)
        view.findViewById<Slider>(R.id.qr_d25_pos_depth_slider)?.setSnappedValue(state.d25PositionDepth)
        view.findViewById<TextView>(R.id.qr_d25_pos_depth_label)?.text = String.format(Locale.US, "Finder 3D Depth: %.2fx", state.d25PositionDepth)
        view.findViewById<Slider>(R.id.qr_d25_angle_slider)?.setSnappedValue(state.d25Angle)
        view.findViewById<TextView>(R.id.qr_d25_angle_label)?.text = String.format(Locale.US, "Projection Angle: %.0f°", state.d25Angle)

        view.findViewById<Slider>(R.id.qr_line_thickness_slider)?.setSnappedValue(state.lineThickness)
        view.findViewById<TextView>(R.id.qr_line_thickness_label)?.text = String.format(Locale.US, "Line Thickness: %.2f", state.lineThickness)
        view.findViewById<MaterialSwitch>(R.id.qr_line_accent_rings_switch)?.let { sw ->
            if (sw.isChecked != state.lineAccentRings) sw.isChecked = state.lineAccentRings
        }
        view.findViewById<MaterialSwitch>(R.id.qr_line_circuit_bridges_switch)?.let { sw ->
            if (sw.isChecked != state.lineCircuitBridges) sw.isChecked = state.lineCircuitBridges
        }

        view.findViewById<Slider>(R.id.qr_data_scale_slider)?.setSnappedValue(state.dataScale)
        view.findViewById<TextView>(R.id.qr_data_scale_label)?.text = String.format(Locale.US, "Data Module Scale: %d%%", (state.dataScale * 100).toInt())

        view.findViewById<Slider>(R.id.qr_image_data_scale_slider)?.setSnappedValue(state.imageDataScale * 100f)
        view.findViewById<TextView>(R.id.qr_image_data_scale_label)?.text = String.format(Locale.US, "Data Module Scale: %d%%", (state.imageDataScale * 100).toInt())

        view.findViewById<Slider>(R.id.qr_anim_max_frames_slider)?.setSnappedValue(state.maxAnimationFrames.toFloat())
        view.findViewById<TextView>(R.id.qr_anim_max_frames_label)?.text = "${state.maxAnimationFrames} frames"

        view.findViewById<MaterialSwitch>(R.id.qr_resample_dither_switch)?.let { sw ->
            val checked = state.resampleDitherMode == com.veilframe.app.qr.model.ResampleDitherMode.FLOYD_STEINBERG
            if (sw.isChecked != checked) sw.isChecked = checked
        }
        val minVer = state.resampleMinVersion.coerceIn(1, 10)
        view.findViewById<Slider>(R.id.qr_resample_min_version_slider)?.setSnappedValue(minVer.toFloat())
        val minMods = minVer * 4 + 17
        view.findViewById<TextView>(R.id.qr_resample_min_version_label)?.text = "V$minVer (${minMods}x${minMods})"
    }

    private fun bindInfoButtons(view: View) {
        val topics = mapOf(
            R.id.qr_info_content to ("Payload Content" to "Choose the type of data encoded inside the QR code: URL/Text links, Wi-Fi auto-connect credentials, Contact vCard, Email, SMS, or UPI payment links."),
            R.id.qr_info_style to ("Visual Style" to "Each visual engine draws QR modules differently and automatically applies its matching default finder eye and geometry. Controls that a style does not support are hidden automatically."),
            R.id.qr_info_color_media to ("Palette & Artwork" to "Foreground/Background set base contrast colors (keep high contrast for reliable scanning). Optional Logo places an icon in the center; Image styles also accept Source/Backdrop artwork."),
            R.id.qr_info_fg_bg to ("Foreground & Background" to "Foreground colors the dark encoded blocks; Background fills the light canvas behind them. Avoid low-contrast pastel pairs unless testing with the live verifier."),
            R.id.qr_info_logo_source to ("Logo & Source Photo" to "Logo overlays a badge at the center using Error Correction recovery. Source Photo is the artwork blended into Image Backdrop, Image Fill, or Image Resample QR codes."),
            R.id.qr_info_bg_backdrop to ("BG Photo & Backdrop Frame" to "BG Photo draws an optional tinted background layer behind Basic or Image styles. Backdrop sets outer frame fill color, corner rounding, and backdrop artwork."),
            R.id.qr_info_geometry to ("Matrix & Geometry" to "Fine-tune individual QR structural parts: data dot shapes, finder eye corners, timing dashed tracks, alignment targets, quiet zone margin, and error correction tolerance."),
            R.id.qr_info_data_shape to ("Data Module Shape" to "Sets the geometry of each encoded data bit (Square, Rounded, Circle, Diamond, Hex, etc.). Keep shape crisp for dense QR payloads."),
            R.id.qr_info_data_scale to ("Data Scale" to "Shrinks or enlarges each data module inside its grid cell (10%–100%). Smaller values reveal more background artwork, while higher values improve camera scan reliability."),
            R.id.qr_info_finder to ("Finder Eyes" to "Finder patterns are the three large corner squares scanners use to lock orientation. Each style chooses its best default (Circle for Bubble, Planets for Line/Circuit, DSJ for DSJ Cross)."),
            R.id.qr_info_timing to ("Timing Track" to "Alternating dark/light pulses connecting the corner finders. Used by decoders to measure module grid spacing."),
            R.id.qr_info_alignment to ("Alignment Targets" to "Small anchor boxes inside larger QR versions that correct perspective skew on curved surfaces."),
            R.id.qr_info_quiet_zone to ("Quiet Zone Margin" to "Blank border padding around the QR matrix. Standard scanners need 2–4 modules on bright cards, or 1+ module in minimal artistic layouts."),
            R.id.qr_info_ec to ("Error Correction" to "Adds redundant recovery codes (Low 7% to High 30%). Higher correction allows bigger center logos and artistic overlays at the cost of a slightly denser grid."),
            R.id.qr_info_gradients to ("Module Gradients" to "Applies Linear, Radial, or Sweep color transitions across foreground modules in supported styles (Classic Vector and Style Function)."),
            R.id.qr_info_style_function_wave to ("Parametric Wave Type" to "Shapes how spatial modulation sweeps across modules: Wave, Radial, Ripple (concentric rings), Value Noise, Spiral, Checker, Organic blobs, or deterministic Random."),
            R.id.qr_info_style_function_amp to ("Wave Amplitude" to "Controls how strongly the parametric wave varies module size, corner rounding, and opacity between wave peaks and troughs."),
            R.id.qr_info_style_function_freq to ("Wave Frequency" to "Controls how many wave cycles or ripples fit across the QR matrix."),
            R.id.qr_info_source_media to ("Watermark Calibration" to "Adjusts how Source photos and videos blend with QR bits—scale mode, opacity, contrast, exposure, color strategy, and halftoning seed."),
            R.id.qr_info_source_opacity to ("Source Opacity" to "Controls how strongly the Source artwork blends into the QR composition."),
            R.id.qr_info_resample_dither to ("Floyd-Steinberg Halftone" to "Uses two-pass center-error-diffused Floyd-Steinberg dithering (like dithered-qr-codes) to smoothly shade faces and objects instead of grain noise."),
            R.id.qr_info_resample_density to ("Detail Grid (Min Version)" to "Expands even short text to a finer QR grid (V6 = 41x41 modules = 123x123 subpixels) with zero center alignment clutter so small subjects look sharp."),
            R.id.qr_info_d25_depth to ("2.5D Extrusion Depth" to "Controls how tall each 3D cube rises. Moderate heights (0.3–0.5) keep the 3D effect crisp while keeping corner finders scannable."),
            R.id.qr_info_line_thickness to ("Line Thickness" to "Adjusts the stroke thickness of connected horizontal, vertical, diagonal, and circuit traces."),
            R.id.qr_info_bg_image to ("Background Photo Opacity" to "Controls transparency of the optional background photo beneath foreground modules."),
            R.id.qr_info_backdrop to ("Backdrop Canvas Frame" to "Controls frame corner radius, background fill color, and image fit for styles that draw a canvas backdrop."),
            R.id.qr_info_logo to ("Logo Fine-Tuning" to "Adjusts center logo footprint, crop shape (Squircle/Circle/Square), opacity, and protective border halo."),
            R.id.qr_info_logo_size to ("Logo Size Ratio" to "Controls what fraction of the QR width your center logo covers (10%–33%). Larger logos need Higher Error Correction."),
            R.id.qr_info_motion to ("Motion & Dynamics" to "Import animated GIF, WebP, or MP4 clips. Full-canvas Source Motion works on Image Backdrop, Image Fill, and Image Resample styles; Animated Logo works on all styles."),
            R.id.qr_info_max_frames to ("Max Animation Frames" to "Sets how many frames to extract and render continuously (12–360 frames). Higher counts create smoother, longer loops; adjust anytime to re-extract your clip."),
            R.id.qr_info_anim_sources to ("Animated Backdrop vs Animated Logo" to "Animated Backdrop animates the entire photo/art background (Image styles only). Animated Logo animates just the center icon badge on any visual style."),
            R.id.qr_info_export to ("Export & Verification" to "Choose export pixel dimensions and save lossless PNG, scalable Vector SVG, JPEG, or animated GIF / MP4 / SVG when motion is active."),
            R.id.qr_info_resolution to ("Output Resolution" to "Live preview renders fast at up to 512px while editing. Exports render at full chosen resolution (256–4096px) with strict decoder validation.")
        )
        for ((id, info) in topics) {
            view.findViewById<View>(id)?.setOnClickListener {
                val (title, body) = info
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton("Got it", null)
                    .show()
            }
        }
        view.findViewById<View>(R.id.qr_info_style_params)?.setOnClickListener {
            val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(vm.state.value.style)
            val detail = when (vm.state.value.style) {
                QrStyle.BUBBLE -> "Controls floating ambient dots, density, and bubble outline/fill/position palette. Uses circular finders by default."
                QrStyle.D25 -> "Extrudes QR blocks into 2.5D isometric pillars. Adjust body/finder height, camera projection angle, and top/left/right shading faces."
                QrStyle.DSJ -> "Connects adjacent modules into bold bars and X glyphs with signature stepped corner eyes."
                QrStyle.LINE -> "Draws continuous horizontal, vertical, diagonal, or PCB circuit traces linking neighboring modules. Defaults to Planet finders."
                QrStyle.RANDOM_RECTANGLE -> "Randomizes every module's scale, offset, and color tint using a deterministic seed."
                QrStyle.FUNCTION -> "Applies linear fade or concentric circular halftone shading across modules."
                QrStyle.STYLE_FUNCTION -> "Modulates module size, rounding, and gradient opacity using mathematical wave fields (Wave, Ripple, Spiral, Noise, Organic, etc.)."
                QrStyle.CONNECTED_ORGANIC -> "Builds smooth bezier ink bridges between orthogonal neighbor modules."
                QrStyle.IMAGE -> "Composites QR patterns directly over your Source image/video with adaptive contrast tuning."
                QrStyle.IMAGE_FILL -> "Masks your Source artwork inside dark QR modules over a tinted fill background."
                QrStyle.IMAGE_RESAMPLE -> "Halftones Source imagery into crisp subpixels while locking timing, alignment, and finder anchors."
                QrStyle.BASIC -> "Standard geometric vector modules with full support for custom shapes, gradients, and backdrop artwork."
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("${def.displayName} Settings")
                .setMessage(detail)
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    fun canHandleInnerBack(): Boolean = currentMode == Mode.SCAN

    fun handleBackPressed(): Boolean {
        if (currentMode == Mode.SCAN) {
            if (currentUiState == CameraUiState.RESULT_PRESENTED) {
                isScanningPaused = false
                binding.containerQrScan.findViewById<TextView>(R.id.qr_scan_guidance_text)?.text = "Point camera at a QR code"
                activeQrScanner?.resumeAnalysis()
                updateCameraUiState(CameraUiState.READY)
                return true
            }
            switchMode(Mode.GENERATE)
            return true
        }
        return false
    }

    private fun Slider?.setSnappedValue(raw: Float) {
        val slider = this ?: return
        val min = slider.valueFrom
        val max = slider.valueTo
        val step = slider.stepSize
        val clamped = raw.coerceIn(min, max)
        val snapped = if (step > 0f) {
            val minBd = java.math.BigDecimal(min.toString())
            val maxBd = java.math.BigDecimal(max.toString())
            val stepBd = java.math.BigDecimal(step.toString())
            val clampedBd = java.math.BigDecimal(clamped.toString())
            val nSteps = clampedBd.subtract(minBd)
                .divide(stepBd, 0, java.math.RoundingMode.HALF_UP)
            minBd.add(nSteps.multiply(stepBd))
                .max(minBd)
                .min(maxBd)
                .toFloat()
        } else {
            clamped
        }
        if (kotlin.math.abs(slider.value - snapped) > 1e-4f) {
            slider.value = snapped
        }
    }

    fun onDestroy() {
        discardAndReleaseAllResources()
        cameraExecutor.shutdown()
    }

    companion object {
        internal fun formatActionSummary(action: QrAction, maskWifiPassword: Boolean = true): String = when (action) {
            is QrAction.Url -> buildString {
                append(action.uri)
                if (!action.host.isNullOrBlank()) {
                    append("\nHost: " + action.host)
                }
            }
            is QrAction.Wifi -> buildString {
                append("Network: " + action.ssid)
                append("\nSecurity: " + action.type)
                if (action.password.isNotBlank()) {
                    val pass = if (maskWifiPassword) "••••••••" else action.password
                    append("\nPassword: " + pass)
                }
                if (action.hidden) {
                    append(" (Hidden)")
                }
            }
            is QrAction.Contact -> buildString {
                append(action.name ?: "Contact")
                if (!action.org.isNullOrBlank()) append("\nOrg: " + action.org)
                if (action.phones.isNotEmpty()) append("\nPhone: " + action.phones.joinToString(", "))
                if (action.emails.isNotEmpty()) append("\nEmail: " + action.emails.joinToString(", "))
            }
            is QrAction.UpiPayment -> buildString {
                if (!action.payeeName.isNullOrBlank()) {
                    append("Payee: " + action.payeeName + "\n")
                }
                append("VPA: " + action.payeeAddress)
                if (action.amount != null) {
                    append("\nAmount: ₹" + action.amount)
                }
                if (!action.note.isNullOrBlank()) {
                    append("\nNote: " + action.note)
                }
            }
            is QrAction.Phone -> "Phone: " + action.number
            is QrAction.Sms -> buildString {
                append("Recipient: " + action.number)
                if (!action.message.isNullOrBlank()) append("\nMessage: " + action.message)
            }
            is QrAction.Email -> buildString {
                append("To: " + action.address)
                if (!action.subject.isNullOrBlank()) append("\nSubject: " + action.subject)
                if (!action.body.isNullOrBlank()) append("\nBody: " + action.body)
            }
            is QrAction.Geo -> buildString {
                append("Coordinates: " + action.lat + ", " + action.lon)
                if (!action.label.isNullOrBlank()) append("\nLabel: " + action.label)
            }
            is QrAction.CalendarEvent -> buildString {
                append("Event: " + (action.title ?: "Calendar entry"))
                if (!action.location.isNullOrBlank()) append("\nLocation: " + action.location)
                if (!action.dtStart.isNullOrBlank()) append("\nStarts: " + action.dtStart)
                if (!action.description.isNullOrBlank()) append("\nDetails: " + action.description)
            }
            is QrAction.OtpAuth -> buildString {
                val label = listOfNotNull(action.issuer, action.account).joinToString(": ")
                append("Service: " + (if (label.isNotBlank()) label else "Authenticator"))
                append("\nType: " + action.type.uppercase(Locale.ROOT) + " (" + action.digits + " digits)")
            }
            is QrAction.Raw -> action.text
        }
    }
}
