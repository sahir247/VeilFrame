package com.veilframe.app.ui.theme

import android.graphics.Color
import android.view.View
import android.graphics.drawable.ColorDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.view.ContextThemeWrapper
import com.google.android.material.color.MaterialColors
import com.veilframe.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ShellThemeStylerTest {
    // ColorStateList consumes the actual positive drawable states. Omitting
    // state_enabled matches -state_enabled even when state_checked is present.
    private val enabledChecked = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
    private val enabledUnchecked = intArrayOf(android.R.attr.state_enabled)
    private val disabledChecked = intArrayOf(android.R.attr.state_checked)
    private val disabledUnchecked = intArrayOf()
    private fun themed(overlay: Int): ContextThemeWrapper =
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VeilFrame).also {
            it.theme.applyStyle(overlay, true)
        }

    @Test
    @Config(qualifiers = "night")
    fun oledCanvasIsBlackAndNavigationContrastIsThemed() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        assertEquals(Color.BLACK, MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.MAGENTA))
        val tint = ShellThemeStyler.navigationItemTint(context)
        val selected = tint.getColorForState(enabledChecked, Color.MAGENTA)
        val other = tint.getColorForState(enabledUnchecked, Color.MAGENTA)
        assertEquals(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.MAGENTA), selected)
        assertEquals(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.MAGENTA), other)
        assertNotEquals(Color.BLACK, selected)
        assertNotEquals(Color.BLACK, other)
        val disabled = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.MAGENTA)
        assertEquals(disabled, tint.getColorForState(disabledChecked, Color.MAGENTA))
        assertEquals(disabled, tint.getColorForState(disabledUnchecked, Color.MAGENTA))
    }

    @Test
    @Config(qualifiers = "night")
    fun materialNavigationViewActuallyAdoptsOledAndDarkSurface() {
        val oledContext = themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        val oledNav = BottomNavigationView(oledContext)
        ShellThemeStyler.styleNavigation(oledContext, oledNav)
        assertEquals(Color.BLACK, (oledNav.background as ColorDrawable).color)
        assertEquals(
            MaterialColors.getColor(oledContext, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.MAGENTA),
            oledNav.itemIconTintList!!.getColorForState(enabledChecked, Color.MAGENTA)
        )
        val darkContext = themed(R.style.ThemeOverlay_VeilFrame_Palette_Monochrome)
        val darkNav = BottomNavigationView(darkContext)
        ShellThemeStyler.styleNavigation(darkContext, darkNav)
        assertEquals(
            MaterialColors.getColor(darkContext, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.MAGENTA),
            (darkNav.background as ColorDrawable).color
        )
        assertNotEquals((oledNav.background as ColorDrawable).color, (darkNav.background as ColorDrawable).color)
    }

    @Test
    @Config(qualifiers = "night")
    fun realNavigationItemsResolveEnabledCheckedAndDisabledDrawableStates() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean)
        val nav = BottomNavigationView(context)
        val firstId = View.generateViewId()
        val secondId = View.generateViewId()
        nav.menu.add(0, firstId, 0, "First")
        nav.menu.add(0, secondId, 1, "Second")
        nav.selectedItemId = firstId
        ShellThemeStyler.styleNavigation(context, nav)

        val first = requireNotNull(nav.findViewById<View>(firstId))
        val second = requireNotNull(nav.findViewById<View>(secondId))
        assertTrue(first.drawableState.contains(android.R.attr.state_enabled))
        assertTrue(first.drawableState.contains(android.R.attr.state_checked))
        assertTrue(second.drawableState.contains(android.R.attr.state_enabled))
        val tint = requireNotNull(nav.itemIconTintList)
        val text = requireNotNull(nav.itemTextColor)
        val selected = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.MAGENTA)
        val normal = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.MAGENTA)
        val disabled = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.MAGENTA)
        for (colors in listOf(tint, text)) {
            assertEquals(selected, colors.getColorForState(first.drawableState, Color.MAGENTA))
            assertEquals(normal, colors.getColorForState(second.drawableState, Color.MAGENTA))
        }
        nav.menu.findItem(secondId).isEnabled = false
        val disabledItem = requireNotNull(nav.findViewById<View>(secondId))
        assertTrue(!disabledItem.drawableState.contains(android.R.attr.state_enabled))
        for (colors in listOf(tint, text)) {
            assertEquals(disabled, colors.getColorForState(disabledItem.drawableState, Color.MAGENTA))
        }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun lightNavigationAndIndicatorResolveThemeRoles() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean)
        val nav = BottomNavigationView(context)
        ShellThemeStyler.styleNavigation(context, nav)
        assertEquals(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.MAGENTA),
            (nav.background as ColorDrawable).color
        )
        assertEquals(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, Color.MAGENTA),
            nav.itemActiveIndicatorColor!!.defaultColor
        )
    }

    @Test
    @Config(qualifiers = "night")
    fun newAccentChangesSelectedNavigationTint() {
        val neutralContext = themed(R.style.ThemeOverlay_VeilFrame_Palette_Monochrome)
        val oceanContext = themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean)
        val neutral = ShellThemeStyler.navigationItemTint(neutralContext)
        val ocean = ShellThemeStyler.navigationItemTint(oceanContext)
        val neutralSelected = neutral.getColorForState(enabledChecked, Color.MAGENTA)
        val oceanSelected = ocean.getColorForState(enabledChecked, Color.MAGENTA)
        assertEquals(MaterialColors.getColor(neutralContext, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.MAGENTA), neutralSelected)
        assertEquals(MaterialColors.getColor(oceanContext, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.MAGENTA), oceanSelected)
        assertNotEquals(neutralSelected, oceanSelected)
    }
}
