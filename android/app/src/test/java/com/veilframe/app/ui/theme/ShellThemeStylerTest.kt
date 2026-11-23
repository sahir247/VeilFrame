package com.veilframe.app.ui.theme

import android.graphics.Color
import android.view.ContextThemeWrapper
import com.google.android.material.color.MaterialColors
import com.veilframe.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ShellThemeStylerTest {
    private fun themed(overlay: Int): ContextThemeWrapper =
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VeilFrame).also {
            it.theme.applyStyle(overlay, true)
        }

    @Test
    @Config(qualifiers = "night")
    fun oledCanvasIsBlackAndNavigationContrastIsThemed() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        assertEquals(Color.BLACK, MaterialColors.getColor(context, R.attr.colorSurfaceContainerLowest, Color.MAGENTA))
        val tint = ShellThemeStyler.navigationItemTint(context)
        val selected = tint.getColorForState(intArrayOf(android.R.attr.state_checked), Color.MAGENTA)
        val other = tint.getColorForState(intArrayOf(-android.R.attr.state_checked), Color.MAGENTA)
        assertEquals(MaterialColors.getColor(context, R.attr.colorOnSecondaryContainer, Color.MAGENTA), selected)
        assertEquals(MaterialColors.getColor(context, R.attr.colorOnSurfaceVariant, Color.MAGENTA), other)
        assertNotEquals(Color.BLACK, selected)
        assertNotEquals(Color.BLACK, other)
    }

    @Test
    @Config(qualifiers = "night")
    fun newAccentChangesSelectedNavigationTint() {
        val selectedState = intArrayOf(android.R.attr.state_checked)
        val neutral = ShellThemeStyler.navigationItemTint(themed(R.style.ThemeOverlay_VeilFrame_Palette_Monochrome))
        val ocean = ShellThemeStyler.navigationItemTint(themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean))
        assertNotEquals(neutral.getColorForState(selectedState, Color.MAGENTA),
            ocean.getColorForState(selectedState, Color.MAGENTA))
    }
}
