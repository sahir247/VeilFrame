package com.veilframe.app.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Central Theme & Customization Manager for VeilFrame.
 * Coordinates Material You Dynamic Colors, AMOLED True Black, Accent Palettes,
 * and Typography styles with consistent persistence and clear precedence rules.
 */
object ThemeSettingsManager {

    private const val PREFS_NAME = "veilframe_theme_prefs"

    const val KEY_THEME_MODE = "theme_mode" // SYSTEM, LIGHT, DARK, AMOLED
    const val KEY_DYNAMIC_COLOR = "dynamic_color" // Boolean
    const val KEY_ACCENT_PALETTE = "accent_palette" // MONOCHROME, SAGE, OCEAN, AMBER, VIOLET
    const val KEY_TYPOGRAPHY = "typography_style" // DEFAULT, MONOSPACE, SERIF

    enum class ThemeMode(val displayName: String) {
        SYSTEM("System Default"),
        LIGHT("Light Mode"),
        DARK("Matte Dark"),
        AMOLED("AMOLED True Black")
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

    fun init(application: Application) {
        val prefs = getPrefs(application)
        val mode = getThemeMode(application)
        applyNightMode(mode)

        val dynamicEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        if (dynamicEnabled && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(application)
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

    fun setThemeMode(context: Context, mode: ThemeMode) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        applyNightMode(mode)
    }

    fun isDynamicColorEnabled(context: Context): Boolean {
        if (!DynamicColors.isDynamicColorAvailable()) return false
        return getPrefs(context).getBoolean(KEY_DYNAMIC_COLOR, true)
    }

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun getAccentPalette(context: Context): AccentPalette {
        val name = getPrefs(context).getString(KEY_ACCENT_PALETTE, AccentPalette.MONOCHROME.name)
        return try {
            AccentPalette.valueOf(name ?: AccentPalette.MONOCHROME.name)
        } catch (_: Exception) {
            AccentPalette.MONOCHROME
        }
    }

    fun setAccentPalette(context: Context, palette: AccentPalette) {
        getPrefs(context).edit().putString(KEY_ACCENT_PALETTE, palette.name).apply()
    }

    fun getTypographyStyle(context: Context): TypographyStyle {
        val name = getPrefs(context).getString(KEY_TYPOGRAPHY, TypographyStyle.DEFAULT.name)
        return try {
            TypographyStyle.valueOf(name ?: TypographyStyle.DEFAULT.name)
        } catch (_: Exception) {
            TypographyStyle.DEFAULT
        }
    }

    fun setTypographyStyle(context: Context, style: TypographyStyle) {
        getPrefs(context).edit().putString(KEY_TYPOGRAPHY, style.name).apply()
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
        val currentNightMode = AppCompatDelegate.getDefaultNightMode()
        return if (currentNightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            currentNightMode == AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    /**
     * Applies custom theme adjustments (AMOLED True Black & Typography) to the activity window.
     */
    fun applyActivityTheme(activity: Activity) {
        val isAmoled = isAmoledActive(activity)
        if (isAmoled) {
            activity.window.decorView.setBackgroundColor(Color.BLACK)
            activity.window.statusBarColor = Color.BLACK
            activity.window.navigationBarColor = Color.BLACK

            val rootId = activity.resources.getIdentifier("rootCoordinator", "id", activity.packageName)
            if (rootId != 0) {
                activity.findViewById<View>(rootId)?.setBackgroundColor(Color.BLACK)
            }
        }

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
