package com.veilframe.app.qr

import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import org.junit.Assert.*
import org.junit.Test

class PayloadParserTest {

    @Test
    fun testParseWifi() {
        val raw = "WIFI:T:WPA;S:VeilFrameSecure;P:SuperSecret999;H:false;;"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Wifi", action is QrAction.Wifi)
        val wifi = action as QrAction.Wifi
        assertEquals("VeilFrameSecure", wifi.ssid)
        assertEquals("SuperSecret999", wifi.password)
        assertEquals("WPA", wifi.type)
        assertFalse(wifi.hidden)
    }

    @Test
    fun testParseUpi() {
        val raw = "upi://pay?pa=merchant@upi&pn=PrivacyStore&am=250.00&cu=INR&tn=Invoice"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.UpiPayment", action is QrAction.UpiPayment)
        val upi = action as QrAction.UpiPayment
        assertEquals("merchant@upi", upi.pa)
        assertEquals("PrivacyStore", upi.pn)
        assertEquals("250.00", upi.amount)
        assertEquals("INR", upi.currency)
        assertEquals("Invoice", upi.note)
    }

    @Test
    fun testParsePhone() {
        val raw = "tel:+15551234567"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Phone", action is QrAction.Phone)
        val phone = action as QrAction.Phone
        assertEquals("+15551234567", phone.number)
    }

    @Test
    fun testParseSms() {
        val raw = "smsto:+15559876543:Welcome to VeilFrame"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Sms", action is QrAction.Sms)
        val sms = action as QrAction.Sms
        assertEquals("+15559876543", sms.number)
        assertEquals("Welcome to VeilFrame", sms.message)
    }

    @Test
    fun testParseEmail() {
        val raw = "mailto:privacy@veilframe.app?subject=Inquiry&body=Hello"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Email", action is QrAction.Email)
        val email = action as QrAction.Email
        assertEquals("privacy@veilframe.app", email.address)
        assertEquals("Inquiry", email.subject)
        assertEquals("Hello", email.body)
    }

    @Test
    fun testParseGeo() {
        val raw = "geo:37.7749,-122.4194"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Geo", action is QrAction.Geo)
        val geo = action as QrAction.Geo
        assertEquals(37.7749, geo.lat, 0.0001)
        assertEquals(-122.4194, geo.lon, 0.0001)
    }

    @Test
    fun testParseUrl() {
        val raw = "https://github.com/sahir247/VeilFrame"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Url", action is QrAction.Url)
        val url = action as QrAction.Url
        assertEquals("https://github.com/sahir247/VeilFrame", url.uri)
    }

    @Test
    fun testParseRawFallback() {
        val raw = "Just a raw string with arbitrary text"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Raw", action is QrAction.Raw)
        val rawAction = action as QrAction.Raw
        assertEquals("Just a raw string with arbitrary text", rawAction.text)
    }
}
