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
     * Parses a raw UPI payment URI (e.g., upi://pay?pa=user@upi&pn=Name&am=100) into key-value pairs.
     */
    fun parseUpiUri(rawUri: String): Map<String, String> {
        val trimmed = rawUri.trim()
        val queryPart = when {
            trimmed.startsWith("upi://pay?", ignoreCase = true) -> trimmed.substring("upi://pay?".length)
            trimmed.contains("?") -> trimmed.substringAfter("?")
            else -> return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        for (param in queryPart.split("&")) {
            val idx = param.indexOf('=')
            if (idx != -1) {
                val key = param.substring(0, idx).trim().lowercase()
                val rawVal = param.substring(idx + 1).trim()
                val decoded = try {
                    java.net.URLDecoder.decode(rawVal, "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
                result[key] = decoded
            }
        }
        return result
    }

    /**
     * Formats a standard NPCI UPI payment URI.
     * When only VPA is supplied: upi://pay?pa=name@upi
     * When amount is supplied: upi://pay?pa=name@upi&am=100.00&cu=INR
     * Accepts either clean UPI ID (e.g., name@upi) or a pasted full URI (upi://pay?pa=name@upi...).
     */
    fun formatUpi(
        vpa: String,
        amount: String = "",
        payeeName: String = "",
        note: String = ""
    ): String {
        val trimmed = vpa.trim()
        if (trimmed.isBlank()) return ""

        var effectivePa = trimmed
        var effectiveAm = amount.trim()
        var effectivePn = payeeName.trim()
        var effectiveTn = note.trim()

        // If user pasted a full upi:// URI, extract its components intelligently
        if (trimmed.startsWith("upi://pay?", ignoreCase = true)) {
            val parsed = parseUpiUri(trimmed)
            if (parsed.containsKey("pa")) {
                effectivePa = parsed["pa"].orEmpty()
            }
            if (effectiveAm.isBlank() && parsed.containsKey("am")) {
                effectiveAm = parsed["am"].orEmpty()
            }
            if (effectivePn.isBlank() && parsed.containsKey("pn")) {
                effectivePn = parsed["pn"].orEmpty()
            }
            if (effectiveTn.isBlank() && parsed.containsKey("tn")) {
                effectiveTn = parsed["tn"].orEmpty()
            }
        } else {
            if (effectivePa.startsWith("upi://pay?pa=", ignoreCase = true)) {
                effectivePa = effectivePa.substring("upi://pay?pa=".length).trim()
            } else if (effectivePa.startsWith("pa=", ignoreCase = true)) {
                effectivePa = effectivePa.substring("pa=".length).trim()
            }
        }

        if (effectivePa.isBlank()) return ""

        // Preserve literal @ in UPI ID for standard UPI app compliance
        val encodedVpa = urlEncode(effectivePa).replace("%40", "@")
        val params = mutableListOf("pa=$encodedVpa")

        if (effectiveAm.isNotBlank() && effectiveAm != "0" && effectiveAm != "0.0" && effectiveAm != "0.00") {
            val formattedAmount = try {
                val bd = java.math.BigDecimal(effectiveAm)
                val maxLimit = java.math.BigDecimal("100000.00")
                val cappedBd = if (bd > maxLimit) maxLimit else bd
                if (cappedBd.scale() < 2) cappedBd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() else cappedBd.toPlainString()
            } catch (_: Exception) {
                effectiveAm
            }
            params.add("am=$formattedAmount")
            params.add("cu=INR")
        }
        if (effectivePn.isNotBlank()) {
            params.add("pn=" + urlEncode(effectivePn))
        }
        if (effectiveTn.isNotBlank()) {
            params.add("tn=" + urlEncode(effectiveTn))
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
