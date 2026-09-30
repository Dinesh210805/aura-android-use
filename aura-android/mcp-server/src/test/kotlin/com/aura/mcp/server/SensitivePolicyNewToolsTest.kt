package com.aura.mcp.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The new assistant-plane tools route text OUT of the device (SMS body, share
 * text, notification reply) — each free-text arg must pass the same
 * sensitive-text screen as `type_text`, and everything else stays allowed.
 */
class SensitivePolicyNewToolsTest {

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun `notification reply with password text is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "notification_action",
            args("key" to "0|com.whatsapp|1", "action" to "Reply", "reply_text" to "my password is hunter2"),
        )
        assertIs<SensitivePolicy.Decision.Block>(decision)
    }

    @Test
    fun `ordinary notification reply is allowed`() {
        val decision = SensitivePolicy.evaluate(
            "notification_action",
            args("key" to "0|com.whatsapp|1", "action" to "Reply", "reply_text" to "on my way!"),
        )
        assertIs<SensitivePolicy.Decision.Allow>(decision)
    }

    @Test
    fun `sms body with card number is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "system_intent",
            args("action" to "compose_sms", "phone_number" to "9876543210", "body" to "card 4111 1111 1111 1111"),
        )
        assertIs<SensitivePolicy.Decision.Block>(decision)
    }

    @Test
    fun `share text with ssn is blocked`() {
        val decision = SensitivePolicy.evaluate(
            "system_intent",
            args("action" to "share_text", "text" to "his ssn 123-45-6789"),
        )
        assertIs<SensitivePolicy.Decision.Block>(decision)
    }

    @Test
    fun `benign system intents are allowed`() {
        val decision = SensitivePolicy.evaluate(
            "system_intent",
            args("action" to "set_alarm", "hour" to "7"),
        )
        assertIs<SensitivePolicy.Decision.Allow>(decision)
    }
}
