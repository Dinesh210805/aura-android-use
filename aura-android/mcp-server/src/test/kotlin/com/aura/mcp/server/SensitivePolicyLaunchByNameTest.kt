package com.aura.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertTrue

/**
 * E3/GP2 — with launch_app accepting `app_name`, the pre-dispatch policy gate
 * must screen the NAME argument too (the resolved package is separately
 * re-screened in the handler; this is the cheap first line).
 */
class SensitivePolicyLaunchByNameTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `launch_app by blocked app_name is blocked pre-dispatch`() {
        val decision = SensitivePolicy.evaluate("launch_app", args("app_name" to "PayPal"))
        assertTrue(decision is SensitivePolicy.Decision.Block, "PayPal by name must be blocked")
    }

    @Test
    fun `launch_app by harmless app_name is allowed`() {
        val decision = SensitivePolicy.evaluate("launch_app", args("app_name" to "Amazon"))
        assertTrue(decision is SensitivePolicy.Decision.Allow)
    }

    @Test
    fun `launch_app by blocked package_name stays blocked`() {
        val decision = SensitivePolicy.evaluate(
            "launch_app",
            args("package_name" to "com.paypal.android.p2pmobile"),
        )
        assertTrue(decision is SensitivePolicy.Decision.Block)
    }
}
