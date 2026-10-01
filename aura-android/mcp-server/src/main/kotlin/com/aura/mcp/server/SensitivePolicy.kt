package com.aura.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hard safety gate for the on-device MCP server — the Kotlin equivalent of the
 * legacy OPA policy (`aura_live_mcp/policies/{safety,apps}.rego` +
 * `policies/sensitive_actions.py`), ported as static data so it runs locally
 * and deterministically with no network dependency.
 *
 * ## Why this shape (and not the legacy one)
 *
 * The legacy policy keyword-matched a natural-language *command string*. At the
 * MCP boundary there is no command string — only tool calls with arguments. So
 * enforcement lives at the **entry points** an agent uses to reach a sensitive
 * surface:
 *
 *  - `launch_app(package_name)` and `open_deeplink(uri, package_name)` — block
 *    banking / payment / authenticator / password-manager apps by exact package,
 *    banking package-name pattern, and banking host pattern. Blocking entry means
 *    the agent never gets *into* the app to begin with.
 *  - `type_text(text)` — block entering card numbers, CVVs, SSNs, or
 *    "password is…" / "pin is…" secrets into any field.
 *
 * Raw `tap` / `swipe` / `long_press` are coordinate-only and semantically
 * opaque — they cannot be classified, so they are intentionally **not** gated
 * here (a tap inside an already-open app is out of scope). Advisory free-text
 * args (e.g. `perceive_screen.description`) are agent-authored and never gated.
 *
 * ## Fail-closed
 *
 * Evaluation is local and deterministic, so there is no transient error to
 * forgive — this gate is **fail-closed** (block on match). That intentionally
 * diverges from the network PromptGuard/OPA "fail-safe (allow on error)"
 * posture documented in CLAUDE.md, which applied to remote calls that could
 * flake.
 */
// PUBLIC (was `internal`) since 2026-08-12. The conversation plane — Gemini Live + the brain lane,
// which live in the `:app` module — must screen a request BEFORE the reasoning model reads it, and
// the whole point is that they reuse THIS object rather than growing a second, drifting copy of the
// blocklists. Widening the visibility is the entire cost of that reuse.
object SensitivePolicy {

    /** Why an action was refused — surfaced to the agent and the audit log. */
    enum class Category(val id: String) {
        FINANCIAL_APP("banking_or_payment_app"),
        AUTH_APP("authenticator_or_password_app"),
        BANKING_PATTERN("banking_keyword_app"),
        SENSITIVE_TEXT("sensitive_text_entry"),

        /**
         * A verification code this device was just shown, on its way back out. See [OtpSighting]
         * for the eval finding (2026-08-26 task 22) and why this matches sighted codes rather
         * than code-shaped numbers.
         */
        OTP_EXFILTRATION("otp_leaving_device"),
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Block(val category: Category, val message: String) : Decision
    }

    // ── Blocklists (ported from apps.rego) ───────────────────────────────────

    /** Financial / payment apps — hard blocked on launch + deep link. */
    private val BLOCKED_FINANCIAL_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel", // Google Pay / Wallet
        "com.samsung.android.spay",             // Samsung Pay
        "com.paypal.android.p2pmobile",         // PayPal
        "com.venmo",                            // Venmo
        "com.zellepay.zelle",                   // Zelle
        "com.cashapp",                          // Cash App
        "com.squareup.cash",                    // Cash App (alt)
        // GP7 — India / UPI market (the device's actual market). Exact ids so a
        // general shopping app is never caught by an over-broad substring.
        "com.google.android.apps.nbu.paisa.user", // Google Pay India ("paisa" matched no pattern)
        "in.org.npci.upiapp",                     // BHIM
        "com.dreamplug.androidapp",               // CRED
        "in.slice.android",                       // Slice
        "com.mobikwik_new",                       // MobiKwik
        "com.freecharge.android",                 // Freecharge
        "com.csam.icici.bank.imobile",            // iMobile Pay (ICICI)
    )

    /** Authenticator / password-manager apps — hard blocked. */
    private val BLOCKED_AUTH_PACKAGES = setOf(
        "com.google.android.apps.authenticator2", // Google Authenticator
        "com.authy.authy",                        // Authy
        "com.microsoft.msa",                      // (legacy id from apps.rego)
        "com.azure.authenticator",                // Microsoft Authenticator (real id)
        "com.lastpass.lpandroid",                 // LastPass
        "com.onepassword.android",                // 1Password
        "com.bitwarden.vault",                    // Bitwarden
    )

    /** Substrings that mark a package as a banking/finance app. */
    private val BANKING_PACKAGE_PATTERNS = listOf(
        "bank", "chase", "wellsfargo", "citi", "bofa", "capitalone", "finance",
        "trading", "invest", "crypto", "wallet", "fidelity", "schwab", "robinhood",
        "coinbase", "binance", "etrade", "ameritrade", "vanguard", "paypal", "venmo",
        "paytm", "phonepe",
        // GP7 — India/UPI market patterns (payment-specific; won't catch shopping apps).
        "paisa", "npci", "mobikwik", "freecharge", "razorpay",
    )

    /**
     * Custom URI schemes that open a payment/UPI sheet. These carry no
     * meaningful `host` (e.g. `upi://pay?…` has host "pay"), so the banking-host
     * rules never catch them — they must be blocked by scheme. GP2: the exact
     * bypass that let `open_deeplink(uri="upi://pay?…")` fire a live UPI sheet
     * while `launch_app(paytm)` was refused.
     */
    private val BLOCKED_PAYMENT_SCHEMES = setOf(
        "upi",       // generic UPI intent (India)
        "tez",       // Google Pay India (legacy scheme)
        "gpay",
        "phonepe",
        "paytm", "paytmmp",
        "bhim",
        "credpay",
        "venmo",
        "cashme",    // Cash App
    )

    /** Substrings that mark a deep-link host as a banking/finance domain. */
    private val BANKING_HOST_PATTERNS = listOf(
        "bank", "chase", "wellsfargo", "citi", "bofa", "bankofamerica", "capitalone",
        "paypal", "venmo", "cashapp", "cash.app", "zelle", "coinbase", "binance",
        "robinhood", "crypto", "finance", "fidelity", "schwab", "etrade", "vanguard",
        "paytm", "phonepe",
    )

    // ── Sensitive text (ported from safety.rego sensitive_patterns + card/SSN) ─

    private val SENSITIVE_TEXT_REGEXES = listOf(
        Regex("""password\s+is""", RegexOption.IGNORE_CASE),
        Regex("""\bpin\s+is""", RegexOption.IGNORE_CASE),
        Regex("""social\s+security""", RegexOption.IGNORE_CASE),
        Regex("""\bssn\b""", RegexOption.IGNORE_CASE),
        Regex("""credit\s*card\s*(number|no\.?)""", RegexOption.IGNORE_CASE),
        Regex("""\bcv[cv]\b""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""), // US SSN format
    )

    /** Matches a run of 13–19 digits (optionally space/dash separated). */
    private val CARD_CANDIDATE = Regex("""(?:\d[ -]?){13,19}""")

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Evaluate a tool call. Only app entry points and arguments that carry text or a URL off
     * the device are inspected; every other tool returns [Decision.Allow].
     */
    fun evaluate(toolName: String, args: JsonObject?): Decision = when (toolName) {
        // E3 — launch_app accepts `app_name` too; screen BOTH the raw package
        // and the human-readable name (the handler re-screens the RESOLVED
        // package after lookup — this is the cheap pre-dispatch line).
        "launch_app" -> {
            val byPackage = evalPackage(stringArg(args, "package_name"))
            if (byPackage is Decision.Block) byPackage else evalPackage(stringArg(args, "app_name"))
        }
        "open_deeplink" -> firstBlock(
            evalPackage(stringArg(args, "package_name")),
            // E3 — open_deeplink accepts `app_name` too; screen the raw name here
            // (the handler re-screens the RESOLVED package after lookup).
            evalPackage(stringArg(args, "app_name")),
            evalUriTarget(stringArg(args, "uri")),
            // A query string (`smsto:…?body=`, `wa.me/…?text=`) carries text off the device.
            evalUrl(stringArg(args, "uri")),
        )
        "type_text" -> evalText(stringArg(args, "text"))
        // Assistant plane — free-text that leaves the device (a reply, an SMS
        // body, a share payload) gets the same screen as type_text.
        "notification_action" -> evalText(stringArg(args, "reply_text"))
        "system_intent" -> firstBlock(
            evalText(stringArg(args, "body")),
            evalText(stringArg(args, "text")),
            evalText(stringArg(args, "notes")),
            evalText(stringArg(args, "subject")),
        )
        // The browser is an exfiltration path like any other: a code typed into a form
        // field leaves the device just as surely as one sent by SMS.
        "browser_act" -> evalText(stringArg(args, "value"))
        // A URL reaches its server the moment it loads, with nothing on screen: the same
        // exfiltration path as a typed form value. Banking sites get the deep-link host rule.
        "browser_open", "browser_tabs" -> stringArg(args, "url").let { firstBlock(evalUrl(it), evalHost(it)) }
        // The query is sent to the search provider.
        "web_search" -> evalText(stringArg(args, "query"))
        else -> Decision.Allow
    }

    private fun firstBlock(vararg decisions: Decision): Decision =
        decisions.firstOrNull { it is Decision.Block } ?: Decision.Allow

    /**
     * Freeform pre-check for the `validate_action` tool. Treats [value] as a
     * possible package id, deep-link host, or text-to-type and blocks if any
     * rule matches — so an agent can ask "is this target sensitive?" before it
     * commits to a tool call.
     */
    fun screen(value: String?): Decision {
        if (value.isNullOrBlank()) return Decision.Allow
        evalPackage(value).let { if (it is Decision.Block) return it }
        evalHost(value).let { if (it is Decision.Block) return it }
        return evalText(value)
    }

    /**
     * GP5+GP6 — package-ONLY screening for the foreground gate. Unlike [screen]
     * (which also tries host + text rules for freeform values), a foreground
     * package is definitionally a package id, so only the package rules apply.
     */
    fun screenPackage(pkg: String?): Decision = evalPackage(pkg)

    /**
     * Screen a **spoken, natural-language request** — the conversation plane's front door
     * (added 2026-08-12).
     *
     * ## Why [screen] alone is not enough here
     *
     * [screen] was built for TOOL ARGUMENTS: package ids, deep-link hosts, and text-to-type. Two
     * of its three rule sets transfer to speech unchanged — the text regexes still catch "my card
     * is 4111…" and "the password is …". The third does not: a person says *"open Google Pay"*,
     * never `com.google.android.apps.walletnfcrel`, so the package rules never fire and the
     * request sails through to the reasoning model.
     *
     * This closes that hole using the SAME data — [BANKING_PACKAGE_PATTERNS] matched as words
     * plus the spoken names of the already-blocked payment/auth apps. No second blocklist: adding
     * one bank to [BANKING_PACKAGE_PATTERNS] still updates every lane at once.
     *
     * ## Word-boundary matching, not `contains`
     *
     * The package rules use substring matching, which is right for ids (`com.mybank.app`) and
     * wrong for prose: `contains("invest")` refuses *"investigate this"* and `contains("crypto")`
     * refuses *"cryptography"*. Matching whole words removes that class of false positive.
     *
     * To be precise about the limit — a sentence containing the whole word IS still refused.
     * "Check my bank balance" is blocked, and should be: that is the request this policy exists
     * to stop. Word matching buys accuracy against longer innocent words, not intent detection.
     */
    fun screenSpokenRequest(request: String?): Decision {
        if (request.isNullOrBlank()) return Decision.Allow

        // Text rules (cards, SSNs, "password is") apply to speech verbatim.
        evalText(request).let { if (it is Decision.Block) return it }

        val lower = request.lowercase()

        SPOKEN_SENSITIVE_APP_NAMES.firstOrNull { lower.contains(it) }?.let { name ->
            return Decision.Block(
                Category.FINANCIAL_APP,
                "I can't help with payment or authenticator apps — \"$name\" is off limits. " +
                    "You'll need to do that one yourself.",
            )
        }

        val words = WORD_SPLIT.split(lower).filter { it.isNotEmpty() }.toSet()
        BANKING_PACKAGE_PATTERNS.firstOrNull { it in words }?.let { hit ->
            return Decision.Block(
                Category.BANKING_PATTERN,
                "That sounds like it involves banking or finance (\"$hit\"), which I'm not " +
                    "allowed to touch. You'll need to handle that one yourself.",
            )
        }

        return Decision.Allow
    }

    /**
     * How people SAY the apps already blocked by package id. Multi-word entries are matched as
     * substrings because they cannot collide with ordinary prose the way a bare word can — but
     * note the deliberate omissions: plain "pay", "cash" and "wallet" are far too common in
     * normal speech ("pay attention", "cash in on") to be safe here.
     */
    private val SPOKEN_SENSITIVE_APP_NAMES = listOf(
        "google pay", "gpay", "g pay", "samsung pay", "apple pay", "phonepe", "phone pe",
        "paytm", "paypal", "venmo", "cash app", "zelle", "google wallet", "samsung wallet",
        "google authenticator", "microsoft authenticator", "authy", "1password", "lastpass",
        "bitwarden", "dashlane", "keeper password",
    )

    /** Splits prose into words so package substrings are not matched mid-word. */
    private val WORD_SPLIT = Regex("""[^a-z0-9]+""")

    private fun evalPackage(pkg: String?): Decision {
        if (pkg.isNullOrBlank()) return Decision.Allow
        val p = pkg.trim().lowercase()
        return when {
            p in BLOCKED_FINANCIAL_PACKAGES -> Decision.Block(Category.FINANCIAL_APP, FINANCIAL_MSG)
            p in BLOCKED_AUTH_PACKAGES -> Decision.Block(Category.AUTH_APP, AUTH_MSG)
            BANKING_PACKAGE_PATTERNS.any { p.contains(it) } ->
                Decision.Block(Category.BANKING_PATTERN, FINANCIAL_MSG)
            else -> Decision.Allow
        }
    }

    /**
     * Screen an `open_deeplink` URI. A synthetic `app-shortcut://<pkg>/<id>`
     * URI carries a *package name* as its host, so it gets the exact-blocklist
     * PACKAGE rules; everything else gets the banking-domain host rules.
     */
    private fun evalUriTarget(uri: String?): Decision {
        if (uri.isNullOrBlank()) return Decision.Allow
        val trimmed = uri.trim()
        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull()
        // Robust scheme extraction: some payment URIs (e.g. `upi://pay?pa=x@y`)
        // fail java.net.URI parsing, so fall back to the raw text before ':'.
        val scheme = (parsed?.scheme ?: trimmed.substringBefore(":", ""))
            .lowercase()
            .ifBlank { null }

        if (scheme != null && scheme in BLOCKED_PAYMENT_SCHEMES) {
            return Decision.Block(Category.FINANCIAL_APP, FINANCIAL_MSG)
        }
        if (scheme == "app-shortcut") {
            val pkg = evalPackage(parsed?.host)
            if (pkg is Decision.Block) return pkg
        }
        return evalHost(uri)
    }

    private fun evalHost(uri: String?): Decision {
        if (uri.isNullOrBlank()) return Decision.Allow
        val host = runCatching { java.net.URI(uri.trim()).host }.getOrNull()?.lowercase()
            ?: return Decision.Allow
        return if (BANKING_HOST_PATTERNS.any { host.contains(it) }) {
            Decision.Block(Category.FINANCIAL_APP, FINANCIAL_MSG)
        } else {
            Decision.Allow
        }
    }

    /**
     * The OTP rule alone, for URLs. The card-number and SSN rules are text rules: long numeric
     * ids in paths (19-digit status ids) are Luhn-valid one time in ten.
     */
    private fun evalUrl(url: String?): Decision =
        if (OtpSighting.codeIn(url) != null) Decision.Block(Category.OTP_EXFILTRATION, OTP_MSG) else Decision.Allow

    private fun evalText(text: String?): Decision {
        if (text.isNullOrBlank()) return Decision.Allow
        // Checked first, and reported as its own category: "you are forwarding the code this
        // phone was just shown" is a different thing to have caught than "this looks like a card
        // number", and the user reading the audit log needs to be able to tell them apart.
        OtpSighting.codeIn(text)?.let { return Decision.Block(Category.OTP_EXFILTRATION, OTP_MSG) }
        if (SENSITIVE_TEXT_REGEXES.any { it.containsMatchIn(text) }) {
            return Decision.Block(Category.SENSITIVE_TEXT, TEXT_MSG)
        }
        if (looksLikeCardNumber(text)) {
            return Decision.Block(Category.SENSITIVE_TEXT, TEXT_MSG)
        }
        return Decision.Allow
    }

    /** True if [text] contains a 13–19 digit, Luhn-valid card-number candidate. */
    private fun looksLikeCardNumber(text: String): Boolean =
        CARD_CANDIDATE.findAll(text).any { m ->
            val digits = m.value.filter(Char::isDigit)
            digits.length in 13..19 && isLuhnValid(digits)
        }

    private fun isLuhnValid(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alt = !alt
        }
        return sum % 10 == 0
    }

    private fun stringArg(args: JsonObject?, name: String): String? =
        args?.get(name)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /** Human-readable policy document — backs the `aura://policy/sensitive-actions` resource. */
    fun policyMarkdown(): String = """
        # AURA MCP — Sensitive-Action Policy (hard block, fail-closed)

        These actions are **refused server-side**. The agent cannot override them;
        a blocked call returns `error: "policy_blocked"` with the category below.

        Enforced at the tool boundary (entry points only — a tap inside an
        already-open app is not classifiable and is out of scope):

        - **launch_app / open_deeplink** — blocked for banking & payment apps
          (Google Pay, Samsung Pay, PayPal, Venmo, Zelle, Cash App), authenticator
          & password managers (Google Authenticator, Authy, Microsoft Authenticator,
          LastPass, 1Password, Bitwarden), any package whose name matches a banking
          keyword (${BANKING_PACKAGE_PATTERNS.joinToString(", ")}), and any deep-link
          host matching a banking domain.
        - **type_text** — blocked when the text looks like a credit-card number
          (Luhn-checked), CVV, SSN, or contains "password is…" / "pin is…".
        - **browser_open / browser_tabs** — blocked for banking & payment domains.
        - **Anything that sends text or a URL off the device** (type_text, replies, SMS and
          share text, browser URLs and form values, deep-link queries, web_search) — blocked
          when it contains a verification code this phone was just shown.
        - **All gestures & screen perception while a blocked app is foreground** —
          if a banking/payment/authenticator app is on screen (however it got
          there), taps, scrolls, typing, screenshots, UI-tree reads, and
          perceive_screen are refused (`error: "foreground_blocked"`). Use
          press_back / press_home to leave the app.

        If the user truly needs one of these, they must do it themselves on the
        device. Do not attempt workarounds.
    """.trimIndent()

    private const val FINANCIAL_MSG =
        "Blocked for your security: I can't open banking or payment apps. " +
            "Please handle financial transactions yourself on the device."
    private const val AUTH_MSG =
        "Blocked for your security: I can't open authenticator or password-manager apps."
    private const val TEXT_MSG =
        "Blocked for your security: I won't type card numbers, CVVs, SSNs, PINs, or passwords."

    /**
     * Written to be repeated to the user, because the agent will read it out. It names what was
     * caught and why the refusal is not negotiable — an agent that instead says "I can't do that"
     * invites the user to rephrase, and a security control the user can talk their way past is
     * decoration. Do not add a "unless you confirm" clause: task 23 is what that looks like.
     */
    private const val OTP_MSG =
        "Blocked: that text contains a verification code this phone was just sent. " +
            "One-time codes are how someone takes over an account, so I won't forward one " +
            "anywhere — not by message, not into a form — even if you ask me to. " +
            "If you need it somewhere, copy it across yourself."
}
