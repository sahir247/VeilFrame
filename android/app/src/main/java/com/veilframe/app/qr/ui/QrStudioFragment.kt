package com.veilframe.app.qr.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.veilframe.app.R
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.QrScanner
import com.veilframe.app.qr.scanner.action.QrActionExecutor
import android.text.Editable
import android.text.TextWatcher
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * QR Studio Fragment — host fragment for the QR Studio tool.
 * Provides two tabs:
 * 1. Generate — create stylized QR codes with 11 artistic styles, logos, and custom colors
 * 2. Scan — scan QR codes via CameraX or image gallery import with safe intent dispatch
 */
class QrStudioFragment : Fragment() {

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

        toolbar.setNavigationOnClickListener {
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
}

/**
 * Generate tab fragment for QR Code generation, customization, and export.
 */
class QrGenerateTabFragment : Fragment() {

    private val vm: QrStudioViewModel by activityViewModels()

    private val logoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = requireContext().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        vm.updateLogo(bmp)
    }

    private val bgImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = requireContext().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        vm.updateBackgroundImage(bmp)
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
        val scanabilityStatus  = view.findViewById<TextView>(R.id.qr_scanability_status)
        val scanabilityDetails = view.findViewById<TextView>(R.id.qr_scanability_details)
        val autoRepairBtn      = view.findViewById<Button>(R.id.qr_auto_repair_btn)

        val contentInput       = view.findViewById<EditText>(R.id.qr_content_input)
        val styleSpinner       = view.findViewById<Spinner>(R.id.qr_style_spinner)
        val resSpinner         = view.findViewById<Spinner>(R.id.qr_resolution_spinner)
        val ecSpinner          = view.findViewById<Spinner>(R.id.qr_ec_spinner)

        val fgColorBtn         = view.findViewById<Button>(R.id.qr_fg_color_btn)
        val bgColorBtn         = view.findViewById<Button>(R.id.qr_bg_color_btn)
        val logoBtn            = view.findViewById<Button>(R.id.qr_logo_btn)
        val bgImageBtn         = view.findViewById<Button>(R.id.qr_bg_image_btn)

        val saveBtn            = view.findViewById<Button>(R.id.qr_save_btn)
        val saveSvgBtn         = view.findViewById<Button>(R.id.qr_save_svg_btn)
        val shareBtn           = view.findViewById<Button>(R.id.qr_share_btn)

        // 1. Style Spinner (11 EFQRCode Styles)
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

        // 4. Content Input with debounced live updates
        contentInput.setText(vm.state.value.content)
        contentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (text != vm.state.value.content) {
                    vm.updateContent(text)
                }
            }
        })
        contentInput.setOnEditorActionListener { tv, _, _ ->
            vm.updateContent(tv.text.toString())
            false
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

        vm.regenerate()
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
        val bmp = requireContext().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return@registerForActivityResult
        val raw = QrScanner.decode(bmp)
        if (raw != null) {
            handleScanResult(raw)
        } else {
            Toast.makeText(requireContext(), "No QR code found in image", Toast.LENGTH_SHORT).show()
        }
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
        previewView = view.findViewById(R.id.qr_camera_preview)
        resultCard = view.findViewById(R.id.qr_result_card)
        resultType = view.findViewById(R.id.qr_result_type)
        resultText = view.findViewById(R.id.qr_result_text)
        resultActionBtn = view.findViewById(R.id.qr_result_action_btn)

        view.findViewById<Button>(R.id.qr_scan_gallery_btn).setOnClickListener {
            qrDecodePickerLauncher.launch("image/*")
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndStartCamera()
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = previewView ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor, QrScanner { raw ->
                    activity?.runOnUiThread {
                        handleScanResult(raw)
                    }
                })
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                // Ignore or log error
            }
        }, ContextCompat.getMainExecutor(requireContext()))
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
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
