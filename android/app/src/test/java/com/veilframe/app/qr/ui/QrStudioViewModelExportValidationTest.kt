package com.veilframe.app.qr.ui

import android.app.Application
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying that QrStudioViewModel does not silently export fallback URLs
 * like "https://example.com" when the user-provided content is blank, and properly sets
 * user-facing validation error messages.
 */
class QrStudioViewModelExportValidationTest {

    @Test
    fun testExportRejectedWhenContentIsBlank() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Ensure content starts blank or is set to whitespace
        vm.updateContent("   ")

        // 1. Save to Gallery
        vm.saveToGallery()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 2. Save SVG
        vm.saveSvg()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 3. Save GIF
        vm.saveGif()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 4. Save Video
        vm.saveVideo()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 5. Save Animated SVG
        vm.saveAnimatedSvg()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 6. Share
        vm.share()
        assertEquals("Content is required to share QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)
    }
}
