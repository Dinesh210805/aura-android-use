package com.aura.aura_ui.agentcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkEgressTest {

    @Test
    fun `a link carrying only the user's words stays on the phone`() {
        assertTrue(ToolHub.dictatedOnly("https://wa.me/919876543210?text=hi%20amma", "send hi amma to Amma on WhatsApp"))
        assertTrue(ToolHub.dictatedOnly("https://wa.me/919876543210", "message Amma"))
        assertTrue(ToolHub.dictatedOnly("spotify://search?q=lofi", "play lofi on spotify"))
    }

    @Test
    fun `a link carrying text the user did not say counts as sending out`() {
        assertFalse(ToolHub.dictatedOnly("https://wa.me/919876543210?text=OTP%20is%20482910", "reply to the last message"))
        assertFalse(ToolHub.dictatedOnly("myapp://open#token=abc123secret", "open my app"))
    }

    @Test
    fun `data hidden in a path, a parameter name, a short number or a substring is caught`() {
        assertFalse(ToolHub.dictatedOnly("myapp://open/482910", "open my app"))
        assertFalse(ToolHub.dictatedOnly("myapp://open?482910=1", "open my app"))
        assertFalse(ToolHub.dictatedOnly("https://wa.me/919876543210?text=a", "send hi amma"))
        assertFalse(ToolHub.dictatedOnly("https://wa.me/919876543210?text=4111111111111111", "pay"))
        // A long number outside a recipient slot, data in the host, or a user@ part.
        assertFalse(ToolHub.dictatedOnly("myapp://open/home/123456789012", "open my app"))
        assertFalse(ToolHub.dictatedOnly("https://wa.me/919876543210?text=123456789012", "send it"))
        assertFalse(ToolHub.dictatedOnly("https://acct123456789.example.app/open", "open my app"))
        assertFalse(ToolHub.dictatedOnly("https://secret:pw@example.app/open", "open my app"))
        assertTrue(ToolHub.dictatedOnly("smsto://compose?to=919876543210", "text Amma"))
        // A number the user said is theirs to send.
        assertTrue(ToolHub.dictatedOnly("https://wa.me/919876543210?text=room%20402", "tell Amma room 402"))
    }
}
