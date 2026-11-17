package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPictureTest {

    private val frame = """{"v":1,"pkg":"app","w":1080,"h":2400,"n":3,"e":[
        [0,0,1080,2400,"","","FrameLayout"],
        [40,200,1040,320,"Settings","*","Button"],
        [40,400,40,500,"zero width","*","Button"]]}"""

    @Test
    fun `a frame parses into boxes, largest first, dropping empty ones`() {
        val parsed = requireNotNull(ScreenPicture.parse(frame))
        assertEquals(1080, parsed.width)
        assertEquals(2400, parsed.height)
        assertEquals(listOf("", "Settings"), parsed.boxes.map { it.label })
        assertTrue(parsed.boxes[1].tappable)
    }

    @Test
    fun `an unreadable or missing frame gives null, so the page shows the text`() {
        assertNull(ScreenPicture.parse(null))
        assertNull(ScreenPicture.parse("not json"))
        assertNull(ScreenPicture.parse("""{"w":0,"h":10,"e":[]}"""))
    }
}
