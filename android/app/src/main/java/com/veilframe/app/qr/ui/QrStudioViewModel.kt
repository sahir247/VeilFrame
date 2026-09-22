package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.exporter.QrExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.validation.AutoRepairEngine
import com.veilframe.app.qr.validation.ScanabilityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrStudioViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val content: String = "https://veilframe.app",
        val style: QrStyle = QrStyle.BASIC,
        val outputSize: Int = 512,
        val ecChoice: ErrorCorrectionChoice = ErrorCorrectionChoice.AUTO,
        val foreground: Int = Color.BLACK,
        val background: Int = Color.WHITE,
        val logo: Bitmap? = null,
        val backgroundImage: Bitmap? = null,
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

    fun updateBackgroundImage(bmp: Bitmap?) {
        _state.value = _state.value.copy(backgroundImage = bmp, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun autoRepair() {
        val s = _state.value
        val report = s.scanabilityReport ?: return
        val currentDesign = s.design ?: return

        val repairResult = AutoRepairEngine.repair(currentDesign, report, s.content)
        val notice = if (repairResult.changesApplied.isNotEmpty()) {
            repairResult.changesApplied.joinToString("; ")
        } else {
            "No adjustments needed"
        }

        _state.value = s.copy(
            foreground = repairResult.repairedDesign.palette.foreground,
            background = when (val bg = repairResult.repairedDesign.background) {
                is BackgroundStyle.Solid -> bg.color
                else -> s.background
            },
            repairNotice = notice
        )
        regenerate(customDesign = repairResult.repairedDesign, debounceMs = 0)
    }

    fun regenerate(customDesign: QrDesign? = null, debounceMs: Long = 0) {
        val s = _state.value
        if (s.content.isBlank()) return

        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)

            val design = customDesign ?: buildDesignFromState(_state.value)
            val renderResult = QrGenerator.generateWithResult(s.content, design)

            withContext(Dispatchers.Main) {
                when (renderResult) {
                    is QrRenderResult.Success -> {
                        _state.value = _state.value.copy(
                            bitmap = renderResult.bitmap,
                            matrix = renderResult.matrix,
                            design = renderResult.design,
                            scanabilityReport = renderResult.report,
                            isLoading = false,
                            errorMessage = null
                        )
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

    private fun buildDesignFromState(s: UiState): QrDesign {
        val moduleShape = when (s.style) {
            QrStyle.BASIC -> ModuleShape.SQUARE
            QrStyle.BUBBLE -> ModuleShape.CIRCLE
            QrStyle.LINE -> ModuleShape.LINE
            QrStyle.RANDOM_RECTANGLE -> ModuleShape.ORGANIC
            QrStyle.DSJ -> ModuleShape.CONNECTED
            QrStyle.D25 -> ModuleShape.SQUARE
            else -> ModuleShape.SQUARE
        }

        val finderStyle = when (s.style) {
            QrStyle.DSJ -> FinderStyle.DSJ
            QrStyle.BUBBLE -> FinderStyle.CIRCLE
            else -> FinderStyle.CLASSIC
        }

        val is25D = s.style == QrStyle.D25
        val isImageFill = s.style == QrStyle.IMAGE_FILL || s.style == QrStyle.IMAGE

        return QrDesign(
            correction = s.ecChoice,
            moduleStyle = ModuleStyle(
                shape = moduleShape,
                scale = 0.85f,
                cornerRadiusFraction = 0.0f
            ),
            eyeStyle = EyeStyle(
                style = finderStyle,
                outerColor = s.foreground,
                innerColor = s.foreground
            ),
            palette = PaletteStyle(
                foreground = s.foreground,
                background = s.background
            ),
            background = if (s.backgroundImage != null) {
                BackgroundStyle.Image(s.backgroundImage, 0.85f)
            } else {
                BackgroundStyle.Solid(s.background)
            },
            logo = if (s.logo != null) {
                LogoStyle(bitmap = s.logo, scaleFraction = 0.22f)
            } else null,
            effects = EffectStyle(
                is25D = is25D,
                topColor = s.foreground,
                leftColor = 0x33000000,
                rightColor = 0x99000000.toInt()
            ),
            quietZoneModules = 4,
            outputSize = s.outputSize,
            backgroundImage = s.backgroundImage,
            imageFillMode = isImageFill
        )
    }

    fun saveToGallery() {
        val bmp = _state.value.bitmap ?: return
        viewModelScope.launch {
            val uri = QrExporter.saveToGallery(getApplication(), bmp)
            _state.value = _state.value.copy(
                saveResult = if (uri != null) "PNG saved to Gallery" else "Save failed"
            )
        }
    }

    fun saveSvg() {
        val matrix = _state.value.matrix ?: return
        val design = _state.value.design ?: return
        viewModelScope.launch {
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
