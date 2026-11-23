package com.veilframe.app.settings

import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.appcompat.content.res.AppCompatResources
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

/**
 * Contract for the shared workflow/Settings/navigation theme roles.
 * The smoke test is intentionally independent of MainActivity startup and
 * network/native SDK state. Screenshot/device acceptance remains separate.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyToolThemeContractTest {
    private fun themed(style: Int = 0): ContextThemeWrapper {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.Theme_VeilFrame
        )
        if (style != 0) context.theme.applyStyle(style, true)
        return context
    }

    @Test
    @Config(qualifiers = "night")
    fun oledUsesTrueBlackNavigationAndSettingsCanvas() {
        val context = themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        val lowest = MaterialColors.getColor(context, R.attr.colorSurfaceContainerLowest, Color.MAGENTA)
        val attrs = context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorBackground)
        )
        val body = try {
            attrs.getColor(0, Color.MAGENTA)
        } finally {
            attrs.recycle()
        }
        assertEquals(Color.BLACK, lowest)
        assertEquals(Color.BLACK, body)
        assertNotEquals(Color.BLACK,
            MaterialColors.getColor(context, R.attr.colorOnSurface, Color.BLACK))
    }

    @Test
    @Config(qualifiers = "night")
    fun darkAndOledNavigationColorsFollowSelectedMaterialRoles() {
        for (context in listOf(
            themed(),
            themed(R.style.ThemeOverlay_VeilFrame_Amoled)
        )) {
            val color = AppCompatResources.getColorStateList(
                context, R.color.selector_vf_navigation_item
            ) ?: throw AssertionError("Navigation tint selector could not be inflated")
            val selected = MaterialColors.getColor(
                context, R.attr.colorOnSecondaryContainer, Color.MAGENTA
            )
            val normal = MaterialColors.getColor(
                context, R.attr.colorOnSurfaceVariant, Color.MAGENTA
            )
            assertEquals(selected, color.getColorForState(
                intArrayOf(android.R.attr.state_checked), Color.MAGENTA
            ))
            assertEquals(normal, color.getColorForState(
                intArrayOf(-android.R.attr.state_checked), Color.MAGENTA
            ))
            assertTrue(color.isStateful)
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun changingAccentOverlayChangesMaterialPrimaryRole() {
        val monochrome = themed(R.style.ThemeOverlay_VeilFrame_Palette_Monochrome)
        val ocean = themed(R.style.ThemeOverlay_VeilFrame_Palette_Ocean)
        val monoPrimary = MaterialColors.getColor(monochrome, R.attr.colorPrimary, Color.MAGENTA)
        val oceanPrimary = MaterialColors.getColor(ocean, R.attr.colorPrimary, Color.MAGENTA)
        assertNotEquals("Dynamic chips cannot keep a fixed @color/vf_primary", monoPrimary, oceanPrimary)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun lightModeNavigationIsContrastAware() {
        val context = themed()
        val selected = MaterialColors.getColor(context, R.attr.colorOnSecondaryContainer, Color.MAGENTA)
        val unchecked = MaterialColors.getColor(context, R.attr.colorOnSurfaceVariant, Color.MAGENTA)
        assertNotEquals(Color.MAGENTA, selected)
        assertNotEquals(Color.MAGENTA, unchecked)
    }
}
