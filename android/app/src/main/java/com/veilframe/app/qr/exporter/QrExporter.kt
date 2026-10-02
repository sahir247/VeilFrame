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
import androidx.annotation.VisibleForTesting
import com.veilframe.app.qr.model.ImageSource
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
    @Deprecated(
        message = "Use saveBitmapTyped(context, bitmap, format, quality) for typed error handling.",
        replaceWith = ReplaceWith("saveBitmapTyped(context, bitmap, format, quality).getOrNull()")
    )
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Uri? = saveBitmapTyped(context, bitmap, format, quality).getOrNull()

    /**
     * Validates [bitmap] with strict scanability gate prior to saving.
     */
    @Deprecated(
        message = "Use exportTyped(context, content, design, format) for unified validation and export.",
        replaceWith = ReplaceWith("exportTyped(context, content, design, QrOutputFormat.Png)")
    )
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
        @Suppress("DEPRECATION")
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
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed("Failed to open output stream for MediaStore URI $uri")
                    )
                }
                stream.use { out ->
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
     * Strict vector rasterization & scanability validation gate for emitted SVG markup.
     * Rasterizes [svgData] using [DeterministicSvgRasterizer] and validates quiet zone,
     * contrast separation, finder separators, and decodability.
     */
    internal suspend fun validateSvgScanability(
        svgData: String,
        design: QrDesign,
        matrix: QrMatrix,
        expectedContent: String?
    ): QrError? {
        val rasterBmp = try {
            com.veilframe.app.qr.raster.DeterministicSvgRasterizer.rasterize(
                svgData,
                design.outputSize,
                design.outputSize
            )
        } catch (t: Throwable) {
            return QrError.Validation.VectorRasterizationFailed(t.message ?: "Rasterizer threw exception")
        } ?: return QrError.Validation.VectorRasterizationFailed("SVG rasterizer returned null")

        val targetContent = if (!expectedContent.isNullOrBlank()) {
            expectedContent
        } else {
            val decoded = try {
                val intArray = IntArray(rasterBmp.width * rasterBmp.height)
                rasterBmp.getPixels(intArray, 0, rasterBmp.width, 0, 0, rasterBmp.width, rasterBmp.height)
                val source = com.google.zxing.RGBLuminanceSource(rasterBmp.width, rasterBmp.height, intArray)
                val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                com.google.zxing.MultiFormatReader().decode(binaryBitmap)?.text ?: ""
            } catch (_: Throwable) {
                ""
            }
            if (decoded.isEmpty()) {
                rasterBmp.recycle()
                return QrError.Validation.ScanabilityFailed("Rasterized vector SVG could not be decoded by barcode reader")
            }
            decoded
        }

        val report = com.veilframe.app.qr.validation.ScanabilityValidator.validateStrict(
            rasterBmp,
            design,
            matrix,
            targetContent
        )
        rasterBmp.recycle()

        return if (!report.isScanReady && !report.validationSkipped) {
            QrError.Validation.ScanabilityFailed(
                report.warnings.firstOrNull() ?: "Strict scanability validation failed",
                report.warnings
            )
        } else {
            null
        }
    }

    /**
     * Validates an animated SVG across every frame in the animation sequence, plus
     * the final assembled document (at frame 0), ensuring no animation frame degrades scanability.
     */
    internal suspend fun validateAnimatedSvgScanability(
        matrix: QrMatrix,
        design: QrDesign,
        expectedContent: String?
    ): QrError? {
        val sourceFrames = AnimatedQrGenerator.extractSourceFrames(design)
        if (sourceFrames.isEmpty()) {
            val staticSvg = try {
                SvgExporter.generateSvg(matrix, design)
            } catch (t: Throwable) {
                return QrError.Rendering.SvgRenderFailed(t.message ?: "SVG generation failed", t)
            }
            return validateSvgScanability(staticSvg, design, matrix, expectedContent)
        }

        // Validate every frame in the animation sequence to ensure no frame degrades scanability
        for ((idx, frame) in sourceFrames.withIndex()) {
            val frameDesign = design.copy(
                imageSource = design.imageSource.copy(
                    source = com.veilframe.app.qr.model.ImageSource.Memory(frame.bitmap)
                )
            )
            val frameSvg = try {
                SvgExporter.generateSvg(matrix, frameDesign)
            } catch (t: Throwable) {
                return QrError.Rendering.SvgRenderFailed("Frame $idx SVG generation failed: ${t.message}", t)
            }
            val frameErr = validateSvgScanability(frameSvg, frameDesign, matrix, expectedContent)
            if (frameErr != null) {
                return if (frameErr is QrError.Validation.ScanabilityFailed) {
                    QrError.Validation.ScanabilityFailed(
                        "Animation frame $idx degrades scanability: ${frameErr.reason}",
                        frameErr.warnings
                    )
                } else {
                    frameErr
                }
            }
        }

        // Validate the assembled animated SVG document
        val assembledSvg = try {
            AnimatedQrGenerator.generateAnimatedSvg(matrix, design)
        } catch (t: Throwable) {
            return QrError.Rendering.SvgRenderFailed(t.message ?: "Animated SVG assembly failed", t)
        }
        return validateSvgScanability(assembledSvg, design, matrix, expectedContent)
    }

    /**
     * Saves true vector SVG markup returning typed [QrOutputResult].
     * Fails closed if the generated SVG fails rasterization or strict scanability gating.
     */
    /**
     * Canonical overload: saves true vector SVG markup returning typed [QrOutputResult].
     */
    suspend fun saveSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): QrOutputResult<Uri> = saveSvgTyped(context, matrix, design, content = null)

    /**
     * Saves true vector SVG markup returning typed [QrOutputResult].
     * Fails closed if the generated SVG fails rasterization or strict scanability gating.
     */
    suspend fun saveSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        content: String?
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        val svgData = try {
            SvgExporter.generateSvg(matrix, design)
        } catch (t: Throwable) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.SvgRenderFailed(t.message ?: "SVG generation failed", t)
            )
        }
        val validationErr = validateSvgScanability(svgData, design, matrix, content)
        if (validationErr != null) {
            return@withContext QrOutputResult.Failure(validationErr)
        }
        saveSvgStringTyped(context, svgData)
    }

    /**
     * Saves true vector SVG markup to the Downloads or Documents directory.
     */
    @Deprecated(
        message = "Use saveSvgTyped(context, matrix, design) for typed error handling.",
        replaceWith = ReplaceWith("saveSvgTyped(context, matrix, design).getOrNull()")
    )
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
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed("Failed to open output stream for MediaStore URI $uri")
                    )
                }
                stream.use { out ->
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
    @Deprecated(
        message = "Use saveGifTyped(context, gifBytes) for typed error handling.",
        replaceWith = ReplaceWith("saveGifTyped(context, gifBytes).getOrNull()")
    )
    suspend fun saveGif(
        context: Context,
        gifBytes: ByteArray
    ): Uri? = saveGifTyped(context, gifBytes).getOrNull()

    /**
     * Saves animated SVG markup returning typed [QrOutputResult].
     * [design] is the authoritative single source of truth for artwork frames and timing.
     * Fails closed if any frame in the animation sequence degrades scanability.
     */
    /**
     * Canonical overload: saves animated SVG markup returning typed [QrOutputResult].
     */
    suspend fun saveAnimatedSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): QrOutputResult<Uri> = saveAnimatedSvgTyped(context, matrix, design, content = null)

    /**
     * Saves animated SVG markup returning typed [QrOutputResult].
     * [design] is the authoritative single source of truth for artwork frames and timing.
     * Fails closed if any frame in the animation sequence degrades scanability.
     */
    suspend fun saveAnimatedSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        content: String?
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        val validationErr = validateAnimatedSvgScanability(matrix, design, content)
        if (validationErr != null) {
            return@withContext QrOutputResult.Failure(validationErr)
        }
        val svgData = try {
            AnimatedQrGenerator.generateAnimatedSvg(matrix, design)
        } catch (t: Throwable) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.SvgRenderFailed(t.message ?: "Animated SVG generation failed", t)
            )
        }
        saveSvgStringTyped(context, svgData)
    }

    /**
     * Pure transformation used by the compatibility overloads: injects [sourceArtworkFrames] into
     * [design] as [ImageSource.Animated] so [QrDesign] remains the single source of truth.
     *
     * When [sourceArtworkFrames] is empty the original [design] is returned unchanged.
     * This function is extracted for testability so unit tests can verify the actual production
     * transformation rather than reconstructing it manually.
     */
    @VisibleForTesting
    internal fun applyArtworkFramesToDesign(design: QrDesign, sourceArtworkFrames: List<QrFrame>): QrDesign {
        if (sourceArtworkFrames.isEmpty()) return design
        return design.copy(
            imageSource = design.imageSource.copy(
                source = ImageSource.Animated(
                    sourceArtworkFrames.map { it.bitmap },
                    sourceArtworkFrames.map { it.durationMs }
                )
            )
        )
    }

    /**
     * Compatibility overload routing externally supplied source artwork frames safely
     * into [QrDesign.imageSource] as [ImageSource.Animated] so [design] remains the single source of truth.
     * Prevents pre-rendered QR bitmaps from being nested/double-rendered into SVG vector trees.
     */
    @Deprecated(
        "Use saveAnimatedSvgTyped(context, matrix, design) where QrDesign is the single source of truth.",
        ReplaceWith("saveAnimatedSvgTyped(context, matrix, design)")
    )
    suspend fun saveAnimatedSvgTyped(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        sourceArtworkFrames: List<QrFrame>
    ): QrOutputResult<Uri> {
        return saveAnimatedSvgTyped(context, matrix, applyArtworkFramesToDesign(design, sourceArtworkFrames))
    }

    /**
     * Saves animated SVG markup to the Downloads or Documents directory.
     * [design] is the authoritative single source of truth for artwork frames and timing.
     */
    @Deprecated(
        message = "Use saveAnimatedSvgTyped(context, matrix, design) for typed error handling.",
        replaceWith = ReplaceWith("saveAnimatedSvgTyped(context, matrix, design).getOrNull()")
    )
    suspend fun saveAnimatedSvg(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign
    ): Uri? = saveAnimatedSvgTyped(context, matrix, design).getOrNull()

    /**
     * Compatibility overload routing externally supplied source artwork frames safely
     * into [QrDesign.imageSource] as [ImageSource.Animated].
     */
    @Suppress("DEPRECATION")
    @Deprecated(
        "Use saveAnimatedSvg(context, matrix, design) where QrDesign is the single source of truth.",
        ReplaceWith("saveAnimatedSvg(context, matrix, design)")
    )
    suspend fun saveAnimatedSvg(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        sourceArtworkFrames: List<QrFrame>
    ): Uri? = saveAnimatedSvgTyped(context, matrix, design, sourceArtworkFrames).getOrNull()

    /**
     * Saves a video file (.mp4, .mov, or .m4v) returning typed [QrOutputResult].
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
        val ext = videoFile.extension.lowercase(Locale.US)
        val mime = when (ext) {
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            else -> "video/mp4"
        }
        val name = timestampName(if (ext.isNotEmpty()) ext else "mp4")

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
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed("Failed to open output stream for MediaStore URI $uri")
                    )
                }
                stream.use { out ->
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
     * Saves a video file to the device Movies/VeilFrame directory.
     */
    @Deprecated(
        message = "Use saveVideoTyped(context, videoFile) for typed error handling.",
        replaceWith = ReplaceWith("saveVideoTyped(context, videoFile).getOrNull()")
    )
    suspend fun saveVideo(
        context: Context,
        videoFile: File
    ): Uri? = saveVideoTyped(context, videoFile).getOrNull()

    /**
     * Saves [bitmap] as a printable PDF document returning typed [QrOutputResult].
     */
    suspend fun savePdfTyped(
        context: Context,
        bitmap: Bitmap,
        pageWidthPoints: Int = 595,
        pageHeightPoints: Int = 842
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) {
            return@withContext QrOutputResult.Failure(
                QrError.Rendering.BitmapAllocationFailed(
                    width = 0,
                    height = 0,
                    cause = IllegalStateException("Cannot export recycled bitmap to PDF")
                )
            )
        }
        val name = timestampName("pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidthPoints, pageHeightPoints, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            val scale = (pageWidthPoints.toFloat() * 0.8f) / bitmap.width.toFloat()
            val targetW = bitmap.width * scale
            val targetH = bitmap.height * scale
            val left = (pageWidthPoints - targetW) / 2f
            val top = (pageHeightPoints - targetH) / 2f
            val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dst = android.graphics.RectF(left, top, left + targetW, top + targetH)
            canvas.drawBitmap(bitmap, src, dst, null)
            document.finishPage(page)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VeilFrame")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed("Failed to open output stream for MediaStore URI $uri")
                    )
                }
                stream.use { out ->
                    document.writeTo(out)
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
                FileOutputStream(file).use { out ->
                    document.writeTo(out)
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
        } finally {
            document.close()
        }
    }

    /**
     * Saves [bitmap] as a printable PDF document.
     */
    @Deprecated(
        message = "Use savePdfTyped(context, bitmap, pageWidthPoints, pageHeightPoints) for typed error handling.",
        replaceWith = ReplaceWith("savePdfTyped(context, bitmap, pageWidthPoints, pageHeightPoints).getOrNull()")
    )
    suspend fun savePdf(
        context: Context,
        bitmap: Bitmap,
        pageWidthPoints: Int = 595,
        pageHeightPoints: Int = 842
    ): Uri? = savePdfTyped(context, bitmap, pageWidthPoints, pageHeightPoints).getOrNull()

    /**
     * Saves an animated PNG (APNG) file returning typed [QrOutputResult].
     */
    suspend fun saveApngTyped(
        context: Context,
        apngFile: File
    ): QrOutputResult<Uri> = withContext(Dispatchers.IO) {
        if (!apngFile.exists() || apngFile.length() == 0L) {
            return@withContext QrOutputResult.Failure(
                QrError.Output.ApngEncodingFailed("APNG file does not exist or is empty")
            )
        }
        val name = timestampName("png")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return@withContext QrOutputResult.Failure(
                        QrError.Platform.ExportUriUnavailable("Failed to create MediaStore entry for $name")
                    )
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    context.contentResolver.delete(uri, null, null)
                    return@withContext QrOutputResult.Failure(
                        QrError.Platform.StorageFailed("Failed to open output stream for MediaStore URI $uri")
                    )
                }
                stream.use { out ->
                    apngFile.inputStream().use { input -> input.copyTo(out) }
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
                apngFile.copyTo(file, overwrite = true)
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
     * Saves an animated PNG (APNG) file to the device Pictures/VeilFrame gallery.
     */
    @Deprecated(
        message = "Use saveApngTyped(context, apngFile) for typed error handling.",
        replaceWith = ReplaceWith("saveApngTyped(context, apngFile).getOrNull()")
    )
    suspend fun saveApng(
        context: Context,
        apngFile: File
    ): Uri? = saveApngTyped(context, apngFile).getOrNull()

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
                val success = renderResult as QrRenderResult.Success
                if (!success.report.isScanReady && !success.report.validationSkipped) {
                    return QrOutputResult.Failure(
                        QrError.Validation.ScanabilityFailed(
                            success.report.warnings.firstOrNull() ?: "Scanability validation failed",
                            success.report.warnings
                        )
                    )
                }
                val bmp = success.bitmap
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
                val success = renderResult as QrRenderResult.Success
                if (!success.report.isScanReady && !success.report.validationSkipped) {
                    return QrOutputResult.Failure(
                        QrError.Validation.ScanabilityFailed(
                            success.report.warnings.firstOrNull() ?: "Scanability validation failed",
                            success.report.warnings
                        )
                    )
                }
                val bmp = success.bitmap
                    ?: return QrOutputResult.Failure(
                        QrError.Rendering.BitmapAllocationFailed(design.outputSize, design.outputSize)
                    )
                saveBitmapTyped(context, bmp, Bitmap.CompressFormat.JPEG, format.quality)
            }
            is QrOutputFormat.Svg -> {
                if (AnimatedQrGenerator.isDesignAnimated(design)) {
                    val validationErr = validateAnimatedSvgScanability(matrix, design, content)
                    if (validationErr != null) {
                        return QrOutputResult.Failure(validationErr)
                    }
                    val svgData = try {
                        AnimatedQrGenerator.generateAnimatedSvg(matrix, design)
                    } catch (t: Throwable) {
                        return QrOutputResult.Failure(
                            QrError.Rendering.SvgRenderFailed(t.message ?: "Animated SVG generation failed", t)
                        )
                    }
                    saveSvgStringTyped(context, svgData)
                } else {
                    saveSvgTyped(context, matrix, design, content)
                }
            }
            is QrOutputFormat.Pdf -> {
                val renderResult = QrGenerator.generateWithResult(content, design)
                if (renderResult is QrRenderResult.Failure) {
                    return QrOutputResult.Failure(renderResult.qrError)
                }
                val bmp = (renderResult as QrRenderResult.Success).bitmap
                    ?: return QrOutputResult.Failure(
                        QrError.Rendering.BitmapAllocationFailed(design.outputSize, design.outputSize)
                    )
                savePdfTyped(context, bmp, format.pageWidthPoints, format.pageHeightPoints)
            }
            is QrOutputFormat.Gif -> {
                val frames = AnimatedQrGenerator.extractSourceFrames(design)
                if (frames.isEmpty()) {
                    return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
                }
                val gifResult = AnimatedQrGenerator.encodeToGifStreaming(matrix, design, frames, design.outputSize, format.loopCount)
                if (gifResult is QrOutputResult.Failure) {
                    return QrOutputResult.Failure(gifResult.error)
                }
                val gifBytes = (gifResult as QrOutputResult.Success).value
                saveGifTyped(context, gifBytes)
            }
            is QrOutputFormat.Apng -> {
                val frames = AnimatedQrGenerator.extractSourceFrames(design)
                if (frames.isEmpty()) {
                    return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
                }
                val tempFile = File.createTempFile("qr_export_", ".png", context.cacheDir)
                try {
                    val apngResult = AnimatedQrGenerator.encodeToApngStreaming(
                        matrix = matrix,
                        baseDesign = design,
                        sourceFrames = frames,
                        outputFile = tempFile,
                        fps = format.fps,
                        loops = format.loopCount,
                        outputSize = design.outputSize
                    )
                    if (apngResult is QrOutputResult.Failure) {
                        return QrOutputResult.Failure(apngResult.error)
                    }
                    saveApngTyped(context, tempFile)
                } finally {
                    tempFile.delete()
                }
            }
            is QrOutputFormat.Video -> {
                val frames = AnimatedQrGenerator.extractSourceFrames(design)
                if (frames.isEmpty()) {
                    return QrOutputResult.Failure(QrError.Animation.EmptyFrames)
                }
                val ext = format.container.ext
                val tempFile = File.createTempFile("qr_export_", ".$ext", context.cacheDir)
                try {
                    val vidResult = AnimatedQrGenerator.encodeToVideoStreaming(
                        matrix = matrix,
                        baseDesign = design,
                        sourceFrames = frames,
                        outputFile = tempFile,
                        fps = format.fps,
                        outputSize = design.outputSize
                    )
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
     * Opens system share sheet for [bitmap] using temporary cache file.
     * Prevents side-effect of permanently polluting the user's Pictures gallery.
     */
    suspend fun share(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext
        val cacheShareDir = File(context.cacheDir, "share")
        if (!cacheShareDir.exists()) {
            cacheShareDir.mkdirs()
        }
        val shareFile = File(cacheShareDir, "qr_share.png")
        try {
            FileOutputStream(shareFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    shareFile
                )
            } catch (_: Exception) {
                Uri.fromFile(shareFile)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share QR Code").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Throwable) {
            // Non-fatal if share intent dispatch cannot be completed
        }
    }
}
