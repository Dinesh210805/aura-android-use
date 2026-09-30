package com.aura.aura_ui.agent.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingStore : LearningsStore {
    val paths = mutableListOf<Triple<String, String, List<String>>>()
    val facts = mutableListOf<Pair<FactKind, List<String>>>()
    var lastSource: String? = null
    var lastGoalLabel: String? = null
    var lastEndsAt: String? = null
    override suspend fun hintsFor(appPackage: String, goalType: String) = ""
    override suspend fun appHints(appPackage: String, installedAppVersion: String?) = ""
    override suspend fun recordVerifiedPath(
        appPackage: String,
        goalType: String,
        steps: List<String>,
        recoveries: List<String>,
        appVersion: String,
        source: String,
        goalLabel: String,
        endsAt: String,
    ) {
        paths += Triple(appPackage, goalType, steps)
        lastSource = source
        lastGoalLabel = goalLabel
        lastEndsAt = endsAt
    }
    override suspend fun recordFacts(
        appPackage: String,
        kind: FactKind,
        texts: List<String>,
        appVersion: String,
        source: String,
    ) {
        facts += kind to texts
        lastSource = source
    }
}

private fun args(vararg pairs: Pair<String, String>) =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

class LearningsAccumulatorTest {

    @Test fun `verified session flushes path`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "send a whatsapp message")
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.onStep("tap", args("text" to "Chats"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        acc.flush()
        assertEquals(1, store.paths.size)
        assertEquals("com.whatsapp", store.paths.first().first)
        assertEquals("send_message", store.paths.first().second)
    }

    @Test fun `unverified session flushes NO path but keeps recovery pairs`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "search in spotify")
        acc.onStep("launch_app", args("package_name" to "com.spotify.music"), failed = false, screenChanged = true, foregroundApp = "com.spotify.music")
        // dead end: dispatched fine, screen unchanged
        acc.onStep("tap", args("text" to "Search"), failed = false, screenChanged = false, foregroundApp = "com.spotify.music")
        // recovery: next step lands
        acc.onStep("tap", args("som_id" to "12", "label" to "Search box"), failed = false, screenChanged = true, foregroundApp = "com.spotify.music")
        acc.flush() // no markVerified()
        assertTrue(store.paths.isEmpty())
        assertEquals(1, store.facts.size)
        assertEquals(FactKind.RECOVERY, store.facts.first().first)
        assertTrue(store.facts.first().second.single().contains("worked instead"))
    }

    @Test fun `failed tool then success forms recovery`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "go to settings")
        acc.onStep("tap", args("text" to "Settings"), failed = true, screenChanged = null, foregroundApp = "com.android.settings")
        acc.onStep("tap", args("som_id" to "3", "label" to "Settings"), failed = false, screenChanged = true, foregroundApp = "com.android.settings")
        acc.flush()
        assertEquals(FactKind.RECOVERY, store.facts.single().first)
    }

    @Test fun `flush resets state`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "open whatsapp chats")
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.onStep("tap", args("text" to "Chats"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        acc.flush()
        acc.flush() // second flush: nothing accumulated
        assertEquals(1, store.paths.size)
    }

    @Test fun `client label becomes source and reason text drives goal when no goal set`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = null, defaultSource = "mcp-client")
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp", sourceLabel = "Claude Code")
        acc.onStep("tap", args("text" to "Chats"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        acc.flush(reasonText = "sent a whatsapp message to a contact")
        assertEquals("Claude Code", store.lastSource)
        assertEquals("send_message", store.paths.single().second)
    }

    @Test fun `explicit goal type wins over classification`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = null)
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.onStep("tap", args("text" to "Chats"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        acc.flush(reasonText = "sent a message", explicitGoalType = "navigate")
        assertEquals("navigate", store.paths.single().second)
    }

    @Test fun `trail is capped at maxSteps dropping oldest`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "navigate somewhere in the menu", maxSteps = 3)
        repeat(5) { i ->
            acc.onStep("tap", args("text" to "Item$i"), failed = false, screenChanged = true, foregroundApp = "com.app.x")
        }
        acc.markVerified()
        acc.flush()
        assertEquals(3, store.paths.single().third.size)
        assertTrue(store.paths.single().third.first().contains("Item2"))
    }

    @Test fun `flush returns a summary only when a path was written`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "open the shopping app")
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.onStep("tap", args("text" to "Chats"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        val summary = acc.flush()
        assertEquals("open_app", summary?.goalType)
        assertEquals(2, summary?.steps?.size)
        // unverified second round returns null
        acc.onStep("tap", args("som_id" to "1"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        assertEquals(null, acc.flush())
    }

    // ── Memory-usefulness pass ──

    @Test fun `a verified one-step path is not recorded - it teaches nothing`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "open whatsapp chats")
        acc.onStep("launch_app", args("package_name" to "com.whatsapp"), failed = false, screenChanged = true, foregroundApp = "com.whatsapp")
        acc.markVerified()
        assertEquals(null, acc.flush())
        assertTrue(store.paths.isEmpty())
    }

    @Test fun `goal label and destination anchor ride the flush`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "check battery usage")
        acc.onStep(
            "launch_app", args("package_name" to "com.android.settings"),
            failed = false, screenChanged = true, foregroundApp = "com.android.settings", topLabel = "Settings",
        )
        acc.onStep(
            "tap", args("text" to "Battery"),
            failed = false, screenChanged = true, foregroundApp = "com.android.settings", topLabel = "Battery usage",
        )
        acc.markVerified()
        acc.flush()
        assertEquals("check battery usage", store.lastGoalLabel)
        assertEquals("Battery usage", store.lastEndsAt)
    }

    @Test fun `goal label is neutralized before storage`() = runTest {
        val store = RecordingStore()
        val acc = LearningsAccumulator(store, goal = "email bob@gmail.com <script>now</script>")
        acc.onStep("launch_app", args("package_name" to "com.google.android.gm"), failed = false, screenChanged = true, foregroundApp = "com.google.android.gm")
        acc.onStep("tap", args("text" to "Compose"), failed = false, screenChanged = true, foregroundApp = "com.google.android.gm")
        acc.markVerified()
        acc.flush()
        val label = store.lastGoalLabel.orEmpty()
        assertTrue("email must be scrubbed", !label.contains("bob@gmail.com"))
        assertTrue("markup must be stripped", !label.contains("<script>"))
    }
}
