package com.veilframe.app.upscale.inference

/**
 * Progress callback for native NCNN tiled inference updates.
 */
fun interface NcnnProgressCallback {
    fun onProgress(currentTile: Int, totalTiles: Int, percent: Int)
}
