package com.aura.mcp.server

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GP5+GP6 — package-only screening used by the foreground gate. */
class SensitivePolicyForegroundTest {

    @Test
    fun `exact-blocklisted UPI package blocks`() {
        val d = SensitivePolicy.screenPackage("in.org.npci.upiapp")
        assertIs<SensitivePolicy.Decision.Block>(d)
    }

    @Test
    fun `banking-pattern package blocks`() {
        val d = SensitivePolicy.screenPackage("com.mybank.mobile")
        assertIs<SensitivePolicy.Decision.Block>(d)
        assertTrue((d as SensitivePolicy.Decision.Block).category == SensitivePolicy.Category.BANKING_PATTERN)
    }

    @Test
    fun `authenticator package blocks with AUTH category`() {
        val d = SensitivePolicy.screenPackage("com.azure.authenticator")
        assertIs<SensitivePolicy.Decision.Block>(d)
        assertTrue((d as SensitivePolicy.Decision.Block).category == SensitivePolicy.Category.AUTH_APP)
    }

    @Test
    fun `ordinary app allows`() {
        assertIs<SensitivePolicy.Decision.Allow>(SensitivePolicy.screenPackage("com.whatsapp"))
    }

    @Test
    fun `null and blank allow`() {
        assertIs<SensitivePolicy.Decision.Allow>(SensitivePolicy.screenPackage(null))
        assertIs<SensitivePolicy.Decision.Allow>(SensitivePolicy.screenPackage("  "))
    }

    @Test
    fun `package rules only - text regexes must not fire on a package id`() {
        // screen() would text-block this via the \bssn\b regex; a package id must
        // only be judged by the package rules (review finding, 2026-07-12).
        assertIs<SensitivePolicy.Decision.Block>(SensitivePolicy.screen("com.ssn.tracker"))
        assertIs<SensitivePolicy.Decision.Allow>(SensitivePolicy.screenPackage("com.ssn.tracker"))
    }
}
