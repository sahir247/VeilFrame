package com.veilframe.app.cv.core

import android.content.Context
import com.google.ai.edge.litert.Environment

/**
 * One process-wide LiteRT environment. Use the Application context so the runtime can
 * persist compiler cache entries between app launches without retaining an Activity.
 */
internal object LiteRtEnvironment {
    @Volatile
    private var cached: Environment? = null

    fun get(context: Context?): Environment {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: (if (context != null) {
                Environment.create(context.applicationContext, enableCompilerCache = true)
            } else {
                Environment.create()
            }).also { cached = it }
        }
    }
}
