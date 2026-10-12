package com.veilframe.app.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage governor for VeilFrame.
 * Monitors and enforces storage quotas across cache and export directories
 * using FIFO pruning while strictly protecting AI and CV models.
 */
object StorageGovernor {
    private const val PREFS_NAME = "veilframe_storage_prefs"
    const val KEY_MAX_STORAGE_GB = "max_storage_gb"

    const val LIMIT_1_GB = 1L
    const val LIMIT_2_GB = 2L
    const val LIMIT_4_GB = 4L
    const val LIMIT_8_GB = 8L
    const val LIMIT_UNLIMITED = -1L

    const val DEFAULT_LIMIT_GB = LIMIT_2_GB

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMaxStorageGb(context: Context): Long {
        return getPrefs(context).getLong(KEY_MAX_STORAGE_GB, DEFAULT_LIMIT_GB)
    }

    fun setMaxStorageGb(context: Context, limitGb: Long) {
        getPrefs(context).edit().putLong(KEY_MAX_STORAGE_GB, limitGb).apply()
    }

    /**
     * Calculates the total size in bytes of cache and export directories, excluding protected model files.
     */
    fun calculateManagedStorageBytes(context: Context): Long {
        return getManagedFiles(context).sumOf { it.length() }
    }

    /**
     * Enforces FIFO pruning on exports and cache if max storage limit is reached.
     * Excludes models directories.
     */
    suspend fun enforceStorageLimit(context: Context): Long = withContext(Dispatchers.IO) {
        val limitGb = getMaxStorageGb(context)
        if (limitGb == LIMIT_UNLIMITED || limitGb <= 0) return@withContext 0L

        val maxBytes = limitGb * 1024L * 1024L * 1024L
        val managedFiles = getManagedFiles(context).sortedBy { it.lastModified() } // FIFO (oldest first)
        var currentTotal = managedFiles.sumOf { it.length() }
        var bytesFreed = 0L

        if (currentTotal > maxBytes) {
            val targetBytes = (maxBytes * 0.90).toLong() // prune down to 90% headroom
            for (file in managedFiles) {
                if (currentTotal <= targetBytes) break
                val size = file.length()
                if (file.delete()) {
                    currentTotal -= size
                    bytesFreed += size
                }
            }
        }
        bytesFreed
    }

    @androidx.annotation.VisibleForTesting
    internal fun getManagedFiles(context: Context): List<File> {
        // Only disposable cache directories are subject to automated quota pruning.
        // User-facing export folders (files/exports, files/cv/exports) are protected from auto-deletion.
        val dirs = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir
        )

        val result = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().filter { file ->
                file.isFile && !isProtectedModelFile(file)
            }.forEach {
                result.add(it)
            }
        }
        return result
    }

    internal fun isProtectedModelFile(file: File): Boolean {
        val path = file.absolutePath.replace('\\', '/')
        if (path.contains("/models/") || path.contains("/cv_models/")) return true
        val ext = file.extension.lowercase()
        if (ext in listOf("onnx", "tflite", "bin", "param", "builtin")) return true
        return false
    }
}
