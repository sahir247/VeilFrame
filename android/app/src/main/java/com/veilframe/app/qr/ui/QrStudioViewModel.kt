package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.validation.AutoRepairEngine
import com.veilframe.app.qr.validation.ScanabilityReport
import com.veilframe.app.qr.exporter.QrExporter
import com.veilframe.app.qr.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrStudioViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val content: String = "",
        val style: QrStyle = QrStyle.BASIC,
        val outputSize: Int = 512,
        val ecChoice: ErrorCorrectionChoice = ErrorCorrectionChoice.AUTO,
        val foreground: Int = Color.BLACK,
        val background: Int = Color.WHITE,
        val logo: Bitmap? = null,
        val logoFraction: Float = 0.20f,
        val backgroundImage: Bitmap? = null,
        val backgroundImageAlpha: Float = 0.25f,
        val bitmap: Bitmap? = null,
        val matrix: QrMatrix? = null,
        val design: QrDesign? = null,
        val scanabilityReport: ScanabilityReport? = null,
        val isLoading: Boolean = false,
        val saveResult: String? = null,
        val errorMessage: String? = null,
        val repairNotice: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var generateJob: Job? = null

    init {
        regenerate(debounceMs = 0)
    }

    fun updateContent(text: String) {
        _state.value = _state.value.copy(content = text, repairNotice = null)
        regenerate(debounceMs = 150)
    }

    fun updateStyle(style: QrStyle) {
        _state.value = _state.value.copy(style = style, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateOutputSize(size: Int) {
        _state.value = _state.value.copy(outputSize = size)
        regenerate(debounceMs = 0)
    }

    fun updateErrorCorrection(choice: ErrorCorrectionChoice) {
        _state.value = _state.value.copy(ecChoice = choice)
        regenerate(debounceMs = 0)
    }

    fun updateForeground(color: Int) {
        _state.value = _state.value.copy(foreground = color, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateBackground(color: Int) {
        _state.value = _state.value.copy(background = color, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateLogo(bmp: Bitmap?) {
        _state.value = _state.value.copy(logo = bmp, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun removeLogo() {
        _state.value = _state.value.copy(logo = null, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateLogoFraction(fraction: Float) {
        _state.value = _state.value.copy(logoFraction = fraction.coerceIn(0.10f, 0.35f))
        regenerate(debounceMs = 120)
    }

    fun updateBackgroundImage(bmp: Bitmap?) {
        _state.value = _state.value.copy(backgroundImage = bmp, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun removeBackgroundImage() {
        _state.value = _state.value.copy(backgroundImage = null, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateBackgroundImageAlpha(alpha: Float) {
        _state.value = _state.value.copy(backgroundImageAlpha = alpha.coerceIn(0.05f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun terminateSession() {
        generateJob?.cancel()
        generateJob = null
        val s = _state.value
        val bmp = s.bitmap
        if (bmp != null && !bmp.isRecycled) {
            bmp.recycle()
        }
        val logo = s.logo
        if (logo != null && !logo.isRecycled) {
            logo.recycle()
        }
        val bgBmp = s.backgroundImage
        if (bgBmp != null && !bgBmp.isRecycled) {
            bgBmp.recycle()
        }
        _state.value = UiState(content = "")
    }

    fun autoRepair() {
        val s = _state.value
        val report = s.scanabilityReport ?: return
        val currentDesign = s.design ?: return
        val effectiveContent = s.content.ifBlank { "https://example.com" }

        val repairResult = AutoRepairEngine.repair(currentDesign, report, effectiveContent)
        val notice = if (repairResult.changesApplied.isNotEmpty()) {
            repairResult.changesApplied.joinToString("; ")
        } else {
            "No adjustments needed"
        }

        val repaired = repairResult.repairedDesign
        _state.value = s.copy(
            ecChoice = repaired.correction,
            foreground = repaired.palette.foreground,
            background = when (val bg = repaired.background) {
                is BackgroundStyle.Solid -> bg.color
                else -> s.background
            },
            design = repaired,
            repairNotice = notice
        )
        regenerate(customDesign = repaired, debounceMs = 0)
    }

    fun regenerate(customDesign: QrDesign? = null, debounceMs: Long = 0) {
        val s = _state.value
        val effectiveContent = s.content.ifBlank { "https://example.com" }

        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)

            val design = customDesign ?: buildDesignFromState(_state.value)
            val renderResult = QrGenerator.generateWithResult(effectiveContent, design)

            withContext(Dispatchers.Main) {
                when (renderResult) {
                    is QrRenderResult.Success -> {
                        val oldBitmap = _state.value.bitmap
                        _state.value = _state.value.copy(
                            bitmap = renderResult.bitmap,
                            matrix = renderResult.matrix,
                            design = renderResult.design,
                            scanabilityReport = renderResult.report,
                            isLoading = false,
                            errorMessage = null
                        )
                        if (oldBitmap != null && oldBitmap != renderResult.bitmap && !oldBitmap.isRecycled) {
                            oldBitmap.recycle()
                        }
                    }
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            errorMessage = renderResult.error
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        terminateSession()
    }

    private fun buildDesignFromState(s: UiState): QrDesign {
        val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(s.style)

        return QrDesign(
            correction = s.ecChoice,
            moduleStyle = ModuleStyle(
                shape = def.defaultModuleShape,
                scale = 0.85f,
                cornerRadiusFraction = 0.0f,
                connected = s.style == QrStyle.DSJ
            ),
            eyeStyle = EyeStyle(
                style = def.defaultFinderStyle,
                outerColor = s.foreground,
                innerColor = s.foreground
            ),
            palette = PaletteStyle(
                foreground = s.foreground,
                background = s.background
            ),
            background = if (s.backgroundImage != null) {
                BackgroundStyle.Image(s.backgroundImage, s.backgroundImageAlpha)
            } else {
                BackgroundStyle.Solid(s.background)
            },
            logo = if (s.logo != null) {
                LogoStyle(bitmap = s.logo, scaleFraction = s.logoFraction)
            } else null,
            effects = EffectStyle(
                is25D = def.is25D,
                topColor = s.foreground,
                leftColor = 0x33000000,
                rightColor = 0x99000000.toInt()
            ),
            quietZoneModules = 4,
            outputSize = s.outputSize,
            backgroundImage = s.backgroundImage,
            backgroundImageAlpha = s.backgroundImageAlpha,
            imageFillMode = def.imageFillMode,
            style = s.style
        )
    }

    fun saveToGallery() {
        val bmp = _state.value.bitmap ?: return
        val content = _state.value.content.ifBlank { "https://example.com" }
        val design = _state.value.design
        val matrix = _state.value.matrix
        viewModelScope.launch {
            if (design != null && matrix != null) {
                val (uri, report) = QrExporter.saveToGalleryValidated(getApplication(), bmp, content, design, matrix)
                _state.value = _state.value.copy(
                    saveResult = if (uri != null) "PNG saved to Gallery" else "Export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                    scanabilityReport = report
                )
            } else {
                val uri = QrExporter.saveToGallery(getApplication(), bmp)
                _state.value = _state.value.copy(
                    saveResult = if (uri != null) "PNG saved to Gallery" else "Save failed"
                )
            }
        }
    }

    fun saveSvg() {
        val matrix = _state.value.matrix ?: return
        val design = _state.value.design ?: return
        val bmp = _state.value.bitmap
        val content = _state.value.content.ifBlank { "https://example.com" }
        viewModelScope.launch {
            if (bmp != null) {
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(bmp, design, matrix, content)
                if (!report.isScanReady) {
                    _state.value = _state.value.copy(
                        saveResult = "SVG export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
            }
            val uri = QrExporter.saveSvg(getApplication(), matrix, design)
            _state.value = _state.value.copy(
                saveResult = if (uri != null) "Vector SVG saved to Downloads" else "SVG export failed"
            )
        }
    }

    fun share() {
        val bmp = _state.value.bitmap ?: return
        viewModelScope.launch {
            QrExporter.share(getApplication(), bmp)
        }
    }

    fun clearSaveResult() {
        _state.value = _state.value.copy(saveResult = null)
    }
}
