package com.veilframe.app.qr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.veilframe.app.qr.model.ImageSource
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.QrDesign

/**
 * Authoritative loader and resolver for [ImageSource] variants.
 * Converts [ImageSource.Uri], [ImageSource.Resource], and [ImageSource.Memory] into usable [Bitmap] instances.
 */
object ImageSourceLoader {

    /**
     * Calculates optimal sub-sampling factor to decode within required bounds.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Resolves and loads a [Bitmap] from an [ImageSource] with optional downsampling.
     *
     * @param context Application context for accessing resources and content resolvers.
     * @param source The source to resolve.
     * @param targetWidth Max desired width for downsampling (default 1024).
     * @param targetHeight Max desired height for downsampling (default 1024).
     * @return Decoded Bitmap or null if loading failed.
     */
    fun loadBitmap(
        context: Context,
        source: ImageSource,
        targetWidth: Int = 1024,
        targetHeight: Int = 1024
    ): Bitmap? {
        return try {
            when (source) {
                is ImageSource.Memory -> source.bitmap
                is ImageSource.Animated -> source.frames.firstOrNull()
                is ImageSource.Resource -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(context.resources, source.id, opts)
                    val sampleSize = calculateInSampleSize(opts, targetWidth, targetHeight)
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeResource(context.resources, source.id, decodeOpts)
                }
                is ImageSource.Uri -> {
                    val uri = Uri.parse(source.value)
                    val sampleSize = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, opts)
                        calculateInSampleSize(opts, targetWidth, targetHeight)
                    } ?: 1

                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(stream, null, decodeOpts)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Safely materializes all unmaterialized image sources (Uri, Resource) across [design]
     * into active [ImageSource.Memory] bitmaps.
     *
     * If materialization succeeds, returns a new [QrDesign] with materialized bitmaps.
     * If resolution fails (e.g. invalid URI or missing file), leaves the original source intact
     * so that fail-closed validation can report a typed [com.veilframe.app.qr.error.QrError.Image.UnmaterializedSource].
     */
    fun materializeDesign(context: Context, design: QrDesign): QrDesign {
        var modified = design

        val imgSource = design.imageSource.source
        if (design.imageSource.bitmap == null && imgSource != null) {
            val targetSize = design.outputSize.coerceIn(256, 4096)
            val loaded = loadBitmap(context, imgSource, targetSize, targetSize)
            if (loaded != null) {
                modified = modified.copy(
                    imageSource = modified.imageSource.copy(
                        source = ImageSource.Memory(loaded)
                    )
                )
            }
        }

        val logo = design.logo
        val logoSource = logo?.source
        if (logo?.effectiveBitmap == null && logoSource != null) {
            val targetSize = (design.outputSize * logo.scaleFraction).toInt().coerceIn(64, 1024)
            val loaded = loadBitmap(context, logoSource, targetSize, targetSize)
            if (loaded != null) {
                modified = modified.copy(
                    logo = logo.copy(
                        source = ImageSource.Memory(loaded),
                        bitmap = loaded
                    )
                )
            }
        }

        return modified
    }
}
