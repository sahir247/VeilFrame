package com.veilframe.app.qr

/**
 * Backward compatibility typealias mapping [com.veilframe.app.qr.QrMatrix]
 * to the authoritative domain model [com.veilframe.app.qr.model.QrMatrix].
 */
@Deprecated(
    message = "Use com.veilframe.app.qr.model.QrMatrix directly",
    replaceWith = ReplaceWith("com.veilframe.app.qr.model.QrMatrix")
)
typealias QrMatrix = com.veilframe.app.qr.model.QrMatrix

@Deprecated(
    message = "Use com.veilframe.app.qr.model.QrMatrix.ModuleType directly",
    replaceWith = ReplaceWith("com.veilframe.app.qr.model.QrMatrix.ModuleType")
)
typealias ModuleType = com.veilframe.app.qr.model.QrMatrix.ModuleType
