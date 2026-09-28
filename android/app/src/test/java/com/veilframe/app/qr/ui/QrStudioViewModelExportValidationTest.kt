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
        assertFalse(vm.state.value.isExporting)
    }

    @Test
    fun testBlankContentDoesNotGeneratePreviewOrExampleCom() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateContent("   ")

        // Must not silently generate a preview for example.com
        assertNull(vm.state.value.bitmap)
        assertNull(vm.state.value.matrix)
        assertNull(vm.state.value.scanabilityReport)
        assertFalse(vm.state.value.isRenderingPreview)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun testAutoRepairRefusesBlankContent() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateContent("")
        vm.autoRepair()

        // Auto repair must not run or set repairNotice on blank content
        assertNull(vm.state.value.repairNotice)
        assertNull(vm.state.value.bitmap)
    }

    @Test
    fun testSeparatePreviewAndExportLoadingStates() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        assertFalse(vm.state.value.isRenderingPreview)
        assertFalse(vm.state.value.isExporting)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun testFormStateAndStyleStateUpdates() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Preset and Form Hydration
        vm.updateActivePreset(999)
        assertEquals(999, vm.state.value.activePresetId)

        vm.updateWifiForm("TestSSID", "TestPass", 2, true)
        assertEquals("TestSSID", vm.state.value.wifiSsid)
        assertEquals("TestPass", vm.state.value.wifiPassword)
        assertEquals(2, vm.state.value.wifiSecurityPos)
        assertTrue(vm.state.value.wifiHidden)

        vm.updateVcardForm("Partha", "Maitra", "9876543210", "partha@example.com", "VeilFrame")
        assertEquals("Partha", vm.state.value.vcardFirst)
        assertEquals("Maitra", vm.state.value.vcardLast)
        assertEquals("9876543210", vm.state.value.vcardPhone)
        assertEquals("partha@example.com", vm.state.value.vcardEmail)
        assertEquals("VeilFrame", vm.state.value.vcardOrg)

        vm.updateEmailForm("hello@example.com", "Hi", "Message")
        assertEquals("hello@example.com", vm.state.value.emailRecipient)
        assertEquals("Hi", vm.state.value.emailSubject)
        assertEquals("Message", vm.state.value.emailBody)

        vm.updateSmsForm("123456", "Hello")
        assertEquals("123456", vm.state.value.smsPhone)
        assertEquals("Hello", vm.state.value.smsBody)

        vm.updateUpiForm("merchant@upi", "250.00")
        assertEquals("merchant@upi", vm.state.value.upiVpa)
        assertEquals("250.00", vm.state.value.upiAmount)

        // Contextual Style Options
        vm.updateD25Depth(1.75f)
        assertEquals(1.75f, vm.state.value.d25Depth, 0.001f)

        vm.updateLineDirection(com.veilframe.app.qr.model.LineDirection.CROSS)
        assertEquals(com.veilframe.app.qr.model.LineDirection.CROSS, vm.state.value.lineDirection)

        vm.updateLineThickness(0.85f)
        assertEquals(0.85f, vm.state.value.lineThickness, 0.001f)

        vm.updateDsjSizes(0.45f, 0.45f)
        assertEquals(0.45f, vm.state.value.dsjLineSize, 0.001f)

        val initialSeed = vm.state.value.randomRectSeed
        vm.randomizeRandomRectSeed()
        assertNotEquals(initialSeed, vm.state.value.randomRectSeed)
    }

    @Test
    fun testRepairNoticeCanBeCleared() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.clearRepairNotice()
        assertNull(vm.state.value.repairNotice)
    }

    @Test
    fun testScannerPipelinePauseAndResume() {
        val scanner = com.veilframe.app.qr.scanner.QrScanner {}
        assertFalse(scanner.isAnalysisPaused)

        scanner.pauseAnalysis()
        assertTrue(scanner.isAnalysisPaused)

        scanner.resumeAnalysis()
        assertFalse(scanner.isAnalysisPaused)
        scanner.close()
    }
}
