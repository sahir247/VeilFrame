package com.veilframe.app.qr.scanner

import java.math.BigDecimal

/**
 * Factual URL classification flags.
 */
enum class UrlDisposition {
    HTTPS,
    HTTP,
    PUNYCODE,
    IP_HOST,
    INVALID,
    UNSUPPORTED_SCHEME
}

/**
 * Sealed class hierarchy for all recognized QR payload types.
 */
sealed class QrAction {
    data class Url(
        val uri: String,
        val disposition: UrlDisposition = UrlDisposition.HTTPS,
        val host: String? = null
    ) : QrAction()

    data class Wifi(
        val ssid: String,
        val password: String,
        val type: String,
        val hidden: Boolean
    ) : QrAction()

    data class Contact(
        val name: String?,
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val org: String? = null,
        val title: String? = null
    ) : QrAction()

    data class UpiPayment(
        val payeeAddress: String,
        val payeeName: String?,
        val amount: BigDecimal?,
        val currency: String?,
        val note: String?,
        val rawParameters: Map<String, String> = emptyMap()
    ) : QrAction()

    data class Phone(val number: String) : QrAction()
    data class Sms(val number: String, val message: String?) : QrAction()
    data class Email(val address: String, val subject: String?, val body: String?) : QrAction()
    data class Geo(val lat: Double, val lon: Double, val label: String?) : QrAction()
    data class CalendarEvent(
        val title: String?,
        val dtStart: String?,
        val dtEnd: String?,
        val location: String?,
        val description: String?
    ) : QrAction()

    data class OtpAuth(
        val issuer: String?,
        val account: String?,
        val secret: String?,
        val type: String = "totp",
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val period: Int = 30
    ) : QrAction()

    data class Raw(val text: String) : QrAction()
}
