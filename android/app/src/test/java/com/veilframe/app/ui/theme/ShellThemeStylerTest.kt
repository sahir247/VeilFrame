package com.veilframe.app.ui.theme

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    fun materialNavigationViewActuallyAdoptsOledAndDarkSurface() {
        val oledContext = themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        val oledNav = BottomNavigationView(oledContext)
        ShellThemeStyler.styleNavigation(oledContext, oledNav)
        assertEquals(Color.BLACK, (oledNav.background as ColorDrawable).color)
        assertEquals(
            MaterialColors.getColor(oledContext, R.attr.colorOnSecondaryContainer, Color.MAGENTA),
            oledNav.itemIconTintList!!.getColorForState(intArrayOf(android.R.attr.state_checked), Color.MAGENTA)
        )
        val darkContext = themed(R.style.ThemeOverlay_VeilFrame_Palette_Monochrome)
        val darkNav = BottomNavigationView(darkContext)
        ShellThemeStyler.styleNavigation(darkContext, darkNav)
        assertEquals(
            MaterialColors.getColor(darkContext, R.attr.colorSurfaceContainerLowest, Color.MAGENTA),
            (darkNav.background as ColorDrawable).color
        )
        assertNotEquals((oledNav.background as ColorDrawable).color, (darkNav.background as ColorDrawable).color)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun lightNavigationAndIndicatorResolveThemeRoles() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean)
        val nav = BottomNavigationView(context)
        ShellThemeStyler.styleNavigation(context, nav)
        assertEquals(
            MaterialColors.getColor(context, R.attr.colorSurfaceContainerLowest, Color.MAGENTA),
            (nav.background as ColorDrawable).color
        )
        assertEquals(
            MaterialColors.getColor(context, R.attr.colorSecondaryContainer, Color.MAGENTA),
            nav.itemActiveIndicatorColor!!.defaultColor
        )
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
