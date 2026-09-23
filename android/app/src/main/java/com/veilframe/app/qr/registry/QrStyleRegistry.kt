package com.veilframe.app.qr.registry

import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.renderer.*

/**
 * Authoritative specification of a QR visual style, binding renderer implementation,
 * default module/finder geometry, and aesthetic capabilities.
 */
data class QrStyleDefinition(
    val style: QrStyle,
    val displayName: String,
    val defaultModuleShape: ModuleShape,
    val defaultFinderStyle: FinderStyle,
    val is25D: Boolean = false,
    val imageFillMode: Boolean = false,
    val requiresBackgroundImage: Boolean = false,
    val supportsGradients: Boolean = true,
    val rendererFactory: () -> QrRenderer
)

/**
 * Single source of truth unifying the 12 [QrStyle] enum members with their
 * canonical renderers, shape models, and capabilities.
 */
object QrStyleRegistry {

    private val definitions: Map<QrStyle, QrStyleDefinition> = mapOf(
        QrStyle.BASIC to QrStyleDefinition(
            style = QrStyle.BASIC,
            displayName = "Basic",
            defaultModuleShape = ModuleShape.SQUARE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            rendererFactory = { BasicRenderer() }
        ),
        QrStyle.BUBBLE to QrStyleDefinition(
            style = QrStyle.BUBBLE,
            displayName = "Bubble",
            defaultModuleShape = ModuleShape.CIRCLE,
            defaultFinderStyle = FinderStyle.CIRCLE,
            rendererFactory = { BubbleRenderer() }
        ),
        QrStyle.D25 to QrStyleDefinition(
            style = QrStyle.D25,
            displayName = "2.5D Isometric",
            defaultModuleShape = ModuleShape.SQUARE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            is25D = true,
            rendererFactory = { Renderer25D() }
        ),
        QrStyle.DSJ to QrStyleDefinition(
            style = QrStyle.DSJ,
            displayName = "DSJ Cross",
            defaultModuleShape = ModuleShape.CONNECTED,
            defaultFinderStyle = FinderStyle.DSJ,
            rendererFactory = { DsjRenderer() }
        ),
        QrStyle.IMAGE_FILL to QrStyleDefinition(
            style = QrStyle.IMAGE_FILL,
            displayName = "Image Fill",
            defaultModuleShape = ModuleShape.SQUARE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            imageFillMode = true,
            rendererFactory = { ImageFillRenderer() }
        ),
        QrStyle.IMAGE to QrStyleDefinition(
            style = QrStyle.IMAGE,
            displayName = "Image Backdrop",
            defaultModuleShape = ModuleShape.SQUARE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            requiresBackgroundImage = true,
            rendererFactory = { ImageRenderer() }
        ),
        QrStyle.IMAGE_RESAMPLE to QrStyleDefinition(
            style = QrStyle.IMAGE_RESAMPLE,
            displayName = "Image Resample",
            defaultModuleShape = ModuleShape.SQUARE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            requiresBackgroundImage = true,
            rendererFactory = { ResampleImageRenderer() }
        ),
        QrStyle.LINE to QrStyleDefinition(
            style = QrStyle.LINE,
            displayName = "Line Stripe",
            defaultModuleShape = ModuleShape.LINE,
            defaultFinderStyle = FinderStyle.CLASSIC,
            rendererFactory = { LineRenderer() }
        ),
        QrStyle.RANDOM_RECTANGLE to QrStyleDefinition(
            style = QrStyle.RANDOM_RECTANGLE,
            displayName = "Random Rectangle",
            defaultModuleShape = ModuleShape.ORGANIC,
            defaultFinderStyle = FinderStyle.ROUNDED,
            rendererFactory = { RandomRectangleRenderer() }
        ),
        QrStyle.FUNCTION to QrStyleDefinition(
            style = QrStyle.FUNCTION,
            displayName = "Parametric Function",
            defaultModuleShape = ModuleShape.ROUNDED,
            defaultFinderStyle = FinderStyle.ROUNDED,
            rendererFactory = { FunctionRenderer() }
        ),
        QrStyle.STYLE_FUNCTION to QrStyleDefinition(
            style = QrStyle.STYLE_FUNCTION,
            displayName = "Style Function",
            defaultModuleShape = ModuleShape.ROUNDED,
            defaultFinderStyle = FinderStyle.ROUNDED,
            supportsGradients = true,
            rendererFactory = { StyleFunctionRenderer() }
        ),
        QrStyle.CONNECTED_ORGANIC to QrStyleDefinition(
            style = QrStyle.CONNECTED_ORGANIC,
            displayName = "Connected Organic",
            defaultModuleShape = ModuleShape.ORGANIC,
            defaultFinderStyle = FinderStyle.ROUNDED,
            rendererFactory = { ConnectedOrganicRenderer() }
        )
    )

    fun get(style: QrStyle): QrStyleDefinition = definitions[style] ?: definitions.getValue(QrStyle.BASIC)

    fun getAll(): List<QrStyleDefinition> = definitions.values.toList()

    fun getRenderer(style: QrStyle): QrRenderer = get(style).rendererFactory()
}
