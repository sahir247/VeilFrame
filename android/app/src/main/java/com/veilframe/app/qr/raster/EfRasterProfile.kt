package com.veilframe.app.qr.raster

/**
 * Pinned raster specification profile matching EFQRCode 7.0.3 CoreGraphics raster semantics.
 *
 * Grounded in EFQRCode 7.0.3 source (pinned commit 07ff9e2e83a4bbd384e5a8e33b47f389c9aac762)
 * and pinned SwiftDraw dependency (0.22.0, commit a19594794cdcdee5135caad3bc119096c50c92c2):
 *
 * Invariant:
 * ONE EF PREPROCESSING RASTER + ONE EF FINAL IMAGE-DRAW RASTER + NO EXTRA MODE/FIT RASTER
 *
 * Profile: EF-RASTER-7.0.3-SRGB8
 * - Input domain: 8-bit RGBA, sRGB-compatible, non-HDR, non-wide-gamut.
 * - Intermediate bitmap contexts: RGBA8, premultipliedLast (CGImageAlphaInfo.premultipliedLast).
 * - Color space: DeviceRGB (CGColorSpaceCreateDeviceRGB()).
 * - Sampling: CG reference kernel (approximated by Skia 2x2 linear filter on Android).
 * - Dithering: Disabled (CoreGraphics does not perform explicit dithering during preprocessing).
 * - Coordinates: 64-bit Double geometry throughout preprocessor with CGRectIntegral on cropping.
 */
data class EfRasterProfile(
    val profileName: String = "EF-RASTER-7.0.3-SRGB8",
    val sourceFormat: String = "RGBA8_sRGB_NON_HDR",
    val alphaMode: String = "premultipliedLast",
    val colorSpace: String = "DeviceRGB",
    val sampling: String = "CG_REFERENCE_KERNEL",
    val dither: Boolean = false,
    val version: String = "7.0.3",
    val referenceCommit: String = "07ff9e2e83a4bbd384e5a8e33b47f389c9aac762",
    val pinnedSwiftDrawCommit: String = "a19594794cdcdee5135caad3bc119096c50c92c2",
    val invariantDescription: String = "ONE EF PREPROCESSING RASTER + ONE EF FINAL IMAGE-DRAW RASTER + NO EXTRA MODE/FIT RASTER"
) {
    companion object {
        val EF_RASTER_7_0_3_SRGB8 = EfRasterProfile()
        val EF_7_0_3 = EF_RASTER_7_0_3_SRGB8
    }
}
