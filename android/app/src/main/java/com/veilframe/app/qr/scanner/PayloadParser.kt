package com.veilframe.app.qr.scanner

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder

/**
 * Robust parser for QR payload contents into structured [QrAction] instances.
 */
object PayloadParser {

    fun parse(raw: String): QrAction {
        val t = raw.trim()
        return parseWifi(t)
            ?: parseUpi(t)
            ?: parseOtpAuth(t)
            ?: parseSms(t)
            ?: parseEmail(t)
            ?: parseGeo(t)
            ?: parseContact(t)
            ?: parseCalendar(t)
            ?: parsePhone(t)
            ?: parseUrl(t)
            ?: QrAction.Raw(t)
    }

    /**
     * Wi-Fi parser supporting escaped characters: \;, \:, \\, \,
     * Format: WIFI:T:WPA;S:My\;SSID;P:Pass\;123;H:false;;
     */
    private fun parseWifi(t: String): QrAction.Wifi? {
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null

        val content = t.substring(5)
        val fields = mutableMapOf<String, String>()

        var i = 0
        while (i < content.length) {
            val colonIdx = content.indexOf(':', i)
            if (colonIdx == -1) break
            val key = content.substring(i, colonIdx).uppercase()

            val sb = StringBuilder()
            var j = colonIdx + 1
            var escaped = false
            while (j < content.length) {
                val c = content[j]
                if (escaped) {
                    sb.append(c)
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == ';') {
                    break
                } else {
                    sb.append(c)
                }
                j++
            }
            fields[key] = sb.toString()
            i = j + 1
        }

        val ssid = fields["S"] ?: return null
        val password = fields["P"] ?: ""
        val type = fields["T"]?.ifBlank { "WPA" } ?: "WPA"
        val hidden = fields["H"].equals("true", ignoreCase = true)

        return QrAction.Wifi(
            ssid = ssid,
            password = password,
            type = type,
            hidden = hidden
        )
    }

    /**
     * UPI Payment parser preserving the complete lossless raw query map.
     * Example: upi://pay?pa=merchant@upi&pn=Merchant&am=150.00&cu=INR&tn=Order123&mc=5411
     */
    private fun parseUpi(t: String): QrAction.UpiPayment? {
        if (!t.startsWith("upi://", ignoreCase = true)) return null
        val uri = try { URI(t) } catch (_: Exception) { return null }
        val queryString = uri.rawQuery ?: t.substringAfter('?', "")
        if (queryString.isBlank()) return null

        val rawParams = queryString.split("&").filter { it.isNotBlank() }.associate {
            val parts = it.split("=", limit = 2)
            val key = try { URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
            val value = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            } else ""
            key to value
        }

        val pa = rawParams["pa"] ?: return null
        val pn = rawParams["pn"]
        val amStr = rawParams["am"]
        val cu = rawParams["cu"] ?: "INR"
        val tn = rawParams["tn"]

        val amount = amStr?.let {
            try { BigDecimal(it) } catch (_: Exception) { null }
        }

        return QrAction.UpiPayment(
            payeeAddress = pa,
            payeeName = pn,
            amount = amount,
            currency = cu,
            note = tn,
            rawParameters = rawParams
        )
    }

    /**
     * OTPAuth parser: otpauth://totp/Example:alice@google.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&algorithm=SHA1&digits=6&period=30
     */
    private fun parseOtpAuth(t: String): QrAction.OtpAuth? {
        if (!t.startsWith("otpauth://", ignoreCase = true)) return null
        val uri = try { URI(t) } catch (_: Exception) { return null }
        val type = uri.host ?: "totp"
        val path = uri.path?.trimStart('/') ?: ""

        val (pathIssuer, account) = if (':' in path) {
            val parts = path.split(":", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        } else {
            null to path
        }

        val rawParams = uri.query?.split("&")?.associate {
            val parts = it.split("=", limit = 2)
            val k = parts[0]
            val v = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            } else ""
            k to v
        } ?: emptyMap()

        val secret = rawParams["secret"]
        val issuer = rawParams["issuer"] ?: pathIssuer
        val algorithm = rawParams["algorithm"] ?: "SHA1"
        val digits = rawParams["digits"]?.toIntOrNull() ?: 6
        val period = rawParams["period"]?.toIntOrNull() ?: 30

        return QrAction.OtpAuth(
            issuer = issuer,
            account = account.ifBlank { null },
            secret = secret,
            type = type,
            algorithm = algorithm,
            digits = digits,
            period = period
        )
    }

    private fun parseSms(t: String): QrAction.Sms? {
        val smsto = Regex("^smsto:([^:]+):?(.*)", RegexOption.IGNORE_CASE).find(t)
        if (smsto != null) {
            return QrAction.Sms(smsto.groupValues[1], smsto.groupValues[2].ifBlank { null })
        }
        if (!t.startsWith("sms:", ignoreCase = true)) return null
        val after = t.substring(4)
        val parts = after.split("?", limit = 2)
        val number = parts[0]
        val body = parts.getOrNull(1)?.split("&")?.find { it.startsWith("body=", ignoreCase = true) }
            ?.substringAfter("body=")?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
        return QrAction.Sms(number, body)
    }

    private fun parseEmail(t: String): QrAction.Email? {
        if (!t.startsWith("mailto:", ignoreCase = true)) return null
        val after = t.substring(7)
        val parts = after.split("?", limit = 2)
        val address = parts[0]
        val q = parts.getOrNull(1)?.split("&")?.associate {
            val p = it.split("=", limit = 2)
            p[0].lowercase() to (try { URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8") } catch (_: Exception) { "" })
        } ?: emptyMap()
        return QrAction.Email(
            address = address,
            subject = q["subject"],
            body = q["body"]
        )
    }

    private fun parseGeo(t: String): QrAction.Geo? {
        if (!t.startsWith("geo:", ignoreCase = true)) return null
        val after = t.substring(4).split("?", limit = 2)[0]
        val coords = after.split(",")
        if (coords.size < 2) return null
        val lat = coords[0].toDoubleOrNull() ?: return null
        val lon = coords[1].toDoubleOrNull() ?: return null
        val q = t.substringAfter("q=", "").ifBlank { null }
        return QrAction.Geo(lat, lon, q)
    }

    private fun parseContact(t: String): QrAction.Contact? {
        if (t.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            val rawLines = t.lines()
            val lines = mutableListOf<String>()
            for (line in rawLines) {
                if (lines.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                    val lastIdx = lines.size - 1
                    lines[lastIdx] = lines[lastIdx] + line
                } else {
                    lines.add(line)
                }
            }

            var name: String? = null
            var nFallback: String? = null
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            var org: String? = null
            var title: String? = null

            for (line in lines) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("FN:") || upper.startsWith("FN;") -> {
                        name = unescapeVCard(line.substringAfter(':').trim())
                    }
                    upper.startsWith("N:") || upper.startsWith("N;") -> {
                        val nRaw = line.substringAfter(':').trim()
                        val parts = nRaw.split(';').map { unescapeVCard(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            nFallback = if (parts.size >= 2) "${parts[1]} ${parts[0]}".trim() else parts[0]
                        }
                    }
                    upper.startsWith("TEL:") || upper.startsWith("TEL;") -> {
                        phones.add(line.substringAfter(':').trim())
                    }
                    upper.startsWith("EMAIL:") || upper.startsWith("EMAIL;") -> {
                        emails.add(line.substringAfter(':').trim())
                    }
                    upper.startsWith("ORG:") || upper.startsWith("ORG;") -> {
                        org = unescapeVCard(line.substringAfter(':').trim())
                    }
                    upper.startsWith("TITLE:") || upper.startsWith("TITLE;") -> {
                        title = unescapeVCard(line.substringAfter(':').trim())
                    }
                }
            }
            return QrAction.Contact(
                name = name ?: nFallback,
                phones = phones,
                emails = emails,
                org = org,
                title = title
            )
        }

        if (t.startsWith("MECARD:", ignoreCase = true)) {
            val content = t.substring(7)
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            var name: String? = null
            var org: String? = null

            var i = 0
            while (i < content.length) {
                val colonIdx = content.indexOf(':', i)
                if (colonIdx == -1) break
                val tag = content.substring(i, colonIdx).trim().uppercase()

                val sb = StringBuilder()
                var j = colonIdx + 1
                var escaped = false
                while (j < content.length) {
                    val c = content[j]
                    if (escaped) {
                        sb.append(c)
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == ';') {
                        break
                    } else {
                        sb.append(c)
                    }
                    j++
                }
                val value = sb.toString().trim()
                when (tag) {
                    "N" -> name = value.replace("\\;", ";").replace("\\,", ",")
                    "TEL" -> phones.add(value)
                    "EMAIL" -> emails.add(value)
                    "ORG" -> org = value.replace("\\;", ";").replace("\\,", ",")
                }
                i = j + 1
            }
            return QrAction.Contact(name, phones, emails, org)
        }

        return null
    }

    private fun parseCalendar(t: String): QrAction.CalendarEvent? {
        if (!t.startsWith("BEGIN:VEVENT", ignoreCase = true) &&
            !t.contains("BEGIN:VEVENT", ignoreCase = true)
        ) return null

        val rawLines = t.lines()
        val lines = mutableListOf<String>()
        for (line in rawLines) {
            if (lines.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                val lastIdx = lines.size - 1
                lines[lastIdx] = lines[lastIdx] + line
            } else {
                lines.add(line)
            }
        }

        fun findProp(p: String): String? {
            val prefix1 = "$p:".uppercase()
            val prefix2 = "$p;".uppercase()
            for (line in lines) {
                val upper = line.uppercase()
                if (upper.startsWith(prefix1) || upper.startsWith(prefix2)) {
                    val rawVal = line.substringAfter(':').trim()
                    return unescapeVCard(rawVal)
                }
            }
            return null
        }

        return QrAction.CalendarEvent(
            title = findProp("SUMMARY"),
            dtStart = findProp("DTSTART"),
            dtEnd = findProp("DTEND"),
            location = findProp("LOCATION"),
            description = findProp("DESCRIPTION")
        )
    }

    private fun parsePhone(t: String): QrAction.Phone? {
        if (!t.startsWith("tel:", ignoreCase = true)) return null
        return QrAction.Phone(t.substring(4).trim())
    }

    /**
     * URL classification with factual UrlDisposition, punycode and IDN homograph detection.
     */
    private fun parseUrl(t: String): QrAction.Url? {
        val scheme = when {
            t.startsWith("https://", ignoreCase = true) -> "https"
            t.startsWith("http://", ignoreCase = true) -> "http"
            else -> return null
        }

        // Handle potential non-ASCII unicode domains that fail strict RFC 2396 URI parsing
        val uri = try {
            URI(t)
        } catch (_: Exception) {
            try {
                val afterScheme = t.substring(scheme.length + 3)
                val slashIdx = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
                val hostPart = if (slashIdx != -1) afterScheme.substring(0, slashIdx) else afterScheme
                val rest = if (slashIdx != -1) afterScheme.substring(slashIdx) else ""
                val hostOnly = hostPart.substringBefore(':')
                val portPart = if (':' in hostPart) ":" + hostPart.substringAfter(':') else ""
                val asciiHost = java.net.IDN.toASCII(hostOnly, java.net.IDN.ALLOW_UNASSIGNED)
                URI("$scheme://$asciiHost$portPart$rest")
            } catch (_: Exception) {
                null
            }
        }

        val host = if (uri != null && !uri.host.isNullOrBlank()) {
            uri.host
        } else {
            val afterScheme = t.substring(scheme.length + 3)
            val slashIdx = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
            val hostPart = if (slashIdx != -1) afterScheme.substring(0, slashIdx) else afterScheme
            hostPart.substringBefore(':')
        }

        val asciiHost = try {
            java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED)
        } catch (_: Exception) {
            host
        }

        val isPunycodeOrIdn = asciiHost.split('.').any { it.startsWith("xn--", ignoreCase = true) } ||
                host.split('.').any { it.startsWith("xn--", ignoreCase = true) } ||
                host.any { it.code > 127 }

        val disposition = when {
            isPunycodeOrIdn -> UrlDisposition.PUNYCODE
            host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) -> UrlDisposition.IP_HOST
            scheme == "https" -> UrlDisposition.HTTPS
            scheme == "http" -> UrlDisposition.HTTP
            else -> UrlDisposition.UNSUPPORTED_SCHEME
        }

        return QrAction.Url(
            uri = t,
            disposition = disposition,
            host = host.ifBlank { null }
        )
    }

    private fun unescapeVCard(value: String): String {
        return value
            .replace("\\n", "\n", ignoreCase = true)
            .replace("\\r", "\r", ignoreCase = true)
            .replace(Regex("""\\([,;\\&:])""")) { it.groupValues[1] }
    }
}
