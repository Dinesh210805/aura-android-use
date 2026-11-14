package com.aura.aura_ui.agentcore

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeBlocksTest {
    @Test
    fun `a reply splits at its fences and the code keeps its whitespace`() {
        val reply = "Run this:\n```kotlin\nfun main() {\n    println(\"hi\")\n}\n```\nThen check."
        assertEquals(
            listOf(Block(false, "Run this:"), Block(true, "fun main() {\n    println(\"hi\")\n}"), Block(false, "Then check.")),
            blocks(reply),
        )
    }

    @Test
    fun `a fence still open while streaming runs to the end`() {
        assertEquals(listOf(Block(false, "Here:"), Block(true, "ls -la")), blocks("Here:\n```\nls -la"))
    }

    @Test
    fun `the files sheet lists each sent file once, newest first`() {
        fun sent(id: Long, json: String) = ItemRow(rowid = id, threadId = "t", atMs = id, kind = ItemKind.USER.name, text = "", attachmentsJson = json)
        val a = """{"uri":"content://a","displayName":"a.jpg","mimeType":"image/jpeg","sizeBytes":1}"""
        val b = """{"uri":"content://b","displayName":"b.pdf","mimeType":"application/pdf","sizeBytes":1}"""
        val files = chatFiles(listOf(sent(1, "[$a]"), sent(2, "[$b,$a]")))
        assertEquals(listOf("content://b", "content://a"), files.map { it.uri })
    }
}
