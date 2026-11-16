package com.veilframe.app.cv.core

import android.content.Context
import com.veilframe.app.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Stages bundled LiteRT models once per installed APK revision into private no-backup storage.
 *
 * CompiledModel.create(assetManager, name) holds a copy of the asset in native memory.
 * Loading a file path instead allows the model to be memory mapped and page-reclaimed.
 * Downloaded models are already files and should bypass this helper.
 */
internal object LiteRtModelStorage {
    @Synchronized
    fun stageBundled(context: Context, assetName: String, key: String): File {
        require(key.matches(Regex("[a-z0-9-]+"))) { "Invalid LiteRT model cache key" }
        val app = context.applicationContext
        val directory = app.noBackupFilesDir
        val apkRevision = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        val prefix = "vf-litert-$key-"
        val destination = File(directory, "$prefix${BuildConfig.VERSION_CODE}-$apkRevision.tflite")
        if (destination.isFile && destination.length() > 0L) return destination

        val temporary = File.createTempFile(prefix, ".partial", directory)
        try {
            app.assets.open(assetName).use { source ->
                FileOutputStream(temporary).use { output ->
                    source.copyTo(output, bufferSize = 64 * 1024)
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0L) { "Empty bundled LiteRT model: $assetName" }
            if (destination.exists()) check(destination.delete())
            check(temporary.renameTo(destination)) { "Cannot atomically stage model: $assetName" }

            // Remove stale bundled revisions only after the current version is safely staged.
            directory.listFiles()?.forEach { candidate ->
                if (candidate != destination && candidate.name.startsWith(prefix) &&
                    candidate.name.endsWith(".tflite") && candidate.isFile
                ) {
                    candidate.delete()
                }
            }
            return destination
        } finally {
            temporary.delete()
        }
    }
}
