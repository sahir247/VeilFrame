package com.veilframe.app.qr.exporter

import org.junit.Test
import java.io.IOException

class QrPublicationContractTest {
    @Test
    fun exactlyOneRowIsPublished() {
        QrExporter.requirePublishedRow(1)
    }

    @Test(expected = IOException::class)
    fun zeroRowsCannotReportSuccess() {
        QrExporter.requirePublishedRow(0)
    }

    @Test(expected = IOException::class)
    fun multipleRowsCannotReportSuccess() {
        QrExporter.requirePublishedRow(2)
    }
}
