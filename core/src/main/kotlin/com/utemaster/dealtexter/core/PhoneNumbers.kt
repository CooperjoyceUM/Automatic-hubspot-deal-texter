package com.utemaster.dealtexter.core

object PhoneNumbers {

    /**
     * Normalises a phone number to E.164. Australian numbers become +61…
     *
     *   "0412 345 678"      → "+61412345678"
     *   "412345678"         → "+61412345678"
     *   "61 412 345 678"    → "+61412345678"
     *   "+61 (0)412 345 678"→ "+61412345678"
     *   "0011 64 21 123 4567" → "+64211234567"
     *   "(02) 9876 5432"    → "+61298765432"
     *
     * Returns null if the input can't be understood.
     */
    fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        if (plus) {
            if (digits.startsWith("61")) return au(digits.removePrefix("61"))
            return if (digits.length in 8..15) "+$digits" else null
        }
        if (digits.startsWith("0011")) { // Australian international prefix
            digits = digits.removePrefix("0011")
            return if (digits.startsWith("61")) au(digits.removePrefix("61")) else if (digits.length in 8..15) "+$digits" else null
        }
        if (digits.startsWith("0061")) return au(digits.removePrefix("0061"))
        if (digits.startsWith("61") && digits.length == 11) return au(digits.removePrefix("61"))
        if (digits.startsWith("0") && digits.length == 10) return au(digits)
        if (digits.length == 9 && digits[0] in "23478") return au(digits)
        return null
    }

    /** National significant number (with or without trunk 0) → +61… */
    private fun au(national: String): String? {
        val n = national.removePrefix("0")
        return if (n.length == 9 && n[0] in "23478") "+61$n" else null
    }

    /** True for anything that can receive an SMS: Australian mobiles (+614…) or any overseas number. */
    fun isTextable(e164: String): Boolean =
        if (e164.startsWith("+61")) e164.startsWith("+614") && e164.length == 12 else e164.startsWith("+")

    /** "+61412345678" → "+61 412 345 678" for display. */
    fun pretty(e164: String): String =
        if (e164.startsWith("+614") && e164.length == 12) {
            "+61 ${e164.substring(3, 6)} ${e164.substring(6, 9)} ${e164.substring(9)}"
        } else e164
}
