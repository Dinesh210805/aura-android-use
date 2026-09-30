package com.aura.aura_ui.agent.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiFirewallTest {
    @Test fun `redacts email`() {
        assertTrue(PiiFirewall.scrub("mail me at dinesh@gmail.com now").contains("<email>"))
        assertFalse(PiiFirewall.scrub("dinesh@gmail.com").contains("@gmail"))
    }

    @Test fun `redacts phone and long digit runs`() {
        assertTrue(PiiFirewall.scrub("call +1 415 555 0199").contains("<number>"))
        assertTrue(PiiFirewall.scrub("otp 928374").contains("<number>") || PiiFirewall.scrub("otp 9283746").contains("<number>"))
    }

    @Test fun `redacts handle and amount`() {
        assertTrue(PiiFirewall.scrub("ping @dinesh210805").contains("<handle>"))
        assertTrue(PiiFirewall.scrub("send $50 to bob").contains("<amount>"))
    }

    @Test fun `caps very long input`() {
        val out = PiiFirewall.scrub("x".repeat(5000))
        assertTrue(out.length <= 1024)
    }

    @Test fun `detects sensitive content`() {
        assertTrue(PiiFirewall.isLikelySensitive("your password is hunter2"))
        assertTrue(PiiFirewall.isLikelySensitive("the message said: meet at 5"))
        assertFalse(PiiFirewall.isLikelySensitive("open spotify"))
    }

    @Test fun `M6 - redacts short 4 to 6 digit otp or 2fa codes`() {
        val out = PiiFirewall.scrub("your otp is 482913")
        assertTrue(out.contains("<code>"))
        assertFalse(out.contains("482913"))
        assertTrue(PiiFirewall.scrub("code 4829").contains("<code>"))
    }

    @Test fun `M6 - detects widened otp and 2fa terms`() {
        assertTrue(PiiFirewall.isLikelySensitive("enter the verification code"))
        assertTrue(PiiFirewall.isLikelySensitive("your 2FA is ready"))
        assertTrue(PiiFirewall.isLikelySensitive("one time password sent"))
        assertTrue(PiiFirewall.isLikelySensitive("security code below"))
    }
}
