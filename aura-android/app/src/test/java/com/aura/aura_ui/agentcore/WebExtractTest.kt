package com.aura.aura_ui.agentcore

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebExtractTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `only public addresses are reachable`() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, PublicDns.isPublic(ip(it))) }
        listOf(
            "127.0.0.1", "10.0.0.5", "172.20.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "::1", "fe80::1", "fd00::1", "::ffff:192.168.1.1", "224.0.0.1",
        ).forEach { assertFalse(it, PublicDns.isPublic(ip(it))) }
    }

    @Test
    fun `a page reads as its text without scripts or markup`() {
        val html = "<html><head><title>Rates &amp; Fees</title><script>var x=1</script></head><body><nav>Home</nav>" +
            "<h1>Fees</h1><table><tr><td>Basic</td><td>&#8377;99</td></tr></table><p>Billed monthly.</p></body></html>"
        assertEquals("Rates & Fees", WebText.title(html))
        assertEquals("Fees\nBasic ₹99\nBilled monthly.", WebText.fromHtml(html))
    }

    @Test
    fun `long text pages at line breaks`() {
        val text = (1..30).joinToString("\n") { "line $it ".repeat(10) }
        val pages = WebText.pages(text, 1_000)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 1_000 })
        assertEquals(text.filterNot(Char::isWhitespace), pages.joinToString("").filterNot(Char::isWhitespace))
    }

    @Test
    fun `reading a page after a notification asks first, like a search`() {
        val spec = webTools(WebExtract()).single().spec
        assertTrue(spec.egress)
        val taint = TurnTaint().apply { mark(Origin.NOTIFICATION) }
        val request = ToolCallRequest("web_extract", spec.risk, spec.origin, """{"url":"https://example.com/?q=otp"}""", spec.egress)
        assertTrue(SafetyGate.evaluate(request, RunMode.FOREGROUND, taint) is Verdict.Ask)
    }
}
