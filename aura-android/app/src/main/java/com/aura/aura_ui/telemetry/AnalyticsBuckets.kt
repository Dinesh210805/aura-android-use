package com.aura.aura_ui.telemetry

/**
 * Turns raw values into the coarse, content-free labels [AuraAnalytics] sends. Pure; covered by
 * `AnalyticsBucketsTest`.
 *
 * - Why coarse: a band or a category answers "how is AURA doing" without making any single
 *   event describe what one person did.
 */
object AnalyticsBuckets {

    /** `<10s`, `10-30s`, `30-60s`, `1-3m`, `3-10m`, `>10m`. Negative counts as `<10s`. */
    fun durationBand(ms: Long): String = when {
        ms < 10_000L -> "<10s"
        ms < 30_000L -> "10-30s"
        ms < 60_000L -> "30-60s"
        ms < 180_000L -> "1-3m"
        ms < 600_000L -> "3-10m"
        else -> ">10m"
    }

    /**
     * What kind of failure ended an agent run: `no_key`, `auth`, `rate_limit`, `network`,
     * `timeout`, or `other`.
     *
     * - Contract: reads the exception's class name and message only to pick a label; neither is
     *   sent anywhere.
     */
    fun errorCategory(className: String, message: String?): String {
        val m = message.orEmpty().lowercase()
        val c = className.substringAfterLast('.')
        return when {
            "no api key" in m -> "no_key"
            Regex("\\b(401|403)\\b").containsMatchIn(m) || "unauthorized" in m || "invalid api key" in m ||
                "invalid key" in m || "permission denied" in m -> "auth"
            Regex("\\b429\\b").containsMatchIn(m) || "rate limit" in m || "quota" in m -> "rate_limit"
            c == "SocketTimeoutException" || c == "TimeoutCancellationException" || "timed out" in m ||
                "timeout" in m -> "timeout"
            c in NETWORK_EXCEPTIONS -> "network"
            else -> "other"
        }
    }

    /**
     * The LLM provider as a family name: a built-in endpoint's id (for example `gemini`, `groq`),
     * or `custom` for anything the user added. A custom endpoint's own id or URL is never sent.
     */
    fun providerFamily(selectedId: String, isBuiltin: Boolean): String =
        if (isBuiltin) selectedId.take(24) else "custom"

    /** `windows`, `mac`, `linux`, or `other`, from the PC's self-declared Node.js platform. */
    fun pcOs(platform: String?): String = when (platform?.lowercase()) {
        "win32" -> "windows"
        "darwin" -> "mac"
        "linux" -> "linux"
        else -> "other"
    }

    private val NETWORK_EXCEPTIONS = setOf(
        "UnknownHostException",
        "ConnectException",
        "NoRouteToHostException",
        "SocketException",
        "SSLException",
        "SSLHandshakeException",
        "IOException",
    )
}
