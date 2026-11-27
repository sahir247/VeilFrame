package com.veilframe.app.media

/**
 * Batch publishing must not call the success UI when any source image failed
 * to decode, transform, encode, or copy to the selected document tree.
 *
 * Encoded and destination-published counts are intentionally separate:
 * local cache output is not evidence that a SAF export succeeded.
 */
object ImageStudioBatchExportAcceptance {
    /** Roll back only identities this operation created, in reverse order. */
    internal fun <T> rollbackCreated(created: MutableList<T>, delete: (T) -> Boolean): Int {
        var failures = 0
        for (entry in created.asReversed()) {
            if (!delete(entry)) failures++
        }
        created.clear()
        return failures
    }

    internal fun requireOriginalDimensions(expectedWidth: Int, expectedHeight: Int, width: Int, height: Int) {
        require(expectedWidth > 0 && expectedHeight > 0) { "Unknown source dimensions" }
        check(width == expectedWidth && height == expectedHeight) {
            "Full-resolution decode unavailable ($width × $height instead of $expectedWidth × $expectedHeight); no preview substitute was exported"
        }
    }

    fun requireComplete(
        requested: Int,
        encoded: Int,
        copiedToDestination: Int,
        destinationSelected: Boolean
    ) {
        require(requested > 0) { "No batch items supplied" }
        require(encoded in 0..requested) { "Invalid batch encode count" }
        require(copiedToDestination in 0..encoded) { "Invalid destination publication count" }
        check(encoded == requested) {
            "Only $encoded of $requested images were encoded; refusing batch success"
        }
        check(!destinationSelected || copiedToDestination == requested) {
            "Only $copiedToDestination of $requested images reached the destination"
        }
    }
}
