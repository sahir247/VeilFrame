package com.veilframe.app.qr.scanner

/**
 * Sealed class hierarchy for all recognized QR payload types.
 * Maps to the EFQRCode payload taxonomy and the Executive Summary action model.
 */
sealed class QrAction {
    data class Url(val uri: String, val riskScore: Int = 0) : QrAction()
    data class Wifi(val ssid: String, val password: String, val type: String, val hidden: Boolean) : QrAction()
    data class Contact(val name: String?, val phone: String?, val email: String?, val org: String?) : QrAction()
    data class UpiPayment(val pa: String, val pn: String?, val amount: String?, val currency: String?, val note: String?) : QrAction()
    data class Phone(val number: String) : QrAction()
    data class Sms(val number: String, val message: String?) : QrAction()
    data class Email(val address: String, val subject: String?, val body: String?) : QrAction()
    data class Geo(val lat: Double, val lon: Double, val label: String?) : QrAction()
    data class CalendarEvent(val title: String?, val dtStart: String?, val dtEnd: String?, val location: String?, val description: String?) : QrAction()
    data class OtpAuth(val issuer: String?, val account: String?, val secret: String?, val type: String = "totp") : QrAction()
    data class Raw(val text: String) : QrAction()
}
