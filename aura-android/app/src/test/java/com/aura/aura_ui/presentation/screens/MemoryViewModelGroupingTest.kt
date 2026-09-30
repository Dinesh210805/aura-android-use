package com.aura.aura_ui.presentation.screens

import com.aura.aura_ui.agent.memory.AppFactEntry
import com.aura.aura_ui.agent.memory.EncryptedLearningsStore
import com.aura.aura_ui.agent.memory.FactKind
import com.aura.aura_ui.agent.memory.LearningEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 2026-07-17 — per-app Memory UI: pure grouping logic. */
class MemoryViewModelGroupingTest {

    @Test
    fun `groups sorted by label with facts split by kind`() {
        val byApp = mapOf(
            "com.whatsapp" to EncryptedLearningsStore.AppLessons(
                paths = listOf(LearningEntry("com.whatsapp", "send_message", listOf("s"), recordedAtEpochMs = 1L)),
                facts = listOf(
                    AppFactEntry("com.whatsapp", FactKind.RECOVERY, "a led nowhere → b worked instead", recordedAtEpochMs = 1L),
                    AppFactEntry("com.whatsapp", FactKind.QUIRK, "slow splash", count = 2, recordedAtEpochMs = 1L),
                ),
            ),
            "com.spotify.music" to EncryptedLearningsStore.AppLessons(paths = emptyList(), facts = emptyList()),
        )
        val groups = groupLessons(byApp) { pkg -> if (pkg == "com.whatsapp") "WhatsApp" else "Spotify" }
        assertEquals(listOf("Spotify", "WhatsApp"), groups.map { it.appLabel })
        val wa = groups.single { it.appLabel == "WhatsApp" }
        assertEquals(1, wa.paths.size)
        assertEquals(1, wa.recoveries.size)
        assertEquals(1, wa.quirks.size)
        assertEquals(0, wa.antiPatterns.size)
    }

    @Test
    fun `paths sorted by successCount within an app`() {
        val byApp = mapOf(
            "com.x" to EncryptedLearningsStore.AppLessons(
                paths = listOf(
                    LearningEntry("com.x", "navigate", listOf("weak"), recordedAtEpochMs = 1L, successCount = 1),
                    LearningEntry("com.x", "navigate", listOf("strong"), recordedAtEpochMs = 2L, successCount = 9),
                ),
                facts = emptyList(),
            ),
        )
        val groups = groupLessons(byApp) { it }
        assertEquals(listOf("strong"), groups.single().paths.first().steps)
    }
}
