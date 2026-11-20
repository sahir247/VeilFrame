package com.veilframe.app.tools

/** The UI reports opened pages, never claims the user has read them. */
internal class BundleReviewGate {
    var pageReady = false
        private set
    var reachedEnd = false
        private set

    fun loading() { pageReady = false }
    fun loaded(atEnd: Boolean) {
        pageReady = true
        reachedEnd = reachedEnd || atEnd
    }
    fun canTransfer(explicitUnreadConsent: Boolean): Boolean =
        pageReady && (reachedEnd || explicitUnreadConsent)
}
