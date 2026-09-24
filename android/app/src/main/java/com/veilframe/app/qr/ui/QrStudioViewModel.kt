package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veilframe.app.qr.GenerationMode
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
import kotlinx.coroutines.ensureActive
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
        val sourceImage: Bitmap? = null,
        val sourceImageScaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
        val sourceImageOpacity: Float = 1.0f,
        val sourceImageContrast: Float = 0.0f,
        val sourceImageExposure: Float = 0.0f,
        val resampleUseSourceAsBackdrop: Boolean = false,
        val resampleBackdropOpacity: Float = 0.85f,
        val resampleSeed: Long = 42L,
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
    private val renderGeneration = java.util.concurrent.atomic.AtomicLong(0)

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

    fun updateSourceImage(bmp: Bitmap?) {
        _state.value = _state.value.copy(sourceImage = bmp, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun removeSourceImage() {
        _state.value = _state.value.copy(sourceImage = null, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateSourceImageScaleMode(mode: ImageScaleMode) {
        _state.value = _state.value.copy(sourceImageScaleMode = mode)
        regenerate(debounceMs = 0)
    }

    fun updateSourceImageOpacity(opacity: Float) {
        _state.value = _state.value.copy(sourceImageOpacity = opacity.coerceIn(0.05f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateSourceImageContrast(contrast: Float) {
        _state.value = _state.value.copy(sourceImageContrast = contrast.coerceIn(-1.0f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateSourceImageExposure(exposure: Float) {
        _state.value = _state.value.copy(sourceImageExposure = exposure.coerceIn(-1.0f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateResampleUseSourceAsBackdrop(use: Boolean) {
        _state.value = _state.value.copy(resampleUseSourceAsBackdrop = use, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateResampleBackdropOpacity(opacity: Float) {
        _state.value = _state.value.copy(resampleBackdropOpacity = opacity.coerceIn(0.0f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun randomizeResampleSeed() {
        val newSeed = kotlin.random.Random.nextLong()
        _state.value = _state.value.copy(resampleSeed = newSeed, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun terminateSession() {
        renderGeneration.incrementAndGet()
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
        val srcBmp = s.sourceImage
        if (srcBmp != null && !srcBmp.isRecycled) {
            srcBmp.recycle()
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
        val generation = renderGeneration.incrementAndGet()

        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            _state.value = _state.value.copy(isLoading = true, errorMessage = null)

            val design = customDesign ?: buildDesignFromState(_state.value)
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            val mode = if (design.style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE
            val renderResult = QrGenerator.generateWithResult(effectiveContent, design, mode = mode)
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            withContext(Dispatchers.Main) {
                if (generation != renderGeneration.get()) return@withContext
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

    override fun onCleared() {
        super.onCleared()
        terminateSession()
    }

    private fun buildDesignFromState(s: UiState, isPreview: Boolean = true): QrDesign {
        val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(s.style)

        val moduleFill = when {
            s.style == QrStyle.IMAGE_RESAMPLE -> ModuleFill.IMAGE_SAMPLED
            s.style == QrStyle.IMAGE_FILL -> ModuleFill.IMAGE_MASKED
            else -> ModuleFill.SOLID
        }
        val moduleShape = when {
            s.style == QrStyle.BUBBLE -> ModuleShape.BUBBLE_CLUSTER
            else -> def.defaultModuleShape
        }

        // Staged preview pipeline: 512px during interactive editor preview, full s.outputSize for export
        val effectiveSize = if (isPreview) minOf(s.outputSize, 512) else s.outputSize

        return QrDesign(
            correction = s.ecChoice,
            moduleStyle = ModuleStyle(
                shape = moduleShape,
                fill = moduleFill,
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
            outputSize = effectiveSize,
            backgroundImage = s.backgroundImage,
            backgroundImageAlpha = s.backgroundImageAlpha,
            imageFillMode = def.imageFillMode,
            style = s.style,
            imageSource = ImageSourceStyle(
                source = if (s.sourceImage != null) ImageSource.Memory(s.sourceImage) else null,
                scaleMode = s.sourceImageScaleMode,
                opacity = s.sourceImageOpacity,
                contrast = s.sourceImageContrast,
                exposure = s.sourceImageExposure
            ),
            backgroundLayer = BackgroundLayer(
                enabled = s.backgroundImage != null,
                color = s.background,
                bitmap = s.backgroundImage,
                opacity = s.backgroundImageAlpha
            ),
            resampleStyle = ResampleStyle(
                seed = s.resampleSeed,
                useSourceAsBackdrop = s.resampleUseSourceAsBackdrop,
                backdropOpacity = s.resampleBackdropOpacity,
                backdropScaleMode = s.sourceImageScaleMode,
                backdropBlendMode = BackdropBlendMode.NORMAL
            )
        )
    }

    fun saveToGallery() {
        val content = _state.value.content.ifBlank { "https://example.com" }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val exportDesign = buildDesignFromState(_state.value, isPreview = false)
            val mode = if (exportDesign.style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE
            val renderResult = withContext(Dispatchers.Default) {
                QrGenerator.generateWithResult(content, exportDesign, mode = mode)
            }
            if (renderResult is QrRenderResult.Success && renderResult.bitmap != null) {
                val (uri, report) = QrExporter.saveToGalleryValidated(
                    getApplication(),
                    renderResult.bitmap,
                    content,
                    exportDesign,
                    renderResult.matrix
                )
                _state.value = _state.value.copy(
                    saveResult = if (uri != null) "PNG saved to Gallery (${exportDesign.outputSize}x${exportDesign.outputSize})" else "Export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                    scanabilityReport = report,
                    isLoading = false
                )
            } else {
                _state.value = _state.value.copy(
                    saveResult = "Save failed: bitmap generation unsuccessful",
                    isLoading = false
                )
            }
        }
    }

    fun saveSvg() {
        val content = _state.value.content.ifBlank { "https://example.com" }
        viewModelScope.launch {
            val exportDesign = buildDesignFromState(_state.value, isPreview = false)
            val mode = if (exportDesign.style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE
            val matrix = _state.value.matrix ?: QrGenerator.generateMatrix(content, exportDesign, mode = mode)
            val bmp = _state.value.bitmap
            if (bmp != null) {
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(bmp, exportDesign, matrix, content)
                if (!report.isScanReady && !report.validationSkipped) {
                    _state.value = _state.value.copy(
                        saveResult = "SVG export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
            }
            val uri = QrExporter.saveSvg(getApplication(), matrix, exportDesign)
            _state.value = _state.value.copy(
                saveResult = if (uri != null) "Vector SVG saved to Downloads" else "SVG export failed"
            )
        }
    }

    fun share() {
        val content = _state.value.content.ifBlank { "https://example.com" }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val exportDesign = buildDesignFromState(_state.value, isPreview = false)
            val mode = if (exportDesign.style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE
            val renderResult = withContext(Dispatchers.Default) {
                QrGenerator.generateWithResult(content, exportDesign, mode = mode)
            }
            val bmp = if (renderResult is QrRenderResult.Success && renderResult.bitmap != null) {
                renderResult.bitmap
            } else {
                _state.value.bitmap
            }
            _state.value = _state.value.copy(isLoading = false)
            if (bmp != null) {
                QrExporter.share(getApplication(), bmp)
            }
        }
    }

    fun clearSaveResult() {
        _state.value = _state.value.copy(saveResult = null)
    }
}
