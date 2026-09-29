package com.veilframe.app.qr.exporter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.veilframe.app.qr.AnimatedQrGenerator
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrOutputFormat
import com.veilframe.app.qr.model.QrOutputResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles exporting QR codes as PNG, JPEG (with lossy compression warning),
 * vector SVG, animated GIF, and video (MP4/MOV) with EFQRCode-grade typed error boundary.
 */
object QrExporter {

    private fun timestampName(ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "VeilFrame_QR_$ts.$ext"
    }

    /**
     * Typed export pipeline saving [bitmap] to the device Pictures/VeilFrame gallery.
     * Verifies bitmap compression return code and returns structured [QrOutputResult].
     */
    suspend fun saveBitmapTyped(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.BitmapAllocationFailed(
                    width = 0,
                    height = 0,
                    cause = IllegalStateException("Cannot export recycled bitmap")
                )
            )
        }

        val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val name = timestampName(ext)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )

                val compressed = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(format, quality, out)
                } ?: false

                if (!compressed) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        if (format == Bitmap.CompressFormat.PNG)
                            QrError.Output.PngEncodingFailed("Bitmap compression returned false for PNG")
                        else
                            QrError.Output.JpegEncodingFailed("Bitmap compression returned false for JPEG")
                    )
                }

                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                QrOutputResult.Success(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VeilFrame")
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed(dir.absolutePath)
                    )
                }
                val file = File(dir, name)
                val compressed = FileOutputStream(file).use { out -> bitmap.compress(format, quality, out) }
                if (!compressed) {
                    file.delete()
                    return@withContext QrOutputResult.Failure(
                        if (format == Bitmap.CompressFormat.PNG)
                            QrError.Output.PngEncodingFailed("Bitmap compression returned false for PNG")
                        else
                            QrError.Output.JpegEncodingFailed("Bitmap compression returned false for JPEG")
                    )
                }
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (_: Exception) {
                    Uri.fromFile(file)
                }
                QrOutputResult.Success(uri)
            }
        } catch (t: Throwable) {
            QrOutputResult.Failure(QrError.fromThrowable(t))
        }
    }

    /**
     * Saves [bitmap] to the device Pictures/VeilFrame gallery.
     * Backwards-compatible nullable URI return.
     */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Uri? = saveBitmapTyped(context, bitmap, format, quality).getOrNull()

    /**
     * Validates [bitmap] with strict scanability gate prior to saving.
     */
    suspend fun saveToGalleryValidated(
        context: Context,
        bitmap: Bitmap,
        content: String,
        design: QrDesign,
        matrix: QrMatrix,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Pair<Uri?, com.veilframe.app.qr.validation.ScanabilityReport> {
        val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(bitmap, design, matrix, content)
        if (!report.isScanReady) {
            return Pair(null, report)
        }
        val uri = saveToGallery(context, bitmap, format, quality)
        return Pair(uri, report)
    }

    /**
     * Internal typed helper for writing SVG content to storage.
     */
    suspend fun saveSvgStringTyped(
        context: Context,
        svgData: String
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (svgData.isBlank()) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.SvgRenderFailed("SVG markup is empty")
            )
        }
        val name = timestampName("svg")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VeilFrame")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(svgData.toByteArray(Charsets.UTF_8))
                }
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                QrOutputResult.Success(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VeilFrame")
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed(dir.absolutePath)
                    )
                }
                val file = File(dir, name)
                FileOutputStream(file).use { out -> out.write(svgData.toByteArray(Charsets.UTF_8)) }
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (_: Exception) {
                    Uri.fromFile(file)
                }
                QrOutputResult.Success(uri)
            }
        } catch (t: Throwable) {
            QrOutputResult.Failure(QrError.fromThrowable(t))
        }
    }

    /**
     * Saves true vector SVG markup returning typed [QrOutputResult].
     */
    suspend fun saveSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        val svgData = try {
            SvgExporter.generateSvg(matrix, design)
        } catch (t: Throwable) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.SvgRenderFailed(t.message ?: "SVG generation failed", t)
            )
        }
        saveSvgStringTyped(context, svgData)
    }

    /**
     * Saves true vector SVG markup to the Downloads or Documents directory.
     */
    suspend fun saveSvg(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): Uri? = saveSvgTyped(context, matrix, design).getOrNull()

    /**
     * Saves animated GIF bytes to the device Pictures/VeilFrame gallery returning typed [QrOutputResult].
     */
    suspend fun saveGifTyped(
        context: Context,
        gifBytes: ByteArray
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (gifBytes.isEmpty()) {
            return@withContext QrOutputResult.Failure(
                QrError.Output.GifEncodingFailed("GIF byte array is empty")
            )
        }
        val name = timestampName("gif")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(gifBytes)
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                QrOutputResult.Success(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VeilFrame")
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed(dir.absolutePath)
                    )
                }
                val file = File(dir, name)
                FileOutputStream(file).use { out -> out.write(gifBytes) }
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (_: Exception) {
                    Uri.fromFile(file)
                }
                QrOutputResult.Success(uri)
            }
        } catch (t: Throwable) {
            QrOutputResult.Failure(QrError.fromThrowable(t))
        }
    }

    /**
     * Saves animated GIF bytes to the device Pictures/VeilFrame gallery.
     */
    suspend fun saveGif(
        context: Context,
        gifBytes: ByteArray
    ): Uri? = saveGifTyped(context, gifBytes).getOrNull()

    /**
     * Saves animated SVG markup returning typed [QrOutputResult].
     */
    suspend fun saveAnimatedSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        frames: List<QrFrame>
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        val svgData = try {
            AnimatedQrGenerator.generateAnimatedSvg(matrix, design, frames)
        } catch (t: Throwable) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.SvgRenderFailed(t.message ?: "Animated SVG generation failed", t)
            )
        }
        saveSvgStringTyped(context, svgData)
    }

    /**
     * Saves animated SVG markup to the Downloads or Documents directory.
     */
    suspend fun saveAnimatedSvg(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        frames: List<QrFrame>
    ): Uri? = saveAnimatedSvgTyped(context, matrix, design, frames).getOrNull()

    /**
     * Saves a video file (.mp4 or .mov) returning typed [QrOutputResult].
     */
    suspend fun saveVideoTyped(
        context: Context,
        videoFile: File
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext QrOutputResult.Failure(
                QrError.Output.VideoEncodingFailed("Video file does not exist or is empty")
            )
        }
        val isMov = videoFile.extension.equals("mov", ignoreCase = true)
        val ext = if (isMov) "mov" else "mp4"
        val mime = if (isMov) "video/quicktime" else "video/mp4"
        val name = timestampName(ext)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VeilFrame")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    videoFile.inputStream().use { input -> input.copyTo(out) }
                }
                cv.clear()
                cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, cv, null, null)
                QrOutputResult.Success(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VeilFrame")
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed(dir.absolutePath)
                    )
                }
                val file = File(dir, name)
                videoFile.copyTo(file, overwrite = true)
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (_: Exception) {
                    Uri.fromFile(file)
                }
                QrOutputResult.Success(uri)
            }
        } catch (t: Throwable) {
            QrOutputResult.Failure(QrError.fromThrowable(t))
        }
    }

    /**
     * Saves an MP4 video file to the device Movies/VeilFrame directory.
     */
    suspend fun saveVideo(
        context: Context,
        videoFile: File
    ): Uri? = saveVideoTyped(context, videoFile).getOrNull()

    /**
     * Authoritative typed export dispatcher implementing EFQRCode-grade multi-format export pipeline.
     */
    suspend fun exportTyped(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        format: QrOutputFormat = QrOutputFormat.Png
    ): QrOutputResult<Uri> {
        if (content.isBlank()) {
            return QrOutputResult.Failure(QrError.Input.EmptyContent)
        }

        val matrix = try {
            QrGenerator.generateMatrix(content, design)
        } catch (t: Throwable) {
            return QrOutputResult.Failure(QrError.fromThrowable(t))
        }

        return when (format) {
            is QrOutputFormat.Png -> {
                val renderResult = QrGenerator.generateWithResult(content, design)
                if (renderResult is QrRenderResult.Failure) {
                    return QrOutputResult.Failure(renderResult.qrError)
                }
                val bmp = (renderResult as QrRenderResult.Success).bitmap
                    ?: return QrOutputResult.Failure(
                        QrError.Rendering.BitmapAllocationFailed(design.outputSize, design.outputSize)
                    )
                saveBitmapTyped(context, bmp, Bitmap.CompressFormat.PNG, 100)
            }
            is QrOutputFormat.Jpeg -> {
                val renderResult = QrGenerator.generateWithResult(content, design)
                if (renderResult is QrRenderResult.Failure) {
                    return QrOutputResult.Failure(renderResult.qrError)
                }
                val bmp = (renderResult as QrRenderResult.Success).bitmap
                    ?: return QrOutputResult.Failure(
                        QrError.Rendering.BitmapAllocationFailed(design.outputSize, design.outputSize)
                    )
                saveBitmapTyped(context, bmp, Bitmap.CompressFormat.JPEG, format.quality)
            }
            is QrOutputFormat.Svg -> {
                if (AnimatedQrGenerator.isDesignAnimated(design)) {
                    val frames = AnimatedQrGenerator.extractSourceFrames(design)
                    val rendered = if (frames.isNotEmpty()) {
                        AnimatedQrGenerator.renderFrames(matrix, design, frames, design.outputSize)
                    } else emptyList()
                    val svgData = try {
                        AnimatedQrGenerator.generateAnimatedSvg(matrix, design, rendered)
                    } catch (t: Throwable) {
                        return QrOutputResult.Failure(
                            QrError.Rendering.SvgRenderFailed(t.message ?: "Animated SVG generation failed", t)
                        )
                    }
                    saveSvgStringTyped(context, svgData)
                } else {
                    saveSvgTyped(context, matrix, design)
                }
            }
            is QrOutputFormat.Gif -> {
                val frames = AnimatedQrGenerator.extractSourceFrames(design)
                if (frames.isEmpty()) {
                    return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
                }
                val rendered = AnimatedQrGenerator.renderFrames(matrix, design, frames, design.outputSize)
                val gifBytes = try {
                    AnimatedQrGenerator.encodeToGif(rendered, design.outputSize, design.outputSize, format.loopCount)
                } catch (t: Throwable) {
                    return QrOutputResult.Failure(QrError.Output.GifEncodingFailed(t.message ?: "GIF encoding failed", t))
                }
                saveGifTyped(context, gifBytes)
            }
            is QrOutputFormat.Video -> {
                val frames = AnimatedQrGenerator.extractSourceFrames(design)
                if (frames.isEmpty()) {
                    return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
                }
                val rendered = AnimatedQrGenerator.renderFrames(matrix, design, frames, design.outputSize)
                val ext = if (format.isMov) "mov" else "mp4"
                val tempFile = File.createTempFile("qr_export_", ".$ext", context.cacheDir)
                try {
                    val vidResult = AnimatedQrGenerator.encodeToVideoResult(rendered, tempFile, format.fps)
                    if (vidResult is QrOutputResult.Failure) {
                        return QrOutputResult.Failure(vidResult.error)
                    }
                    saveVideoTyped(context, tempFile)
                } finally {
                    tempFile.delete()
                }
            }
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
        val chooser = Intent.createChooser(intent, "Share QR Code").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
