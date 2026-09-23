package com.veilframe.app.qr

import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.UrlDisposition
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PayloadParserTest {

    @Test
    fun testParseWifiWithEscapedCharacters() {
        val raw = "WIFI:T:WPA;S:VeilFrame\\;Secure\\:Network;P:Super\\;Secret\\\\999;H:false;;"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Wifi", action is QrAction.Wifi)
        val wifi = action as QrAction.Wifi
        assertEquals("VeilFrame;Secure:Network", wifi.ssid)
        assertEquals("Super;Secret\\999", wifi.password)
        assertEquals("WPA", wifi.type)
        assertFalse(wifi.hidden)
    }

    @Test
    fun testParseUpiLosslessParameters() {
        val raw = "upi://pay?pa=merchant@upi&pn=PrivacyStore&am=250.00&cu=INR&tn=Invoice&mc=5411&tr=TX12345"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.UpiPayment", action is QrAction.UpiPayment)
        val upi = action as QrAction.UpiPayment
        assertEquals("merchant@upi", upi.payeeAddress)
        assertEquals("PrivacyStore", upi.payeeName)
        assertEquals(BigDecimal("250.00"), upi.amount)
        assertEquals("INR", upi.currency)
        assertEquals("Invoice", upi.note)
        assertEquals("5411", upi.rawParameters["mc"])
        assertEquals("TX12345", upi.rawParameters["tr"])
    }

    @Test
    fun testParseOtpAuthComplete() {
        val raw = "otpauth://totp/VeilFrame:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=VeilFrame&algorithm=SHA256&digits=8&period=60"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.OtpAuth", action is QrAction.OtpAuth)
        val otp = action as QrAction.OtpAuth
        assertEquals("VeilFrame", otp.issuer)
        assertEquals("alice@example.com", otp.account)
        assertEquals("JBSWY3DPEHPK3PXP", otp.secret)
        assertEquals("SHA256", otp.algorithm)
        assertEquals(8, otp.digits)
        assertEquals(60, otp.period)
    }

    @Test
    fun testParseUrlDispositions() {
        // 1. Standard HTTPS
        val httpsAction = PayloadParser.parse("https://veilframe.app/about") as QrAction.Url
        assertEquals(UrlDisposition.HTTPS, httpsAction.disposition)

        // 2. Insecure HTTP
        val httpAction = PayloadParser.parse("http://example.com/insecure") as QrAction.Url
        assertEquals(UrlDisposition.HTTP, httpAction.disposition)

        // 3. Punycode (top-level and subdomains)
        val punyAction = PayloadParser.parse("https://xn--e1afmkfd.xn--p1ai") as QrAction.Url
        assertEquals(UrlDisposition.PUNYCODE, punyAction.disposition)

        val subPunyAction = PayloadParser.parse("https://login.subdomain.xn--fiqs8s.cn/auth") as QrAction.Url
        assertEquals(UrlDisposition.PUNYCODE, subPunyAction.disposition)

        // 4. IP Host
        val ipAction = PayloadParser.parse("http://192.168.1.1/admin") as QrAction.Url
        assertEquals(UrlDisposition.IP_HOST, ipAction.disposition)
    }

    @Test
    fun testParseUpiEncodedCharactersWithoutDoubleDecoding() {
        val raw = "upi://pay?pa=merchant@upi&pn=Privacy%20Store%20%26%20Co&am=100.00&cu=INR&tn=Invoice%20%23123"
        val action = PayloadParser.parse(raw) as QrAction.UpiPayment
        assertEquals("Privacy Store & Co", action.payeeName)
        assertEquals("Invoice #123", action.note)
    }

    @Test
    fun testParseMultiFieldContactVCard() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Alice Smith
            ORG:VeilFrame Inc.
            TITLE:Security Architect
            TEL;TYPE=CELL:+15551234567
            TEL;TYPE=WORK:+15559876543
            EMAIL;TYPE=PREF:alice@veilframe.app
            EMAIL;TYPE=HOME:alice.personal@example.com
            END:VCARD
        """.trimIndent()

        val action = PayloadParser.parse(vcard)
        assertTrue("Expected QrAction.Contact", action is QrAction.Contact)
        val contact = action as QrAction.Contact
        assertEquals("Alice Smith", contact.name)
        assertEquals("VeilFrame Inc.", contact.org)
        assertEquals("Security Architect", contact.title)
        assertEquals(2, contact.phones.size)
        assertEquals("+15551234567", contact.phones[0])
        assertEquals("+15559876543", contact.phones[1])
        assertEquals(2, contact.emails.size)
        assertEquals("alice@veilframe.app", contact.emails[0])
        assertEquals("alice.personal@example.com", contact.emails[1])
    }

    @Test
    fun testParseRawFallback() {
        val raw = "Plain unstructured QR text"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Raw", action is QrAction.Raw)
        assertEquals("Plain unstructured QR text", (action as QrAction.Raw).text)
    }

    @Test
    fun testParseIdnHomographUrl() {
        // Cyrillic lookalike domain "аррӏе.com"
        val raw = "https://арр\u0456е.com/login"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Url", action is QrAction.Url)
        val url = action as QrAction.Url
        assertEquals(UrlDisposition.PUNYCODE, url.disposition)
    }

    @Test
    fun testVCardFoldedLinesAndTelexExclusion() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            N:Smith;Bob;J;Dr;
            ORG:VeilFrame
             Global Security
            TITLE:Lead
             Engineer
            TELEX:999-NOT-A-PHONE
            TEL;TYPE=CELL:+15550001111
            END:VCARD
        """.trimIndent()

        val action = PayloadParser.parse(vcard)
        assertTrue("Expected QrAction.Contact", action is QrAction.Contact)
        val contact = action as QrAction.Contact
        assertEquals("Bob Smith", contact.name)
        assertEquals("VeilFrame Global Security", contact.org)
        assertEquals("Lead Engineer", contact.title)
        assertEquals(1, contact.phones.size)
        assertEquals("+15550001111", contact.phones[0])
    }

    @Test
    fun testMecardEscapedCharacters() {
        val raw = "MECARD:N:Smith\\; Jr\\, John;TEL:+15551234567;ORG:VeilFrame\\; Ltd;;;"
        val action = PayloadParser.parse(raw)
        assertTrue("Expected QrAction.Contact", action is QrAction.Contact)
        val contact = action as QrAction.Contact
        assertEquals("Smith; Jr, John", contact.name)
        assertEquals(listOf("+15551234567"), contact.phones)
        assertEquals("VeilFrame; Ltd", contact.org)
    }

    @Test
    fun testCalendarEventEscapedCharactersAndFolding() {
        val ical = """
            BEGIN:VEVENT
            SUMMARY:Security\, Privacy \& Architecture
             Review
            DTSTART:20261001T090000Z
            DTEND:20261001T100000Z
            LOCATION:Room 404\, Building A
            DESCRIPTION:Project sync\nDiscussing VeilFrame updates
            END:VEVENT
        """.trimIndent()

        val action = PayloadParser.parse(ical)
        assertTrue("Expected QrAction.CalendarEvent", action is QrAction.CalendarEvent)
        val event = action as QrAction.CalendarEvent
        assertEquals("Security, Privacy & Architecture Review", event.title)
        assertEquals("20261001T090000Z", event.dtStart)
        assertEquals("Room 404, Building A", event.location)
        assertTrue(event.description?.contains("\nDiscussing VeilFrame updates") == true)
    }
}
