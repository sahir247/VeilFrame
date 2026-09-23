package com.veilframe.app.qr

import com.veilframe.app.qr.model.QrPresetFormatter
import com.veilframe.app.qr.scanner.PayloadParser
import com.veilframe.app.qr.scanner.QrAction
import org.junit.Assert.*
import org.junit.Test

class QrPresetFormatterTest {

    @Test
    fun testWifiPresetFormattingAndRoundtrip() {
        val payload = QrPresetFormatter.formatWifi(
            ssid = "My;Home:Net\\Work",
            password = "P@ss:word;123",
            security = "WPA",
            isHidden = true
        )

        assertTrue(payload.startsWith("WIFI:"))
        assertTrue(payload.contains("S:My\\;Home\\:Net\\\\Work;"))
        assertTrue(payload.contains("T:WPA;"))
        assertTrue(payload.contains("P:P@ss\\:word\\;123;"))
        assertTrue(payload.contains("H:true;"))

        // Roundtrip verify through PayloadParser
        val parsed = PayloadParser.parse(payload)
        assertTrue(parsed is QrAction.Wifi)
        val wifi = parsed as QrAction.Wifi
        assertEquals("My;Home:Net\\Work", wifi.ssid)
        assertEquals("P@ss:word;123", wifi.password)
        assertEquals("WPA", wifi.type)
        assertTrue(wifi.hidden)
    }

    @Test
    fun testWifiOpenNetwork() {
        val payload = QrPresetFormatter.formatWifi(
            ssid = "PublicGuest",
            password = "",
            security = "nopass",
            isHidden = false
        )

        assertEquals("WIFI:S:PublicGuest;T:nopass;;", payload)
        val parsed = PayloadParser.parse(payload)
        assertTrue(parsed is QrAction.Wifi)
        val wifi = parsed as QrAction.Wifi
        assertEquals("PublicGuest", wifi.ssid)
        assertEquals("", wifi.password)
        assertEquals("nopass", wifi.type)
        assertFalse(wifi.hidden)
    }

    @Test
    fun testVCardPresetFormattingAndRoundtrip() {
        val payload = QrPresetFormatter.formatVCard(
            firstName = "John",
            lastName = "Doe",
            phone = "+1234567890",
            email = "john.doe@example.com",
            org = "Acme Corp"
        )

        assertTrue(payload.startsWith("BEGIN:VCARD"))
        assertTrue(payload.contains("FN:John Doe"))
        assertTrue(payload.contains("TEL;TYPE=CELL:+1234567890"))
        assertTrue(payload.contains("EMAIL:john.doe@example.com"))
        assertTrue(payload.contains("ORG:Acme Corp"))
        assertTrue(payload.endsWith("END:VCARD"))

        val parsed = PayloadParser.parse(payload)
        assertTrue(parsed is QrAction.Contact)
        val contact = parsed as QrAction.Contact
        assertEquals("John Doe", contact.name)
        assertTrue(contact.phones.contains("+1234567890"))
        assertTrue(contact.emails.contains("john.doe@example.com"))
        assertEquals("Acme Corp", contact.org)
    }

    @Test
    fun testEmailPresetFormattingAndRoundtrip() {
        val payload = QrPresetFormatter.formatEmail(
            recipient = "support@veilframe.app",
            subject = "Feedback",
            body = "Hello World"
        )

        assertTrue(payload.startsWith("mailto:support@veilframe.app?"))
        val parsed = PayloadParser.parse(payload)
        assertTrue(parsed is QrAction.Email)
        val email = parsed as QrAction.Email
        assertEquals("support@veilframe.app", email.address)
        assertEquals("Feedback", email.subject)
        assertEquals("Hello World", email.body)
    }

    @Test
    fun testPhoneAndSmsPresetFormattingAndRoundtrip() {
        val phonePayload = QrPresetFormatter.formatPhone("+1987654321")
        assertEquals("tel:+1987654321", phonePayload)
        val parsedPhone = PayloadParser.parse(phonePayload)
        assertTrue(parsedPhone is QrAction.Phone)
        assertEquals("+1987654321", (parsedPhone as QrAction.Phone).number)

        val smsPayload = QrPresetFormatter.formatSms("+1987654321", "Hello there")
        assertEquals("smsto:+1987654321:Hello there", smsPayload)
        val parsedSms = PayloadParser.parse(smsPayload)
        assertTrue(parsedSms is QrAction.Sms)
        assertEquals("+1987654321", (parsedSms as QrAction.Sms).number)
        assertEquals("Hello there", (parsedSms as QrAction.Sms).message)
    }

    @Test
    fun testUpiPresetFormattingAndRoundtrip() {
        val upiPayload = QrPresetFormatter.formatUpi(
            vpa = "merchant@upi",
            payeeName = "VeilFrame Store",
            amount = "250.00",
            note = "Pro License"
        )

        assertTrue(upiPayload.startsWith("upi://pay?"))
        val parsed = PayloadParser.parse(upiPayload)
        assertTrue(parsed is QrAction.UpiPayment)
        val upi = parsed as QrAction.UpiPayment
        assertEquals("merchant@upi", upi.payeeAddress)
        assertEquals("VeilFrame Store", upi.payeeName)
        assertEquals("250.00", upi.amount?.toPlainString())
    }

    @Test
    fun testUpiExactUserRequirements() {
        // 1. Without amount: clean UPI payload with no additional parameters
        val cleanUpi = QrPresetFormatter.formatUpi("sampleuser@fam")
        assertEquals("upi://pay?pa=sampleuser@fam", cleanUpi)

        // Parsing clean UPI
        val parsedClean = PayloadParser.parse(cleanUpi)
        assertTrue(parsedClean is QrAction.UpiPayment)
        assertEquals("sampleuser@fam", (parsedClean as QrAction.UpiPayment).payeeAddress)
        assertNull(parsedClean.amount)

        // 2. With automatically stripped prefix if user enters or pastes upi://pay?pa=
        val prefixedUpi = QrPresetFormatter.formatUpi("upi://pay?pa=sampleuser@fam")
        assertEquals("upi://pay?pa=sampleuser@fam", prefixedUpi)

        // 3. With selected amount: adds &am=100.00&cu=INR
        val amountUpi1 = QrPresetFormatter.formatUpi("merchant@okhdfcbank", amount = "100.00")
        assertEquals("upi://pay?pa=merchant@okhdfcbank&am=100.00&cu=INR", amountUpi1)

        val amountUpi2 = QrPresetFormatter.formatUpi("merchant@okhdfcbank", amount = "100")
        assertEquals("upi://pay?pa=merchant@okhdfcbank&am=100.00&cu=INR", amountUpi2)

        // 4. Up to 1 Lakh INR (100000.00)
        val lakhUpi = QrPresetFormatter.formatUpi("merchant@okhdfcbank", amount = "100000")
        assertEquals("upi://pay?pa=merchant@okhdfcbank&am=100000.00&cu=INR", lakhUpi)

        val exceedingUpi = QrPresetFormatter.formatUpi("merchant@okhdfcbank", amount = "150000")
        assertEquals("upi://pay?pa=merchant@okhdfcbank&am=100000.00&cu=INR", exceedingUpi)

        // 5. Amount "0" should NOT add &am= or &cu=
        val zeroAmountUpi = QrPresetFormatter.formatUpi("sampleuser@fam", amount = "0")
        assertEquals("upi://pay?pa=sampleuser@fam", zeroAmountUpi)
    }
}
