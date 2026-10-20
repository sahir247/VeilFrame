package com.veilframe.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.veilframe.app.BuildConfig

/**
 * Centralized provider for application versioning and User-Agent telemetry.
 * Dynamically resolves from [BuildConfig] with graceful fallback to [PackageManager].
 */
object AppVersionProvider {

    /**
     * Sourced dynamically from BuildConfig.VERSION_NAME with graceful fallback to PackageManager.
     */
    fun getVersionName(context: Context? = null): String {
        val buildVersion = runCatching { BuildConfig.VERSION_NAME }.getOrNull()
        if (!buildVersion.isNullOrBlank() && buildVersion != "null") {
            return buildVersion
        }

        if (context != null) {
            val pmVersion = runCatching {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName
            }.getOrNull()

            if (!pmVersion.isNullOrBlank()) {
                return pmVersion
            }
        }

        return "1.0.0"
    }

    /**
     * Sourced dynamically from BuildConfig.VERSION_CODE with graceful fallback to PackageManager.
     */
    fun getVersionCode(context: Context? = null): Long {
        val buildCode = runCatching { BuildConfig.VERSION_CODE.toLong() }.getOrNull()
        if (buildCode != null && buildCode > 0L) {
            return buildCode
        }

        if (context != null) {
            val pmCode = runCatching {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            }.getOrNull()

            if (pmCode != null && pmCode > 0L) {
                return pmCode
            }
        }

        return 1L
    }

    /**
     * Formatted display version string, e.g. "v2.2.9"
     */
    fun getDisplayVersion(context: Context? = null): String {
        return "v${getVersionName(context)}"
    }

    /**
     * Canonical User-Agent string for network interactions, e.g. "VeilFrame-Android/2.2.9"
     */
    fun getUserAgent(context: Context? = null): String {
        return "VeilFrame-Android/${getVersionName(context)}"
    }
}
