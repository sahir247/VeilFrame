package com.veilframe.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding

/** Rebind shell colors against the current Activity overlay after each inflation. */
object ShellThemeStyler {
    internal fun navigationItemTint(context: Context): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        ),
        intArrayOf(
            MaterialColors.getColor(context, R.attr.colorOutline, Color.GRAY),
            MaterialColors.getColor(context, R.attr.colorOnSecondaryContainer, Color.WHITE),
            MaterialColors.getColor(context, R.attr.colorOnSurfaceVariant, Color.GRAY)
        )
    )

    fun apply(activity: Activity, binding: ActivityMainBinding) {
        val canvas = MaterialColors.getColor(activity, R.attr.colorSurfaceContainerLowest, Color.BLACK)
        val onSurface = MaterialColors.getColor(activity, R.attr.colorOnSurface, Color.WHITE)
        val indicator = MaterialColors.getColor(activity, R.attr.colorSecondaryContainer, Color.DKGRAY)
        val tint = navigationItemTint(activity)
        for (nav: NavigationBarView in listOf(binding.bottomNavigation, binding.navigationRail)) {
            nav.setBackgroundColor(canvas)
            nav.setItemIconTintList(tint)
            nav.setItemTextColor(tint)
            nav.setItemActiveIndicatorColor(ColorStateList.valueOf(indicator))
        }
        // The Settings sheet has its own canvas and header, including in OLED.
        binding.containerSettings.setBackgroundColor(canvas)
        binding.layoutSettingsPanel.root.setBackgroundColor(canvas)
        binding.layoutSettingsPanel.settingsHeaderBar.setBackgroundColor(canvas)
        binding.layoutSettingsPanel.tvSettingsHeaderTitle.setTextColor(onSurface)
        binding.layoutSettingsPanel.btnSettingsClose.iconTint = ColorStateList.valueOf(onSurface)
    }
}
