package com.veilframe.app.cv.motion

import org.opencv.core.Core
import org.opencv.core.Mat

/** Short-lived operation scope. Borrowed inputs are never registered here. */
internal class MotionMatScope {
    private val mats = ArrayList<Mat>()
    fun <T : Mat> own(mat: T): T = mat.also { mats.add(it) }
    fun <T : Mat> transfer(mat: T): T {
        val index = mats.indexOfFirst { it === mat }
        check(index >= 0) { "Cannot transfer an unowned Mat" }
        mats.removeAt(index)
        return mat
    }
    fun split(mat: Mat): List<Mat> {
        val channels = ArrayList<Mat>()
        try { Core.split(mat,channels) }
        finally { channels.forEach { own(it) } }
        return channels
    }
    fun close() { mats.asReversed().forEach { it.release() }; mats.clear() }
}

internal inline fun <T> withMotionMats(block: MotionMatScope.() -> T): T {
    val scope = MotionMatScope()
    return try { scope.block() } finally { scope.close() }
}
