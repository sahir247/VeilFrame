package com.veilframe.app.qr.model

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
    val contrast: Float = 1.0f,
    val exposure: Float = 0.0f,
    val maskColor: Int = Color.BLACK,
    val maskAlpha: Float = 0.1f
) {
    val bitmap: Bitmap? get() = (source as? ImageSource.Memory)?.bitmap
}

data class BubbleClusterStyle(
    val seed: Long = 42L,
    val ambientBubbles: Boolean = true,
    val ambientDensity: Float = 0.15f,
    val ambientMaxRadius: Float = 0.22f,
    val crossOuterStrokeRatio: Float = 0.45f,
    val pairStrokeRatio: Float = 0.38f
)

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
    val scale: Float = 1.0f
)

data class AlignmentStyle(
    val shape: ModuleShape = ModuleShape.ROUNDED,
    val color: Int? = null,
    val scale: Float = 1.0f
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

data class LogoStyle(
    val bitmap: Bitmap? = null,
    val scaleFraction: Float = 0.20f,
    val paddingModules: Float = 0.5f,
    val backgroundMode: LogoBackgroundMode = LogoBackgroundMode.AUTO_CONTRAST,
    val customBackgroundColor: Int = Color.WHITE,
    val shape: LogoShape = LogoShape.SQUIRCLE
)

data class EffectStyle(
    val is25D: Boolean = false,
    val topColor: Int = Color.BLACK,
    val leftColor: Int = 0x33000000,
    val rightColor: Int = 0x99000000.toInt(),
    val dataHeightRatio: Float = 0.5f,
    val positionHeightRatio: Float = 0.75f,
    val seed: Long = 42L
)

enum class LineDirection {
    HORIZONTAL,
    VERTICAL,
    CROSS,
    X,
    DIAGONAL_FORWARD,
    DIAGONAL_BACKWARD,
    LOOP
}

data class LineStyle(
    val direction: LineDirection = LineDirection.HORIZONTAL,
    val thicknessFraction: Float = 0.5f,
    val lengthFraction: Float = 1.0f,
    val roundCaps: Boolean = true
)

data class DepthStyle(
    val depth: Float = 0.5f,
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
    val offsetJitter: Float = 0.15f,
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
    val quietZoneModules: Int = 4,
    val outputSize: Int = 512,
    val backgroundImage: Bitmap? = null,
    val backgroundImageAlpha: Float = 0.25f,
    val imageFillMode: Boolean = false,
    val style: QrStyle = QrStyle.BASIC,
    val timingColor: Int? = null,
    val alignmentColor: Int? = null,
    val timingStyle: TimingStyle = TimingStyle(color = timingColor),
    val alignmentStyle: AlignmentStyle = AlignmentStyle(color = alignmentColor),
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
    val backgroundLayer: BackgroundLayer = BackgroundLayer(
        color = palette.background,
        bitmap = backgroundImage,
        opacity = backgroundImageAlpha
    )
) {
    companion object {
        fun fromQrStyleParams(params: QrStyleParams): QrDesign {
            val def = QrStyleRegistry.get(params.style)
            val isRound = params.dataShape == com.veilframe.app.qr.ModuleShape.ROUND ||
                params.dataShape == com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE

            val moduleShape = if (params.style == QrStyle.BASIC && isRound) {
                ModuleShape.ROUNDED
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
                else -> ModuleShape.ROUNDED
            }

            val alignShape = when (params.alignShape) {
                com.veilframe.app.qr.ModuleShape.ROUND -> ModuleShape.CIRCLE
                com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE -> ModuleShape.ROUNDED
                else -> ModuleShape.ROUNDED
            }

            return QrDesign(
                correction = if (params.logo != null) ErrorCorrectionChoice.H else ErrorCorrectionChoice.AUTO,
                moduleStyle = ModuleStyle(
                    shape = moduleShape,
                    fill = moduleFill,
                    scale = params.dataScale.coerceIn(0.5f, 1.0f),
                    cornerRadiusFraction = if (isRound) 0.35f else 0.0f,
                    connected = params.style == QrStyle.DSJ
                ),
                eyeStyle = EyeStyle(
                    style = finderStyle,
                    outerColor = params.foreground,
                    innerColor = params.foreground
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
                        scaleFraction = params.logoFraction
                    )
                } else null,
                effects = EffectStyle(
                    is25D = is25D,
                    topColor = params.foreground,
                    leftColor = 0x33000000,
                    rightColor = 0x99000000.toInt()
                ),
                quietZoneModules = 4,
                outputSize = params.outputSize,
                backgroundImage = params.backgroundImage,
                backgroundImageAlpha = params.backgroundImageAlpha,
                imageFillMode = imageFillMode,
                style = params.style,
                timingColor = params.timingColor,
                alignmentColor = params.alignmentColor,
                timingStyle = TimingStyle(shape = timingShape, color = params.timingColor),
                alignmentStyle = AlignmentStyle(shape = alignShape, color = params.alignmentColor),
                lineStyle = LineStyle(
                    direction = LineDirection.HORIZONTAL,
                    thicknessFraction = 0.5f,
                    lengthFraction = 1.0f,
                    roundCaps = true
                ),
                depthStyle = DepthStyle(
                    depth = params.d25DataHeight,
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
                imageSource = if (params.backgroundImage != null && (params.style == QrStyle.IMAGE_RESAMPLE || imageFillMode)) {
                    ImageSourceStyle(source = ImageSource.Memory(params.backgroundImage))
                } else {
                    ImageSourceStyle()
                },
                clusterStyle = BubbleClusterStyle(seed = params.randomRectSeed),
                backgroundLayer = BackgroundLayer(
                    enabled = true,
                    color = params.background,
                    bitmap = params.backgroundImage,
                    opacity = params.backgroundImageAlpha
                )
            )
        }
    }
}
