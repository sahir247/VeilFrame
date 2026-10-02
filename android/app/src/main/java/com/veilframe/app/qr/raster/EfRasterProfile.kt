package com.veilframe.app.qr.raster

/**
 * Pinned raster specification profile matching EFQRCode 7.0.3 CoreGraphics raster semantics.
 *
 * Grounded in EFQRCode 7.0.3 source (pinned commit 07ff9e2e83a4bbd384e5a8e33b47f389c9aac762):
 * - Input domain: 8-bit RGBA, sRGB-compatible, non-HDR.
 * - Intermediate bitmap contexts: RGBA8, premultipliedLast (CGImageAlphaInfo.premultipliedLast).
 * - Color space: DeviceRGB (CGColorSpaceCreateDeviceRGB()).
 * - Sampling: CG reference kernel (approximated by Skia 2x2 linear filter on Android).
 * - Dithering: Disabled (CoreGraphics does not perform explicit dithering during preprocessing).
 * - Coordinates: 64-bit Double geometry throughout preprocessor with single final subpixel boundary.
 */
data class EfRasterProfile(
    val sourceFormat: String = "RGBA8_sRGB",
    val alphaMode: String = "premultipliedLast",
    val colorSpace: String = "DeviceRGB",
    val sampling: String = "CG_REFERENCE_KERNEL",
    val dither: Boolean = false,
    val version: String = "7.0.3",
    val referenceCommit: String = "07ff9e2e83a4bbd384e5a8e33b47f389c9aac762"
) {
    companion object {
        val EF_7_0_3 = EfRasterProfile()
    }
}
