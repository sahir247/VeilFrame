package com.veilframe.app.qr

import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.decoder.DecodeResult
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.validation.*
import org.junit.Assert.*
import org.junit.Test

class QrModuleRoleTest {

    @Test
    fun testQrModuleRoleClassification() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)

        // 1. Finder patterns
        // Center of top-left finder: col 3, row 3
        assertEquals(QrModuleRole.FINDER_INNER, matrix.roleAt(3, 3))
        // Top-left outer border: col 0, row 0
        assertEquals(QrModuleRole.FINDER_OUTER, matrix.roleAt(0, 0))

        // 2. Separators
        // Separator surrounds 7x7 finders (e.g. col 7, row 0..7)
        assertEquals(QrModuleRole.SEPARATOR, matrix.roleAt(7, 0))
        assertEquals(QrModuleRole.SEPARATOR, matrix.roleAt(0, 7))

        // 3. Timing strips (row 6 and col 6)
        assertEquals(QrModuleRole.TIMING, matrix.roleAt(6, 10))
        assertEquals(QrModuleRole.TIMING, matrix.roleAt(10, 6))

        // 4. Protection queries
        assertTrue("Finder center must be protected", matrix.isProtected(3, 3))
        assertTrue("Finder outer must be protected", matrix.isProtected(0, 0))
        assertTrue("Separator must be protected", matrix.isProtected(7, 0))
        assertTrue("Timing track must be protected", matrix.isProtected(6, 10))

        // 5. moduleAt descriptor
        val modFinder = matrix.moduleAt(3, 3)
        assertEquals(3, modFinder.col)
        assertEquals(3, modFinder.row)
        assertEquals(QrModuleRole.FINDER_INNER, modFinder.role)
        assertTrue(modFinder.isDark)
        assertTrue(modFinder.isProtected)
    }

    @Test
    fun testAlignmentPatternClassificationInHigherVersions() {
        // Longer payload forces Version >= 2 with alignment patterns
        val longContent = "https://veilframe.app/security/privacy-cleaner/advanced-qr-verification-protocol-with-extended-metadata"
        val matrix = QrMatrix(longContent, ErrorCorrectionLevel.H)
        assertTrue("Matrix size should be >= 25 (Version >= 2)", matrix.size >= 25)

        // Count alignment modules
        var alignmentCenterCount = 0
        var alignmentBorderCount = 0
        for (c in 0 until matrix.size) {
            for (r in 0 until matrix.size) {
                when (matrix.roleAt(c, r)) {
                    QrModuleRole.ALIGNMENT_CENTER -> alignmentCenterCount++
                    QrModuleRole.ALIGNMENT_BORDER -> alignmentBorderCount++
                    else -> {}
                }
            }
        }

        assertTrue("Version >= 2 must contain alignment center patterns", alignmentCenterCount >= 1)
        assertTrue("Version >= 2 must contain alignment border patterns", alignmentBorderCount >= 8)
    }

    @Test
    fun testModuleShapeAndFillCombinations() {
        val design = QrDesign(
            moduleStyle = ModuleStyle(
                shape = ModuleShape.STAR,
                fill = ModuleFill.LINEAR_GRADIENT
            ),
            timingStyle = TimingStyle(
                shape = ModuleShape.ROUNDED,
                color = 0xFF2563EB.toInt()
            ),
            alignmentStyle = AlignmentStyle(
                shape = ModuleShape.CIRCLE,
                color = 0xFF7C3AED.toInt()
            )
        )

        assertEquals(ModuleShape.STAR, design.moduleStyle.shape)
        assertEquals(ModuleFill.LINEAR_GRADIENT, design.moduleStyle.fill)
        assertEquals(ModuleShape.ROUNDED, design.timingStyle.shape)
        assertEquals(ModuleShape.CIRCLE, design.alignmentStyle.shape)
    }

    @Test
    fun testSvgExporterWithPlanetsAndDsjFinders() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)

        // 1. Planets Finder Style
        val planetsDesign = QrDesign(
            eyeStyle = EyeStyle(style = FinderStyle.PLANETS),
            moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE)
        )
        val planetsSvg = SvgExporter.generateSvg(matrix, planetsDesign)
        assertTrue("SVG must contain planets orbit ring", planetsSvg.contains("stroke-dasharray=\"0.5,0.5\""))

        // 2. DSJ Finder Style with Star Data Modules
        val dsjDesign = QrDesign(
            eyeStyle = EyeStyle(style = FinderStyle.DSJ),
            moduleStyle = ModuleStyle(shape = ModuleShape.STAR)
        )
        val dsjSvg = SvgExporter.generateSvg(matrix, dsjDesign)
        assertTrue("SVG must contain polygon elements for Star modules", dsjSvg.contains("<polygon points=\""))
    }

    @Test
    fun testAutoRepairEngineWithViolations() {
        // Design with quietZone = 0, low contrast, aggressive shape
        val candidateDesign = QrDesign(
            quietZoneModules = 0,
            moduleStyle = ModuleStyle(shape = ModuleShape.STAR, scale = 0.6f),
            palette = PaletteStyle(foreground = 0xFF888888.toInt(), background = 0xFF999999.toInt())
        )

        val report = ScanabilityReport(
            isScanReady = false,
            quietZone = QuietZoneReport(hasFourModuleMargin = false, quietZoneModules = 0),
            contrast = ContrastReport(0.5f, 0.5f, 0.6f, 0.6f, separation = 0.1f, isContrastAdequate = false),
            finders = FinderIntegrityReport(findersIntact = true, separatorsClear = true),
            logo = LogoOcclusionReport(false, 0, 0f, true),
            decodeResult = DecodeResult(success = false, error = "Low contrast"),
            errorCorrection = ErrorCorrectionLevel.M,
            warnings = listOf("Quiet zone missing", "Low contrast", "Deformation"),
            repairSuggestions = listOf(
                RepairReason.RESTORE_QUIET_ZONE,
                RepairReason.INCREASE_CONTRAST,
                RepairReason.INCREASE_MODULE_SCALE,
                RepairReason.REDUCE_DEFORMATION
            )
        )

        val repair = AutoRepairEngine.repair(candidateDesign, report, "https://veilframe.app")
        val repaired = repair.repairedDesign

        assertEquals("Quiet zone must be restored to 4", 4, repaired.quietZoneModules)
        assertEquals("Foreground contrast must be restored to black", Color.BLACK, repaired.palette.foreground)
        assertEquals("Background contrast must be restored to white", Color.WHITE, repaired.palette.background)
        assertTrue("Module scale must be increased", repaired.moduleStyle.scale > 0.6f)
        assertEquals("Shape must be normalized to ROUNDED", ModuleShape.ROUNDED, repaired.moduleStyle.shape)
        assertTrue("Changes trail must be documented", repair.changesApplied.isNotEmpty())
    }

    @Test
    fun testGenerateWithAutoRepairBlankContent() {
        val result = QrGenerator.generateWithAutoRepair("   ")
        assertTrue("Blank content must return Failure", result is QrRenderResult.Failure)
        assertEquals("QR content must not be blank", (result as QrRenderResult.Failure).error)
    }
}
