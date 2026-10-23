package com.veilframe.app.ui.settings

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutSettingsPanelBinding
import com.veilframe.app.settings.ThemeSettingsManager
import com.veilframe.app.storage.StorageGovernor
import com.veilframe.app.ui.motion.NavigationMotionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates settings slide-over overlay bindings:
 * - Theme mode selection (4-segment pill bar: System/Light/Dark/OLED)
 * - Material You Dynamic Colors toggle with live swatch disabling
 * - Accent palette swatches (5 colored circles: Monochrome, Forest Sage, Deep Ocean, Warm Amber, Cyber Violet)
 * - Typography style (2x2 visual preview cards: System, Modern Sans, Terminal Mono, Editorial Serif)
 * - Storage governor quota (5-segment pill bar: 1GB/2GB/4GB/8GB/Max) + Clear Cache Now button
 * - Evicted sections from Home: Diagnostics & Console, About & In-App Updates, What's New, Resources & Community links
 * - Swipe-to-dismiss and window insets
 */
class SettingsOverlayCoordinator(
    private val activity: Activity,
    private val panelBinding: LayoutSettingsPanelBinding,
    private val settingsContainer: View,
    private val scrimSettings: View,
    private val rootCoordinator: View,
    private val scope: CoroutineScope,
    private val onRecreateNeeded: (restoreScrollY: Int) -> Unit,
    private val onOpenDiagnostics: () -> Unit = {},
    private val onCheckUpdates: () -> Unit = {},
    private val onRepairApp: () -> Unit = {},
    private val onOpenUrl: (url: String) -> Unit = {},
    private val onShowAbout: () -> Unit = {}
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

        // 1. Theme mode selection (4-Segment Horizontal Pill Bar)
        val currentThemeMode = ThemeSettingsManager.getThemeMode(activity)
        val targetThemeBtnId = when (currentThemeMode) {
            ThemeSettingsManager.ThemeMode.SYSTEM -> R.id.btnThemeSystem
            ThemeSettingsManager.ThemeMode.LIGHT -> R.id.btnThemeLight
            ThemeSettingsManager.ThemeMode.DARK -> R.id.btnThemeDark
            ThemeSettingsManager.ThemeMode.AMOLED -> R.id.btnThemeAmoled
        }
        panelBinding.toggleThemeMode.check(targetThemeBtnId)

        panelBinding.toggleThemeMode.clearOnButtonCheckedListeners()
        panelBinding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeLight -> ThemeSettingsManager.ThemeMode.LIGHT
                    R.id.btnThemeDark -> ThemeSettingsManager.ThemeMode.DARK
                    R.id.btnThemeAmoled -> ThemeSettingsManager.ThemeMode.AMOLED
                    else -> ThemeSettingsManager.ThemeMode.SYSTEM
                }
                if (mode != ThemeSettingsManager.getThemeMode(activity)) {
                    ThemeSettingsManager.setThemeMode(activity, mode, applyImmediately = true)
                    seamlessRecreate()
                }
            }
        }

        // 2. Dynamic color toggle with live palette disabling
        fun updateSwatchesEnabled(dynamicActive: Boolean) {
            panelBinding.layoutAccentSwatches.alpha = if (dynamicActive) 0.38f else 1.0f
            panelBinding.swatchMonochrome.isEnabled = !dynamicActive
            panelBinding.swatchForestSage.isEnabled = !dynamicActive
            panelBinding.swatchDeepOcean.isEnabled = !dynamicActive
            panelBinding.swatchWarmAmber.isEnabled = !dynamicActive
            panelBinding.swatchCyberViolet.isEnabled = !dynamicActive
            panelBinding.tvDynamicColorNotice.visibility = if (dynamicActive) View.VISIBLE else View.GONE
        }

        val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamicInitial = ThemeSettingsManager.isDynamicColorEnabled(activity) && isDynamicSupported
        panelBinding.switchDynamicColor.isEnabled = isDynamicSupported
        panelBinding.switchDynamicColor.isChecked = dynamicInitial
        updateSwatchesEnabled(dynamicInitial)

        panelBinding.switchDynamicColor.setOnCheckedChangeListener { _, isChecked ->
            val currentDynamic = ThemeSettingsManager.isDynamicColorEnabled(activity)
            if (isChecked != currentDynamic) {
                ThemeSettingsManager.setDynamicColorEnabled(activity, isChecked)
                updateSwatchesEnabled(isChecked)
                seamlessRecreate()
            }
        }

        // 3. Custom accent palette swatches (5 colored circle swatches)
        fun updateSwatchSelection(selected: ThemeSettingsManager.AccentPalette) {
            val primaryColor = activity.getColor(R.color.vf_primary)
            fun updateCard(card: MaterialCardView, checkView: View, isSelected: Boolean) {
                checkView.visibility = if (isSelected) View.VISIBLE else View.GONE
                card.strokeWidth = if (isSelected) 6 else 0
                card.strokeColor = primaryColor
            }

            updateCard(panelBinding.swatchMonochrome, panelBinding.imgCheckMonochrome, selected == ThemeSettingsManager.AccentPalette.MONOCHROME)
            updateCard(panelBinding.swatchForestSage, panelBinding.imgCheckForestSage, selected == ThemeSettingsManager.AccentPalette.SAGE)
            updateCard(panelBinding.swatchDeepOcean, panelBinding.imgCheckDeepOcean, selected == ThemeSettingsManager.AccentPalette.OCEAN)
            updateCard(panelBinding.swatchWarmAmber, panelBinding.imgCheckWarmAmber, selected == ThemeSettingsManager.AccentPalette.AMBER)
            updateCard(panelBinding.swatchCyberViolet, panelBinding.imgCheckCyberViolet, selected == ThemeSettingsManager.AccentPalette.VIOLET)
        }

        val currentPalette = ThemeSettingsManager.getAccentPalette(activity)
        updateSwatchSelection(currentPalette)

        fun onSwatchClicked(palette: ThemeSettingsManager.AccentPalette) {
            val curr = ThemeSettingsManager.getAccentPalette(activity)
            val dynamicWasChecked = panelBinding.switchDynamicColor.isChecked
            if (palette != curr || dynamicWasChecked) {
                if (dynamicWasChecked) {
                    ThemeSettingsManager.setDynamicColorEnabled(activity, false)
                }
                ThemeSettingsManager.setAccentPalette(activity, palette)
                updateSwatchSelection(palette)
                seamlessRecreate()
            }
        }

        panelBinding.swatchMonochrome.setOnClickListener { onSwatchClicked(ThemeSettingsManager.AccentPalette.MONOCHROME) }
        panelBinding.swatchForestSage.setOnClickListener { onSwatchClicked(ThemeSettingsManager.AccentPalette.SAGE) }
        panelBinding.swatchDeepOcean.setOnClickListener { onSwatchClicked(ThemeSettingsManager.AccentPalette.OCEAN) }
        panelBinding.swatchWarmAmber.setOnClickListener { onSwatchClicked(ThemeSettingsManager.AccentPalette.AMBER) }
        panelBinding.swatchCyberViolet.setOnClickListener { onSwatchClicked(ThemeSettingsManager.AccentPalette.VIOLET) }

        // 4. Typography style (2x2 Visual Cards)
        fun updateTypographySelection(selected: ThemeSettingsManager.TypographyStyle) {
            val primaryColor = activity.getColor(R.color.vf_primary)
            val outlineVariantColor = activity.getColor(R.color.vf_outline_variant)

            fun highlight(card: MaterialCardView, isSelected: Boolean) {
                card.strokeWidth = if (isSelected) 4 else 2
                card.strokeColor = if (isSelected) primaryColor else outlineVariantColor
            }

            highlight(panelBinding.cardTypoSystem, selected == ThemeSettingsManager.TypographyStyle.SYSTEM)
            highlight(panelBinding.cardTypoSans, selected == ThemeSettingsManager.TypographyStyle.DEFAULT)
            highlight(panelBinding.cardTypoMonospace, selected == ThemeSettingsManager.TypographyStyle.MONOSPACE)
            highlight(panelBinding.cardTypoSerif, selected == ThemeSettingsManager.TypographyStyle.SERIF)
        }

        val currentTypo = ThemeSettingsManager.getTypographyStyle(activity)
        updateTypographySelection(currentTypo)

        fun onTypoClicked(style: ThemeSettingsManager.TypographyStyle) {
            val curr = ThemeSettingsManager.getTypographyStyle(activity)
            if (style != curr) {
                ThemeSettingsManager.setTypographyStyle(activity, style)
                ThemeSettingsManager.applyTypography(activity)
                updateTypographySelection(style)
            }
        }

        panelBinding.cardTypoSystem.setOnClickListener { onTypoClicked(ThemeSettingsManager.TypographyStyle.SYSTEM) }
        panelBinding.cardTypoSans.setOnClickListener { onTypoClicked(ThemeSettingsManager.TypographyStyle.DEFAULT) }
        panelBinding.cardTypoMonospace.setOnClickListener { onTypoClicked(ThemeSettingsManager.TypographyStyle.MONOSPACE) }
        panelBinding.cardTypoSerif.setOnClickListener { onTypoClicked(ThemeSettingsManager.TypographyStyle.SERIF) }

        // 5. Storage governor selection (Horizontal Segmented Bar + Clear Cache Action)
        val targetStorageBtnId = when (StorageGovernor.getMaxStorageGb(activity)) {
            StorageGovernor.LIMIT_1_GB -> R.id.btnStorage1Gb
            StorageGovernor.LIMIT_2_GB -> R.id.btnStorage2Gb
            StorageGovernor.LIMIT_4_GB -> R.id.btnStorage4Gb
            StorageGovernor.LIMIT_8_GB -> R.id.btnStorage8Gb
            StorageGovernor.LIMIT_UNLIMITED -> R.id.btnStorageUnlimited
            else -> R.id.btnStorage2Gb
        }
        panelBinding.toggleStorageLimit.check(targetStorageBtnId)

        panelBinding.toggleStorageLimit.clearOnButtonCheckedListeners()
        panelBinding.toggleStorageLimit.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val limitGb = when (checkedId) {
                    R.id.btnStorage1Gb -> StorageGovernor.LIMIT_1_GB
                    R.id.btnStorage2Gb -> StorageGovernor.LIMIT_2_GB
                    R.id.btnStorage4Gb -> StorageGovernor.LIMIT_4_GB
                    R.id.btnStorage8Gb -> StorageGovernor.LIMIT_8_GB
                    R.id.btnStorageUnlimited -> StorageGovernor.LIMIT_UNLIMITED
                    else -> StorageGovernor.DEFAULT_LIMIT_GB
                }
                StorageGovernor.setMaxStorageGb(activity, limitGb)
                scope.launch(Dispatchers.IO) {
                    StorageGovernor.enforceStorageLimit(activity)
                }
            }
        }

        // Live Cache Size & Clear Cache Action
        fun refreshCacheButtonLabel() {
            scope.launch(Dispatchers.IO) {
                val cacheBytes = StorageGovernor.getAppCacheSizeBytes(activity)
                val formatted = StorageGovernor.formatBytes(cacheBytes)
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        panelBinding.btnClearCacheNow.text = "Clear Cache Now ($formatted)"
                    }
                }
            }
        }
        refreshCacheButtonLabel()

        panelBinding.btnClearCacheNow.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val freedBytes = StorageGovernor.clearCache(activity)
                val formattedFreed = StorageGovernor.formatBytes(freedBytes)
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        panelBinding.btnClearCacheNow.text = "Cache Cleared (0 B)"
                        android.widget.Toast.makeText(activity, "Freed $formattedFreed temporary cache", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 6. Evicted Sections: Diagnostics, Updates, What's New, Resources
        panelBinding.cardSettingsDiagnostics.setOnClickListener { onOpenDiagnostics() }
        panelBinding.btnSettingsCheckUpdates.setOnClickListener { onCheckUpdates() }
        panelBinding.btnSettingsRepairApp.setOnClickListener { onRepairApp() }

        panelBinding.layoutSettingsWhatsNewHeader.setOnClickListener {
            val isVisible = panelBinding.cardSettingsWhatsNew.visibility == View.VISIBLE
            if (isVisible) {
                panelBinding.cardSettingsWhatsNew.visibility = View.GONE
                panelBinding.ivSettingsWhatsNewChevron.animate().rotation(0f).setDuration(200).start()
            } else {
                panelBinding.cardSettingsWhatsNew.visibility = View.VISIBLE
                panelBinding.ivSettingsWhatsNewChevron.animate().rotation(180f).setDuration(200).start()
            }
        }

        panelBinding.btnSettingsLinkGithub.setOnClickListener { onOpenUrl("https://github.com/sahir247/VeilFrame") }
        panelBinding.btnSettingsLinkDocs.setOnClickListener { onOpenUrl("https://github.com/sahir247/VeilFrame#readme") }
        panelBinding.btnSettingsLinkChangelog.setOnClickListener { onOpenUrl("https://github.com/sahir247/VeilFrame/blob/main/RELEASE_NOTES.md") }
        panelBinding.btnSettingsLinkAbout.setOnClickListener { onShowAbout() }

        // 7. Close and dismissal bindings
        panelBinding.btnSettingsClose.setOnClickListener { close() }
        scrimSettings.setOnClickListener { close() }

        // 8. Left-to-right swipe-to-dismiss gesture
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

        // 9. Open slide-over animation or instant restore
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
