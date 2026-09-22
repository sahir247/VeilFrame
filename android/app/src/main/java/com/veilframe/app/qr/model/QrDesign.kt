package com.veilframe.app.qr.model

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.QrStyleParams

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
    SQUIRCLE
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

sealed interface BackgroundStyle {
    data class Solid(val color: Int = Color.WHITE) : BackgroundStyle
    data class LinearGradient(val startColor: Int, val endColor: Int, val angleDegrees: Float = 45f) : BackgroundStyle
    data class RadialGradient(val centerColor: Int, val edgeColor: Int) : BackgroundStyle
    data class Image(val bitmap: Bitmap, val alpha: Float = 0.25f, val fitCenter: Boolean = true) : BackgroundStyle
    data object Transparent : BackgroundStyle
}

data class ModuleStyle(
    val shape: ModuleShape = ModuleShape.SQUARE,
    val scale: Float = 1.0f,
    val cornerRadiusFraction: Float = 0.25f,
    val connected: Boolean = false
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
    val imageFillMode: Boolean = false
) {
    companion object {
        fun fromQrStyleParams(params: QrStyleParams): QrDesign {
            val isRound = params.dataShape == com.veilframe.app.qr.ModuleShape.ROUND ||
                params.dataShape == com.veilframe.app.qr.ModuleShape.ROUNDED_RECTANGLE
            val moduleShape = when (params.style) {
                QrStyle.BASIC -> if (isRound) ModuleShape.ROUNDED else ModuleShape.SQUARE
                QrStyle.BUBBLE -> ModuleShape.CIRCLE
                QrStyle.LINE -> ModuleShape.LINE
                QrStyle.RANDOM_RECTANGLE -> ModuleShape.ORGANIC
                QrStyle.DSJ -> ModuleShape.CONNECTED
                QrStyle.D25 -> ModuleShape.SQUARE
                else -> ModuleShape.SQUARE
            }

            val finderStyle = when (params.style) {
                QrStyle.DSJ -> FinderStyle.DSJ
                QrStyle.BUBBLE -> FinderStyle.CIRCLE
                else -> if (isRound) FinderStyle.ROUNDED else FinderStyle.CLASSIC
            }

            val is25D = params.style == QrStyle.D25
            val hasGradient = params.gradientStart != null && params.gradientEnd != null

            return QrDesign(
                correction = if (params.logo != null) ErrorCorrectionChoice.H else ErrorCorrectionChoice.AUTO,
                moduleStyle = ModuleStyle(
                    shape = moduleShape,
                    scale = params.dataScale.coerceIn(0.5f, 1.0f),
                    cornerRadiusFraction = if (isRound) 0.35f else 0.0f
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
                imageFillMode = params.style == QrStyle.IMAGE_FILL || params.style == QrStyle.IMAGE
            )
        }
    }
}
