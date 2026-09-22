package com.veilframe.app.qr.scanner

import java.net.URI

/**
 * Registry of QR payload parsers.
 *
 * Parsers are evaluated in priority order; the first match wins.
 * Falls back to [QrAction.Raw] for unrecognized content.
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

    // WIFI:T:WPA;S:SSID;P:pass;H:true;;
    private fun parseWifi(t: String): QrAction.Wifi? {
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        fun extract(key: String) = Regex("$key:([^;]*);", RegexOption.IGNORE_CASE)
            .find(t)?.groupValues?.getOrNull(1) ?: ""
        return QrAction.Wifi(
            ssid = extract("S"),
            password = extract("P"),
            type = extract("T").ifBlank { "WPA" },
            hidden = extract("H").equals("true", ignoreCase = true)
        )
    }

    // upi://pay?pa=...&pn=...&am=...
    private fun parseUpi(t: String): QrAction.UpiPayment? {
        if (!t.startsWith("upi://", ignoreCase = true)) return null
        val uri = try { URI(t) } catch (_: Exception) { return null }
        val q = uri.query?.split("&")?.associate {
            val (k, v) = it.split("=", limit = 2).let { p -> Pair(p[0], p.getOrElse(1) { "" }) }
            k to java.net.URLDecoder.decode(v, "UTF-8")
        } ?: return null
        return QrAction.UpiPayment(
            pa = q["pa"] ?: return null,
            pn = q["pn"], amount = q["am"], currency = q["cu"], note = q["tn"]
        )
    }

    // otpauth://totp/Issuer:account?secret=...&issuer=...
    private fun parseOtpAuth(t: String): QrAction.OtpAuth? {
        if (!t.startsWith("otpauth://", ignoreCase = true)) return null
        val uri = try { URI(t) } catch (_: Exception) { return null }
        val type = uri.host ?: "totp"
        val path = uri.path?.trimStart('/') ?: ""
        val (issuer, account) = if (':' in path) path.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                                else null to path
        val query = uri.query?.split("&")?.associate {
            val (k, v) = it.split("=", limit = 2).let { p -> Pair(p[0], p.getOrElse(1) { "" }) }
            k to v
        } ?: emptyMap()
        return QrAction.OtpAuth(
            issuer = issuer ?: query["issuer"],
            account = account.ifBlank { null },
            secret = query["secret"],
            type = type
        )
    }

    // SMSTO:+123:hello  or  sms:+123?body=hello
    private fun parseSms(t: String): QrAction.Sms? {
        val smsto = Regex("^smsto:([^:]+):?(.*)", RegexOption.IGNORE_CASE).find(t)
        if (smsto != null) return QrAction.Sms(smsto.groupValues[1], smsto.groupValues[2].ifBlank { null })
        if (!t.startsWith("sms:", ignoreCase = true)) return null
        val after = t.substring(4)
        val parts = after.split("?", limit = 2)
        val number = parts[0]
        val body = parts.getOrNull(1)?.split("&")?.find { it.startsWith("body=", ignoreCase = true) }
            ?.substringAfter("body=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        return QrAction.Sms(number, body)
    }

    // mailto:user@example.com?subject=Hi&body=Hello
    private fun parseEmail(t: String): QrAction.Email? {
        if (!t.startsWith("mailto:", ignoreCase = true)) return null
        val after = t.substring(7)
        val parts = after.split("?", limit = 2)
        val address = parts[0]
        val q = parts.getOrNull(1)?.split("&")?.associate {
            val p = it.split("=", limit = 2)
            p[0].lowercase() to java.net.URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        } ?: emptyMap()
        return QrAction.Email(
            address = address,
            subject = q["subject"],
            body = q["body"]
        )
    }

    // geo:lat,lon or geo:lat,lon?q=...
    private fun parseGeo(t: String): QrAction.Geo? {
        if (!t.startsWith("geo:", ignoreCase = true)) return null
        val m = Regex("geo:(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)(?:\\?q=(.*))?", RegexOption.IGNORE_CASE).find(t) ?: return null
        return QrAction.Geo(
            lat = m.groupValues[1].toDoubleOrNull() ?: return null,
            lon = m.groupValues[2].toDoubleOrNull() ?: return null,
            label = m.groupValues.getOrNull(3)?.ifBlank { null }
        )
    }

    // BEGIN:VCARD or MECARD:N:...;
    private fun parseContact(t: String): QrAction.Contact? {
        if (!t.startsWith("BEGIN:VCARD", ignoreCase = true) &&
            !t.startsWith("MECARD:", ignoreCase = true)) return null
        fun vcard(key: String) = Regex("^$key[^:]*:(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(t)?.groupValues?.getOrNull(1)?.trim()
        fun mecard(key: String) = Regex("$key:([^;]*);?").find(t)?.groupValues?.getOrNull(1)
        return if (t.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            QrAction.Contact(name = vcard("FN"), phone = vcard("TEL"), email = vcard("EMAIL"), org = vcard("ORG"))
        } else {
            QrAction.Contact(name = mecard("N"), phone = mecard("TEL"), email = mecard("EMAIL"), org = null)
        }
    }

    // BEGIN:VEVENT
    private fun parseCalendar(t: String): QrAction.CalendarEvent? {
        if (!t.startsWith("BEGIN:VEVENT", ignoreCase = true)) return null
        fun field(key: String) = Regex("^$key:(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(t)?.groupValues?.getOrNull(1)?.trim()
        return QrAction.CalendarEvent(
            title = field("SUMMARY"), dtStart = field("DTSTART"), dtEnd = field("DTEND"),
            location = field("LOCATION"), description = field("DESCRIPTION")
        )
    }

    // tel:+123...
    private fun parsePhone(t: String): QrAction.Phone? {
        if (!t.startsWith("tel:", ignoreCase = true)) return null
        return QrAction.Phone(t.removePrefix("tel:").removePrefix("tel://"))
    }

    // http:// or https://
    private fun parseUrl(t: String): QrAction.Url? {
        if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) return null
        val risk = if (t.startsWith("http://")) 1 else 0  // plain HTTP = low risk flag
        // IDN / punycode detection
        val idn = try { URI(t).host?.contains("xn--") == true } catch (_: Exception) { false }
        return QrAction.Url(t, riskScore = risk + if (idn) 2 else 0)
    }
}
