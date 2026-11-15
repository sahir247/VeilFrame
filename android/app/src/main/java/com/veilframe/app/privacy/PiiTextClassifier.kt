package com.veilframe.app.privacy

/**
 * Classifies recognized OCR text spans/lines for common personally identifiable information (PII)
 * and credentials without sending any data off-device.
 */
object PiiTextClassifier {

    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
    private val PAN_REGEX = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val SSN_REGEX = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
    private val AADHAAR_GROUPED_REGEX = Regex("""(?<!\d[\s-])\b[2-9]\d{3}[\s-]\d{4}[\s-]\d{4}\b(?![\s-]?\d)""")
    private val PHONE_LABEL_REGEX = Regex("""(?i)\b(?:whatsapp|wa|phone|mobile|tel|contact|cell|call|toll\s*free)\s*(?:no\.?|number|num)?\s*[:=#\-]?\s*(\+?\d[\d\s.-]{7,16}\d)""")
    private val TOLL_FREE_REGEX = Regex("""(?<!\d)1800[-.\s]?\d{3}[-.\s]?\d{3,4}(?!\d)""")
    private val PHONE_10_DIGIT_REGEX = Regex("""(?<!\d)[6-9]\d{9}(?!\d)""")
    private val PHONE_SPLIT_REGEX = Regex("""(?<!\d)[6-9]\d{4}[\s.-]\d{5}(?!\d)""")
    private val PHONE_INTL_REGEX = Regex("""(?<!\d[\s.-])(?:\+\d{1,3}[\s.-](?:\d{2,5}[\s.-])?|\(\d{2,4}\)[\s.-]?|\b\d{2,4}[\s.-])\d{3,5}[\s.-]\d{4,5}\b(?![\s.-]?\d)""")
    private val PHONE_COMPACT_REGEX = Regex("""\+\d{8,14}\b""")
    private val CARD_CANDIDATE_REGEX = Regex("""(?:\d[\s-]?){13,19}""")
    private val SECRET_TOKEN_REGEX = Regex("""(?:sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z\-_]{20,}|AKIA[0-9A-Z]{16}|Bearer\s+[A-Za-z0-9._\-]{16,})""")
    private val KEYWORD_SECRET_REGEX = Regex("""(?i)\b(?:password|passwd|secret|api[_\s]?key|otp|cvv|pin|ssn|passport|license)\b\s*[:=#]?\s*\S{3,}""")
    private val IPV4_REGEX = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val UPI_REGEX = Regex("""(?i)(?:upi://pay\S*|\b[A-Za-z0-9.\-_]{2,}@(?:ok\w+|ybl|ibl|axl|paytm|upi|icici|sbi|hdfcbank)\b)""")

    /**
     * Returns a short descriptive label (e.g. `"Email"`, `"Phone"`, `"Card Number"`) if [text]
     * matches sensitive PII patterns, or `null` when benign.
     */
    fun classifySensitiveText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < 4) return null

        if (SECRET_TOKEN_REGEX.containsMatchIn(trimmed)) {
            return "Credential / Key"
        }
        if (EMAIL_REGEX.containsMatchIn(trimmed)) {
            return "Email"
        }
        if (UPI_REGEX.containsMatchIn(trimmed)) {
            return "UPI Handle"
        }
        if (SSN_REGEX.containsMatchIn(trimmed)) {
            return "SSN"
        }
        if (PAN_REGEX.containsMatchIn(trimmed)) {
            return "Tax / PAN ID"
        }
        if (containsLuhnValidCard(trimmed)) {
            return "Card Number"
        }
        if (AADHAAR_GROUPED_REGEX.containsMatchIn(trimmed)) {
            return "National ID"
        }
        val phoneLabel = findPhoneMatch(trimmed)
        if (phoneLabel != null) {
            return phoneLabel
        }
        if (PHONE_COMPACT_REGEX.containsMatchIn(trimmed) || PHONE_INTL_REGEX.containsMatchIn(trimmed)) {
            return "Phone"
        }
        if (KEYWORD_SECRET_REGEX.containsMatchIn(trimmed)) {
            return "Credential / Key"
        }
        val ipMatch = IPV4_REGEX.find(trimmed)
        if (ipMatch != null && (1..4).all { idx ->
                val v = ipMatch.groupValues[idx].toIntOrNull()
                v != null && v in 0..255
            }
        ) {
            return "IPv4 Address"
        }
        return null
    }

    internal fun findPhoneMatch(input: String): String? {
        if (PHONE_LABEL_REGEX.containsMatchIn(input)) {
            return if (input.contains("whatsapp", ignoreCase = true) || input.contains("wa", ignoreCase = true)) {
                "Phone (WhatsApp)"
            } else if (input.contains("toll", ignoreCase = true)) {
                "Toll Free Phone"
            } else {
                "Phone"
            }
        }
        if (TOLL_FREE_REGEX.containsMatchIn(input)) return "Toll Free Phone"
        if (PHONE_10_DIGIT_REGEX.containsMatchIn(input) || PHONE_SPLIT_REGEX.containsMatchIn(input)) return "Phone"
        return null
    }

    internal fun containsLuhnValidCard(input: String): Boolean {
        for (match in CARD_CANDIDATE_REGEX.findAll(input)) {
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..19 && passesLuhn(digits)) {
                return true
            }
        }
        return false
    }

    internal fun passesLuhn(digits: String): Boolean {
        if (digits.length < 13 || digits.all { it == digits.first() }) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
