package com.veilframe.app.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.veilframe.app.R

/**
 * Central Theme & Customization Manager for VeilFrame.
 * Coordinates Material You Dynamic Colors, AMOLED True Black, Accent Palettes,
 * and Typography styles with consistent persistence and clear precedence rules.
 *
 * Architecture:
 * - Application.onCreate: set night mode policy only
 * - Activity.onCreate: super.onCreate() → resolve theme overlay → setContentView()
 * - Theme changes: persist preference, set pendingRecreate=true → caller decides when to recreate
 *   (typically deferred until the settings panel is dismissed via Done/back).
 */
object ThemeSettingsManager {

    /** True when at least one theme pref changed during an open settings session.
     *  The caller (MainActivity.closeSettingsOverlay) should call [consumePendingRecreate]
     *  after the close animation to apply a single activity.recreate().
     */
    var pendingRecreate: Boolean = false
        private set

    /** Consumes and clears the flag, returning whether a recreate is needed. */
    fun consumePendingRecreate(): Boolean {
        val needed = pendingRecreate
        pendingRecreate = false
        return needed
    }

    private const val PREFS_NAME = "veilframe_theme_prefs"

    const val KEY_THEME_MODE = "theme_mode" // SYSTEM, LIGHT, DARK, AMOLED
    const val KEY_DYNAMIC_COLOR = "dynamic_color" // Boolean
    const val KEY_ACCENT_PALETTE = "accent_palette" // MONOCHROME, SAGE, OCEAN, AMBER, VIOLET
    const val KEY_TYPOGRAPHY = "typography_style" // DEFAULT, MONOSPACE, SERIF

    enum class ThemeMode(val displayName: String) {
        SYSTEM("System Default"),
        LIGHT("Light"),
        DARK("Dark"),
        AMOLED("AMOLED Dark")
    }

    enum class AccentPalette(val displayName: String, val hexColor: String) {
        MONOCHROME("Monochrome Classic", "#18181B"),
        SAGE("Forest Sage", "#2E7D32"),
        OCEAN("Deep Ocean", "#1976D2"),
        AMBER("Warm Amber", "#D97706"),
        VIOLET("Cyber Violet", "#7C3AED")
    }

    enum class TypographyStyle(val displayName: String, val familyName: String) {
        DEFAULT("System Sans", "sans-serif"),
        MONOSPACE("Forensic Terminal", "monospace"),
        SERIF("Editorial Serif", "serif")
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Called from Application.onCreate ONLY.
     * Sets the global night mode policy. Does NOT apply DynamicColors globally.
     */
    fun init(application: Application) {
        val mode = getThemeMode(application)
        applyNightMode(mode)
    }

    /**
     * Called from Activity.onCreate AFTER super.onCreate() but BEFORE setContentView().
     * Resolves and applies the concrete theme overlay per-Activity:
     * 1. If Dynamic Color is enabled and available, apply DynamicColors
     * 2. Otherwise, apply the selected palette overlay
     * 3. If AMOLED mode, layer the AMOLED overlay on top
     */
    fun applyActivityTheme(activity: Activity) {
        val prefs = getPrefs(activity)
        val dynamicEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        val mode = getThemeMode(activity)

        if (dynamicEnabled && DynamicColors.isDynamicColorAvailable()) {
            // Apply Material You dynamic colors per-Activity
            DynamicColors.applyToActivityIfAvailable(activity)
        } else {
            // Apply the selected palette overlay
            val palette = getAccentPalette(activity)
            val paletteOverlayRes = getPaletteOverlayRes(palette)
            if (paletteOverlayRes != 0) {
                activity.theme.applyStyle(paletteOverlayRes, true)
            }
        }

        // Layer AMOLED overlay on top if active
        if (mode == ThemeMode.AMOLED && isNightModeActive(activity)) {
            activity.theme.applyStyle(R.style.ThemeOverlay_VeilFrame_Amoled, true)
        }

        // Apply typography
        val typo = getTypographyStyle(activity)
        if (typo != TypographyStyle.DEFAULT) {
            // Typography is applied post-inflation via applyTypography()
        }
    }

    /**
     * Called post-setContentView to apply custom typography to the view tree.
     */
    fun applyTypography(activity: Activity) {
        val typo = getTypographyStyle(activity)
        if (typo != TypographyStyle.DEFAULT) {
            val tf = when (typo) {
                TypographyStyle.MONOSPACE -> Typeface.MONOSPACE
                TypographyStyle.SERIF -> Typeface.SERIF
                else -> Typeface.DEFAULT
            }
            applyTypefaceRecursively(activity.window.decorView, tf)
        }
    }

    fun getThemeMode(context: Context): ThemeMode {
        val name = getPrefs(context).getString(KEY_THEME_MODE, ThemeMode.DARK.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.DARK.name)
        } catch (_: Exception) {
            ThemeMode.DARK
        }
    }

    /**
     * Persists the theme mode and applies night mode immediately (safe without recreate).
     * Sets [pendingRecreate] so the caller can recreate once the settings panel is dismissed.
     */
    fun setThemeMode(activity: Activity, mode: ThemeMode) {
        getPrefs(activity).edit().putString(KEY_THEME_MODE, mode.name).apply()
        applyNightMode(mode)
        pendingRecreate = true
    }

    fun isDynamicColorEnabled(context: Context): Boolean {
        if (!DynamicColors.isDynamicColorAvailable()) return false
        return getPrefs(context).getBoolean(KEY_DYNAMIC_COLOR, true)
    }

    /**
     * Persists the dynamic color preference.
     * Sets [pendingRecreate] so the caller can recreate once the settings panel is dismissed.
     */
    fun setDynamicColorEnabled(activity: Activity, enabled: Boolean) {
        getPrefs(activity).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        pendingRecreate = true
    }

    fun getAccentPalette(context: Context): AccentPalette {
        val name = getPrefs(context).getString(KEY_ACCENT_PALETTE, AccentPalette.MONOCHROME.name)
        return try {
            AccentPalette.valueOf(name ?: AccentPalette.MONOCHROME.name)
        } catch (_: Exception) {
            AccentPalette.MONOCHROME
        }
    }

    /**
     * Persists the accent palette selection.
     * Sets [pendingRecreate] so the caller can recreate once the settings panel is dismissed.
     */
    fun setAccentPalette(activity: Activity, palette: AccentPalette) {
        getPrefs(activity).edit().putString(KEY_ACCENT_PALETTE, palette.name).apply()
        pendingRecreate = true
    }

    fun getTypographyStyle(context: Context): TypographyStyle {
        val name = getPrefs(context).getString(KEY_TYPOGRAPHY, TypographyStyle.DEFAULT.name)
        return try {
            TypographyStyle.valueOf(name ?: TypographyStyle.DEFAULT.name)
        } catch (_: Exception) {
            TypographyStyle.DEFAULT
        }
    }

    /**
     * Persists the typography style selection.
     * Sets [pendingRecreate] so the caller can recreate once the settings panel is dismissed.
     */
    fun setTypographyStyle(activity: Activity, style: TypographyStyle) {
        getPrefs(activity).edit().putString(KEY_TYPOGRAPHY, style.name).apply()
        pendingRecreate = true
    }

    fun applyNightMode(mode: ThemeMode) {
        when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            ThemeMode.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            ThemeMode.DARK, ThemeMode.AMOLED -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    fun isAmoledActive(context: Context): Boolean {
        if (getThemeMode(context) != ThemeMode.AMOLED) return false
        return isNightModeActive(context)
    }

    private fun isNightModeActive(context: Context): Boolean {
        val currentNightMode = AppCompatDelegate.getDefaultNightMode()
        return if (currentNightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            currentNightMode == AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    /**
     * Maps AccentPalette enum to the corresponding style resource ID.
     */
    private fun getPaletteOverlayRes(palette: AccentPalette): Int {
        return when (palette) {
            AccentPalette.MONOCHROME -> R.style.ThemeOverlay_VeilFrame_Palette_Monochrome
            AccentPalette.SAGE -> R.style.ThemeOverlay_VeilFrame_Palette_Sage
            AccentPalette.OCEAN -> R.style.ThemeOverlay_VeilFrame_Palette_Ocean
            AccentPalette.AMBER -> R.style.ThemeOverlay_VeilFrame_Palette_Amber
            AccentPalette.VIOLET -> R.style.ThemeOverlay_VeilFrame_Palette_Violet
        }
    }

    private fun applyTypefaceRecursively(view: View, typeface: Typeface) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.setTypeface(typeface, style)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTypefaceRecursively(view.getChildAt(i), typeface)
            }
        }
    }
}
