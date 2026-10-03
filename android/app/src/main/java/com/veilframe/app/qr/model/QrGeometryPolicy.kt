package com.veilframe.app.qr.model

import com.veilframe.app.qr.QrStyle

/**
 * Explicit geometry policy separating EFQRCode parity geometry defaults (1-module margin)
 * from ISO/IEC 18004 safe production defaults (4-module margin for basic QR codes).
 */
sealed interface QrGeometryPolicy {
    fun defaultQuietZoneModules(style: QrStyle): Int

    /**
     * EFQRCode exact behavioral parity policy:
     * - Defaults to 1 quiet-zone module for styles matching EFQRCode's default (quietzone = nil -> 1 module).
     * - D25 uses 0 additional quiet zone modules because EFQRCode Style25D viewBox natively
     *   provides an isometric 2n x 2n bounding canvas (EFQRCodeStyle25D.swift L317-L328).
     */
    object EfParity : QrGeometryPolicy {
        override fun defaultQuietZoneModules(style: QrStyle): Int =
            QrGeometry.resolveDefaultQuietZone(style, defaultFallback = 1)
    }

    /**
     * Production safety-first policy:
     * - Enforces 4-module quiet zone for BASIC and geometric styles to guarantee scanability on standard scanners.
     * - Allows 1-module margin for image and resample styles where boundary contrast or backdrops exist.
     */
    object SafeProduction : QrGeometryPolicy {
        override fun defaultQuietZoneModules(style: QrStyle): Int =
            when (style) {
                QrStyle.D25 -> 0
                QrStyle.IMAGE, QrStyle.IMAGE_RESAMPLE, QrStyle.IMAGE_FILL -> 1
                else -> 4
            }
    }
}
