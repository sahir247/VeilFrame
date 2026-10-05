package com.veilframe.app.qr.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.veilframe.app.qr.model.ImageSource

/**
 * Authoritative loader and resolver for [ImageSource] variants.
 * Converts [ImageSource.Uri], [ImageSource.Resource], and [ImageSource.Memory] into usable [Bitmap] instances.
 */
object ImageSourceLoader {

    /**
     * Resolves and loads a [Bitmap] from an [ImageSource].
     *
     * @param context Application context for accessing resources and content resolvers.
     * @param source The source to resolve.
     * @return Decoded Bitmap or null if loading failed.
     */
    fun loadBitmap(context: Context, source: ImageSource): Bitmap? {
        return try {
            when (source) {
                is ImageSource.Memory -> source.bitmap
                is ImageSource.Animated -> source.frames.firstOrNull()
                is ImageSource.Resource -> {
                    BitmapFactory.decodeResource(context.resources, source.id)
                }
                is ImageSource.Uri -> {
                    val uri = Uri.parse(source.value)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
