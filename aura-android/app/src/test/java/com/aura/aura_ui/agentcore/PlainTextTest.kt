package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test
    fun `strips markdown but keeps the words`() {
        val md = "## Plan\n- **Open** [WhatsApp](https://wa.me)\n- type `hi` in *chat*\n```kotlin\nval x = 1\n```"
        assertEquals("Plan\n• Open WhatsApp\n• type hi in chat\nval x = 1", plainText(md))
    }

    @Test
    fun `leaves snake_case and arithmetic alone`() {
        assertEquals("use file_name and 2 * 3 * 4", plainText("use file_name and 2 * 3 * 4"))
    }
}
