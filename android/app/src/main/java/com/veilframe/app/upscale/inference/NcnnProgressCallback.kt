package com.veilframe.app.upscale.inference

/**
 * Progress callback for native NCNN tiled inference updates.
 *
 * @return 0 to continue inference, non-zero (e.g. 1) to request immediate native cancellation.
 */
fun interface NcnnProgressCallback {
    fun onProgress(currentTile: Int, totalTiles: Int, percent: Int): Int
}
