package com.veilframe.app.qr.exporter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles exporting QR codes as PNG, JPEG (with lossy compression warning),
 * and vector SVG.
 */
object QrExporter {

    private fun timestampName(ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "VeilFrame_QR_$ts.$ext"
    }

    /**
     * Saves [bitmap] to the device Pictures/VeilFrame gallery.
     * Note: For JPEG format, lossy compression artifacts may degrade QR scanning reliability.
     */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Uri? = withContext(Dispatchers.IO) {
        val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val name = timestampName(ext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(format, quality, out)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VeilFrame")
            dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { out -> bitmap.compress(format, quality, out) }
            Uri.fromFile(file)
        }
    }

    /**
     * Saves true vector SVG markup to the Downloads or Documents directory.
     */
    suspend fun saveSvg(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): Uri? = withContext(Dispatchers.IO) {
        val svgData = SvgExporter.generateSvg(matrix, design)
        val name = timestampName("svg")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VeilFrame")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(svgData.toByteArray(Charsets.UTF_8))
            }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VeilFrame")
            dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { out -> out.write(svgData.toByteArray(Charsets.UTF_8)) }
            Uri.fromFile(file)
        }
    }

    /**
     * Opens system share sheet for [bitmap].
     */
    suspend fun share(context: Context, bitmap: Bitmap) {
        val uri = saveToGallery(context, bitmap) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share QR Code"))
    }
}
