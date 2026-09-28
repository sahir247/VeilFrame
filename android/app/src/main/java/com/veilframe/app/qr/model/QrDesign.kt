package com.veilframe.app.qr.model

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.QrStyleParams
import com.veilframe.app.qr.registry.QrStyleRegistry

enum class ErrorCorrectionChoice {
    AUTO, L, M, Q, H;

    fun toZxingLevel(hasLogo: Boolean, isAggressiveStyle: Boolean = false): ErrorCorrectionLevel {
        return when (this) {
            L -> ErrorCorrectionLevel.L
            M -> ErrorCorrectionLevel.M
            Q -> ErrorCorrectionLevel.Q
            H -> ErrorCorrectionLevel.H
            AUTO -> {
                when {
                    hasLogo -> ErrorCorrectionLevel.H
                    isAggressiveStyle -> ErrorCorrectionLevel.Q
                    else -> ErrorCorrectionLevel.M
                }
            }
        }
    }
}

enum class ModuleShape {
    NONE,
    SQUARE,
    ROUNDED,
    CIRCLE,
    DOT,
    PILL,
    ORGANIC,
    CONNECTED,
    DIAMOND,
    HEX,
    LINE,
    SQUIRCLE,
    STAR,
    BUBBLE,
    BUBBLE_CLUSTER,
    CUSTOM
}

enum class ModuleFill {
    SOLID,
    LINEAR_GRADIENT,
    RADIAL_GRADIENT,
    SWEEP_GRADIENT,
    IMAGE,
    IMAGE_SAMPLED,
    IMAGE_MASKED,
    NOISE
}

enum class FinderStyle {
    CLASSIC,
    ROUNDED,
    CIRCLE,
    SOFT,
    FRAME,
    PLANETS,
    DSJ
}

enum class GradientType {
    NONE,
    LINEAR,
    RADIAL,
    SWEEP
}

enum class LogoBackgroundMode {
    NONE,
    AUTO_CONTRAST,
    FOREGROUND,
    BACKGROUND,
    CUSTOM
}

enum class LogoShape {
    SQUIRCLE,
    CIRCLE,
    SQUARE
}

sealed interface ImageSource {
    data class Uri(val value: String) : ImageSource
    data class Resource(val id: Int) : ImageSource
    data class Memory(val bitmap: Bitmap) : ImageSource
    data class Animated(val frames: List<Bitmap>, val delaysMs: List<Int> = emptyList()) : ImageSource
}

enum class ImageScaleMode {
    ASPECT_FILL,
    ASPECT_FIT,
    CENTER_CROP,
    STRETCH
}

enum class ImageMaskScope {
    DATA_ONLY,
    ALL_MODULES,
    CUSTOM
}

data class ImageSourceStyle(
    val source: ImageSource? = null,
    val scaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
    val scope: ImageMaskScope = ImageMaskScope.DATA_ONLY,
    val opacity: Float = 1.0f,
    val contrast: Float = 0.0f, // VeilFrame Art Engine default: 0.0f ((contrast + 1) = 1.0 multiplier)
    val exposure: Float = 0.0f,
    val maskColor: Int = 0x1A000000,
    val maskAlpha: Float = 0.1f,
    val allowTransparent: Boolean = false
) {
    val bitmap: Bitmap? get() = when (source) {
        is ImageSource.Memory -> source.bitmap
        is ImageSource.Animated -> source.frames.firstOrNull()
        else -> null
    }
    val animatedFrames: List<Bitmap>? get() = (source as? ImageSource.Animated)?.frames
    val frameDelaysMs: List<Int>? get() = (source as? ImageSource.Animated)?.delaysMs
    val isAnimated: Boolean get() = source is ImageSource.Animated
}

data class BubbleClusterStyle(
    val seed: Long = 42L,
    val ambientBubbles: Boolean = true,
    val ambientDensity: Float = 0.15f,
    val ambientMaxRadius: Float = 0.22f,
    val crossOuterStrokeRatio: Float = 0.45f,
    val pairStrokeRatio: Float = 0.38f,
    /** EF parity: data module outline color. Default: light blue 0x8ED1FC. */
    val dataColor: Int = 0xFF8ED1FC.toInt(),
    /** EF parity: data module center color. Default: white. */
    val dataCenterColor: Int = 0xFFFFFFFF.toInt(),
    /** EF parity: position detection pattern color. Default: blue 0x0693E3. */
    val positionColor: Int = 0xFF0693E3.toInt()
)

enum class BackdropBlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY
}

data class ResampleStyle(
    val seed: Long = 42L,
    val useSourceAsBackdrop: Boolean = false,
    val backdropBitmap: Bitmap? = null,
    val backdropOpacity: Float = 1.0f,
    val backdropScaleMode: ImageScaleMode = ImageScaleMode.ASPECT_FILL,
    val backdropBlendMode: BackdropBlendMode = BackdropBlendMode.NORMAL,
    val backdropTint: Int? = null,
    val rngMode: com.veilframe.app.qr.renderer.ResampleRngMode = com.veilframe.app.qr.renderer.ResampleRngMode.SYSTEM_UNSEEDED
) {
    /** True if a backdrop image should be rendered (either via an independent backdropBitmap or by reusing sourceImage). */
    val hasBackdrop: Boolean get() = backdropBitmap != null || useSourceAsBackdrop
}

sealed interface BackgroundStyle {
    data class Solid(val color: Int = Color.WHITE) : BackgroundStyle
    data class LinearGradient(val startColor: Int, val endColor: Int, val angleDegrees: Float = 45f) : BackgroundStyle
    data class RadialGradient(val centerColor: Int, val edgeColor: Int) : BackgroundStyle
    data class Image(val bitmap: Bitmap, val alpha: Float = 0.25f, val fitCenter: Boolean = true) : BackgroundStyle
    data object Transparent : BackgroundStyle
}

data class ModuleStyle(
    val shape: ModuleShape = ModuleShape.SQUARE,
    val fill: ModuleFill = ModuleFill.SOLID,
    val scale: Float = 1.0f,
    val cornerRadiusFraction: Float = 0.25f,
    val connected: Boolean = false
)

data class TimingStyle(
    val shape: ModuleShape = ModuleShape.ROUNDED,
    val color: Int? = null,
    val scale: Float = 1.0f,
    val onlyWhite: Boolean = false
)

data class AlignmentStyle(
    val shape: ModuleShape = ModuleShape.ROUNDED,
    val color: Int? = null,
    val scale: Float = 1.0f,
    val onlyWhite: Boolean = false
)

data class EyeStyle(
    val style: FinderStyle = FinderStyle.CLASSIC,
    val outerColor: Int? = null,
    val innerColor: Int? = null
)

data class PaletteStyle(
    val foreground: Int = Color.BLACK,
    val background: Int = Color.WHITE,
    val gradientStart: Int? = null,
    val gradientEnd: Int? = null,
    val gradientType: GradientType = GradientType.NONE
)

data class DirectionalInsets(
    val left: Int = 4,
    val top: Int = 4,
    val right: Int = 4,
    val bottom: Int = 4
)

data class LogoStyle(
    val bitmap: Bitmap? = null,
    val scaleFraction: Float = 0.20f,
    val paddingModules: Float = 0.5f,
    val backgroundMode: LogoBackgroundMode = LogoBackgroundMode.AUTO_CONTRAST,
    val customBackgroundColor: Int = Color.WHITE,
    val shape: LogoShape = LogoShape.SQUIRCLE,
    val borderColor: Int? = null,
    val borderWidth: Float = 0f
)

data class EffectStyle(
    val is25D: Boolean = false,
    val topColor: Int = Color.BLACK,
    val leftColor: Int = 0x33000000,
    val rightColor: Int = 0x99000000.toInt(),
    val dataHeightRatio: Float = 1.0f,
    val positionHeightRatio: Float = 1.0f,
    val seed: Long = 42L
)

enum class LineDirection {
    HORIZONTAL,
    VERTICAL,
    CROSS,
    LOOPBACK,
    TOP_LEFT_TO_BOTTOM_RIGHT,
    TOP_RIGHT_TO_BOTTOM_LEFT,
    X,
    DIAGONAL_FORWARD, // Alias for TOP_LEFT_TO_BOTTOM_RIGHT
    DIAGONAL_BACKWARD, // Alias for TOP_RIGHT_TO_BOTTOM_LEFT
    LOOP // Alias for LOOPBACK
}

enum class LineVariant {
    EF,
    CIRCUIT
}

data class LineStyle(
    val direction: LineDirection = LineDirection.X,
    val thicknessFraction: Float = 0.5f,
    val lengthFraction: Float = 1.0f,
    val roundCaps: Boolean = true,
    val color: Int? = null,
    val positionStyle: FinderStyle = FinderStyle.CLASSIC,
    val positionSize: Float = 1.0f,
    val positionColor: Int? = null,
    val accentRingsEnabled: Boolean = false,
    val circuitBridgesEnabled: Boolean = false,
    val variant: LineVariant = LineVariant.EF,
    val randomSource: (() -> Float)? = null
)

enum class VeilFunctionType {
    FADE,
    CIRCLE
}

enum class VeilFunctionDataStyle {
    ROUND,
    RECTANGLE
}

data class VeilFunctionStyle(
    val functionType: VeilFunctionType = VeilFunctionType.FADE,
    val dataStyle: VeilFunctionDataStyle = VeilFunctionDataStyle.ROUND,
    val dataColor: Int = Color.BLACK,
    val circleColor: Int = Color.BLACK
)

data class VeilDsjStyle(
    val lineSize: Float = 0.7f,
    val xSize: Float = 0.7f,
    val horizontalLineColor: Int = 0xFFF6B506.toInt(),
    val verticalLineColor: Int = 0xFFE02020.toInt(),
    val xColor: Int = 0xFF0B2D97.toInt()
)

data class DepthStyle(
    val depth: Float = 1.0f,
    val positionDepth: Float = 1.0f,
    val angleDegrees: Float = 45f,
    val topColor: Int = Color.BLACK,
    val leftColor: Int = 0x33000000,
    val rightColor: Int = 0x99000000.toInt()
)

enum class FunctionType {
    WAVE,
    RADIAL,
    RIPPLE,
    NOISE,
    SPIRAL,
    CHECKER,
    ORGANIC,
    RANDOM
}

data class FunctionStyle(
    val type: FunctionType = FunctionType.WAVE,
    val frequency: Float = 0.5f,
    val amplitude: Float = 0.25f,
    val phase: Float = 0f,
    val scale: Float = 1.0f,
    val seed: Long = 42L,
    val rotationDegrees: Float = 0f
)

data class RandomJitterStyle(
    val seed: Long = 42L,
    val scaleJitter: Float = 0.25f,
    /** EF parity: 0.0 (no positional jitter). Non-zero values are a VeilFrame extension. */
    val offsetJitter: Float = 0.0f,
    val colorJitter: Float = 0.1f
)

enum class ModulePrimitive {
    RECT,
    CIRCLE,
    LINE,
    CROSS,
    X,
    POLYGON,
    PATH
}

data class CompositePrimitiveStyle(
    val primitives: List<ModulePrimitive> = listOf(ModulePrimitive.CROSS, ModulePrimitive.X),
    val lineThickness: Float = 0.2f,
    val crossScale: Float = 1.0f
)

data class BackgroundLayer(
    val enabled: Boolean = true,
    val color: Int = Color.WHITE,
    val bitmap: Bitmap? = null,
    val opacity: Float = 1.0f,
    val scale: Float = 1.0f,
    val blurRadius: Float = 0f,
    val tintColor: Int? = null
)

/**
 * Domain specification of complete QR visual design intent.
 */
data class QrDesign(
    val correction: ErrorCorrectionChoice = ErrorCorrectionChoice.AUTO,
    val moduleStyle: ModuleStyle = ModuleStyle(),
    val eyeStyle: EyeStyle = EyeStyle(),
    val palette: PaletteStyle = PaletteStyle(),
    val background: BackgroundStyle = BackgroundStyle.Solid(Color.WHITE),
    val logo: LogoStyle? = null,
    val effects: EffectStyle = EffectStyle(),
    val quietZoneModules: Int = 1,
    val explicitQuietZone: Int? = null,
    val outputSize: Int = 512,
    val backgroundImage: Bitmap? = null,
    val backgroundImageAlpha: Float = 0.25f,
    val imageFillMode: Boolean = false,
    val style: QrStyle = QrStyle.BASIC,
    val timingColor: Int? = null,
    val alignmentColor: Int? = null,
    val timingStyle: TimingStyle = if (style == QrStyle.IMAGE_RESAMPLE || style == QrStyle.IMAGE) TimingStyle(shape = ModuleShape.SQUARE, color = timingColor) else TimingStyle(color = timingColor),
    val alignmentStyle: AlignmentStyle = if (style == QrStyle.IMAGE_RESAMPLE || style == QrStyle.IMAGE) AlignmentStyle(shape = ModuleShape.SQUARE, color = alignmentColor) else AlignmentStyle(color = alignmentColor),
    val lineStyle: LineStyle = LineStyle(),
    val depthStyle: DepthStyle = DepthStyle(
        depth = effects.dataHeightRatio,
        topColor = effects.topColor,
        leftColor = effects.leftColor,
        rightColor = effects.rightColor
    ),
    val functionStyle: FunctionStyle = FunctionStyle(seed = effects.seed),
    val jitterStyle: RandomJitterStyle = RandomJitterStyle(seed = effects.seed),
    val compositeStyle: CompositePrimitiveStyle = CompositePrimitiveStyle(),
    val imageSource: ImageSourceStyle = ImageSourceStyle(),
    val clusterStyle: BubbleClusterStyle = BubbleClusterStyle(),
    val allowTransparent: Boolean = false,
    val imageDataScale: Float? = null,
    val dataColorDark: Int = Color.BLACK,
    val dataColorLight: Int = Color.WHITE,
    val positionDarkColor: Int = Color.BLACK,
    val positionLightColor: Int = Color.WHITE,
    val positionSize: Float = 1.0f,
    val timingDarkColor: Int = Color.BLACK,
    val timingLightColor: Int = Color.WHITE,
    val timingSize: Float = 1.0f,
    val alignDarkColor: Int = Color.BLACK,
    val alignLightColor: Int = Color.WHITE,
    val alignSize: Float = 1.0f,
    val imageFillBackgroundColor: Int = Color.WHITE,
    val imageFillMaskColor: Int = 0x1A000000,
    val veilDsjStyle: VeilDsjStyle = VeilDsjStyle(),
    val veilFunctionStyle: VeilFunctionStyle = VeilFunctionStyle(),
    val randomRectColor: Int = 0xFF14AA3C.toInt(),
    val backgroundLayer: BackgroundLayer = BackgroundLayer(
        color = palette.background,
        bitmap = backgroundImage,
        opacity = backgroundImageAlpha
    ),
    val resampleStyle: ResampleStyle = ResampleStyle(),
    val directionalQuietZone: DirectionalInsets? = null
) {
    val effectiveQuietZone: Int get() = QrGeometry.resolveQuietZone(this)
    val effectiveQuietZoneLeft: Int get() = directionalQuietZone?.left ?: effectiveQuietZone
    val effectiveQuietZoneTop: Int get() = directionalQuietZone?.top ?: effectiveQuietZone
    val effectiveQuietZoneRight: Int get() = directionalQuietZone?.right ?: effectiveQuietZone
    val effectiveQuietZoneBottom: Int get() = directionalQuietZone?.bottom ?: effectiveQuietZone
    val recommendedGenerationMode: GenerationMode get() = if (style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE

    companion object {
        fun fromQrStyleParams(params: QrStyleParams): QrDesign {
            val def = QrStyleRegistry.get(params.style)
            val isRound = params.dataShape == com.veilframe.app.qr.ModuleShape.ROUND ||
                params.dataShape == com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE

            val moduleShape = if (params.style == QrStyle.BASIC) {
                when (params.dataShape) {
                    com.veilframe.app.qr.ModuleShape.ROUND -> ModuleShape.ROUNDED
                    com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE -> ModuleShape.ROUNDED
                    com.veilframe.app.qr.ModuleShape.RANDOM_ROUND -> ModuleShape.ORGANIC
                    else -> def.defaultModuleShape
                }
            } else {
                def.defaultModuleShape
            }

            val finderStyle = if (params.style == QrStyle.BASIC && isRound) {
                FinderStyle.ROUNDED
            } else {
                def.defaultFinderStyle
            }

            val is25D = def.is25D
            val imageFillMode = def.imageFillMode
            val hasGradient = params.gradientStart != null && params.gradientEnd != null

            val moduleFill = when {
                params.style == QrStyle.IMAGE_RESAMPLE -> ModuleFill.IMAGE_SAMPLED
                imageFillMode -> ModuleFill.IMAGE
                hasGradient -> ModuleFill.LINEAR_GRADIENT
                else -> ModuleFill.SOLID
            }

            val timingShape = when (params.timingShape) {
                com.veilframe.app.qr.ModuleShape.ROUND -> ModuleShape.CIRCLE
                com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE -> ModuleShape.ROUNDED
                else -> if (params.style == QrStyle.IMAGE_RESAMPLE || params.style == QrStyle.IMAGE) ModuleShape.SQUARE else ModuleShape.ROUNDED
            }

            val alignShape = when (params.alignShape) {
                com.veilframe.app.qr.ModuleShape.ROUND -> ModuleShape.CIRCLE
                com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE -> ModuleShape.ROUNDED
                else -> if (params.style == QrStyle.IMAGE_RESAMPLE || params.style == QrStyle.IMAGE) ModuleShape.SQUARE else ModuleShape.ROUNDED
            }

            val resolvedSourceImage = params.sourceImage

            val directionalQuietZone = if (params.quietZoneLeft != null || params.quietZoneTop != null ||
                params.quietZoneRight != null || params.quietZoneBottom != null) {
                val defaultQz = QrGeometry.resolveDefaultQuietZone(params.style)
                val base = params.quietZone ?: defaultQz
                DirectionalInsets(
                    left = params.quietZoneLeft ?: base,
                    top = params.quietZoneTop ?: base,
                    right = params.quietZoneRight ?: base,
                    bottom = params.quietZoneBottom ?: base
                )
            } else null

            val moduleScale = when (params.style) {
                QrStyle.D25 -> 1.0f
                QrStyle.IMAGE -> params.imageDataScale.coerceIn(0.05f, 1.0f)
                else -> params.dataScale.coerceIn(0.1f, 1.0f)
            }

            return QrDesign(
                correction = if (params.logo != null) ErrorCorrectionChoice.H else ErrorCorrectionChoice.AUTO,
                moduleStyle = ModuleStyle(
                    shape = moduleShape,
                    fill = moduleFill,
                    scale = moduleScale,
                    cornerRadiusFraction = if (isRound) 0.35f else 0.0f,
                    connected = params.style == QrStyle.DSJ
                ),
                eyeStyle = EyeStyle(
                    style = finderStyle,
                    outerColor = params.positionColor ?: params.foreground,
                    innerColor = params.positionColor ?: params.foreground
                ),
                palette = PaletteStyle(
                    foreground = params.foreground,
                    background = params.background,
                    gradientStart = params.gradientStart,
                    gradientEnd = params.gradientEnd,
                    gradientType = if (hasGradient) GradientType.LINEAR else GradientType.NONE
                ),
                background = if (params.backgroundImage != null) {
                    BackgroundStyle.Image(params.backgroundImage, params.backgroundImageAlpha)
                } else {
                    BackgroundStyle.Solid(params.background)
                },
                logo = if (params.logo != null) {
                    LogoStyle(
                        bitmap = params.logo,
                        scaleFraction = params.logoFraction.coerceIn(0.10f, 0.35f),
                        shape = params.logoShape,
                        borderColor = params.logoBorderColor,
                        borderWidth = params.logoBorderWidth
                    )
                } else null,
                effects = EffectStyle(
                    is25D = is25D,
                    topColor = params.d25TopColor,
                    leftColor = params.d25LeftColor,
                    rightColor = params.d25RightColor,
                    dataHeightRatio = params.d25DataHeight,
                    positionHeightRatio = params.d25PositionHeight
                ),
                quietZoneModules = params.quietZone ?: QrGeometry.resolveDefaultQuietZone(params.style),
                explicitQuietZone = params.quietZone,
                directionalQuietZone = directionalQuietZone,
                outputSize = params.outputSize,
                backgroundImage = params.backgroundImage,
                backgroundImageAlpha = params.backgroundImageAlpha,
                randomRectColor = params.randomRectColor ?: if (params.style == QrStyle.RANDOM_RECTANGLE && params.foreground == Color.BLACK) 0xFF14AA3C.toInt() else params.foreground,
                imageFillMode = imageFillMode,
                style = params.style,
                timingColor = params.timingColor,
                alignmentColor = params.alignmentColor,
                timingStyle = TimingStyle(shape = timingShape, color = params.timingColor, onlyWhite = params.timingOnlyWhite),
                alignmentStyle = AlignmentStyle(shape = alignShape, color = params.alignmentColor, onlyWhite = params.alignOnlyWhite),
                lineStyle = LineStyle(
                    direction = params.lineDirection,
                    thicknessFraction = params.lineThickness,
                    lengthFraction = 1.0f,
                    roundCaps = true,
                    color = params.lineColor ?: params.foreground,
                    positionStyle = finderStyle,
                    positionSize = params.imagePositionSize,
                    positionColor = params.positionColor ?: params.foreground,
                    accentRingsEnabled = params.lineAccentRingsEnabled,
                    circuitBridgesEnabled = params.lineCircuitBridgesEnabled,
                    variant = params.lineVariant,
                    randomSource = params.lineRandomSource
                ),
                veilDsjStyle = VeilDsjStyle(
                    lineSize = params.dsjLineSize,
                    xSize = params.dsjXSize,
                    horizontalLineColor = params.dsjHorizontalLineColor,
                    verticalLineColor = params.dsjVerticalLineColor,
                    xColor = params.dsjXColor
                ),
                veilFunctionStyle = VeilFunctionStyle(
                    functionType = params.functionType,
                    dataStyle = params.functionDataStyle,
                    dataColor = params.functionDataColor,
                    circleColor = params.functionCircleColor
                ),
                depthStyle = DepthStyle(
                    depth = params.d25DataHeight,
                    positionDepth = params.d25PositionHeight,
                    angleDegrees = 45f,
                    topColor = params.d25TopColor,
                    leftColor = params.d25LeftColor,
                    rightColor = params.d25RightColor
                ),
                functionStyle = FunctionStyle(
                    type = FunctionType.WAVE,
                    seed = params.randomRectSeed
                ),
                jitterStyle = RandomJitterStyle(
                    seed = params.randomRectSeed
                ),
                compositeStyle = CompositePrimitiveStyle(
                    primitives = if (params.style == QrStyle.DSJ) listOf(ModulePrimitive.LINE, ModulePrimitive.CROSS, ModulePrimitive.X)
                                 else listOf(ModulePrimitive.CROSS, ModulePrimitive.X)
                ),
                imageSource = if (params.sourceImageAnimatedFrames != null) {
                    ImageSourceStyle(
                        source = ImageSource.Animated(params.sourceImageAnimatedFrames, params.sourceImageFrameDelaysMs ?: emptyList()),
                        opacity = params.sourceImageAlpha,
                        scaleMode = params.imageScaleMode,
                        contrast = 0.0f,
                        exposure = 0.0f,
                        maskColor = params.imageFillMaskColor,
                        allowTransparent = params.imageAllowTransparent
                    )
                } else if (resolvedSourceImage != null) {
                    ImageSourceStyle(
                        source = ImageSource.Memory(resolvedSourceImage),
                        opacity = params.sourceImageAlpha,
                        scaleMode = params.imageScaleMode,
                        contrast = 0.0f,
                        exposure = 0.0f,
                        maskColor = params.imageFillMaskColor,
                        allowTransparent = params.imageAllowTransparent
                    )
                } else {
                    ImageSourceStyle(
                        scaleMode = params.imageScaleMode,
                        contrast = 0.0f,
                        exposure = 0.0f,
                        maskColor = params.imageFillMaskColor,
                        allowTransparent = params.imageAllowTransparent
                    )
                },
                clusterStyle = BubbleClusterStyle(
                    seed = params.randomRectSeed,
                    dataColor = if (params.style == QrStyle.BUBBLE && params.foreground == android.graphics.Color.BLACK) params.bubbleOutlineColor else params.foreground,
                    dataCenterColor = params.bubbleCenterColor,
                    positionColor = if (params.style == QrStyle.BUBBLE && params.positionColor == null && params.foreground == android.graphics.Color.BLACK) params.bubblePositionColor else (params.positionColor ?: params.foreground)
                ),
                allowTransparent = params.imageAllowTransparent,
                imageDataScale = if (params.style == QrStyle.IMAGE) params.imageDataScale.coerceIn(0.05f, 1.0f) else null,
                dataColorDark = params.dataColor ?: params.imageDataDarkColor,
                dataColorLight = params.imageDataLightColor,
                positionDarkColor = params.positionColor ?: params.imagePositionDarkColor,
                positionLightColor = params.imagePositionLightColor,
                positionSize = params.imagePositionSize,
                timingDarkColor = params.timingColor ?: params.imageTimingDarkColor,
                timingLightColor = params.imageTimingLightColor,
                timingSize = params.timingSize ?: params.imageTimingSize,
                alignDarkColor = params.alignmentColor ?: params.imageAlignDarkColor,
                alignLightColor = params.imageAlignLightColor,
                alignSize = params.alignSize ?: params.imageAlignSize,
                imageFillBackgroundColor = params.imageFillBackgroundColor,
                imageFillMaskColor = params.imageFillMaskColor,
                backgroundLayer = BackgroundLayer(
                    enabled = params.backgroundImage != null,
                    color = params.background,
                    bitmap = params.backgroundImage,
                    opacity = params.backgroundImageAlpha
                ),
                resampleStyle = ResampleStyle(
                    seed = params.resampleSeed,
                    useSourceAsBackdrop = params.resampleUseSourceAsBackdrop,
                    backdropBitmap = params.resampleBackdropImage,
                    backdropOpacity = params.resampleBackdropOpacity,
                    backdropScaleMode = params.resampleBackdropScaleMode,
                    backdropTint = params.resampleBackdropTint,
                    rngMode = params.resampleRngMode
                )
            )
        }
    }
}
