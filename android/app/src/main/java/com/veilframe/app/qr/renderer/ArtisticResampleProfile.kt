package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign

/**
 * Authoritative specification profile for Artistic Image Resample QR generation.
 *
 * Defines the complete behavioral and geometric contract for the 3x3 stochastic
 * image-resample pipeline:
 * 1. Finder Behavior: Hollow 7x7 outer stroke (1 module) and 3x3 core, allowing
 *    the continuous backdrop to shine through the 1-module light ring.
 * 2. Timing Track Geometry: Canonical square modules ([ModuleShape.SQUARE]).
 * 3. Alignment Pattern Geometry: Canonical square modules ([ModuleShape.SQUARE]).
 * 4. Coordinate Space: 3N x 3N raster grid with 1.02x subpixel expansion to eliminate seams.
 * 5. Margin/Quiet Zone: Exactly 1 module (explicitQuietZone = 1).
 * 6. Suppression Rules: Governed by [ArtisticResamplePolicy] and [ArtisticResampleFunctionalMask]
 *    (8x8 finders, timing, 5x5 alignment; format and version participate in stochastic pass).
 * 7. Backdrop Composition: Full-viewBox continuous backdrop with supported blend modes.
 */
object ArtisticResampleProfile {
    const val DEFAULT_QUIET_ZONE_MODULES: Int = 1
    const val SUBPIXEL_GRID_FACTOR: Int = 3
    const val SUBPIXEL_OVERLAP_FACTOR: Float = 1.02f

    val DEFAULT_TIMING_SHAPE: ModuleShape = ModuleShape.SQUARE
    val DEFAULT_ALIGNMENT_SHAPE: ModuleShape = ModuleShape.SQUARE
    val DEFAULT_FINDER_STYLE: FinderStyle = FinderStyle.CLASSIC
    val DEFAULT_POLICY: ResamplePolicy = ArtisticResamplePolicy

    /**
     * Applies canonical artistic resample defaults to a given [design] to guarantee
     * full behavioral and visual alignment across Canvas, SVG, and export pipelines.
     */
    fun applyProfile(design: QrDesign): QrDesign {
        return design.copy(
            quietZoneModules = DEFAULT_QUIET_ZONE_MODULES,
            explicitQuietZone = design.explicitQuietZone ?: DEFAULT_QUIET_ZONE_MODULES,
            timingStyle = design.timingStyle.copy(shape = DEFAULT_TIMING_SHAPE),
            alignmentStyle = design.alignmentStyle.copy(shape = DEFAULT_ALIGNMENT_SHAPE),
            resampleStyle = design.resampleStyle.copy(useSourceAsBackdrop = true)
        )
    }
}
