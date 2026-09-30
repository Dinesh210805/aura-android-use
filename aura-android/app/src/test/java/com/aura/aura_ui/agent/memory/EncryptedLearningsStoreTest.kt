package com.aura.aura_ui.agent.memory

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EncryptedLearningsStoreTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private fun store(name: String) = EncryptedLearningsStore(EncryptedJsonStore(ctx, name))

    @Test fun `record then hintsFor returns a stale-caveated hint`() = runTest {
        val s = store("learn_rt")
        s.recordVerifiedPath("com.spotify.music", "play_media", listOf("tap \"Library\"", "tap \"Liked Songs\""))
        val hint = s.hintsFor("com.spotify.music", "play_media")
        assertTrue(hint.contains("Library"))
        assertTrue(hint.contains("MAY BE STALE"))
    }

    @Test fun `nothing learned yields empty string`() = runTest {
        assertEquals("", store("learn_empty").hintsFor("com.x", "open_app"))
    }

    @Test fun `re-recording an identical path bumps successCount instead of duplicating`() = runTest {
        val s = store("learn_bump")
        repeat(3) { s.recordVerifiedPath("com.x", "navigate", listOf("tap \"Menu\"")) }
        val entries = s.allEntries().filter { it.appPackage == "com.x" }
        assertEquals(1, entries.size)
        assertEquals(3, entries.single().successCount)
    }

    @Test fun `empty steps are not recorded`() = runTest {
        val s = store("learn_emptysteps")
        s.recordVerifiedPath("com.x", "other", emptyList())
        assertTrue(s.allEntries().isEmpty())
    }

    @Test fun `steps are scrubbed at the store boundary`() = runTest {
        val s = store("learn_scrub")
        s.recordVerifiedPath("com.x", "send_message", listOf("type_text bob@gmail.com"))
        assertTrue(s.allEntries().single().steps.single().contains("<email>"))
    }

    @Test fun `per-key cap keeps the most recent`() = runTest {
        var t = 0L
        val s = EncryptedLearningsStore(EncryptedJsonStore(ctx, "learn_cap"), clock = { t++ })
        repeat(EncryptedLearningsStore.MAX_PER_KEY + 5) { i ->
            s.recordVerifiedPath("com.x", "navigate", listOf("tap \"item$i\""))
        }
        assertEquals(EncryptedLearningsStore.MAX_PER_KEY, s.allEntries().count { it.appPackage == "com.x" })
    }

    // ── M4: the `unknown` bucket is a cross-app dumping ground — one app's path
    //        presented as "a path that worked before" inside a different app is
    //        prompt pollution. It is written (for the inspector) but never replayed. ──

    // ── Recovery lessons: stored with the path, rendered as their own hint lines ──

    @Test fun `recoveries are persisted and rendered as recovery hints`() = runTest {
        val s = store("learn_recovery")
        s.recordVerifiedPath(
            "in.amazon.mShop", "purchase",
            steps = listOf("launch_app", "tap \"Search\"", "tap \"iPhone 17 Pro suggestion\""),
            recoveries = listOf("press_enter led nowhere → tap \"iPhone 17 Pro suggestion\" worked instead"),
        )
        val hint = s.hintsFor("in.amazon.mShop", "purchase")
        assertTrue("recovery lesson must be rendered", hint.contains("led nowhere"))
        assertTrue(hint.contains("Recovery"))
    }

    @Test fun `recoveries are scrubbed and capped at the store boundary`() = runTest {
        val s = store("learn_recovery_scrub")
        s.recordVerifiedPath(
            "com.x", "send_message",
            steps = listOf("tap \"Compose\""),
            recoveries = (1..10).map { "step$it failed → tap \"to bob@gmail.com\" worked" },
        )
        val entry = s.allEntries().single()
        assertTrue("recoveries must be capped", entry.recoveries.size <= EncryptedLearningsStore.MAX_RECOVERIES_PER_ENTRY)
        assertTrue("recoveries must be PII-scrubbed", entry.recoveries.all { it.contains("<email>") })
    }

    @Test fun `unknown-bucket hints are never surfaced`() = runTest {
        val s = store("learn_unknown")
        s.recordVerifiedPath("unknown", "navigate", listOf("tap \"Menu\""))
        assertEquals("", s.hintsFor("unknown", "navigate"))
        // Still visible to the Settings → Memory inspector.
        assertTrue(s.allEntries().any { it.appPackage == "unknown" })
    }

    // ── Schema v2 (spec 2026-07-17): provenance/appVersion, app facts, per-app view ──

    @Test fun `v1 json rows read with defaulted appVersion and source`() = runTest {
        val raw = EncryptedJsonStore(ctx, "learn_v1json")
        raw.writeRaw(
            "send_message__com.whatsapp",
            """[{"appPackage":"com.whatsapp","goalType":"send_message","steps":["launch_app \"WhatsApp\""],"recordedAtEpochMs":1}]""",
        )
        val entries = EncryptedLearningsStore(raw).allEntries()
        assertEquals(1, entries.size)
        assertEquals("", entries.first().appVersion)
        assertEquals("on-device-agent", entries.first().source)
    }

    @Test fun `recordFacts dedupes by text and reinforces count`() = runTest {
        val s = store("learn_facts_dedupe")
        s.recordFacts("com.spotify.music", FactKind.RECOVERY, listOf("tap \"Search\" led nowhere → tap som worked instead"))
        s.recordFacts("com.spotify.music", FactKind.RECOVERY, listOf("tap \"Search\" led nowhere → tap som worked instead"))
        val facts = s.lessonsByApp().getValue("com.spotify.music").facts
        assertEquals(1, facts.size)
        assertEquals(2, facts.first().count)
    }

    @Test fun `quirks served only when confirmed twice, recoveries served immediately`() = runTest {
        val s = store("learn_facts_confirm")
        s.recordFacts("com.app.x", FactKind.QUIRK, listOf("shows 3s splash on launch"))
        assertTrue(!s.appHints("com.app.x").contains("splash"))
        s.recordFacts("com.app.x", FactKind.QUIRK, listOf("shows 3s splash on launch"))
        assertTrue(s.appHints("com.app.x").contains("splash"))
        s.recordFacts("com.app.x", FactKind.RECOVERY, listOf("press_enter led nowhere → tap \"Go\" worked instead"))
        assertTrue(s.appHints("com.app.x").contains("worked instead"))
    }

    @Test fun `goal-optional hintsFor merges top paths across goals plus facts`() = runTest {
        val s = store("learn_app_hints")
        s.recordVerifiedPath("com.whatsapp", "send_message", listOf("launch_app \"WhatsApp\"", "tap \"Chats\""))
        s.recordVerifiedPath("com.whatsapp", "search", listOf("launch_app \"WhatsApp\"", "tap \"Search\""))
        val hints = s.appHints("com.whatsapp")
        assertTrue(hints.contains("Chats"))
        assertTrue(hints.contains("Search"))
        assertTrue(hints.contains("MAY BE STALE"))
    }

    @Test fun `forgetApp removes paths and facts for that app only`() = runTest {
        val s = store("learn_forget")
        s.recordVerifiedPath("com.whatsapp", "send_message", listOf("launch_app \"WhatsApp\""))
        s.recordFacts("com.whatsapp", FactKind.RECOVERY, listOf("a led nowhere → b worked instead"))
        s.recordVerifiedPath("com.spotify.music", "play_media", listOf("launch_app \"Spotify\""))
        s.forgetApp("com.whatsapp")
        assertTrue(!s.lessonsByApp().containsKey("com.whatsapp"))
        assertTrue(s.lessonsByApp().containsKey("com.spotify.music"))
    }

    @Test fun `deletePath and deleteFact remove single entries`() = runTest {
        val s = store("learn_delete")
        s.recordVerifiedPath("com.app.x", "navigate", listOf("tap \"Settings\""))
        val recorded = s.lessonsByApp().getValue("com.app.x").paths.single().steps
        s.deletePath("com.app.x", "navigate", recorded)
        assertTrue(s.lessonsByApp()["com.app.x"]?.paths.isNullOrEmpty())
        s.recordFacts("com.app.x", FactKind.RECOVERY, listOf("a led nowhere → b worked instead"))
        val fact = s.lessonsByApp().getValue("com.app.x").facts.single()
        s.deleteFact("com.app.x", FactKind.RECOVERY, fact.text)
        assertTrue(s.lessonsByApp()["com.app.x"]?.facts.isNullOrEmpty())
    }

    // ── Memory-usefulness pass: min-step serve filter, freshness, version delta,
    //    goal label + destination anchor ──

    @Test fun `one-step paths are stored for the inspector but never served as hints`() = runTest {
        val s = store("learn_minsteps")
        s.recordVerifiedPath("com.x", "open_app", listOf("launch_app \"X\""))
        assertTrue(s.allEntries().isNotEmpty()) // inspector still sees it
        assertEquals("", s.appHints("com.x"))
        assertEquals("", s.hintsFor("com.x", "open_app"))
    }

    @Test fun `hints render freshness from the entry age`() = runTest {
        var now = 0L
        val s = EncryptedLearningsStore(EncryptedJsonStore(ctx, "learn_age"), clock = { now })
        s.recordVerifiedPath("com.x", "navigate", listOf("tap \"A\"", "tap \"B\""))
        assertTrue(s.appHints("com.x").contains("verified today"))
        now = 2L * 24 * 60 * 60 * 1000 + 1
        assertTrue(s.appHints("com.x").contains("verified 2d ago"))
    }

    @Test fun `installed app version mismatch is flagged, match is silent`() = runTest {
        val s = store("learn_version")
        s.recordVerifiedPath("com.x", "navigate", listOf("tap \"A\"", "tap \"B\""), appVersion = "15.2")
        assertTrue(s.appHints("com.x", installedAppVersion = "16.0").contains("layout may have changed"))
        assertTrue(!s.appHints("com.x", installedAppVersion = "15.2").contains("layout may have changed"))
        assertTrue(!s.appHints("com.x").contains("layout may have changed"))
    }

    @Test fun `goal label and destination anchor are rendered when present`() = runTest {
        val s = store("learn_goal_dest")
        s.recordVerifiedPath(
            "com.whatsapp", "send_message", listOf("launch_app \"WhatsApp\"", "tap \"Chats\""),
            goalLabel = "send a whatsapp message", endsAt = "Chats",
        )
        val hints = s.appHints("com.whatsapp")
        assertTrue(hints.contains("goal was \"send a whatsapp message\""))
        assertTrue(hints.contains("ends at \"Chats\""))
    }

    @Test fun `facts are scrubbed at the store boundary and never written for unknown`() = runTest {
        val s = store("learn_facts_scrub")
        s.recordFacts("com.app.x", FactKind.RECOVERY, listOf("type_text bob@gmail.com led nowhere → tap worked instead"))
        assertTrue(s.lessonsByApp().getValue("com.app.x").facts.single().text.contains("<email>"))
        s.recordFacts("unknown", FactKind.QUIRK, listOf("some quirk"))
        assertTrue(!s.lessonsByApp().containsKey("unknown"))
    }
}
