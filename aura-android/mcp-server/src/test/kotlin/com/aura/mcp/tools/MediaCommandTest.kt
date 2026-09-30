package com.aura.mcp.tools

import com.aura.mcp.bridge.MediaCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaCommandTest {

    @Test
    fun `parses known commands case-insensitively`() {
        assertEquals(MediaCommand.PLAY, MediaCommand.parse("play"))
        assertEquals(MediaCommand.PAUSE, MediaCommand.parse("PAUSE"))
        assertEquals(MediaCommand.PLAY_PAUSE, MediaCommand.parse(" play_pause "))
        assertEquals(MediaCommand.NEXT, MediaCommand.parse("next"))
        assertEquals(MediaCommand.PREVIOUS, MediaCommand.parse("previous"))
        assertEquals(MediaCommand.STOP, MediaCommand.parse("stop"))
    }

    @Test
    fun `rejects unknown or missing commands`() {
        assertNull(MediaCommand.parse("rewind"))
        assertNull(MediaCommand.parse(""))
        assertNull(MediaCommand.parse(null))
    }
}
