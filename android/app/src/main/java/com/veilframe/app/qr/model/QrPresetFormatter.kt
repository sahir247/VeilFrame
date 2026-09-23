package com.veilframe.app.qr.model

import java.net.URLEncoder

/**
 * Guided QR Presets supported in QR Studio.
 */
enum class QrPresetType(val label: String) {
    TEXT_URL("URL / Text"),
    WIFI("Wi-Fi"),
    VCARD("Contact"),
    EMAIL("Email"),
    SMS("SMS"),
    UPI("UPI Pay")
}

/**
 * Formats structured user inputs into RFC-compliant standard QR payloads.
 */
object QrPresetFormatter {

    /**
     * Formats a Wi-Fi network configuration QR string.
     * Special characters (\, ;, :, ,, ") are escaped per ZXing/Android Wi-Fi spec.
     */
    fun formatWifi(
        ssid: String,
        password: String = "",
        security: String = "WPA",
        isHidden: Boolean = false
    ): String {
        val cleanSsid = escapeWifiString(ssid)
        val cleanPass = escapeWifiString(password)
        val secType = when (security.uppercase()) {
            "WEP" -> "WEP"
            "NONE", "NOPASS", "OPEN" -> "nopass"
            else -> "WPA"
        }
        val passSegment = if (secType != "nopass" && cleanPass.isNotEmpty()) "P:$cleanPass;" else ""
        val hiddenSegment = if (isHidden) "H:true;" else ""

        return "WIFI:S:$cleanSsid;T:$secType;$passSegment$hiddenSegment;"
    }

    /**
     * Formats a vCard 3.0 contact card.
     */
    fun formatVCard(
        firstName: String,
        lastName: String = "",
        phone: String = "",
        email: String = "",
        org: String = "",
        url: String = ""
    ): String {
        val fullName = listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\n")
        sb.append("VERSION:3.0\n")
        sb.append("N:${escapeVCard(lastName)};${escapeVCard(firstName)};;;\n")
        if (fullName.isNotEmpty()) {
            sb.append("FN:${escapeVCard(fullName)}\n")
        }
        if (phone.isNotBlank()) {
            sb.append("TEL;TYPE=CELL:${phone.trim()}\n")
        }
        if (email.isNotBlank()) {
            sb.append("EMAIL:${email.trim()}\n")
        }
        if (org.isNotBlank()) {
            sb.append("ORG:${escapeVCard(org.trim())}\n")
        }
        if (url.isNotBlank()) {
            sb.append("URL:${url.trim()}\n")
        }
        sb.append("END:VCARD")
        return sb.toString()
    }

    /**
     * Formats a mailto URI with optional subject and body.
     */
    fun formatEmail(
        recipient: String,
        subject: String = "",
        body: String = ""
    ): String {
        val cleanEmail = recipient.trim()
        val params = mutableListOf<String>()
        if (subject.isNotBlank()) {
            params.add("subject=" + urlEncode(subject.trim()))
        }
        if (body.isNotBlank()) {
            params.add("body=" + urlEncode(body.trim()))
        }
        return if (params.isNotEmpty()) {
            "mailto:$cleanEmail?" + params.joinToString("&")
        } else {
            "mailto:$cleanEmail"
        }
    }

    /**
     * Formats a standard tel: phone URI.
     */
    fun formatPhone(number: String): String {
        return "tel:${number.trim()}"
    }

    /**
     * Formats a standard smsto: SMS URI.
     */
    fun formatSms(number: String, message: String = ""): String {
        val cleanNumber = number.trim()
        val cleanMsg = message.trim()
        return if (cleanMsg.isNotEmpty()) {
            "smsto:$cleanNumber:$cleanMsg"
        } else {
            "smsto:$cleanNumber"
        }
    }

    /**
     * Formats a standard NPCI UPI payment URI.
     * When only VPA is supplied: upi://pay?pa=sampleuser@fam
     * When amount is supplied: upi://pay?pa=merchant@okhdfcbank&am=100.00&cu=INR
     */
    fun formatUpi(
        vpa: String,
        amount: String = "",
        payeeName: String = "",
        note: String = ""
    ): String {
        var cleanVpa = vpa.trim()
        if (cleanVpa.startsWith("upi://pay?pa=", ignoreCase = true)) {
            cleanVpa = cleanVpa.substring("upi://pay?pa=".length).trim()
        } else if (cleanVpa.startsWith("pa=", ignoreCase = true)) {
            cleanVpa = cleanVpa.substring("pa=".length).trim()
        }
        // Preserve literal @ in UPI ID for standard UPI app compliance
        val encodedVpa = urlEncode(cleanVpa).replace("%40", "@")
        val params = mutableListOf("pa=$encodedVpa")

        val cleanAmount = amount.trim()
        if (cleanAmount.isNotBlank() && cleanAmount != "0" && cleanAmount != "0.0" && cleanAmount != "0.00") {
            val formattedAmount = try {
                val bd = java.math.BigDecimal(cleanAmount)
                val maxLimit = java.math.BigDecimal("100000.00")
                val cappedBd = if (bd > maxLimit) maxLimit else bd
                if (cappedBd.scale() < 2) cappedBd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() else cappedBd.toPlainString()
            } catch (_: Exception) {
                cleanAmount
            }
            params.add("am=$formattedAmount")
            params.add("cu=INR")
        }
        if (payeeName.isNotBlank()) {
            params.add("pn=" + urlEncode(payeeName.trim()))
        }
        if (note.isNotBlank()) {
            params.add("tn=" + urlEncode(note.trim()))
        }
        return "upi://pay?" + params.joinToString("&")
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value
        }
    }

    private fun escapeWifiString(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(":", "\\:")
            .replace(",", "\\,")
            .replace("\"", "\\\"")
    }

    private fun escapeVCard(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }
}
