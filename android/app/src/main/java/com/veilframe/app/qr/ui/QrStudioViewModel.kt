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
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.exporter.QrExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.renderer.RngMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
        val imageAllowTransparent: Boolean = false,
        val imageDataScale: Float = 1.0f,
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
        val vcardUrl: String = "",
        val emailRecipient: String = "",
        val emailSubject: String = "",
        val emailBody: String = "",
        val smsPhone: String = "",
        val smsBody: String = "",
        val upiVpa: String = "",
        val upiAmount: String = "",
        val upiPayeeName: String = "",
        val upiNote: String = "",

        // Geometry & Zone Shapes / Colors
        val dataShape: ModuleShape = ModuleShape.SQUARE,
        val dataScale: Float = 1.0f,
        val finderStyle: FinderStyle = FinderStyle.CLASSIC,
        val finderOuterColor: Int? = null,
        val finderInnerColor: Int? = null,
        val timingShape: ModuleShape = ModuleShape.ROUNDED,
        val timingColor: Int? = null,
        val alignShape: ModuleShape = ModuleShape.ROUNDED,
        val alignmentColor: Int? = null,

        // Style-Specific Customization Parameters
        val d25Depth: Float = 1.0f,
        val d25PositionDepth: Float = 1.0f,
        val d25Angle: Float = 45f,
        val d25TopColor: Int? = null,
        val d25LeftColor: Int = 0x33000000,
        val d25RightColor: Int = 0x99000000.toInt(),
        val lineDirection: LineDirection = LineDirection.X,
        val lineThickness: Float = 0.5f,
        val lineVariant: LineVariant = LineVariant.EF,
        val lineLengthFraction: Float = 1.0f,
        val lineColor: Int? = null,
        val lineHorizontalColor: Int? = null,
        val lineVerticalColor: Int? = null,
        val lineAccentRings: Boolean = false,
        val lineCircuitBridges: Boolean = false,
        val dsjLineSize: Float = 0.7f,
        val dsjXSize: Float = 0.7f,
        val dsjHorizontalColor: Int = 0xFFF6B506.toInt(),
        val dsjVerticalColor: Int = 0xFFE02020.toInt(),
        val dsjXColor: Int = 0xFF0B2D97.toInt(),
        val randomRectColor: Int? = null,
        val randomRectSeed: Long = 42L,
        val randomJitterScale: Float = 0.25f,
        val randomJitterOffset: Float = 0.0f,
        val randomJitterColor: Float = 0.1f,
        val bubbleAmbient: Boolean = true,
        val bubbleDensity: Float = 0.15f,
        val bubbleOutlineColor: Int? = null,
        val bubbleCenterColor: Int? = null,
        val bubblePositionColor: Int? = null,
        val veilFunctionType: VeilFunctionType = VeilFunctionType.FADE,
        val veilFunctionDataStyle: VeilFunctionDataStyle = VeilFunctionDataStyle.ROUND,
        val functionDataColor: Int? = null,
        val functionCircleColor: Int? = null,
        val paramFunctionType: FunctionType = FunctionType.WAVE,
        val styleFunctionFrequency: Float = 0.5f,
        val styleFunctionAmplitude: Float = 0.25f,
        val connectedLineThickness: Float = 0.2f,
        val imageFillBackgroundColor: Int = Color.WHITE,
        val imageFillMaskColor: Int = 0x1A000000,
        val imageDataDarkColor: Int = Color.BLACK,
        val imageDataLightColor: Int = Color.WHITE,
        val imagePositionDarkColor: Int = Color.BLACK,
        val imagePositionLightColor: Int = Color.WHITE,
        val imagePositionSize: Float = 1.0f,
        val imageTimingDarkColor: Int = Color.BLACK,
        val imageTimingLightColor: Int = Color.WHITE,
        val imageTimingSize: Float = 1.0f,
        val imageAlignDarkColor: Int = Color.BLACK,
        val imageAlignLightColor: Int = Color.WHITE,
        val imageAlignSize: Float = 1.0f,
        val resampleBackdropScaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
        val resampleBackdropTint: Int? = null,
        val resampleBackdropCornerRadius: Float = 0.0f,
        val resampleRngMode: RngMode = RngMode.SYSTEM_UNSEEDED,
        val backdropColor: Int? = null,
        val backdropCornerRadius: Float = 0.0f,
        val backdropImage: Bitmap? = null,
        val backdropImageAlpha: Float = 1.0f,
        val backdropImageScaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
        val logoShape: LogoShape = LogoShape.SQUIRCLE,
        val logoAlpha: Float = 1.0f,
        val logoBorderWidth: Float = 0.0f,
        val logoBorderColor: Int? = null,
        val logoScaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
        val gradientStart: Int? = null,
        val gradientEnd: Int? = null,
        val gradientType: GradientType = GradientType.NONE,
        val directionalQuietZone: DirectionalInsets? = null,
        val fractionalQuietZone: FractionalInsets? = null,
        val quietZoneChoice: Int? = null,
        val useAsymmetricQuietZone: Boolean = false,
        val quietZoneLeft: Float = 4f,
        val quietZoneTop: Float = 4f,
        val quietZoneRight: Float = 4f,
        val quietZoneBottom: Float = 4f,
        val timingOnlyWhite: Boolean = false,
        val alignOnlyWhite: Boolean = false,
        val positionSize: Float = 1.0f,
        val timingSize: Float = 1.0f,
        val alignSize: Float = 1.0f,
        val resampleDataColor: Int? = null,
        val logoAnimatedFrames: List<Bitmap> = emptyList(),
        val logoFrameDelaysMs: List<Int> = emptyList(),
        val geometryPolicy: com.veilframe.app.qr.model.QrGeometryPolicy = com.veilframe.app.qr.model.QrGeometryPolicy.SafeProduction,
        val generationMode: GenerationMode = GenerationMode.PARITY_EF
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

    fun updateGeometryPolicy(policy: com.veilframe.app.qr.model.QrGeometryPolicy) {
        _state.value = _state.value.copy(geometryPolicy = policy)
        regenerate(debounceMs = 0)
    }

    fun updateGenerationMode(mode: GenerationMode) {
        _state.value = _state.value.copy(generationMode = mode)
        regenerate(debounceMs = 0)
    }

    fun updateStyle(style: QrStyle) {
        _state.value = _state.value.copy(
            style = style,
            repairNotice = null
        )
        regenerate(debounceMs = 0)
    }

    fun updateOutputSize(size: Int) {
        _state.value = _state.value.copy(outputSize = size.coerceIn(256, 4096))
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
        _state.value = _state.value.copy(
            logo = null,
            logoAnimatedFrames = emptyList(),
            logoFrameDelaysMs = emptyList(),
            repairNotice = null
        )
        regenerate(debounceMs = 0)
    }

    fun updateLogoFraction(fraction: Float) {
        _state.value = _state.value.copy(logoFraction = fraction.coerceIn(0.10f, 0.33f))
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

    fun updateImageAllowTransparent(enabled: Boolean) {
        _state.value = _state.value.copy(imageAllowTransparent = enabled, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateImageDataScale(scale: Float) {
        _state.value = _state.value.copy(imageDataScale = scale.coerceIn(0.05f, 1.0f), repairNotice = null)
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

    fun updateTimingOnlyWhite(onlyWhite: Boolean) {
        _state.value = _state.value.copy(timingOnlyWhite = onlyWhite, repairNotice = null)
        regenerate(debounceMs = 0)
    }

    fun updateAlignOnlyWhite(onlyWhite: Boolean) {
        _state.value = _state.value.copy(alignOnlyWhite = onlyWhite, repairNotice = null)
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
            _state.update {
                it.copy(
                    bitmap = null,
                    matrix = null,
                    design = null,
                    scanabilityReport = null,
                    isRenderingPreview = false,
                    errorMessage = null
                )
            }
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

            _state.update { it.copy(isRenderingPreview = true, errorMessage = null) }

            val design = customDesign ?: buildDesignFromState(_state.value)
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            val mode = s.generationMode
            val renderResult = QrGenerator.generateWithResult(effectiveContent, design, mode = mode)
            ensureActive()
            if (generation != renderGeneration.get()) return@launch

            withContext(Dispatchers.Main) {
                if (generation != renderGeneration.get()) return@withContext
                when (renderResult) {
                    is QrRenderResult.Success -> {
                        _state.update {
                            it.copy(
                                bitmap = renderResult.bitmap,
                                matrix = renderResult.matrix,
                                design = renderResult.design,
                                scanabilityReport = renderResult.report,
                                isRenderingPreview = false,
                                errorMessage = null
                            )
                        }
                    }
                    is QrRenderResult.Failure -> {
                        _state.update {
                            it.copy(
                                isRenderingPreview = false,
                                errorMessage = renderResult.error
                            )
                        }
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
            s.gradientStart != null && s.gradientEnd != null -> ModuleFill.LINEAR_GRADIENT
            else -> ModuleFill.SOLID
        }
        val moduleShape = when (s.style) {
            QrStyle.BUBBLE -> ModuleShape.BUBBLE_CLUSTER
            QrStyle.D25 -> ModuleShape.SQUARE
            QrStyle.LINE -> ModuleShape.LINE
            QrStyle.DSJ -> ModuleShape.CONNECTED
            QrStyle.CONNECTED_ORGANIC -> ModuleShape.ORGANIC
            else -> s.dataShape
        }

        // Staged resolution tiering (M-04):
        // - Interactive preview tier: clamped to minOf(size, 512) for smooth 60fps gesture rendering and low memory overhead.
        // - Production export tier: full user-selected resolution (256..4096px).
        // Geometric and subpixel structural isomorphism is guaranteed by QrGeometry module-relative calculations.
        val sanitizedSize = s.outputSize.coerceIn(256, 4096)
        val effectiveSize = if (isPreview) minOf(sanitizedSize, 512) else sanitizedSize

        val baseDesign = QrDesign(
            correction = s.ecChoice,
            moduleStyle = ModuleStyle(
                shape = moduleShape,
                fill = moduleFill,
                // EFQRCode parity: BASIC, D25, and IMAGE use 1.0 data-module scale (or user configured).
                // Other styles use 0.85 as VeilFrame's default.
                scale = when (s.style) {
                    QrStyle.D25 -> 1.0f
                    QrStyle.IMAGE -> s.imageDataScale
                    else -> s.dataScale
                },
                cornerRadiusFraction = if (moduleShape == ModuleShape.ROUNDED) 0.35f else 0.0f,
                connected = s.style == QrStyle.DSJ
            ),
            eyeStyle = EyeStyle(
                style = s.finderStyle,
                outerColor = s.finderOuterColor ?: s.foreground,
                innerColor = s.finderInnerColor ?: s.foreground
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
            logo = if (s.logo != null || s.logoAnimatedFrames.isNotEmpty()) {
                LogoStyle(
                    bitmap = s.logo ?: s.logoAnimatedFrames.firstOrNull(),
                    source = if (s.logoAnimatedFrames.isNotEmpty()) {
                        ImageSource.Animated(s.logoAnimatedFrames, s.logoFrameDelaysMs)
                    } else if (s.logo != null) {
                        ImageSource.Memory(s.logo)
                    } else null,
                    scaleFraction = s.logoFraction,
                    shape = s.logoShape,
                    borderColor = s.logoBorderColor,
                    borderWidth = s.logoBorderWidth,
                    alpha = s.logoAlpha,
                    scaleMode = s.logoScaleMode
                )
            } else null,
            effects = EffectStyle(
                is25D = def.is25D,
                topColor = s.d25TopColor ?: s.foreground,
                leftColor = s.d25LeftColor,
                rightColor = s.d25RightColor,
                dataHeightRatio = s.d25Depth,
                positionHeightRatio = s.d25PositionDepth
            ),
            depthStyle = DepthStyle(
                depth = s.d25Depth,
                positionDepth = s.d25PositionDepth,
                angleDegrees = s.d25Angle,
                topColor = s.d25TopColor ?: s.foreground,
                leftColor = s.d25LeftColor,
                rightColor = s.d25RightColor
            ),
            timingColor = s.timingColor,
            alignmentColor = s.alignmentColor,
            timingStyle = TimingStyle(
                shape = s.timingShape,
                color = s.timingColor,
                scale = s.timingSize,
                onlyWhite = s.timingOnlyWhite
            ),
            alignmentStyle = AlignmentStyle(
                shape = s.alignShape,
                color = s.alignmentColor,
                scale = s.alignSize,
                onlyWhite = s.alignOnlyWhite
            ),
            lineStyle = LineStyle(
                direction = s.lineDirection,
                thicknessFraction = s.lineThickness,
                lengthFraction = s.lineLengthFraction,
                color = s.lineColor ?: s.foreground,
                positionStyle = s.finderStyle,
                positionSize = s.positionSize,
                positionColor = s.finderOuterColor ?: s.finderInnerColor ?: s.foreground,
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
                dataColor = s.bubbleOutlineColor ?: (if (s.style == QrStyle.BUBBLE && s.foreground == Color.BLACK) 0xFF8ED1FC.toInt() else s.foreground),
                dataCenterColor = s.bubbleCenterColor ?: (if (s.style == QrStyle.BUBBLE && s.background == Color.WHITE) 0xFFFFFFFF.toInt() else s.background),
                positionColor = s.bubblePositionColor ?: (if (s.style == QrStyle.BUBBLE && s.foreground == Color.BLACK) 0xFF0693E3.toInt() else s.foreground)
            ),
            veilFunctionStyle = VeilFunctionStyle(
                functionType = s.veilFunctionType,
                dataStyle = s.veilFunctionDataStyle,
                dataColor = s.functionDataColor ?: s.foreground,
                circleColor = s.functionCircleColor ?: s.foreground
            ),
            functionStyle = FunctionStyle(
                type = s.paramFunctionType,
                frequency = s.styleFunctionFrequency,
                amplitude = s.styleFunctionAmplitude,
                seed = s.randomRectSeed
            ),
            compositeStyle = CompositePrimitiveStyle(
                primitives = listOf(ModulePrimitive.CROSS, ModulePrimitive.X),
                lineThickness = s.connectedLineThickness,
                crossScale = 1.0f
            ),
            directionalQuietZone = s.directionalQuietZone,
            quietZoneModules = s.quietZoneChoice ?: s.geometryPolicy.defaultQuietZoneModules(s.style),
            explicitQuietZone = s.quietZoneChoice,
            outputSize = effectiveSize,
            backgroundImage = s.backgroundImage,
            backgroundImageAlpha = s.backgroundImageAlpha,
            imageFillMode = def.imageFillMode,
            style = s.style,
            allowTransparent = s.imageAllowTransparent,
            // EFQRCode parity: IMAGE data scale defaults to 1.0 (or customized via UI), not hardcoded 0.33.
            imageDataScale = if (s.style == QrStyle.IMAGE) s.imageDataScale else 0.85f,
            dataColorDark = if (s.style == QrStyle.IMAGE_RESAMPLE && s.resampleDataColor != null) {
                s.resampleDataColor!!
            } else if (s.style == QrStyle.IMAGE) {
                s.imageDataDarkColor
            } else {
                s.foreground
            },
            dataColorLight = s.imageDataLightColor,
            positionDarkColor = s.imagePositionDarkColor,
            positionLightColor = s.imagePositionLightColor,
            positionSize = s.positionSize,
            timingDarkColor = s.imageTimingDarkColor,
            timingLightColor = s.imageTimingLightColor,
            timingSize = s.timingSize,
            alignDarkColor = s.imageAlignDarkColor,
            alignLightColor = s.imageAlignLightColor,
            alignSize = s.alignSize,
            imageFillBackgroundColor = s.imageFillBackgroundColor,
            imageFillMaskColor = s.imageFillMaskColor,
            randomRectColor = s.randomRectColor ?: (if (s.style == QrStyle.RANDOM_RECTANGLE && s.foreground == Color.BLACK) 0xFF14AA3C.toInt() else s.foreground),
            imageSource = ImageSourceStyle(
                source = if (s.sourceImage != null) ImageSource.Memory(s.sourceImage) else null,
                scaleMode = s.sourceImageScaleMode,
                opacity = s.sourceImageOpacity,
                contrast = s.sourceImageContrast,
                exposure = s.sourceImageExposure,
                allowTransparent = s.imageAllowTransparent
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
                backdropScaleMode = s.resampleBackdropScaleMode,
                backdropTint = s.resampleBackdropTint,
                backdropCornerRadius = s.resampleBackdropCornerRadius,
                rngMode = s.resampleRngMode
            ),
            backdropStyle = BackdropStyle(
                color = s.backdropColor,
                cornerRadius = s.backdropCornerRadius,
                image = s.backdropImage ?: s.resampleBackdropImage ?: s.backgroundImage,
                imageAlpha = s.backdropImageAlpha,
                imageScaleMode = s.backdropImageScaleMode,
                fractionalQuietZone = s.fractionalQuietZone
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

    fun updateVcardForm(first: String, last: String, phone: String, email: String, org: String, url: String = "") {
        _state.value = _state.value.copy(
            vcardFirst = first,
            vcardLast = last,
            vcardPhone = phone,
            vcardEmail = email,
            vcardOrg = org,
            vcardUrl = url
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

    fun updateUpiForm(vpa: String, amount: String, payeeName: String = "", note: String = "") {
        _state.value = _state.value.copy(
            upiVpa = vpa,
            upiAmount = amount,
            upiPayeeName = payeeName,
            upiNote = note
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
        _state.update { it.copy(lineThickness = thickness.coerceIn(0.1f, 1.0f)) }
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

    // --- Geometry & Module Shape / Scale Updaters ---

    fun updateDataShape(shape: ModuleShape) {
        _state.value = _state.value.copy(dataShape = shape)
        regenerate(debounceMs = 0)
    }

    fun updateDataScale(scale: Float) {
        _state.value = _state.value.copy(dataScale = scale.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateFinderStyle(style: FinderStyle) {
        _state.value = _state.value.copy(finderStyle = style)
        regenerate(debounceMs = 0)
    }

    fun updateFinderColors(outer: Int?, inner: Int?) {
        _state.value = _state.value.copy(finderOuterColor = outer, finderInnerColor = inner)
        regenerate(debounceMs = 0)
    }

    fun updateTimingShape(shape: ModuleShape) {
        _state.value = _state.value.copy(timingShape = shape)
        regenerate(debounceMs = 0)
    }

    fun updateTimingColor(color: Int?) {
        _state.value = _state.value.copy(timingColor = color)
        regenerate(debounceMs = 0)
    }

    fun updatePositionSize(size: Float) {
        _state.value = _state.value.copy(
            positionSize = size.coerceIn(0.2f, 2.0f),
            imagePositionSize = size.coerceIn(0.2f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateTimingSize(size: Float) {
        _state.value = _state.value.copy(
            timingSize = size.coerceIn(0.2f, 2.0f),
            imageTimingSize = size.coerceIn(0.2f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateAlignSize(size: Float) {
        _state.value = _state.value.copy(
            alignSize = size.coerceIn(0.2f, 2.0f),
            imageAlignSize = size.coerceIn(0.2f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateResampleDataColor(color: Int?) {
        _state.value = _state.value.copy(resampleDataColor = color)
        regenerate(debounceMs = 0)
    }

    fun updateAsymmetricQuietZone(enabled: Boolean, left: Float = 4f, top: Float = 4f, right: Float = 4f, bottom: Float = 4f) {
        if (!enabled) {
            _state.value = _state.value.copy(
                useAsymmetricQuietZone = false,
                directionalQuietZone = null,
                fractionalQuietZone = null
            )
        } else {
            _state.value = _state.value.copy(
                useAsymmetricQuietZone = true,
                quietZoneLeft = left,
                quietZoneTop = top,
                quietZoneRight = right,
                quietZoneBottom = bottom,
                directionalQuietZone = DirectionalInsets(left, top, right, bottom),
                fractionalQuietZone = FractionalInsets(left, top, right, bottom)
            )
        }
        regenerate(debounceMs = 120)
    }

    fun updateLogoAnimatedFrames(frames: List<Bitmap>, delays: List<Int> = emptyList()) {
        _state.value = _state.value.copy(
            logo = frames.firstOrNull(),
            logoAnimatedFrames = frames,
            logoFrameDelaysMs = delays,
            repairNotice = null
        )
        regenerate(debounceMs = 0)
    }

    fun saveJpeg() {
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
                val mode = _state.value.generationMode
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign, mode = mode)
                }
                val bmp = when (renderResult) {
                    is QrRenderResult.Success -> renderResult.bitmap
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "Save failed: ${renderResult.error}"
                        )
                        return@launch
                    }
                }
                if (bmp == null) {
                    _state.value = _state.value.copy(
                        saveResult = "Save failed: ${QrError.Rendering.BitmapAllocationFailed(exportDesign.outputSize, exportDesign.outputSize).description}"
                    )
                    return@launch
                }
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                    bmp,
                    exportDesign,
                    renderResult.matrix,
                    content
                )
                if (!report.isScanReady && !report.validationSkipped) {
                    _state.value = _state.value.copy(
                        saveResult = "Export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.saveBitmapTyped(getApplication(), bmp, Bitmap.CompressFormat.JPEG, 95)
                }
                when (exportResult) {
                    is QrOutputResult.Success -> {
                        _state.value = _state.value.copy(
                            saveResult = "JPEG saved to Gallery (${exportDesign.outputSize}x${exportDesign.outputSize})"
                        )
                    }
                    is QrOutputResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "Save failed: ${exportResult.error.description}"
                        )
                    }
                }
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun updateAlignShape(shape: ModuleShape) {
        _state.value = _state.value.copy(alignShape = shape)
        regenerate(debounceMs = 0)
    }

    fun updateAlignmentColor(color: Int?) {
        _state.value = _state.value.copy(alignmentColor = color)
        regenerate(debounceMs = 0)
    }

    // --- Style Colors & Parameter Updaters ---

    fun updateBubbleColors(outline: Int?, center: Int?, position: Int?) {
        _state.value = _state.value.copy(
            bubbleOutlineColor = outline,
            bubbleCenterColor = center,
            bubblePositionColor = position
        )
        regenerate(debounceMs = 0)
    }

    fun updateD25TopColor(color: Int?) {
        _state.value = _state.value.copy(d25TopColor = color)
        regenerate(debounceMs = 0)
    }

    fun updateLineColors(lineColor: Int?, hColor: Int? = null, vColor: Int? = null) {
        _state.value = _state.value.copy(
            lineColor = lineColor,
            lineHorizontalColor = hColor,
            lineVerticalColor = vColor
        )
        regenerate(debounceMs = 0)
    }

    fun updateLineAccentRings(enabled: Boolean) {
        _state.value = _state.value.copy(lineAccentRings = enabled)
        regenerate(debounceMs = 0)
    }

    fun updateLineCircuitBridges(enabled: Boolean) {
        _state.value = _state.value.copy(lineCircuitBridges = enabled)
        regenerate(debounceMs = 0)
    }

    fun updateRandomRectColor(color: Int?) {
        _state.value = _state.value.copy(randomRectColor = color)
        regenerate(debounceMs = 0)
    }

    fun updateRandomRectSeed(seed: Long) {
        _state.value = _state.value.copy(randomRectSeed = seed)
        regenerate(debounceMs = 0)
    }

    fun updateRandomJitterColor(color: Float) {
        _state.value = _state.value.copy(randomJitterColor = color.coerceIn(0f, 1f))
        regenerate(debounceMs = 120)
    }

    fun updateVeilFunctionDataStyle(dataStyle: VeilFunctionDataStyle) {
        _state.value = _state.value.copy(veilFunctionDataStyle = dataStyle)
        regenerate(debounceMs = 0)
    }

    fun updateFunctionColors(dataColor: Int?, circleColor: Int?) {
        _state.value = _state.value.copy(functionDataColor = dataColor, functionCircleColor = circleColor)
        regenerate(debounceMs = 0)
    }

    fun updateStyleFunctionParams(type: FunctionType, freq: Float, amp: Float) {
        _state.value = _state.value.copy(
            paramFunctionType = type,
            styleFunctionFrequency = freq.coerceIn(0.1f, 2.0f),
            styleFunctionAmplitude = amp.coerceIn(0.05f, 1.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateImageFillParams(bgColor: Int, maskColor: Int) {
        _state.value = _state.value.copy(imageFillBackgroundColor = bgColor, imageFillMaskColor = maskColor)
        regenerate(debounceMs = 0)
    }

    fun updateImageDataColors(darkColor: Int, lightColor: Int) {
        _state.value = _state.value.copy(imageDataDarkColor = darkColor, imageDataLightColor = lightColor)
        regenerate(debounceMs = 0)
    }

    fun updateImagePositionParams(darkColor: Int, lightColor: Int, size: Float) {
        _state.value = _state.value.copy(
            imagePositionDarkColor = darkColor,
            imagePositionLightColor = lightColor,
            imagePositionSize = size.coerceIn(0.5f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateImageTimingParams(darkColor: Int, lightColor: Int, size: Float) {
        _state.value = _state.value.copy(
            imageTimingDarkColor = darkColor,
            imageTimingLightColor = lightColor,
            imageTimingSize = size.coerceIn(0.5f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateImageAlignParams(darkColor: Int, lightColor: Int, size: Float) {
        _state.value = _state.value.copy(
            imageAlignDarkColor = darkColor,
            imageAlignLightColor = lightColor,
            imageAlignSize = size.coerceIn(0.5f, 2.0f)
        )
        regenerate(debounceMs = 120)
    }

    fun updateResampleAdvanced(
        scaleMode: ImageScaleMode,
        tint: Int?,
        cornerRadius: Float,
        rngMode: RngMode
    ) {
        _state.value = _state.value.copy(
            resampleBackdropScaleMode = scaleMode,
            resampleBackdropTint = tint,
            resampleBackdropCornerRadius = cornerRadius.coerceIn(0f, 64f),
            resampleRngMode = rngMode
        )
        regenerate(debounceMs = 0)
    }

    fun updateBackdrop(color: Int?, cornerRadius: Float, image: Bitmap?, alpha: Float, scaleMode: ImageScaleMode) {
        _state.value = _state.value.copy(
            backdropColor = color,
            backdropCornerRadius = cornerRadius.coerceIn(0f, 64f),
            backdropImage = image,
            backdropImageAlpha = alpha.coerceIn(0f, 1f),
            backdropImageScaleMode = scaleMode
        )
        regenerate(debounceMs = 0)
    }

    fun updateBackdropColor(color: Int?) {
        _state.value = _state.value.copy(backdropColor = color)
        regenerate(debounceMs = 0)
    }

    fun updateBackdropRadius(radius: Float) {
        _state.value = _state.value.copy(backdropCornerRadius = radius.coerceIn(0f, 64f))
        regenerate(debounceMs = 120)
    }

    fun updateBackdropImage(image: Bitmap?) {
        _state.value = _state.value.copy(backdropImage = image)
        regenerate(debounceMs = 0)
    }

    fun updateBackdropImageAlpha(alpha: Float) {
        _state.value = _state.value.copy(backdropImageAlpha = alpha.coerceIn(0f, 1f))
        regenerate(debounceMs = 120)
    }

    fun updateBackdropScaleMode(mode: ImageScaleMode) {
        _state.value = _state.value.copy(backdropImageScaleMode = mode)
        regenerate(debounceMs = 0)
    }

    fun updateResampleBackdropScaleMode(mode: ImageScaleMode) {
        _state.value = _state.value.copy(resampleBackdropScaleMode = mode)
        regenerate(debounceMs = 0)
    }

    fun updateResampleBackdropTint(tint: Int?) {
        _state.value = _state.value.copy(resampleBackdropTint = tint)
        regenerate(debounceMs = 0)
    }

    fun updateResampleBackdropRadius(radius: Float) {
        _state.value = _state.value.copy(resampleBackdropCornerRadius = radius.coerceIn(0f, 64f))
        regenerate(debounceMs = 120)
    }

    fun updateResampleRngMode(mode: RngMode) {
        _state.value = _state.value.copy(resampleRngMode = mode)
        regenerate(debounceMs = 0)
    }

    fun updateLogoShape(shape: LogoShape) {
        _state.value = _state.value.copy(logoShape = shape)
        regenerate(debounceMs = 0)
    }

    fun updateLogoAlpha(alpha: Float) {
        _state.value = _state.value.copy(logoAlpha = alpha.coerceIn(0.1f, 1.0f))
        regenerate(debounceMs = 120)
    }

    fun updateLogoBorderWidth(width: Float) {
        _state.value = _state.value.copy(logoBorderWidth = width.coerceIn(0f, 32f))
        regenerate(debounceMs = 120)
    }

    fun updateLogoBorderColor(color: Int?) {
        _state.value = _state.value.copy(logoBorderColor = color)
        regenerate(debounceMs = 0)
    }

    fun updateLogoScaleMode(mode: ImageScaleMode) {
        _state.value = _state.value.copy(logoScaleMode = mode)
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
                val mode = _state.value.generationMode
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign, mode = mode)
                }
                val bmp = when (renderResult) {
                    is QrRenderResult.Success -> renderResult.bitmap
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "Save failed: ${renderResult.error}"
                        )
                        return@launch
                    }
                }
                if (bmp == null) {
                    _state.value = _state.value.copy(
                        saveResult = "Save failed: ${QrError.Rendering.BitmapAllocationFailed(exportDesign.outputSize, exportDesign.outputSize).description}"
                    )
                    return@launch
                }
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                    bmp,
                    exportDesign,
                    renderResult.matrix,
                    content
                )
                if (!report.isScanReady && !report.validationSkipped) {
                    _state.value = _state.value.copy(
                        saveResult = "Export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.saveBitmapTyped(getApplication(), bmp, Bitmap.CompressFormat.PNG, 100)
                }
                when (exportResult) {
                    is QrOutputResult.Success -> {
                        _state.value = _state.value.copy(
                            saveResult = "PNG saved to Gallery (${exportDesign.outputSize}x${exportDesign.outputSize})"
                        )
                    }
                    is QrOutputResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "Save failed: ${exportResult.error.description}"
                        )
                    }
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
                val mode = _state.value.generationMode
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign, mode = mode)
                }
                val (bmp, matrix) = when (renderResult) {
                    is QrRenderResult.Success -> Pair(renderResult.bitmap, renderResult.matrix)
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "SVG export failed: ${renderResult.error}"
                        )
                        return@launch
                    }
                }
                if (bmp != null) {
                    val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                        bmp,
                        exportDesign,
                        matrix,
                        content
                    )
                    if (!report.isScanReady && !report.validationSkipped) {
                        _state.value = _state.value.copy(
                            saveResult = "SVG export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                            scanabilityReport = report
                        )
                        return@launch
                    }
                }
                val svgData = withContext(Dispatchers.Default) {
                    QrGenerator.generateSvg(matrix, exportDesign)
                }
                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.saveSvgStringTyped(getApplication(), svgData)
                }
                _state.value = _state.value.copy(
                    saveResult = if (exportResult.isSuccess) "Vector SVG saved to Downloads" else "SVG export failed: ${exportResult.errorOrNull()?.description}"
                )
            } finally {
                _state.value = _state.value.copy(isExporting = false)
            }
        }
    }

    fun savePdf() {
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
                val mode = _state.value.generationMode
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign, mode = mode)
                }
                val bmp = when (renderResult) {
                    is QrRenderResult.Success -> renderResult.bitmap
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "PDF export failed: ${renderResult.error}"
                        )
                        return@launch
                    }
                }
                if (bmp == null) {
                    _state.value = _state.value.copy(
                        saveResult = "PDF export failed: ${QrError.Rendering.BitmapAllocationFailed(exportDesign.outputSize, exportDesign.outputSize).description}"
                    )
                    return@launch
                }
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                    bmp,
                    exportDesign,
                    renderResult.matrix,
                    content
                )
                if (!report.isScanReady && !report.validationSkipped) {
                    _state.value = _state.value.copy(
                        saveResult = "Export rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.savePdfTyped(getApplication(), bmp)
                }
                when (exportResult) {
                    is QrOutputResult.Success -> {
                        _state.value = _state.value.copy(
                            saveResult = "Printable PDF saved to Downloads"
                        )
                    }
                    is QrOutputResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "PDF export failed: ${exportResult.error.description}"
                        )
                    }
                }
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
            _state.value = _state.value.copy(
                isExporting = true
            )
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage
                    if (baseBmp != null) listOf(QrFrame(baseBmp, 100)) else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "GIF export requires a photo, background image, or animated frames"
                    )
                    return@launch
                }

                val designWithFrames = if (exportDesign.imageSource.source !is ImageSource.Animated && frames.isNotEmpty()) {
                    exportDesign.copy(
                        imageSource = exportDesign.imageSource.copy(
                            source = ImageSource.Animated(frames.map { it.bitmap }, frames.map { it.durationMs })
                        )
                    )
                } else exportDesign

                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.exportTyped(getApplication(), content, designWithFrames, QrOutputFormat.Gif())
                }
                _state.value = _state.value.copy(
                    saveResult = if (exportResult.isSuccess) "Animated GIF saved to Gallery (${frames.size} frames)" else "GIF export failed: ${exportResult.errorOrNull()?.description}"
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
            _state.value = _state.value.copy(
                isExporting = true
            )
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage
                    if (baseBmp != null) {
                        (0 until 15).map { QrFrame(baseBmp, 66) }
                    } else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "Video export requires a photo, background image, or video frames"
                    )
                    return@launch
                }

                val designWithFrames = if (exportDesign.imageSource.source !is ImageSource.Animated && frames.isNotEmpty()) {
                    exportDesign.copy(
                        imageSource = exportDesign.imageSource.copy(
                            source = ImageSource.Animated(frames.map { it.bitmap }, frames.map { it.durationMs })
                        )
                    )
                } else exportDesign

                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.exportTyped(
                        getApplication(),
                        content,
                        designWithFrames,
                        QrOutputFormat.Video(fps = 15, container = QrOutputFormat.VideoContainer.MP4)
                    )
                }
                _state.value = _state.value.copy(
                    saveResult = if (exportResult.isSuccess) "MP4 Video saved to Movies" else "Video export failed: ${exportResult.errorOrNull()?.description}"
                )
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
            _state.value = _state.value.copy(
                isExporting = true
            )
            try {
                val exportDesign = buildDesignFromState(_state.value, isPreview = false)
                val frames = if (_state.value.animatedFrames.isNotEmpty()) {
                    _state.value.animatedFrames
                } else {
                    val baseBmp = _state.value.sourceImage ?: _state.value.backgroundImage
                    if (baseBmp != null) listOf(QrFrame(baseBmp, 100)) else emptyList()
                }

                if (frames.isEmpty()) {
                    _state.value = _state.value.copy(
                        saveResult = "Animated SVG requires frames"
                    )
                    return@launch
                }

                val designWithFrames = if (exportDesign.imageSource.source !is ImageSource.Animated && frames.isNotEmpty()) {
                    exportDesign.copy(
                        imageSource = exportDesign.imageSource.copy(
                            source = ImageSource.Animated(frames.map { it.bitmap }, frames.map { it.durationMs })
                        )
                    )
                } else exportDesign

                val exportResult = withContext(Dispatchers.IO) {
                    QrExporter.exportTyped(getApplication(), content, designWithFrames, QrOutputFormat.Svg)
                }
                _state.value = _state.value.copy(
                    saveResult = if (exportResult.isSuccess) "Animated SVG saved to Downloads" else "Animated SVG export failed: ${exportResult.errorOrNull()?.description}"
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
                val mode = _state.value.generationMode
                val renderResult = withContext(Dispatchers.Default) {
                    QrGenerator.generateWithResult(content, exportDesign, mode = mode)
                }
                val bmp = when (renderResult) {
                    is QrRenderResult.Success -> renderResult.bitmap
                    is QrRenderResult.Failure -> {
                        _state.value = _state.value.copy(
                            saveResult = "Share failed: ${renderResult.error}"
                        )
                        return@launch
                    }
                }
                if (bmp == null) {
                    _state.value = _state.value.copy(
                        saveResult = "Share failed: ${QrError.Rendering.BitmapAllocationFailed(exportDesign.outputSize, exportDesign.outputSize).description}"
                    )
                    return@launch
                }
                val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
                    bmp,
                    exportDesign,
                    renderResult.matrix,
                    content
                )
                if (!report.isScanReady && !report.validationSkipped) {
                    _state.value = _state.value.copy(
                        saveResult = "Share rejected: verification failed (${report.warnings.firstOrNull() ?: "Unreadable"})",
                        scanabilityReport = report
                    )
                    return@launch
                }
                QrExporter.share(getApplication(), bmp)
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
