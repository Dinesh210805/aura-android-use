package com.aura.aura_ui.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalClassifierTest {
    @Test fun `classifies common goal types`() {
        assertEquals("play_media", GoalClassifier.classify("play my liked songs"))
        assertEquals("send_message", GoalClassifier.classify("text mom I'm late"))
        assertEquals("open_app", GoalClassifier.classify("open spotify"))
        assertEquals("search", GoalClassifier.classify("search for pasta recipes"))
        assertEquals("other", GoalClassifier.classify("hmm"))
    }

    @Test fun `resolves app from utterance`() {
        assertEquals("com.spotify.music", AppPackageResolver.resolve("play music on spotify"))
        assertEquals("unknown", AppPackageResolver.resolve("do a thing"))
    }

    @Test fun `resolves app from observed args even if utterance is vague`() {
        assertEquals("com.whatsapp", AppPackageResolver.resolve("message her", observed = listOf("whatsapp")))
    }
}
