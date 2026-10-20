package com.veilframe.app.ui.settings

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutSettingsPanelBinding
import com.veilframe.app.settings.ThemeSettingsManager
import com.veilframe.app.storage.StorageGovernor
import com.veilframe.app.ui.motion.NavigationMotionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Coordinates settings slide-over overlay bindings:
 * Theme mode selection (System/Light/Dark/AMOLED), Material You Dynamic Colors,
 * Accent Palettes, Typography style, Storage Governor quotas, and swipe gestures.
 */
class SettingsOverlayCoordinator(
    private val activity: Activity,
    private val panelBinding: LayoutSettingsPanelBinding,
    private val settingsContainer: View,
    private val scrimSettings: View,
    private val rootCoordinator: View,
    private val scope: CoroutineScope,
    private val onRecreateNeeded: (restoreScrollY: Int) -> Unit
) {

    fun open(animate: Boolean = true, restoreScrollY: Int? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        ThemeSettingsManager.consumePendingRecreate()

        val rootInsets = ViewCompat.getRootWindowInsets(rootCoordinator)
        val sTop = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val nBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (sTop > 0 || nBottom > 0) {
            settingsContainer.setPadding(0, sTop, 0, nBottom)
        }

        // 1. Theme mode selection
        when (ThemeSettingsManager.getThemeMode(activity)) {
            ThemeSettingsManager.ThemeMode.SYSTEM -> panelBinding.rbThemeSystem.isChecked = true
            ThemeSettingsManager.ThemeMode.LIGHT -> panelBinding.rbThemeLight.isChecked = true
            ThemeSettingsManager.ThemeMode.DARK -> panelBinding.rbThemeDark.isChecked = true
            ThemeSettingsManager.ThemeMode.AMOLED -> panelBinding.rbThemeAmoled.isChecked = true
        }

        panelBinding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> ThemeSettingsManager.ThemeMode.LIGHT
                R.id.rbThemeDark -> ThemeSettingsManager.ThemeMode.DARK
                R.id.rbThemeAmoled -> ThemeSettingsManager.ThemeMode.AMOLED
                else -> ThemeSettingsManager.ThemeMode.SYSTEM
            }
            val currentMode = ThemeSettingsManager.getThemeMode(activity)
            if (mode != currentMode) {
                ThemeSettingsManager.setThemeMode(activity, mode, applyImmediately = true)
                seamlessRecreate()
            }
        }

        // 2. Dynamic color toggle with live palette chip disabling
        fun updatePaletteChipsEnabled(dynamicActive: Boolean) {
            panelBinding.chipGroupAccentPalette.isEnabled = !dynamicActive
            panelBinding.chipPaletteMonochrome.isEnabled = !dynamicActive
            panelBinding.chipPaletteForestSage.isEnabled = !dynamicActive
            panelBinding.chipPaletteDeepOcean.isEnabled = !dynamicActive
            panelBinding.chipPaletteWarmAmber.isEnabled = !dynamicActive
            panelBinding.chipPaletteCyberViolet.isEnabled = !dynamicActive
            panelBinding.chipGroupAccentPalette.alpha = if (dynamicActive) 0.38f else 1.0f
            panelBinding.tvDynamicColorNotice.visibility = if (dynamicActive) View.VISIBLE else View.GONE
        }

        val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamicInitial = ThemeSettingsManager.isDynamicColorEnabled(activity) && isDynamicSupported
        panelBinding.switchDynamicColor.isEnabled = isDynamicSupported
        panelBinding.switchDynamicColor.isChecked = dynamicInitial
        updatePaletteChipsEnabled(dynamicInitial)

        panelBinding.switchDynamicColor.setOnCheckedChangeListener { _, isChecked ->
            val currentDynamic = ThemeSettingsManager.isDynamicColorEnabled(activity)
            if (isChecked != currentDynamic) {
                ThemeSettingsManager.setDynamicColorEnabled(activity, isChecked)
                updatePaletteChipsEnabled(isChecked)
                seamlessRecreate()
            }
        }

        // 3. Custom accent palette
        when (ThemeSettingsManager.getAccentPalette(activity)) {
            ThemeSettingsManager.AccentPalette.MONOCHROME -> panelBinding.chipPaletteMonochrome.isChecked = true
            ThemeSettingsManager.AccentPalette.SAGE -> panelBinding.chipPaletteForestSage.isChecked = true
            ThemeSettingsManager.AccentPalette.OCEAN -> panelBinding.chipPaletteDeepOcean.isChecked = true
            ThemeSettingsManager.AccentPalette.AMBER -> panelBinding.chipPaletteWarmAmber.isChecked = true
            ThemeSettingsManager.AccentPalette.VIOLET -> panelBinding.chipPaletteCyberViolet.isChecked = true
        }

        panelBinding.chipGroupAccentPalette.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val palette = when (checkedIds[0]) {
                    R.id.chipPaletteMonochrome -> ThemeSettingsManager.AccentPalette.MONOCHROME
                    R.id.chipPaletteForestSage -> ThemeSettingsManager.AccentPalette.SAGE
                    R.id.chipPaletteDeepOcean -> ThemeSettingsManager.AccentPalette.OCEAN
                    R.id.chipPaletteWarmAmber -> ThemeSettingsManager.AccentPalette.AMBER
                    R.id.chipPaletteCyberViolet -> ThemeSettingsManager.AccentPalette.VIOLET
                    else -> ThemeSettingsManager.AccentPalette.MONOCHROME
                }
                val currentPalette = ThemeSettingsManager.getAccentPalette(activity)
                val dynamicWasChecked = panelBinding.switchDynamicColor.isChecked
                if (palette != currentPalette || dynamicWasChecked) {
                    if (dynamicWasChecked) {
                        ThemeSettingsManager.setDynamicColorEnabled(activity, false)
                    }
                    ThemeSettingsManager.setAccentPalette(activity, palette)
                    seamlessRecreate()
                }
            }
        }

        // 4. Typography style
        when (ThemeSettingsManager.getTypographyStyle(activity)) {
            ThemeSettingsManager.TypographyStyle.SYSTEM -> panelBinding.rbTypoSystem.isChecked = true
            ThemeSettingsManager.TypographyStyle.DEFAULT -> panelBinding.rbTypoSans.isChecked = true
            ThemeSettingsManager.TypographyStyle.MONOSPACE -> panelBinding.rbTypoMonospace.isChecked = true
            ThemeSettingsManager.TypographyStyle.SERIF -> panelBinding.rbTypoSerif.isChecked = true
        }

        panelBinding.rgTypography.setOnCheckedChangeListener { _, checkedId ->
            val typo = when (checkedId) {
                R.id.rbTypoSystem -> ThemeSettingsManager.TypographyStyle.SYSTEM
                R.id.rbTypoMonospace -> ThemeSettingsManager.TypographyStyle.MONOSPACE
                R.id.rbTypoSerif -> ThemeSettingsManager.TypographyStyle.SERIF
                else -> ThemeSettingsManager.TypographyStyle.DEFAULT
            }
            val currentTypo = ThemeSettingsManager.getTypographyStyle(activity)
            if (typo != currentTypo) {
                ThemeSettingsManager.setTypographyStyle(activity, typo)
                ThemeSettingsManager.applyTypography(activity)
            }
        }

        // 5. Storage governor selection
        when (StorageGovernor.getMaxStorageGb(activity)) {
            StorageGovernor.LIMIT_1_GB -> panelBinding.rbStorage1Gb.isChecked = true
            StorageGovernor.LIMIT_2_GB -> panelBinding.rbStorage2Gb.isChecked = true
            StorageGovernor.LIMIT_4_GB -> panelBinding.rbStorage4Gb.isChecked = true
            StorageGovernor.LIMIT_8_GB -> panelBinding.rbStorage8Gb.isChecked = true
            StorageGovernor.LIMIT_UNLIMITED -> panelBinding.rbStorageUnlimited.isChecked = true
            else -> panelBinding.rbStorage2Gb.isChecked = true
        }

        panelBinding.rgStorageLimit.setOnCheckedChangeListener { _, checkedId ->
            val limitGb = when (checkedId) {
                R.id.rbStorage1Gb -> StorageGovernor.LIMIT_1_GB
                R.id.rbStorage2Gb -> StorageGovernor.LIMIT_2_GB
                R.id.rbStorage4Gb -> StorageGovernor.LIMIT_4_GB
                R.id.rbStorage8Gb -> StorageGovernor.LIMIT_8_GB
                R.id.rbStorageUnlimited -> StorageGovernor.LIMIT_UNLIMITED
                else -> StorageGovernor.DEFAULT_LIMIT_GB
            }
            StorageGovernor.setMaxStorageGb(activity, limitGb)
            scope.launch(Dispatchers.IO) {
                StorageGovernor.enforceStorageLimit(activity)
            }
        }

        // 6. Close and dismissal bindings
        panelBinding.btnSettingsClose.setOnClickListener { close() }
        scrimSettings.setOnClickListener { close() }

        // 7. Left-to-right swipe-to-dismiss gesture
        NavigationMotionController.attachSwipeToDismiss(
            settingsContainer = settingsContainer,
            scrimView = scrimSettings
        ) {
            close()
        }

        if (restoreScrollY != null && restoreScrollY > 0) {
            panelBinding.scrollSettings.post {
                panelBinding.scrollSettings.scrollY = restoreScrollY
            }
        }

        // 8. Open slide-over animation or instant restore
        if (animate) {
            NavigationMotionController.openSettings(
                settingsContainer = settingsContainer,
                scrimView = scrimSettings
            )
        } else {
            settingsContainer.visibility = View.VISIBLE
            settingsContainer.translationX = 0f
            settingsContainer.alpha = 1f
            scrimSettings.visibility = View.VISIBLE
            scrimSettings.alpha = 1f
        }
    }

    fun close(animate: Boolean = true) {
        if (settingsContainer.visibility != View.VISIBLE) return
        if (animate) {
            NavigationMotionController.closeSettings(
                settingsContainer = settingsContainer,
                scrimView = scrimSettings
            ) {
                if (ThemeSettingsManager.consumePendingRecreate()) {
                    activity.recreate()
                }
            }
        } else {
            settingsContainer.visibility = View.GONE
            scrimSettings.visibility = View.GONE
            if (ThemeSettingsManager.consumePendingRecreate()) {
                activity.recreate()
            }
        }
    }

    private fun seamlessRecreate() {
        val scrollY = panelBinding.scrollSettings.scrollY
        onRecreateNeeded(scrollY)
    }
}
