package com.aura.aura_ui.agent.memory

import com.aura.aura_ui.agent.mcpbridge.client.McpPayloadGuard

/**
 * Write-time PII firewall (umbrella §2: "store UI structure, not content"). Best-effort redaction —
 * the real protection is the structural discipline in PathStepSanitizer (record tool + nav label,
 * never field content); this is the second line. Conservative: when unsure, redact.
 */
object PiiFirewall {
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val AMOUNT = Regex("""[\$₹€£]\s?\d[\d,]*(?:\.\d+)?""")
    private val PHONE = Regex("""\+?\d[\d\s().-]{5,}\d""")
    private val DIGITS = Regex("""\d{7,}""")
    // M6 — standalone 4-6 digit runs are OTP / 2FA / PIN codes. Word-bounded so it
    // only hits bare codes; conservative over-redaction of a stray year is fine in
    // fuzzy stored memory (the whole file's posture is "when unsure, redact").
    private val CODE = Regex("""\b\d{4,6}\b""")
    private val HANDLE = Regex("""@[A-Za-z0-9_]{2,}""")
    private val SPACES = Regex("""[ \t]{2,}""")

    private val SENSITIVE_TERMS = listOf(
        "password", "passcode", "otp", "one-time", "one time", "pin ", "ssn", "cvv", "balance",
        "credit card", "diagnos", "prescription", "said:", "message:", "texted",
        // M6 — widen 2FA / verification vocabulary.
        "verification code", "security code", "auth code", "2fa", "verification",
    )

    /** Redact emails/amounts/phones/handles, collapse whitespace, then cap (reuse McpPayloadGuard). */
    fun scrub(text: String): String {
        if (text.isBlank()) return ""
        var s = EMAIL.replace(text, "<email>")
        s = AMOUNT.replace(s, "<amount>")
        s = PHONE.replace(s, "<number>")
        s = DIGITS.replace(s, "<number>")
        s = CODE.replace(s, "<code>")
        s = HANDLE.replace(s, "<handle>")
        s = SPACES.replace(s, " ").trim()
        return McpPayloadGuard.capDescription(s)
    }

    /** True if the text likely contains content that must never surface unprompted (V.7). */
    fun isLikelySensitive(text: String): Boolean {
        val t = text.lowercase()
        return SENSITIVE_TERMS.any { t.contains(it) }
    }
}
