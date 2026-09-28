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
import com.veilframe.app.qr.registry.QrStyleRegistry
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
        val resampleBackdropImage: Bitmap? = null,
        val resampleUseSourceAsBackdrop: Boolean = false,
        val resampleBackdropOpacity: Float = 1.0f,
        val resampleSeed: Long = 42L,
        val animatedFrames: List<QrFrame> = emptyList(),
        val bitmap: Bitmap? = null,
        val matrix: QrMatrix? = null,
        val design: QrDesign? = null,
        val scanabilityReport: ScanabilityReport? = null,
        val isRenderingPreview: Boolean = false,
        val isExporting: Boolean = false,
        val exportProgress: Float? = null,
        val saveResult: String? = null,
        val errorMessage: String? = null,
        val repairNotice: String? = null,

        // Complete Structured Form State (Hydrated on Fragment restoration)
        val activePresetId: Int = com.veilframe.app.R.id.chip_preset_text,
        val wifiSsid: String = "",
        val wifiPassword: String = "",
        val wifiSecurityPos: Int = 0,
        val wifiHidden: Boolean = false,
        val vcardFirst: String = "",
        val vcardLast: String = "",
        val vcardPhone: String = "",
        val vcardEmail: String = "",
        val vcardOrg: String = "",
        val emailRecipient: String = "",
        val emailSubject: String = "",
        val emailBody: String = "",
        val smsPhone: String = "",
        val smsBody: String = "",
        val upiVpa: String = "",
        val upiAmount: String = "",

        // Style-Specific Customization Parameters
        val d25Depth: Float = 1.0f,
        val d25PositionDepth: Float = 1.0f,
        val d25Angle: Float = 45f,
        val d25LeftColor: Int = 0x33000000,
        val d25RightColor: Int = 0x99000000.toInt(),
        val lineDirection: LineDirection = LineDirection.X,
        val lineThickness: Float = 0.5f,
        val lineVariant: LineVariant = LineVariant.EF,
        val lineLengthFraction: Float = 1.0f,
        val lineAccentRings: Boolean = false,
        val lineCircuitBridges: Boolean = false,
        val dsjLineSize: Float = 0.7f,
        val dsjXSize: Float = 0.7f,
        val dsjHorizontalColor: Int = 0xFFF6B506.toInt(),
        val dsjVerticalColor: Int = 0xFFE02020.toInt(),
        val dsjXColor: Int = 0xFF0B2D97.toInt(),
        val randomRectSeed: Long = 42L,
        val randomJitterScale: Float = 0.25f,
        val randomJitterOffset: Float = 0.0f,
        val randomJitterColor: Float = 0.1f,
        val bubbleAmbient: Boolean = true,
        val bubbleDensity: Float = 0.15f,
        val veilFunctionType: VeilFunctionType = VeilFunctionType.FADE,
        val veilFunctionDataStyle: VeilFunctionDataStyle = VeilFunctionDataStyle.ROUND,
        val paramFunctionType: FunctionType = FunctionType.WAVE,
        val connectedLineThickness: Float = 0.2f,
        val gradientStart: Int? = null,
        val gradientEnd: Int? = null,
        val gradientType: GradientType = GradientType.NONE,
        val directionalQuietZone: DirectionalInsets? = null,
        val quietZoneChoice: Int? = null
    ) {
        val isLoading: Boolean get() = isRenderingPreview || isExporting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var generateJob: Job? = null
    private var exportJob: Job? = null
    private val renderGeneration = java.util.concurrent.atomic.AtomicLong(0)

    init {
        regenerate(debounceMs = 0)
    }

    fun updateContent(text: String) {
        _state.value = _state.value.copy(content = text, repairNotice = null)
        regenerate(debounceMs = 150)
    }

    fun updateStyle(style: QrStyle) {
        _state.value = _state.value.copy(
            style = style,
            repairNotice = null
        )
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
        _state.value = _state.value.copy(sourceImage = bmp, animatedFrames = emptyList(), repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateAnimatedFrames(frames: List<QrFrame>) {
        val firstBmp = frames.firstOrNull()?.bitmap
        _state.value = _state.value.copy(
            sourceImage = firstBmp ?: _state.value.sourceImage,
            animatedFrames = frames,
            repairNotice = null
        )
        regenerate(debounceMs = 0)
    }

    fun removeSourceImage() {
        _state.value = _state.value.copy(sourceImage = null, animatedFrames = emptyList(), repairNotice = null)
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

    fun updateResampleBackdropImage(bmp: Bitmap?) {
        _state.value = _state.value.copy(resampleBackdropImage = bmp, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun removeResampleBackdropImage() {
        _state.value = _state.value.copy(resampleBackdropImage = null, repairNotice = null)
        regenerate(debounceMs = 0)
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
        exportJob?.cancel()
        exportJob = null
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
        val backdropBmp = s.resampleBackdropImage
        if (backdropBmp != null && !backdropBmp.isRecycled && backdropBmp !== srcBmp && backdropBmp !== bgBmp) {
            backdropBmp.recycle()
        }
        _state.value = UiState(content = "")
    }

    fun autoRepair() {
        val s = _state.value
        val effectiveContent = s.content.trim()
        if (effectiveContent.isEmpty()) return
        val report = s.scanabilityReport ?: return
        val currentDesign = s.design ?: return

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
        val effectiveContent = s.content.trim()
        if (effectiveContent.isEmpty()) {
            renderGeneration.incrementAndGet()
            generateJob?.cancel()
            generateJob = null
            _state.value = _state.value.copy(
                bitmap = null,
                matrix = null,
                design = null,
                scanabilityReport = null,
                isRenderingPreview = false,
                errorMessage = null
            )
            return
        }
        val generation = renderGeneration.incrementAndGet()

        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            _state.value = _state.value.copy(isRenderingPreview = true, errorMessage = null)

            val design = customDesign ?: buildDesignFromState(_state.value)
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            val renderResult = QrGenerator.generateWithResult(effectiveContent, design)
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
                            isRenderingPreview = false,
                            errorMessage = null
                        )
                    }
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            isRenderingPreview = false,
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

    internal fun buildDesignFromState(s: UiState, isPreview: Boolean = true): QrDesign {
        val def = com.veilframe.app.qr.registry.QrStyleRegistry.get(s.style)

        val moduleFill = when {
            s.style == QrStyle.IMAGE_RESAMPLE -> ModuleFill.IMAGE_SAMPLED
            s.style == QrStyle.IMAGE_FILL -> ModuleFill.IMAGE
            else -> ModuleFill.SOLID
        }
        val moduleShape = when {
            s.style == QrStyle.BUBBLE -> ModuleShape.BUBBLE_CLUSTER
            else -> def.defaultModuleShape
        }

        // Staged preview pipeline: 512px during interactive editor preview, full s.outputSize for export
        val effectiveSize = if (isPreview) minOf(s.outputSize, 512) else s.outputSize

        val baseDesign = QrDesign(
            correction = s.ecChoice,
            moduleStyle = ModuleStyle(
                shape = moduleShape,
                fill = moduleFill,
                // EFQRCode parity: BASIC, D25, and IMAGE use 1.0 data-module scale.
                // Other styles use 0.85 as VeilFrame's default.
                scale = when (s.style) {
                    QrStyle.BASIC, QrStyle.D25, QrStyle.IMAGE -> 1.0f
                    else -> 0.85f
                },
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
                background = s.background,
                gradientStart = s.gradientStart,
                gradientEnd = s.gradientEnd,
                gradientType = s.gradientType
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
                leftColor = s.d25LeftColor,
                rightColor = s.d25RightColor,
                dataHeightRatio = s.d25Depth,
                positionHeightRatio = s.d25PositionDepth
            ),
            depthStyle = DepthStyle(
                depth = s.d25Depth,
                positionDepth = s.d25PositionDepth,
                angleDegrees = s.d25Angle,
                topColor = s.foreground,
                leftColor = s.d25LeftColor,
                rightColor = s.d25RightColor
            ),
            timingStyle = TimingStyle(
                shape = if (s.style == QrStyle.IMAGE || s.style == QrStyle.IMAGE_RESAMPLE) ModuleShape.SQUARE else ModuleShape.ROUNDED
            ),
            alignmentStyle = AlignmentStyle(
                shape = if (s.style == QrStyle.IMAGE || s.style == QrStyle.IMAGE_RESAMPLE) ModuleShape.SQUARE else ModuleShape.ROUNDED
            ),
            lineStyle = LineStyle(
                direction = s.lineDirection,
                thicknessFraction = s.lineThickness,
                lengthFraction = s.lineLengthFraction,
                color = s.foreground,
                variant = s.lineVariant,
                accentRingsEnabled = s.lineAccentRings || s.lineVariant == LineVariant.CIRCUIT,
                circuitBridgesEnabled = s.lineCircuitBridges || s.lineVariant == LineVariant.CIRCUIT
            ),
            veilDsjStyle = VeilDsjStyle(
                lineSize = s.dsjLineSize,
                xSize = s.dsjXSize,
                horizontalLineColor = s.dsjHorizontalColor,
                verticalLineColor = s.dsjVerticalColor,
                xColor = s.dsjXColor
            ),
            jitterStyle = RandomJitterStyle(
                seed = s.randomRectSeed,
                scaleJitter = s.randomJitterScale,
                offsetJitter = s.randomJitterOffset,
                colorJitter = s.randomJitterColor
            ),
            clusterStyle = BubbleClusterStyle(
                seed = s.resampleSeed,
                ambientBubbles = s.bubbleAmbient,
                ambientDensity = s.bubbleDensity,
                dataColor = if (s.style == QrStyle.BUBBLE && s.foreground == Color.BLACK) 0xFF8ED1FC.toInt() else s.foreground,
                dataCenterColor = if (s.style == QrStyle.BUBBLE && s.background == Color.WHITE) 0xFFFFFFFF.toInt() else s.background,
                positionColor = if (s.style == QrStyle.BUBBLE && s.foreground == Color.BLACK) 0xFF0693E3.toInt() else s.foreground
            ),
            veilFunctionStyle = VeilFunctionStyle(
                functionType = s.veilFunctionType,
                dataStyle = s.veilFunctionDataStyle,
                dataColor = s.foreground,
                circleColor = s.foreground
            ),
            functionStyle = FunctionStyle(
                type = s.paramFunctionType,
                seed = s.randomRectSeed
            ),
            compositeStyle = CompositePrimitiveStyle(
                primitives = listOf(ModulePrimitive.CROSS, ModulePrimitive.X),
                lineThickness = s.connectedLineThickness,
                crossScale = 1.0f
            ),
            directionalQuietZone = s.directionalQuietZone,
            quietZoneModules = s.quietZoneChoice ?: if (s.style == QrStyle.IMAGE || s.style == QrStyle.IMAGE_RESAMPLE || s.style == QrStyle.IMAGE_FILL) 1 else 4,
            explicitQuietZone = s.quietZoneChoice ?: if (s.style == QrStyle.IMAGE || s.style == QrStyle.IMAGE_RESAMPLE || s.style == QrStyle.IMAGE_FILL) 1 else null,
            outputSize = effectiveSize,
            backgroundImage = s.backgroundImage,
            backgroundImageAlpha = s.backgroundImageAlpha,
            imageFillMode = def.imageFillMode,
            style = s.style,
            // EFQRCode parity: IMAGE data scale is 1.0 (not 0.33).
            imageDataScale = if (s.style == QrStyle.IMAGE) 1.0f else 0.85f,
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
                backdropBitmap = s.resampleBackdropImage,
                useSourceAsBackdrop = s.resampleUseSourceAsBackdrop,
                backdropOpacity = s.resampleBackdropOpacity,
                backdropScaleMode = s.sourceImageScaleMode,
                backdropBlendMode = BackdropBlendMode.NORMAL
            )
        )
        return if (s.style == QrStyle.IMAGE_RESAMPLE) {
            com.veilframe.app.qr.renderer.ArtisticResampleProfile.applyProfile(baseDesign)
        } else {
            baseDesign
        }
    }

    // --- Structured Form State Updaters ---

    fun updateActivePreset(presetId: Int) {
        _state.value = _state.value.copy(activePresetId = presetId)
    }

    fun updateWifiForm(ssid: String, pass: String, secPos: Int, hidden: Boolean) {
        _state.value = _state.value.copy(
            wifiSsid = ssid,
            wifiPassword = pass,
            wifiSecurityPos = secPos,
            wifiHidden = hidden
        )
    }

    fun updateVcardForm(first: String, last: String, phone: String, email: String, org: String) {
        _state.value = _state.value.copy(
            vcardFirst = first,
            vcardLast = last,
            vcardPhone = phone,
            vcardEmail = email,
            vcardOrg = org
        )
    }

    fun updateEmailForm(recipient: String, subject: String, body: String) {
        _state.value = _state.value.copy(
            emailRecipient = recipient,
            emailSubject = subject,
            emailBody = body
        )
    }

    fun updateSmsForm(phone: String, body: String) {
        _state.value = _state.value.copy(
            smsPhone = phone,
            smsBody = body
        )
    }

    fun updateUpiForm(vpa: String, amount: String) {
        _state.value = _state.value.copy(
            upiVpa = vpa,
            upiAmount = amount
        )
    }

    // --- Style-Specific Updaters ---

    fun updateD25Depth(depth: Float) {
        _state.value = _state.value.copy(d25Depth = depth.coerceIn(0.2f, 2.0f))
        regenerate(debounceMs = 120)
    }

    fun updateD25PositionDepth(posDepth: Float) {
        _state.value = _state.value.copy(d25PositionDepth = posDepth.coerceIn(0.2f, 2.0f))
        regenerate(debounceMs = 120)
    }

    fun updateD25Angle(angle: Float) {
        _state.value = _state.value.copy(d25Angle = angle.coerceIn(0f, 90f))
        regenerate(debounceMs = 120)
    }

    fun updateD25Colors(leftColor: Int, rightColor: Int) {
        _state.value = _state.value.copy(d25LeftColor = leftColor, d25RightColor = rightColor)
        regenerate(debounceMs = 0)
    }

    fun updateLineDirection(direction: LineDirection) {
        _state.value = _state.value.copy(lineDirection = direction)
        regenerate(debounceMs = 0)
    }

    fun updateLineThickness(thickness: Float) {
        _state.value = _state.value.copy(lineThickness = thickness.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateLineVariant(variant: LineVariant) {
        _state.value = _state.value.copy(lineVariant = variant)
        regenerate(debounceMs = 0)
    }

    fun updateLineLengthFraction(fraction: Float) {
        _state.value = _state.value.copy(lineLengthFraction = fraction.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateLineTopology(accentRings: Boolean, circuitBridges: Boolean) {
        _state.value = _state.value.copy(lineAccentRings = accentRings, lineCircuitBridges = circuitBridges)
        regenerate(debounceMs = 0)
    }

    fun updateDsjSizes(lineSize: Float, xSize: Float) {
        _state.value = _state.value.copy(
            dsjLineSize = lineSize.coerceIn(0.1f, 1.0f),
            dsjXSize = xSize.coerceIn(0.1f, 1.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateDsjLineSize(lineSize: Float) {
        _state.value = _state.value.copy(dsjLineSize = lineSize.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateDsjXSize(xSize: Float) {
        _state.value = _state.value.copy(dsjXSize = xSize.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateDsjColors(hColor: Int, vColor: Int, xColor: Int) {
        _state.value = _state.value.copy(
            dsjHorizontalColor = hColor,
            dsjVerticalColor = vColor,
            dsjXColor = xColor
        )
        regenerate(debounceMs = 0)
    }

    fun randomizeRandomRectSeed() {
        val newSeed = kotlin.random.Random.nextLong()
        _state.value = _state.value.copy(randomRectSeed = newSeed)
        regenerate(debounceMs = 0)
    }

    fun updateRandomJitter(scale: Float, offset: Float, color: Float = 0.1f) {
        _state.value = _state.value.copy(
            randomJitterScale = scale.coerceIn(0f, 1f),
            randomJitterOffset = offset.coerceIn(0f, 1f),
            randomJitterColor = color.coerceIn(0f, 1f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateBubbleCluster(ambient: Boolean, density: Float) {
        _state.value = _state.value.copy(
            bubbleAmbient = ambient,
            bubbleDensity = density.coerceIn(0f, 1f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateVeilFunction(type: VeilFunctionType, dataStyle: VeilFunctionDataStyle) {
        _state.value = _state.value.copy(veilFunctionType = type, veilFunctionDataStyle = dataStyle)
        regenerate(debounceMs = 0)
    }

    fun updateParamFunction(type: FunctionType) {
        _state.value = _state.value.copy(paramFunctionType = type)
        regenerate(debounceMs = 0)
    }

    fun updateConnectedLineThickness(thickness: Float) {
        _state.value = _state.value.copy(connectedLineThickness = thickness.coerceIn(0.05f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun isSourcePhotoRequired(style: QrStyle, sourceImage: Bitmap?): Boolean {
        return QrStyleRegistry.get(style).requiresSourceImage && sourceImage == null
    }

    fun updateGradient(start: Int?, end: Int?, type: GradientType = GradientType.LINEAR) {
        _state.value = _state.value.copy(gradientStart = start, gradientEnd = end, gradientType = type)
        regenerate(debounceMs = 0)
    }

    fun updateDirectionalQuietZone(insets: DirectionalInsets?) {
        _state.value = _state.value.copy(directionalQuietZone = insets)
        regenerate(debounceMs = 0)
    }

    fun updateQuietZone(modules: Int?) {
        _state.value = _state.value.copy(quietZoneChoice = modules)
        regenerate(debounceMs = 0)
    }

    // --- Export Actions (Serialized with single-flight Job execution) ---

    fun saveToGallery() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to export QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign)
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
                        scanabilityReport = report
                    )
                } else {
                    _state.value = _state.value.copy(
                        saveResult = "Save failed: bitmap generation unsuccessful"
                    )
                }
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun saveSvg() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to export QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val matrix = QrGenerator.generateMatrix(content, exportDesign)
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign)
                }
                val exportBmp = if (renderResult is QrRenderResult.Success) renderResult.bitmap else null
                if (exportBmp != null) {
                    val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(exportBmp, exportDesign, matrix, content)
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
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun saveGif() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to export QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val matrix = QrGenerator.generateMatrix(content, exportDesign)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage ?: _state.value.bitmap
                    if (baseBmp != null) listOf(QrFrame(baseBmp, 100)) else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "GIF export requires a photo, background image, or animated frames"
                    )
                    return@launch
                }

                val rendered = withContext(Dispatchers.Default) {
                    com.veilframe.app.qr.AnimatedQrGenerator.renderFrames(
                        matrix = matrix,
                        baseDesign = exportDesign,
                        sourceFrames = frames,
                        outputSize = exportDesign.outputSize
                    )
                }

                if (rendered.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "GIF rendering produced no frames"
                    )
                    return@launch
                }

                val gifBytes = withContext(Dispatchers.Default) {
                    com.veilframe.app.qr.AnimatedQrGenerator.encodeToGif(rendered)
                }

                val uri = QrExporter.saveGif(getApplication(), gifBytes)
                _state.value = _state.value.copy(
                    saveResult = if (uri != null) "Animated GIF saved to Gallery (${rendered.size} frames)" else "GIF export failed"
                )
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun saveVideo() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to export QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val matrix = QrGenerator.generateMatrix(content, exportDesign)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage ?: _state.value.bitmap
                    if (baseBmp != null) {
                        // Create loop sequence of 15 frames for single image
                        (0 until 15).map { QrFrame(baseBmp, 66) }
                    } else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "Video export requires a photo, background image, or video frames"
                    )
                    return@launch
                }

                val rendered = withContext(Dispatchers.Default) {
                    com.veilframe.app.qr.AnimatedQrGenerator.renderFrames(
                        matrix = matrix,
                        baseDesign = exportDesign,
                        sourceFrames = frames,
                        outputSize = exportDesign.outputSize
                    )
                }

                if (rendered.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "Video rendering produced no frames"
                    )
                    return@launch
                }

                val context = getApplication<Application>()
                val tempFile = java.io.File(context.cacheDir, "temp_qr_export_${System.currentTimeMillis()}.mp4")
                val success = withContext(Dispatchers.IO) {
                    com.veilframe.app.qr.AnimatedQrGenerator.encodeToVideo(rendered, tempFile, fps = 15)
                }

                if (success && tempFile.exists()) {
                    val uri = QrExporter.saveVideo(context, tempFile)
                    tempFile.delete()
                    _state.value = _state.value.copy(
                        saveResult = if (uri != null) "MP4 Video saved to Movies" else "Video export write failed"
                    )
                } else {
                    tempFile.delete()
                    _state.value = _state.value.copy(
                        saveResult = "Video encoding failed"
                    )
                }
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun saveAnimatedSvg() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to export QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val matrix = QrGenerator.generateMatrix(content, exportDesign)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage ?: _state.value.bitmap
                    if (baseBmp != null) listOf(QrFrame(baseBmp, 100)) else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "Animated SVG requires frames"
                    )
                    return@launch
                }

                val uri = QrExporter.saveAnimatedSvg(getApplication(), matrix, exportDesign, frames)
                _state.value = _state.value.copy(
                    saveResult = if (uri != null) "Animated SVG saved to Downloads" else "Animated SVG export failed"
                )
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun share() {
        val content = _state.value.content.trim()
        if (content.isEmpty()) {
            _state.value = _state.value.copy(
                saveResult = "Content is required to share QR code",
                isExporting = false
            )
            return
        }
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true)
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign)
                }
                val bmp = if (renderResult is QrRenderResult.Success && renderResult.bitmap != null) {
                    renderResult.bitmap
                } else {
                    _state.value.bitmap
                }
                if (bmp != null) {
                    QrExporter.share(getApplication(), bmp)
                }
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun clearSaveResult() {
        _state.value = _state.value.copy(saveResult = null)
    }

    fun clearRepairNotice() {
        _state.value = _state.value.copy(repairNotice = null)
    }
}
